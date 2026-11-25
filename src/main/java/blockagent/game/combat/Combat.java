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
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 依怪物種類應對(docs/SPEC.md S9.1b)。自衛反射和 hunt 共用：每 tick 呼叫 {@link #tick}，
 * 依 {@link Threats.Kind} 決定走近打、打了就退、跑開、舉盾、用弓射或躲起來。
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
	/** 血量低於這個、又沒有弓時，遠程怪改成躲起來。 */
	private static final float COVER_HEALTH = 8f;

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
	private boolean shieldChecked;
	/** 這個 tick 有在拉弓或放箭；沒有的話 tick 最後把弓收起來。 */
	private boolean shooting;

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
		closest = e.distanceTo(Minecraft.getInstance().player);
		noCloserTicks = 0;
		lostSightTicks = 0;
		cover = null;
		backoff = 0;
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

	/** 自衛反射什麼時候可以收手。 */
	public boolean finished(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (gone(mc)) return true;
		double d = target.distanceTo(p);
		Threats.Kind k = target instanceof Enemy || Threats.kind(target) == Threats.Kind.AVOID ? Threats.kind(target, Bow.ready(p)) : Threats.Kind.MELEE;
		if (k == Threats.Kind.AVOID) return d > 24;
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
		if (!shieldChecked) {
			shieldChecked = true;
			equipShield(mc);
		}
		blocked = null;
		double d = target.distanceTo(p);
		boolean los = p.hasLineOfSight(target);
		lostSightTicks = los ? 0 : lostSightTicks + 1;
		if (d < closest - 0.5) {
			closest = d;
			noCloserTicks = 0;
		} else {
			noCloserTicks++;
		}
		boolean hostile = target instanceof Enemy;
		boolean bow = hostile && Bow.ready(p);
		// 狩獵的動物、打了自己的狼和蜜蜂當近戰處理；鐵傀儡打不過，照分類避開
		Threats.Kind k = hostile || Threats.kind(target) == Threats.Kind.AVOID ? Threats.kind(target, bow) : Threats.Kind.MELEE;
		shooting = false;
		switch (k) {
			case EXPLODER -> creeper(mc, (Creeper) target, d, los, bow);
			case RANGED -> ranged(mc, d, los, bow);
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

	/** 結束時：放下盾牌或收弓、放開使用鍵、停下移動。斷線時(沒有玩家)只放開按鍵(H9)。 */
	public void release(Minecraft mc) {
		if (mc.player != null && mc.gameMode != null) {
			if (drawingBow(mc.player)) cancelDraw(mc);
			else lowerShield(mc);
		}
		if (mc.options.keyUse.isDown()) mc.options.keyUse.setDown(false);
		try {
			halt();
		} catch (RuntimeException ignored) {
			// 不在世界裡時 Baritone 可能還沒準備好；按鍵已經放開了
		}
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
		if (strike(mc)) {
			tactic("melee");
			return;
		}
		if (blocked != null) return;
		if (CombatPolicy.shouldWaitInReach(inReach(mc), los)) {
			// 等攻擊冷卻：舉盾(隔著牆看不到時不能停在這裡，要往下進 approach 繞過去，P3-H4)
			if (d < 4) raiseShield(mc);
			return;
		}
		if (d >= 4) lowerShield(mc);
		tactic("melee");
		approach(mc);
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
		if (strike(mc)) {
			tactic("hit_and_back");
			backoff = 12;
			return;
		}
		if (blocked != null || CombatPolicy.shouldWaitInReach(inReach(mc), los)) return;
		approach(mc);
	}

	private void ranged(Minecraft mc, double d, boolean los, boolean bow) {
		LocalPlayer p = mc.player;
		if (bow && los && (d > 8 || noCloserTicks > 60) && shoot(mc, "bow_duel")) return;
		if (!bow && !inReach(mc) && (p.getHealth() <= COVER_HEALTH || noCloserTicks > 60)) {
			takeCover(mc);
			return;
		}
		if (strike(mc)) {
			tactic("charge");
			return;
		}
		if (blocked != null) return;
		if (CombatPolicy.shouldWaitInReach(inReach(mc), los)) {
			if (d < 4) raiseShield(mc);
			return;
		}
		if (target.isUsingItem() && target.getTicksUsingItem() >= 8 && hasShield(mc)) {
			tactic("shield_vs_bow");
			Moves.lookAt(p, target.getEyePosition());
			raiseShield(mc);
		} else {
			lowerShield(mc);
			tactic("charge");
		}
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

	private void takeCover(Minecraft mc) {
		lowerShield(mc);
		if (cover == null || agent.tick() - coverTick > 40) {
			cover = findCover(mc);
			coverTick = agent.tick();
		}
		if (cover != null) {
			tactic("hide_from_" + id(target));
			goTo(new GoalBlock(cover), cover);
		} else {
			tactic("back_off");
			flee(target, 16);
		}
	}

	/** 8 格內站得住、從那裡看不到目標的位置，取最近的。 */
	private BlockPos findCover(Minecraft mc) {
		LocalPlayer p = mc.player;
		Level level = mc.level;
		BlockPos me = p.blockPosition();
		Vec3 eye = target.getEyePosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos b : BlockPos.betweenClosed(me.offset(-8, -2, -8), me.offset(8, 2, 8))) {
			double dist = b.distSqr(me);
			if (dist >= bestD) continue;
			if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()
				|| !level.getBlockState(b.above()).getCollisionShape(level, b.above()).isEmpty()
				|| level.getBlockState(b.below()).getCollisionShape(level, b.below()).isEmpty()
				|| !level.getFluidState(b).isEmpty()) {
				continue;
			}
			Vec3 from = Vec3.atBottomCenterOf(b).add(0, 1.62, 0);
			if (level.clip(new ClipContext(from, eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.BLOCK) {
				best = b.immutable();
				bestD = dist;
			}
		}
		return best;
	}

	// ---- 動作 ----

	/** 在攻擊距離內、冷卻滿了就打。打了回傳 true；被守門員擋下時記在 {@link #blocked}。 */
	private boolean strike(Minecraft mc) {
		LocalPlayer p = mc.player;
		// 隔著方塊打不到(原版玩家也打不到)：先走過去(M14)
		if (!inReach(mc) || !p.hasLineOfSight(target)) return false;
		halt();
		Moves.lookAt(p, target.getBoundingBox().getCenter());
		if (p.getAttackStrengthScale(0) < 0.9f) return false;
		// 目標旁邊有玩家、寵物或村民時不用劍：劍的橫掃會打到牠們(M14)，改用斧頭或其他東西
		if (RuleGuard.protectedNearTarget(p, target) != null) {
			if (!Inv.selectInHand(mc, s -> s.is(ItemTags.AXES))) Inv.selectInHand(mc, s -> !s.is(ItemTags.SWORDS));
		} else {
			Inv.selectInHand(mc, s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES));
		}
		RuleGuard.Verdict v = agent.guard.canAttack(target);
		if (!v.allowed()) {
			blocked = v;
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
		agent.log.log(source, logPrefix + "_attack", id(target) + " 距離 " + fmt(target.distanceTo(p)));
		mc.gameMode.attack(p, target);
		p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		hits++;
		return true;
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
		return true;
	}

	private boolean hasShield(Minecraft mc) {
		return mc.player.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS);
	}

	/** 副手是空的、背包有盾牌：換到副手。 */
	private void equipShield(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!p.getOffhandItem().isEmpty() || p.containerMenu != p.inventoryMenu) return;
		int idx = Inv.find(p, s -> s.has(DataComponents.BLOCKS_ATTACKS));
		if (idx < 0) return;
		Inv.swapToOffhand(mc, idx);
		agent.log.log(source, logPrefix + "_equip", "把 " + Inv.id(p.getOffhandItem()) + " 換到副手");
	}

	private void raiseShield(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!hasShield(mc)) return;
		// 正在拉弓時先收弓，不然舉不起盾(H8：苦力怕快爆了還拉著弓)
		if (drawingBow(p)) {
			cancelDraw(mc);
			shooting = false;
		}
		if (p.isUsingItem()) {
			if (p.getUsedItemHand() == InteractionHand.OFF_HAND) UseKey.holdOff(mc);
			return;
		}
		UseKey.startUsing(mc, InteractionHand.OFF_HAND);
	}

	private void lowerShield(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND) {
			mc.options.keyUse.setDown(false);
			mc.gameMode.releaseUsingItem(p);
		} else if (!p.isUsingItem() && mc.options.keyUse.isDown()) {
			mc.options.keyUse.setDown(false);
		}
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
