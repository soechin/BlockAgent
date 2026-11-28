package blockagent.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 快捷列換位與挖掘工具划算度純邏輯測試。 */
class InvPolicyTest {
	@Test
	void worthDiggingRequiresCorrectToolWhenDropsRequireIt() {
		// 木鎬挖鐵礦：雖然比較快(2.0)，但不合掉落條件，絕不划算
		assertFalse(Inv.worthDigging(true, true, false, 2.0f));
		// 石鎬/鐵鎬挖鐵礦：合條件
		assertTrue(Inv.worthDigging(true, true, true, 4.0f));
		// 斧頭砍原木：不需特定工具且明顯加速
		assertTrue(Inv.worthDigging(true, false, false, 4.0f));
		// 斧頭打樹葉或泥土：沒有加速，避免白耗耐久
		assertFalse(Inv.worthDigging(true, false, false, 1.0f));
		// 空手或不會耗損耐久的方塊永遠允許
		assertTrue(Inv.worthDigging(false, true, false, 1.0f));
	}

	@Test
	void chooseHotbarSwapSlotPrefersEmptyThenNonDamageable() {
		boolean[] occupied = {true, true, true, false, true, true, true, true, true};
		boolean[] damageable = {true, true, true, false, false, true, false, false, false};
		assertEquals(3, Inv.chooseHotbarSwapSlot(occupied, damageable, null, 0), "有空格時優先換空格");

		occupied[3] = true;
		damageable[3] = true;
		// 滿格且 0–3,5 為工具，4,6,7,8 為非工具：不該把工具擠出去
		assertEquals(4, Inv.chooseHotbarSwapSlot(occupied, damageable, null, 0));

		// 若 4 格受保護(例如正要用的建材或鷹架)，應跳到下一格非工具(6)
		boolean[] protectedSlot = new boolean[9];
		protectedSlot[4] = true;
		assertEquals(6, Inv.chooseHotbarSwapSlot(occupied, damageable, protectedSlot, 0));

		// 全是工具且前兩格受保護：才退而選未受保護的工具(2)
		boolean[] allTools = {true, true, true, true, true, true, true, true, true};
		boolean[] prot = {true, true, false, false, false, false, false, false, false};
		assertEquals(2, Inv.chooseHotbarSwapSlot(allTools, allTools, prot, -1));

		// 全部受保護：回傳 fallback
		assertEquals(-1, Inv.chooseHotbarSwapSlot(allTools, allTools, allTools, -1));
	}

	/** R11：暫時換走副手盾牌時，優先挑空格，滿格時挑非主手且非盾牌的格子。 */
	@Test
	void chooseOffhandShieldSwapSlotPrefersEmptyThenNonShield() {
		boolean[] occupied = {true, true, false, true};
		boolean[] blocksAttacks = {true, false, false, false};
		assertEquals(2, Inv.chooseOffhandShieldSwapSlot(occupied, blocksAttacks, 1), "有空格優先挑空格");

		occupied[2] = true;
		blocksAttacks[2] = true;
		// 0 和 2 是盾牌，1 是目前主手，只剩 3 可換
		assertEquals(3, Inv.chooseOffhandShieldSwapSlot(occupied, blocksAttacks, 1));

		// 除了主手外全都是盾牌：不能拿另一個盾牌來換
		blocksAttacks[3] = true;
		assertEquals(-1, Inv.chooseOffhandShieldSwapSlot(occupied, blocksAttacks, 1));
	}

	@Test
	void isPreciousToolIdIdentifiesDiamondAndNetherite() {
		assertTrue(Inv.isPreciousToolId("diamond_pickaxe"));
		assertTrue(Inv.isPreciousToolId("netherite_axe"));
		assertFalse(Inv.isPreciousToolId("iron_pickaxe"));
		assertFalse(Inv.isPreciousToolId("stone_pickaxe"));
		assertFalse(Inv.isPreciousToolId("wooden_shovel"));
		assertFalse(Inv.isPreciousToolId("golden_pickaxe"));
		assertFalse(Inv.isPreciousToolId("shears"));
		assertFalse(Inv.isPreciousToolId(null));
	}

	@Test
	void effectiveMiningSpeedAppliesEfficiencyOnlyWhenToolIsEffective() {
		// 有效工具(速度 > 1)加上效率附魔：base + lvl^2 + 1
		assertEquals(8.0f, Inv.effectiveMiningSpeed(8.0f, 0), 0.001f);
		assertEquals(25.0f, Inv.effectiveMiningSpeed(8.0f, 4), 0.001f);
		assertEquals(34.0f, Inv.effectiveMiningSpeed(8.0f, 5), 0.001f);
		// 對無效方塊(速度 1.0)不加效率
		assertEquals(1.0f, Inv.effectiveMiningSpeed(1.0f, 5), 0.001f);
	}

	@Test
	void defaultMiningToolPrefersOrdinaryUnenchantedOverPreciousAndEnchanted() {
		int ironPlain = Inv.defaultMiningToolTier(false, false, false, false, 250, 250);
		int ironEff = Inv.defaultMiningToolTier(false, true, false, false, 250, 250);
		int diaPlain = Inv.defaultMiningToolTier(true, false, false, false, 1561, 1561);
		int diaEff = Inv.defaultMiningToolTier(true, true, false, false, 1561, 1561);
		int diaFortune = Inv.defaultMiningToolTier(true, true, true, false, 1561, 1561);
		int diaSilk = Inv.defaultMiningToolTier(true, true, false, true, 1561, 1561);
		int diaLowDur = Inv.defaultMiningToolTier(true, true, false, false, 10, 1561);

		assertEquals(0, ironPlain);
		assertEquals(1, ironEff);
		assertEquals(2, diaPlain);
		assertEquals(3, diaEff);
		assertEquals(4, diaFortune);
		assertEquals(5, diaSilk);
		assertEquals(6, diaLowDur);

		// 無附魔鐵鎬(速度 6)優於效率 IV 鑽石鎬(速度 25)、無附魔鑽石鎬(速度 8)、時運/絲綢鑽石鎬
		assertTrue(Inv.compareDefaultMiningTools(ironPlain, false, 6.0f, diaEff, true, 25.0f) < 0);
		assertTrue(Inv.compareDefaultMiningTools(ironPlain, false, 6.0f, diaPlain, true, 8.0f) < 0);
		assertTrue(Inv.compareDefaultMiningTools(ironPlain, false, 6.0f, diaFortune, true, 25.0f) < 0);
		assertTrue(Inv.compareDefaultMiningTools(ironPlain, false, 6.0f, diaSilk, true, 25.0f) < 0);

		// 同為無附魔普通工具：鐵鎬(6)優於石鎬(4)優於木鎬(2)
		assertTrue(Inv.compareDefaultMiningTools(ironPlain, false, 6.0f, ironPlain, false, 4.0f) < 0);
		assertTrue(Inv.compareDefaultMiningTools(ironPlain, false, 4.0f, ironPlain, false, 2.0f) < 0);

		// 無附魔鑽石鎬優於附魔鑽石鎬(即使附魔鎬更快)
		assertTrue(Inv.compareDefaultMiningTools(diaPlain, true, 8.0f, diaEff, true, 34.0f) < 0);
		assertTrue(Inv.compareDefaultMiningTools(diaPlain, true, 8.0f, diaFortune, true, 34.0f) < 0);
		// 一般附魔鑽石鎬優於時運，時運優於絲綢之觸
		assertTrue(Inv.compareDefaultMiningTools(diaEff, true, 10.0f, diaFortune, true, 34.0f) < 0);
		assertTrue(Inv.compareDefaultMiningTools(diaFortune, true, 8.0f, diaSilk, true, 34.0f) < 0);

		// 瀕臨損壞的珍貴/附魔工具(殘餘 ≤ 10)退居最後，避免挖爆；無附魔鐵鎬殘餘低仍可繼續用到壞
		assertTrue(Inv.compareDefaultMiningTools(diaSilk, true, 8.0f, diaLowDur, true, 34.0f) < 0);
		int ironLowDur = Inv.defaultMiningToolTier(false, false, false, false, 3, 250);
		assertEquals(0, ironLowDur);
		int ironEffLowDur = Inv.defaultMiningToolTier(false, true, false, false, 5, 250);
		assertEquals(6, ironEffLowDur);
		// 同在低耐久層時，普通附魔工具優於鑽石附魔工具(優先保護鑽石)
		assertTrue(Inv.compareDefaultMiningTools(ironEffLowDur, false, 10.0f, diaLowDur, true, 34.0f) < 0);
	}
}
