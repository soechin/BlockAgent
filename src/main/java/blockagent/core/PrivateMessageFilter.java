package blockagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 系統訊息的私訊判斷(docs/SPEC.md S3.5 第 3 點)。玩家聊天的私訊用 ChatType 判斷，不經過這裡。
 */
public final class PrivateMessageFilter {
	/** 原版與常見伺服器外掛的私訊格式。 */
	public static final List<String> DEFAULT_PATTERNS = List.of(
		"(?i)^\\S+ whispers to you:.*",
		"(?i)^You whisper to \\S+:.*",
		"^\\S+ 悄悄地對你說：.*",
		"^你悄悄地對 \\S+ 說：.*",
		"^\\[\\S+ -> (me|我)\\].*",
		"^\\[(me|我) -> \\S+\\].*",
		"(?i)^\\[?\\S+ -> you\\]?:? .*",
		"(?i)^\\(from \\S+\\):? .*",
		"(?i)^\\(to \\S+\\):? .*",
		"(?i)^\\[msg\\].*",
		"(?i)^\\[pm\\].*",
		"(?i)^\\[dm\\].*"
	);

	private final List<Pattern> patterns = new ArrayList<>();
	private final List<String> invalid = new ArrayList<>();

	/**
	 * extra 是設定檔裡使用者加的格式。寫錯的 regex 略過並記在 {@link #invalidPatterns()}，不讓模組初始化失敗；
	 * 一律加 DOTALL，多行的私訊也要整段比對得到(M3)。
	 */
	public PrivateMessageFilter(List<String> extra) {
		for (String p : DEFAULT_PATTERNS) patterns.add(Pattern.compile(p, Pattern.DOTALL));
		if (extra != null) {
			for (String p : extra) {
				if (p == null) continue;
				try {
					patterns.add(Pattern.compile(p, Pattern.DOTALL));
				} catch (PatternSyntaxException e) {
					invalid.add(p);
				}
			}
		}
	}

	/** 設定檔裡寫錯、被略過的格式。 */
	public List<String> invalidPatterns() {
		return List.copyOf(invalid);
	}

	public boolean isPrivate(String plainText) {
		if (plainText == null) return false;
		String t = plainText.strip();
		for (Pattern p : patterns) if (p.matcher(t).matches()) return true;
		return false;
	}
}
