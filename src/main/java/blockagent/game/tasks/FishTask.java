package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import blockagent.mixin.FishingHookAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/** 釣魚(fish)：走到水邊拋竿，浮標被咬住就收竿，釣到指定數量或時間到為止。 */
public final class FishTask extends Task {
	private enum Phase {FIND, WALK, CAST, WAIT, REEL}

	private static final int WALK_TICKS = 30 * 20;
	private static final int MAX_MISSES = 3;

	private final int count;
	private final int maxSec;
	private Phase phase = Phase.FIND;
	private BlockPos water;
	private BlockPos spot;
	private long phaseTick;
	private int caught;
	private int misses;
	private int beforeReel;
	private boolean pendingReel;
	private Vec3 hookAt;
	private Map<String, Integer> before;

	public FishTask(Agent agent, int count, int maxSec) {
		super(agent, "fish");
		this.count = count;
		this.maxSec = maxSec;
	}

	@Override
	public String describe() {
		return "釣魚 " + caught + "/" + count;
	}

	@Override
	public String progress() {
		return "caught=" + caught + "/" + count;
	}

	/** 只有走去水邊時算移動；站著等魚上鉤不算卡住。 */
	@Override
	public boolean moves() {
		return phase == Phase.FIND || phase == Phase.WALK;
	}

	private void enter(Phase p) {
		phase = p;
		phaseTick = agent.tick();
	}

	private long inPhase() {
		return agent.tick() - phaseTick;
	}

	@Override
	protected void start(Minecraft mc) {
		before = Inv.summary(mc.player);
	}

	@Override
	protected void tick(Minecraft mc) {
		var p = mc.player;
		if (elapsed() >= maxSec * 20L && phase != Phase.REEL) {
			reelIn(mc);
			finish(mc, "time_up");
			return;
		}
		// 收竿後釣到的東西可能要一兩秒才飛進背包；若最後一尾在暫停期間入包，恢復切到 CAST 前就該直接結束(P3-M13)
		if (pendingReel && catchTotal(p) > beforeReel) {
			caught++;
			misses = 0;
			pendingReel = false;
			markProgress();
			if (caught >= count) {
				finish(mc, null);
				return;
			}
		}
		switch (phase) {
			case FIND -> {
				List<BlockPos> found = Moves.scan(mc, List.of(Blocks.WATER), 16, 64, b -> surface(mc, b));
				if (found.isEmpty()) {
					fail(ErrorCode.NOT_FOUND, "no open water surface within 16 blocks");
					return;
				}
				// 拋到開闊的水面：優先選四周也是水面的格子(拋到水邊容易落在岸上)
				water = found.stream().filter(b -> open(mc, b)).findFirst().orElse(found.getFirst());
				spot = shoreSpot(mc, water);
				if (spot == null) {
					fail(ErrorCode.PATH_BLOCKED, "no shore spot 2-5 blocks from the water at " + blockagent.game.Fmt.pos(water));
					return;
				}
				if (canCast(mc)) enter(Phase.CAST);
				else {
					path(new baritone.api.pathing.goals.GoalBlock(spot));
					enter(Phase.WALK);
				}
			}
			case WALK -> {
				if (canCast(mc)) {
					Bari.stopAll();
					enter(Phase.CAST);
				} else if (inPhase() > WALK_TICKS || (!Bari.busy() && inPhase() > 40)) {
					fail(ErrorCode.PATH_BLOCKED, "cannot reach the shore near " + blockagent.game.Fmt.pos(water));
				}
			}
			case CAST -> {
				if (!Inv.selectInHand(mc, s -> s.is(Items.FISHING_ROD))) {
					fail(ErrorCode.MISSING_TOOL, "fishing rod is gone");
					return;
				}
				if (p.fishing != null) { // 上一竿還在水裡：先收回來
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
					return;
				}
				// 朝水面的方向，俯仰角用浮標的飛行模擬算出落點正好在目標格
				Vec3 target = Vec3.atBottomCenterOf(water).add(0, 0.9, 0);
				Moves.lookAt(p, target);
				float pitch = castPitch(Math.hypot(target.x - p.getX(), target.z - p.getZ()), target.y - p.getEyeY());
				p.setXRot(pitch);
				p.xRotO = pitch;
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
				p.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
				enter(Phase.WAIT);
			}
			case WAIT -> {
				var hook = p.fishing;
				if (hook == null) {
					if (inPhase() > 20) miss(mc, "bobber was not cast");
					return;
				}
				markProgress();
				// 客戶端的 FishingHook.isInWater() 不可靠(浮在水面上也回 false)，直接看浮標所在格和下面一格是不是水
				BlockPos hp = hook.blockPosition();
				boolean inWater = mc.level.getFluidState(hp).is(net.minecraft.tags.FluidTags.WATER)
					|| mc.level.getFluidState(hp.below()).is(net.minecraft.tags.FluidTags.WATER);
				if (inPhase() > 60 && !inWater) {
					BlockPos at = hook.blockPosition();
					miss(mc, "bobber landed on " + blockagent.game.RuleGuard.shortId(mc.level.getBlockState(at.below())) + " at "
						+ blockagent.game.Fmt.pos(at) + " instead of the water at " + blockagent.game.Fmt.pos(water));
					return;
				}
				if (((FishingHookAccessor) hook).blockagent$isBiting()) {
					beforeReel = catchTotal(p);
					pendingReel = true;
					hookAt = hook.position();
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
					p.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
					enter(Phase.REEL);
				}
			}
			case REEL -> {
				// 釣到的東西通常會飛過來；被岸邊擋住、停在水裡時，自己過去撿(進背包就在 tick 開頭計數)
				if (pendingReel && inPhase() < 200) {
					if (inPhase() > 30) fetchCatch(mc);
					return;
				}
				pendingReel = false;
				if (caught >= count) {
					finish(mc, null);
					return;
				}
				if (canCast(mc)) enter(Phase.CAST);
				else {
					path(new baritone.api.pathing.goals.GoalBlock(spot));
					enter(Phase.WALK);
				}
			}
		}
	}

	private static boolean surface(Minecraft mc, BlockPos b) {
		return mc.level.getFluidState(b).isSource() && mc.level.getBlockState(b).is(Blocks.WATER) && mc.level.getBlockState(b.above()).isAir();
	}

	private static boolean open(Minecraft mc, BlockPos b) {
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!surface(mc, b.offset(dx, 0, dz))) return false;
		return true;
	}

	/** 離水面 2 到 5 格、腳下是實心方塊、身體所在的兩格是空氣的岸邊，選離自己最近的。 */
	private static BlockPos shoreSpot(Minecraft mc, BlockPos water) {
		BlockPos me = mc.player.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				double h = Math.sqrt(dx * dx + dz * dz);
				if (h < 2 || h > 5) continue;
				for (int dy = 0; dy <= 2; dy++) {
					BlockPos feet = water.offset(dx, dy, dz);
					BlockPos below = feet.below();
					if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, net.minecraft.core.Direction.UP)) continue;
					if (!mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty() || !mc.level.getFluidState(feet).isEmpty()) continue;
					if (!mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()) continue;
					double d = feet.distSqr(me);
					if (d < bestD) {
						bestD = d;
						best = feet.immutable();
					}
				}
			}
		}
		return best;
	}

	/** 站在岸上、離水面 5 格以內、看得到水面才拋竿。 */
	private boolean canCast(Minecraft mc) {
		var p = mc.player;
		if (!p.onGround() || p.isInWater()) return false;
		Vec3 target = Vec3.atCenterOf(water).add(0, 0.5, 0);
		double h = Math.hypot(p.getX() - target.x, p.getZ() - target.z);
		if (h > 5.5 || h < 1.5) return false;
		var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), target,
			net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	/**
	 * 浮標的拋射：原版 FishingHook 的初速是水平 0.6·cos(仰角)+0.5、垂直 −(0.6·sin+0.5·tan)，
	 * 每 tick 先受重力 0.03 再移動、速度乘 0.92。回傳落點離 h 最近的俯仰角(正值往下看)。
	 */
	static float castPitch(double h, double dy) {
		float best = 20f;
		double bestErr = Double.MAX_VALUE;
		for (float pitch = -30f; pitch <= 70f; pitch += 1f) {
			double r = Math.toRadians(pitch);
			double vx = 0.6 * Math.cos(r) + 0.5, vy = -(0.6 * Math.sin(r) + 0.5 * Math.tan(r)), x = 0, y = 0;
			for (int t = 0; t < 200 && y > dy; t++) {
				vy -= 0.03;
				x += vx;
				y += vy;
				vx *= 0.92;
				vy *= 0.92;
			}
			double err = Math.abs(x - h);
			if (err < bestErr) {
				bestErr = err;
				best = pitch;
			}
		}
		return best;
	}

	/** 走過去撿起剛釣上來、停在浮標附近的物品。 */
	private void fetchCatch(Minecraft mc) {
		var p = mc.player;
		var items = mc.level.getEntities(p, net.minecraft.world.phys.AABB.ofSize(hookAt, 6, 4, 6),
			e -> e instanceof net.minecraft.world.entity.item.ItemEntity && e.tickCount <= inPhase() + 5);
		if (items.isEmpty()) return;
		var item = items.getFirst();
		if (!Bari.busy()) path(new baritone.api.pathing.goals.GoalNear(item.blockPosition(), 0));
	}

	private void miss(Minecraft mc, String why) {
		reelIn(mc);
		if (++misses >= MAX_MISSES) {
			fail(ErrorCode.PATH_BLOCKED, why + " (" + misses + " times in a row); try a more open water surface");
			return;
		}
		enter(Phase.CAST);
	}

	private void reelIn(Minecraft mc) {
		var p = mc.player;
		// 斷線時沒有玩家(H9)
		if (p == null || mc.gameMode == null) return;
		if (p.fishing != null && p.getMainHandItem().is(Items.FISHING_ROD)) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
	}

	/** 背包裡釣竿以外的物品總數。釣竿在最後一竿斷掉時總數不變，只看釣竿以外的才算得到那一尾(M14)。 */
	private static int catchTotal(net.minecraft.world.entity.player.Player p) {
		return Inv.total(p) - Inv.count(p, Items.FISHING_ROD);
	}

	private void finish(Minecraft mc, String why) {
		String change = Inv.diff(before, Inv.summary(mc.player));
		if (caught == 0) {
			fail(ErrorCode.TIMEOUT, (why == null ? "" : why + ": ") + "caught nothing");
			return;
		}
		done((why == null ? "" : "stopped=" + why) + (change.isEmpty() ? "" : " inv_change=" + change));
	}

	@Override
	protected void onStop(Minecraft mc) {
		try {
			reelIn(mc);
		} finally {
			Bari.stopAll();
		}
	}

	/** 反射可能把人帶離岸邊：站得到拋竿的位置才直接拋，不然先走回岸邊(H7：原本原地亂拋)。 */
	@Override
	protected void onResume(Minecraft mc) {
		if (water == null || spot == null) {
			enter(Phase.FIND);
		} else if (canCast(mc)) {
			enter(Phase.CAST);
		} else {
			path(new baritone.api.pathing.goals.GoalBlock(spot));
			enter(Phase.WALK);
		}
	}
}
