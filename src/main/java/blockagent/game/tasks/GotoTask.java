package blockagent.game.tasks;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.combat.DangerZones;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.List;

/** 走到某處(Baritone CustomGoalProcess)。 */
public final class GotoTask extends Task {
	private static final int SHORE_TIMEOUT_TICKS = 160;
	private static final int MAX_SHORE_ATTEMPTS = 3;
	private static final int ESCAPE_WATCH_TICKS = 20 * 30;
	private static final int ESCAPE_REPLAN_INTERVAL = 20;
	private static final int ESCAPE_REPLAN_LIMIT = 2;
	private static final int ESCAPE_LOOKAHEAD = 12;
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
	private boolean oldAllowBreak = true;
	private final ShoreExit shore = new ShoreExit();
	private int shoreAttempts;
	private long escapeWatchArmedAt = Long.MIN_VALUE / 2;
	private long escapeWatchUntil;
	private long nextThreatCheck;
	private int escapeReplans;
	private List<DangerZones.Zone> routeThreats = List.of();

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
		var settings = BaritoneAPI.getSettings();
		oldAllowBreak = settings.allowBreak.value;
		settings.allowBreak.value = false;
		bestHeuristic = goal.heuristic(mc.player.blockPosition());
		Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
	}

	/** 純移動期間不破壞方塊(避免毀壞礦道或地形)；被反射暫停時不套用(S1.4)。 */
	@Override
	public void adjustBaritone(baritone.api.Settings s) {
		if (suspended) return;
		s.allowBreak.value = false;
	}

	@Override
	protected void tick(Minecraft mc) {
		if (goal.isInGoal(mc.player.blockPosition())) {
			done("arrived=" + where);
			return;
		}
		if (shore.active()) {
			walkToShore(mc);
			return;
		}
		if (replanIfThreatMoved(mc)) return;
		double h = goal.heuristic(mc.player.blockPosition());
		if (h + 4.0 < bestHeuristic) {
			bestHeuristic = h;
			markProgress();
		}
		var b = Bari.b();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing()) {
			if (++idleTicks > 20 && !startShoreExit(mc)) {
				fail(ErrorCode.PATH_BLOCKED, "no path to " + where + " (blocked, too far, or chunks not loaded)",
					"goto does not break blocks; inspect the route, clear a single obstacle with break_block, or use dig_tunnel(stairs_down|stairs_up|flat)");
			}
		} else {
			idleTicks = 0;
		}
	}

	/** 脫身後的第一段路會短暫檢查追兵移動，原本的目的地及任務逾時照舊。 */
	public void watchThreatsAfterEscape() {
		long now = agent.tick();
		escapeWatchArmedAt = now;
		escapeWatchUntil = now + ESCAPE_WATCH_TICKS;
		nextThreatCheck = now + ESCAPE_REPLAN_INTERVAL;
		escapeReplans = 0;
	}

	private boolean replanIfThreatMoved(Minecraft mc) {
		long now = agent.tick();
		if (now >= escapeWatchUntil || now < nextThreatCheck || escapeReplans >= ESCAPE_REPLAN_LIMIT
			|| !mc.player.onGround()) return false;
		var pathing = Bari.b().getPathingBehavior();
		var executor = pathing.getCurrent();
		if (executor == null) return false;
		nextThreatCheck = now + ESCAPE_REPLAN_INTERVAL;
		var positions = executor.getPath().positions();
		int first = executor.getPosition() + 1;
		int limit = Math.min(first + ESCAPE_LOOKAHEAD, positions.size());
		BlockPos here = mc.player.blockPosition();
		for (DangerZones.Zone zone : agent.danger.snapshot()) {
			// 位置沒實質改變的怪或已離開視野的記憶，原路徑已經考慮過。
			if (zone.until() > now || routeThreats.stream().anyMatch(old -> old.entityId() == zone.entityId()
				&& old.radius() == zone.radius() && old.center().distSqr(zone.center()) < 4)) continue;
			double maxDistance = Math.min(here.distSqr(zone.center()), (double) zone.radius() * zone.radius());
			for (int i = first; i < limit; i++) {
				if (positions.get(i).distSqr(zone.center()) >= maxDistance) continue;
				var movements = executor.getPath().movements();
				if (executor.getPosition() >= movements.size() || !movements.get(executor.getPosition()).safeToCancel()) {
					nextThreatCheck = now + 1;
					return false;
				}
				boolean cancelled = pathing.cancelEverything();
				// Baritone 可能只取消流程而保留目前動作，此時同樣要把原本的目標交還給它。
				Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
				if (cancelled) {
					escapeReplans++;
					routeThreats = agent.danger.snapshot();
					agent.log.log(ActionLog.Source.ai, "goto_replan", "moving threat=" + zone.what()
						+ " at=" + zone.center().toShortString());
				} else {
					nextThreatCheck = now + 1;
				}
				return true;
			}
		}
		return false;
	}

	/** 只有正常尋路失敗、腳底有支撐的室外淺水，才嘗試沿水面走回最近的乾地。 */
	private boolean startShoreExit(Minecraft mc) {
		if (shoreAttempts >= MAX_SHORE_ATTEMPTS) return false;
		BlockPos start = mc.player.blockPosition();
		// 同樣近的岸邊先找靠近原目標的方向。
		if (!shore.start(mc, agent.tick() + SHORE_TIMEOUT_TICKS,
			Comparator.comparingDouble(d -> goal.heuristic(start.relative(d))), true)) {
			return false;
		}
		shoreAttempts++;
		markProgress();
		return true;
	}

	private void walkToShore(Minecraft mc) {
		switch (shore.tick(mc, agent.tick(), agent.control.userLooking(), this::markProgress)) {
			case ARRIVED -> {
				idleTicks = 0;
				markProgress();
				Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
			}
			case FAILED -> failShoreExit();
			case WALKING -> {
			}
		}
	}

	private void failShoreExit() {
		fail(ErrorCode.PATH_BLOCKED, "could not reach dry ground from shallow water", "move to the bank and retry goto");
	}

	@Override
	protected void onSuspend(Minecraft mc) {
		shore.stop(mc);
		Bari.stopAll();
		BaritoneAPI.getSettings().allowBreak.value = oldAllowBreak;
	}

	@Override
	protected void onStop(Minecraft mc) {
		try {
			shore.stop(mc);
			Bari.stopAll();
		} finally {
			BaritoneAPI.getSettings().allowBreak.value = oldAllowBreak;
		}
	}

	static boolean shouldShiftEscapeWatch(long watchUntil, long armedAt, long suspendedAt) {
		return watchUntil > 0 && armedAt <= suspendedAt;
	}

	@Override
	protected void onShiftTimers(long pausedTicks) {
		if (shouldShiftEscapeWatch(escapeWatchUntil, escapeWatchArmedAt, suspendedAt)) {
			escapeWatchArmedAt += pausedTicks;
			escapeWatchUntil += pausedTicks;
			if (nextThreatCheck > 0) nextThreatCheck += pausedTicks;
		}
		shore.shiftDeadline(pausedTicks);
	}

	@Override
	protected void onResume(Minecraft mc) {
		adjustBaritone(BaritoneAPI.getSettings());
		if (agent.tick() < escapeWatchUntil) routeThreats = agent.danger.snapshot();
		Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
	}
}
