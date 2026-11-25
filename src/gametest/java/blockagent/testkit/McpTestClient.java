package blockagent.testkit;

import blockagent.mcp.RuntimeFile;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * 代替 AI 的 MCP 用戶端(docs/SPEC.md S10.1「模擬 AI」)：直接用 HTTP 呼叫模組的工具，不經過任何模型。
 */
public final class McpTestClient {
	public record Result(boolean ok, String text, JsonObject structured) {
		public String code() {
			return structured.has("code") ? structured.get("code").getAsString() : null;
		}

		public String reason() {
			return structured.has("reason") ? structured.get("reason").getAsString() : null;
		}

		public String hint() {
			return structured.has("hint") ? structured.get("hint").getAsString() : null;
		}
	}

	private static final Gson GSON = new Gson();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final URI uri;
	private final String token;
	private final AtomicInteger ids = new AtomicInteger();
	private String session;

	public McpTestClient(URI uri, String token) {
		this.uri = uri;
		this.token = token;
	}

	public static McpTestClient fromRuntimeFile() throws Exception {
		JsonObject o = JsonParser.parseString(Files.readString(RuntimeFile.defaultHome().resolve("runtime.json"))).getAsJsonObject();
		McpTestClient c = new McpTestClient(URI.create(o.get("url").getAsString()), o.get("token").getAsString());
		c.initialize();
		return c;
	}

	private HttpResponse<String> post(JsonObject body, Duration timeout) throws Exception {
		var b = HttpRequest.newBuilder(uri).timeout(timeout)
			.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
			.header("Authorization", "Bearer " + token)
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
		if (session != null) b.header("Mcp-Session-Id", session);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	public void initialize() throws Exception {
		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", ids.incrementAndGet());
		req.addProperty("method", "initialize");
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", "2025-06-18");
		params.add("capabilities", new JsonObject());
		JsonObject ci = new JsonObject();
		ci.addProperty("name", "blockagent-testkit");
		ci.addProperty("version", "1");
		params.add("clientInfo", ci);
		req.add("params", params);
		var r = post(req, Duration.ofSeconds(10));
		if (r.statusCode() != 200) throw new IllegalStateException("initialize 失敗：" + r.statusCode());
		session = r.headers().firstValue("Mcp-Session-Id").orElseThrow();
		JsonObject note = new JsonObject();
		note.addProperty("jsonrpc", "2.0");
		note.addProperty("method", "notifications/initialized");
		post(note, Duration.ofSeconds(5));
	}

	public Result call(String tool, JsonObject args) throws Exception {
		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", ids.incrementAndGet());
		req.addProperty("method", "tools/call");
		JsonObject params = new JsonObject();
		params.addProperty("name", tool);
		params.add("arguments", args == null ? new JsonObject() : args);
		req.add("params", params);
		var r = post(req, Duration.ofSeconds(150));
		JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
		if (o.has("error")) throw new IllegalStateException(tool + "：" + o.getAsJsonObject("error"));
		JsonObject res = o.getAsJsonObject("result");
		String text = res.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		return new Result(!res.get("isError").getAsBoolean(), text, parseError(text));
	}

	/**
	 * 從第一行「ERR CODE k=v | reason | hint=...」(docs/SPEC.md S3.0d)解析出錯誤碼、鍵值、原因和 hint。
	 * 鍵值段的值可能是含 | 的引號字串(例如 missing="cobblestone|#stone_tool_materials:3"，P2-4)，
	 * 所以鍵值段結束的「 | 」要跳過引號找；後面的原因是自由文字，hint 是最後一個「 | hint=」之後。
	 */
	static JsonObject parseError(String text) {
		JsonObject o = new JsonObject();
		String first = text.lines().findFirst().orElse("");
		if (!first.startsWith("ERR ")) return o;
		int split = -1;
		boolean quoted = false;
		for (int i = 4; i < first.length() && split < 0; i++) {
			char c = first.charAt(i);
			if (quoted && c == '\\') i++;
			else if (c == '"') quoted = !quoted;
			else if (!quoted && first.startsWith(" | ", i)) split = i;
		}
		if (split < 0) return o;
		String head = first.substring(4, split), rest = first.substring(split + 3);
		int sp = head.indexOf(' ');
		o.addProperty("code", sp < 0 ? head : head.substring(0, sp));
		if (sp >= 0 && !head.substring(sp).isBlank()) o.addProperty("data", head.substring(sp).strip());
		int h = rest.lastIndexOf(" | hint=");
		o.addProperty("reason", h < 0 ? rest : rest.substring(0, h));
		if (h >= 0) o.addProperty("hint", rest.substring(h + 8));
		return o;
	}

	public Result call(String tool) throws Exception {
		return call(tool, null);
	}

	/**
	 * 遊戲測試裡要用這個：測試執行緒等待時遊戲不會前進，所以一邊推進 tick 一邊等工具回應。
	 */
	@SuppressWarnings("UnstableApiUsage")
	public Result call(ClientGameTestContext ctx, String tool, JsonObject args) {
		CompletableFuture<Result> f = CompletableFuture.supplyAsync(() -> {
			try {
				return call(tool, args);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		for (int i = 0; i < 20 * 150 && !f.isDone(); i++) ctx.waitTick();
		return f.join();
	}

	@SuppressWarnings("UnstableApiUsage")
	public Result call(ClientGameTestContext ctx, String tool) {
		return call(ctx, tool, null);
	}

	/** 背景呼叫(例如長輪詢 wait_events)，回傳 future，由測試自己推進 tick。 */
	public CompletableFuture<Result> callAsync(String tool, JsonObject args) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return call(tool, args);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	public JsonObject listTools() throws Exception {
		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", ids.incrementAndGet());
		req.addProperty("method", "tools/list");
		return JsonParser.parseString(post(req, Duration.ofSeconds(10)).body()).getAsJsonObject().getAsJsonObject("result");
	}

	/** 小工具：組參數。 */
	public static JsonObject args(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) {
			Object v = kv[i + 1];
			String k = (String) kv[i];
			if (v instanceof Number n) o.addProperty(k, n);
			else if (v instanceof Boolean b) o.addProperty(k, b);
			else if (v instanceof JsonObject j) o.add(k, j);
			else o.addProperty(k, String.valueOf(v));
		}
		return o;
	}
}
