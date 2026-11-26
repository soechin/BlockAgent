package blockagent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * MCP Streamable HTTP 伺服器(docs/SPEC.md S11.1)。
 * 只綁 127.0.0.1；每個請求都要帶 Bearer token；同時只有一個 MCP session；回應一律是 JSON(不用 SSE)。
 */
public final class McpServer implements AutoCloseable {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("blockagent");
	public static final List<String> SUPPORTED_PROTOCOLS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
	private static final int MAX_BODY = 1 << 20;
	private static final Gson GSON = new Gson();
	/** 超過這麼久沒有任何請求、也沒有進行中的呼叫，就當成 AI 端可能已經離開(S8.3)。模型想很久時也可能超過，所以只用在畫面提示。 */
	public static final long SILENT_AFTER_MS = 60_000;

	public record ClientInfo(String sessionId, String protocolVersion, String clientName, String clientVersion) {
	}

	private final ToolRegistry registry;
	private final String token;
	private final String serverVersion;
	private final String instructions;

	/** MCP Prompts(A13)：一般 MCP client 用 prompts/list、prompts/get 取得的提示詞。text 每次取用時才讀。 */
	public record Prompt(String name, String description, Supplier<String> text) {
	}

	private final java.util.Map<String, Prompt> prompts = new java.util.concurrent.ConcurrentHashMap<>();
	private HttpServer http;
	private ExecutorService executor;
	private volatile String sessionId;
	private volatile Consumer<ClientInfo> onInitialize = c -> {};
	private volatile Runnable onClose = () -> {};
	private volatile Supplier<JsonObject> statusSupplier = JsonObject::new;
	// 規劃服務用的端點(S11.7)：另一把 token，只能讀規劃快照、送回規劃結果，不能呼叫工具
	private volatile String planToken;
	private volatile PlanBridge planBridge;
	// AI 端的活動(S8.3)：目前 session 的最後一次請求時間和進行中的工具呼叫數
	private volatile LongSupplier clockNanos = System::nanoTime;
	private volatile long lastActivityNanos;
	private final AtomicInteger callsInFlight = new AtomicInteger();

	public McpServer(ToolRegistry registry, String token, String serverVersion, String instructions) {
		if (token == null || token.length() < 32) throw new IllegalArgumentException("token 太短");
		this.registry = registry;
		this.token = token;
		this.serverVersion = serverVersion;
		this.instructions = instructions;
	}

	public static String newToken() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	/** 加一個 prompt(在 start 之前呼叫；有任何 prompt 時 initialize 才宣告 prompts capability)。 */
	public void addPrompt(String name, String description, Supplier<String> text) {
		prompts.put(name, new Prompt(name, description, text));
	}

	public void setOnInitialize(Consumer<ClientInfo> c) {
		this.onInitialize = c;
	}

	/** AI 端用 DELETE 結束 MCP session 時呼叫。 */
	public void setOnClose(Runnable r) {
		this.onClose = r;
	}

	/** 測試用：換掉計時用的時鐘(奈秒)。 */
	void setClock(LongSupplier nanos) {
		this.clockNanos = nanos;
	}

	/** 從 MCP 的活動推測 AI 端在不在(S8.3)。 */
	public AiPresence presence() {
		if (sessionId == null) return AiPresence.NONE;
		if (callsInFlight.get() > 0) return AiPresence.BUSY;
		long idleMs = (clockNanos.getAsLong() - lastActivityNanos) / 1_000_000;
		return idleMs >= SILENT_AFTER_MS ? AiPresence.SILENT : AiPresence.ACTIVE;
	}

	private void touch() {
		lastActivityNanos = clockNanos.getAsLong();
	}

	/** GET /status 的內容(給 Claude Code 的 Stop hook 查詢工作階段狀態，S11.3)。 */
	public void setStatusSupplier(Supplier<JsonObject> s) {
		this.statusSupplier = s;
	}

	/** 規劃服務的兩個端點：GET /plan/context 回傳快照文字；POST /plan/result 交出結果，回傳 null 表示接受，否則是拒絕原因。 */
	public interface PlanBridge {
		JsonObject context();

		String accept(JsonObject result);
	}

	/** 開放規劃服務的端點(S11.7)。token 跟 MCP 的不同：拿到它的程式不能操作遊戲。 */
	public void setPlanBridge(String token, PlanBridge bridge) {
		if (token == null || token.length() < 32) throw new IllegalArgumentException("token 太短");
		if (token.equals(this.token)) throw new IllegalArgumentException("規劃 token 不能跟 MCP token 相同");
		this.planToken = token;
		this.planBridge = bridge;
	}

	/** 從 preferredPort 開始往上找可用的 port，回傳實際使用的 port。 */
	public synchronized int start(int preferredPort) throws IOException {
		if (http != null) throw new IllegalStateException("已經啟動");
		InetAddress loopback = InetAddress.getByName("127.0.0.1");
		IOException last = null;
		for (int p = preferredPort; p < preferredPort + 50 && p <= 65535; p++) {
			try {
				http = HttpServer.create(new InetSocketAddress(loopback, p), 16);
				break;
			} catch (BindException e) {
				last = e;
			}
		}
		if (http == null) throw last != null ? last : new IOException("找不到可用的 port");
		AtomicInteger n = new AtomicInteger();
		executor = Executors.newFixedThreadPool(8, r -> {
			Thread t = new Thread(r, "blockagent-mcp-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		http.setExecutor(executor);
		http.createContext("/mcp", this::handleMcp);
		http.createContext("/status", this::handleStatus);
		http.createContext("/plan/context", this::handlePlanContext);
		http.createContext("/plan/result", this::handlePlanResult);
		http.start();
		return http.getAddress().getPort();
	}

	public int port() {
		return http.getAddress().getPort();
	}

	@Override
	public synchronized void close() {
		if (http != null) {
			http.stop(0);
			http = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	// ---- HTTP ----

	private boolean checkCommon(HttpExchange ex) throws IOException {
		return checkCommon(ex, token);
	}

	private boolean checkCommon(HttpExchange ex, String expected) throws IOException {
		String origin = ex.getRequestHeaders().getFirst("Origin");
		if (origin != null && !isLocalOrigin(origin)) {
			drain(ex);
			send(ex, 403, null);
			return false;
		}
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		if (expected == null || auth == null || !auth.startsWith("Bearer ") || !constantTimeEquals(auth.substring(7).trim(), expected)) {
			drain(ex);
			ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
			send(ex, 401, null);
			return false;
		}
		return true;
	}

	/** 拒絕前先讀完 body，否則連線會被直接關掉，用戶端只看到 EOF。 */
	private static void drain(HttpExchange ex) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			in.skip(MAX_BODY);
		}
	}

	static boolean isLocalOrigin(String origin) {
		try {
			String host = URI.create(origin).getHost();
			return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host) || "::1".equals(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private void handleStatus(HttpExchange ex) throws IOException {
		try (ex) {
			if (!checkCommon(ex)) return;
			if (!"GET".equals(ex.getRequestMethod())) {
				send(ex, 405, null);
				return;
			}
			send(ex, 200, statusSupplier.get());
		}
	}

	private void handlePlanContext(HttpExchange ex) throws IOException {
		try (ex) {
			PlanBridge bridge = planBridge;
			if (!checkCommon(ex, bridge == null ? null : planToken)) return;
			if (!"GET".equals(ex.getRequestMethod())) {
				send(ex, 405, null);
				return;
			}
			JsonObject o;
			try {
				o = bridge.context();
			} catch (RuntimeException e) {
				LOG.error("規劃快照失敗", e);
				send(ex, 500, null);
				return;
			}
			send(ex, 200, o);
		}
	}

	private void handlePlanResult(HttpExchange ex) throws IOException {
		try (ex) {
			PlanBridge bridge = planBridge;
			if (!checkCommon(ex, bridge == null ? null : planToken)) return;
			if (!"POST".equals(ex.getRequestMethod())) {
				drain(ex);
				send(ex, 405, null);
				return;
			}
			byte[] body;
			try (InputStream in = ex.getRequestBody()) {
				body = in.readNBytes(MAX_BODY + 1);
			}
			if (body.length > MAX_BODY) {
				send(ex, 413, null);
				return;
			}
			JsonObject result;
			try {
				JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
				if (!parsed.isJsonObject()) throw new JsonParseException("not an object");
				result = parsed.getAsJsonObject();
			} catch (JsonParseException e) {
				send(ex, 400, errorBody("body must be a JSON object"));
				return;
			}
			String err;
			try {
				err = bridge.accept(result);
			} catch (RuntimeException e) {
				LOG.error("規劃結果處理失敗", e);
				send(ex, 500, null);
				return;
			}
			if (err != null) {
				send(ex, 400, errorBody(err));
				return;
			}
			JsonObject ok = new JsonObject();
			ok.addProperty("ok", true);
			send(ex, 200, ok);
		}
	}

	private static JsonObject errorBody(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("error", message);
		return o;
	}

	private void handleMcp(HttpExchange ex) throws IOException {
		try (ex) {
			if (!checkCommon(ex)) return;
			switch (ex.getRequestMethod()) {
				case "POST" -> {
					try {
						handlePost(ex);
					} catch (RuntimeException e) {
						// 回應只寫例外類別，完整的堆疊記進日誌，事後查得到(L2)
						LOG.error("MCP 請求處理失敗", e);
						send(ex, 500, rpcError(JsonNull.INSTANCE, -32603, "Internal error: " + e.getClass().getSimpleName()));
					}
				}
				case "DELETE" -> {
					String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
					boolean closed = sid != null && sid.equals(sessionId);
					if (closed) sessionId = null;
					send(ex, 200, null);
					if (closed) {
						try {
							onClose.run();
						} catch (RuntimeException ignored) {
							// 紀錄失敗不影響回應
						}
					}
				}
				default -> {
					// 不提供 GET 的 SSE 串流
					ex.getResponseHeaders().add("Allow", "POST, DELETE");
					send(ex, 405, null);
				}
			}
		}
	}

	private static boolean isString(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
	}

	private void handlePost(HttpExchange ex) throws IOException {
		byte[] body;
		try (InputStream in = ex.getRequestBody()) {
			body = in.readNBytes(MAX_BODY + 1);
		}
		if (body.length > MAX_BODY) {
			send(ex, 413, null);
			return;
		}
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
		} catch (JsonParseException e) {
			send(ex, 400, rpcError(JsonNull.INSTANCE, -32700, "Parse error"));
			return;
		}
		if (!parsed.isJsonObject()) {
			// 2025-06-18 起不支援 JSON-RPC batch
			send(ex, 400, rpcError(JsonNull.INSTANCE, -32600, "Invalid Request"));
			return;
		}
		JsonObject msg = parsed.getAsJsonObject();
		JsonElement id = msg.get("id");
		// 欄位型別不對是用戶端的錯：回 400，不要變成 500(L2)
		if (msg.has("method") && !isString(msg.get("method"))) {
			send(ex, 400, rpcError(JsonNull.INSTANCE, -32600, "Invalid Request: method must be a string"));
			return;
		}
		String method = msg.has("method") ? msg.get("method").getAsString() : null;
		if (method == null) {
			// 用戶端送來的 response(例如 elicitation 回覆)；本伺服器不發 request，直接接受
			send(ex, 202, null);
			return;
		}

		if ("initialize".equals(method)) {
			handleInitialize(ex, msg, id);
			return;
		}

		String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
		if (sessionId == null || sid == null) {
			send(ex, 400, rpcError(id, -32600, "initialize first"));
			return;
		}
		if (!sid.equals(sessionId)) {
			send(ex, 404, rpcError(id, -32001, "session expired; reconnect"));
			return;
		}
		touch();

		if (id == null || id.isJsonNull()) {
			// notification
			send(ex, 202, null);
			return;
		}

		JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
		switch (method) {
			case "ping" -> send(ex, 200, rpcResult(id, new JsonObject()));
			case "tools/list" -> {
				JsonObject r = new JsonObject();
				r.add("tools", registry.listJson());
				send(ex, 200, rpcResult(id, r));
			}
			case "prompts/list" -> {
				var arr = new com.google.gson.JsonArray();
				prompts.values().stream().sorted(java.util.Comparator.comparing(Prompt::name)).forEach(pr -> {
					JsonObject o = new JsonObject();
					o.addProperty("name", pr.name());
					o.addProperty("description", pr.description());
					o.add("arguments", new com.google.gson.JsonArray());
					arr.add(o);
				});
				JsonObject r = new JsonObject();
				r.add("prompts", arr);
				send(ex, 200, rpcResult(id, r));
			}
			case "prompts/get" -> {
				Prompt pr = params.has("name") && params.get("name").isJsonPrimitive() ? prompts.get(params.get("name").getAsString()) : null;
				String text = null;
				if (pr != null) {
					try {
						text = pr.text().get();
					} catch (RuntimeException e) {
						text = null;
					}
				}
				if (text == null) {
					send(ex, 200, rpcError(id, -32602, pr == null ? "unknown prompt" : "prompt unavailable"));
					return;
				}
				JsonObject content = new JsonObject();
				content.addProperty("type", "text");
				content.addProperty("text", text);
				JsonObject message = new JsonObject();
				message.addProperty("role", "user");
				message.add("content", content);
				var messages = new com.google.gson.JsonArray();
				messages.add(message);
				JsonObject r = new JsonObject();
				r.addProperty("description", pr.description());
				r.add("messages", messages);
				send(ex, 200, rpcResult(id, r));
			}
			case "tools/call" -> {
				if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
					send(ex, 200, rpcError(id, -32602, "missing tool name"));
					return;
				}
				String name = params.get("name").getAsString();
				if (!registry.has(name)) {
					send(ex, 200, rpcError(id, -32602, "unknown tool: " + name));
					return;
				}
				JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
					? params.getAsJsonObject("arguments") : new JsonObject();
				ToolResult tr;
				callsInFlight.incrementAndGet();
				try {
					tr = registry.call(name, args);
				} finally {
					touch();
					callsInFlight.decrementAndGet();
				}
				send(ex, 200, rpcResult(id, toCallResult(tr)));
			}
			default -> send(ex, 200, rpcError(id, -32601, "Method not found: " + method));
		}
	}

	private void handleInitialize(HttpExchange ex, JsonObject msg, JsonElement id) throws IOException {
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
		JsonObject ci = params.has("clientInfo") && params.get("clientInfo").isJsonObject() ? params.getAsJsonObject("clientInfo") : null;
		boolean badTypes = params.has("protocolVersion") && !isString(params.get("protocolVersion"))
			|| params.has("clientInfo") && ci == null
			|| ci != null && (ci.has("name") && !isString(ci.get("name")) || ci.has("version") && !isString(ci.get("version")));
		if (badTypes) {
			send(ex, 400, rpcError(id == null ? JsonNull.INSTANCE : id, -32602,
				"Invalid params: protocolVersion, clientInfo.name and clientInfo.version must be strings"));
			return;
		}
		String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
		String version = requested != null && SUPPORTED_PROTOCOLS.contains(requested) ? requested : SUPPORTED_PROTOCOLS.getFirst();
		String clientName = ci != null && ci.has("name") ? ci.get("name").getAsString() : null;
		String clientVersion = ci != null && ci.has("version") ? ci.get("version").getAsString() : null;
		String newSid = newToken();
		touch();
		sessionId = newSid; // 新的 initialize 取代舊的 session

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", version);
		JsonObject caps = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		caps.add("tools", tools);
		if (!prompts.isEmpty()) {
			JsonObject p = new JsonObject();
			p.addProperty("listChanged", false);
			caps.add("prompts", p);
		}
		result.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", "blockagent");
		info.addProperty("version", serverVersion);
		result.add("serverInfo", info);
		if (instructions != null && !instructions.isBlank()) result.addProperty("instructions", instructions);

		ex.getResponseHeaders().add("Mcp-Session-Id", newSid);
		send(ex, 200, rpcResult(id, result));
		try {
			onInitialize.accept(new ClientInfo(newSid, version, clientName, clientVersion));
		} catch (RuntimeException ignored) {
			// 紀錄失敗不影響連線
		}
	}

	static JsonObject toCallResult(ToolResult tr) {
		JsonObject r = new JsonObject();
		var content = new com.google.gson.JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", tr.text());
		content.add(text);
		r.add("content", content);
		// 不回傳 structuredContent：工具沒有宣告 outputSchema，而且 Claude Code 有 structuredContent 時只把它交給模型、丟掉文字
		// (T8 實測)。錯誤碼、原因、細節都寫在文字裡。
		r.addProperty("isError", !tr.isOk());
		return r;
	}

	private static JsonObject rpcResult(JsonElement id, JsonObject result) {
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id == null ? JsonNull.INSTANCE : id);
		o.add("result", result);
		return o;
	}

	private static JsonObject rpcError(JsonElement id, int code, String message) {
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id == null ? JsonNull.INSTANCE : id);
		JsonObject e = new JsonObject();
		e.addProperty("code", code);
		e.addProperty("message", message);
		o.add("error", e);
		return o;
	}

	private static void send(HttpExchange ex, int status, JsonObject body) throws IOException {
		if (body == null) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		byte[] b = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(status, b.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(b);
		}
	}
}
