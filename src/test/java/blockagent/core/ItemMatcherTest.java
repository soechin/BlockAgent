package blockagent.core;

import blockagent.core.ItemMatcher.DurPref;
import blockagent.core.ItemMatcher.ItemInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** T16(a)：ItemMatcher 語法解析、屬性比對與耐久排序測試(docs/SPEC.md S4.2d)。 */
class ItemMatcherTest {
	private static ItemInfo pick(Map<String, Integer> ench, int remDur, int maxDur, String name) {
		return new ItemInfo(ench, Map.of(), null, List.of(), name, null, remDur, maxDur);
	}

	private static ItemInfo book(Map<String, Integer> stored, String title) {
		return new ItemInfo(Map.of(), stored, null, List.of(), null, title, 0, 0);
	}

	private static ItemInfo potion(String name, List<String> effects) {
		return new ItemInfo(Map.of(), Map.of(), name, effects, null, null, 0, 0);
	}

	@Test
	void matchesEnchantmentsStoredPotionNameAndShorthand() {
		ItemInfo silkPick = pick(Map.of("silk_touch", 1, "efficiency", 4), 1200, 1561, "Mine Pick");
		ItemInfo plainPick = pick(Map.of("efficiency", 2), 1500, 1561, null);
		ItemInfo mendingBook = book(Map.of("mending", 1), null);
		ItemInfo diary = book(Map.of(), "Base Rules");
		ItemInfo firePot = potion("long_fire_resistance", List.of("fire_resistance"));

		assertTrue(ItemMatcher.parse("ench=efficiency:3").matches(silkPick));
		assertFalse(ItemMatcher.parse("ench=efficiency:3").matches(plainPick));
		assertTrue(ItemMatcher.parse("ench=silk_touch;name=mine").matches(silkPick));
		assertFalse(ItemMatcher.parse("ench=silk_touch;name=other").matches(silkPick));

		assertTrue(ItemMatcher.parse("stored=mending").matches(mendingBook));
		assertTrue(ItemMatcher.parse("mending").matches(mendingBook));
		assertFalse(ItemMatcher.parse("stored=unbreaking").matches(mendingBook));

		assertTrue(ItemMatcher.parse("potion=long_fire_resistance").matches(firePot));
		assertTrue(ItemMatcher.parse("potion=fire_resistance").matches(firePot));
		assertTrue(ItemMatcher.parse("fire_resistance").matches(firePot));
		assertFalse(ItemMatcher.parse("potion=night_vision").matches(firePot));

		assertTrue(ItemMatcher.parse("name=rules").matches(diary));
		assertTrue(ItemMatcher.parse("rules").matches(diary));
		assertTrue(ItemMatcher.parse("silk_touch").matches(silkPick));
	}

	@Test
	void durabilityPreferenceOrdering() {
		ItemInfo fresh = pick(Map.of(), 1500, 1561, null);
		ItemInfo worn = pick(Map.of(), 120, 1561, null);
		ItemInfo nonDurA = book(Map.of(), "A");
		ItemInfo nonDurB = book(Map.of(), "B");

		ItemMatcher best = ItemMatcher.parse("best_dur");
		assertEquals(DurPref.BEST, best.durPref());
		assertTrue(best.comparePreference(fresh, worn) < 0);
		assertTrue(best.comparePreference(worn, fresh) > 0);
		assertEquals(0, best.comparePreference(nonDurA, nonDurB));

		ItemMatcher low = ItemMatcher.parse("low_dur");
		assertEquals(DurPref.LOW, low.durPref());
		assertTrue(low.comparePreference(worn, fresh) < 0);
		assertTrue(low.comparePreference(fresh, worn) > 0);
		assertEquals(0, low.comparePreference(nonDurA, nonDurB));

		assertEquals(0, ItemMatcher.ANY.comparePreference(fresh, worn));
	}

	@Test
	void parseSpecHandlesPlainAndBracketSyntax() {
		var plain = ItemMatcher.parseSpec("oak_log");
		assertEquals("oak_log", plain.itemId());
		assertFalse(plain.hasMatcher());

		var filtered = ItemMatcher.parseSpec("diamond_pickaxe[silk_touch;best_dur]");
		assertEquals("diamond_pickaxe", filtered.itemId());
		assertTrue(filtered.hasMatcher());
		assertEquals(DurPref.BEST, filtered.matcher().durPref());
		assertTrue(filtered.matcher().matches(pick(Map.of("silk_touch", 1), 100, 1561, null)));
	}

	@Test
	void rejectsMalformedSyntax() {
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parse(""));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parse("ench=efficiency:0"));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parse("ench=efficiency:abc"));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parse("unknown=foo"));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parse("best_dur;low_dur"));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parse("mending;;best_dur"));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parseSpec("potion[]"));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parseSpec("potion[fire_resistance"));
		assertThrows(IllegalArgumentException.class, () -> ItemMatcher.parseSpec("potion]"));
	}
}
