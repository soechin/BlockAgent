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
}
