package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Inv;
import blockagent.game.WaterFloat;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/** 對生物用手上的物品(use_on_entity)：剪羊毛、餵食繁殖、拴繩、擠奶…，用完撿起掉在旁邊的東西。 */
public final class UseEntityTask extends EntityTask {
	private static final int COLLECT_TICKS = 60;

	private final Item item; // null=空手
	private Map<String, Integer> before;
	private long usedTick = -1;

	public UseEntityTask(Agent agent, Entity target, Item item) {
		super(agent, "use_on_entity", target);
		this.item = item;
	}

	private String what() {
		return item == null ? "空手" : name(item);
	}

	@Override
	public String describe() {
		return "對 " + hudName() + " 用" + what();
	}

	@Override
	public String progress() {
		return "target=" + targetName + (usedTick < 0 ? " state=walking" : " state=collecting");
	}

	private BlockPos collectKey;
	private boolean swimJumpHeld;
	private boolean swimForwardHeld;

	@Override
	protected void start(Minecraft mc) {
		before = Inv.summary(mc.player);
	}

	@Override
	protected void onStop(Minecraft mc) {
		super.onStop(mc);
		releaseSwimKeys(mc);
		// 撿東西時的走路不在 EntityTask 的記錄裡，取消時也要停下(H12)
		blockagent.game.Bari.stopAll();
	}

	@Override
	protected void onResume(Minecraft mc) {
		collectKey = null;
		super.onResume(mc);
	}

	@Override
	protected void tick(Minecraft mc) {
		if (usedTick >= 0) {
			collect(mc);
			return;
		}
		if (!present(mc)) {
			fail(ErrorCode.NOT_FOUND, targetName + " is no longer nearby");
			return;
		}
		if (!approach(mc)) return;
		boolean held = item == null ? Inv.selectInHand(mc, ItemStack::isEmpty) : Inv.selectInHand(mc, s -> s.is(item));
		if (!held) {
			fail(item == null ? ErrorCode.INVALID_ARGUMENT : ErrorCode.MISSING_MATERIALS,
				item == null ? "need an empty hand but the inventory has no free slot" : "no " + Inv.id(item) + " in inventory");
			return;
		}
		long mark = agent.guard.blockMark();
		var r = interact(mc);
		if (!r.consumesAction()) {
			var v = agent.guard.blockedAfter(mark);
			if (v != null) fail(v.code(), v.reason(), v.hint());
			else fail(ErrorCode.INVALID_ARGUMENT, "using " + (item == null ? "empty hand" : Inv.id(item)) + " on " + targetName + " had no effect (already sheared, wrong food, cooldown, or a baby)");
			return;
		}
		usedTick = agent.tick();
		markProgress();
	}

	@Override
	protected void onShiftTimers(long pausedTicks) {
		if (usedTick >= 0) usedTick += pausedTicks;
	}

	/** 撿起互動後掉在目標旁邊的新物品(剪下的羊毛等)，最多 3 秒。 */
	private void collect(Minecraft mc) {
		long since = agent.tick() - usedTick;
		ItemEntity closest = null;
		double best = Double.MAX_VALUE;
		for (Entity e : mc.level.getEntities(mc.player, target.getBoundingBox().inflate(4), e -> e instanceof ItemEntity && e.tickCount <= since + 5)) {
			double d = e.distanceToSqr(mc.player);
			if (d < best) {
				best = d;
				closest = (ItemEntity) e;
			}
		}
		if (closest != null && since < COLLECT_TICKS) {
			if (WaterFloat.needsSurfaceFloat(mc) && mc.player.hasLineOfSight(closest)
				&& WaterFloat.isOnWaterSurface(mc.level, closest)) {
				if (collectKey != null || blockagent.game.Bari.busy()) {
					blockagent.game.Bari.stopAll();
					collectKey = null;
				}
				if (agent.control.userLooking()) {
					holdSwimKeys(mc, false);
				} else {
					Moves.lookAt(mc.player, closest.position());
					holdSwimKeys(mc, true);
				}
				return;
			}
			releaseSwimKeys(mc);
			// 掉落物換了位置才重新規劃，不要每 tick 都重新尋路(H12)
			BlockPos at = closest.blockPosition();
			if (!at.equals(collectKey)) {
				collectKey = at;
				path(new baritone.api.pathing.goals.GoalNear(at, 0));
			}
			return;
		}
		if (since < 10) {
			if (WaterFloat.needsSurfaceFloat(mc)) holdSwimKeys(mc, false);
			else releaseSwimKeys(mc);
			return; // 等掉落物生成
		}
		releaseSwimKeys(mc);
		blockagent.game.Bari.stopAll();
		String change = Inv.diff(before, Inv.summary(mc.player));
		done(change.isEmpty() ? "" : "inv_change=" + change);
	}

	private void holdSwimKeys(Minecraft mc, boolean forward) {
		if (mc == null || mc.options == null) return;
		mc.options.keyJump.setDown(true);
		swimJumpHeld = true;
		mc.options.keyUp.setDown(forward);
		swimForwardHeld = forward;
	}

	private void releaseSwimKeys(Minecraft mc) {
		if (mc != null && mc.options != null) {
			if (swimJumpHeld) mc.options.keyJump.setDown(false);
			if (swimForwardHeld) mc.options.keyUp.setDown(false);
		}
		swimJumpHeld = false;
		swimForwardHeld = false;
	}
}
