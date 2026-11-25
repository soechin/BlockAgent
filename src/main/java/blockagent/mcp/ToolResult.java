package blockagent.mcp;

import com.google.gson.JsonObject;

/**
 * 工具的回傳結果(docs/SPEC.md S3.0c、S3.0d、S3.6)。
 * 成功：文字由工具自己組(動作以 {@code OK} 開頭，觀察直接是內容)。
 * 失敗：一行 {@code ERR <CODE> <kv> | <reason> | hint=<next>}，code、reason、hint 也留在 structured 裡給測試和紀錄用。
 */
public final class ToolResult {
	private final boolean ok;
	private final String text;
	private final JsonObject structured;
	private final boolean skipUnreadSuffix;

	private ToolResult(boolean ok, String text, JsonObject structured) {
		this(ok, text, structured, false);
	}

	private ToolResult(boolean ok, String text, JsonObject structured, boolean skipUnreadSuffix) {
		this.ok = ok;
		this.text = text;
		this.structured = structured;
		this.skipUnreadSuffix = skipUnreadSuffix;
	}

	public static ToolResult ok(String text) {
		JsonObject s = new JsonObject();
		s.addProperty("ok", true);
		return new ToolResult(true, text, s);
	}

	public static ToolResult ok(Kv kv) {
		return ok(kv.toString());
	}

	public static ToolResult ok(String text, JsonObject data) {
		JsonObject s = data.deepCopy();
		s.addProperty("ok", true);
		return new ToolResult(true, text, s);
	}

	public static ToolResult error(ErrorCode code, String reason) {
		return error(code, null, reason, null);
	}

	public static ToolResult error(ErrorCode code, String reason, String hint) {
		return error(code, null, reason, hint);
	}

	public static ToolResult error(ErrorCode code, Kv data, String reason) {
		return error(code, data, reason, null);
	}

	/**
	 * @param data   可以直接拿來用的鍵值(例如 missing=、pos=)，沒有就 null
	 * @param reason 一句短英文
	 * @param hint   下一步；null 時用錯誤碼的預設({@link ErrorCode#hint})，空字串表示不給
	 */
	public static ToolResult error(ErrorCode code, Kv data, String reason, String hint) {
		if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason 不能是空的");
		String h = hint == null ? code.hint() : hint.isBlank() ? null : hint;
		JsonObject s = new JsonObject();
		s.addProperty("ok", false);
		s.addProperty("code", code.name());
		s.addProperty("reason", reason);
		if (data != null && !data.isEmpty()) s.addProperty("data", data.toString());
		if (h != null) s.addProperty("hint", h);
		return new ToolResult(false, line(code.name(), data == null ? null : data.toString(), reason, h), s);
	}

	/** 錯誤那一行；任務失敗事件也用同一個寫法。 */
	public static String line(String code, String data, String reason, String hint) {
		StringBuilder sb = new StringBuilder("ERR ").append(code);
		if (data != null && !data.isBlank()) sb.append(' ').append(data.strip());
		sb.append(" | ").append(oneLine(reason));
		if (hint != null && !hint.isBlank()) sb.append(" | hint=").append(oneLine(hint));
		return sb.toString();
	}

	private static String oneLine(String s) {
		return s.strip().replace("\r", " ").replace("\n", " ");
	}

	public boolean isOk() {
		return ok;
	}

	public String text() {
		return text;
	}

	public JsonObject structured() {
		return structured.deepCopy();
	}

	public String code() {
		return ok ? null : structured.get("code").getAsString();
	}

	public String reason() {
		return ok ? null : structured.get("reason").getAsString();
	}

	public String hint() {
		return ok || !structured.has("hint") ? null : structured.get("hint").getAsString();
	}

	public boolean skipUnreadSuffix() {
		return skipUnreadSuffix;
	}

	public ToolResult withSuffix(String suffix) {
		if (suffix == null || suffix.isEmpty()) return this;
		return new ToolResult(ok, text + "\n" + suffix, structured, skipUnreadSuffix);
	}

	/** 工具本身已處理事件等待(例如 taskTool 帶 wait_sec > 0)，呼叫端不要再額外倒出未讀事件摘要(M4、P3-M11)。 */
	public ToolResult withSuffixHandled(String suffix) {
		String t = suffix == null || suffix.isEmpty() ? text : text + "\n" + suffix;
		return new ToolResult(ok, t, structured, true);
	}
}
