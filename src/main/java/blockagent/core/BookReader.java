package blockagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 告示牌與書本內容的純文字格式化與分頁邏輯(docs/SPEC.md S3.2a)。
 */
public final class BookReader {
	public static final int MAX_BOOK_CHARS = 1200;
	public static final int MAX_SIGN_SUMMARY = 60;

	public record BookView(String title, String author, int totalPages, int startPage, Integer nextPage,
						   boolean truncated, String text) {
	}

	private BookReader() {
	}

	/** 告示牌 4 行文字去掉空白行後以 {@code /} 串接；全空回傳空字串。 */
	public static String joinSignLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) return "";
		List<String> kept = new ArrayList<>();
		for (String line : lines) {
			if (line != null && !line.isBlank()) kept.add(line.strip());
		}
		return String.join("/", kept);
	}

	/** 依字元上限截斷摘要，超過時尾端加 {@code …}。 */
	public static String clipSummary(String text, int maxChars) {
		if (text == null || text.isEmpty()) return "";
		if (text.codePointCount(0, text.length()) <= maxChars) return text;
		return text.substring(0, text.offsetByCodePoints(0, maxChars)) + "…";
	}

	/**
	 * 讀取書本頁面並依字元上限分頁(1-based {@code startPage})。
	 *
	 * @throws IllegalArgumentException {@code startPage} 小於 1 或超出總頁數時
	 */
	public static BookView formatBook(String title, String author, List<String> pages, int startPage, int maxChars) {
		if (startPage < 1) throw new IllegalArgumentException("page must be >= 1");
		int total = pages == null ? 0 : pages.size();
		if (total == 0) {
			if (startPage > 1) throw new IllegalArgumentException("book has 0 pages");
			return new BookView(nonBlank(title), nonBlank(author), 0, 1, null, false, "");
		}
		if (startPage > total) {
			throw new IllegalArgumentException("page " + startPage + " is out of range (book has " + total + " pages)");
		}
		StringBuilder sb = new StringBuilder();
		boolean truncated = false;
		Integer nextPage = null;
		for (int i = startPage - 1; i < total; i++) {
			String body = normalizePage(pages.get(i));
			String entry = "[p" + (i + 1) + "] " + body;
			if (sb.isEmpty()) {
				if (entry.length() <= maxChars) {
					sb.append(entry);
				} else {
					sb.append(entry, 0, maxChars);
					truncated = true;
					if (i + 1 < total) nextPage = i + 2;
					break;
				}
			} else if (sb.length() + 1 + entry.length() <= maxChars) {
				sb.append('\n').append(entry);
			} else {
				truncated = true;
				nextPage = i + 1;
				break;
			}
		}
		return new BookView(nonBlank(title), nonBlank(author), total, startPage, nextPage, truncated, sb.toString());
	}

	private static String normalizePage(String s) {
		return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
	}

	private static String nonBlank(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
