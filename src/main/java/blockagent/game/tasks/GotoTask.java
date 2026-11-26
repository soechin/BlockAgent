package blockagent.game.tasks;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.BuildCommands;
import blockagent.game.combat.DangerZones;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 走到某處(Baritone CustomGoalProcess)。 */
public final class GotoTask extends Task {
	private static final int SHORE_MAX_STEPS = 8;
	private static final int SHORE_TIMEOUT_TICKS = 160;
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
	private final ArrayDeque<BlockPos> shoreSteps = new ArrayDeque<>();
	private boolean triedShore;
	private ClientLevel shoreLevel;
	private BlockPos shoreFrom;
	private long shoreDeadline;
	private long escapeWatchUntil;
	private long nextThreatCheck;
	private int escapeReplans;
	private List<DangerZones.Zone> routeThreats = List.of();

	private record ShoreNode(BlockPos pos, ShoreNode previous, int steps) {
	}

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
		if (!shoreSteps.isEmpty()) {
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
				fail(ErrorCode.PATH_BLOCKED, "no path to " + where + " (blocked, too far, or chunks not loaded)");
			}
		} else {
			idleTicks = 0;
		}
	}

	/** 脫身後的第一段路會短暫檢查追兵移動，原本的目的地及任務逾時照舊。 */
	public void watchThreatsAfterEscape() {
		escapeWatchUntil = agent.tick() + ESCAPE_WATCH_TICKS;
		nextThreatCheck = agent.tick() + ESCAPE_REPLAN_INTERVAL;
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
		var player = mc.player;
		BlockPos start = player.blockPosition();
		if (triedShore || player.isEyeInFluid(FluidTags.WATER) || !shallowWater(mc.level, start)) return false;

		if (!planShoreRoute(mc.level, start)) return false;
		Bari.stopAll();
		triedShore = true;
		shoreLevel = mc.level;
		shoreDeadline = agent.tick() + SHORE_TIMEOUT_TICKS;
		markProgress();
		return true;
	}

	private boolean planShoreRoute(ClientLevel level, BlockPos start) {
		// 同樣近的岸邊先找靠近原目標的方向。
		Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
		Arrays.sort(directions, Comparator.comparingDouble(d -> goal.heuristic(start.relative(d))));
		var pending = new ArrayDeque<ShoreNode>();
		var visited = new HashSet<BlockPos>();
		pending.add(new ShoreNode(start, null, 0));
		visited.add(start);
		while (!pending.isEmpty()) {
			ShoreNode here = pending.removeFirst();
			for (Direction direction : directions) {
				BlockPos next = here.pos().relative(direction);
				if (dryGround(level, next)) {
					setShoreRoute(start, next, here);
					return true;
				}
				if (dryGround(level, next.above()) && level.getBlockState(here.pos().above(2)).isAir()) {
					setShoreRoute(start, next.above(), here);
					return true;
				}
				if (here.steps() + 1 < SHORE_MAX_STEPS && visited.add(next) && shallowWater(level, next)) {
					pending.addLast(new ShoreNode(next, here, here.steps() + 1));
				}
			}
		}
		return false;
	}

	private void setShoreRoute(BlockPos start, BlockPos shore, ShoreNode lastWater) {
		shoreFrom = start;
		shoreSteps.clear();
		shoreSteps.add(shore);
		for (ShoreNode at = lastWater; at.previous() != null; at = at.previous()) {
			shoreSteps.addFirst(at.pos());
		}
	}

	private static boolean openWithFooting(ClientLevel level, BlockPos feet) {
		if (!BuildCommands.validTarget(level, feet) || !level.canSeeSky(feet.above())
			|| !level.getBlockState(feet.above()).isAir()) return false;
		BlockPos below = feet.below();
		var floor = level.getBlockState(below);
		return floor.getFluidState().isEmpty() && !floor.is(Blocks.MAGMA_BLOCK)
			&& floor.isFaceSturdy(level, below, Direction.UP);
	}

	private static boolean shallowWater(ClientLevel level, BlockPos feet) {
		return openWithFooting(level, feet) && level.getBlockState(feet).is(Blocks.WATER);
	}

	private static boolean dryGround(ClientLevel level, BlockPos feet) {
		return openWithFooting(level, feet) && level.getBlockState(feet).isAir();
	}

	private void walkToShore(Minecraft mc) {
		var player = mc.player;
		BlockPos here = player.blockPosition();
		if (mc.level != shoreLevel || player.isEyeInFluid(FluidTags.WATER) || agent.tick() >= shoreDeadline) {
			failShoreExit();
			return;
		}
		if (player.onGround() && dryGround(mc.level, here) && !player.isInWater()) {
			releaseShoreKeys(mc);
			shoreSteps.clear();
			idleTicks = 0;
			markProgress();
			Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
			return;
		}

		BlockPos next = shoreSteps.getFirst();
		boolean last = shoreSteps.size() == 1;
		boolean rising = next.getY() > shoreFrom.getY();
		boolean onRoute = here.atY(shoreFrom.getY()).equals(shoreFrom) || here.atY(next.getY()).equals(next);
		if (!onRoute || !shallowWater(mc.level, shoreFrom) || !(last ? dryGround(mc.level, next) : shallowWater(mc.level, next))
			|| (rising && !mc.level.getBlockState(shoreFrom.above(2)).isAir())) {
			// 水流可能把玩家推進相鄰格。只從仍可安全踩地的淺水重找路，沿用原本時限。
			releaseShoreKeys(mc);
			if (!shallowWater(mc.level, here) || !planShoreRoute(mc.level, here)) failShoreExit();
			return;
		}
		var center = Vec3.atBottomCenterOf(next);
		if (here.equals(next) && player.position().subtract(center).horizontalDistanceSqr() < 0.09) {
			releaseShoreKeys(mc);
			if (!last) {
				shoreSteps.removeFirst();
				shoreFrom = next;
				markProgress();
			}
			return;
		}
		if (agent.control.userLooking()) {
			releaseShoreKeys(mc);
			return;
		}
		Moves.lookAt(player, new Vec3(center.x, player.getEyeY(), center.z));
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(rising);
	}

	private void failShoreExit() {
		fail(ErrorCode.PATH_BLOCKED, "could not reach dry ground from shallow water", "move to the bank and retry goto");
	}

	private static void releaseShoreKeys(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
	}

	@Override
	protected void onStop(Minecraft mc) {
		if (!shoreSteps.isEmpty()) {
			releaseShoreKeys(mc);
			shoreSteps.clear();
		}
		Bari.stopAll();
	}

	@Override
	protected void onResume(Minecraft mc) {
		if (agent.tick() < escapeWatchUntil) routeThreats = agent.danger.snapshot();
		Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
	}
}
