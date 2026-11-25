package blockagent.core;

/**
 * AI 發言的規則(docs/SPEC.md S6.2、S6.4、S6.5)。
 */
public final class ChatPolicy {
	public static final String PREFIX = "[AI] ";
	public static final int MAX_LENGTH = 256;

	public sealed interface Result permits Ok, Rejected {
	}

	public record Ok(String message) implements Result {
	}

	public record Rejected(String code, String reason) implements Result {
	}

	private final Clock clock;
	private volatile boolean enabled;
	private volatile long minIntervalMs;
	private long lastSent = Long.MIN_VALUE;

	public ChatPolicy(Clock clock, boolean enabled, long minIntervalMs) {
		this.clock = clock;
		this.enabled = enabled;
		this.minIntervalMs = minIntervalMs;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean enabled() {
		return enabled;
	}

	public void setMinIntervalMs(long ms) {
		this.minIntervalMs = ms;
	}

	/** 移除頭尾空白、控制字元、零寬字元、§ 格式碼。 */
	public static String sanitize(String text) {
		if (text == null) return "";
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '§') {
				if (i < text.length()) i += Character.charCount(text.codePointAt(i)); // 連同後面的格式碼字元
				continue;
			}
			if (Character.isISOControl(cp) && cp != ' ') {
				sb.append(' ');
				continue;
			}
			int type = Character.getType(cp);
			if (type == Character.FORMAT || type == Character.UNASSIGNED || type == Character.PRIVATE_USE) continue;
			sb.appendCodePoint(cp);
		}
		return sb.toString().strip().replaceAll("\\s{2,}", " ");
	}

	/** 檢查並組出要送出的訊息；通過時記錄發言時間。 */
	public synchronized Result prepare(String raw) {
		if (!enabled) return new Rejected("CHAT_DISABLED", "chat disabled by user");
		String text = sanitize(raw);
		if (text.isEmpty()) return new Rejected("CHAT_BLOCKED", "message is empty");
		if (text.startsWith("/")) return new Rejected("CHAT_BLOCKED", "messages must not start with /");
		long now = clock.millis();
		if (lastSent != Long.MIN_VALUE && now - lastSent < minIntervalMs) {
			long waitSec = (minIntervalMs - (now - lastSent) + 999) / 1000;
			long sinceSec = (now - lastSent) / 1000;
			return new Rejected("RATE_LIMITED", "too soon: " + sinceSec + "s since last message, min interval "
				+ (minIntervalMs / 1000) + "s; wait " + waitSec + "s");
		}
		String msg = PREFIX + text;
		if (msg.length() > MAX_LENGTH) {
			int end = MAX_LENGTH;
			if (Character.isHighSurrogate(msg.charAt(end - 1))) end--;
			msg = msg.substring(0, end);
		}
		lastSent = now;
		return new Ok(msg);
	}

	/** 最後防線(S6.3)：模組送出的聊天一定要有前綴，而且不是指令。 */
	public static boolean isAllowedOutgoing(String message) {
		return message != null && message.startsWith(PREFIX);
	}
}
