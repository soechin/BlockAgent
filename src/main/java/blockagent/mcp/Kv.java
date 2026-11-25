package blockagent.mcp;

import java.util.Map;

/**
 * 組出一行 Compact KV(docs/SPEC.md S3.0a)：空白分隔的 {@code key=value}。和 Minecraft 無關。
 * 值裡有空白、引號、{@code |}、{@code =} 或是空字串時自動加上 JSON 式雙引號；玩家寫的文字用 {@link #quoted} 一律加引號。
 * null 值直接略過，呼叫端不用自己判斷選填欄位。
 */
public final class Kv {
	private final StringBuilder sb = new StringBuilder();

	private Kv() {
	}

	public static Kv of() {
		return new Kv();
	}

	/** 開頭是一個字(例如 {@code OK}、{@code started})，後面再接鍵值。 */
	public static Kv of(String head) {
		return new Kv().word(head);
	}

	/** 加一個不是鍵值的字，例如動詞。 */
	public Kv word(String w) {
		if (w == null || w.isEmpty()) return this;
		sep();
		sb.append(w);
		return this;
	}

	public Kv put(String key, Object value) {
		if (value == null) return this;
		sep();
		sb.append(key).append('=').append(value(value));
		return this;
	}

	public Kv putIf(boolean cond, String key, Object value) {
		return cond ? put(key, value) : this;
	}

	/** 玩家寫的文字：一律加引號，保持原文。 */
	public Kv quoted(String key, String value) {
		if (value == null) return this;
		sep();
		sb.append(key).append('=').append(quote(value));
		return this;
	}

	/** 接上另一段已經組好的鍵值(例如共用的狀態尾巴)。 */
	public Kv add(Kv other) {
		if (other == null || other.isEmpty()) return this;
		sep();
		sb.append(other.sb);
		return this;
	}

	/** 接上已經是 KV 格式的文字。 */
	public Kv raw(String kv) {
		if (kv == null || kv.isBlank()) return this;
		sep();
		sb.append(kv.strip());
		return this;
	}

	public boolean isEmpty() {
		return sb.isEmpty();
	}

	private void sep() {
		if (!sb.isEmpty()) sb.append(' ');
	}

	@Override
	public String toString() {
		return sb.toString();
	}

	/** {@code id:數量} 清單，逗號分隔；空的寫 none(S3.0b)。 */
	public static String counts(Map<String, Integer> counts) {
		if (counts == null || counts.isEmpty()) return "none";
		StringBuilder s = new StringBuilder();
		counts.forEach((k, v) -> {
			if (!s.isEmpty()) s.append(',');
			s.append(k).append(':').append(v);
		});
		return s.toString();
	}

	static String value(Object v) {
		String s = v instanceof Double d ? fmt(d) : v instanceof Float f ? fmt(f) : String.valueOf(v);
		return needsQuote(s) ? quote(s) : s;
	}

	private static String fmt(double d) {
		if (d == Math.rint(d) && Math.abs(d) < 1e9) return Long.toString((long) d);
		return String.format(java.util.Locale.ROOT, "%.1f", d);
	}

	static boolean needsQuote(String s) {
		if (s.isEmpty()) return true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '"' || c == '|' || c == '=' || c == '\\') return true;
		}
		return false;
	}

	/** JSON 字串寫法：雙引號，跳脫引號、反斜線和控制字元。 */
	public static String quote(String s) {
		StringBuilder q = new StringBuilder(s.length() + 2).append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> q.append("\\\"");
				case '\\' -> q.append("\\\\");
				case '\n' -> q.append("\\n");
				case '\r' -> q.append("\\r");
				case '\t' -> q.append("\\t");
				default -> {
					if (c < 0x20) q.append(String.format("\\u%04x", (int) c));
					else q.append(c);
				}
			}
		}
		return q.append('"').toString();
	}
}
