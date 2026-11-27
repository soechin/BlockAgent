package blockagent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkstationPolicyTest {
	@Test
	void enchantPreviewFormatsOffersAndTracksMaxLevel() {
		var preview = WorkstationPolicy.buildEnchantPreview(
			new int[]{7, 15, 30},
			new String[]{"efficiency", "unbreaking", "fortune"},
			new int[]{1, 2, 3});
		assertTrue(preview.hasAny());
		assertEquals(30, preview.maxLevel());
		assertEquals(3, preview.offers().size());
		assertEquals("1:lv7(lapis=1,clue=efficiency:1);2:lv15(lapis=2,clue=unbreaking:2);3:lv30(lapis=3,clue=fortune:3)",
			preview.summary());
	}

	@Test
	void enchantPreviewEmptyWhenAllCostsZero() {
		var preview = WorkstationPolicy.buildEnchantPreview(new int[]{0, 0, 0}, new String[3], new int[3]);
		assertFalse(preview.hasAny());
		assertEquals(0, preview.maxLevel());
		assertEquals("", preview.summary());
	}

	@Test
	void enchantOffersReadyRequiresPositiveCostAndSyncedClue() {
		assertFalse(WorkstationPolicy.enchantOffersReady(new int[]{0, 0, 0}, new int[]{-1, -1, -1}));
		assertFalse(WorkstationPolicy.enchantOffersReady(new int[]{5, 10, 30}, new int[]{1, -1, 3}));
		assertTrue(WorkstationPolicy.enchantOffersReady(new int[]{5, 10, 30}, new int[]{1, 2, 3}));
		assertTrue(WorkstationPolicy.enchantOffersReady(new int[]{0, 0, 30}, new int[]{-1, -1, 3}));
	}

	@Test
	void anvilTooExpensiveRespectsCreativeMode() {
		assertFalse(WorkstationPolicy.anvilTooExpensive(39, false));
		assertTrue(WorkstationPolicy.anvilTooExpensive(40, false));
		assertFalse(WorkstationPolicy.anvilTooExpensive(50, true));
	}

	@Test
	void hasDistinctInputsRejectsSingleSharedItem() {
		assertFalse(WorkstationPolicy.hasDistinctInputs(1, 1, 1));
		assertFalse(WorkstationPolicy.hasDistinctInputs(0, 2, 2));
		assertTrue(WorkstationPolicy.hasDistinctInputs(1, 1, 2));
		assertTrue(WorkstationPolicy.hasDistinctInputs(2, 2, 2));
	}

	@Test
	void smeltFallbackFuelAllowsSurplusCombustibleInput() {
		assertTrue(WorkstationPolicy.canUseInputAsFallbackFuel(true, 10, 4, 3));
		assertFalse(WorkstationPolicy.canUseInputAsFallbackFuel(true, 6, 4, 3));
		assertFalse(WorkstationPolicy.canUseInputAsFallbackFuel(false, 20, 4, 1));
		assertFalse(WorkstationPolicy.canUseInputAsFallbackFuel(true, 10, 4, 0));
	}
}
