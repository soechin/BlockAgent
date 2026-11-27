package blockagent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S3.2a：告示牌文字串接與書本分頁純邏輯測試。 */
class BookReaderTest {
	@Test
	void joinsNonBlankSignLinesAndClipsSummary() {
		assertEquals("倉儲/鐵礦區", BookReader.joinSignLines(List.of(" 倉儲 ", "", "  ", "鐵礦區")));
		assertEquals("", BookReader.joinSignLines(List.of("", "   ")));
		assertEquals("abc", BookReader.clipSummary("abc", 10));
		assertEquals("abcde…", BookReader.clipSummary("abcdefgh", 5));
	}

	@Test
	void paginatesLongBooksAtCharLimit() {
		String pageText = "A".repeat(700);
		var first = BookReader.formatBook("Guide", "Ming", List.of(pageText, pageText, "End"), 1, BookReader.MAX_BOOK_CHARS);
		assertEquals("Guide", first.title());
		assertEquals("Ming", first.author());
		assertEquals(3, first.totalPages());
		assertEquals(1, first.startPage());
		assertTrue(first.truncated());
		assertEquals(2, first.nextPage());
		assertTrue(first.text().startsWith("[p1] "));

		var second = BookReader.formatBook("Guide", "Ming", List.of(pageText, pageText, "End"), 2, BookReader.MAX_BOOK_CHARS);
		assertEquals(2, second.startPage());
		assertFalse(second.truncated());
		assertNull(second.nextPage());
		assertTrue(second.text().contains("[p2] ") && second.text().contains("[p3] End"));

		assertThrows(IllegalArgumentException.class,
			() -> BookReader.formatBook("Guide", "Ming", List.of("one"), 2, BookReader.MAX_BOOK_CHARS));
	}
}
