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
PLAN = {"goal": "蓋屋", "recommendation": "去 B 地", "steps": ["先預檢地點 B"]}


class PlannerTest(unittest.TestCase):
    def test_request_and_response_limits(self):
        self.assertEqual(REQUEST, planner.checked_request(REQUEST))
        self.assertEqual(PLAN, planner.checked_plan(json.dumps(PLAN)))
        self.assertEqual({"goal": "蓋屋"}, planner.checked_request({"goal": "蓋屋"}), "context 可以不寫")
        self.assertEqual({}, planner.checked_request({}), "goal 不寫就由規劃模型挑")
        for request in ({"goal": " "}, {"goal": "蓋" * 501}, dict(REQUEST, context="地" * 4000),
                        dict(REQUEST, model="custom")):
            with self.assertRaisesRegex(planner.PlannerError, "欄位|字元|只能") as error:
                planner.checked_request(request)
            self.assertEqual("INVALID_ARGUMENT", error.exception.code)
        no_goal = {key: value for key, value in PLAN.items() if key != "goal"}
        for invalid in (None, "plain text", json.dumps(no_goal), json.dumps(dict(PLAN, goal=" ")),
                        json.dumps(dict(PLAN, steps=[])), json.dumps(dict(PLAN, steps=["走"] * 6))):
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

    def test_goal_comes_from_planner_only_when_not_given(self):
        def backend(model, request, usage):
            return dict(PLAN, goal="挖到鑽石")

        bridge = FakeBridge("[self]\nhp=20")
        server = planner.Server("gpt-6.2-sol", backend, bridge, io.StringIO())
        with patch.object(planner, "log_usage"):
            server.run_plan({})
            server.run_plan({"goal": "蓋屋"})
        self.assertEqual(["挖到鑽石", "蓋屋"], [payload["goal"] for payload in bridge.delivered])

        def failing(model, request, usage):
            raise planner.PlannerError("TIMEOUT", "逾時")

        bridge = FakeBridge(None)
        with patch.object(planner, "log_usage"):
            planner.Server("gpt-6.2-sol", failing, bridge, io.StringIO()).run_plan({})
        self.assertEqual("下一個目標", bridge.delivered[0]["goal"])

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
            res = subprocess.run([sys.executable, str(ROOT / "bin/blockagent-planner"), "codex", "gpt-6.2-sol", bad_port],
                                 capture_output=True, text=True, env={"PATH": os.environ.get("PATH", "")})
            self.assertNotEqual(0, res.returncode, bad_port)

    def test_cli_rejects_unknown_backend(self):
        import sys
        res = subprocess.run([sys.executable, str(ROOT / "bin/blockagent-planner"), "gemini", "x", "48800"],
                             capture_output=True, text=True, env={"PATH": os.environ.get("PATH", "")})
        self.assertNotEqual(0, res.returncode)

    def test_claude_uses_no_tools_and_reads_structured_output(self):
        import tempfile
        output = [
            {"type": "system", "subtype": "init", "tools": ["StructuredOutput"]},
            {"type": "result", "subtype": "success", "is_error": False, "result": "ok", "structured_output": PLAN,
             "usage": {"input_tokens": 2, "cache_read_input_tokens": 70, "cache_creation_input_tokens": 30,
                       "output_tokens": 20}},
        ]
        usage = {}
        with tempfile.TemporaryDirectory() as home:
            with patch.object(planner, "PLANNER_HOME", home), \
                    patch.dict(os.environ, {"BLOCKAGENT_TOKEN": "canary", "CLAUDECODE": "1"}):
                with patch.object(planner.subprocess, "run",
                                  return_value=subprocess.CompletedProcess([], 0, json.dumps(output), "")) as run:
                    self.assertEqual(PLAN, planner.claude_plan("claude-sonnet-5-5", REQUEST, usage))
            self.assertEqual(str(Path(home, "planner-workdir")), run.call_args.kwargs["cwd"])
        self.assertEqual({"input_tokens": 102, "cached_input_tokens": 70, "output_tokens": 20}, usage)
        argv = run.call_args.args[0]
        options = run.call_args.kwargs
        self.assertEqual("", argv[argv.index("--tools") + 1])
        self.assertEqual('{"mcpServers":{}}', argv[argv.index("--mcp-config") + 1])
        self.assertEqual("", argv[argv.index("--setting-sources") + 1])
        self.assertTrue({"--strict-mcp-config", "--no-session-persistence", "--disable-slash-commands"} <= set(argv))
        self.assertEqual(planner.PLAN_SCHEMA, json.loads(argv[argv.index("--json-schema") + 1]))
        self.assertIn("Bash", json.loads(argv[argv.index("--settings") + 1])["permissions"]["deny"])
        self.assertFalse(any(key.startswith("BLOCKAGENT_") or key == "CLAUDECODE" for key in options["env"]))
        self.assertEqual(REQUEST, json.loads(options["input"]))

    def test_claude_failures_have_safe_messages(self):
        def failed(text):
            return json.dumps({"type": "result", "is_error": True, "result": text})
        responses = {
            "AUTH_FAILED": subprocess.CompletedProcess([], 1, failed("Failed to authenticate. API Error: 401 secret"), ""),
            "CLI_FAILED": subprocess.CompletedProcess([], 1, failed("secret model not found"), ""),
            "INVALID_OUTPUT": subprocess.CompletedProcess([], 0, json.dumps(
                {"type": "result", "is_error": False, "result": "bad", "structured_output": {"steps": []}}), ""),
            "TIMEOUT": subprocess.TimeoutExpired("claude", 120),
        }
        for code, response in responses.items():
            kwargs = {"side_effect" if isinstance(response, Exception) else "return_value": response}
            with patch.object(planner.subprocess, "run", **kwargs):
                with self.assertRaises(planner.PlannerError) as error:
                    planner.claude_plan("claude-sonnet-5-5", REQUEST, {})
            self.assertEqual(code, error.exception.code)
            self.assertNotIn("secret", str(error.exception))

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

    def test_loads_planner_prompt_from_prompts_dir(self):
        import tempfile
        expected = (ROOT / "prompts" / "planner.md").read_text(encoding="utf-8").strip()
        self.assertEqual(expected, planner.load_instructions())
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp, "missing.md")
            with self.assertRaises(planner.PlannerError) as err:
                planner.load_instructions(missing)
            self.assertEqual("CLI_FAILED", err.exception.code)
            empty = Path(tmp, "empty.md")
            empty.write_text("   \n", encoding="utf-8")
            with self.assertRaises(planner.PlannerError) as err:
                planner.load_instructions(empty)
            self.assertEqual("CLI_FAILED", err.exception.code)


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
