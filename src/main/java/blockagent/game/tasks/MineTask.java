package blockagent.game.tasks;

import baritone.api.BaritoneAPI;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
	private final int radius;
	private final boolean search;
	/** Baritone 尋路執行緒會讀：開工時的起點座標(不變物件)，用來在 search=false 時把開採目標限制在 radius 內。 */
	private volatile BlockPos origin;
	private int mined;
	private int idleTicks;
	private boolean savedExplore;
	/** 挖到數量後去撿最後的掉落物(L6)：開始撿的 tick(-1 表示還在挖)和最後挖掉的位置。 */
	private long finishing = -1;
	private boolean sawFinalDrop;
	private BlockPos lastMined;
	private BlockPos pickupKey;
	private static final int PICKUP_TICKS = 60;
	/** 採礦啟動著卻一直沒有可走的路、原地沒挖到東西，超過這麼久就當作受困(R8)。 */
	static final int TRAPPED_TICKS = 160;
	private int trappedTicks;
	private BlockPos trappedAnchor;
	private int trappedMined;

	/** 純邏輯：採礦啟動中卻沒有路徑、沒離開原地、沒挖到新方塊時累計，否則歸零(R8)。 */
	static int nextTrappedTicks(int currentTicks, boolean mineActive, boolean hasPath, boolean movedAway, boolean minedChanged) {
		if (!mineActive || hasPath || movedAway || minedChanged) return 0;
		return currentTicks + 1;
	}


	private String blockersAround(Minecraft mc, BlockPos here) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (BlockPos p : BlockPos.betweenClosed(here.offset(-1, 0, -1), here.offset(1, 2, 1))) {
			BlockState st = mc.level.getBlockState(p);
			if (st.isAir() || agent.guard.canPathBreak(p.getX(), p.getY(), p.getZ(), st)) continue;
			out.add(blockagent.game.RuleGuard.shortId(st) + "(" + agent.observation.ownerLabel(p, st) + ")");
			if (out.size() >= 4) break;
		}
		return String.join(",", out);
	}

	private String miningPosition(Minecraft mc) {
		BlockPos here = mc.player.blockPosition();
		String blockers = blockersAround(mc, here);
		return "pos=" + blockagent.game.Fmt.pos(here) + (blockers.isEmpty() ? "" : " nearby_protected=" + blockers);
	}

	public MineTask(Agent agent, List<Block> blocks, String label, int count, int radius, boolean search) {
		super(agent, "mine");
		this.blocks = blocks;
		this.blockSet = Set.copyOf(blocks);
		this.label = label;
		this.count = count;
		this.radius = radius;
		this.search = search;
		if (search) this.timeoutTicks = Math.min(timeoutTicks, 5 * 60 * 20L + count * 60 * 20L);
	}

	/** 目標座標是否在起點半徑內(純邏輯，供單元測試直接驗證)。 */
	static boolean withinRadius(int ox, int oy, int oz, int x, int y, int z, int radius) {
		long dx = (long) x - ox;
		long dy = (long) y - oy;
		long dz = (long) z - oz;
		return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
	}

	/** Baritone 尋路執行緒會讀：這格是不是本次 mine 明確要求的目標方塊(執行緒安全)。 */
	public boolean isMineTarget(BlockState state) {
		return blockSet.contains(state.getBlock());
	}

	/**
	 * Baritone 尋路執行緒會讀：search=false 時，超出起點 radius 的目標方塊不納入開採，
	 * 避免挖淺層礦或拔草時一路鑽進深洞或遠征數十格。
	 */
	public boolean rejectsMineTarget(int x, int y, int z, BlockState current) {
		BlockPos o = origin;
		if (search || o == null || !blockSet.contains(current.getBlock())) return false;
		return !withinRadius(o.getX(), o.getY(), o.getZ(), x, y, z, radius);
	}

	@Override
	public String describe() {
		if (blocks.isEmpty()) return "挖 " + label;
		// label 本身是方塊 ID 時用那個方塊的名稱；是群組(例如 log)時顯示「第一個方塊名等」
		String id = label.trim().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
		for (Block b : blocks) {
			if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().equals(id)) return "挖 " + name(b);
		}
		return "挖 " + name(blocks.getFirst()) + (blocks.size() > 1 ? "等" : "");
	}

	@Override
	public Progress hudProgress() {
		return new Progress(mined, count);
	}

	@Override
	public String progress() {
		return "block=" + label + " mined=" + mined + "/" + count;
	}

	@Override
	public boolean moves() {
		return true;
	}

	private boolean ensureTool(Minecraft mc) {
		if (blockagent.game.Creative.isCreative(mc)) return true;
		BlockState st = blocks.getFirst().defaultBlockState();
		int slot = blockagent.game.Inv.ensureHotbarTool(mc, st);
		if (st.requiresCorrectToolForDrops() && slot < 0
			&& blockagent.game.Inv.find(mc.player, s -> s.isCorrectToolForDrops(st)) < 0) {
			fail(ErrorCode.MISSING_TOOL, "no suitable tool left for " + label + " (tool broke or missing)", "craft a suitable tool first");
			return false;
		}
		return true;
	}

	@Override
	protected void start(Minecraft mc) {
		var s = BaritoneAPI.getSettings();
		savedExplore = s.exploreForBlocks.value;
		origin = mc.player.blockPosition().immutable();
		trappedTicks = 0;
		trappedAnchor = null;
		trappedMined = mined;
		if (!ensureTool(mc)) return;
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
				sawFinalDrop = false;
				Bari.b().getMineProcess().cancel();
				Bari.b().getMineProcess().onLostControl();
			}
			if (!pickUp(mc)) done(blockagent.game.Creative.noDropsNote(mc));
			return;
		}
		if (agent.tick() % 10 == 0 && !ensureTool(mc)) return;
		if (!Bari.b().getMineProcess().isActive()) {
			if (++idleTicks > 20) {
				BlockPos o = origin;
				int scanRadius = search ? 48 : radius;
				java.util.function.Predicate<BlockPos> inRange = p -> search || o == null
					|| withinRadius(o.getX(), o.getY(), o.getZ(), p.getX(), p.getY(), p.getZ(), radius);
				List<BlockPos> allowedTargets = Moves.scan(mc, blocks, scanRadius, 1,
					p -> inRange.test(p) && agent.guard.canBreak(mc.level, p).allowed());
				if (!allowedTargets.isEmpty()) {
					reportUnreachable(mc, allowedTargets.getFirst());
					return;
				}
				var nearby = Moves.scan(mc, blocks, scanRadius, 1, inRange);
				if (nearby.isEmpty()) {
					fail(ErrorCode.NOT_FOUND, "no more " + label + (search ? " found" : " within radius " + radius + " (pass search=true to explore)"));
				} else {
					var blocked = agent.guard.canBreak(mc.level, nearby.getFirst());
					fail(blocked.code(), blocked.reason(), blocked.hint());
				}
			}
		} else {
			idleTicks = 0;
			BlockPos here = mc.player.blockPosition();
			boolean movedAway = trappedAnchor == null || here.distSqr(trappedAnchor) > 4;
			boolean active = Bari.b().getPathingBehavior().isPathing() || mc.gameMode.isDestroying();
			trappedTicks = nextTrappedTicks(trappedTicks, true, active, movedAway, mined != trappedMined);
			if (trappedTicks == 0) {
				trappedAnchor = here.immutable();
				trappedMined = mined;
			} else if (trappedTicks >= TRAPPED_TICKS) {
				fail(ErrorCode.PATH_BLOCKED, "trapped with no path to " + label + "; " + miningPosition(mc),
					"inspect_area around you, or goto an open exit first");
			}
		}
	}

	private void reportUnreachable(Minecraft mc, BlockPos target) {
		boolean exposed = false;
		int protectedSides = 0;
		for (Direction d : Direction.values()) {
			BlockPos side = target.relative(d);
			BlockState state = mc.level.getBlockState(side);
			if (state.getCollisionShape(mc.level, side).isEmpty()) exposed = true;
			else if (!agent.guard.canPathBreak(side.getX(), side.getY(), side.getZ(), state)) protectedSides++;
		}
		String hint = exposed ? "inspect the route, or goto a clearer approach and retry"
			: protectedSides == 0 ? "use inspect_area and break_block to expose the vein through nearby natural blocks"
			: protectedSides == Direction.values().length ? "the vein is surrounded by protected blocks; choose another deposit"
			: "use inspect_area and approach the vein from a side without protected blocks";
		fail(ErrorCode.PATH_BLOCKED, label + " is unreachable for Baritone; nearest=" + blockagent.game.Fmt.pos(target)
			+ " exposed=" + exposed + " protected_sides=" + protectedSides + "; " + miningPosition(mc), hint);
	}

	/** 還有剛掉在最後那格附近的物品要撿就走過去，回傳 true；撿完或超過 3 秒回傳 false。 */
	private boolean pickUp(Minecraft mc) {
		if (lastMined == null || agent.tick() - finishing > PICKUP_TICKS || blockagent.game.Inv.freeSlots(mc.player) == 0) return false;
		var near = mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
			new net.minecraft.world.phys.AABB(lastMined).inflate(3), e -> e.isAlive() && e.tickCount < 20 * 10);
		if (near.isEmpty()) return !sawFinalDrop && agent.tick() - finishing < 6;
		sawFinalDrop = true;
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
		trappedTicks = 0;
		trappedAnchor = null;
		trappedMined = mined;
		if (finishing >= 0) {
			pickupKey = null;
			return;
		}
		if (!ensureTool(mc)) return;
		Bari.b().getMineProcess().mine(blocks.toArray(new Block[0]));
	}
}
