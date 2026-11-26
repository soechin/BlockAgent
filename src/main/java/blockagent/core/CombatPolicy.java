package blockagent.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 戰鬥與反射的純邏輯決策(無 Minecraft 依賴，可直接由 JUnit 測試)。
 */
public final class CombatPolicy {
	private CombatPolicy() {
	}

	// ---- 可調常數(docs/SPEC.md S9.1b；試玩的 fight_summary 指出問題時只改這裡) ----

	/** 撤退線：血量到這裡就撤退；勝算也以「打完還在這條線以上」為準。 */
	public static final float RETREAT_HP = 6f;
	/** 撤退回血到這裡才算回來了。 */
	public static final float RECOVERED_HP = 10f;
	/** 一隻近戰怪實際每秒打掉的血(殭屍一下 3 點、最多每秒一下，被擊退後大約一半)。 */
	public static final double MELEE_DPS = 1.5;
	/** 一隻射得到自己(盾牌擋不到)的遠程怪每秒打掉的血(骷髏一般難度 2 秒一箭、平均 3.5 點)。 */
	public static final double RANGED_DPS = 2.0;
	/** 每點護甲減免的傷害比例(原版約 4%)。 */
	public static final double ARMOR_REDUCTION = 0.04;
	/** 跳劈暴擊的傷害倍數。 */
	public static final double CRIT_MULTIPLIER = 1.5;
	/** 攻擊間隔短於這個(秒)時不跳劈：跳一次的時間比冷卻還長(空手 0.25 秒)，反而打得慢。 */
	public static final double CRIT_MIN_INTERVAL = 0.5;
	/** 衝向遠程怪的速度(格/秒，扣掉牠邊退邊射)。 */
	public static final double APPROACH_SPEED = 4.0;
	/**
	 * 打遠程怪時每一下至少要隔這麼久(秒)：每一下都把牠擊退出攻擊距離，牠又邊退邊射，得重新貼上去
	 * (2026-11-26 遊戲測試：空手打骷髏實際 1.1–1.3 秒才一拳，不是 0.25 秒)。
	 */
	public static final double RANGED_REENGAGE_SECS = 1.0;
	/** 盾牌只擋正面：跟面對的方向差超過這個角度(cos 值)的射手擋不到。 */
	public static final double SHIELD_FRONT_COS = 0.0;
	/** 埋伏等遠程怪繞過來時，看不到目標最多等幾 tick(比一般戰鬥的 60 tick 長，免得牠還沒繞過來就收手)。 */
	public static final int AMBUSH_LOST_SIGHT_TICKS = 200;
	/** 尋路時要繞開遠程怪多遠(射程外)、近戰怪多遠。 */
	public static final int AVOID_RANGED_RADIUS = 16;
	public static final int AVOID_MELEE_RADIUS = 8;
	/** 尋路經過怪物附近的成本倍數：繞半圈(約 1.6 倍路程)也比穿過去便宜。 */
	public static final double AVOID_COEFFICIENT = 4.0;
	/** 脫身時記住的怪物位置要避開多久(tick)。 */
	public static final int DANGER_MEMORY_TICKS = 60 * 20;
	/** 劍的攻擊距離約 3 格：衝到這裡就打得到。 */
	public static final double MELEE_REACH = 3.0;
	/** 箭的速度大約 1.6 格/tick。 */
	public static final double ARROW_SPEED = 1.6;

	/**
	 * 自己的戰力：血量、最佳近戰武器的傷害和攻擊間隔(秒)、護甲值、有沒有盾牌、弓箭、可吃的食物，
	 * 以及這一場能不能跳劈(不在水裡、不在梯子上)。
	 */
	public record Fighter(float hp, double weaponDamage, double attackInterval, int armor, boolean shield, boolean bow,
		boolean food, boolean canCrit) {
		/** 跳劈划不划算：攻擊間隔夠長(劍、斧)才划算。 */
		public boolean critWorthIt() {
			return canCrit && attackInterval >= CRIT_MIN_INTERVAL;
		}
	}

	/**
	 * 預估打死這個目標會掉多少血：打死要幾秒 × 每秒會被打掉多少(扣掉護甲)。
	 * 打死要幾秒 = ceil(目標血量 / 每下傷害) × 攻擊間隔，遠程怪每下至少隔 {@link #RANGED_REENGAGE_SECS}、再加上衝過去的時間。
	 *
	 * @param meleeNear     6 格內的近戰怪數量(包含目標本身，如果牠是近戰怪)
	 * @param rangedExposed 看得到自己、而且盾牌擋不到的遠程怪數量：沒盾時是全部；有盾時只算不在正面(跟目標方向差 90 度以上)的
	 * @param rangedTarget  目標是遠程怪
	 */
	public static double expectedLoss(Fighter me, double targetHp, double distance, int meleeNear, int rangedExposed, boolean rangedTarget) {
		double perHit = Math.max(0.5, me.weaponDamage() * (me.critWorthIt() ? CRIT_MULTIPLIER : 1));
		double interval = rangedTarget ? Math.max(me.attackInterval(), RANGED_REENGAGE_SECS) : me.attackInterval();
		double secs = Math.ceil(Math.max(1, targetHp) / perHit) * interval;
		if (rangedTarget) secs += Math.max(0, distance - MELEE_REACH) / APPROACH_SPEED;
		double dps = meleeNear * MELEE_DPS + rangedExposed * RANGED_DPS;
		dps *= Math.max(0, 1 - Math.min(20, me.armor()) * ARMOR_REDUCTION);
		return secs * dps;
	}

	/** 勝算：打完血量還在撤退線以上才打。 */
	public static boolean worthFighting(Fighter me, double targetHp, double distance, int meleeNear, int rangedExposed, boolean rangedTarget) {
		return me.hp() - expectedLoss(me, targetHp, distance, meleeNear, rangedExposed, rangedTarget) > RETREAT_HP;
	}

	public enum RangedStance {
		/** 用弓對射。 */
		SHOOT,
		/** 牠拉弓或箭飛來時舉盾擋，兩輪射擊之間往前衝。 */
		GUARD_ADVANCE,
		/** 沒有盾，直接衝過去。 */
		CHARGE,
		/**
		 * 打得贏但一直走不近(牠邊退邊射)：躲到掩體後埋伏，牠看不到人會繞過來找，走進攻擊距離就砍。
		 * 跟 COVER 不同，這不是脫身(2026-11-26 遊戲測試：預估掉血 0 卻因為走不近就跑掉)。
		 */
		AMBUSH,
		/** 打不贏：躲到看不到牠的地方(箭飛來照樣舉盾)。 */
		COVER
	}

	/**
	 * 遠程怪怎麼打。
	 *
	 * @param stalled 一直走不近(3 秒沒有更接近，砍中或擋箭時重算)
	 */
	public static RangedStance rangedStance(Fighter me, double distance, boolean los, boolean stalled, double targetHp, int meleeNear,
		int rangedExposed) {
		if (me.bow() && los && (distance > 8 || stalled)) return RangedStance.SHOOT;
		if (worthFighting(me, targetHp, distance, meleeNear, rangedExposed, true)) {
			if (stalled) return RangedStance.AMBUSH;
			return me.shield() ? RangedStance.GUARD_ADVANCE : RangedStance.CHARGE;
		}
		return RangedStance.COVER;
	}

	/** 骷髏放箭後盾牌還要舉幾 tick：箭飛過來的時間再留一點餘裕，最少 8 tick。 */
	public static int shieldHoldTicks(double distance) {
		return Math.max(8, (int) Math.ceil(distance / ARROW_SPEED) + 4);
	}

	/**
	 * 脫身或撤退時，停下舉盾面向 {@code (faceDx, faceDz)} 安不安全：
	 * 如果有其他看得到自己的遠程怪在側面／背後(跟舉盾方向夾角超過 90 度)，停下來只會背對牠們挨射，應該繼續跑。
	 *
	 * @param archerOffsets 各個看得到自己的遠程怪相對於自己的水平向量 {@code {dx, dz}}
	 */
	public static boolean canSafelyStopToShield(double faceDx, double faceDz, List<double[]> archerOffsets) {
		double fLen = Math.hypot(faceDx, faceDz);
		if (fLen < 1e-4) return true;
		double fx = faceDx / fLen, fz = faceDz / fLen;
		for (double[] a : archerOffsets) {
			double aLen = Math.hypot(a[0], a[1]);
			if (aLen < 1e-4) continue;
			if ((a[0] * fx + a[1] * fz) / aLen < SHIELD_FRONT_COS) return false;
		}
		return true;
	}

	/** 撤退 RUN → RECOVER：16 格內沒有看得到自己的威脅，且 6 格內沒有威脅(含隔牆，與 RECOVER 的 4 格條件留遲滯避免震盪)。 */
	public static boolean shouldEnterRetreatRecover(int visible16, int near6) {
		return visible16 == 0 && near6 == 0;
	}

	/** 撤退 RECOVER → RUN：12 格內有看得到自己的威脅，或 4 格內有威脅逼近。 */
	public static boolean shouldLeaveRetreatRecover(int visible12, int near4) {
		return visible12 > 0 || near4 > 0;
	}

	/**
	 * 現在要不要起跳準備跳劈：站在地上、不在水裡或梯子上、頭上有空間、武器值得跳(劍、斧)、
	 * 冷卻回到 0.6 以上(落下時剛好滿)，而且沒有在擋箭。冷卻已經滿了也跳：每一下都把怪擊退，重新貼上時冷卻總是滿的，
	 * 滿了就不跳的話實戰幾乎打不出暴擊(2026-11-26 遊戲測試：自衛打殭屍 0/5 暴擊)，多等半秒換 1.5 倍傷害划算。
	 */
	public static boolean shouldJumpForCrit(boolean onGround, boolean inFluid, boolean climbing, boolean headroom,
		double attackInterval, float cooldown, boolean guarding) {
		return onGround && !inFluid && !climbing && headroom && attackInterval >= CRIT_MIN_INTERVAL && cooldown >= 0.6f
			&& !guarding;
	}

	/** 跳起來之後什麼時候砍：往下掉而且冷卻滿了(原版暴擊條件)。 */
	public static boolean critReady(boolean onGround, double verticalSpeed, float cooldown) {
		return !onGround && verticalSpeed < 0 && cooldown >= 0.9f;
	}

	// ---- 戰前評估(S9.1c) ----

	public enum Gear {WEAK, OK, STRONG}

	/** 備戰程度和缺什麼(依序 sword、shield、armor、bow、food)。 */
	public record Readiness(Gear gear, List<String> missing) {
		public String label() {
			return gear.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** 沒有傷害 ≥ 4 的武器，或護甲 < 5 又沒盾：weak；石劍以上 + 盾 + 護甲 ≥ 15：strong；其他 ok。 */
	public static Readiness readiness(Fighter me) {
		Gear g;
		if (me.weaponDamage() < 4 || (me.armor() < 5 && !me.shield())) g = Gear.WEAK;
		else if (me.weaponDamage() >= 5 && me.shield() && me.armor() >= 15) g = Gear.STRONG;
		else g = Gear.OK;
		List<String> missing = new ArrayList<>();
		if (me.weaponDamage() < 5) missing.add("sword");
		if (!me.shield()) missing.add("shield");
		if (me.armor() < 15) missing.add("armor");
		if (!me.bow()) missing.add("bow");
		if (!me.food()) missing.add("food");
		return new Readiness(g, List.copyOf(missing));
	}

	public enum DangerLevel {LOW, MEDIUM, HIGH}

	public record Danger(DangerLevel level, List<String> reasons) {
		public String label() {
			return level.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/**
	 * 危險程度(給 AI 看，讓牠決定要不要收工)。
	 *
	 * @param dark     附近會生怪：夜裡在戶外，或在室內/地底而且腳下光照 0
	 * @param outdoors 頭上看得到天空
	 * @param hostiles 24 格內的威脅數量
	 */
	public static Danger danger(boolean dark, boolean outdoors, int hostiles, float hp, Gear gear) {
		List<String> reasons = new ArrayList<>();
		if (dark) reasons.add("dark");
		if (outdoors) reasons.add("outdoors");
		if (hostiles > 0) reasons.add("hostiles:" + hostiles);
		if (gear == Gear.WEAK) reasons.add("gear_weak");
		if (hp <= RECOVERED_HP) reasons.add("hp_low");
		DangerLevel level;
		if ((dark && gear == Gear.WEAK) || hostiles >= 3 || (hp <= RECOVERED_HP && hostiles >= 1)) level = DangerLevel.HIGH;
		else if (dark || hostiles >= 1) level = DangerLevel.MEDIUM;
		else level = DangerLevel.LOW;
		return new Danger(level, List.copyOf(reasons));
	}

	// ---- 撤退(S9.1 retreat) ----

	public enum RetreatOutcome {
		/** 回血了：接著做原本的任務。 */
		RESUME,
		/** 同一個任務第二次撤退：這裡太危險，取消任務交給 AI。 */
		CANCEL_REPEAT,
		/** 回不了血(沒食物、一直被追)：取消任務交給 AI。 */
		CANCEL_CANNOT_HEAL
	}

	/**
	 * 撤退結束後原本的任務怎麼辦。
	 *
	 * @param how              撤退的結束原因(recovered、cannot_heal、gave_up_after_60s、turned_off、preempted_by_…)
	 * @param retreatsThisTask 這個任務(包含這次)撤退了幾次
	 */
	public static RetreatOutcome retreatOutcome(String how, int retreatsThisTask) {
		if ("cannot_heal".equals(how) || how.startsWith("gave_up")) return RetreatOutcome.CANCEL_CANNOT_HEAL;
		if (retreatsThisTask >= 2 && "recovered".equals(how)) return RetreatOutcome.CANCEL_REPEAT;
		return RetreatOutcome.RESUME;
	}

	/**
	 * 近戰/苦力怕/遠程戰術中，是否該停在攻擊距離內等冷卻/舉盾，而不是呼叫 {@code approach()} 繞路。
	 * 隔著方塊看不到目標({@code !hasLineOfSight})時必須回傳 {@code false}，否則會在牆前卡死(P3-H4)。
	 */
	public static boolean shouldWaitInReach(boolean inReach, boolean hasLineOfSight) {
		return inReach && hasLineOfSight;
	}

	/**
	 * 尚未拉弓時，是否具備放箭前提(視線/彈道沒被屋頂或牆擋住、身上有弓)。
	 * 必須在放下盾牌之前先檢查，避免屋頂下每 tick 收盾又舉盾(P3-H5)。
	 */
	public static boolean canPrepareShot(boolean hasClearShot, boolean hasBow) {
		return hasClearShot && hasBow;
	}

	/**
	 * 脫困反射結束時，是否應算作在任務無進展下累計達 3 次而中斷原任務。
	 * 即使當次脫困後退成功，只要任務本身仍未推進、反覆卡住達 3 次就必須中斷，避免「卡住 10 秒 → 後退 3 格 → 又卡回原點」死循環。
	 * 被新任務替換({@code task_replaced})、取消({@code task_cancelled})或關閉({@code turned_off})時不算失敗(P3-M13)。
	 */
	public static boolean shouldReportUnstuckFailure(String how, int stalledAttempts) {
		if (stalledAttempts < 3) return false;
		return !"task_replaced".equals(how) && !"task_cancelled".equals(how) && !"turned_off".equals(how);
	}

	/**
	 * 自衛反射收手後的單隻生物冷卻表(H8、P3-M2)：
	 * <ul>
	 *   <li>被更高優先級反射搶佔({@code preempted == true})時不加冷卻，免得保命完忽略身邊的苦力怕。</li>
	 *   <li>只有在「冷卻開始<strong>之後</strong>」該生物再次攻擊自己({@code lastAttackedTick > startTick})才提早解除冷卻，
	 *       避免收手前 20 tick 內的舊傷在下一 tick 立刻繞過冷卻。</li>
	 * </ul>
	 */
	public static final class DefenseCooldown {
		private record Entry(long startTick, long untilTick) {
		}

		private final Map<Integer, Entry> entries = new HashMap<>();

		public void put(int entityId, long startTick, long durationTicks, boolean preempted) {
			if (preempted || durationTicks <= 0) return;
			entries.put(entityId, new Entry(startTick, startTick + durationTicks));
		}

		public void prune(long nowTick) {
			entries.values().removeIf(e -> e.untilTick() <= nowTick);
		}

		public boolean isEmpty() {
			return entries.isEmpty();
		}

		public void clear() {
			entries.clear();
		}

		/**
		 * @param entityId         候選目標 entity id
		 * @param nowTick          目前遊戲 tick
		 * @param recentAttackerId 最近打自己的生物 id(無則 -1)
		 * @param lastAttackedTick 最近一次被打的 tick
		 */
		public boolean isCoolingDown(int entityId, long nowTick, int recentAttackerId, long lastAttackedTick) {
			Entry e = entries.get(entityId);
			if (e == null) return false;
			if (e.untilTick() <= nowTick) {
				entries.remove(entityId);
				return false;
			}
			// 只有冷卻開始之後的新攻擊才解除冷卻
			if (entityId == recentAttackerId && lastAttackedTick > e.startTick()) return false;
			return true;
		}
	}
}
