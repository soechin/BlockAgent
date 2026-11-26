package blockagent.game.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** 估計鄰近掩體的步行成本與沿路曝露，實際移動交由 Baritone。 */
public final class CoverRoute {
	private static final int SEARCH_RADIUS = 8;
	private static final int SEARCH_HEIGHT = 2;
	private static final int SEARCH_LIMIT = 256;
	private static final int EXPOSURE_COST = 2;
	private static final int STALL_TICKS = 20;
	private static final int RETRY_TICKS = 10;

	private final Set<BlockPos> rejected = new HashSet<>();
	private BlockPos goal;
	private BlockPos lastPosition;
	private long movedAt;
	private long searchedAt = Long.MIN_VALUE / 2;

	private record Step(BlockPos pos, int cost, int exposed) {
	}

	public void clear() {
		rejected.clear();
		goal = null;
		lastPosition = null;
		searchedAt = Long.MIN_VALUE / 2;
	}

	/** 射手或玩家移動時驗證掩體；無路可走時節流重試。 */
	public BlockPos choose(Minecraft mc, List<? extends Entity> shooters, long now) {
		if (shooters.isEmpty()) return null;
		BlockPos position = mc.player.blockPosition();
		if (goal != null) {
			if (!position.equals(lastPosition)) movedAt = now;
			lastPosition = position;
			if (!position.equals(goal) && now - movedAt >= STALL_TICKS) {
				rejected.add(goal);
				goal = null;
			} else if (exposure(mc, goal, shooters) == 0 && canStand(mc.level, goal)) {
				return goal;
			} else {
				goal = null;
			}
		}
		if (now - searchedAt < RETRY_TICKS) return null;
		searchedAt = now;
		goal = find(mc, position, shooters);
		lastPosition = position;
		movedAt = now;
		return goal;
	}

	private BlockPos find(Minecraft mc, BlockPos start, List<? extends Entity> shooters) {
		Level level = mc.level;
		if (!canStand(level, start)) return null;
		double minGap = nearestShooter(start, shooters) + 1;
		PriorityQueue<Step> open = new PriorityQueue<>(Comparator.comparingInt(Step::cost));
		Map<BlockPos, Integer> costs = new HashMap<>();
		Map<BlockPos, Integer> visibility = new HashMap<>();
		open.add(new Step(start, 0, exposure(mc, start, shooters)));
		costs.put(start, 0);
		int expanded = 0;
		while (!open.isEmpty() && expanded < SEARCH_LIMIT) {
			Step current = open.remove();
			if (current.cost() != costs.get(current.pos())) continue;
			expanded++;
			if (current.exposed() == 0 && !current.pos().equals(start) && !rejected.contains(current.pos())
				&& nearestShooter(current.pos(), shooters) >= minGap && !lavaNearby(level, current.pos())) {
				return current.pos();
			}
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos next = adjacentStep(level, current.pos(), direction);
				if (next == null || Math.abs(next.getX() - start.getX()) > SEARCH_RADIUS
					|| Math.abs(next.getY() - start.getY()) > SEARCH_HEIGHT
					|| Math.abs(next.getZ() - start.getZ()) > SEARCH_RADIUS) continue;
				int exposed = visibility.computeIfAbsent(next, b -> exposure(mc, b, shooters));
				int cost = current.cost() + 1 + Math.abs(next.getY() - current.pos().getY())
					+ EXPOSURE_COST * exposed;
				if (cost >= costs.getOrDefault(next, Integer.MAX_VALUE)) continue;
				costs.put(next, cost);
				open.add(new Step(next, cost, exposed));
			}
		}
		return null;
	}

	/** 只考慮平移、一步跳上或往下走一格。 */
	private static BlockPos adjacentStep(Level level, BlockPos from, Direction direction) {
		BlockPos side = from.relative(direction);
		if (canStand(level, side)) return side;
		if (passable(level, side) && passable(level, side.above()) && canStand(level, side.below())) return side.below();
		if (passable(level, from.above(2)) && canStand(level, side.above())) return side.above();
		return null;
	}

	private static boolean canStand(Level level, BlockPos pos) {
		if (!level.hasChunkAt(pos) || !passable(level, pos) || !passable(level, pos.above())) return false;
		BlockPos below = pos.below();
		var floor = level.getBlockState(below);
		return floor.getFluidState().isEmpty() && !floor.is(Blocks.MAGMA_BLOCK)
			&& floor.isFaceSturdy(level, below, Direction.UP);
	}

	private static boolean passable(Level level, BlockPos pos) {
		var state = level.getBlockState(pos);
		return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()
			&& !state.is(Blocks.POWDER_SNOW) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.COBWEB)
			&& !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE);
	}

	private static boolean lavaNearby(Level level, BlockPos pos) {
		for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 0, 1))) {
			if (level.getFluidState(near).is(FluidTags.LAVA)) return true;
		}
		return false;
	}

	private static double nearestShooter(BlockPos pos, List<? extends Entity> shooters) {
		Vec3 center = Vec3.atBottomCenterOf(pos);
		double best = Double.MAX_VALUE;
		for (Entity shooter : shooters) best = Math.min(best, center.distanceTo(shooter.position()));
		return best;
	}

	private static int exposure(Minecraft mc, BlockPos pos, List<? extends Entity> shooters) {
		Vec3 eyes = Vec3.atBottomCenterOf(pos).add(0, mc.player.getEyeHeight(), 0);
		int visible = 0;
		for (Entity shooter : shooters) {
			if (mc.level.clip(new ClipContext(shooter.getEyePosition(), eyes, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, shooter)).getType() == HitResult.Type.MISS) {
				visible++;
			}
		}
		return visible;
	}
}
