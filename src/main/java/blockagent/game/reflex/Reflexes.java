package blockagent.game.reflex;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalRunAway;
import blockagent.core.CombatPolicy;
import blockagent.core.Event;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.game.UseKey;
import blockagent.game.combat.Combat;
import blockagent.game.combat.CoverRoute;
import blockagent.game.combat.ShieldGuard;
import blockagent.game.combat.Threats;
import blockagent.game.tasks.BreakTask;
import blockagent.game.tasks.GotoTask;
import blockagent.game.tasks.Moves;
import blockagent.game.tasks.ShoreExit;
import blockagent.game.tasks.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 反射(docs/SPEC.md S9)：只看自己的狀態和環境，不看其他玩家(玩家只出現在「不要攻擊」的判斷)。
 * 所有動作都經過守門員(MultiPlayerGameMode 的 Mixin)；暫停時完全不執行(S9.3a)。
 */
public final class Reflexes {
	public static final float LOW_HEALTH = CombatPolicy.RETREAT_HP;

	/**
	 * 依優先順序排列(S9.2)：保命 > 撤退 > 自衛 > 進食 > 脫困。岩漿排在著火前面：碰到岩漿一定也著火，
	 * 著火反射只會原地找水，留在岩漿裡會燒死(docs/REVIEW.md C4)。
	 */
	public enum Kind {
		DROWNING("survival", true), LAVA_AVOID("survival", true), ON_FIRE("survival", true), FALL("survival", false),
		// 撤退暫停任務，回血後接著做；同一個任務撤退第二次或回不了血才取消(R3、CombatPolicy.retreatOutcome)
		RETREAT("retreat", false, 60), SELF_DEFENSE("defense", false), EAT("eat", false), UNSTUCK("unstuck", false);

		final String type;
		/** true：取消目前任務；false：暫停任務，結束後接著做。 */
		final boolean cancelsTask;
		/** 最多執行幾秒就放棄。 */
		final int limitSecs;

		Kind(String type, boolean cancelsTask) {
			this(type, cancelsTask, 30);
		}

		Kind(String type, boolean cancelsTask, int limitSecs) {
			this.type = type;
			this.cancelsTask = cancelsTask;
			this.limitSecs = limitSecs;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Kind byId(String id) {
			for (Kind k : values()) if (k.id().equals(id)) return k;
			return null;
		}

		boolean urgent() {
			return type.equals("survival") || type.equals("retreat");
		}
	}

	private static final Set<net.minecraft.world.item.Item> BAD_FOOD =
		Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW);

	private final Agent agent;
	private final Map<Kind, Boolean> enabled = new EnumMap<>(Kind.class);
	private volatile Kind active;
	private String activeReason;
	private int activeTicks;
	private boolean suspendedTask;
	private String interruptedTask;
	/** 自衛反射正在處理的怪物和戰術(S9.1b)。 */
	private Combat combat;
	private Combat.Threat pendingThreat;
	/** 目前目標被挑中時的優先度(越小越危險)，換目標時比較用。 */
	private int engagedPriority;
	private boolean bucketPlaced;
	/** fall：放水前身上的水桶數；落地的 tick(還沒落地是 -1)。 */
	private int bucketsBefore;
	private int landedAt;
	/** MCP 執行緒會在 WAIT task_running 讀取目前任務累計的脫困次數(volatile)。 */
	private volatile int unstuckAttempts;
	private int unstuckProgressCount;
	/** unstuck：開始挖時鎖定的起點與目標格，避免中途被擠偏方向，且挖通後遠離起點 >= 3 格(大於 movedSinceUnstuck 的 2 格門檻)。 */
	private BlockPos unstuckStart;
	private BlockPos unstuckFront;
	private int unstuckRunAwayAt = -1;
	private Vec3 unstuckOriginPos;
	private final ShoreExit unstuckShore = new ShoreExit();
	/** eat：實際在吃的食物和開始時的數量(C5)。 */
	private net.minecraft.world.item.Item eatingFood;
	private int eatStartCount;
	/** retreat：上次重新規劃逃跑路線的 tick。 */
	private int retreatPlannedAt;
	/** retreat 分兩段：RUN 跑開；RECOVER 沒有怪看得到自己時停下吃東西回血(R3)。 */
	private enum RetreatPhase {RUN, RECOVER}

	private RetreatPhase retreatPhase;
	private final CoverRoute retreatCover = new CoverRoute();
	private int recoverSince;
	/** 這個任務撤退了幾次(第二次回血後就取消任務)。 */
	private Task retreatTask;
	private int retreatCount;
	/** 回不了血之後，這段時間內不再觸發撤退，除非期間又被打。 */
	private long retreatQuietFrom = Long.MIN_VALUE;
	private long retreatQuietUntil = Long.MIN_VALUE;
	private static final int RETREAT_QUIET_TICKS = 20 * 20;
	/** RECOVER 這麼久還回不了血(飢餓度不夠自然回血又沒東西吃)就放棄。 */
	private static final int CANNOT_HEAL_TICKS = 10 * 20;
	private final ShieldGuard retreatGuard = new ShieldGuard();
	/** 已經為哪些怪物自動備戰過(穿甲、換盾)，同一隻只做一次。 */
	private final java.util.Set<Integer> preparedFor = new HashSet<>();
	/** 自衛反射收手後，同一隻怪物這段時間內不再觸發(除非冷卻開始後牠又打了自己)，免得隔牆反覆打斷任務(H8、P3-M2)。 */
	private static final int DEFENSE_COOLDOWN_TICKS = 200;
	private final CombatPolicy.DefenseCooldown defenseCooldown = new CombatPolicy.DefenseCooldown();
	private final Deque<Vec3> history = new ArrayDeque<>();
	/** 位置軌跡只算目前這個任務開始之後的(脫困判斷用)。 */
	private Task historyTask;

	public Reflexes(Agent agent) {
		this.agent = agent;
		resetEnabled();
	}

	public void resetEnabled() {
		for (Kind k : Kind.values()) enabled.put(k, true);
	}

	public boolean isLocked(Kind k) {
		return agent.config.lockedReflexes.contains(k.id());
	}

	/** 回傳 null 表示成功，否則是錯誤原因(S9.4)。要在 client thread 呼叫(會停下正在執行的反射，C3)。 */
	public synchronized String set(Kind k, boolean on) {
		if (!on && isLocked(k)) return "locked on by the user; cannot be turned off";
		enabled.put(k, on);
		if (!on && active == k) end(Minecraft.getInstance(), "turned_off");
		return null;
	}

	/** 任務被換掉或取消時，舊任務的脫困反射也結束(不再恢復任何任務)。 */
	public synchronized void dropUnstuck(Minecraft mc, String how) {
		if (active != Kind.UNSTUCK) return;
		suspendedTask = false;
		interruptedTask = null;
		end(mc, how);
	}

	public synchronized Map<Kind, Boolean> states() {
		return new EnumMap<>(enabled);
	}

	public synchronized String summary() {
		List<String> off = new ArrayList<>();
		for (var e : enabled.entrySet()) if (!e.getValue()) off.add(e.getKey().id());
		String s = off.isEmpty() ? "all_on" : "off=" + String.join(",", off);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && invulnerable(p)) s += " note=creative_only_unstuck_triggers";
		return active == null ? s : s + " active=" + active.id();
	}

	public Kind active() {
		return active;
	}

	/** 目前任務無進展期間已觸發的脫困次數(MCP 執行緒可讀)。 */
	public int unstuckAttempts() {
		return unstuckAttempts;
	}

	// ---- tick ----

	public synchronized void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		Task cur = agent.tasks.current();
		if (cur != historyTask) {
			history.clear();
			historyTask = cur;
			// 脫困累計次數只算同一個任務，且在任務實際推進後歸零(H12)
			if (active != Kind.UNSTUCK) {
				unstuckAttempts = 0;
				unstuckProgressCount = cur == null ? 0 : cur.progressCount();
			}
		} else if (cur != null && active != Kind.UNSTUCK && cur.progressCount() != unstuckProgressCount) {
			unstuckAttempts = 0;
			unstuckProgressCount = cur.progressCount();
		}
		history.addLast(p.position());
		while (history.size() > 200) history.removeFirst();
		if (agent.tick() % 20 == 0 && !invulnerable(p)) prepareForThreats(mc);
		// 尋路繞怪的範圍：在主執行緒更新快照，Baritone 背景執行緒只讀快照(S9.1b)
		if (agent.tick() % 10 == 0) agent.danger.refresh(mc, agent.observation.recentAttacker(mc, 100), agent.tick());

		Kind trig = null;
		String why = null;
		for (Kind k : Kind.values()) {
			if (!enabled.get(k)) continue;
			why = trigger(mc, k);
			if (why != null) {
				trig = k;
				break;
			}
		}
		if (active != null) {
			activeTicks++;
			String how;
			if (active != Kind.UNSTUCK && invulnerable(p)) {
				end(mc, "not_needed_in_creative");
			} else if (trig != null && trig.ordinal() < active.ordinal()) {
				end(mc, "preempted_by_" + trig.id());
				begin(mc, trig, why);
			} else if ((how = finished(mc)) != null) {
				end(mc, how);
			} else if (activeTicks > 20 * active.limitSecs) {
				end(mc, "gave_up_after_" + active.limitSecs + "s");
			} else {
				act(mc);
			}
			return;
		}
		if (trig != null) begin(mc, trig, why);
	}

	/**
	 * 怪物來了自動備戰(S9.1b)：16 格內出現新的威脅時，穿上背包裡的護甲、把盾牌換到副手。同一隻只做一次。
	 */
	private void prepareForThreats(Minecraft mc) {
		List<Entity> near = hostiles(mc, 16);
		preparedFor.retainAll(near.stream().map(Entity::getId).toList());
		boolean fresh = false;
		for (Entity e : near) fresh |= preparedFor.add(e.getId());
		if (!fresh) return;
		String done = Inv.prepareForCombat(mc);
		if (!done.isEmpty()) agent.log.log(ActionLog.Source.reflex, "combat_prep", done);
	}

	/** 威脅：敵對生物，加上生氣了或剛打過自己的中立生物(S9.1b)。 */
	private List<Entity> hostiles(Minecraft mc, double r) {
		LocalPlayer p = mc.player;
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		return mc.level.getEntities(p, p.getBoundingBox().inflate(r), e -> Threats.isThreat(e, p, attacker));
	}

	private List<BlockPos> lavaNear(Minecraft mc, int r) {
		List<BlockPos> out = new ArrayList<>();
		BlockPos f = mc.player.blockPosition();
		for (BlockPos b : BlockPos.betweenClosed(f.offset(-r, -1, -r), f.offset(r, 1, r))) {
			if (mc.level.getFluidState(b).is(FluidTags.LAVA)) out.add(b.immutable());
		}
		return out;
	}

	/** 創造(或旁觀)模式不會受傷也不會餓：只留脫困，其他反射都不觸發(S9.1a)。 */
	static boolean invulnerable(LocalPlayer p) {
		return p.getAbilities().invulnerable;
	}

	private static boolean waterWorks(Minecraft mc) {
		return !mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, mc.player.blockPosition());
	}

	/** 觸發條件；回傳 null 表示沒有觸發，否則是原因。 */
	private String trigger(Minecraft mc, Kind k) {
		LocalPlayer p = mc.player;
		if (k != Kind.UNSTUCK && invulnerable(p)) return null;
		switch (k) {
			case DROWNING -> {
				if (p.isEyeInFluid(FluidTags.WATER) && p.getAirSupply() < p.getMaxAirSupply() * 0.4) {
					return "air=" + (100 * p.getAirSupply() / p.getMaxAirSupply()) + "%";
				}
			}
			case ON_FIRE -> {
				if (p.isOnFire() && !p.isInWater() && !p.isInLava() && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) return "on_fire";
			}
			case LAVA_AVOID -> {
				if (p.isInLava()) return "in_lava";
				if (!lavaNear(mc, 1).isEmpty()) return "lava_adjacent";
			}
			case FALL -> {
				if (waterWorks(mc) && !p.onGround() && p.fallDistance > 4 && p.getDeltaMovement().y < -0.5 && Inv.count(p, Items.WATER_BUCKET) > 0) {
					return "falling=" + Math.round(p.fallDistance);
				}
			}
			case RETREAT -> {
				if (p.getHealth() > LOW_HEALTH || (hostiles(mc, 12).isEmpty() && visibleRangedThreats(mc, Combat.TRIGGER_RANGE) == 0)) return null;
				// 正在吃東西、3 格內又沒有怪：讓這一口吃完(R3：吃西瓜吃到一半被撤退打斷)
				if (p.isUsingItem() && p.getUseItem().has(DataComponents.FOOD) && hostiles(mc, 3).isEmpty()) return null;
				// 剛因為回不了血放棄撤退：這段時間內不再觸發，除非之後又被打
				long now = agent.tick();
				if (now < retreatQuietUntil && agent.observation.lastAttackedTick() <= retreatQuietFrom) return null;
				return "hp=" + Math.round(p.getHealth()) + " hostiles_near";
			}
			case SELF_DEFENSE -> {
				Combat.Threat t = nextThreat(mc);
				if (t != null) {
					pendingThreat = t;
					return t.why();
				}
			}
			case EAT -> {
				int food = p.getFoodData().getFoodLevel();
				if (food <= 14 && hostiles(mc, 8).isEmpty() && bestFood(p, food) != null && !p.isUsingItem()) return "food=" + food;
			}
			case UNSTUCK -> {
				Task t = agent.tasks.current();
				// 站著不動但任務有進展(例如站在原地一直放方塊)不算卡住；正在破塊時門檻放寬至 20 秒避免打斷水中慢速挖掘
				if (t != null && t.moves() && !p.isPassenger() && !history.isEmpty()) {
					boolean destroying = mc.gameMode != null && mc.gameMode.isDestroying();
					return CombatPolicy.unstuckTriggerReason(t.ticksSinceProgress(), history.size(),
						history.peekFirst().distanceTo(p.position()), destroying);
				}
			}
		}
		return null;
	}

	private ItemStack bestFood(LocalPlayer p, int food) {
		ItemStack best = null;
		int bestN = -1;
		for (int i = 0; i < Inv.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			FoodProperties fp = s.get(DataComponents.FOOD);
			if (fp == null) continue;
			if (BAD_FOOD.contains(s.getItem()) && food > 6) continue;
			if (fp.nutrition() > bestN) {
				bestN = fp.nutrition();
				best = s;
			}
		}
		return best;
	}

	/** 剛收手、還在冷卻的怪物；只有在冷卻開始之後又打了自己的才提早解除(H8、P3-M2)。 */
	private java.util.function.Predicate<Entity> coolingDown() {
		long now = agent.tick();
		defenseCooldown.prune(now);
		if (defenseCooldown.isEmpty()) return e -> false;
		int attackerId = agent.observation.lastAttackerId();
		long attackedAt = agent.observation.lastAttackedTick();
		return e -> defenseCooldown.isCoolingDown(e.getId(), now, attackerId, attackedAt);
	}

	private Combat.Threat nextThreat(Minecraft mc) {
		var ignore = coolingDown();
		if (agent.tasks.current() instanceof blockagent.game.tasks.HuntTask hunt) {
			ignore = ignore.or(e -> e == hunt.target());
		}
		return Combat.pickThreat(mc, agent, ignore);
	}

	private void retarget(Combat.Threat threat) {
		combat.engage(threat.entity());
		engagedPriority = threat.priority();
		agent.log.log(ActionLog.Source.reflex, "self_defense_retarget", threat.why());
	}

	private void begin(Minecraft mc, Kind k, String why) {
		active = k;
		activeReason = why;
		activeTicks = 0;
		bucketPlaced = false;
		landedAt = -1;
		unstuckShore.stop(mc);
		unstuckStart = null;
		unstuckFront = null;
		unstuckRunAwayAt = -1;
		unstuckOriginPos = mc.player == null ? null : mc.player.position();
		bucketsBefore = Inv.count(mc.player, Items.WATER_BUCKET);
		retreatPlannedAt = 0;
		retreatPhase = RetreatPhase.RUN;
		retreatGuard.reset();
		interruptedTask = null;
		suspendedTask = false;
		Task t = agent.tasks.current();
		if (k == Kind.RETREAT) {
			if (t != null && t == retreatTask) {
				retreatCount++;
			} else {
				retreatTask = t;
				retreatCount = t == null ? 0 : 1;
			}
		}
		if (t != null && k != Kind.UNSTUCK) {
			interruptedTask = t.name() + " " + t.progress();
			if (k.cancelsTask) agent.tasks.interrupt(k.id());
			else suspendedTask = agent.tasks.suspend();
		} else if (k == Kind.UNSTUCK && t != null) {
			suspendedTask = agent.tasks.suspend();
		}
		agent.log.log(ActionLog.Source.reflex, k.id() + "_start", why);
		switch (k) {
			case DROWNING -> {
				BlockPos air = findAirSpot(mc);
				if (air != null) path(new GoalBlock(air));
			}
			case ON_FIRE -> {
				List<BlockPos> water = Moves.scan(mc, List.of(Blocks.WATER), 8, 1, null);
				if (!water.isEmpty()) path(new GoalBlock(water.getFirst()));
				else path(new GoalRunAway(5, mc.player.blockPosition()));
			}
			case LAVA_AVOID -> {
				List<BlockPos> lava = lavaNear(mc, 2);
				path(new GoalRunAway(4, lava.isEmpty() ? new BlockPos[]{mc.player.blockPosition()} : lava.toArray(new BlockPos[0])));
			}
			case RETREAT -> {
				eatingFood = null;
				retreatCover.clear();
				planRetreat(mc);
			}
			case EAT -> {
				ItemStack food = bestFood(mc.player, mc.player.getFoodData().getFoodLevel());
				eatingFood = food == null ? null : food.getItem();
				eatStartCount = food == null ? 0 : Inv.count(mc.player, food.getItem());
				if (food != null && Inv.selectInHand(mc, s -> s.getItem() == eatingFood)) {
					UseKey.startEating(mc);
				}
			}
			case SELF_DEFENSE -> {
				combat = new Combat(agent, ActionLog.Source.reflex, "self_defense", new Combat.Mover() {
					@Override
					public void path(Goal goal) {
						Reflexes.this.path(goal);
					}

					@Override
					public void stop() {
						Bari.stopAll();
					}
				}, false);
				if (pendingThreat != null) {
					combat.engage(pendingThreat.entity());
					engagedPriority = pendingThreat.priority();
				}
			}
			case UNSTUCK -> {
				if (t != null) unstuckProgressCount = t.progressCount();
				unstuckAttempts++;
			}
			default -> {
			}
		}
	}

	private void planRetreat(Minecraft mc) {
		LocalPlayer player = mc.player;
		List<Entity> threats = hostiles(mc, 16);
		if (threats.isEmpty()) return;
		List<Entity> shooters = new ArrayList<>();
		List<BlockPos> from = new ArrayList<>();
		Entity attacker = agent.observation.recentAttacker(mc, 100);
		boolean meleeClose = false;
		for (Entity threat : threats) {
			from.add(threat.blockPosition());
			double distance = player.distanceTo(threat);
			if (Threats.kind(threat, false, player, attacker) == Threats.Kind.RANGED) {
				if (distance <= 16 && player.hasLineOfSight(threat)) shooters.add(threat);
			} else if (distance <= 4) {
				meleeClose = true;
			}
		}
		retreatPlannedAt = activeTicks;
		BlockPos cover = meleeClose ? null : retreatCover.choose(mc, shooters, agent.tick());
		path(cover != null && !player.blockPosition().equals(cover)
			? new GoalBlock(cover) : new GoalRunAway(20, from.toArray(new BlockPos[0])));
	}

	private void path(Goal g) {
		Bari.b().getCustomGoalProcess().setGoalAndPath(g);
	}

	private BlockPos findAirSpot(Minecraft mc) {
		BlockPos f = mc.player.blockPosition();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (BlockPos b : BlockPos.betweenClosed(f.offset(-6, -2, -6), f.offset(6, 8, 6))) {
			BlockState s = mc.level.getBlockState(b), head = mc.level.getBlockState(b.above());
			if (s.isAir() && head.isAir() && mc.level.getFluidState(b).isEmpty()) {
				double d = b.distSqr(f);
				if (d < bd) {
					bd = d;
					best = b.immutable();
				}
			}
		}
		return best;
	}

	/** 結束了沒；結束時回傳原因(一般是 done，撤退是 recovered 或 cannot_heal)，還沒結束回傳 null。 */
	private String finished(Minecraft mc) {
		if (active == Kind.RETREAT) return retreatFinished(mc);
		return done(mc) ? "done" : null;
	}

	private String retreatFinished(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p.getHealth() > CombatPolicy.RECOVERED_HP) return "recovered";
		// RECOVER 10 秒還回不了血：飢餓度不夠自然回血(< 18)，又沒東西吃
		if (retreatPhase == RetreatPhase.RECOVER && activeTicks - recoverSince > CANNOT_HEAL_TICKS && !p.isUsingItem()
			&& p.getFoodData().getFoodLevel() < 18 && bestFood(p, p.getFoodData().getFoodLevel()) == null) {
			return "cannot_heal";
		}
		return null;
	}

	private boolean done(Minecraft mc) {
		LocalPlayer p = mc.player;
		return switch (active) {
			// 頭一露出水面就結束的話，浮浮沉沉會一直重新觸發：等氧氣回滿(M14)
			case DROWNING -> p.getAirSupply() >= p.getMaxAirSupply();
			case ON_FIRE -> !p.isOnFire();
			case LAVA_AVOID -> !p.isInLava() && lavaNear(mc, 3).isEmpty();
			// 放了水就要舀回來(身上本來有幾桶水就回到幾桶)；落地 2 秒還舀不回來就算了(M14)
			case FALL -> p.onGround() && (!bucketPlaced || Inv.count(p, Items.WATER_BUCKET) >= bucketsBefore
				|| (landedAt >= 0 && activeTicks - landedAt > 40));
			case RETREAT -> false;
			case SELF_DEFENSE -> {
				if (combat == null) yield true;
				if (!combat.finished(mc)) yield false;
				if (combat.targetDied()) {
					Combat.Threat next = nextThreat(mc);
					if (next != null) {
						retarget(next);
						yield false;
					}
				}
				yield true;
			}
			case EAT -> {
				// 只看實際在吃的那種食物(C5：看「最好的食物」會換成別種，數量一比就誤判吃完了)
				boolean ate = eatingFood == null || Inv.count(p, eatingFood) < eatStartCount;
				yield ate || (!p.isUsingItem() && activeTicks > 5) || activeTicks > 60;
			}
			case UNSTUCK -> !unstuckShore.active() && activeTicks > 20 && movedSinceUnstuck();
		};
	}

	private boolean movedSinceUnstuck() {
		if (unstuckOriginPos == null || history.isEmpty()) return false;
		return history.peekLast().distanceTo(unstuckOriginPos) > 2;
	}

	private void act(Minecraft mc) {
		LocalPlayer p = mc.player;
		switch (active) {
			case DROWNING -> {
				if (!Bari.busy()) mc.options.keyJump.setDown(true);
			}
			case RETREAT -> retreat(mc);
			case EAT -> UseKey.holdOff(mc);
			case FALL -> {
				if (p.onGround() && landedAt < 0) landedAt = activeTicks;
				if (!bucketPlaced) {
					if (p.onGround() || !waterWorks(mc)) return;
					BlockPos ground = null;
					for (int i = 1; i <= 4; i++) {
						BlockPos b = p.blockPosition().below(i);
						if (!mc.level.getBlockState(b).isAir()) {
							ground = b;
							break;
						}
					}
					if (ground != null && Inv.selectInHand(mc, s -> s.is(Items.WATER_BUCKET))) {
						Moves.lookAt(p, Vec3.atCenterOf(ground).add(0, 0.5, 0));
						p.setXRot(90f);
						if (p.blockPosition().getY() - ground.getY() <= 3) {
							bucketPlaced = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction();
						}
					}
				} else if (p.onGround() && Inv.selectInHand(mc, s -> s.is(Items.BUCKET))) {
					p.setXRot(90f);
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND); // 把水舀回來
				}
			}
			case SELF_DEFENSE -> {
				if (combat == null) return;
				// 每半秒重新看一次：更危險的(例如苦力怕摸過來)就先處理
				if (activeTicks % 10 == 0) {
					Combat.Threat t = nextThreat(mc);
					if (t != null && t.entity() != combat.target()
						&& (combat.target() == null || !combat.target().isAlive() || t.priority() < engagedPriority)) {
						retarget(t);
					}
				}
				combat.tick(mc);
			}
			case UNSTUCK -> actUnstuck(mc);
			default -> {
			}
		}
	}

	/**
	 * 脫困步驟：
	 * - 水中不跳躍浮空，優先走淺水上岸(不受樹冠遮蔽限制)，找不到岸才改 GoalRunAway。
	 * - 陸地 0–9 tick 跳；第 10 tick 起挖頭前可破壞天然方塊(最多 UNSTUCK_DIG_LIMIT_TICKS)，
	 *   挖通或超時後再給足 UNSTUCK_ESCAPE_TICKS(3 秒)走離起點(H12、P3-M1)。
	 */
	private void actUnstuck(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (unstuckShore.active()) {
			switch (unstuckShore.tick(mc, agent.tick(), agent.control.userLooking(), null)) {
				case ARRIVED -> end(mc, "done");
				case FAILED -> startUnstuckRunAway(p);
				case WALKING -> {
				}
			}
			return;
		}
		if (unstuckRunAwayAt >= 0) {
			if (CombatPolicy.unstuckEscapeTimedOut(activeTicks, unstuckRunAwayAt)) end(mc, "still_stuck");
			return;
		}
		if (activeTicks == 1 && p.isInWater()) {
			unstuckStart = p.blockPosition().immutable();
			if (!unstuckShore.start(mc, agent.tick() + 100, null, false)) startUnstuckRunAway(p);
			return;
		}
		int digDeadline = 10 + CombatPolicy.UNSTUCK_DIG_LIMIT_TICKS;
		if (activeTicks < 10) {
			mc.options.keyJump.setDown(true);
		} else if (activeTicks == 10) {
			mc.options.keyJump.setDown(false);
			unstuckStart = p.blockPosition().immutable();
			BlockPos front = unstuckStart.relative(p.getDirection()).above();
			BlockState st = mc.level.getBlockState(front);
			boolean diggable = !st.isAir() && st.getFluidState().isEmpty() && st.getDestroySpeed(mc.level, front) >= 0
				&& agent.guard.isNatural(st) && agent.guard.canBreak(mc.level, front).allowed();
			// 只挖天然方塊：自己放的方塊(例如剛蓋的牆)也可以挖，但脫困時不該拆掉
			if (diggable) {
				BreakTask.equipBestTool(mc, st);
				mc.gameMode.startDestroyBlock(front, Moves.faceToward(p, front));
				unstuckFront = front;
			} else {
				startUnstuckRunAway(p);
			}
		} else if (activeTicks < digDeadline) {
			if (unstuckFront != null) {
				BlockState st = mc.level.getBlockState(unstuckFront);
				boolean diggable = !st.isAir() && st.getFluidState().isEmpty() && st.getDestroySpeed(mc.level, unstuckFront) >= 0
					&& agent.guard.isNatural(st) && agent.guard.canBreak(mc.level, unstuckFront).allowed();
				if (diggable) {
					mc.gameMode.continueDestroyBlock(unstuckFront, Moves.faceToward(p, unstuckFront));
				} else {
					mc.gameMode.stopDestroyBlock();
					unstuckFront = null;
					startUnstuckRunAway(p);
				}
			}
		} else {
			if (unstuckFront != null) {
				mc.gameMode.stopDestroyBlock();
				unstuckFront = null;
			}
			startUnstuckRunAway(p);
		}
	}

	private void startUnstuckRunAway(LocalPlayer p) {
		unstuckRunAwayAt = activeTicks;
		path(new GoalRunAway(3, unstuckStart != null ? unstuckStart : p.blockPosition()));
	}

	/**
	 * 撤退(R3)：RUN 跑開，怪物追上來就重新規劃(M14：每 2 秒一次，有怪物貼到 4 格內時半秒一次)；
	 * 4 格內沒有近戰怪而且有箭要擋時，停下舉盾擋完再跑。16 格內沒有看得到自己的威脅就進 RECOVER：
	 * 停下吃東西回血；有威脅走進 12 格就回到 RUN。
	 */
	private void retreat(Minecraft mc) {
		LocalPlayer p = mc.player;
		boolean meleeClose = false;
		for (Entity e : hostiles(mc, 4)) meleeClose |= Threats.kind(e, false, mc.player, agent.observation.recentAttacker(mc, 100)) != Threats.Kind.RANGED;
		if (!meleeClose && retreatGuard.update(mc, agent.tick())
			&& ShieldGuard.canSafelyStop(mc, agent.observation.recentAttacker(mc, 100), retreatGuard.faceAt())) {
			stopEating(mc);
			Bari.stopAll();
			if (retreatGuard.faceAt() != null) Moves.lookAt(p, retreatGuard.faceAt());
			ShieldGuard.raise(mc);
			retreatPlannedAt = -1000;
			return;
		}
		if (p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND) ShieldGuard.lower(mc);
		if (retreatPhase == RetreatPhase.RUN) {
			if (CombatPolicy.shouldEnterRetreatRecover(visibleThreats(mc, 16), hostiles(mc, 6).size())) {
				retreatPhase = RetreatPhase.RECOVER;
				recoverSince = activeTicks;
				Bari.stopAll();
				agent.log.log(ActionLog.Source.reflex, "retreat_recover", "hp=" + Math.round(p.getHealth()) + " food=" + p.getFoodData().getFoodLevel());
				return;
			}
			boolean close = !hostiles(mc, 4).isEmpty();
			if (activeTicks - retreatPlannedAt >= (close ? 10 : 40)) planRetreat(mc);
			return;
		}
		// RECOVER
		if (CombatPolicy.shouldLeaveRetreatRecover(visibleThreats(mc, 12), hostiles(mc, 4).size())) {
			stopEating(mc);
			retreatPhase = RetreatPhase.RUN;
			planRetreat(mc);
			return;
		}
		if (eatingFood != null) {
			if (p.isUsingItem() && p.getUseItem().is(eatingFood)) {
				UseKey.holdOff(mc);
				return;
			}
			stopEating(mc);
		}
		int food = p.getFoodData().getFoodLevel();
		ItemStack pick = retreatFood(p, food);
		if (pick == null) return;
		eatingFood = pick.getItem();
		eatStartCount = Inv.count(p, eatingFood);
		if (Inv.selectInHand(mc, s -> s.getItem() == eatingFood)) UseKey.startEating(mc);
		else eatingFood = null;
	}

	/**
	 * 回血時吃什麼：血量 ≤ 6 而且還沒有回復效果時金蘋果優先(金蘋果飽了也能吃；已經在回血就別再浪費一顆)；
	 * 其他食物要飢餓度 < 20 才吃得下。
	 */
	private ItemStack retreatFood(LocalPlayer p, int food) {
		if (p.getHealth() <= LOW_HEALTH && !p.hasEffect(MobEffects.REGENERATION)) {
			for (net.minecraft.world.item.Item apple : List.of(Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE)) {
				int idx = Inv.find(p, s -> s.is(apple));
				if (idx >= 0) return p.getInventory().getItem(idx);
			}
		}
		return food < 20 ? bestFood(p, food) : null;
	}

	private void stopEating(Minecraft mc) {
		if (eatingFood == null) return;
		UseKey.release(mc);
		eatingFood = null;
	}

	/** 這麼多格內看得到自己的威脅數量。 */
	private int visibleThreats(Minecraft mc, double r) {
		int n = 0;
		for (Entity e : hostiles(mc, r)) if (e.distanceTo(mc.player) <= r && mc.player.hasLineOfSight(e)) n++;
		return n;
	}

	/** 這麼多格內看得到自己的遠程威脅數量(骷髏在 13–16 格就能射，撤退觸發不能漏掉)。 */
	private int visibleRangedThreats(Minecraft mc, double r) {
		int n = 0;
		for (Entity e : hostiles(mc, r)) {
			if (e.distanceTo(mc.player) <= r && Threats.kind(e, false, mc.player, agent.observation.recentAttacker(mc, 100)) == Threats.Kind.RANGED && mc.player.hasLineOfSight(e)) n++;
		}
		return n;
	}

	private void end(Minecraft mc, String how) {
		Kind k = active;
		if (k == null) return;
		active = null;
		if (mc.options != null) mc.options.keyJump.setDown(false);
		if (k == Kind.EAT || (k == Kind.RETREAT && eatingFood != null)) {
			UseKey.release(mc);
		}
		if (k == Kind.RETREAT && mc.player != null && mc.gameMode != null) ShieldGuard.lower(mc);
		if (k == Kind.UNSTUCK && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		unstuckShore.stop(mc);
		unstuckStart = null;
		unstuckFront = null;
		unstuckRunAwayAt = -1;
		unstuckOriginPos = null;
		if (k == Kind.SELF_DEFENSE && combat != null && combat.target() != null && combat.target().isAlive()) {
			defenseCooldown.put(combat.target().getId(), agent.tick(), DEFENSE_COOLDOWN_TICKS, how.startsWith("preempted_by_"));
			// 打不贏而拉開距離：一起追來的也冷卻，不然換另一隻馬上又觸發(遊戲測試：2 隻殭屍輪流觸發)。
			// 脫身要甩到 18 格外才收手，所以範圍要比 16 格大
			if (combat.outmatched() && mc.player != null) {
				for (Entity e : hostiles(mc, 32)) {
					defenseCooldown.put(e.getId(), agent.tick(), DEFENSE_COOLDOWN_TICKS, how.startsWith("preempted_by_"));
				}
			}
		}
		// 脫身或撤退後記住剛才那群怪的位置：之後回家的路線看不到牠們也會繞開(S9.1b)
		boolean fled = (k == Kind.SELF_DEFENSE && combat != null && combat.outmatched()) || k == Kind.RETREAT;
		if (fled && mc.player != null && mc.level != null) {
			Entity attacker = agent.observation.recentAttacker(mc, 100);
			agent.danger.remember(mc, attacker, 32, agent.tick());
			// 任務恢復時立刻起算第一段路，先發布剛記下的避怪範圍。
			agent.danger.refresh(mc, attacker, agent.tick());
		}
		eatingFood = null;
		String action = describeAction(k);
		if (k == Kind.SELF_DEFENSE && combat != null) {
			action += " tactics=" + combat.summary();
			combat.release(mc, how);
		}
		combat = null;
		pendingThreat = null;
		if (k != Kind.SELF_DEFENSE && k != Kind.EAT) {
			try {
				Bari.stopAll();
			} catch (RuntimeException ignored) {
				// 斷線或離開世界時 Baritone 可能不可用
			}
		}
		String text = "reflex=" + k.id() + " kind=" + k.type + " trigger=" + blockagent.mcp.Kv.quote(String.valueOf(activeReason))
			+ " action=" + action + " result=" + how;
		boolean cancelTask = k.cancelsTask;
		if (k == Kind.FALL && how.startsWith("gave_up") && suspendedTask) {
			agent.tasks.interrupt("fall (could not confirm a safe landing)");
			suspendedTask = false;
			cancelTask = true;
		}
		if (k == Kind.RETREAT) {
			CombatPolicy.RetreatOutcome outcome = CombatPolicy.retreatOutcome(how, retreatCount);
			text += " phase=" + (retreatPhase == null ? "run" : retreatPhase.name().toLowerCase(Locale.ROOT)) + " retreats=" + retreatCount;
			if (outcome != CombatPolicy.RetreatOutcome.RESUME) {
				suspendedTask = false;
				String why = outcome == CombatPolicy.RetreatOutcome.CANCEL_REPEAT ? "retreated twice during this task; the area is too dangerous"
					: "could not heal (no food and hunger too low to regenerate)";
				if (interruptedTask != null) agent.tasks.interrupt("retreat (" + why + ")");
				cancelTask = true;
				text += " outcome=" + (outcome == CombatPolicy.RetreatOutcome.CANCEL_REPEAT ? "retreated_twice" : "cannot_heal")
					+ " | hint=" + (outcome == CombatPolicy.RetreatOutcome.CANCEL_REPEAT ? "go home, sleep, or wall yourself in before going on"
					: "find food or shelter; hp will not come back on its own");
			}
			if (how.equals("cannot_heal") || how.startsWith("gave_up")) {
				retreatQuietFrom = agent.tick();
				retreatQuietUntil = agent.tick() + RETREAT_QUIET_TICKS;
			}
			retreatPhase = null;
		}
		if (interruptedTask != null) {
			text += (cancelTask ? " task_cancelled=" : " task_resumed=") + blockagent.mcp.Kv.quote(interruptedTask);
		}
		if (k == Kind.UNSTUCK) {
			if (CombatPolicy.shouldReportUnstuckFailure(how, unstuckAttempts)) {
				unstuckAttempts = 0;
				unstuckProgressCount = 0;
				if (suspendedTask) {
					agent.tasks.resume();
					suspendedTask = false;
				}
				agent.tasks.interrupt("unstuck (failed 3 times; PATH_BLOCKED)");
				text += " unstuck_failed=3 task_failed=true";
			}
			history.clear();
		}
		if (suspendedTask) {
			if (fled && (how.equals("done") || how.equals("recovered")) && agent.tasks.current() instanceof GotoTask go) {
				go.watchThreatsAfterEscape();
			}
			agent.tasks.resume();
		}
		suspendedTask = false;
		agent.log.log(ActionLog.Source.reflex, k.id() + "_end", how);
		if (k.urgent()) agent.events.add("reflex", Event.Level.URGENT, text);
		else agent.events.add(new Event("reflex", Event.Level.BATCH, text, "reflex", System.currentTimeMillis()));
	}

	private static String describeAction(Kind k) {
		return switch (k) {
			case DROWNING -> "swim_to_air";
			case ON_FIRE -> "seek_water_or_leave_fire";
			case LAVA_AVOID -> "move_away_from_lava";
			case FALL -> "water_bucket_landing";
			case RETREAT -> "retreat_from_hostiles";
			case SELF_DEFENSE -> "fight_by_mob_type";
			case EAT -> "eat";
			case UNSTUCK -> "jump_dig_or_back_off";
		};
	}

	/** 暫停時：反射全部停止，完全交給使用者(S9.3a)。 */
	public synchronized void stopAll() {
		Minecraft mc = Minecraft.getInstance();
		if (active != null) {
			if (active == Kind.UNSTUCK && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
			agent.log.log(ActionLog.Source.reflex, active.id() + "_stopped", "暫停");
			active = null;
		}
		unstuckShore.stop(mc);
		unstuckStart = null;
		unstuckFront = null;
		unstuckRunAwayAt = -1;
		unstuckOriginPos = null;
		suspendedTask = false;
		if (combat != null) combat.release(mc, "paused");
		combat = null;
		pendingThreat = null;
		eatingFood = null;
		retreatPhase = null;
		retreatTask = null;
		retreatCount = 0;
		retreatQuietUntil = Long.MIN_VALUE;
		preparedFor.clear();
		history.clear();
		defenseCooldown.clear();
		unstuckAttempts = 0;
		unstuckProgressCount = 0;
		if (mc.options != null) {
			mc.options.keyJump.setDown(false);
			mc.options.keyUse.setDown(false);
		}
	}
}
