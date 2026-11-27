package blockagent.game.tasks;

import blockagent.game.Bari;
import blockagent.game.BuildCommands;
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

/**
 * 淺水上岸輔助(R25、脫困反射共用)：從腳底有支撐的一格深淺水，BFS 找最多 8 步可走到乾地的路線並以方向鍵走回岸上。
 * 僅在 client thread 使用。
 */
public final class ShoreExit {
	public static final int MAX_STEPS = 8;

	public enum Step {WALKING, ARRIVED, FAILED}

	private record Node(BlockPos pos, Node previous, int steps) {
	}

	private final ArrayDeque<BlockPos> steps = new ArrayDeque<>();
	private ClientLevel level;
	private BlockPos from;
	private long deadline;
	private boolean requireSky;
	private Comparator<Direction> dirOrder;

	public boolean active() {
		return !steps.isEmpty();
	}

	/** 嘗試從目前位置規劃淺水上岸路線；成功時停下 Baritone 並回傳 true。 */
	public boolean start(Minecraft mc, long deadlineTick, Comparator<Direction> directionOrder, boolean requireSky) {
		var player = mc.player;
		if (player == null || mc.level == null) return false;
		BlockPos start = player.blockPosition();
		if (player.isEyeInFluid(FluidTags.WATER)) return false;
		// 浮在 1 格淺水表面時 blockPosition 可能暫時落在水面上方的空氣格
		if (!shallowWater(mc.level, start, requireSky)) {
			if (mc.level.getBlockState(start).isAir() && shallowWater(mc.level, start.below(), requireSky)) {
				start = start.below();
			} else {
				return false;
			}
		}
		this.requireSky = requireSky;
		this.dirOrder = directionOrder;
		if (!planRoute(mc.level, start)) return false;
		Bari.stopAll();
		level = mc.level;
		deadline = deadlineTick;
		return true;
	}

	/** 推進一步淺水上岸；抵達乾地回 ARRIVED，逾時或無路回 FAILED。 */
	public Step tick(Minecraft mc, long nowTick, boolean userLooking, Runnable onStepProgress) {
		var player = mc.player;
		if (player == null || mc.level != level || player.isEyeInFluid(FluidTags.WATER) || nowTick >= deadline) {
			stop(mc);
			return Step.FAILED;
		}
		BlockPos here = player.blockPosition();
		if (player.onGround() && dryGround(mc.level, here, requireSky) && !player.isInWater()) {
			stop(mc);
			return Step.ARRIVED;
		}
		BlockPos next = steps.getFirst();
		boolean last = steps.size() == 1;
		boolean rising = next.getY() > from.getY();
		boolean onRoute = here.atY(from.getY()).equals(from) || here.atY(next.getY()).equals(next);
		if (!onRoute || !shallowWater(mc.level, from, requireSky)
			|| !(last ? dryGround(mc.level, next, requireSky) : shallowWater(mc.level, next, requireSky))
			|| (rising && !mc.level.getBlockState(from.above(2)).isAir())) {
			// 水流可能把玩家推進相鄰格。只從仍可安全踩地的淺水重找路，沿用原本時限。
			releaseKeys(mc);
			if (!shallowWater(mc.level, here, requireSky) || !planRoute(mc.level, here)) {
				stop(mc);
				return Step.FAILED;
			}
			return Step.WALKING;
		}
		var center = Vec3.atBottomCenterOf(next);
		if (here.equals(next) && player.position().subtract(center).horizontalDistanceSqr() < 0.09) {
			releaseKeys(mc);
			if (!last) {
				steps.removeFirst();
				from = next;
				if (onStepProgress != null) onStepProgress.run();
			}
			return Step.WALKING;
		}
		if (userLooking) {
			releaseKeys(mc);
			return Step.WALKING;
		}
		Moves.lookAt(player, new Vec3(center.x, player.getEyeY(), center.z));
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(rising);
		return Step.WALKING;
	}

	public void shiftDeadline(long pausedTicks) {
		if (deadline > 0) deadline += pausedTicks;
	}

	public void stop(Minecraft mc) {
		if (!steps.isEmpty()) {
			releaseKeys(mc);
			steps.clear();
		}
		level = null;
		from = null;
	}

	private boolean planRoute(ClientLevel world, BlockPos start) {
		Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
		if (dirOrder != null) Arrays.sort(directions, dirOrder);
		var pending = new ArrayDeque<Node>();
		var visited = new HashSet<BlockPos>();
		pending.add(new Node(start, null, 0));
		visited.add(start);
		while (!pending.isEmpty()) {
			Node here = pending.removeFirst();
			for (Direction direction : directions) {
				BlockPos next = here.pos().relative(direction);
				if (dryGround(world, next, requireSky)) {
					setRoute(start, next, here);
					return true;
				}
				if (dryGround(world, next.above(), requireSky) && world.getBlockState(here.pos().above(2)).isAir()) {
					setRoute(start, next.above(), here);
					return true;
				}
				if (here.steps() + 1 < MAX_STEPS && visited.add(next) && shallowWater(world, next, requireSky)) {
					pending.addLast(new Node(next, here, here.steps() + 1));
				}
			}
		}
		return false;
	}

	private void setRoute(BlockPos start, BlockPos shore, Node lastWater) {
		from = start;
		steps.clear();
		steps.add(shore);
		for (Node at = lastWater; at.previous() != null; at = at.previous()) {
			steps.addFirst(at.pos());
		}
	}

	private static boolean openWithFooting(ClientLevel world, BlockPos feet, boolean requireSky) {
		if (!BuildCommands.validTarget(world, feet) || (requireSky && !world.canSeeSky(feet.above()))
			|| !world.getBlockState(feet.above()).isAir()) return false;
		BlockPos below = feet.below();
		var floor = world.getBlockState(below);
		return floor.getFluidState().isEmpty() && !floor.is(Blocks.MAGMA_BLOCK)
			&& floor.isFaceSturdy(world, below, Direction.UP);
	}

	private static boolean shallowWater(ClientLevel world, BlockPos feet, boolean requireSky) {
		return openWithFooting(world, feet, requireSky) && world.getBlockState(feet).is(Blocks.WATER);
	}

	private static boolean dryGround(ClientLevel world, BlockPos feet, boolean requireSky) {
		return openWithFooting(world, feet, requireSky) && world.getBlockState(feet).isAir();
	}

	private static void releaseKeys(Minecraft mc) {
		if (mc != null && mc.options != null) {
			mc.options.keyUp.setDown(false);
			mc.options.keyJump.setDown(false);
		}
	}
}
