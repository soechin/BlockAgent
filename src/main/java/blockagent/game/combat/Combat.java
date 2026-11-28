package blockagent.game.combat;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import blockagent.core.Ballistics;
import blockagent.core.CombatPolicy;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import blockagent.game.UseKey;
import blockagent.game.WaterFloat;
import blockagent.game.tasks.Moves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 依怪物種類應對(docs/SPEC.md S9.1b)。自衛反射和 hunt 共用：每 tick 呼叫 {@link #tick}，
 * 依 {@link Threats.Kind} 決定走近打、打了就退、跑開、舉盾、用弓射或躲起來。
 * 每 tick 先問 {@link ShieldGuard} 要不要擋箭(R1)；打不打、怎麼打看 {@link CombatPolicy} 的勝算(R7)。
 * 所有攻擊都經過守門員；射箭前做拋物線檢查(S7.7)。
 */
public final class Combat {
	/** 移動交給呼叫的人：反射直接用 Baritone，任務要記住目標以便恢復。 */
	public interface Mover {
		void path(Goal goal);

		void stop();
	}

	public enum Outcome {CONTINUE, BLOCKED, GONE, SUBMERGED_TARGET, OUTMATCHED}

	/** priority 越小越優先：0 膨脹中的苦力怕，1 苦力怕或要避開的，2 貼身的，3 瞄準中的遠程，4 其他。 */
	public record Threat(LivingEntity entity, int priority, String why) {
	}

	public static final double TRIGGER_RANGE = 16;
	/** 近戰怪超過這個距離就收手(沒有弓時)；觸發條件也用同一個距離，免得一開始就結束、馬上又觸發。 */
	private static final double MELEE_RANGE = 8;
	/** 走不近：這麼多 tick 沒有更接近(砍中或擋箭時重算，R2)。 */
	private static final int STALL_TICKS = 60;

	private final Agent agent;
	private final ActionLog.Source source;
	private final String logPrefix;
	private final Mover mover;
	/** true：hunt 主動去打；false：自衛反射。 */
	private final boolean hunting;

	private LivingEntity target;
	private String tactic;
	private final Set<String> used = new LinkedHashSet<>();
	private int backoff;
	private long lastPathTick = -100;
	private BlockPos pathKey;
	private double closest;
	private int noCloserTicks;
	private int lostSightTicks;
	private final CoverRoute coverRoute = new CoverRoute();
	private long lastFleeAllTick = -100;
	private RuleGuard.Verdict blocked;
	private int hits;
	private long lastHoldLog = -1000;
	private boolean prepared;
	/** 這個 tick 有在拉弓或放箭；沒有的話 tick 最後把弓收起來。 */
	private boolean shooting;
	/** 已送出使用弓的快捷列位置。伺服器同步的使用狀態可能比客戶端起手慢。 */
	private int bowDrawSlot = -1;
	private String drawStopReason;
	private int firedCrossbowSlot = -1;
	private long crossbowFiredAt;
	private int aimingCrossbowAt = -1;
	private long crossbowAimStart;
	private long crossbowAimTick = -1;
	private float crossbowAimYaw;
	private float crossbowAimPitch;
	private final ShieldGuard guard = new ShieldGuard();
	/** 這個 tick 有遠程攻擊需要戒備(擋箭空檔可平砍但不跳劈)。 */
	private boolean guardActive;
	/** 正在掩體後埋伏等遠程怪繞過來(容許較長的失去視線時間)。 */
	private boolean ambushing;
	/**
	 * 打不贏，正在脫身(一旦決定就不再改主意，換目標也一樣，免得一下打一下跑)：借掩護擋住射手、
	 * 往遠離整群怪的方向跑，甩掉整群才收手。
	 */
	private boolean outmatched;
	/** 脫身時記給 AI 看的戰術：近戰怪是 disengage_outmatched，遠程怪是 back_off。 */
	private String escapeTactic = "back_off";
	/** 脫身時連續多少 tick 沒有任何威脅看得到自己。 */
	private int unseenTicks;
	/** 為了跳劈起跳了，還沒出手。 */
	private boolean jumpedForCrit;
	/** 打苦力怕前先按前進+衝刺一 tick，讓這一下帶衝刺擊退。 */
	private boolean sprintWindup;
	/** 在開放水域近戰或追擊水面目標時，由戰鬥邏輯按住空白鍵／前進鍵(與 sprintWindup 獨立清理)。 */
	private boolean waterJumpHeld;
	private boolean waterForwardHeld;
	// ---- 戰鬥紀錄(fight_summary) ----
	private long startTick = -1;
	private float hpStart;
	private int swings;
	private int shots;
	private int arrowHits;
	private int arrowsEmbedded;
	private int arrowsUnconfirmed;
	private int drawCancels;
	private int kills;
	private int submergedMisses;
	private float submergedHealth;
	private int crits;
	private int hitsTaken;
	private int shieldBlocks;
	private int lastHurtTime;
	private int lastShieldDamage = -1;
	/** 最後一次打中(揮劍或放箭)的目標和 tick：目標死了是不是自己打死的。 */
	private int lastHitId = -1;
	private long lastHitTick = -1000;
	/** 打中後這麼多 tick 內死掉算自己打死的(箭還在飛、死亡動畫)。 */
	private static final int KILL_CREDIT_TICKS = 40;
	private static final int SUBMERGED_MISS_LIMIT = 3;
	private final Set<String> targets = new LinkedHashSet<>();

	public Combat(Agent agent, ActionLog.Source source, String logPrefix, Mover mover, boolean hunting) {
		this.agent = agent;
		this.source = source;
		this.logPrefix = logPrefix;
		this.mover = mover;
		this.hunting = hunting;
	}

	// ---- 挑目標(反射的觸發條件) ----

	/**
	 * 16 格內最該處理的怪物；沒有回傳 null。只看自己和怪物，不看其他玩家(S4.7)。
	 * 除了要避開的，都要看得到才算(H8：隔著牆的怪物打不到，觸發了只會每隔幾秒打斷任務)。
	 * 不是敵對生物、但剛打過自己的(狼、蜜蜂、鐵傀儡…)也算，受保護的寵物除外(M14)。
	 *
	 * @param ignore 剛收手、還在冷卻的怪物
	 */
	public static Threat pickThreat(Minecraft mc, Agent a, java.util.function.Predicate<Entity> ignore) {
		LocalPlayer p = mc.player;
		Entity attacker = a.observation.recentAttacker(mc, 100);
		boolean bow = Bow.ready(p);
		Threat best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(TRIGGER_RANGE),
			e -> e instanceof LivingEntity && e.isAlive()
				&& (e instanceof Enemy || (e == attacker && !RuleGuard.isProtectedEntity(e))))) {
			LivingEntity le = (LivingEntity) e;
			double d = e.distanceTo(p);
			if (d > TRIGGER_RANGE || ignore.test(e)) continue;
			boolean hurtMe = e == attacker;
			boolean los = p.hasLineOfSight(e);
			Threats.Kind kind = Threats.kind(e, bow, p, attacker);
			if (!los && kind != Threats.Kind.AVOID) continue;
			Vec3 moved = e.position().subtract(e.xo, e.yo, e.zo);
			boolean approaching = moved.dot(p.position().subtract(e.position())) > 0 || e.getDeltaMovement().dot(p.position().subtract(e.position())) > 0;
			boolean angry = Threats.angry(e);
			String name = id(e);
			Threat t = switch (kind) {
				case EXPLODER -> {
					boolean swelling = ((Creeper) e).getSwellDir() > 0;
					yield d <= 3.5 || (d <= 6 && (angry || approaching || hurtMe))
						? new Threat(le, swelling ? 0 : 1, name + (swelling ? " swelling" : " approaching") + " dist=" + fmt(d)) : null;
				}
				case AVOID -> new Threat(le, 1, name + " to_avoid dist=" + fmt(d));
				case RANGED -> hurtMe || angry
					? new Threat(le, d <= 3 ? 2 : 3, name + (le.isUsingItem() ? " aiming_bow" : " targeting_you") + " dist=" + fmt(d)) : null;
				case NEUTRAL -> (hurtMe || angry) && d <= 6 ? new Threat(le, d <= 3 ? 2 : 4, name + " angry dist=" + fmt(d)) : null;
				// 打過自己的近戰怪：8 格內才處理(跟結束條件一致)；有弓而且看得到時 16 格內就先射
				case MELEE -> (hurtMe && (d <= MELEE_RANGE || (bow && Bow.canShoot(e)))) || (d <= 6 && angry) || (d <= 3.5 && approaching)
					? new Threat(le, d <= 3 ? 2 : 4, name + " close dist=" + fmt(d)) : null;
			};
			if (t != null && (best == null || t.priority() < best.priority() || (t.priority() == best.priority() && d < bestD))) {
				best = t;
				bestD = d;
			}
		}
		return best;
	}

	// ---- 狀態 ----

	public LivingEntity target() {
		return target;
	}

	/** 換目標(或第一次指定)。 */
	public void engage(LivingEntity e) {
		if (e == target) return;
		if (target != null) {
			boolean died = targetDied();
			if (died && recentlyAttackedTarget()) kills++;
			// 舊目標已死或原本只是避開深水目標時，換目標後重新評估勝算，不沿用舊的脫身狀態。
			if (shouldResetOutmatchedOnRetarget(died, escapeTactic)) {
				outmatched = false;
				escapeTactic = "disengage_outmatched";
			}
			cancelDraw(Minecraft.getInstance(), "target_changed");
		}
		target = e;
		submergedMisses = 0;
		submergedHealth = e.getHealth();
		targets.add(id(e));
		closest = e.distanceTo(Minecraft.getInstance().player);
		noCloserTicks = 0;
		lostSightTicks = 0;
		coverRoute.clear();
		backoff = 0;
		jumpedForCrit = false;
		sprintWindup = false;
		ambushing = false;
		agent.danger.setIgnoredEntity(outmatched ? -1 : e.getId());
	}

	/**
	 * 自己現在的戰力(R7)：最佳近戰武器、護甲、盾牌(副手或開戰能換上去的未冷卻盾)、弓箭、食物，
	 * 以及能不能跳劈。戰前評估(S9.1c)也用這個。
	 */
	public static CombatPolicy.Fighter assess(LocalPlayer p) {
		Inv.Weapon w = Inv.bestWeapon(p, s -> true);
		CombatPolicy.Difficulty difficulty = switch (p.level().getLevelData().getDifficulty()) {
			case PEACEFUL -> CombatPolicy.Difficulty.PEACEFUL;
			case EASY -> CombatPolicy.Difficulty.EASY;
			case NORMAL -> CombatPolicy.Difficulty.NORMAL;
			case HARD -> CombatPolicy.Difficulty.HARD;
		};
		return new CombatPolicy.Fighter(p.getHealth(), w.damage(), w.interval(), Inv.wornArmor(p), canUseShield(p), Bow.ready(p),
			Inv.hasFood(p), !p.isInLiquid() && !p.onClimbable(), difficulty, p.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
	}

	public static boolean hardDifficulty(LocalPlayer player) {
		return player.level().getLevelData().getDifficulty() == Difficulty.HARD;
	}

	/** 副手已有未冷卻盾牌，或開戰時能把背包裡的未冷卻盾牌換上副手(副手不是不死圖騰且沒開其他容器)。 */
	private static boolean canUseShield(LocalPlayer p) {
		if (ShieldGuard.hasShield(p)) return true;
		if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || p.containerMenu != p.inventoryMenu) return false;
		return Inv.find(p, s -> s.has(DataComponents.BLOCKS_ATTACKS) && !p.getCooldowns().isOnCooldown(s)) >= 0;
	}

	/** 打不贏，正在(或剛)拉開距離。 */
	public boolean outmatched() {
		return outmatched;
	}

	/** 打到或射出過至少一次。 */
	public boolean engaged() {
		return hits > 0;
	}

	/** 出手(揮劍或放箭)的次數。 */
	public int hits() {
		return hits;
	}

	public RuleGuard.Verdict blocked() {
		return blocked;
	}

	/** 用過的戰術(給 AI 看)，例如 {@code hit_and_back,creeper_run}；沒出手是 none。 */
	public String summary() {
		return used.isEmpty() ? "none" : String.join(",", used);
	}

	private Threats.Kind targetKind(Minecraft mc, boolean bow) {
		return Threats.kind(target, bow, mc.player, agent.observation.recentAttacker(mc, 100));
	}

	/** 自衛反射什麼時候可以收手。 */
	public boolean finished(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (gone(mc)) return true;
		double d = target.distanceTo(p);
		boolean canShoot = readyToShoot(p);
		Threats.Kind k = targetKind(mc, canShoot);
		if (k == Threats.Kind.AVOID) return d > 24;
		// 脫身要甩掉整群：最近的威脅超過 18 格，或超過 12 格而且 3 秒都沒有一隻看得到自己。
		// 只看目標、躲到牆後就收手的話，牠們繞過來或追上來又觸發自衛(遊戲測試：被 2 隻殭屍追 30 秒觸發 8 次)
		if (outmatched) {
			double nearest = nearestThreat(mc, 24);
			if (hardDifficulty(p)) return (nearest > 20 && unseenTicks >= 20) || (nearest > 12 && unseenTicks > 60);
			return nearest > TRIGGER_RANGE + 2 || (nearest > 12 && unseenTicks > 60);
		}
		int sightLimit = ambushing ? CombatPolicy.AMBUSH_LOST_SIGHT_TICKS : 60;
		if (d > TRIGGER_RANGE + 2 || lostSightTicks > sightLimit) return true;
		return switch (k) {
			case EXPLODER -> d > 10;
			case MELEE, NEUTRAL -> d > MELEE_RANGE && !(k == Threats.Kind.MELEE && canShoot && lostSightTicks == 0);
			default -> false;
		};
	}

	private boolean gone(Minecraft mc) {
		return target == null || !target.isAlive() || mc.level.getEntity(target.getId()) != target;
	}

	// ---- 每 tick ----

	public Outcome tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (gone(mc)) return Outcome.GONE;
		if (mc.options.keyUse.isDown()) UseKey.holdOff(mc);
		if (startTick < 0) {
			startTick = agent.tick();
			hpStart = p.getHealth();
			lastHurtTime = p.hurtTime;
		}
		if (!prepared) {
			prepared = true;
			// 剛穿上的護甲下一個 tick 才算進護甲值：這個 tick 先不判斷勝算，免得用 0 護甲算出「打不贏」就決定脫身
			if (prepare(mc)) return Outcome.CONTINUE;
		}
		trackDamage(p);
		blocked = null;
		double d = target.distanceTo(p);
		boolean los = p.hasLineOfSight(target);
		lostSightTicks = los ? 0 : lostSightTicks + 1;
		boolean hostile = target instanceof Enemy;
		boolean bow = hostile && Bow.canShoot(target) && readyToShoot(p);
		Threats.Kind k = targetKind(mc, bow);
		agent.danger.setIgnoredEntity(outmatched ? -1 : target.getId());
		shooting = false;
		drawStopReason = null;
		Creeper swelling = nearestSwellingCreeper(mc, 5);
		if (swelling != null) {
			avoidExplosion(mc, swelling);
			return Outcome.CONTINUE;
		}
		if (!WaterFloat.needsSurfaceFloat(mc)) releaseWaterKeys(mc);
		BlockPos targetPos = target.blockPosition();
		Level targetLevel = target.level();
		boolean waterBelow = targetLevel.getFluidState(targetPos.below()).is(FluidTags.WATER);
		boolean waterAbove = targetLevel.getFluidState(targetPos.above()).is(FluidTags.WATER);
		boolean targetSurfaceOpen = target.isInWater() && WaterFloat.hasOpenSurfaceAbove(targetLevel, targetPos);
		boolean deepTarget = CombatPolicy.isBlockedDeepWaterTarget(k == Threats.Kind.AVOID, target.isInWater(),
			waterBelow, waterAbove, p.isEyeInFluid(FluidTags.WATER), los && inReach(mc), hunting, hostile, targetSurfaceOpen);
		if (deepTarget && !outmatched && !(bow && los && !p.isInWater() && worthShooting(mc) && clearShot(p))) {
			cancelDraw(mc, "deep_water");
			if (hunting) return Outcome.SUBMERGED_TARGET;
			startEscape("leave_deep_water");
		}
		// 盾牌守備優先(R1)：身旁有苦力怕在膨脹、要避開的、脫身時近戰怪貼身(或停步會露背給其他射手)時照樣跑；
		// 攻擊冷卻滿了而且箭還沒到時可以先平砍(但不跳劈)
		boolean panic = k == Threats.Kind.AVOID || (outmatched && nearestMelee(mc) <= 4);
		guardActive = !panic && guard.update(mc, agent.tick())
			&& (!outmatched || ShieldGuard.canSafelyStop(mc, agent.observation.recentAttacker(mc, 100), guard.faceAt()));
		if (guardActive && !(canStrikeNow(mc, los) && !guard.arrowImminent())) {
			if (!outmatched && drawingBow(p) && p.getTicksUsingItem() >= 15 && d > 3.5 && los
				&& guard.canFireBeforeBlocking() && worthShooting(mc)) {
				releaseArrow(mc, "bow_before_guard");
			}
			holdGuard(mc);
			return Outcome.CONTINUE;
		}
		// 走不近的判斷(R2)：擋箭的 tick 不算；砍中時在 strike() 重算
		if (d < closest - 0.5) {
			closest = d;
			noCloserTicks = 0;
		} else {
			noCloserTicks++;
		}
		if (outmatched) {
			if (hunting && hardDifficulty(p) && finished(mc)) return Outcome.OUTMATCHED;
			escape(mc);
			if (!shooting) cancelDraw(mc, "disengaging");
			return blocked != null ? Outcome.BLOCKED : Outcome.CONTINUE;
		}
		if (deepTarget) {
			if (target.getHealth() < submergedHealth) submergedMisses = 0;
			submergedHealth = target.getHealth();
			int before = shots;
			if (submergedMisses < SUBMERGED_MISS_LIMIT && shoot(mc, "bow_from_shore", 5)) {
				submergedMisses += shots - before;
				return Outcome.CONTINUE;
			}
			cancelDraw(mc, "deep_water");
			if (hunting) return Outcome.SUBMERGED_TARGET;
			startEscape("leave_deep_water");
			escape(mc);
			return Outcome.CONTINUE;
		}
		submergedMisses = 0;
		switch (k) {
			case EXPLODER -> creeper(mc, (Creeper) target, d, los, bow);
			case RANGED -> ranged(mc, d, los);
			case AVOID -> {
				if (hunting && hardDifficulty(p)) startEscape("flee_" + id(target));
				lowerShield(mc);
				tactic("flee_" + id(target));
				flee(target, 24);
			}
			case MELEE, NEUTRAL -> {
				if (hostile && Threats.flying(target)) flyer(mc, d, los, bow);
				else melee(mc, d, los);
			}
		}
		// 這個 tick 換了別的戰術(或瞄不到)：拉到一半的弓收起來，不然會一直拉著、也舉不起盾(H8)
		if (!shooting && hasBowDraw(p)) cancelDraw(mc, drawStopReason(p, k, d, los));
		return blocked != null ? Outcome.BLOCKED : Outcome.CONTINUE;
	}

	/** 舉盾擋：停下 Baritone(走路會轉視角，R1)、對準射手或箭的來向、舉盾。 */
	private void holdGuard(Minecraft mc) {
		halt();
		releaseMoveKeys(mc);
		jumpedForCrit = false;
		sprintWindup = false;
		if (guard.faceAt() != null) Moves.lookAt(mc.player, guard.faceAt());
		raiseShield(mc);
		String src = guard.source();
		tactic(src.startsWith("arrow") || src.startsWith("trident") || src.endsWith("_drawing") ? "shield_vs_bow" : "shield_block");
	}

	/** 開打時自動備戰(S9.1b)：穿上背包裡的護甲、把盾牌換到副手。 */
	private boolean prepare(Minecraft mc) {
		String done = Inv.prepareForCombat(mc);
		if (done.isEmpty()) return false;
		agent.log.log(source, logPrefix + "_equip", done);
		return true;
	}

	/** 記下挨打次數和盾擋次數(盾牌耐久少了 = 擋下一次)。 */
	private void trackDamage(LocalPlayer p) {
		if (p.hurtTime > lastHurtTime) hitsTaken++;
		lastHurtTime = p.hurtTime;
		var off = p.getOffhandItem();
		int dmg = off.has(DataComponents.BLOCKS_ATTACKS) ? off.getDamageValue() : -1;
		if (dmg >= 0 && lastShieldDamage >= 0 && dmg > lastShieldDamage) shieldBlocks++;
		lastShieldDamage = dmg;
	}

	private boolean canStrikeNow(Minecraft mc, boolean los) {
		return los && inReach(mc) && mc.player.getAttackStrengthScale(0) >= 0.9f;
	}

	/** 暫停(例如 HuntTask 被反射暫時接手)：收盾／收弓、放開按鍵、停下移動，但不寫 fight_summary。 */
	public void suspend(Minecraft mc) {
		agent.danger.setIgnoredEntity(-1);
		sprintWindup = false;
		jumpedForCrit = false;
		if (mc.player != null && mc.gameMode != null) {
			if (hasBowDraw(mc.player)) cancelDraw(mc, "suspended");
			else lowerShield(mc);
		}
		releaseMoveKeys(mc);
		if (mc.options != null && mc.options.keyUse.isDown()) mc.options.keyUse.setDown(false);
		try {
			halt();
		} catch (RuntimeException ignored) {
			// 不在世界裡時 Baritone 可能還沒準備好；按鍵已經放開了
		}
	}

	/** 結束時：放下盾牌或收弓、放開使用鍵、停下移動。斷線時(沒有玩家)只放開按鍵(H9)。 */
	public void release(Minecraft mc) {
		release(mc, "ended");
	}

	/**
	 * 結束並記一筆 {@code fight_summary}(S9.1b：試玩後比較擊殺率、每場掉血、盾擋次數，用來調整
	 * {@link CombatPolicy} 的常數)。
	 *
	 * @param how 結束原因(反射的結束原因；任務是 ended)
	 */
	public void release(Minecraft mc, String how) {
		agent.danger.setIgnoredEntity(-1);
		sprintWindup = false;
		jumpedForCrit = false;
		if (mc.player != null && mc.gameMode != null) {
			if (hasBowDraw(mc.player)) cancelDraw(mc, targetDied() ? "target_died" : how);
			else lowerShield(mc);
			summarize(mc, how);
		}
		releaseMoveKeys(mc);
		if (mc.options != null && mc.options.keyUse.isDown()) mc.options.keyUse.setDown(false);
		try {
			halt();
		} catch (RuntimeException ignored) {
			// 不在世界裡時 Baritone 可能還沒準備好；按鍵已經放開了
		}
	}

	public boolean targetDied() {
		return target != null && (target.isDeadOrDying() || target.getRemovalReason() == Entity.RemovalReason.KILLED);
	}

	/** 只有剛出手的目標才算自己打死的，免得把自然死亡或別人的擊殺算進來。 */
	private boolean recentlyAttackedTarget() {
		return target.getId() == lastHitId && agent.tick() - lastHitTick <= KILL_CREDIT_TICKS;
	}

	private void summarize(Minecraft mc, String how) {
		if (startTick < 0) return;
		LocalPlayer p = mc.player;
		boolean targetDied = targetDied();
		boolean killed = targetDied && recentlyAttackedTarget();
		String result;
		if (p.isDeadOrDying()) result = "died";
		else if (targetDied) result = killed ? "killed" : "target_died";
		else if (outmatched) result = "disengaged";
		else result = how;
		agent.log.log(source, "fight_summary", "targets=" + String.join(",", targets) + " secs=" + fmt((agent.tick() - startTick) / 20.0)
			+ " tactics=" + summary() + " swings=" + swings + " crits=" + crits + " shots=" + shots
			+ " kills=" + (kills + (killed ? 1 : 0)) + " hp=" + Math.round(hpStart) + "->" + Math.round(p.getHealth())
			+ " hits_taken=" + hitsTaken + " shield_blocks=" + shieldBlocks + " fight=" + startTick
			+ " arrow_hits=" + arrowHits + " arrow_embedded=" + arrowsEmbedded + " arrow_unconfirmed=" + arrowsUnconfirmed
			+ " arrow_pending=" + (shots - arrowHits - arrowsEmbedded - arrowsUnconfirmed) + " draw_cancelled=" + drawCancels
			+ " result=" + result);
		startTick = -1;
	}

	private void releaseMoveKeys(Minecraft mc) {
		if (mc == null || mc.options == null) return;
		mc.options.keyUp.setDown(false);
		if (waterJumpHeld) mc.options.keyJump.setDown(false);
		waterJumpHeld = false;
		waterForwardHeld = false;
	}

	private void releaseWaterKeys(Minecraft mc) {
		if (mc != null && mc.options != null) {
			if (waterJumpHeld) mc.options.keyJump.setDown(false);
			if (waterForwardHeld) mc.options.keyUp.setDown(false);
		}
		waterJumpHeld = false;
		waterForwardHeld = false;
	}

	private void holdWaterKeys(Minecraft mc, boolean forward) {
		if (mc == null || mc.options == null) return;
		mc.options.keyJump.setDown(true);
		waterJumpHeld = true;
		mc.options.keyUp.setDown(forward);
		waterForwardHeld = forward;
	}

	private static boolean drawingBow(LocalPlayer p) {
		return p.isUsingItem() && Bow.isBow(p.getUseItem());
	}

	private boolean hasBowDraw(LocalPlayer player) {
		return bowDrawSlot >= 0 || drawingBow(player);
	}

	/** 取消拉弓，不射出去。 */
	private void cancelDraw(Minecraft mc, String reason) {
		if (!hasBowDraw(mc.player)) return;
		logDrawCancel(mc.player, reason);
		if (bowDrawSlot >= 0) {
			UseKey.cancelMainHandUse(mc, bowDrawSlot);
			bowDrawSlot = -1;
		} else {
			UseKey.cancelUse(mc);
		}
	}

	private void logDrawCancel(LocalPlayer player, String reason) {
		if (target == null || startTick < 0) return;
		drawCancels++;
		agent.log.log(source, logPrefix + "_draw_cancel", "fight=" + startTick + " target=" + id(target)
			+ " target_id=" + target.getId() + " reason=" + reason + " draw_ticks=" + (drawingBow(player) ? player.getTicksUsingItem() : 0));
	}

	private String drawStopReason(LocalPlayer player, Threats.Kind kind, double distance, boolean los) {
		if (drawStopReason != null) return drawStopReason;
		if (!los) return "line_of_sight";
		if (!Bow.bowAndAmmo(player)) return "no_ammo";
		double minimum = kind == Threats.Kind.RANGED ? 5 : 3.5;
		if (!keepDrawing(player, distance, minimum)) return "target_too_close";
		if (!clearShot(player)) return "trajectory_blocked";
		return outmatched ? "disengaging" : "tactic_changed";
	}

	// ---- 戰術 ----

	private void melee(Minecraft mc, double d, boolean los) {
		CombatPolicy.Fighter me = assessSelf(mc.player);
		int others = meleeNear(mc);
		List<LivingEntity> shooters = archersSeeingMe(mc);
		if (los && target instanceof Enemy && Bow.canShoot(target) && (d > 6 || keepDrawing(mc.player, d, 3.5))
			&& CombatPolicy.worthShooting(me, target.getHealth(), others, rangedPressure(mc, shooters, me, false, false))
			&& shoot(mc, "bow_first", 4.5)) return;
		int melee = others + (target instanceof Enemy || !hunting ? 1 : 0);
		double exposed = rangedPressure(mc, shooters, me, me.shield(), melee > 0);
		if ((!hunting || me.hard()) && !CombatPolicy.worthFighting(me, target.getHealth(), d, melee, exposed, false)) {
			logOdds(me, CombatPolicy.expectedLoss(me, target.getHealth(), d, melee, exposed, false), melee, exposed);
			startEscape("disengage_outmatched");
			escape(mc);
			return;
		}
		boolean inOpenWater = WaterFloat.needsSurfaceFloat(mc);
		boolean directWaterChase = CombatPolicy.shouldDirectWaterChase(inOpenWater, los,
			WaterFloat.isOnWaterSurface(mc.level, target));
		if (directWaterChase || (inOpenWater && inReach(mc) && los)) {
			// 在開放水域打水面／岸邊目標：直接邊按空白鍵保持漂浮邊游近揮劍，不交給 Baritone 算深水路徑
			halt();
			holdWaterKeys(mc, directWaterChase && !inReach(mc));
			if (strike(mc)) {
				tactic("melee");
				return;
			}
			if (blocked != null) {
				releaseWaterKeys(mc);
				return;
			}
			if (CombatPolicy.shouldWaitInReach(inReach(mc), los)) {
				Moves.lookAt(mc.player, target.getBoundingBox().getCenter());
				holdWaterKeys(mc, false);
				if (target instanceof Enemy && d < 4 && !jumpedForCrit) raiseShield(mc);
				tactic("melee");
				return;
			}
			if (d >= 4) lowerShield(mc);
			Moves.lookAt(mc.player, target.getBoundingBox().getCenter());
			holdWaterKeys(mc, true);
			tactic("melee");
			return;
		}
		releaseWaterKeys(mc);
		if (strike(mc)) {
			tactic("melee");
			return;
		}
		if (blocked != null) return;
		if (CombatPolicy.shouldWaitInReach(inReach(mc), los) && !hazardBehind(mc)) {
			// 等攻擊冷卻：舉盾(隔著牆看不到時不能停在這裡，要往下進 approach 繞過去，P3-H4)；
			// 正在為跳劈起跳時不舉盾，落下時要砍
			if (d < 4 && !jumpedForCrit) raiseShield(mc);
			return;
		}
		if (d >= 4) lowerShield(mc);
		tactic("melee");
		approach(mc);
	}

	/** 6 格內、除了目前交戰目標的近戰威脅數量。 */
	private int meleeNear(Minecraft mc) {
		LocalPlayer p = mc.player;
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		int n = 0;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(6), e -> Threats.isThreat(e, p, attacker))) {
			Threats.Kind k = Threats.kind(e, false, p, attacker);
			if (e != target && e.distanceTo(p) <= 6 && k != Threats.Kind.RANGED && k != Threats.Kind.AVOID) n++;
		}
		return n;
	}

	/** 背後(被擊退的方向)1 格是落差 4 格以上、岩漿或水：不要停在這裡挨打，往前挪(S9.1b 站位檢查)。 */
	private boolean hazardBehind(Minecraft mc) {
		LocalPlayer p = mc.player;
		Vec3 away = p.position().subtract(target.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1e-4) return false;
		Vec3 at = p.position().add(away.normalize());
		BlockPos b = BlockPos.containing(at.x, p.getY(), at.z);
		Level level = mc.level;
		for (int dy = 0; dy >= -4; dy--) {
			BlockPos c = b.offset(0, dy, 0);
			if (!level.getFluidState(c).isEmpty()) return true;
			if (dy < 0 && !level.getBlockState(c).getCollisionShape(level, c).isEmpty()) return false;
		}
		return true;
	}

	/** 引爆比持續追擊、舉盾擋箭或走向原本的掩體更急迫。 */
	private void avoidExplosion(Minecraft mc, Creeper creeper) {
		cancelDraw(mc, "creeper_fuse");
		jumpedForCrit = false;
		sprintWindup = false;
		releaseMoveKeys(mc);
		LocalPlayer p = mc.player;
		if (hasShield(mc) && creeper.distanceTo(p) < 4.5 && creeper.getSwelling(1f) > 0.5f) {
			tactic("creeper_shield");
			halt();
			Moves.lookAt(p, creeper.getBoundingBox().getCenter());
			raiseShield(mc);
		} else {
			lowerShield(mc);
			if (!"creeper_run".equals(tactic)) {
				halt();
				lastPathTick = -1000;
			}
			tactic("creeper_run");
			flee(creeper, 8);
		}
	}

	private void creeper(Minecraft mc, Creeper c, double d, boolean los, boolean bow) {
		if (c.getSwellDir() > 0) {
			avoidExplosion(mc, c);
			return;
		}
		if (backoff > 0) {
			sprintWindup = false;
			releaseMoveKeys(mc);
			backoff--;
			flee(c, 5);
			return;
		}
		if (bow && los && (d >= 5 || keepDrawing(mc.player, d, 3.5)) && worthShooting(mc) && shoot(mc, "bow_creeper", 4.5)) {
			sprintWindup = false;
			releaseMoveKeys(mc);
			return;
		}
		// 衝刺擊退：出手前一 tick 先切好近戰武器，再按住前進並開衝刺，避免下一 tick 換武器重設冷卻
		if (!sprintWindup && los && inReach(mc) && !mc.player.isSprinting() && mc.player.getFoodData().getFoodLevel() > 6
			&& mc.player.onGround()) {
			selectMeleeWeapon(mc);
			if (mc.player.getAttackStrengthScale(0) >= 0.9f) {
				halt();
				lowerShield(mc);
				Moves.lookAt(mc.player, c.getBoundingBox().getCenter());
				mc.options.keyUp.setDown(true);
				mc.player.setSprinting(true);
				sprintWindup = true;
				return;
			}
		}
		sprintWindup = false;
		if (strike(mc)) {
			releaseMoveKeys(mc);
			tactic("hit_and_back");
			backoff = 12;
			return;
		}
		releaseMoveKeys(mc);
		if (blocked != null || CombatPolicy.shouldWaitInReach(inReach(mc), los)) return;
		approach(mc);
	}

	private void ranged(Minecraft mc, double d, boolean los) {
		CombatPolicy.Fighter me = assessSelf(mc.player);
		List<LivingEntity> archers = archersSeeingMe(mc);
		int melee = meleeNear(mc);
		double exposed = rangedPressure(mc, archers, me, me.shield(), melee > 0);
		double shooters = rangedPressure(mc, archers, me, false, false);
		boolean canShoot = me.bow() && los && clearShot(mc.player);
		CombatPolicy.RangedStance st = CombatPolicy.rangedStance(me, d, canShoot, noCloserTicks > STALL_TICKS, target.getHealth(),
			melee, exposed, shooters);
		if (canShoot && keepDrawing(mc.player, d, 5)
			&& CombatPolicy.worthShooting(me, target.getHealth(), melee, shooters)) st = CombatPolicy.RangedStance.SHOOT;
		if (hunting && !me.hard() && st == CombatPolicy.RangedStance.COVER) {
			st = me.shield() ? CombatPolicy.RangedStance.GUARD_ADVANCE : CombatPolicy.RangedStance.CHARGE;
		}
		if (st != CombatPolicy.RangedStance.AMBUSH) ambushing = false;
		if (st == CombatPolicy.RangedStance.SHOOT) {
			if (shoot(mc, "bow_duel", 5)) return;
			st = CombatPolicy.rangedStance(me, d, false, noCloserTicks > STALL_TICKS, target.getHealth(), melee, exposed, shooters);
			if (hunting && !me.hard() && st == CombatPolicy.RangedStance.COVER) st = CombatPolicy.RangedStance.CHARGE;
		}
		if (st == CombatPolicy.RangedStance.AMBUSH) {
			ambush(mc);
			return;
		}
		if (st == CombatPolicy.RangedStance.COVER) {
			if (!outmatched) logOdds(me, CombatPolicy.expectedLoss(me, target.getHealth(), d, melee, exposed, true), melee, exposed);
			startEscape("back_off");
			escape(mc);
			return;
		}
		if (strike(mc)) {
			tactic("charge");
			return;
		}
		if (blocked != null) return;
		if (CombatPolicy.shouldWaitInReach(inReach(mc), los)) {
			if (d < 4 && !jumpedForCrit) raiseShield(mc);
			return;
		}
		// 兩輪射擊之間：放下盾牌往前衝(牠再拉弓時 ShieldGuard 會先擋)
		lowerShield(mc);
		tactic(st == CombatPolicy.RangedStance.GUARD_ADVANCE ? "shield_advance" : "charge");
		approach(mc);
	}

	/** 會飛的(幻翼、惱鬼)：追不到，等牠飛近再打，有弓就射。 */
	private void flyer(Minecraft mc, double d, boolean los, boolean bow) {
		if (bow && los && (d > 4 || keepDrawing(mc.player, d, 3.5)) && worthShooting(mc) && shoot(mc, "bow", 4)) return;
		if (strike(mc)) {
			tactic("hit_when_close");
			return;
		}
		if (blocked != null) return;
		halt();
		tactic("wait_for_flyer");
		Moves.lookAt(mc.player, target.getBoundingBox().getCenter());
		if (d < 6) raiseShield(mc);
	}

	/** 決定脫身時記下勝算的依據(試玩後調 CombatPolicy 常數用)。 */
	private void logOdds(CombatPolicy.Fighter me, double loss, int melee, double exposed) {
		if (outmatched) return;
		agent.log.log(source, logPrefix + "_odds", id(target) + " expected_loss=" + fmt(loss) + " hp=" + fmt(me.hp()) + " melee=" + melee
			+ " ranged_exposed=" + fmt(exposed) + " armor=" + me.armor() + " weapon=" + fmt(me.weaponDamage()) + " shield=" + me.shield()
			+ " difficulty=" + Minecraft.getInstance().level.getLevelData().getDifficulty().getSerializedName());
	}

	/**
	 * 埋伏：打得贏但追不近(骷髏邊退邊射)。躲到擋住射手的位置等牠繞過來，走進攻擊距離就砍；
	 * 找不到(或走不到)掩體就重新算「走不近」，再往前推。不是脫身，不會一直跑。
	 */
	private void ambush(Minecraft mc) {
		ambushing = true;
		if (strike(mc)) {
			tactic("hit_from_cover");
			return;
		}
		if (blocked != null) return;
		List<LivingEntity> seeing = archersSeeingMe(mc);
		if (!seeing.isEmpty()) {
			BlockPos spot = coverRoute.choose(mc, seeing, agent.tick());
			if (spot == null) {
				// 附近沒有走得到的掩體可以埋伏：重新往前推
				ambushing = false;
				noCloserTicks = 0;
				closest = target.distanceTo(mc.player);
				return;
			}
			if (!mc.player.blockPosition().equals(spot)) {
				lowerShield(mc);
				tactic("ambush_from_cover");
				goTo(new GoalBlock(spot), spot);
				return;
			}
		}
		// 躲好了：停下來等牠繞過來(箭飛來時 ShieldGuard 會先擋)
		halt();
		tactic("ambush_from_cover");
	}

	private void startEscape(String tactic) {
		if (outmatched) return;
		outmatched = true;
		ambushing = false;
		escapeTactic = tactic;
		unseenTicks = 0;
		coverRoute.clear();
		agent.danger.setIgnoredEntity(-1);
	}

	/**
	 * 脫身(R7、S9.1b)：射手看得到自己時，先躲到擋住所有射手、而且離射手比現在更遠的位置(不會往回躲)；
	 * 沒有射手看得到(或找不到／走不到掩體)就往遠離整群怪的方向跑。遠程目標繞過來走進攻擊距離時照樣砍。
	 */
	private void escape(Minecraft mc) {
		LocalPlayer p = mc.player;
		List<LivingEntity> seeing = archersSeeingMe(mc);
		unseenTicks = seeing.isEmpty() && !anyThreatSees(mc) ? unseenTicks + 1 : 0;
		if (!hardDifficulty(p) && !p.isEyeInFluid(FluidTags.WATER) && targetKind(mc, false) == Threats.Kind.RANGED) {
			if (strike(mc)) {
				tactic("hit_from_cover");
				return;
			}
			if (blocked != null) return;
		}
		lowerShield(mc);
		if (!seeing.isEmpty()) {
			BlockPos spot = coverRoute.choose(mc, seeing, agent.tick());
			if (spot != null && !p.blockPosition().equals(spot)) {
				tactic("hide_from_" + id(seeing.getFirst()));
				goTo(new GoalBlock(spot), spot);
				return;
			}
		}
		tactic(escapeTactic);
		fleeAll(mc, hardDifficulty(p) ? 24 : TRIGGER_RANGE + 6);
	}

	/** 往遠離 24 格內所有威脅的方向跑(節流重規劃：4 格內有怪半秒一次、否則 1.5 秒一次)。 */
	private void fleeAll(Minecraft mc, double distance) {
		long now = agent.tick();
		int interval = nearestThreat(mc, 4) <= 4 ? 10 : 30;
		if (now - lastFleeAllTick < interval && Bari.busy()) return;
		List<BlockPos> from = new ArrayList<>();
		for (Entity e : threats(mc, 24)) from.add(e.blockPosition());
		if (from.isEmpty()) from.add(target.blockPosition());
		lastFleeAllTick = now;
		pathKey = from.getFirst();
		lastPathTick = now;
		releaseWaterKeys(mc);
		mover.path(new GoalRunAway(distance, from.toArray(new BlockPos[0])));
	}

	/** range 格內的威脅(包含目標)。 */
	private List<Entity> threats(Minecraft mc, double range) {
		LocalPlayer p = mc.player;
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		List<Entity> out = new ArrayList<>();
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(range), e -> e == target || Threats.isThreat(e, p, attacker))) {
			if (e.isAlive() && e.distanceTo(p) <= range) out.add(e);
		}
		return out;
	}

	private double nearestThreat(Minecraft mc, double range) {
		double best = Double.MAX_VALUE;
		for (Entity e : threats(mc, range)) best = Math.min(best, e.distanceTo(mc.player));
		return best;
	}

	private double nearestMelee(Minecraft mc) {
		double best = Double.MAX_VALUE;
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		for (Entity e : threats(mc, 8)) {
			if (Threats.kind(e, false, mc.player, attacker) != Threats.Kind.RANGED) best = Math.min(best, e.distanceTo(mc.player));
		}
		return best;
	}

	private static Creeper nearestSwellingCreeper(Minecraft mc, double range) {
		LocalPlayer p = mc.player;
		Creeper nearest = null;
		double best = range;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(range), x -> x instanceof Creeper c
			&& c.isAlive() && c.getSwellDir() > 0)) {
			double distance = e.distanceTo(p);
			if (distance <= best) {
				best = distance;
				nearest = (Creeper) e;
			}
		}
		return nearest;
	}

	private boolean anyThreatSees(Minecraft mc) {
		for (Entity e : threats(mc, TRIGGER_RANGE)) if (mc.player.hasLineOfSight(e)) return true;
		return false;
	}

	/** 16 格內看得到自己的遠程怪。 */
	private List<LivingEntity> archersSeeingMe(Minecraft mc) {
		List<LivingEntity> out = new ArrayList<>();
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		for (Entity e : threats(mc, TRIGGER_RANGE)) {
			if (e instanceof LivingEntity le && Threats.kind(e, true, mc.player, attacker) == Threats.Kind.RANGED
				&& mc.player.hasLineOfSight(e)) out.add(le);
		}
		return out;
	}

	/**
	 * 以每 2 秒一發的射手為單位；困難骷髏每秒一箭。困難混戰在揮劍或轉向時會露出空檔，正面射手仍算半數。
	 */
	private double rangedPressure(Minecraft mc, List<LivingEntity> archers, CombatPolicy.Fighter me, boolean shield, boolean meleeClose) {
		LocalPlayer p = mc.player;
		Vec3 facing = target.position().subtract(p.position()).multiply(1, 0, 1);
		double pressure = 0;
		for (LivingEntity a : archers) {
			double rate = a instanceof AbstractSkeleton ? me.difficulty().skeletonRate() : 1;
			if (!shield || !Threats.shieldBlocks(a)) {
				pressure += rate;
				continue;
			}
			Vec3 dir = a.position().subtract(p.position()).multiply(1, 0, 1);
			if (facing.lengthSqr() < 1e-4 || dir.lengthSqr() < 1e-4) continue;
			if (dir.normalize().dot(facing.normalize()) < CombatPolicy.SHIELD_FRONT_COS) pressure += rate;
			else if (me.hard() && (meleeClose || archers.size() > 1)) pressure += rate * 0.5;
		}
		return pressure;
	}

	// ---- 動作 ----

	/** 依身旁有沒有受保護生物挑近戰武器並切到主手，回傳實際使用的武器數值。 */
	private Inv.Weapon selectMeleeWeapon(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (RuleGuard.protectedNearTarget(p, target) != null) {
			if (!Inv.selectBestWeapon(mc, s -> s.is(ItemTags.AXES))) Inv.selectInHand(mc, s -> !s.is(ItemTags.SWORDS));
			return Inv.bestWeapon(p, s -> s.is(ItemTags.AXES));
		}
		Inv.selectBestWeapon(mc, s -> true);
		return Inv.bestWeapon(p, s -> true);
	}

	/**
	 * 在攻擊距離內、冷卻滿了就打。打了回傳 true；被守門員擋下時記在 {@link #blocked}。
	 * 用劍或斧頭、條件允許時先起跳，往下掉時才砍(跳劈暴擊 1.5 倍)。
	 */
	private boolean strike(Minecraft mc) {
		LocalPlayer p = mc.player;
		// 隔著方塊打不到(原版玩家也打不到)：先走過去(M14)
		if (!inReach(mc) || !p.hasLineOfSight(target)) {
			jumpedForCrit = false;
			return false;
		}
		halt();
		Moves.lookAt(p, target.getBoundingBox().getCenter());
		// 先換武器再看冷卻：換手上的東西會重設冷卻。
		// 目標旁邊有玩家、寵物或村民時不用劍：劍的橫掃會打到牠們(M14)，改用斧頭或其他東西
		Inv.Weapon weapon = selectMeleeWeapon(mc);
		float cd = p.getAttackStrengthScale(0);
		boolean crit = false;
		if (jumpedForCrit && !p.onGround()) {
			if (!CombatPolicy.critReady(false, p.getDeltaMovement().y, cd)) return false;
			crit = true;
		} else {
			jumpedForCrit = false;
			// 苦力怕要的是擊退，不跳；擋箭空檔直接平砍，不放盾起跳
			if (!(target instanceof Creeper) && CombatPolicy.shouldJumpForCrit(p.onGround(), p.isInLiquid(), p.onClimbable(),
				headroom(mc), weapon.interval(), cd, guardActive)) {
				lowerShield(mc);
				p.jumpFromGround();
				jumpedForCrit = true;
				return false;
			}
			if (cd < 0.9f) return false;
		}
		RuleGuard.Verdict v = agent.guard.canAttack(target);
		if (!v.allowed()) {
			blocked = v;
			jumpedForCrit = false;
			if (!hunting) {
				if (agent.tick() - lastHoldLog >= 20) {
					lastHoldLog = agent.tick();
					agent.log.log(source, logPrefix + "_hold", v.reason());
				}
				flee(target, 5);
			}
			return false;
		}
		lowerShield(mc);
		// 原版暴擊不能在衝刺中
		if (crit && p.isSprinting()) p.setSprinting(false);
		agent.log.log(source, logPrefix + "_attack", id(target) + " 距離 " + fmt(target.distanceTo(p)) + (crit ? " crit" : ""));
		mc.gameMode.attack(p, target);
		p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		hits++;
		lastHitId = target.getId();
		lastHitTick = agent.tick();
		swings++;
		if (crit) crits++;
		jumpedForCrit = false;
		ambushing = false;
		// 打中會把目標擊退：從這裡重新算「走不近」(R2)
		closest = target.distanceTo(p);
		noCloserTicks = 0;
		return true;
	}

	/** 頭上兩格內沒有方塊，跳得起來。 */
	private static boolean headroom(Minecraft mc) {
		BlockPos head = mc.player.blockPosition().above(2);
		return mc.level.getBlockState(head).getCollisionShape(mc.level, head).isEmpty();
	}

	private CombatPolicy.Fighter assessSelf(LocalPlayer p) {
		CombatPolicy.Fighter me = assess(p);
		return me.bow() && !readyToShoot(p)
			? new CombatPolicy.Fighter(me.hp(), me.weaponDamage(), me.attackInterval(), me.armor(), me.shield(), false, me.food(), me.canCrit(),
				me.difficulty(), me.armorToughness())
			: me;
	}

	private boolean worthShooting(Minecraft mc) {
		CombatPolicy.Fighter me = assessSelf(mc.player);
		return CombatPolicy.worthShooting(me, target.getHealth(), meleeNear(mc), rangedPressure(mc, archersSeeingMe(mc), me, false, false));
	}

	private static boolean keepDrawing(LocalPlayer player, double distance, double minimum) {
		return drawingBow(player) && CombatPolicy.canContinueDraw(distance, minimum, player.getTicksUsingItem());
	}

	private int findLoadedCrossbow(LocalPlayer p) {
		// 發射後，客戶端要等伺服器同步，快捷列上的弩才會從已裝填變成空弩；若有第二把已裝填的弩仍可使用。
		boolean skipFired = firedCrossbowSlot >= 0 && agent.tick() - crossbowFiredAt <= ArcheryLog.SPAWN_WAIT_TICKS
			&& Bow.loadedArrowCrossbow(p.getInventory().getItem(firedCrossbowSlot));
		if (!skipFired) firedCrossbowSlot = -1;
		var inv = p.getInventory();
		for (int i = 0; i < Inv.MAIN_SIZE; i++) {
			if (skipFired && i == firedCrossbowSlot) continue;
			var stack = inv.getItem(i);
			if (!stack.isEmpty() && Bow.loadedArrowCrossbow(stack)) return i;
		}
		return -1;
	}

	private boolean readyToShoot(LocalPlayer p) {
		return findLoadedCrossbow(p) >= 0 || Bow.bowAndAmmo(p);
	}

	private double shotSpeed(LocalPlayer p) {
		return findLoadedCrossbow(p) >= 0 ? Bow.CROSSBOW_SPEED : Ballistics.FULL_BOW_SPEED;
	}

	private boolean clearShot(LocalPlayer p) {
		return Bow.canShoot(target) && Bow.hasClearShot(p, target, drawingBow(p) ? Ballistics.FULL_BOW_SPEED : shotSpeed(p));
	}

	/** 用已裝填的弩，或拉弓到滿弓／安全距離下限。失去射線時由呼叫端切回近戰或掩護。 */
	private boolean shoot(Minecraft mc, String label, double releaseDistance) {
		LocalPlayer p = mc.player;
		if (!Bow.canShoot(target)) return false;
		if (drawingBow(p)) {
			if (p.getTicksUsingItem() >= Bow.FULL_DRAW || p.getTicksUsingItem() >= 14 && target.distanceTo(p) <= releaseDistance) {
				return releaseArrow(mc, label);
			}
			if (!clearShot(p) || !Bow.aim(p, target, Ballistics.FULL_BOW_SPEED)) {
				drawStopReason = "trajectory_blocked";
				return false;
			}
			halt();
			tactic(label);
			shooting = true;
			return true;
		}
		if (bowDrawSlot >= 0) cancelDraw(mc, "bow_state_changed");
		int crossbowSlot = findLoadedCrossbow(p);
		boolean crossbow = crossbowSlot >= 0;
		if (!crossbow && !Bow.bowAndAmmo(p)) return false;
		double speed = crossbow ? Bow.CROSSBOW_SPEED : Ballistics.FULL_BOW_SPEED;
		if (!Bow.hasClearShot(p, target, speed) || !Bow.aim(p, target, speed)) return false;
		if (p.isUsingItem()) {
			if (p.getUsedItemHand() != InteractionHand.OFF_HAND) return false;
			lowerShield(mc);
		}
		if (crossbow) {
			if (crossbowSlot >= 9 && firedCrossbowSlot >= 0 && p.getInventory().getSelectedSlot() == firedCrossbowSlot) {
				p.getInventory().setSelectedSlot((firedCrossbowSlot + 1) % 9);
			}
			if (!Inv.selectSlotInHand(mc, crossbowSlot)) return false;
		} else if (!Inv.selectInHand(mc, Bow::isBow)) {
			return false;
		}
		halt();
		tactic(label);
		if (crossbow) {
			if (!crossbowAimSettled(p)) return true;
			if (RuleGuard.protectedOnProjectilePath(p, speed) != null) {
				tactic("hold_fire_protected_in_line");
				return false;
			}
			if (!mc.gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction()) return false;
			firedCrossbowSlot = p.getInventory().getSelectedSlot();
			crossbowFiredAt = agent.tick();
			recordShot(p, "crossbow", 0);
			return true;
		}
		bowDrawSlot = p.getInventory().getSelectedSlot();
		UseKey.startUsing(mc, InteractionHand.MAIN_HAND);
		shooting = drawingBow(p);
		return shooting;
	}

	static boolean shouldResetOutmatchedOnRetarget(boolean targetDied, String escapeTactic) {
		return targetDied || "leave_deep_water".equals(escapeTactic);
	}

	static boolean crossbowAimChanged(float yaw, float refYaw, float pitch, float refPitch) {
		return Math.abs(Mth.wrapDegrees(yaw - refYaw)) > 4 || Math.abs(pitch - refPitch) > 4;
	}

	/** 原版弩使用伺服器上一個實體 tick 的頭部朝向，先同步視角再等伺服器更新。 */
	private boolean crossbowAimSettled(LocalPlayer player) {
		long now = agent.tick();
		if (aimingCrossbowAt != target.getId() || now - crossbowAimTick > 1
			|| crossbowAimChanged(player.getYRot(), crossbowAimYaw, player.getXRot(), crossbowAimPitch)) {
			aimingCrossbowAt = target.getId();
			crossbowAimStart = now;
			crossbowAimYaw = player.getYRot();
			crossbowAimPitch = player.getXRot();
			syncShotRotation(player);
		}
		crossbowAimTick = now;
		return now - crossbowAimStart >= 2;
	}

	private static void syncShotRotation(LocalPlayer player) {
		player.connection.send(new ServerboundMovePlayerPacket.Rot(
			player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
	}

	private boolean releaseArrow(Minecraft mc, String label) {
		LocalPlayer p = mc.player;
		double speed = Bow.releaseSpeed(p);
		if (!Bow.bowAndAmmo(p)) {
			drawStopReason = "no_ammo";
			return false;
		}
		if (!Bow.canShoot(target) || speed < 0.3 || !Bow.hasClearShot(p, target, speed) || !Bow.aim(p, target, speed)) {
			drawStopReason = "trajectory_blocked";
			return false;
		}
		halt();
		if (RuleGuard.protectedOnProjectilePath(p, speed) != null) {
			tactic("hold_fire_protected_in_line");
			drawStopReason = "protected_in_line";
			return false;
		}
		tactic(label);
		// 放弓封包沒有視角，先同步瞄準方向再放箭(同 tick 可能立刻轉頭舉盾)。
		syncShotRotation(p);
		int drawTicks = p.getTicksUsingItem();
		UseKey.release(mc);
		bowDrawSlot = -1;
		recordShot(p, "bow", drawTicks);
		shooting = true;
		return true;
	}

	private void recordShot(LocalPlayer player, String weapon, int drawTicks) {
		agent.archery.fired(player, target, agent.tick(), startTick, source, logPrefix, weapon, drawTicks, result -> {
			switch (result) {
				case HIT -> arrowHits++;
				case EMBEDDED -> arrowsEmbedded++;
				case UNCONFIRMED -> arrowsUnconfirmed++;
			}
		});
		shots++;
		hits++;
		lastHitId = target.getId();
		lastHitTick = agent.tick();
	}

	private boolean hasShield(Minecraft mc) {
		return ShieldGuard.hasShield(mc.player);
	}

	private void raiseShield(Minecraft mc) {
		if (hasBowDraw(mc.player)) {
			shooting = false;
			if (ShieldGuard.hasShield(mc.player)) {
				cancelDraw(mc, drawStopReason == null ? "shield" : drawStopReason);
			}
		}
		ShieldGuard.raise(mc);
	}

	private void lowerShield(Minecraft mc) {
		ShieldGuard.lower(mc);
	}

	private boolean inReach(Minecraft mc) {
		return mc.player.isWithinEntityInteractionRange(target, 0);
	}

	private void approach(Minecraft mc) {
		boolean visible = mc.player.hasLineOfSight(target);
		BlockPos at = target.blockPosition();
		goTo(new GoalNear(at, visible ? 1 : 0), at);
	}

	private void flee(Entity from, double distance) {
		BlockPos at = from.blockPosition();
		goTo(new GoalRunAway(distance, at), at);
	}

	/** 同一個目的地 10 tick 內不重新規劃；換目的地也至少隔 5 tick。 */
	private void goTo(Goal goal, BlockPos key) {
		long now = agent.tick();
		if (now - lastPathTick < 5 || (key.equals(pathKey) && now - lastPathTick < 10)) return;
		pathKey = key;
		lastPathTick = now;
		releaseWaterKeys(Minecraft.getInstance());
		mover.path(goal);
	}

	private void halt() {
		if (pathKey == null) return;
		pathKey = null;
		mover.stop();
	}

	private void tactic(String t) {
		if (t.equals(tactic)) return;
		tactic = t;
		used.add(t);
		agent.log.log(source, logPrefix + "_tactic", id(target) + "：" + t);
	}

	static String id(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	static String fmt(double d) {
		return String.format(Locale.ROOT, "%.1f", d);
	}
}
