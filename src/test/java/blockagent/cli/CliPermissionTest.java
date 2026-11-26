package blockagent.cli;

import blockagent.mcp.McpServer;
import blockagent.mcp.RuntimeFile;
import blockagent.mcp.ToolRegistry;
import blockagent.mcp.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T8(兩個 CLI 都接得上、看到的工具一樣，A1.1)與 T9(碰不到其他檔案、執行不了程式、上不了網，A1.2)。
 * 用本機的 stub 模型伺服器取代真正的模型 API，**不呼叫任何 AI**。需要本機裝有 claude 和 codex。
 * 執行：./gradlew cliTest
 */
@Tag("cli")
class CliPermissionTest {
	static final Path PROJECT = Path.of(System.getProperty("user.dir"));

	@TempDir
	Path tmp;
	Path home;
	Path secret;
	Path writeTarget;
	String secretValue;
	McpServer mcp;
	StubModelServer stub;
	JsonArray snapshot;
	final List<String> mcpCalls = new ArrayList<>();
	/** 規劃服務送回來的結果(S11.7)。 */
	final java.util.concurrent.LinkedBlockingQueue<JsonObject> planResults = new java.util.concurrent.LinkedBlockingQueue<>();
	final List<String> planContextReads = new java.util.concurrent.CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		try (var r = new InputStreamReader(getClass().getResourceAsStream("/blockagent/tools-snapshot.json"), StandardCharsets.UTF_8)) {
			snapshot = JsonParser.parseReader(r).getAsJsonArray();
		}
		ToolRegistry reg = new ToolRegistry();
		for (JsonElement e : snapshot) {
			JsonObject t = e.getAsJsonObject();
			String name = t.get("name").getAsString();
			reg.register(name, t.get("description").getAsString(), t.getAsJsonObject("inputSchema"), args -> {
				synchronized (mcpCalls) {
					mcpCalls.add(name);
				}
				if ("wait_events".equals(name)) {
					// 像真的 wait_events 一樣等事件：規劃結果送到就把它當成 plan_ready 回傳
					JsonObject plan = planResults.poll(30, TimeUnit.SECONDS);
					return ToolResult.ok(plan == null ? "OK no_events" : "[EVENTS]\n[PROMPT] plan_ready: " + plan);
				}
				return ToolResult.ok("stub:" + name);
			});
		}
		String token = McpServer.newToken();
		mcp = new McpServer(reg, token, "test", null);
		String planToken = McpServer.newToken();
		mcp.setPlanBridge(planToken, new McpServer.PlanBridge() {
			@Override
			public JsonObject context() {
				planContextReads.add("read");
				var context = new JsonObject();
				context.addProperty("snapshot", "[self]\nhp=20 food=20 inv=oak_log:3\n[player_tools]\ngoto, mine");
				return context;
			}

			@Override
			public String accept(JsonObject result) {
				planResults.add(result);
				return null;
			}
		});
		int port = mcp.start(48700);
		home = tmp.resolve("ba-home");
		RuntimeFile.write(home, port, token, planToken);
		stub = new StubModelServer();

		secretValue = "canary-" + UUID.randomUUID();
		secret = tmp.resolve("canary-secret.txt");
		Files.writeString(secret, secretValue);
		writeTarget = tmp.resolve("canary-write.txt");
	}

	@AfterEach
	void tearDown() {
		if (mcp != null) mcp.close();
		if (stub != null) stub.close();
	}

	private String run(List<String> cmd, Map<String, String> env) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		pb.environment().putAll(env);
		pb.environment().put("BLOCKAGENT_HOME", home.toString());
		pb.environment().put("BLOCKAGENT_DEBUG_DIR", PROJECT.resolve("build").toString());
		Process p = pb.start();
		p.getOutputStream().close();
		boolean done = p.waitFor(180, TimeUnit.SECONDS);
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!done) {
			p.destroyForcibly();
			fail("CLI 沒有在 180 秒內結束：\n" + out);
		}
		System.out.println("---- " + String.join(" ", cmd) + " (exit " + p.exitValue() + ")\n" + out);
		String name = cmd.getFirst().endsWith("claude") ? "claude" : "codex";
		Path dump = PROJECT.resolve("build/cli-" + name + "-requests.json");
		Files.createDirectories(dump.getParent());
		Files.writeString(dump, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(stub.requests()));
		return out;
	}

	private Set<String> snapshotNames() {
		Set<String> s = new TreeSet<>();
		for (JsonElement e : snapshot) s.add(e.getAsJsonObject().get("name").getAsString());
		return s;
	}

	private void assertNoLeaks() throws IOException {
		assertFalse(Files.exists(writeTarget), "canary 寫入路徑被建立了：" + writeTarget);
		for (String body : stub.rawBodies()) {
			assertFalse(body.contains(secretValue), "canary 的內容出現在送給模型的請求裡(讀到了檔案)");
		}
	}

	// ---- Claude Code ----

	@Test
	void claudeCode() throws Exception {
		stub.script(List.of(
			StubModelServer.Step.tool("Read", obj("file_path", secret.toString())),
			StubModelServer.Step.tool("Bash", obj("command", "cat " + secret + " > " + writeTarget)),
			StubModelServer.Step.tool("Write", obj("file_path", writeTarget.toString(), "content", "x")),
			StubModelServer.Step.tool("WebFetch", obj("url", "https://example.com/", "prompt", "x")),
			StubModelServer.Step.tool("WebSearch", obj("query", "minecraft")),
			StubModelServer.Step.tool("Agent", obj("description", "x", "prompt", "cat " + secret, "subagent_type", "general-purpose")),
			StubModelServer.Step.tool("DeferredToolPlaceholder", new JsonObject()),
			StubModelServer.Step.tool("mcp__blockagent__get_self", new JsonObject())
		));
		Path cfg = tmp.resolve("claude-config");
		Files.createDirectories(cfg);
		run(List.of(PROJECT.resolve("bin/blockagent-claude").toString(), "--test-print", "開始"), Map.of(
			"ANTHROPIC_BASE_URL", stub.baseUrl(),
			"ANTHROPIC_API_KEY", "sk-ant-stub-key",
			"CLAUDE_CONFIG_DIR", cfg.toString(),
			"DISABLE_TELEMETRY", "1",
			"CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"));

		JsonObject main = stub.firstMainRequest();
		assertNotNull(main, "Claude Code 沒有送出帶工具的請求");
		// T8：工具清單=模組的工具(名稱與 schema)，沒有任何內建工具(T9)
		Map<String, JsonObject> seen = new TreeMap<>();
		for (JsonElement e : main.getAsJsonArray("tools")) {
			JsonObject t = e.getAsJsonObject();
			seen.put(t.get("name").getAsString(), t);
		}
		Set<String> expected = new TreeSet<>();
		for (String n : snapshotNames()) expected.add("mcp__blockagent__" + n);
		// Claude Code 內部的佔位工具：說明是「never call this tool」，沒有任何參數(下面也會實際呼叫它，確認做不了事)
		JsonObject placeholder = seen.remove("DeferredToolPlaceholder");
		if (placeholder != null) {
			assertEquals(0, placeholder.getAsJsonObject("input_schema").getAsJsonObject("properties").size(), "佔位工具不應該有參數");
		}
		assertEquals(expected, seen.keySet(), "Claude Code 看到的工具應該只有模組的工具");
		for (JsonElement e : snapshot) {
			JsonObject t = e.getAsJsonObject();
			JsonObject got = seen.get("mcp__blockagent__" + t.get("name").getAsString());
			// Claude Code 會把說明文字的全形標點換成半形，所以比對結構(型別、必填、參數名稱)，不比對說明文字
			assertEquals(withoutDescriptions(t.getAsJsonObject("inputSchema")), withoutDescriptions(got.getAsJsonObject("input_schema")),
				"schema 不同：" + t.get("name"));
		}

		// T9：每個越權呼叫都失敗；最後的模組工具成功
		List<JsonObject> results = toolResults(stub.requests());
		assertTrue(results.size() >= 8, "應該有 8 個工具結果，實際 " + results.size() + "：" + results);
		for (int i = 0; i < 7; i++) {
			JsonObject r = results.get(i);
			assertTrue(r.has("is_error") && r.get("is_error").getAsBoolean(), "第 " + (i + 1) + " 個越權呼叫沒有失敗：" + r);
		}
		assertTrue(results.get(7).toString().contains("stub:get_self"), "模組的工具應該可以用：" + results.get(7));
		assertEquals(List.of("get_self"), mcpCalls);
		assertNoLeaks();
	}

	/** Anthropic 格式：從之後的請求裡取出 tool_result(依出現順序，不重複)。 */
	static List<JsonObject> toolResults(List<JsonObject> reqs) {
		Map<String, JsonObject> byId = new java.util.LinkedHashMap<>();
		for (JsonObject r : reqs) {
			if (!r.has("messages")) continue;
			for (JsonElement m : r.getAsJsonArray("messages")) {
				JsonElement content = m.getAsJsonObject().get("content");
				if (content == null || !content.isJsonArray()) continue;
				for (JsonElement c : content.getAsJsonArray()) {
					JsonObject co = c.getAsJsonObject();
					if ("tool_result".equals(co.has("type") ? co.get("type").getAsString() : "")) {
						byId.putIfAbsent(co.get("tool_use_id").getAsString(), co);
					}
				}
			}
		}
		return new ArrayList<>(byId.values());
	}

	// ---- Codex ----

	@Test
	void codexPlanner() throws Exception {
		String planner = "gpt-6.2-sol";
		stub.openAiReply(planner, new com.google.gson.Gson().toJson(Map.of("recommendation", "換到新址",
			"steps", List.of("預檢新址", "開始施工"))));
		stub.script(List.of(StubModelServer.Step.custom("exec",
				"const p = await tools.mcp__planner__request_plan({goal: '完成小屋', context: '只摘要：兩次清除都到不了'}); text(JSON.stringify(p));"),
			StubModelServer.Step.custom("exec",
				"const w = await tools.mcp__blockagent__wait_events({}); text(JSON.stringify(w));")));
		String provider = "model_provider = \"stub\"\n"
			+ "model_providers = { stub = { name = \"stub\", base_url = \"" + stub.baseUrl()
			+ "/v1\", wire_api = \"responses\", env_key = \"STUB_API_KEY\" } }";
		String out = run(List.of(PROJECT.resolve("bin/blockagent-codex").toString(), "--test-exec", "開始遊玩"), Map.of(
			"BLOCKAGENT_CODEX_MODEL", "gpt-6-luna",
			"BLOCKAGENT_PLANNER_MODEL", planner,
			"BLOCKAGENT_TEST_PROVIDER_TOML", provider,
			"STUB_API_KEY", "stub-key"));
		assertFalse(out.contains("內建工具和測試過的不一樣"), out);
		List<JsonObject> plannerRequests = stub.requests().stream()
			.filter(r -> r.has("model") && planner.equals(r.get("model").getAsString())).toList();
		assertEquals(1, plannerRequests.size(), () -> "規劃模型應只被呼叫一次：" + out);
		String request = plannerRequests.getFirst().toString();
		assertTrue(request.contains("只摘要") && !request.contains("開始遊玩"), request);
		// S11.7：規劃模型看得到遊戲端的快照，結果送回遊戲(不是 request_plan 的回應)
		assertEquals(1, planContextReads.size());
		assertTrue(request.contains("inv=oak_log:3"), "快照沒有交給規劃模型：" + request);
		assertFalse(request.contains("mcp__blockagent__") || request.contains("mcp__planner__")
			|| request.contains("### `exec_command`") || request.contains("### `web_search`"),
			"規劃端出現額外遊戲、shell 或搜尋工具");
		var main = stub.requests().getLast();
		assertTrue(main.get("input").toString().contains("換到新址"), () -> main.toString() + "\n" + out);
		String input = main.get("input").toString();
		assertTrue(input.contains("pending"), () -> "request_plan 應該立刻回 pending：" + input);
		var usageLog = Files.readString(home.resolve("codex-home/blockagent-planner-usage.jsonl"));
		assertTrue(usageLog.contains("\"result\": \"ok\"") && usageLog.contains("\"input_tokens\""), usageLog);
		assertFalse(usageLog.contains("只摘要"), usageLog);
		assertTrue(usageLog.contains("\"delivered\": true"), usageLog);
		assertEquals(List.of("wait_events"), mcpCalls);
	}

	@Test
	void codex() throws Exception {
		String js1 = "const r = await tools.exec_command({cmd: \"cat " + secret + "\"}); text(JSON.stringify(r));";
		String patch = "*** Begin Patch\n*** Add File: " + writeTarget + "\n+x\n*** End Patch\n";
		String js2 = "const r = await tools.apply_patch(" + com.google.gson.JsonParser.parseString("\"" + patch.replace("\n", "\\n") + "\"") + "); text(JSON.stringify(r));";
		String js3 = "const r = await tools.view_image({path: \"" + secret + "\"}); text(JSON.stringify(r));";
		String js4 = "text(Object.keys(tools).sort().join(','));";
		String js5 = "const r = await tools.mcp__blockagent__get_self({}); text(JSON.stringify(r));";
		// exec 的 JS 環境本身不能有讀檔、上網的能力(read-only 沙箱只擋寫入)。
		// 每一項的結果是 absent(不存在)、error:…(用了會失敗)或 OK:…(成功=越權)。
		String secretJs = new com.google.gson.Gson().toJson(secret.toString());
		String js6 = String.join("\n",
			"const S = " + secretJs + ";",
			"const R = {};",
			"async function probe(name, present, use) {",
			"  if (!present) { R[name] = 'absent'; return; }",
			"  try { const v = await use(); R[name] = 'OK:' + String(v).length; } catch (e) { R[name] = 'error:' + String(e && e.message || e).slice(0, 80); }",
			"}",
			"await probe('fetch_file', typeof fetch === 'function', async () => (await fetch('file://' + S)).text());",
			"await probe('fetch_net', typeof fetch === 'function', async () => (await fetch('https://example.com/')).status);",
			"await probe('xhr', typeof XMLHttpRequest !== 'undefined', () => 'present');",
			"await probe('require', typeof require === 'function', () => require('fs').readFileSync(S, 'utf8'));",
			// process 存在就算越權：光是 process.env 就讀得到 BLOCKAGENT_TOKEN 等環境變數
			"await probe('process', typeof process !== 'undefined', () => JSON.stringify(process.env));",
			"await probe('process_fs', typeof process !== 'undefined' && typeof process.getBuiltinModule === 'function', () => process.getBuiltinModule('fs').readFileSync(S, 'utf8'));",
			"await probe('deno', typeof Deno !== 'undefined', () => Deno.readTextFileSync(S));",
			"await probe('bun', typeof Bun !== 'undefined', () => Bun.file(S).text());",
			"await probe('import_node_fs', true, async () => (await import('node:fs')).readFileSync(S, 'utf8'));",
			"await probe('import_fs', true, async () => (await import('fs')).readFileSync(S, 'utf8'));",
			"await probe('import_net', true, async () => Object.keys(await import('https://example.com/x.js')).length);",
			"text('PROBES ' + JSON.stringify(R));");
		stub.script(List.of(
			StubModelServer.Step.custom("exec", js1),
			StubModelServer.Step.custom("exec", js2),
			StubModelServer.Step.custom("exec", js3),
			StubModelServer.Step.tool("shell", obj("command", "cat " + secret)),
			StubModelServer.Step.tool("exec_command", obj("cmd", "cat " + secret)),
			StubModelServer.Step.custom("apply_patch", patch),
			StubModelServer.Step.custom("exec", js4),
			StubModelServer.Step.custom("exec", js5),
			StubModelServer.Step.custom("exec", js6)
		));
		String provider = "model_provider = \"stub\"\n"
			+ "model_providers = { stub = { name = \"stub\", base_url = \"" + stub.baseUrl() + "/v1\", wire_api = \"responses\", env_key = \"STUB_API_KEY\" } }";
		String out = run(List.of(PROJECT.resolve("bin/blockagent-codex").toString(), "--test-exec", "開始"), Map.of(
			"BLOCKAGENT_PLANNER_MODEL", "", // 預設會開 Sol 規劃；這裡只測模組和內建工具
			"BLOCKAGENT_TEST_PROVIDER_TOML", provider,
			"STUB_API_KEY", "stub-key"));
		assertFalse(out.contains("內建工具和測試過的不一樣"), "Codex 的工具清單快照比對失敗：\n" + out);

		List<JsonObject> reqs = stub.requests();
		assertFalse(reqs.isEmpty(), "Codex 沒有送出請求");
		// 最後一個請求裡依序有每個工具呼叫的結果
		List<String> outputs = new ArrayList<>();
		List<JsonElement> rawOutputs = new ArrayList<>();
		for (JsonElement it : reqs.getLast().getAsJsonArray("input")) {
			JsonObject o = it.getAsJsonObject();
			if (o.has("type") && o.get("type").getAsString().endsWith("_call_output")) {
				outputs.add(o.get("output").toString());
				rawOutputs.add(o.get("output"));
			}
		}
		assertEquals(9, outputs.size(), "應該有 9 個工具結果：" + outputs);
		// T9：每個越權呼叫都失敗
		assertTrue(outputs.get(0).contains("exec_command is not a function"), "exec_command 應該不存在：" + outputs.get(0));
		assertTrue(outputs.get(1).contains("read-only sandbox"), "apply_patch 應該被唯讀沙箱擋下：" + outputs.get(1));
		assertTrue(outputs.get(2).contains("view_image is not a function"), "view_image 應該不存在：" + outputs.get(2));
		assertTrue(outputs.get(3).contains("unsupported call"), "shell 應該不存在：" + outputs.get(3));
		assertTrue(outputs.get(4).contains("unsupported call"), "exec_command 應該不存在：" + outputs.get(4));
		assertTrue(outputs.get(5).contains("read-only sandbox"), "apply_patch 應該被唯讀沙箱擋下：" + outputs.get(5));
		// T8：exec 裡看得到的工具=模組的工具+少數無害的內建工具
		// 從 exec 的輸出裡找 Object.keys(tools) 印出的那一行，整行解析(不能從某個已知工具名開始切，H13)
		Set<String> keys = toolKeys(rawOutputs.get(6));
		assertFalse(keys.isEmpty(), "exec 的輸出裡找不到工具清單：" + outputs.get(6));
		Set<String> mcpNames = new TreeSet<>(), builtin = new TreeSet<>();
		for (String k : keys) {
			if (k.startsWith("mcp__blockagent__")) mcpNames.add(k.substring("mcp__blockagent__".length()));
			else builtin.add(k);
		}
		assertEquals(snapshotNames(), mcpNames, "Codex 看到的模組工具應該和快照相同");
		assertTrue(Set.of("apply_patch", "clock__curr_time", "list_mcp_resource_templates", "list_mcp_resources", "read_mcp_resource").containsAll(builtin),
			"exec 裡有預期以外的內建工具：" + builtin);
		assertTrue(outputs.get(7).contains("stub:get_self"), "模組的工具應該可以用：" + outputs.get(7));
		assertEquals(List.of("get_self"), mcpCalls);
		// T9：exec 的 JS 環境讀不到檔案、上不了網
		String probes = outputs.get(8);
		System.out.println("[codex exec 環境] " + probes);
		assertTrue(probes.contains("PROBES"), "exec 的環境檢查沒有跑完：" + probes);
		assertFalse(probes.contains("OK:"), "exec 的 JS 環境可以讀檔或上網：" + probes);
		assertNoLeaks();
	}

	/**
	 * exec 工具結果裡 {@code Object.keys(tools).sort().join(',')} 印出的名稱。結果可能是字串，
	 * 也可能是 {@code [{"type":"input_text","text":"..."}]}；取最後一行「整行都是逗號分隔的名稱」的那行。
	 */
	static Set<String> toolKeys(JsonElement output) {
		StringBuilder text = new StringBuilder();
		if (output.isJsonPrimitive()) text.append(output.getAsString());
		else if (output.isJsonArray()) {
			for (JsonElement part : output.getAsJsonArray()) {
				if (part.isJsonObject() && part.getAsJsonObject().has("text")) text.append(part.getAsJsonObject().get("text").getAsString()).append('\n');
			}
		}
		Set<String> keys = new TreeSet<>();
		for (String line : text.toString().split("\\R")) {
			String s = line.strip();
			if (s.matches("[A-Za-z_][A-Za-z0-9_]*(,[A-Za-z_][A-Za-z0-9_]*)+")) {
				keys.clear();
				keys.addAll(List.of(s.split(",")));
			}
		}
		return keys;
	}

	static JsonElement withoutDescriptions(JsonElement e) {
		if (e.isJsonObject()) {
			JsonObject o = new JsonObject();
			for (var en : e.getAsJsonObject().entrySet()) {
				if (en.getKey().equals("description") || en.getKey().equals("$schema")) continue;
				o.add(en.getKey(), withoutDescriptions(en.getValue()));
			}
			return o;
		}
		if (e.isJsonArray()) {
			JsonArray a = new JsonArray();
			for (JsonElement x : e.getAsJsonArray()) a.add(withoutDescriptions(x));
			return a;
		}
		return e;
	}

	static JsonObject obj(String... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
		return o;
	}
}
