package blockagent.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 所有工具的名稱、說明、參數 schema、MCP annotations 與處理函式。和 Minecraft 無關(docs/SPEC.md S2.1、S11.2)。
 * Claude Code 和 Codex 看到的工具清單都由這裡產生(S2.3)。
 */
public final class ToolRegistry {
	@FunctionalInterface
	public interface Handler {
		ToolResult call(JsonObject args) throws Exception;
	}

	/**
	 * MCP Tool Annotations(protocol 2025-03-26 起)：讓一般 MCP client 分得出唯讀觀察和會改變世界的動作。
	 * openWorld 只有發言(對真人說話)是 true；遊戲世界本身由這個模組完整掌握。
	 */
	public record Hints(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {
		/** 只讀取狀態。 */
		public static final Hints READ = new Hints(true, false, true, false);
		/** 改變狀態，但不會毀掉東西(走路、合成、放東西…)。 */
		public static final Hints ACTION = new Hints(false, false, false, false);
		/** 改變狀態，重複呼叫結果一樣(換裝備、設定 HUD、開關反射…)。 */
		public static final Hints IDEMPOTENT = new Hints(false, false, true, false);
		/** 可能挖掉、拆掉、打死或丟掉東西。 */
		public static final Hints DESTRUCTIVE = new Hints(false, true, false, false);
		/** 對其他真人玩家說話。 */
		public static final Hints CHAT = new Hints(false, false, false, true);

		JsonObject json() {
			JsonObject o = new JsonObject();
			o.addProperty("readOnlyHint", readOnly);
			if (!readOnly) {
				o.addProperty("destructiveHint", destructive);
				o.addProperty("idempotentHint", idempotent);
			}
			o.addProperty("openWorldHint", openWorld);
			return o;
		}
	}

	public record Tool(String name, String description, Hints hints, JsonObject inputSchema, Handler handler) {
	}

	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private volatile Function<String, String> responseSuffix = name -> "";
	private volatile java.util.function.BiConsumer<String, String> onError = (n, m) -> {
	};

	/** 工具呼叫因參數錯誤或內部例外失敗時通知(R14：給遊戲端 ActionLog 補記)。 */
	public void setOnError(java.util.function.BiConsumer<String, String> onError) {
		this.onError = onError == null ? (n, m) -> {
		} : onError;
	}

	public synchronized ToolRegistry register(String name, String description, Hints hints, JsonObject schema, Handler handler) {
		if (!name.matches("[a-z][a-z0-9_]{0,47}")) throw new IllegalArgumentException("工具名稱格式錯誤：" + name);
		if (tools.containsKey(name)) throw new IllegalArgumentException("工具重複註冊：" + name);
		tools.put(name, new Tool(name, description, hints, schema, handler));
		return this;
	}

	/** 沒有指定 annotations 的工具(測試、CLI 權限測試用的替身)。 */
	public ToolRegistry register(String name, String description, JsonObject schema, Handler handler) {
		return register(name, description, null, schema, handler);
	}

	/** 依工具名稱決定在回應附加的文字，例如未讀事件摘要(S5.4)。 */
	public void setResponseSuffix(Function<String, String> suffix) {
		this.responseSuffix = suffix;
	}

	public synchronized List<Tool> list() {
		return Collections.unmodifiableList(new ArrayList<>(tools.values()));
	}

	public synchronized boolean has(String name) {
		return tools.containsKey(name);
	}

	public JsonArray listJson() {
		JsonArray arr = new JsonArray();
		for (Tool t : list()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.inputSchema().deepCopy());
			if (t.hints() != null) o.add("annotations", t.hints().json());
			arr.add(o);
		}
		return arr;
	}

	private void notifyError(String name, String message) {
		try {
			onError.accept(name, message);
		} catch (RuntimeException ignored) {
			// 記日誌失敗不能影響工具回應
		}
	}

	public ToolResult call(String name, JsonObject args) {
		Tool t;
		synchronized (this) {
			t = tools.get(name);
		}
		if (t == null) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, "unknown tool: " + name);
		ToolResult r;
		try {
			r = t.handler().call(args == null ? new JsonObject() : args);
		} catch (Args.BadArgument e) {
			notifyError(name, ErrorCode.INVALID_ARGUMENT + " " + e.getMessage());
			r = ToolResult.error(ErrorCode.INVALID_ARGUMENT, e.getMessage());
		} catch (Exception e) {
			notifyError(name, ErrorCode.INTERNAL + " " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
			r = ToolResult.error(ErrorCode.INTERNAL, "internal error: " + e.getClass().getSimpleName()
				+ (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
		}
		if (r.skipUnreadSuffix()) return r;
		String suffix;
		try {
			suffix = responseSuffix.apply(name);
		} catch (RuntimeException e) {
			suffix = "";
		}
		return r.withSuffix(suffix);
	}
}
