package blockagent.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
	private final HttpClient http = HttpClient.newHttpClient();
	private ToolRegistry registry;
	private McpServer server;
	private String token;
	private URI uri;

	@BeforeEach
	void setUp() throws Exception {
		registry = new ToolRegistry();
		registry.register("echo", "Return text", ToolRegistry.Hints.READ, Schema.object().string("text", "Text to return", true).build(),
			a -> ToolResult.ok("echo:" + new Args(a).str("text")));
		registry.register("fail", "一定失敗", Schema.object().build(),
			a -> ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("missing", "cobblestone:3"), "not enough materials"));
		token = McpServer.newToken();
		server = new McpServer(registry, token, "test", "說明");
		int port = server.start(47700);
		uri = URI.create("http://127.0.0.1:" + port + "/mcp");
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private HttpResponse<String> post(String body, String sid, String auth, String origin) throws Exception {
		var b = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
		if (auth != null) b.header("Authorization", auth);
		if (sid != null) b.header("Mcp-Session-Id", sid);
		if (origin != null) b.header("Origin", origin);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String initialize() throws Exception {
		var r = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
			null, "Bearer " + token, null);
		assertEquals(200, r.statusCode());
		JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
		assertEquals("2025-06-18", o.getAsJsonObject("result").get("protocolVersion").getAsString());
		return r.headers().firstValue("Mcp-Session-Id").orElseThrow();
	}

	@Test
	void rejectsMissingOrWrongToken() throws Exception {
		assertEquals(401, post("{}", null, null, null).statusCode());
		assertEquals(401, post("{}", null, "Bearer wrong", null).statusCode());
	}

	/** S11.7：規劃端點只認規劃 token；規劃 token 也不能用在 /mcp 和 /status。 */
	@Test
	void planEndpointsUseTheirOwnToken() throws Exception {
		String base = uri.toString().replace("/mcp", "");
		URI ctx = URI.create(base + "/plan/context"), res = URI.create(base + "/plan/result");
		// 還沒開放規劃時，任何 token 都不行
		assertEquals(401, http.send(HttpRequest.newBuilder(ctx).header("Authorization", "Bearer " + token).GET().build(),
			HttpResponse.BodyHandlers.ofString()).statusCode());
		String planToken = McpServer.newToken();
		assertThrows(IllegalArgumentException.class, () -> server.setPlanBridge(token, null), "不能跟 MCP token 相同");
		var accepted = new java.util.ArrayList<JsonObject>();
		server.setPlanBridge(planToken, new McpServer.PlanBridge() {
			@Override
			public JsonObject context() {
				var context = new JsonObject();
				context.addProperty("snapshot", "[self]\nhp=20");
				context.addProperty("basis", "test-basis");
				return context;
			}

			@Override
			public String accept(JsonObject result) {
				if (!result.has("goal")) return "goal is required";
				accepted.add(result);
				return null;
			}
		});
		var get = HttpRequest.newBuilder(ctx).GET();
		assertEquals(401, http.send(get.copy().header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
		var r = http.send(get.copy().header("Authorization", "Bearer " + planToken).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode());
		assertEquals("[self]\nhp=20", JsonParser.parseString(r.body()).getAsJsonObject().get("snapshot").getAsString());

		var postResult = HttpRequest.newBuilder(res).header("Authorization", "Bearer " + planToken);
		assertEquals(401, http.send(HttpRequest.newBuilder(res).header("Authorization", "Bearer " + token)
			.POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"蓋屋\",\"steps\":[\"a\"]}")).build(),
			HttpResponse.BodyHandlers.ofString()).statusCode(), "帶錯 token 的 POST 也應正常回 401");
		assertEquals(200, http.send(postResult.copy().POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"蓋屋\",\"steps\":[\"a\"]}")).build(),
			HttpResponse.BodyHandlers.ofString()).statusCode());
		var bad = http.send(postResult.copy().POST(HttpRequest.BodyPublishers.ofString("{\"steps\":[]}")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(400, bad.statusCode());
		assertTrue(bad.body().contains("goal is required"), bad.body());
		assertEquals(400, http.send(postResult.copy().POST(HttpRequest.BodyPublishers.ofString("[1]")).build(),
			HttpResponse.BodyHandlers.ofString()).statusCode());
		assertEquals(1, accepted.size());

		// 規劃 token 不能拿來呼叫 MCP 或查狀態
		assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null, "Bearer " + planToken, null).statusCode());
		assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/status")).header("Authorization", "Bearer " + planToken).GET().build(),
			HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	@Test
	void rejectsForeignOrigin() throws Exception {
		assertEquals(403, post("{}", null, "Bearer " + token, "https://evil.example").statusCode());
		assertNotEquals(403, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null,
			"Bearer " + token, "http://localhost:3000").statusCode());
	}

	@Test
	void wrongFieldTypesAreBadRequests() throws Exception {
		String auth = "Bearer " + token;
		// 型別不對是用戶端的錯，回 400，不是 500(L2)
		assertEquals(400, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7}", null, auth, null).statusCode());
		assertEquals(400, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":{}}}", null, auth, null).statusCode());
		assertEquals(400, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":\"x\"}}", null, auth, null).statusCode());
		assertEquals(400, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":[1]}}}", null, auth, null).statusCode());
		assertNotNull(initialize(), "型別正確的 initialize 照常成功");
	}

	@Test
	void unknownProtocolFallsBackToLatest() throws Exception {
		var r = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}",
			null, "Bearer " + token, null);
		assertEquals(McpServer.SUPPORTED_PROTOCOLS.getFirst(),
			JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result").get("protocolVersion").getAsString());
	}

	@Test
	void listAndCallTools() throws Exception {
		String sid = initialize();
		assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sid, "Bearer " + token, null).statusCode());

		var list = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", sid, "Bearer " + token, null).body())
			.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
		assertEquals(2, list.size());
		assertEquals("echo", list.get(0).getAsJsonObject().get("name").getAsString());

		var ok = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"嗨\"}}}",
			sid, "Bearer " + token, null).body()).getAsJsonObject().getAsJsonObject("result");
		assertFalse(ok.get("isError").getAsBoolean());
		assertEquals("echo:嗨", ok.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());

		var fail = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"fail\",\"arguments\":{}}}",
			sid, "Bearer " + token, null).body()).getAsJsonObject().getAsJsonObject("result");
		assertTrue(fail.get("isError").getAsBoolean());
		assertEquals("ERR MISSING_MATERIALS missing=cobblestone:3 | not enough materials | hint=" + ErrorCode.MISSING_MATERIALS.hint(),
			fail.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
		assertFalse(fail.has("structuredContent"), "不回傳 structuredContent");

		var bad = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{}}}",
			sid, "Bearer " + token, null).body()).getAsJsonObject().getAsJsonObject("result");
		assertEquals("ERR INVALID_ARGUMENT | missing required argument: text",
			bad.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());

		var missing = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}",
			sid, "Bearer " + token, null).body()).getAsJsonObject();
		assertEquals(-32602, missing.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void requestsNeedCurrentSession() throws Exception {
		String rpc = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
		assertEquals(400, post(rpc, null, "Bearer " + token, null).statusCode());
		String first = initialize();
		String second = initialize();
		assertEquals(404, post(rpc, first, "Bearer " + token, null).statusCode());
		assertEquals(200, post(rpc, second, "Bearer " + token, null).statusCode());
	}

	@Test
	void responseSuffixIsAppended() throws Exception {
		registry.setResponseSuffix(name -> "[EVENTS]\n[BATCH] chat: from=" + name);
		String sid = initialize();
		var r = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"a\"}}}",
			sid, "Bearer " + token, null).body()).getAsJsonObject().getAsJsonObject("result");
		assertTrue(r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().equals("echo:a\n[EVENTS]\n[BATCH] chat: from=echo"));
	}

	@Test
	void longCallDoesNotBlockOtherCalls() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		registry.register("block", "等待", Schema.object().build(), a -> {
			release.await(10, TimeUnit.SECONDS);
			return ToolResult.ok("done");
		});
		String sid = initialize();
		CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
			try {
				return post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"block\"}}", sid, "Bearer " + token, null);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		var fast = post("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}}}",
			sid, "Bearer " + token, null);
		assertEquals(200, fast.statusCode());
		assertFalse(slow.isDone());
		release.countDown();
		assertEquals(200, slow.get(5, TimeUnit.SECONDS).statusCode());
	}

	@Test
	void presenceFollowsMcpActivity() throws Exception {
		java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
		server.setClock(now::get);
		java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
		server.setOnClose(closed::incrementAndGet);
		CountDownLatch release = new CountDownLatch(1);
		registry.register("block", "等待", Schema.object().build(), a -> {
			release.await(10, TimeUnit.SECONDS);
			return ToolResult.ok("done");
		});
		assertEquals(AiPresence.NONE, server.presence(), "還沒 initialize");
		String sid = initialize();
		assertEquals(AiPresence.ACTIVE, server.presence());
		now.addAndGet((McpServer.SILENT_AFTER_MS - 1) * 1_000_000);
		assertEquals(AiPresence.ACTIVE, server.presence());
		now.addAndGet(1_000_000);
		assertEquals(AiPresence.SILENT, server.presence(), "超過門檻沒有請求");

		// 進行中的呼叫(像 wait_events)不管等多久都算 BUSY；結束後重新計時
		CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
			try {
				return post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"block\"}}", sid, "Bearer " + token, null);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		long deadline = System.currentTimeMillis() + 5000;
		while (server.presence() != AiPresence.BUSY && System.currentTimeMillis() < deadline) Thread.sleep(10);
		assertEquals(AiPresence.BUSY, server.presence());
		now.addAndGet(McpServer.SILENT_AFTER_MS * 2 * 1_000_000);
		assertEquals(AiPresence.BUSY, server.presence());
		release.countDown();
		assertEquals(200, slow.get(5, TimeUnit.SECONDS).statusCode());
		assertEquals(AiPresence.ACTIVE, server.presence(), "呼叫結束時算一次活動");

		// 舊 session 的請求不算活動
		now.addAndGet(McpServer.SILENT_AFTER_MS * 1_000_000);
		assertEquals(404, post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "old-session", "Bearer " + token, null).statusCode());
		assertEquals(AiPresence.SILENT, server.presence());

		// DELETE 結束連線
		var del = http.send(HttpRequest.newBuilder(uri).DELETE().header("Authorization", "Bearer " + token).header("Mcp-Session-Id", sid).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, del.statusCode());
		assertEquals(AiPresence.NONE, server.presence());
		assertEquals(1, closed.get());
	}

	/** A13：有 prompt 時 initialize 宣告 prompts，prompts/list 和 prompts/get 照 MCP 規格回傳；沒有 prompt 時不宣告。 */
	@Test
	void promptsListAndGet() throws Exception {
		String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
		var caps = JsonParser.parseString(post(init, null, "Bearer " + token, null).body()).getAsJsonObject()
			.getAsJsonObject("result").getAsJsonObject("capabilities");
		assertFalse(caps.has("prompts"), "沒有 prompt 時不宣告 prompts");

		server.addPrompt("how_to_play", "Guide", () -> "# 你在玩 Minecraft");
		server.addPrompt("broken", "Unavailable", () -> null);
		var r = post(init, null, "Bearer " + token, null);
		String sid = r.headers().firstValue("Mcp-Session-Id").orElseThrow();
		caps = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonObject("capabilities");
		assertFalse(caps.getAsJsonObject("prompts").get("listChanged").getAsBoolean());

		var list = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\"}", sid, "Bearer " + token, null).body())
			.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("prompts");
		assertEquals(2, list.size());
		assertEquals("broken", list.get(0).getAsJsonObject().get("name").getAsString());
		assertEquals("how_to_play", list.get(1).getAsJsonObject().get("name").getAsString());
		assertEquals("Guide", list.get(1).getAsJsonObject().get("description").getAsString());

		var got = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/get\",\"params\":{\"name\":\"how_to_play\"}}",
			sid, "Bearer " + token, null).body()).getAsJsonObject().getAsJsonObject("result");
		var msg = got.getAsJsonArray("messages").get(0).getAsJsonObject();
		assertEquals("user", msg.get("role").getAsString());
		assertEquals("text", msg.getAsJsonObject("content").get("type").getAsString());
		assertEquals("# 你在玩 Minecraft", msg.getAsJsonObject("content").get("text").getAsString());

		for (String bad : new String[]{"{\"name\":\"nope\"}", "{}", "{\"name\":\"broken\"}"}) {
			var err = JsonParser.parseString(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/get\",\"params\":" + bad + "}",
				sid, "Bearer " + token, null).body()).getAsJsonObject();
			assertEquals(-32602, err.getAsJsonObject("error").get("code").getAsInt(), bad);
		}
	}

	/** A13：玩法說明打包在 jar 的資源裡，內容和專用 CLI 用的 prompts/how-to-play.md 是同一份。 */
	@Test
	void howToPlayIsPackaged() throws Exception {
		try (var in = McpServerTest.class.getResourceAsStream("/blockagent/prompts/how-to-play.md")) {
			assertNotNull(in, "prompts/how-to-play.md 沒有打包進資源");
			assertEquals(Files.readString(Path.of("prompts/how-to-play.md")), new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	/** A11：tools/list 帶出 MCP annotations；沒有指定的工具(測試替身)不帶。 */
	@Test
	void listIncludesAnnotations() {
		var list = registry.listJson();
		var echo = list.get(0).getAsJsonObject().getAsJsonObject("annotations");
		assertTrue(echo.get("readOnlyHint").getAsBoolean());
		assertFalse(echo.get("openWorldHint").getAsBoolean());
		assertFalse(echo.has("destructiveHint"), "唯讀工具不需要 destructiveHint");
		assertFalse(list.get(1).getAsJsonObject().has("annotations"));
		registry.register("dig", "Dig", ToolRegistry.Hints.DESTRUCTIVE, Schema.object().build(), a -> ToolResult.ok("OK"));
		var dig = registry.listJson().get(2).getAsJsonObject().getAsJsonObject("annotations");
		assertFalse(dig.get("readOnlyHint").getAsBoolean());
		assertTrue(dig.get("destructiveHint").getAsBoolean());
		assertFalse(dig.get("idempotentHint").getAsBoolean());
	}

	/** A9：minimum、maximum、default、enum 寫成標準 JSON Schema；沒有說明時不輸出 description。 */
	@Test
	void schemaConstraints() {
		JsonObject s = Schema.object().integer("radius", null, false).range(4, 48).def(16)
			.enumString("mode", "m", false, "a", "b").def("a").bool("dry_run", null, false).def(false)
			.itemCounts("items", null, true).build();
		JsonObject r = s.getAsJsonObject("properties").getAsJsonObject("radius");
		assertEquals(4, r.get("minimum").getAsInt());
		assertEquals(48, r.get("maximum").getAsInt());
		assertEquals(16, r.get("default").getAsInt());
		assertFalse(r.has("description"));
		JsonObject m = s.getAsJsonObject("properties").getAsJsonObject("mode");
		assertEquals("a", m.get("default").getAsString());
		assertEquals(2, m.getAsJsonArray("enum").size());
		assertFalse(s.getAsJsonObject("properties").getAsJsonObject("dry_run").get("default").getAsBoolean());
		assertEquals(1, s.getAsJsonObject("properties").getAsJsonObject("items").get("minProperties").getAsInt());
		assertEquals("items", s.getAsJsonArray("required").get(0).getAsString());
		assertThrows(IllegalStateException.class, () -> Schema.object().range(0, 1));
	}

	/** S3.0a、S3.0d：KV 的引號規則，以及 ERR 行的錯誤碼、資料、原因和 hint。 */
	@Test
	void kvAndErrorLine() {
		assertEquals("OK placed item=oak_log pos=1,64,-2 note=\"two words\" name=\"好餓\" n=3 ratio=1.5 empty=\"\"",
			Kv.of("OK placed").put("item", "oak_log").put("pos", "1,64,-2").put("note", "two words").quoted("name", "好餓")
				.put("n", 3).put("ratio", 1.5).put("empty", "").put("skipped", null).toString());
		assertEquals("q=\"a\\\"b|c\\nd\"", Kv.of().put("q", "a\"b|c\nd").toString());
		var counts = new java.util.LinkedHashMap<String, Integer>();
		counts.put("oak_log", 12);
		counts.put("stick", 2);
		assertEquals("oak_log:12,stick:2", Kv.counts(counts));
		assertEquals("none", Kv.counts(java.util.Map.of()));

		ToolResult busy = ToolResult.error(ErrorCode.BUSY, Kv.of().put("task", "mine"), "another task is running");
		assertEquals("ERR BUSY task=mine | another task is running | hint=stop, or pass replace=true", busy.text());
		assertEquals("BUSY", busy.code());
		assertEquals("stop, or pass replace=true", busy.hint());
		assertEquals("ERR NOT_FOUND | none found | hint=explore", ToolResult.error(ErrorCode.NOT_FOUND, "none\nfound", "explore").text(),
			"一定是一行");
		assertEquals("ERR INVALID_ARGUMENT | bad", ToolResult.error(ErrorCode.INVALID_ARGUMENT, "bad").text());
		assertEquals("ERR MISSING_TOOL | no rod", ToolResult.error(ErrorCode.MISSING_TOOL, null, "no rod", "").text(), "空字串 hint 表示不給");
		assertNull(ToolResult.ok("OK").hint());
	}

	@Test
	void strOrIntAcceptsStringAndInteger() {
		JsonObject o = JsonParser.parseString("{\"a\":\"sheep\",\"b\":1538,\"c\":true,\"d\":1.5}").getAsJsonObject();
		Args args = new Args(o);
		assertEquals("sheep", args.strOrInt("a"));
		assertEquals("1538", args.strOrInt("b"));
		assertThrows(Args.BadArgument.class, () -> args.strOrInt("c"));
		assertThrows(Args.BadArgument.class, () -> args.strOrInt("d"));
	}

	@Test
	void runtimeFileIsPrivate(@TempDir Path dir) throws Exception {
		Path f = RuntimeFile.write(dir, 1234, token);
		assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
		JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
		assertEquals(1234, o.get("port").getAsInt());
		assertEquals(token, o.get("token").getAsString());
	}

	/** R14：ToolRegistry 捕獲 Args.BadArgument 與內部例外時呼叫 onError，且 onError 自己丟例外不影響回傳。 */
	@Test
	void toolRegistryNotifiesOnErrorForBadArgumentAndInternal() {
		var seen = new java.util.ArrayList<String>();
		registry.register("boom", "Throw", Schema.object().build(), a -> {
			throw new IllegalStateException("kaboom");
		});
		registry.setOnError((name, msg) -> seen.add(name + ":" + msg));

		var bad = registry.call("echo", new JsonObject());
		assertEquals("INVALID_ARGUMENT", bad.code());
		var internal = registry.call("boom", new JsonObject());
		assertEquals("INTERNAL", internal.code());
		assertEquals(2, seen.size());
		assertTrue(seen.get(0).startsWith("echo:INVALID_ARGUMENT "), seen.get(0));
		assertTrue(seen.get(1).startsWith("boom:INTERNAL IllegalStateException (kaboom)"), seen.get(1));

		registry.setOnError((name, msg) -> {
			throw new RuntimeException("log failed");
		});
		assertEquals("INVALID_ARGUMENT", registry.call("echo", new JsonObject()).code());
	}
}
