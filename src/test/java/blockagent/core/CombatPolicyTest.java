package blockagent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 戰鬥與反射純邏輯決策測試(P3-H4、P3-H5、P3-M2、P3-M13)。 */
class CombatPolicyTest {
	@Test
	void doesNotWaitInReachWithoutLineOfSight() {
		assertTrue(CombatPolicy.shouldWaitInReach(true, true));
		assertFalse(CombatPolicy.shouldWaitInReach(true, false), "隔牆看不到時不可停在 inReach，必須走過去");
		assertFalse(CombatPolicy.shouldWaitInReach(false, true));
		assertFalse(CombatPolicy.shouldWaitInReach(false, false));
	}

	@Test
	void preparesShotOnlyWhenClearAndHasBow() {
		assertTrue(CombatPolicy.canPrepareShot(true, true));
		assertFalse(CombatPolicy.canPrepareShot(false, true), "屋頂或牆擋住彈道時不可先放盾準備拉弓");
		assertFalse(CombatPolicy.canPrepareShot(true, false));
	}

	@Test
	void defenseCooldownIgnoresOldHitAndRespectsNewHit() {
		var cd = new CombatPolicy.DefenseCooldown();
		cd.put(42, 1000, 200, false);

		// 收手前 5 tick 剛被打過(995 <= 1000)：不可立刻繞過冷卻
		assertTrue(cd.isCoolingDown(42, 1001, 42, 995));
		// 剛好在收手同一 tick(1000)的傷害也不算新攻擊
		assertTrue(cd.isCoolingDown(42, 1005, 42, 1000));
		// 冷卻開始後的新攻擊(1005 > 1000)：立即解除冷卻
		assertFalse(cd.isCoolingDown(42, 1005, 42, 1005));
		// 另一隻怪物攻擊不影響 42 號的冷卻
		assertTrue(cd.isCoolingDown(42, 1050, 99, 1049));
		// 到期(1200)後解除
		assertFalse(cd.isCoolingDown(42, 1200, -1, -1000));
	}

	@Test
	void defenseCooldownSkippedWhenPreempted() {
		var cd = new CombatPolicy.DefenseCooldown();
		cd.put(7, 500, 200, true);
		assertTrue(cd.isEmpty(), "被更高優先級反射搶佔時不應上冷卻");
		assertFalse(cd.isCoolingDown(7, 501, -1, -1000));
	}

	@Test
	void unstuckFailureReportedAfterThreeStalledAttemptsUnlessReplacedOrCancelled() {
		assertTrue(CombatPolicy.shouldReportUnstuckFailure("still_stuck", 3));
		assertTrue(CombatPolicy.shouldReportUnstuckFailure("done", 3), "即使當次後退移動成功，任務無進展累計 3 次仍須中斷");
		assertFalse(CombatPolicy.shouldReportUnstuckFailure("still_stuck", 2));
		assertFalse(CombatPolicy.shouldReportUnstuckFailure("task_replaced", 3));
		assertFalse(CombatPolicy.shouldReportUnstuckFailure("task_cancelled", 3));
		assertFalse(CombatPolicy.shouldReportUnstuckFailure("turned_off", 3));
	}
}
