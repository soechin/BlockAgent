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
	// ---- 勝算(docs/REVIEW.md R7) ----

	private static CombatPolicy.Fighter fist(float hp, int armor) {
		return new CombatPolicy.Fighter(hp, 1, 0.25, armor, false, false, true, true);
	}

	private static CombatPolicy.Fighter stoneSword(float hp, int armor, boolean shield) {
		return new CombatPolicy.Fighter(hp, 5, 0.625, armor, shield, false, true, true);
	}

	@Test
	void unarmedFightOddsAgainstZombies() {
		assertEquals(7.5, CombatPolicy.expectedLoss(fist(20, 0), 20, 2, 1, false), 1e-9, "空手 20 拳 × 0.25 秒 × 1.5 HP/秒");
		assertTrue(CombatPolicy.worthFighting(fist(20, 0), 20, 2, 1, false), "滿血空手對 1 隻殭屍打得贏");
		assertFalse(CombatPolicy.worthFighting(fist(12, 0), 20, 2, 1, false), "12 HP 空手打完會掉到撤退線以下");
		assertFalse(CombatPolicy.worthFighting(fist(20, 0), 20, 2, 2, false), "空手對 2 隻殭屍不打");
		assertTrue(CombatPolicy.worthFighting(fist(12, 15), 20, 2, 1, false), "鐵甲減傷 60%");
	}

	@Test
	void swordAndCritFightOdds() {
		var noCrit = new CombatPolicy.Fighter(10, 5, 0.625, 0, false, false, true, false);
		assertEquals(3.75, CombatPolicy.expectedLoss(noCrit, 20, 2, 1, false), 1e-9);
		assertTrue(CombatPolicy.worthFighting(noCrit, 20, 2, 1, false));
		assertTrue(CombatPolicy.expectedLoss(stoneSword(10, 0, false), 20, 2, 1, false) < 3.75, "跳劈少一刀");
		assertFalse(fist(20, 0).critWorthIt(), "空手冷卻太短，跳劈反而慢");
	}

	@Test
	void rangedStanceDependsOnGear() {
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(fist(20, 0), 12, true, false, 20, 0), "空手無盾不衝骷髏");
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(fist(20, 0), 10, true, false, 20, 0),
			"空手 10 格也不衝(遊戲測試：每一拳都把骷髏擊退，實際 1 秒多才打得到下一拳)");
		assertEquals(CombatPolicy.RangedStance.GUARD_ADVANCE, CombatPolicy.rangedStance(stoneSword(20, 15, true), 12, true, false, 20, 0));
		assertEquals(CombatPolicy.RangedStance.CHARGE, CombatPolicy.rangedStance(stoneSword(20, 0, false), 12, true, false, 20, 0));
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(stoneSword(12, 0, false), 12, true, false, 20, 0));
		var archer = new CombatPolicy.Fighter(20, 5, 0.625, 0, false, true, true, true);
		assertEquals(CombatPolicy.RangedStance.SHOOT, CombatPolicy.rangedStance(archer, 10, true, false, 20, 0));
		assertEquals(CombatPolicy.RangedStance.CHARGE, CombatPolicy.rangedStance(archer, 6, true, false, 20, 0));
		assertEquals(CombatPolicy.RangedStance.SHOOT, CombatPolicy.rangedStance(archer, 6, true, true, 20, 0), "走不近就對射");
	}

	@Test
	void shieldHeldThroughArrowFlight() {
		assertTrue(CombatPolicy.shieldHoldTicks(10) >= 10, "10 格箭要飛 7 tick，盾要舉到箭到");
		assertEquals(8, CombatPolicy.shieldHoldTicks(1));
	}

	@Test
	void jumpCritOnlyWhenWorthIt() {
		assertTrue(CombatPolicy.shouldJumpForCrit(true, false, false, true, 0.625, 0.7f, false));
		assertFalse(CombatPolicy.shouldJumpForCrit(true, false, false, false, 0.625, 0.7f, false), "頭上沒空間");
		assertFalse(CombatPolicy.shouldJumpForCrit(true, true, false, true, 0.625, 0.7f, false), "在水裡");
		assertFalse(CombatPolicy.shouldJumpForCrit(true, false, false, true, 0.25, 0.7f, false), "空手");
		assertFalse(CombatPolicy.shouldJumpForCrit(true, false, false, true, 0.625, 0.7f, true), "正在擋箭");
		assertTrue(CombatPolicy.shouldJumpForCrit(true, false, false, true, 0.625, 1f, false), "冷卻已滿也跳(擊退後重新貼上時冷卻總是滿的)");
		assertTrue(CombatPolicy.critReady(false, -0.1, 0.95f));
		assertFalse(CombatPolicy.critReady(false, 0.2, 0.95f), "還在往上跳");
	}

	// ---- 戰前評估 ----

	@Test
	void readinessAndMissingGear() {
		var bare = CombatPolicy.readiness(fist(20, 0));
		assertEquals(CombatPolicy.Gear.WEAK, bare.gear());
		assertEquals(java.util.List.of("sword", "shield", "armor", "bow"), bare.missing());
		assertEquals(CombatPolicy.Gear.STRONG, CombatPolicy.readiness(stoneSword(20, 15, true)).gear());
		assertEquals(CombatPolicy.Gear.OK, CombatPolicy.readiness(stoneSword(20, 0, true)).gear());
		assertEquals(CombatPolicy.Gear.WEAK, CombatPolicy.readiness(stoneSword(20, 0, false)).gear(), "沒甲沒盾");
	}

	@Test
	void dangerLevels() {
		assertEquals(CombatPolicy.DangerLevel.HIGH, CombatPolicy.danger(true, true, 0, 20, CombatPolicy.Gear.WEAK).level());
		assertEquals(CombatPolicy.DangerLevel.MEDIUM, CombatPolicy.danger(true, true, 0, 20, CombatPolicy.Gear.STRONG).level());
		assertEquals(CombatPolicy.DangerLevel.LOW, CombatPolicy.danger(false, true, 0, 20, CombatPolicy.Gear.WEAK).level());
		assertEquals(CombatPolicy.DangerLevel.HIGH, CombatPolicy.danger(false, true, 1, 10, CombatPolicy.Gear.STRONG).level());
		assertEquals(CombatPolicy.DangerLevel.HIGH, CombatPolicy.danger(false, false, 3, 20, CombatPolicy.Gear.STRONG).level());
		assertEquals(java.util.List.of("dark", "outdoors", "gear_weak"), CombatPolicy.danger(true, true, 0, 20, CombatPolicy.Gear.WEAK).reasons());
	}

	// ---- 撤退(R3) ----

	@Test
	void retreatOutcomes() {
		assertEquals(CombatPolicy.RetreatOutcome.RESUME, CombatPolicy.retreatOutcome("recovered", 1));
		assertEquals(CombatPolicy.RetreatOutcome.CANCEL_REPEAT, CombatPolicy.retreatOutcome("recovered", 2));
		assertEquals(CombatPolicy.RetreatOutcome.CANCEL_CANNOT_HEAL, CombatPolicy.retreatOutcome("cannot_heal", 1));
		assertEquals(CombatPolicy.RetreatOutcome.CANCEL_CANNOT_HEAL, CombatPolicy.retreatOutcome("gave_up_after_60s", 1));
		assertEquals(CombatPolicy.RetreatOutcome.RESUME, CombatPolicy.retreatOutcome("turned_off", 2), "AI 關掉撤退時照 S9.4 恢復任務");
	}
}
