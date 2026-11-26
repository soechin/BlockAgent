package blockagent.game.combat;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import blockagent.core.Ballistics;
import blockagent.core.CombatPolicy;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import blockagent.game.UseKey;
import blockagent.game.tasks.Moves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
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

	public enum Outcome {CONTINUE, BLOCKED, GONE}

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
	private BlockPos cover;
	private long coverTick = -1000;
	private RuleGuard.Verdict blocked;
	private int hits;
	private long lastHoldLog = -1000;
	private boolean prepared;
	/** 這個 tick 有在拉弓或放箭；沒有的話 tick 最後把弓收起來。 */
	private boolean shooting;
	private final ShieldGuard guard = new ShieldGuard();
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
	// ---- 戰鬥紀錄(fight_summary) ----
	private long startTick = -1;
	private float hpStart;
	private int swings;
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
			if (!los && Threats.kind(e, bow) != Threats.Kind.AVOID) continue;
			Vec3 moved = e.position().subtract(e.xo, e.yo, e.zo);
			boolean approaching = moved.dot(p.position().subtract(e.position())) > 0 || e.getDeltaMovement().dot(p.position().subtract(e.position())) > 0;
			boolean angry = Threats.angry(e);
			String name = id(e);
			Threat t = switch (Threats.kind(e, bow)) {
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
				case MELEE -> (hurtMe && (d <= MELEE_RANGE || bow)) || (d <= 6 && angry) || (d <= 3.5 && approaching)
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
		target = e;
		targets.add(id(e));
		closest = e.distanceTo(Minecraft.getInstance().player);
		noCloserTicks = 0;
		lostSightTicks = 0;
		cover = null;
		backoff = 0;
	}

	/**
	 * 自己現在的戰力(R7)：最佳近戰武器、護甲、盾牌(副手或背包裡都算，開打時會換上)、弓箭、食物，
	 * 以及能不能跳劈。戰前評估(S9.1c)也用這個。
	 */
	public static CombatPolicy.Fighter assess(LocalPlayer p) {
		Inv.Weapon w = Inv.bestWeapon(p, s -> true);
		boolean shield = ShieldGuard.hasShield(p) || Inv.find(p, s -> s.has(DataComponents.BLOCKS_ATTACKS)) >= 0;
		return new CombatPolicy.Fighter(p.getHealth(), w.damage(), w.interval(), Inv.wornArmor(p), shield, Bow.ready(p),
			Inv.hasFood(p), !p.isInLiquid() && !p.onClimbable());
	}

	/** 打到或射出過至少一次。 */
	/** 打不贏，正在(或剛)拉開距離。 */
	public boolean outmatched() {
		return outmatched;
	}

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

	/** 自衛反射什麼時候可以收手。 */
	public boolean finished(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (gone(mc)) return true;
		double d = target.distanceTo(p);
		Threats.Kind k = target instanceof Enemy || Threats.kind(target) == Threats.Kind.AVOID ? Threats.kind(target, Bow.ready(p)) : Threats.Kind.MELEE;
		if (k == Threats.Kind.AVOID) return d > 24;
		// 脫身要甩掉整群：最近的威脅超過 18 格，或超過 12 格而且 3 秒都沒有一隻看得到自己。
		// 只看目標、躲到牆後就收手的話，牠們繞過來或追上來又觸發自衛(遊戲測試：被 2 隻殭屍追 30 秒觸發 8 次)
		if (outmatched) {
			double nearest = nearestThreat(mc, 24);
			return nearest > TRIGGER_RANGE + 2 || (nearest > 12 && unseenTicks > 60);
		}
		if (d > TRIGGER_RANGE + 2 || lostSightTicks > 60) return true;
		return switch (k) {
			case EXPLODER -> d > 10;
			case MELEE, NEUTRAL -> d > MELEE_RANGE && !(k == Threats.Kind.MELEE && Bow.ready(p) && lostSightTicks == 0);
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
		boolean bow = hostile && Bow.ready(p);
		// 狩獵的動物、打了自己的狼和蜜蜂當近戰處理；鐵傀儡打不過，照分類避開
		Threats.Kind k = hostile || Threats.kind(target) == Threats.Kind.AVOID ? Threats.kind(target, bow) : Threats.Kind.MELEE;
		shooting = false;
		// 盾牌守備優先(R1)：苦力怕在膨脹、要避開的、正在拉開距離時照樣跑；攻擊冷卻滿了而且箭還沒到時可以先砍
		// 脫身時 4 格內有近戰怪就先跑，沒有的話停下來擋箭(跟撤退一樣)
		boolean panic = k == Threats.Kind.AVOID || (outmatched && nearestMelee(mc) <= 4)
			|| (k == Threats.Kind.EXPLODER && ((Creeper) target).getSwellDir() > 0);
		if (!panic && guard.update(mc, agent.tick()) && !(canStrikeNow(mc, los) && !guard.arrowImminent())) {
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
			escape(mc);
			if (!shooting) cancelDraw(mc);
			return blocked != null ? Outcome.BLOCKED : Outcome.CONTINUE;
		}
		switch (k) {
			case EXPLODER -> creeper(mc, (Creeper) target, d, los, bow);
			case RANGED -> ranged(mc, d, los);
			case AVOID -> {
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
		if (!shooting) cancelDraw(mc);
		return blocked != null ? Outcome.BLOCKED : Outcome.CONTINUE;
	}

	/** 舉盾擋：停下 Baritone(走路會轉視角，R1)、對準射手或箭的來向、舉盾。 */
	private void holdGuard(Minecraft mc) {
		halt();
		releaseMoveKeys(mc);
		jumpedForCrit = false;
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
		if (mc.player != null && mc.gameMode != null) {
			if (drawingBow(mc.player)) cancelDraw(mc);
			else lowerShield(mc);
			summarize(mc, how);
		}
		releaseMoveKeys(mc);
		if (mc.options.keyUse.isDown()) mc.options.keyUse.setDown(false);
		try {
			halt();
		} catch (RuntimeException ignored) {
			// 不在世界裡時 Baritone 可能還沒準備好；按鍵已經放開了
		}
	}

	private void summarize(Minecraft mc, String how) {
		if (startTick < 0) return;
		LocalPlayer p = mc.player;
		String result;
		if (p.isDeadOrDying()) result = "died";
		else if (target != null && (target.isDeadOrDying() || target.getRemovalReason() == Entity.RemovalReason.KILLED)) {
			// 只有剛打中過牠才算自己打死的；不然是被別人、岩漿、陽光或指令弄死的
			result = target.getId() == lastHitId && agent.tick() - lastHitTick <= KILL_CREDIT_TICKS ? "killed" : "target_died";
		}
		else if (outmatched) result = "disengaged";
		else result = how;
		agent.log.log(source, "fight_summary", "targets=" + String.join(",", targets) + " secs=" + fmt((agent.tick() - startTick) / 20.0)
			+ " tactics=" + summary() + " swings=" + swings + " crits=" + crits + " hp=" + Math.round(hpStart) + "->" + Math.round(p.getHealth())
			+ " hits_taken=" + hitsTaken + " shield_blocks=" + shieldBlocks + " result=" + result);
		startTick = -1;
	}

	private static void releaseMoveKeys(Minecraft mc) {
		if (mc.options == null) return;
		mc.options.keyUp.setDown(false);
	}

	private static boolean drawingBow(LocalPlayer p) {
		return p.isUsingItem() && Bow.isBow(p.getUseItem());
	}

	/** 取消拉弓，不射出去。 */
	private void cancelDraw(Minecraft mc) {
		if (drawingBow(mc.player)) UseKey.cancelUse(mc);
	}

	// ---- 戰術 ----

	private void melee(Minecraft mc, double d, boolean los) {
		// 還沒走過來的近戰怪：有弓就先射幾箭
		if (d > 6 && target instanceof Enemy && Bow.ready(mc.player) && los && shoot(mc, "bow_first")) return;
		// 打不贏就拉開距離(R7)：hunt 是 AI 自己要打的，照打
		CombatPolicy.Fighter me = assess(mc.player);
		int melee = meleeNear(mc, true), exposed = rangedExposed(mc);
		if (!hunting && !CombatPolicy.worthFighting(me, target.getHealth(), d, melee, exposed, false)) {
			logOdds(me, CombatPolicy.expectedLoss(me, target.getHealth(), d, melee, exposed, false), melee, exposed);
			startEscape("disengage_outmatched");
			escape(mc);
			return;
		}
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

	/** 6 格內的近戰威脅數量；{@code includeTarget} 時目標不在 6 格內也算 1 隻。 */
	private int meleeNear(Minecraft mc, boolean includeTarget) {
		LocalPlayer p = mc.player;
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		int n = 0;
		boolean sawTarget = false;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(6), e -> Threats.isThreat(e, attacker))) {
			Threats.Kind k = Threats.kind(e);
			if (e.distanceTo(p) > 6 || k == Threats.Kind.RANGED || k == Threats.Kind.AVOID) continue;
			n++;
			if (e == target) sawTarget = true;
		}
		return includeTarget && !sawTarget ? n + 1 : n;
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

	private void creeper(Minecraft mc, Creeper c, double d, boolean los, boolean bow) {
		if (c.getSwellDir() > 0) {
			if (hasShield(mc) && d < 3.5 && c.getSwelling(1f) > 0.5f) {
				tactic("creeper_shield");
				halt();
				Moves.lookAt(mc.player, c.getBoundingBox().getCenter());
				raiseShield(mc);
			} else {
				lowerShield(mc);
				tactic("creeper_run");
				flee(c, 8);
			}
			return;
		}
		if (backoff > 0) {
			backoff--;
			flee(c, 5);
			return;
		}
		if (bow && d >= 5 && los && shoot(mc, "bow_creeper")) return;
		// 衝刺擊退：出手前一 tick 按住前進並開衝刺，這一下會把苦力怕打得更遠
		if (!sprintWindup && canStrikeNow(mc, los) && !mc.player.isSprinting() && mc.player.getFoodData().getFoodLevel() > 6
			&& mc.player.onGround()) {
			halt();
			lowerShield(mc);
			Moves.lookAt(mc.player, c.getBoundingBox().getCenter());
			mc.options.keyUp.setDown(true);
			mc.player.setSprinting(true);
			sprintWindup = true;
			return;
		}
		if (strike(mc)) {
			sprintWindup = false;
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
		CombatPolicy.Fighter me = assess(mc.player);
		int melee = meleeNear(mc, false), exposed = rangedExposed(mc);
		CombatPolicy.RangedStance st = CombatPolicy.rangedStance(me, d, los, noCloserTicks > STALL_TICKS, target.getHealth(), melee, exposed);
		// hunt 是 AI 自己要打的：不躲，照衝
		if (hunting && st == CombatPolicy.RangedStance.COVER) {
			st = me.shield() ? CombatPolicy.RangedStance.GUARD_ADVANCE : CombatPolicy.RangedStance.CHARGE;
		}
		if (st == CombatPolicy.RangedStance.SHOOT && shoot(mc, "bow_duel")) return;
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
		if (bow && los && d > 4 && shoot(mc, "bow")) return;
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
	private void logOdds(CombatPolicy.Fighter me, double loss, int melee, int exposed) {
		if (outmatched) return;
		agent.log.log(source, logPrefix + "_odds", id(target) + " expected_loss=" + fmt(loss) + " hp=" + fmt(me.hp()) + " melee=" + melee
			+ " ranged_exposed=" + exposed + " armor=" + me.armor() + " weapon=" + fmt(me.weaponDamage()) + " shield=" + me.shield());
	}

	/**
	 * 埋伏：打得贏但追不近(骷髏邊退邊射)。躲到擋住射手的位置等牠繞過來，走進攻擊距離就砍；
	 * 找不到掩體就重新算「走不近」，再往前推。不是脫身，不會一直跑。
	 */
	private void ambush(Minecraft mc) {
		if (strike(mc)) {
			tactic("hit_from_cover");
			return;
		}
		if (blocked != null) return;
		List<LivingEntity> seeing = archersSeeingMe(mc);
		if (!seeing.isEmpty()) {
			if (cover == null || agent.tick() - coverTick > 40 || !hiddenAt(mc, cover, seeing)) {
				cover = findCover(mc, seeing);
				coverTick = agent.tick();
			}
			if (cover == null) {
				// 附近沒有掩體可以埋伏：重新往前推
				noCloserTicks = 0;
				closest = target.distanceTo(mc.player);
				return;
			}
			if (!mc.player.blockPosition().equals(cover)) {
				lowerShield(mc);
				tactic("ambush_from_cover");
				goTo(new GoalBlock(cover), cover);
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
		escapeTactic = tactic;
		unseenTicks = 0;
		cover = null;
	}

	/**
	 * 脫身(R7、S9.1b)：射手看得到自己時，先躲到擋住所有射手、而且離射手比現在更遠的位置(不會往回躲)；
	 * 沒有射手看得到(或找不到掩體)就往遠離整群怪的方向跑。遠程目標繞過來走進攻擊距離時照樣砍。
	 */
	private void escape(Minecraft mc) {
		LocalPlayer p = mc.player;
		List<LivingEntity> seeing = archersSeeingMe(mc);
		unseenTicks = seeing.isEmpty() && !anyThreatSees(mc) ? unseenTicks + 1 : 0;
		if (Threats.kind(target) == Threats.Kind.RANGED) {
			if (strike(mc)) {
				tactic("hit_from_cover");
				return;
			}
			if (blocked != null) return;
		}
		lowerShield(mc);
		if (!seeing.isEmpty()) {
			if (cover == null || agent.tick() - coverTick > 40 || !hiddenAt(mc, cover, seeing)) {
				cover = findCover(mc, seeing);
				coverTick = agent.tick();
			}
			if (cover != null && !p.blockPosition().equals(cover)) {
				tactic("hide_from_" + id(seeing.getFirst()));
				goTo(new GoalBlock(cover), cover);
				return;
			}
		}
		tactic(escapeTactic);
		fleeAll(mc, TRIGGER_RANGE + 6);
	}

	/** 往遠離 24 格內所有威脅的方向跑。 */
	private void fleeAll(Minecraft mc, double distance) {
		List<BlockPos> from = new ArrayList<>();
		for (Entity e : threats(mc, 24)) from.add(e.blockPosition());
		if (from.isEmpty()) from.add(target.blockPosition());
		BlockPos key = from.getFirst();
		goTo(new GoalRunAway(distance, from.toArray(new BlockPos[0])), key);
	}

	/** range 格內的威脅(包含目標)。 */
	private List<Entity> threats(Minecraft mc, double range) {
		LocalPlayer p = mc.player;
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		List<Entity> out = new ArrayList<>();
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(range), e -> e == target || Threats.isThreat(e, attacker))) {
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
		for (Entity e : threats(mc, 8)) {
			if (Threats.kind(e) != Threats.Kind.RANGED) best = Math.min(best, e.distanceTo(mc.player));
		}
		return best;
	}

	private boolean anyThreatSees(Minecraft mc) {
		for (Entity e : threats(mc, TRIGGER_RANGE)) if (mc.player.hasLineOfSight(e)) return true;
		return false;
	}

	/** 16 格內看得到自己的遠程怪。 */
	private List<LivingEntity> archersSeeingMe(Minecraft mc) {
		List<LivingEntity> out = new ArrayList<>();
		for (Entity e : threats(mc, TRIGGER_RANGE)) {
			if (e instanceof LivingEntity le && Threats.kind(e) == Threats.Kind.RANGED && mc.player.hasLineOfSight(e)) out.add(le);
		}
		return out;
	}

	/**
	 * 射得到自己、盾牌又擋不到的遠程怪數量：沒盾時是全部；有盾時只算不在正面(跟目標方向差 90 度以上)的。
	 * 盾牌只擋正面，射手分散在兩邊時擋了一邊、另一邊照樣射得到。
	 */
	private int rangedExposed(Minecraft mc) {
		LocalPlayer p = mc.player;
		boolean shield = assess(p).shield();
		Vec3 facing = target.position().subtract(p.position()).multiply(1, 0, 1);
		int n = 0;
		for (LivingEntity a : archersSeeingMe(mc)) {
			if (!shield) {
				n++;
				continue;
			}
			Vec3 dir = a.position().subtract(p.position()).multiply(1, 0, 1);
			if (facing.lengthSqr() < 1e-4 || dir.lengthSqr() < 1e-4) continue;
			if (dir.normalize().dot(facing.normalize()) < CombatPolicy.SHIELD_FRONT_COS) n++;
		}
		return n;
	}

	/** 從這一格看出去，這些射手都看不到。 */
	private static boolean hiddenAt(Minecraft mc, BlockPos b, List<LivingEntity> archers) {
		Vec3 from = Vec3.atBottomCenterOf(b).add(0, 1.62, 0);
		for (LivingEntity a : archers) {
			if (mc.level.clip(new ClipContext(from, a.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player))
				.getType() != HitResult.Type.BLOCK) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 8 格內站得住、所有射手都看不到，而且離最近的射手比現在更遠的位置(往外躲，不會往回走)，取最近的。
	 */
	private BlockPos findCover(Minecraft mc, List<LivingEntity> archers) {
		LocalPlayer p = mc.player;
		Level level = mc.level;
		BlockPos me = p.blockPosition();
		double myGap = nearestArcher(Vec3.atBottomCenterOf(me), archers);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos b : BlockPos.betweenClosed(me.offset(-8, -2, -8), me.offset(8, 2, 8))) {
			double dist = b.distSqr(me);
			if (dist >= bestD) continue;
			if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()
				|| !level.getBlockState(b.above()).getCollisionShape(level, b.above()).isEmpty()
				|| level.getBlockState(b.below()).getCollisionShape(level, b.below()).isEmpty()
				|| !level.getFluidState(b).isEmpty() || lavaAround(level, b)) {
				continue;
			}
			if (nearestArcher(Vec3.atBottomCenterOf(b), archers) < myGap + 1) continue;
			if (hiddenAt(mc, b, archers)) {
				best = b.immutable();
				bestD = dist;
			}
		}
		return best;
	}

	private static double nearestArcher(Vec3 at, List<LivingEntity> archers) {
		double best = Double.MAX_VALUE;
		for (LivingEntity a : archers) best = Math.min(best, a.position().distanceTo(at));
		return best;
	}

	private static boolean lavaAround(Level level, BlockPos b) {
		for (BlockPos c : BlockPos.betweenClosed(b.offset(-1, -1, -1), b.offset(1, 0, 1))) {
			if (level.getBlockState(c).is(Blocks.LAVA)) return true;
		}
		return false;
	}

	// ---- 動作 ----

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
		Inv.Weapon weapon;
		if (RuleGuard.protectedNearTarget(p, target) != null) {
			if (!Inv.selectBestWeapon(mc, s -> s.is(ItemTags.AXES))) Inv.selectInHand(mc, s -> !s.is(ItemTags.SWORDS));
			weapon = Inv.bestWeapon(p, s -> s.is(ItemTags.AXES));
		} else {
			Inv.selectBestWeapon(mc, s -> true);
			weapon = Inv.bestWeapon(p, s -> true);
		}
		float cd = p.getAttackStrengthScale(0);
		boolean crit = false;
		if (jumpedForCrit && !p.onGround()) {
			if (!CombatPolicy.critReady(false, p.getDeltaMovement().y, cd)) return false;
			crit = true;
		} else {
			jumpedForCrit = false;
			// 苦力怕要的是擊退，不跳
			if (!(target instanceof Creeper) && CombatPolicy.shouldJumpForCrit(p.onGround(), p.isInLiquid(), p.onClimbable(),
				headroom(mc), weapon.interval(), cd, false)) {
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

	/** 拉弓、瞄準，蓄力滿就放箭。沒有弓或打不到時回傳 false，讓呼叫的人改用別的戰術。 */
	private boolean shoot(Minecraft mc, String label) {
		LocalPlayer p = mc.player;
		boolean drawing = drawingBow(p);
		if (!drawing) {
			// 先確認彈道沒被屋頂/牆擋住且身上有弓，再放盾拉弓，免得在屋頂下每 tick 收盾又舉盾(P3-H5)
			if (!CombatPolicy.canPrepareShot(Bow.hasClearShot(p, target), Inv.find(p, Bow::isBow) >= 0)) return false;
			if (!Bow.aim(p, target)) return false;
			if (p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND) lowerShield(mc);
			if (!Inv.selectInHand(mc, Bow::isBow)) return false;
			halt();
			UseKey.startUsing(mc, InteractionHand.MAIN_HAND);
			tactic(label);
			shooting = true;
			return true;
		}
		halt();
		tactic(label);
		// 瞄不到了(太遠)或箭會先撞到屋頂、牆壁：交給別的戰術，tick 最後會收弓(H8、M14)
		if (!Bow.aim(p, target) || !Bow.hasClearShot(p, target)) return false;
		shooting = true;
		if (p.getTicksUsingItem() < Bow.FULL_DRAW) return true;
		Entity friend = RuleGuard.protectedOnProjectilePath(p, Ballistics.FULL_BOW_SPEED);
		if (friend != null) {
			tactic("hold_fire_protected_in_line");
			return true;
		}
		agent.log.log(source, logPrefix + "_shoot", id(target) + " 距離 " + fmt(target.distanceTo(p)));
		mc.options.keyUse.setDown(false);
		mc.gameMode.releaseUsingItem(p);
		hits++;
		lastHitId = target.getId();
		lastHitTick = agent.tick();
		return true;
	}

	private boolean hasShield(Minecraft mc) {
		return ShieldGuard.hasShield(mc.player);
	}

	private void raiseShield(Minecraft mc) {
		if (drawingBow(mc.player)) shooting = false;
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
