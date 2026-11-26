import http.client
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
LOADER = importlib.machinery.SourceFileLoader("blockagent_planner", str(ROOT / "bin/blockagent-planner"))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
planner = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(planner)
REQUEST = {"goal": "蓋屋", "context": "地點 A 到不了"}
PLAN = {"recommendation": "去 B 地", "steps": ["先預檢地點 B"]}


class PlannerTest(unittest.TestCase):
    def test_request_and_response_limits(self):
        self.assertEqual(REQUEST, planner.checked_request(REQUEST))
        self.assertEqual(PLAN, planner.checked_plan(json.dumps(PLAN)))
        self.assertEqual({"goal": "蓋屋"}, planner.checked_request({"goal": "蓋屋"}), "context 可以不寫")
        for request in ({}, {"context": "x"}, {"goal": "蓋" * 501}, dict(REQUEST, context="地" * 4000),
                        dict(REQUEST, model="custom")):
            with self.assertRaisesRegex(planner.PlannerError, "欄位|字元|只能") as error:
                planner.checked_request(request)
            self.assertEqual("INVALID_ARGUMENT", error.exception.code)
        for invalid in (None, "plain text", json.dumps(dict(PLAN, steps=[])), json.dumps(dict(PLAN, steps=["走"] * 6))):
            with self.assertRaises(planner.PlannerError) as error:
                planner.checked_plan(invalid)
            self.assertEqual("INVALID_OUTPUT", error.exception.code)

    def test_codex_uses_only_summary_and_logs_usage(self):
        events = [
            {"type": "item.completed", "item": {"type": "agent_message", "text": json.dumps(PLAN)}},
            {"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 50, "output_tokens": 20}},
        ]
        result = subprocess.CompletedProcess([], 0, "\n".join(map(json.dumps, events)), "")
        usage = {}
        with patch.dict(os.environ, {"CODEX_HOME": "/private/codex", "BLOCKAGENT_PORT": "48800",
                                     "BLOCKAGENT_TOKEN": "canary", "BLOCKAGENT_HOME": "/private/game"}):
            with patch.object(planner.subprocess, "run", return_value=result) as run:
                self.assertEqual(PLAN, planner.codex_plan("gpt-6.2-sol", REQUEST, usage))
        self.assertEqual(events[1]["usage"], usage)
        argv = run.call_args.args[0]
        options = run.call_args.kwargs
        self.assertTrue({"--ignore-user-config", "--ephemeral", "mcp_servers={}", 'sandbox_mode="read-only"',
                         "features.shell_tool=false", "agents.enabled=false", 'web_search="disabled"'} <= set(argv))
        self.assertEqual("/private/codex", options["env"]["CODEX_HOME"])
        self.assertFalse(any(key.startswith("BLOCKAGENT_") for key in options["env"]))
        self.assertEqual(REQUEST, json.loads(options["input"]))
        self.assertEqual(120, options["timeout"])

    def test_codex_failures_have_safe_messages(self):
        responses = {
            "CLI_FAILED": subprocess.CompletedProcess([], 2, "", "secret diagnostics"),
            "AUTH_FAILED": subprocess.CompletedProcess([], 1, "", "HTTP 401 secret diagnostics"),
            "INVALID_OUTPUT": subprocess.CompletedProcess([], 0, "bad result", ""),
            "TIMEOUT": subprocess.TimeoutExpired("codex", 120),
        }
        for code, response in responses.items():
            kwargs = {"side_effect" if isinstance(response, Exception) else "return_value": response}
            with patch.object(planner.subprocess, "run", **kwargs):
                with self.assertRaises(planner.PlannerError) as error:
                    planner.codex_plan("gpt-6.2-sol", REQUEST, {})
            self.assertEqual(code, error.exception.code)
            self.assertNotIn("secret", str(error.exception))

    def test_async_single_flight_delivers_to_game(self):
        started, release = threading.Event(), threading.Event()
        seen = []

        def backend(model, request, usage):
            seen.append(request)
            usage["input_tokens"] = 11
            started.set()
            release.wait(2)
            return PLAN

        bridge = FakeBridge("[self]\nhp=20", {"world": 1, "revision": 2})
        output = io.StringIO()
        server = planner.Server("gpt-6.2-sol", backend, bridge, output)
        params = {"name": "request_plan", "arguments": REQUEST}
        with patch.object(planner, "log_usage") as log:
            with server.pool:
                server.dispatch({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
                server.dispatch({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": params})
                self.assertTrue(started.wait(2))
                # 規劃還在跑，第一個呼叫已經回應了
                self.assertIn('"id": 2', output.getvalue())
                server.dispatch({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": params})
                release.set()
        rows = {row["id"]: row["result"] for row in map(json.loads, output.getvalue().splitlines())}
        self.assertEqual([planner.TOOL], rows[1]["tools"])
        self.assertEqual("pending", json.loads(rows[2]["content"][0]["text"])["status"])
        self.assertFalse(rows[2]["isError"])
        self.assertEqual("BUSY", json.loads(rows[3]["content"][0]["text"])["code"])
        self.assertEqual([dict(REQUEST, game_snapshot="[self]\nhp=20")], seen)
        self.assertEqual([dict(PLAN, goal="蓋屋", basis={"world": 1, "revision": 2})], bridge.delivered)
        record = log.call_args.args[0]
        self.assertEqual({"input_tokens": 11}, record["usage"])
        self.assertTrue(record["delivered"])
        self.assertNotIn("context", record)

    def test_failures_are_delivered_as_errors(self):
        def backend(model, request, usage):
            self.assertEqual("unavailable", request["game_snapshot"])
            raise planner.PlannerError("TIMEOUT", "規劃超過 120 秒，請先依目前資訊繼續。")

        bridge = FakeBridge(None)
        bridge.fail_delivery = True
        server = planner.Server("gpt-6.2-sol", backend, bridge, io.StringIO())
        with patch.object(planner, "log_usage") as log:
            server.run_plan({"goal": "找村莊"})
        self.assertEqual("TIMEOUT", bridge.delivered[0]["error"]["code"])
        self.assertEqual("找村莊", bridge.delivered[0]["goal"])
        self.assertEqual("TIMEOUT", log.call_args.args[0]["result"])
        self.assertFalse(log.call_args.args[0]["delivered"])

    def test_http_exceptions_do_not_drop_usage_log(self):
        class BrokenHttpBridge:
            def context(self):
                raise http.client.BadStatusLine("bad")

            def deliver(self, payload):
                raise http.client.IncompleteRead(b"")

        def backend(model, request, usage):
            self.assertEqual("unavailable", request["game_snapshot"])
            return PLAN

        server = planner.Server("gpt-6.2-sol", backend, BrokenHttpBridge(), io.StringIO())
        with patch.object(planner, "log_usage") as log:
            server.run_plan({"goal": "過夜"})
        self.assertEqual("ok", log.call_args.args[0]["result"])
        self.assertFalse(log.call_args.args[0]["delivered"])

    def test_cli_rejects_out_of_range_ports(self):
        import sys
        for bad_port in ("0", "65536", "99999"):
            res = subprocess.run([sys.executable, str(ROOT / "bin/blockagent-planner"), "gpt-6.2-sol", bad_port],
                                 capture_output=True, text=True, env={"PATH": os.environ.get("PATH", "")})
            self.assertNotEqual(0, res.returncode, bad_port)

    def test_codex_reuses_stable_workdir_and_orders_snapshot_first(self):
        import tempfile
        events = [
            {"type": "item.completed", "item": {"type": "agent_message", "text": json.dumps(PLAN)}},
            {"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 80, "output_tokens": 20}},
        ]
        result = subprocess.CompletedProcess([], 0, "\n".join(map(json.dumps, events)), "")
        with tempfile.TemporaryDirectory() as codex_home:
            expected_workdir = str(Path(codex_home, "planner-workdir"))
            with patch.dict(os.environ, {"CODEX_HOME": codex_home}):
                with patch.object(planner.subprocess, "run", return_value=result) as run:
                    planner.codex_plan("gpt-6.2-sol", {"goal": "蓋屋", "game_snapshot": "[player_tools]\ngoto"}, {})
                    planner.codex_plan("gpt-6.2-sol", {"goal": "挖礦", "game_snapshot": "[player_tools]\ngoto"}, {})
            self.assertEqual(2, run.call_count)
            for call in run.call_args_list:
                self.assertEqual(expected_workdir, call.kwargs["cwd"])
                argv = call.args[0]
                self.assertIn(str(Path(expected_workdir, "plan.json")), argv)
                self.assertIn(f'model_instructions_file={json.dumps(str(Path(expected_workdir, "instructions.txt")))}', argv)
                payload = json.loads(call.kwargs["input"])
                self.assertEqual("game_snapshot", list(payload.keys())[0])


class FakeBridge:
    def __init__(self, snapshot, basis=None):
        self.snapshot = snapshot
        self.basis = basis
        self.delivered = []
        self.fail_delivery = False

    def context(self):
        if self.snapshot is None:
            raise OSError("game is gone")
        return self.snapshot, self.basis

    def deliver(self, payload):
        self.delivered.append(payload)
        if self.fail_delivery:
            raise OSError("game is gone")


if __name__ == "__main__":
    unittest.main()
