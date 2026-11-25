package blockagent.game.tasks;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.BuildTarget;
import blockagent.game.Creative;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 藍圖的門(S4.8a)：其他格子都處理完之後，一扇一扇用一般放置裝上。
 * 門的朝向跟著玩家面對的方向，所以站在門朝向的反方向(屋內)那一格，面向門口放下去。
 */
final class DoorPlacer {
	/** 走到放門的位置最多等 10 秒。 */
	private static final int WALK_TICKS = 20 * 10;
	/** 轉頭之後等伺服器收到新的朝向再放(門的朝向是伺服器照玩家的朝向決定的)。 */
	private static final int TURN_TICKS = 3;
	private static final int CONFIRM_TICKS = 40;

	private final Agent agent;
	private final Deque<Map.Entry<BlockPos, BuildTarget>> queue;
	private final Set<BlockPos> cells;
	private final List<String> failed = new ArrayList<>();
	private int placed;
	private int wait;
	private int turned;
	private boolean used;

	DoorPlacer(Agent agent, Map<BlockPos, BuildTarget> doors) {
		this.agent = agent;
		this.queue = new ArrayDeque<>(doors.entrySet());
		Set<BlockPos> all = new java.util.HashSet<>();
		for (BlockPos pos : doors.keySet()) {
			all.add(pos);
			all.add(pos.above());
		}
		this.cells = Set.copyOf(all);
	}

	boolean isEmpty() {
		return queue.isEmpty() && placed == 0 && failed.isEmpty();
	}

	/** 門的上下兩格：施工期間不能拿來墊腳，自己放下去的門也不算鷹架。 */
	boolean covers(BlockPos pos) {
		return cells.contains(pos);
	}

	/** 只有正在裝的那扇門的下半格，可以放那種門。 */
	boolean allows(BlockPos pos, Item item) {
		var entry = queue.peekFirst();
		return entry != null && entry.getKey().equals(pos) && entry.getValue().state().getBlock().asItem() == item;
	}

	int placed() {
		return placed;
	}

	/** 沒裝上的門，每個是 {@code x,y,z:原因}(S3.0b)。 */
	List<String> failed() {
		return failed;
	}

	/** 回傳 true 表示所有門都處理完了(裝好或放棄)。 */
	boolean tick(Minecraft mc, Consumer<Goal> path) {
		var entry = queue.peekFirst();
		if (entry == null) return true;
		BlockPos pos = entry.getKey();
		BuildTarget target = entry.getValue();
		BlockState current = mc.level.getBlockState(pos);
		if (target.matches(current)) {
			placed++;
			next();
			return queue.isEmpty();
		}
		if (used) {
			if (++wait > CONFIRM_TICKS) give(pos, current.isAir() ? "not_placed" : "placed_other:" + describe(current));
			return false;
		}
		if ((!current.isAir() && !current.canBeReplaced()) || !mc.level.getBlockState(pos.above()).canBeReplaced()) {
			give(pos, "blocked_by:" + RuleGuard.shortId(current.isAir() ? mc.level.getBlockState(pos.above()) : current));
			return false;
		}
		BlockPos below = pos.below();
		if (mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()) {
			give(pos, "no_support");
			return false;
		}
		Item item = target.state().getBlock().asItem();
		if (Inv.count(mc.player, item) == 0 && !Creative.takeIntoHotbar(mc, item, 1)) {
			give(pos, "no_item:" + Inv.id(item));
			return false;
		}
		Direction facing = target.state().getValue(DoorBlock.FACING);
		BlockPos stand = pos.relative(facing.getOpposite());
		var player = mc.player;
		// 要站在門的內側、面向門口，而且身體不能卡在門的兩格裡(站在門口上時原版放不下去，方向也算不準)
		Vec3 toDoor = Vec3.atCenterOf(pos).subtract(player.position());
		double ahead = toDoor.x * facing.getStepX() + toDoor.z * facing.getStepZ();
		double side = Math.abs(toDoor.x * facing.getStepZ()) + Math.abs(toDoor.z * facing.getStepX());
		boolean clear = !player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos).expandTowards(0, 1, 0));
		// 門朝哪邊看的是玩家面向最接近的水平方向：側向偏移比前後距離大時會裝歪 90°(H12)
		if (!(ahead >= 0.8 && side <= 1.0 && side < ahead && clear && Moves.canReach(player, below))) {
			if (wait++ % 40 == 0) path.accept(new GoalBlock(stand));
			if (wait > WALK_TICKS) give(pos, "unreachable");
			turned = 0;
			return false;
		}
		if (Bari.busy()) Bari.stopAll();
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		if (!Inv.selectInHand(mc, s -> s.is(item))) {
			give(pos, "cannot_hold:" + Inv.id(item));
			return false;
		}
		BlockHitResult hit = Moves.hit(below, Direction.UP);
		Moves.lookAt(player, hit.getLocation());
		if (turned++ < TURN_TICKS) return false;
		long mark = agent.guard.blockMark();
		var result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		if (!result.consumesAction()) {
			var blocked = agent.guard.blockedAfter(mark);
			give(pos, blocked != null ? "rule:" + blocked.code() : "obstructed");
			return false;
		}
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		used = true;
		wait = 0;
		return false;
	}

	private static String describe(BlockState state) {
		return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state);
	}

	private void give(BlockPos pos, String reason) {
		failed.add(blockagent.game.Fmt.pos(pos) + ":" + reason);
		agent.log.log(blockagent.game.ActionLog.Source.ai, "door_skip", pos.toShortString() + " " + reason);
		next();
	}

	private void next() {
		queue.pollFirst();
		wait = 0;
		turned = 0;
		used = false;
	}
}
