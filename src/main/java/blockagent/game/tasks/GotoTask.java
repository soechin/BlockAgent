package blockagent.game.tasks;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** 走到某處(Baritone CustomGoalProcess)。 */
public final class GotoTask extends Task {
	/** 只比對水平座標的目標(省略 y 時用，沿地表走，避免山地猜錯海拔)。 */
	public record GoalNearXZ(int x, int z, int range) implements Goal {
		@Override
		public boolean isInGoal(int px, int py, int pz) {
			long dx = (long) px - x;
			long dz = (long) pz - z;
			return dx * dx + dz * dz <= (long) range * range;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			return GoalXZ.calculate(px - x, pz - z);
		}

		@Override
		public double heuristic() {
			return GoalXZ.calculate(range, 0);
		}
	}

	private final Goal goal;
	private final String where;
	private final int targetX, targetZ;
	/** 省略 y 時為 null，只算水平距離。 */
	private final Integer targetY;
	private int idleTicks;
	private double bestHeuristic = Double.POSITIVE_INFINITY;

	public GotoTask(Agent agent, String name, Goal goal, String where, BlockPos target) {
		this(agent, name, goal, where, target.getX(), target.getY(), target.getZ());
	}

	public GotoTask(Agent agent, String name, Goal goal, String where, int targetX, Integer targetY, int targetZ) {
		super(agent, name);
		this.goal = goal;
		this.where = where;
		this.targetX = targetX;
		this.targetY = targetY;
		this.targetZ = targetZ;
	}

	/** 玩家到目標的直線格數(省略 y 時只算水平距離)。 */
	static int distanceBlocks(int px, int py, int pz, int tx, Integer ty, int tz) {
		double dx = (double) px - tx;
		double dz = (double) pz - tz;
		double dy = ty == null ? 0.0 : (double) py - ty;
		return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
	}

	@Override
	public String describe() {
		var p = Minecraft.getInstance().player;
		if (p == null) return "走到目標";
		BlockPos bp = p.blockPosition();
		return "走到目標，剩 " + distanceBlocks(bp.getX(), bp.getY(), bp.getZ(), targetX, targetY, targetZ) + " 格";
	}

	@Override
	public String progress() {
		var p = Minecraft.getInstance().player;
		if (p == null) return "-";
		return "target=" + where + " cost_left=" + (int) Math.sqrt(Math.max(0, goal.heuristic(p.blockPosition())));
	}

	@Override
	public boolean moves() {
		return true;
	}

	@Override
	protected void start(Minecraft mc) {
		bestHeuristic = goal.heuristic(mc.player.blockPosition());
		Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
	}

	@Override
	protected void tick(Minecraft mc) {
		if (goal.isInGoal(mc.player.blockPosition())) {
			done("arrived=" + where);
			return;
		}
		double h = goal.heuristic(mc.player.blockPosition());
		if (h + 4.0 < bestHeuristic) {
			bestHeuristic = h;
			markProgress();
		}
		var b = Bari.b();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing()) {
			if (++idleTicks > 20) fail(ErrorCode.PATH_BLOCKED, "no path to " + where + " (blocked, too far, or chunks not loaded)");
		} else {
			idleTicks = 0;
		}
	}

	@Override
	protected void onStop(Minecraft mc) {
		Bari.stopAll();
	}

	@Override
	protected void onResume(Minecraft mc) {
		Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
	}
}
