package blockagent.cli;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 本機的假模型伺服器(docs/SPEC.md S12.1)：取代真正的模型 API，**不呼叫任何 AI**。
 * - 記錄 CLI 送來的每個請求(裡面有完整的工具清單、工具結果)。
 * - 照劇本回應：「呼叫某個工具」或「結束」。
 * 支援 Anthropic Messages API(Claude Code)與 OpenAI Responses API(Codex)的最小串流格式。
 */
public final class StubModelServer implements AutoCloseable {
	/** 劇本的一步：呼叫工具 name，參數 input(Anthropic)/arguments(OpenAI，字串)。custom=true 表示 OpenAI 的 custom tool(例如 exec)。 */
	public record Step(String name, JsonObject input, String rawInput, boolean custom) {
		public static Step tool(String name, JsonObject input) {
			return new Step(name, input, null, false);
		}

		public static Step custom(String name, String raw) {
			return new Step(name, null, raw, true);
		}
	}

	private static final Gson GSON = new Gson();
	private final HttpServer http;
	private final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());
	private final List<String> rawBodies = Collections.synchronizedList(new ArrayList<>());
	private final Deque<Step> script = new ArrayDeque<>();
	private final java.util.Map<String, String> openAiReplies = new java.util.concurrent.ConcurrentHashMap<>();
	private int ids;

	public StubModelServer() throws IOException {
		http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
		http.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "stub-model");
			t.setDaemon(true);
			return t;
		}));
		http.createContext("/", this::handle);
		http.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + http.getAddress().getPort();
	}

	public synchronized void script(List<Step> steps) {
		script.clear();
		script.addAll(steps);
	}

	public void openAiReply(String model, String text) {
		openAiReplies.put(model, text);
	}

	public List<JsonObject> requests() {
		return new ArrayList<>(requests);
	}

	public List<String> rawBodies() {
		return new ArrayList<>(rawBodies);
	}

	/** 第一個帶有工具清單的「主對話」請求(Claude Code 另外會送一些不帶工具的小請求，例如產生標題)。 */
	public JsonObject firstMainRequest() {
		for (JsonObject r : requests()) if (r.has("tools") && r.getAsJsonArray("tools").size() > 0) return r;
		return null;
	}

	private synchronized Step next() {
		return script.pollFirst();
	}

	@Override
	public void close() {
		http.stop(0);
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			rawBodies.add(ex.getRequestURI() + " " + body);
			String path = ex.getRequestURI().getPath();
			JsonObject req = null;
			try {
				if (!body.isBlank()) req = JsonParser.parseString(body).getAsJsonObject();
			} catch (RuntimeException ignored) {
				// 不是 JSON 的請求(遙測等)
			}
			if (req != null) requests.add(req);
			if (path.endsWith("/messages/count_tokens")) {
				json(ex, "{\"input_tokens\":10}");
			} else if (path.endsWith("/messages")) {
				anthropic(ex, req);
			} else if (path.endsWith("/responses")) {
				openai(ex, req);
			} else if (path.endsWith("/models")) {
				json(ex, "{\"data\":[{\"id\":\"stub-model\",\"object\":\"model\"}],\"models\":[]}");
			} else {
				json(ex, "{}");
			}
		}
	}

	private static void json(HttpExchange ex, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, b.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(b);
		}
	}

	private static void sse(OutputStream os, String event, JsonObject data) throws IOException {
		os.write(("event: " + event + "\ndata: " + GSON.toJson(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
		os.flush();
	}

	// ---- Anthropic Messages API(Claude Code) ----

	private void anthropic(HttpExchange ex, JsonObject req) throws IOException {
		boolean main = req != null && req.has("tools") && req.getAsJsonArray("tools").size() > 0;
		Step step = main ? next() : null;
		ex.getResponseHeaders().set("Content-Type", "text/event-stream");
		ex.sendResponseHeaders(200, 0);
		try (OutputStream os = ex.getResponseBody()) {
			String id;
			synchronized (this) {
				id = "msg_" + (++ids);
			}
			JsonObject msg = new JsonObject();
			msg.addProperty("id", id);
			msg.addProperty("type", "message");
			msg.addProperty("role", "assistant");
			msg.addProperty("model", req != null && req.has("model") ? req.get("model").getAsString() : "stub");
			msg.add("content", new JsonArray());
			msg.add("stop_reason", null);
			msg.add("stop_sequence", null);
			JsonObject usage = new JsonObject();
			usage.addProperty("input_tokens", 1);
			usage.addProperty("output_tokens", 1);
			msg.add("usage", usage);
			JsonObject start = new JsonObject();
			start.addProperty("type", "message_start");
			start.add("message", msg);
			sse(os, "message_start", start);

			JsonObject block = new JsonObject();
			JsonObject cbs = new JsonObject();
			cbs.addProperty("type", "content_block_start");
			cbs.addProperty("index", 0);
			JsonObject delta = new JsonObject();
			delta.addProperty("type", "content_block_delta");
			delta.addProperty("index", 0);
			JsonObject d = new JsonObject();
			String stop;
			if (step != null) {
				block.addProperty("type", "tool_use");
				block.addProperty("id", "toolu_" + id);
				block.addProperty("name", step.name());
				block.add("input", new JsonObject());
				d.addProperty("type", "input_json_delta");
				d.addProperty("partial_json", GSON.toJson(step.input() == null ? new JsonObject() : step.input()));
				stop = "tool_use";
			} else {
				block.addProperty("type", "text");
				block.addProperty("text", "");
				d.addProperty("type", "text_delta");
				d.addProperty("text", "結束");
				stop = "end_turn";
			}
			cbs.add("content_block", block);
			sse(os, "content_block_start", cbs);
			delta.add("delta", d);
			sse(os, "content_block_delta", delta);
			JsonObject cbe = new JsonObject();
			cbe.addProperty("type", "content_block_stop");
			cbe.addProperty("index", 0);
			sse(os, "content_block_stop", cbe);
			JsonObject md = new JsonObject();
			md.addProperty("type", "message_delta");
			JsonObject mdd = new JsonObject();
			mdd.addProperty("stop_reason", stop);
			mdd.add("stop_sequence", null);
			md.add("delta", mdd);
			JsonObject u2 = new JsonObject();
			u2.addProperty("output_tokens", 1);
			md.add("usage", u2);
			sse(os, "message_delta", md);
			JsonObject ms = new JsonObject();
			ms.addProperty("type", "message_stop");
			sse(os, "message_stop", ms);
		}
	}

	// ---- OpenAI Responses API(Codex) ----

	private void openai(HttpExchange ex, JsonObject req) throws IOException {
		String model = req != null && req.has("model") ? req.get("model").getAsString() : "";
		String reply = openAiReplies.get(model);
		Step step = reply == null ? next() : null;
		ex.getResponseHeaders().set("Content-Type", "text/event-stream");
		ex.sendResponseHeaders(200, 0);
		try (OutputStream os = ex.getResponseBody()) {
			String id;
			synchronized (this) {
				id = "resp_" + (++ids);
			}
			JsonObject resp = new JsonObject();
			resp.addProperty("id", id);
			JsonObject created = new JsonObject();
			created.addProperty("type", "response.created");
			created.add("response", resp);
			sse(os, "response.created", created);

			JsonObject item = new JsonObject();
			if (step == null) {
				item.addProperty("type", "message");
				item.addProperty("role", "assistant");
				item.addProperty("id", "msg_" + id);
				JsonArray content = new JsonArray();
				JsonObject t = new JsonObject();
				t.addProperty("type", "output_text");
				t.addProperty("text", reply == null ? "結束" : reply);
				content.add(t);
				item.add("content", content);
			} else if (step.custom()) {
				item.addProperty("type", "custom_tool_call");
				item.addProperty("id", "ctc_" + id);
				item.addProperty("call_id", "call_" + id);
				item.addProperty("name", step.name());
				item.addProperty("input", step.rawInput());
			} else {
				item.addProperty("type", "function_call");
				item.addProperty("id", "fc_" + id);
				item.addProperty("call_id", "call_" + id);
				item.addProperty("name", step.name());
				item.addProperty("arguments", GSON.toJson(step.input() == null ? new JsonObject() : step.input()));
			}
			JsonObject added = new JsonObject();
			added.addProperty("type", "response.output_item.added");
			added.addProperty("output_index", 0);
			added.add("item", item);
			sse(os, "response.output_item.added", added);
			JsonObject doneItem = new JsonObject();
			doneItem.addProperty("type", "response.output_item.done");
			doneItem.addProperty("output_index", 0);
			doneItem.add("item", item);
			sse(os, "response.output_item.done", doneItem);

			JsonObject completed = new JsonObject();
			completed.addProperty("type", "response.completed");
			JsonObject r2 = new JsonObject();
			r2.addProperty("id", id);
			JsonObject usage = new JsonObject();
			usage.addProperty("input_tokens", 1);
			usage.addProperty("output_tokens", 1);
			usage.addProperty("total_tokens", 2);
			r2.add("usage", usage);
			completed.add("response", r2);
			sse(os, "response.completed", completed);
		}
	}
}
