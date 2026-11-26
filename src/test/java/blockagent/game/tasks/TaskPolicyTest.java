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

	@Test
	void clearAreaSamplePointsHitThinSnowLayer() {
		double[][] pts = ClearAreaTask.samplePoints(26.0, 110.0, -90.0, 27.0, 110.125, -89.0);
		assertEquals(7, pts.length);
		assertEquals(110.0625, pts[0][1], 1e-9, "中心點應落在一層雪內部中線");
		for (double[] pt : pts) {
			assertTrue(pt[0] > 26.0 && pt[0] < 27.0, "x 超出外框：" + pt[0]);
			assertTrue(pt[1] > 110.0 && pt[1] < 110.125, "y 必須嚴格落在一層雪(0–0.125)之內：" + pt[1]);
			assertTrue(pt[2] > -90.0 && pt[2] < -89.0, "z 超出外框：" + pt[2]);
		}
	}

	@Test
	void mineRadiusBoundsVerticalAndHorizontalDrift() {
		// 小屋 (27,116,-57)，半徑 32：地底 y=35 必須被擋，邊界內允許
		assertFalse(MineTask.withinRadius(27, 116, -57, 22, 35, -63, 32));
		assertTrue(MineTask.withinRadius(27, 116, -57, 27, 84, -57, 32));
		assertFalse(MineTask.withinRadius(27, 116, -57, 27, 83, -57, 32));
		// 拔草 (0,106,-78)，半徑 48：73 格外必須被擋
		assertFalse(MineTask.withinRadius(0, 106, -78, -73, 104, -52, 48));
	}

	@Test
	void smeltOnlyClearsLeftoverFuelInOwnIdleEmptyFurnace() {
		assertTrue(SmeltTask.canClearOwnLeftoverFuel(true, false, true, true, true, true));
		assertFalse(SmeltTask.canClearOwnLeftoverFuel(false, false, true, true, true, true), "別人的熔爐不可取出燃料");
		assertFalse(SmeltTask.canClearOwnLeftoverFuel(true, true, true, true, true, true), "燃燒中不可取");
		assertFalse(SmeltTask.canClearOwnLeftoverFuel(true, false, false, true, true, true), "還有原料不可取");
		assertFalse(SmeltTask.canClearOwnLeftoverFuel(true, false, true, false, true, true), "還有產物不可取");
		assertFalse(SmeltTask.canClearOwnLeftoverFuel(true, false, true, true, true, false), "背包放不下不可取");
	}

	/** R8：採礦啟動中卻沒有路徑、沒離開原地且沒挖到新方塊時才累計受困 tick，任一條件改變即歸零。 */
	@Test
	void mineTrappedTicksResetsUnlessStuckWithoutPath() {
		int t = MineTask.nextTrappedTicks(0, true, false, false, false);
		assertEquals(1, t);
		t = MineTask.nextTrappedTicks(t, true, false, false, false);
		assertEquals(2, t);
		assertEquals(0, MineTask.nextTrappedTicks(t, false, false, false, false), "採礦未啟動應歸零");
		assertEquals(0, MineTask.nextTrappedTicks(t, true, true, false, false), "有路徑應歸零");
		assertEquals(0, MineTask.nextTrappedTicks(t, true, false, true, false), "離開原地應歸零");
		assertEquals(0, MineTask.nextTrappedTicks(t, true, false, false, true), "挖到新方塊應歸零");
	}

	/** R6：施工擋下日誌節流首次(last=-1)必寫、100 tick 內略過、滿 100 tick 再寫，不可因 Long.MIN_VALUE 溢位失效。 */
	@Test
	void buildBlockedLogThrottleHandlesFirstCallAndInterval() {
		assertTrue(blockagent.game.RuleGuard.shouldLogBuildBlocked(0, -1));
		assertTrue(blockagent.game.RuleGuard.shouldLogBuildBlocked(500, -1));
		assertFalse(blockagent.game.RuleGuard.shouldLogBuildBlocked(599, 500));
		assertTrue(blockagent.game.RuleGuard.shouldLogBuildBlocked(600, 500));
	}
}
