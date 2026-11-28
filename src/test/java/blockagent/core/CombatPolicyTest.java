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
		assertEquals(7.5, CombatPolicy.expectedLoss(fist(20, 0), 20, 2, 1, 0, false), 1e-9, "空手 20 拳 × 0.25 秒 × 1.5 HP/秒");
		assertTrue(CombatPolicy.worthFighting(fist(20, 0), 20, 2, 1, 0, false), "滿血空手對 1 隻殭屍打得贏");
		assertFalse(CombatPolicy.worthFighting(fist(12, 0), 20, 2, 1, 0, false), "12 HP 空手打完會掉到撤退線以下");
		assertFalse(CombatPolicy.worthFighting(fist(20, 0), 20, 2, 2, 0, false), "空手對 2 隻殭屍不打");
		assertTrue(CombatPolicy.worthFighting(fist(12, 15), 20, 2, 1, 0, false), "鐵甲能留足撤退血量");
	}

	@Test
	void hardDifficultyAccountsForHitSizeAndSkeletonCadence() {
		var hardUnarmed = new CombatPolicy.Fighter(20, 1, 0.25, 0, false, false, true, true, CombatPolicy.Difficulty.HARD, 0);
		assertEquals(11.25, CombatPolicy.expectedLoss(hardUnarmed, 20, 2, 1, 0, false), 1e-9);
		assertFalse(CombatPolicy.worthFighting(hardUnarmed, 20, 2, 1, 0, false));
		double otherShooter = CombatPolicy.expectedBowLoss(hardUnarmed, 20, 0, 1);
		assertEquals(otherShooter * 2, CombatPolicy.expectedBowLoss(hardUnarmed, 20, 0, CombatPolicy.Difficulty.HARD.skeletonRate()), 1e-9);
		assertEquals(12, CombatPolicy.hardRetreatHp(0, 0, 0, 1), 1e-9);
		assertEquals(11.325, CombatPolicy.hardRetreatHp(15, 0, 1, 1), 1e-9);
		assertTrue(CombatPolicy.hardRetreatHp(15, 8, 1, 1) < CombatPolicy.hardRetreatHp(15, 0, 1, 1));
		assertEquals(6, CombatPolicy.hardRetreatHp(0, 0, 0, 0), 1e-9);
	}

	@Test
	void normalDifficultyUsesPerHitArmorAndToughness() {
		var normal = new CombatPolicy.Fighter(20, 1, 0.25, 15, false, false, true, true, CombatPolicy.Difficulty.NORMAL, 0);
		var tough = new CombatPolicy.Fighter(20, 1, 0.25, 15, false, false, true, true, CombatPolicy.Difficulty.NORMAL, 8);
		assertEquals(3.45, CombatPolicy.expectedLoss(normal, 20, 2, 1, 0, false), 1e-9);
		assertEquals(3.225, CombatPolicy.expectedLoss(tough, 20, 2, 1, 0, false), 1e-9);
		assertEquals(4.8, CombatPolicy.expectedBowLoss(normal, 20, 0, CombatPolicy.Difficulty.NORMAL.skeletonRate()), 1e-9);
	}

	@Test
	void easierDifficultiesUseTheirOwnHitSizes() {
		var easy = new CombatPolicy.Fighter(20, 1, 0.25, 0, false, false, true, true, CombatPolicy.Difficulty.EASY, 0);
		var peaceful = new CombatPolicy.Fighter(20, 1, 0.25, 0, false, false, true, true, CombatPolicy.Difficulty.PEACEFUL, 0);
		assertEquals(6.25, CombatPolicy.expectedLoss(easy, 20, 2, 1, 0, false), 1e-9);
		assertEquals(7.5, CombatPolicy.expectedBowLoss(easy, 20, 0, CombatPolicy.Difficulty.EASY.skeletonRate()), 1e-9);
		assertEquals(0, CombatPolicy.expectedLoss(peaceful, 20, 2, 1, 1, false), 1e-9);
	}

	@Test
	void hardRecoveryRequiresConsecutiveSafeTicks() {
		int clear = 0;
		for (int i = 0; i < 39; i++) clear = CombatPolicy.hardRecoveryClearTicks(clear, false);
		assertFalse(CombatPolicy.canRecoverOnHard(clear));
		assertEquals(0, CombatPolicy.hardRecoveryClearTicks(clear, true));
		assertTrue(CombatPolicy.canRecoverOnHard(CombatPolicy.hardRecoveryClearTicks(clear, false)));
	}

	@Test
	void swordAndCritFightOdds() {
		var noCrit = new CombatPolicy.Fighter(10, 5, 0.625, 0, false, false, true, false);
		assertEquals(3.75, CombatPolicy.expectedLoss(noCrit, 20, 2, 1, 0, false), 1e-9);
		assertTrue(CombatPolicy.worthFighting(noCrit, 20, 2, 1, 0, false));
		assertTrue(CombatPolicy.expectedLoss(stoneSword(10, 0, false), 20, 2, 1, 0, false) < 3.75, "跳劈少一刀");
		assertFalse(fist(20, 0).critWorthIt(), "空手冷卻太短，跳劈反而慢");
	}

	@Test
	void rangedStanceDependsOnGear() {
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(fist(20, 0), 12, true, false, 20, 0, 1), "空手無盾不衝骷髏");
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(fist(20, 0), 10, true, false, 20, 0, 1),
			"空手 10 格也不衝(遊戲測試：每一拳都把骷髏擊退，實際 1 秒多才打得到下一拳)");
		assertEquals(CombatPolicy.RangedStance.GUARD_ADVANCE, CombatPolicy.rangedStance(stoneSword(20, 15, true), 12, true, false, 20, 0, 0));
		assertEquals(CombatPolicy.RangedStance.CHARGE, CombatPolicy.rangedStance(stoneSword(20, 0, false), 12, true, false, 20, 0, 1));
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(stoneSword(12, 0, false), 12, true, false, 20, 0, 1));
		var archer = new CombatPolicy.Fighter(20, 5, 0.625, 0, false, true, true, true);
		assertEquals(CombatPolicy.RangedStance.SHOOT, CombatPolicy.rangedStance(archer, 10, true, false, 20, 0, 1));
		assertEquals(CombatPolicy.RangedStance.CHARGE, CombatPolicy.rangedStance(archer, 6, true, false, 20, 0, 1));
		assertEquals(CombatPolicy.RangedStance.SHOOT, CombatPolicy.rangedStance(archer, 6, true, true, 20, 0, 1), "走不近就對射");
		assertEquals(CombatPolicy.RangedStance.AMBUSH, CombatPolicy.rangedStance(stoneSword(20, 15, true), 12, true, true, 20, 0, 0),
			"打得贏但走不近、沒弓：躲起來埋伏，不是脫身");
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(fist(20, 0), 12, true, true, 20, 0, 1), "打不贏又走不近：脫身");
	}

	@Test
	void considersBowDamageAndChecksThreatsBeforeShooting() {
		var bowOnly = new CombatPolicy.Fighter(20, 1, 0.25, 0, false, true, true, true);
		var injured = new CombatPolicy.Fighter(11, 1, 0.25, 0, false, true, true, true);
		assertEquals(CombatPolicy.RangedStance.SHOOT, CombatPolicy.rangedStance(bowOnly, 12, true, false, 20, 0, 1));
		assertEquals(CombatPolicy.RangedStance.SHOOT, CombatPolicy.rangedStance(bowOnly, 6, true, false, 20, 0, 1));
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(bowOnly, 12, true, false, 20, 1, 1));
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(injured, 12, true, false, 20, 0, 2));
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(bowOnly, 12, false, false, 20, 0, 1));
		assertTrue(CombatPolicy.canContinueDraw(5.5, 5, 8));
		assertTrue(CombatPolicy.canContinueDraw(4, 5, 14));
		assertFalse(CombatPolicy.canContinueDraw(4, 5, 13));
		assertFalse(CombatPolicy.canContinueDraw(3.5, 5, 20));
	}

	@Test
	void groupsChangeTheOdds() {
		var geared = stoneSword(20, 15, true);
		assertEquals(CombatPolicy.RangedStance.GUARD_ADVANCE, CombatPolicy.rangedStance(geared, 12, true, false, 20, 0, 0),
			"射手都在正面：盾牌擋得住");
		assertEquals(CombatPolicy.RangedStance.COVER, CombatPolicy.rangedStance(stoneSword(14, 0, true), 12, true, false, 20, 2, 2),
			"沒甲、2 隻殭屍貼身、另外 2 隻骷髏從側面射：盾牌擋不住，躲");
		assertTrue(CombatPolicy.expectedLoss(fist(20, 0), 20, 2, 1, 2, false) > CombatPolicy.expectedLoss(fist(20, 0), 20, 2, 1, 0, false),
			"打殭屍時旁邊有骷髏在射：掉更多血");
		assertFalse(CombatPolicy.worthFighting(fist(20, 0), 20, 2, 1, 1, false), "空手打殭屍時還有一隻骷髏在射：不打");
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
		assertEquals(CombatPolicy.DangerLevel.MEDIUM, CombatPolicy.danger(true, true, 0, 20, CombatPolicy.Gear.WEAK).level());
		assertEquals(CombatPolicy.DangerLevel.MEDIUM, CombatPolicy.danger(true, true, 0, 20, CombatPolicy.Gear.STRONG).level());
		assertEquals(CombatPolicy.DangerLevel.MEDIUM, CombatPolicy.danger(true, false, 0, 20, CombatPolicy.Gear.WEAK).level());
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

	@Test
	void retreatRecoverPhaseHasHysteresisBehindWall() {
		// 隔牆 4 格內有怪(visible16 == 0, near6 == 1)：不可進 RECOVER，避免與 RECOVER 的 near4 > 0 每 2 tick 震盪
		assertFalse(CombatPolicy.shouldEnterRetreatRecover(0, 1));
		assertFalse(CombatPolicy.shouldEnterRetreatRecover(1, 0));
		assertTrue(CombatPolicy.shouldEnterRetreatRecover(0, 0));

		assertFalse(CombatPolicy.shouldLeaveRetreatRecover(0, 0));
		assertTrue(CombatPolicy.shouldLeaveRetreatRecover(1, 0));
		assertTrue(CombatPolicy.shouldLeaveRetreatRecover(0, 1));
	}

	@Test
	void stopToShieldWhileEscapingOnlyWhenNoFlankingArchers() {
		// 單方向射手(正面)與兩隻都在正面扇形：可以停步舉盾
		assertTrue(CombatPolicy.canSafelyStopToShield(10, 0, java.util.List.of(new double[]{10, 0})));
		assertTrue(CombatPolicy.canSafelyStopToShield(10, 0, java.util.List.of(new double[]{10, 3}, new double[]{8, -4})));
		// 背後或超過 90 度側翼有另一隻射手(例如三隻相隔 120 度包圍)：不可停步舉盾，優先跑出射程
		assertFalse(CombatPolicy.canSafelyStopToShield(0, -12, java.util.List.of(
			new double[]{0, -12}, new double[]{10, 6}, new double[]{-10, 6})));
		assertFalse(CombatPolicy.canSafelyStopToShield(10, 0, java.util.List.of(
			new double[]{10, 0}, new double[]{-10, 0})));
	}

	@Test
	void unstuckAllowsSlowBreakingBeforeInterrupting() {
		// 沒在破塊時 200 tick(10 秒)觸發
		assertNull(CombatPolicy.unstuckTriggerReason(199, 200, 0.1, false));
		assertEquals("stuck_10s", CombatPolicy.unstuckTriggerReason(200, 200, 0.1, false));
		assertNull(CombatPolicy.unstuckTriggerReason(200, 200, 0.6, false));
		// 正在破塊(例如水中浮空徒手砍橡木需約 300 tick)不應在第 10 秒打斷，滿 400 tick(20 秒)才介入
		assertNull(CombatPolicy.unstuckTriggerReason(200, 200, 0.1, true));
		assertNull(CombatPolicy.unstuckTriggerReason(350, 200, 0.1, true));
		assertEquals("stuck_breaking_20s", CombatPolicy.unstuckTriggerReason(400, 200, 0.1, true));
		// 脫困後退視窗：從開始後退起算滿 UNSTUCK_ESCAPE_TICKS(60 tick)才判逾時
		assertFalse(CombatPolicy.unstuckEscapeTimedOut(70, -1));
		assertFalse(CombatPolicy.unstuckEscapeTimedOut(110, 50));
		assertTrue(CombatPolicy.unstuckEscapeTimedOut(111, 50));
	}

	// ---- 水中漂浮、溺水與水面目標 ----

	@Test
	void keepAfloatFloatsInOpenWaterOnlyWhenIdleOrSubmerged() {
		// 浮在深水裡且無移動任務：保持按住空白鍵漂浮
		assertTrue(CombatPolicy.shouldKeepAfloat(true, false, false, false, false, true, false));
		// 踩在 2 格深水底(頭已浸水)：即使 onGround=true 也要浮起來
		assertTrue(CombatPolicy.shouldKeepAfloat(true, true, true, false, false, true, false));
		// 站在 1 格淺水裡且頭在水上：不要原地亂跳
		assertFalse(CombatPolicy.shouldKeepAfloat(true, true, false, false, false, true, false));
		// 不在水裡、乘載具、創造飛行、頭頂有冰/洞頂、或有任務／反射控制移動時皆不插手
		assertFalse(CombatPolicy.shouldKeepAfloat(false, false, false, false, false, true, false));
		assertFalse(CombatPolicy.shouldKeepAfloat(true, false, false, true, false, true, false));
		assertFalse(CombatPolicy.shouldKeepAfloat(true, false, false, false, true, true, false));
		assertFalse(CombatPolicy.shouldKeepAfloat(true, false, false, false, false, false, false));
		assertFalse(CombatPolicy.shouldKeepAfloat(true, false, false, false, false, true, true));
	}

	@Test
	void drowningSwimsStraightUpInOpenWaterAndPathsUnderCeiling() {
		assertEquals(CombatPolicy.DrowningAction.SWIM_UP, CombatPolicy.drowningAction(true));
		assertEquals(CombatPolicy.DrowningAction.PATH_TO_AIR, CombatPolicy.drowningAction(false));
	}

	@Test
	void deepWaterAllowsHuntingFloatingPassiveMobsButBlocksHostilesAndSubmerged() {
		// 主動狩獵浮在開放深水面的羊(waterBelow=true, waterAbove=false, targetSurfaceOpen=true)：允許
		assertFalse(CombatPolicy.isBlockedDeepWaterTarget(false, true, true, false, false, false, true, false, true));
		// 即使玩家短暫浸水，狩獵浮水面被動動物仍不立即中止(改由漂浮鍵上浮)
		assertFalse(CombatPolicy.isBlockedDeepWaterTarget(false, true, true, false, true, false, true, false, true));
		// 冰面下的羊(targetSurfaceOpen=false)：擋下
		assertTrue(CombatPolicy.isBlockedDeepWaterTarget(false, true, true, false, false, false, true, false, false));
		// 潛在水底的魚(waterAbove=true)不在射程內：擋下；在岸邊射程內且人未潛水：允許
		assertTrue(CombatPolicy.isBlockedDeepWaterTarget(false, true, true, true, false, false, true, false, true));
		assertFalse(CombatPolicy.isBlockedDeepWaterTarget(false, true, true, true, false, true, true, false, true));
		// 敵對怪在深水(不論狩獵或自衛)：維持擋下(不在射程內時)
		assertTrue(CombatPolicy.isBlockedDeepWaterTarget(false, true, true, false, false, false, true, true, true));
		assertTrue(CombatPolicy.isBlockedDeepWaterTarget(false, true, true, false, false, false, false, true, true));
		// 淺灘(1 格水)：不擋
		assertFalse(CombatPolicy.isBlockedDeepWaterTarget(false, true, false, false, false, false, false, true, true));
	}

	@Test
	void directWaterChaseOnlyWhenBothInOpenWaterWithSight() {
		assertTrue(CombatPolicy.shouldDirectWaterChase(true, true, true));
		assertFalse(CombatPolicy.shouldDirectWaterChase(false, true, true));
		assertFalse(CombatPolicy.shouldDirectWaterChase(true, false, true));
		assertFalse(CombatPolicy.shouldDirectWaterChase(true, true, false));
	}

	@Test
	void defenseLootWaitsForSpawnCollectsNearbyAndTimesOutCleanly() {
		// 有可撿掉落物時即使空格為 0(可疊加)仍應啟動；沒有掉落物但剛在近處擊殺且有空格時也應短暫等待生成
		assertTrue(CombatPolicy.shouldStartDefenseLoot(true, false, 0));
		assertTrue(CombatPolicy.shouldStartDefenseLoot(false, true, 1));
		assertFalse(CombatPolicy.shouldStartDefenseLoot(false, true, 0));
		assertFalse(CombatPolicy.shouldStartDefenseLoot(false, false, 10));

		// 擊殺後尚未見掉落物且在生成時間窗內：等待生成
		assertEquals(CombatPolicy.DefenseLootAction.WAIT_SPAWN,
			CombatPolicy.defenseLootAction(5, 10, false, true, false));
		// 出現符合條件的掉落物：立刻前往撿取
		assertEquals(CombatPolicy.DefenseLootAction.COLLECT,
			CombatPolicy.defenseLootAction(22, 22, true, true, true));
		// 撿完且已看過掉落物：立刻結束，不必苦等時間窗
		assertEquals(CombatPolicy.DefenseLootAction.DONE,
			CombatPolicy.defenseLootAction(24, 24, false, true, true));
		// 無掉落物超過生成時間窗：立刻結束
		assertEquals(CombatPolicy.DefenseLootAction.DONE,
			CombatPolicy.defenseLootAction(CombatPolicy.DEFENSE_LOOT_SPAWN_TICKS, CombatPolicy.DEFENSE_LOOT_SPAWN_TICKS, false, true, false));
		// 超過總拾取上限(3 秒)：強制結束，避免卡住原任務
		assertEquals(CombatPolicy.DefenseLootAction.DONE,
			CombatPolicy.defenseLootAction(CombatPolicy.DEFENSE_LOOT_TIMEOUT_TICKS, 10, true, true, true));
	}
}
