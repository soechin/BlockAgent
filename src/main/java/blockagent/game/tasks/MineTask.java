package blockagent.game.tasks;

import baritone.api.BaritoneAPI;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/**
 * 找到指定方塊，走過去、挖、撿起來，直到數量達到(M2.8 的 mine)。
 * 進度以「自己挖掉的目標方塊數」計算。
 */
public final class MineTask extends Task {
	private final List<Block> blocks;
	private final Set<Block> blockSet;
	private final String label;
	private final int count;
	private final boolean search;
	private int mined;
	private int idleTicks;
	private boolean savedExplore;
	/** 挖到數量後去撿最後的掉落物(L6)：開始撿的 tick(-1 表示還在挖)和最後挖掉的位置。 */
	private long finishing = -1;
	private BlockPos lastMined;
	private BlockPos pickupKey;
	private static final int PICKUP_TICKS = 60;

	public MineTask(Agent agent, List<Block> blocks, String label, int count, boolean search) {
		super(agent, "mine");
		this.blocks = blocks;
		this.blockSet = Set.copyOf(blocks);
		this.label = label;
		this.count = count;
		this.search = search;
		if (search) this.timeoutTicks = Math.min(timeoutTicks, 5 * 60 * 20L + count * 60 * 20L);
	}

	@Override
	public String describe() {
		return "挖 " + label + " " + mined + "/" + count;
	}

	@Override
	public String progress() {
		return "block=" + label + " mined=" + mined + "/" + count;
	}

	@Override
	public boolean moves() {
		return true;
	}

	@Override
	protected void start(Minecraft mc) {
		var s = BaritoneAPI.getSettings();
		savedExplore = s.exploreForBlocks.value;
		s.exploreForBlocks.value = search;
		Bari.b().getMineProcess().mine(blocks.toArray(new Block[0]));
	}

	@Override
	public void onSelfBroke(BlockPos pos, BlockState before) {
		if (blockSet.contains(before.getBlock())) {
			mined++;
			lastMined = pos.immutable();
			markProgress();
		}
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mined >= count) {
			if (finishing < 0) {
				// 挖到數量就不再挖，但最後一格的掉落物還在地上：最多花 3 秒走過去撿(L6)
				finishing = agent.tick();
				Bari.b().getMineProcess().cancel();
				Bari.b().getMineProcess().onLostControl();
			}
			if (!pickUp(mc)) done(blockagent.game.Creative.noDropsNote(mc));
			return;
		}
		if (!Bari.b().getMineProcess().isActive()) {
			if (++idleTicks > 20) {
				int left = Moves.scan(mc, blocks, 48, 20, p -> agent.guard.canBreak(mc.level, p).allowed()).size();
				if (left > 0) {
					fail(ErrorCode.PATH_BLOCKED, left + " more " + label + " nearby but unreachable (terrain or others' blocks in the way; the rules forbid breaking those)");
				} else {
					fail(ErrorCode.NOT_FOUND, "no more " + label + (search ? " found" : " in loaded chunks (pass search=true to explore)"));
				}
			}
		} else {
			idleTicks = 0;
		}
	}

	/** 還有剛掉在最後那格附近的物品要撿就走過去，回傳 true；撿完或超過 3 秒回傳 false。 */
	private boolean pickUp(Minecraft mc) {
		if (lastMined == null || agent.tick() - finishing > PICKUP_TICKS || blockagent.game.Inv.freeSlots(mc.player) == 0) return false;
		var near = mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
			new net.minecraft.world.phys.AABB(lastMined).inflate(3), e -> e.isAlive() && e.tickCount < 20 * 10);
		if (near.isEmpty()) return false;
		var closest = near.stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).orElseThrow();
		BlockPos at = closest.blockPosition();
		if (!at.equals(pickupKey)) {
			pickupKey = at;
			path(new baritone.api.pathing.goals.GoalNear(at, 0));
		}
		return true;
	}

	@Override
	protected void onStop(Minecraft mc) {
		try {
			Bari.stopAll();
		} finally {
			BaritoneAPI.getSettings().exploreForBlocks.value = savedExplore;
		}
	}

	@Override
	protected void onShiftTimers(long pausedTicks) {
		if (finishing >= 0) finishing += pausedTicks;
	}

	@Override
	protected void onResume(Minecraft mc) {
		// onSuspend(= onStop)把探索設定還原了，要重新套用(H10)
		BaritoneAPI.getSettings().exploreForBlocks.value = search;
		if (finishing >= 0) {
			pickupKey = null;
			return;
		}
		Bari.b().getMineProcess().mine(blocks.toArray(new Block[0]));
	}
}
