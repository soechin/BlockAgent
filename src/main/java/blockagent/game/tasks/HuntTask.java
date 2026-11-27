package blockagent.game.tasks;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.game.WaterFloat;
import blockagent.game.combat.Combat;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HuntTask extends Task {
	private static final int REPATH_INTERVAL = 5;
	private static final int DROP_SPAWN_TICKS = 20;
	private static final int PICKUP_TIMEOUT_TICKS = 160;
	private static final double DROP_RADIUS_SQUARED = 2.5 * 2.5;

	private final LivingEntity target;
	private final String targetName;
	private record Drop(long spawnTick, Vec3 spawnPosition) {
	}

	private final Map<Integer, Drop> drops = new HashMap<>();
	/** 打倒之後有沒有看到掉落物(沒有的話不算失敗，L6)。 */
	private boolean dropsSeen;
	private final LinkedHashMap<String, Integer> pickedUp = new LinkedHashMap<>();
	private BlockPos destination;
	private int destinationRange;
	private long lastPathTick;
	private long deathTick = -1;
	private boolean attacked;
	private Combat combat;
	private boolean moving;
	private boolean swimJumpHeld;
	private boolean swimForwardHeld;

	public HuntTask(Agent agent, LivingEntity target) {
		super(agent, "hunt");
		this.target = target;
		this.targetName = target.getName().getString();
	}

	/** 正在狩獵的目標(給自衛反射排除，避免把 AI 主動要打的目標搶去脫身)。 */
	public LivingEntity target() {
		return target;
	}

	@Override
	public String describe() {
		return deathTick < 0 ? "狩獵 " + targetName : "撿取 " + targetName + " 的掉落物";
	}

	@Override
	public String progress() {
		return deathTick < 0 ? "target=" + targetName + " id=" + target.getId() + " at=" + blockagent.game.Fmt.pos(target.blockPosition())
			: "target=" + targetName + " killed=true picked=" + blockagent.mcp.Kv.counts(pickedUp);
	}

	@Override
	public boolean moves() {
		return true;
	}

	@Override
	protected void tick(Minecraft mc) {
		if (deathTick >= 0) {
			collect(mc);
			return;
		}
		if (target.isDeadOrDying() && attacked) {
			deathTick = agent.tick();
			drops.values().removeIf(drop -> deathTick - drop.spawnTick() > DROP_SPAWN_TICKS
				|| drop.spawnPosition().distanceToSqr(target.position()) > DROP_RADIUS_SQUARED);
			dropsSeen = !drops.isEmpty();
			markProgress();
			if (combat != null) combat.release(mc);
			stopMoving();
			return;
		}
		if (mc.level.getEntity(target.getId()) != target || !target.isAlive()) {
			fail(ErrorCode.NOT_FOUND, "target left view or died without a confirmed kill");
			return;
		}
		// 怎麼打交給 Combat：苦力怕打了就退、遠程怪舉盾衝過去、有弓就射(S9.1b)
		if (combat == null) {
			combat = new Combat(agent, ActionLog.Source.ai, "hunt", new Combat.Mover() {
				@Override
				public void path(Goal goal) {
					HuntTask.this.path(goal);
					moving = true;
				}

				@Override
				public void stop() {
					stopMoving();
				}
			}, true);
			combat.engage(target);
		}
		int before = combat.hits();
		Combat.Outcome outcome = combat.tick(mc);
		if (outcome == Combat.Outcome.SUBMERGED_TARGET) {
			fail(ErrorCode.PATH_BLOCKED, "target is in deep water", "use a bow from shore or wait for the target to surface");
			return;
		}
		if (outcome == Combat.Outcome.BLOCKED) {
			fail(combat.blocked().code(), combat.blocked().reason(), combat.blocked().hint());
			return;
		}
		if (combat.engaged()) attacked = true;
		if (combat.hits() != before) markProgress();
	}

	/** 保留交戰後在目標身旁新出現的物品。拾取時仍以守衛的判定為準。 */
	public void onItemSpawn(ItemEntity item) {
		if (!attacked || (deathTick >= 0 && agent.tick() - deathTick > DROP_SPAWN_TICKS)) return;
		if (item.distanceToSqr(target) <= DROP_RADIUS_SQUARED) {
			drops.values().removeIf(drop -> deathTick < 0 && agent.tick() - drop.spawnTick() > DROP_SPAWN_TICKS);
			drops.put(item.getId(), new Drop(agent.tick(), item.position()));
			if (deathTick >= 0) dropsSeen = true;
		}
	}

	public void onItemPickedUp(ItemEntity item, int amount) {
		Drop drop = drops.get(item.getId());
		if (drop == null || !target.isDeadOrDying()) return;
		if (deathTick < 0 && (agent.tick() - drop.spawnTick() > DROP_SPAWN_TICKS
			|| drop.spawnPosition().distanceToSqr(target.position()) > DROP_RADIUS_SQUARED)) return;
		pickedUp.merge(Inv.id(item.getItem().getItem()), amount, Integer::sum);
		markProgress();
	}

	private void collect(Minecraft mc) {
		ItemEntity closest = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int id : drops.keySet()) {
			if (mc.level.getEntity(id) instanceof ItemEntity item) {
				double distance = item.distanceToSqr(mc.player);
				if (distance < bestDistance) {
					closest = item;
					bestDistance = distance;
				}
			}
		}
		long sinceDeath = agent.tick() - deathTick;
		if (closest == null) {
			stopMoving();
			if (sinceDeath < DROP_SPAWN_TICKS) {
				if (WaterFloat.needsSurfaceFloat(mc)) holdSwimKeys(mc, false);
				else releaseSwimKeys(mc);
				return;
			}
			releaseSwimKeys(mc);
			if (pickedUp.isEmpty() && !dropsSeen) {
				// 小動物、沒有掉落物的生物：打倒了就算完成(L6)
				done("drops=none");
			} else if (pickedUp.isEmpty()) {
				fail(ErrorCode.NOT_FOUND, "killed the target but picked up no keepable drops");
			} else {
				done("");
			}
			return;
		}
		if (sinceDeath >= PICKUP_TIMEOUT_TICKS) {
			releaseSwimKeys(mc);
			fail(ErrorCode.PATH_BLOCKED, "killed the target but some drops could not be picked up");
			return;
		}
		if (WaterFloat.needsSurfaceFloat(mc) && mc.player.hasLineOfSight(closest)
			&& WaterFloat.isOnWaterSurface(mc.level, closest)) {
			stopMoving();
			if (agent.control.userLooking()) {
				holdSwimKeys(mc, false);
			} else {
				Moves.lookAt(mc.player, closest.position());
				holdSwimKeys(mc, true);
			}
			return;
		}
		releaseSwimKeys(mc);
		moveTo(closest.blockPosition(), 0);
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

	private void moveTo(BlockPos pos, int range) {
		if (agent.tick() - lastPathTick < REPATH_INTERVAL) return;
		if (pos.equals(destination) && range == destinationRange && Bari.busy()) return;
		destination = pos;
		destinationRange = range;
		lastPathTick = agent.tick();
		path(new GoalNear(pos, range));
	}

	private void stopMoving() {
		if (destination == null && !moving) return;
		Bari.stopAll();
		destination = null;
		moving = false;
	}

	@Override
	protected void onSuspend(Minecraft mc) {
		if (combat != null) combat.suspend(mc);
		releaseSwimKeys(mc);
		stopMoving();
	}

	@Override
	protected void onStop(Minecraft mc) {
		if (combat != null) combat.release(mc);
		releaseSwimKeys(mc);
		stopMoving();
	}

	@Override
	protected void onResume(Minecraft mc) {
		// 讓下一個 tick 根據目標目前的位置重新規劃。
	}
}
