package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalNear;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Fmt;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * 跟著一位玩家走(follow)。只在 AI 呼叫時開始，時間到、對方不見、stop、暫停或接手就停(需求 M2.5：不做自動跟隨)。
 */
public final class FollowTask extends Task {
	private static final int REPATH_INTERVAL = 20;
	private static final int MAX_FAILURES = 2;
	private static final int STALL_TICKS = 160;

	private final String playerName;
	private final int distance;
	private final int maxSec;
	private BlockPos destination;
	private long lastPathTick = Long.MIN_VALUE / 2;
	private double lastDistance = -1;
	private BlockPos failureTarget;
	private BlockPos movementAnchor;
	private boolean reached;
	private int failedAttempts;
	private double bestDistance = Double.POSITIVE_INFINITY;

	public FollowTask(Agent agent, String playerName, int distance, int maxSec) {
		super(agent, "follow");
		this.playerName = playerName;
		this.distance = distance;
		this.maxSec = maxSec;
	}

	@Override
	public String describe() {
		return "跟著 " + playerName + "(保持約 " + distance + " 格)";
	}

	@Override
	public String progress() {
		return "player=" + playerName + " followed=" + elapsed() / 20 + "/" + maxSec + "s" + (lastDistance < 0 ? "" : " dist=" + Math.round(lastDistance));
	}

	@Override
	public boolean moves() {
		return true;
	}

	public static Player findPlayer(Minecraft mc, String name) {
		for (Player p : mc.level.players()) {
			if (p != mc.player && p.getName().getString().equalsIgnoreCase(name)) return p;
		}
		return null;
	}

	@Override
	protected void tick(Minecraft mc) {
		Player target = findPlayer(mc, playerName);
		if (target == null) {
			fail(ErrorCode.NOT_FOUND, playerName + " is no longer nearby (offline, other dimension, or out of range)");
			return;
		}
		lastDistance = mc.player.distanceTo(target);
		BlockPos at = target.blockPosition();
		BlockPos here = mc.player.blockPosition();
		int height = at.getY() - here.getY();
		boolean close = lastDistance <= distance + 1.5 && Math.abs(height) <= 1;
		if (close || failureTarget == null || at.distSqr(failureTarget) > 4) {
			failureTarget = at;
			failedAttempts = 0;
			bestDistance = lastDistance;
			movementAnchor = here;
			markProgress();
		} else if (lastDistance < bestDistance - 0.5) {
			bestDistance = lastDistance;
			failedAttempts = 0;
			movementAnchor = here;
			markProgress();
		} else if (here.distSqr(movementAnchor) > 4) {
			// 真正在走繞路也算進展，避免只因暫時沒接近目標而把樓梯或繞行判成死路。
			movementAnchor = here;
			markProgress();
		}
		reached |= close;
		if (elapsed() >= maxSec * 20L) {
			if (reached) done("");
			else fail(ErrorCode.PATH_BLOCKED, unreachable(here, at, height));
			return;
		}
		if (close) {
			if (destination != null) {
				Bari.stopAll();
				destination = null;
			}
			return;
		}
		if (ticksSinceProgress() >= STALL_TICKS) {
			fail(ErrorCode.PATH_BLOCKED, unreachable(here, at, height), "ask the player to come closer or find stairs or another route");
			return;
		}
		if (agent.tick() - lastPathTick < REPATH_INTERVAL) return;
		boolean busy = Bari.b().getCustomGoalProcess().isActive()
			|| Bari.b().getPathingBehavior().hasPath() || Bari.b().getPathingBehavior().getInProgress().isPresent();
		if (destination != null && !busy && at.distSqr(destination) <= 1 && ++failedAttempts >= MAX_FAILURES) {
			fail(ErrorCode.PATH_BLOCKED, unreachable(here, at, height), "ask the player to come closer or find stairs or another route");
			return;
		}
		if (busy && destination != null && at.distSqr(destination) <= 1) return;
		destination = at;
		lastPathTick = agent.tick();
		path(new GoalNear(at, distance) {
			@Override
			public boolean isInGoal(int x, int y, int z) {
				return Math.abs(y - at.getY()) <= 1 && super.isInGoal(x, y, z);
			}
		});
	}

	private String unreachable(BlockPos here, BlockPos there, int height) {
		return "could not get within following distance of " + playerName + "; self=" + Fmt.pos(here)
			+ " target=" + Fmt.pos(there) + " delta_y=" + height;
	}

	@Override
	protected void onStop(Minecraft mc) {
		Bari.stopAll();
		destination = null;
	}

	@Override
	protected void onResume(Minecraft mc) {
		destination = null; // 下一個 tick 依對方目前的位置重新找路
	}
}
