package blockagent.game.tasks;

import blockagent.core.DayClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 任務掃描上限、熔爐停滯條件與時間格式邊界測試(P3-M9、Low)。 */
class TaskPolicyTest {
	@Test
	void scanQuotaNeverExceedsMaxScan() {
		int q = Moves.clampScanQuota(Math.max(4096 * 4, 64), Moves.MAX_SCAN);
		assertEquals(16_384, q);
		int next = Moves.nextScanQuota(q, Moves.MAX_SCAN);
		assertEquals(Moves.MAX_SCAN, next, "放大 8 倍後必須封頂在 MAX_SCAN，不可飆到 131,072");

		// 模擬完整掃描迴圈：每一步皆 <= MAX_SCAN 且有限步終止
		for (int max : new int[]{1, 20, 50, 64, 4096, 100_000}) {
			int cur = Moves.clampScanQuota(Math.max(max * 4, 64), Moves.MAX_SCAN);
			int steps = 0;
			while (true) {
				assertTrue(cur >= 1 && cur <= Moves.MAX_SCAN, "quota=" + cur);
				steps++;
				assertTrue(steps <= 6, "掃描迴圈步數過多");
				if (cur >= Moves.MAX_SCAN) break;
				cur = Moves.nextScanQuota(cur, Moves.MAX_SCAN);
			}
		}
	}

	@Test
	void smeltStallConditionCoversFuelAndInput() {
		// 仍在燃燒或產物格非空：不可判停滯
		assertFalse(SmeltTask.shouldStall(true, true, true, false));
		assertFalse(SmeltTask.shouldStall(false, false, true, false));
		// 熄火且產物空，只要缺燃料(含非燃料如空桶)或缺原料就應停下
		assertTrue(SmeltTask.shouldStall(false, true, true, false));
		assertTrue(SmeltTask.shouldStall(false, true, false, true));
		assertTrue(SmeltTask.shouldStall(false, true, true, true));
		// 原料與燃料都還在(例如剛點燃前 1 tick)：不判停滯
		assertFalse(SmeltTask.shouldStall(false, true, false, false));
	}

	@Test
	void dayClockDurationNeverPrints120s() {
		assertEquals("119s", DayClock.duration(2389));
		assertEquals("2min", DayClock.duration(2390), "四捨五入到 120 秒時應改印 2min");
		assertEquals("2min", DayClock.duration(2399));
		assertEquals("2min", DayClock.duration(2400));
	}
}
