package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalBlock;
import blockagent.core.ItemMatcher;
import blockagent.core.TunnelPlanner;
import blockagent.core.TunnelPlanner.Dir;
import blockagent.core.TunnelPlanner.Mode;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Creative;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import blockagent.game.TaggedBlocks;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系統性地下挖礦任務(docs/SPEC.md S4.2j)：
 * 以逐格步進方式開 {@code stairs}(1×3 階梯下挖)或 {@code straight}(1×2 水平礦道)，
 * 每步動鎬前檢查岩漿、水流、深坑、受保護方塊與 {@code stop_on} 礦脈，依 {@code torch_every} 自動插火把，
 * 並一律記錄沿途露出的礦脈清單。
 */
public final class DigTunnelTask extends Task {
	private enum Phase {RETURN_ANCHOR, PLAN_STEP, DIG_CELL, MOVE_NEXT, PLACE_TORCH}

	private final Mode mode;
	private final Dir dir;
	private final int targetSteps;
	private final int torchEvery;
	private final Set<Block> stopOnBlocks;
	private final ItemMatcher toolMatcher;

	private Phase phase = Phase.PLAN_STEP;
	private BlockPos startPos;
	private BlockPos anchor;
	private BlockPos nextStand;
	private List<BlockPos> sliceCells = List.of();
	private int sliceIndex;
	private Block breakingOriginal;
	private Direction breakingFace;
	private boolean breakingStarted;
	private int breakTicks;
	private int moveTicks;
	private boolean moveFallbackBari;
	private int dugSteps;
	private int stepsSinceTorch;
	private int torchesPlaced;
	private int torchWaitTicks;
	private boolean torchPlacedAttempt;

	/** 沿途露出礦脈紀錄：key=礦種短名，value=已記錄的礦脈座標集合(去重並保留順序)。 */
	private final Map<String, Set<BlockPos>> seenOres = new LinkedHashMap<>();

	public DigTunnelTask(Agent agent, Mode mode, Dir dir, int targetSteps, int torchEvery,
						 Set<Block> stopOnBlocks, ItemMatcher toolMatcher) {
		super(agent, "dig_tunnel");
		this.mode = mode;
		this.dir = dir;
		this.targetSteps = targetSteps;
		this.torchEvery = torchEvery;
		this.stopOnBlocks = stopOnBlocks == null ? Set.of() : Set.copyOf(stopOnBlocks);
		this.toolMatcher = toolMatcher == null ? ItemMatcher.ANY : toolMatcher;
		this.timeoutTicks = Math.max(20 * 120L, targetSteps * 20L * 15L);
	}

	@Override
	public String describe() {
		return mode == Mode.STAIRS ? "階梯下挖" : "水平挖礦道";
	}

	@Override
	public Progress hudProgress() {
		return new Progress(dugSteps, targetSteps);
	}

	@Override
	public String progress() {
		Kv kv = Kv.of()
			.put("mode", mode == Mode.STAIRS ? "stairs" : "straight")
			.put("dug", dugSteps + "/" + targetSteps)
			.put("torches_placed", torchesPlaced);
		if (anchor != null) kv.put("pos", Fmt.pos(anchor));
		String ores = formatSeenOres();
		if (!ores.isEmpty()) kv.put("ores", ores);
		return kv.toString();
	}

	@Override
	public boolean moves() {
		return phase == Phase.MOVE_NEXT || phase == Phase.RETURN_ANCHOR;
	}

	@Override
	protected void start(Minecraft mc) {
		startPos = mc.player.blockPosition().immutable();
		anchor = startPos;
		stepsSinceTorch = 0;
		phase = Phase.PLAN_STEP;
	}

	@Override
	protected void tick(Minecraft mc) {
		switch (phase) {
			case RETURN_ANCHOR -> tickReturnAnchor(mc);
			case PLAN_STEP -> tickPlanStep(mc);
			case DIG_CELL -> tickDigCell(mc);
			case MOVE_NEXT -> tickMoveNext(mc);
			case PLACE_TORCH -> tickPlaceTorch(mc);
		}
	}

	private void tickReturnAnchor(Minecraft mc) {
		if (mc.player.blockPosition().distManhattan(anchor) <= 1 && !Bari.busy()) {
			anchor = mc.player.blockPosition().immutable();
			phase = Phase.PLAN_STEP;
			return;
		}
		if (mc.player.blockPosition().equals(anchor)) {
			Bari.stopAll();
			phase = Phase.PLAN_STEP;
			return;
		}
		if (!Bari.busy() && ++moveTicks > 40) {
			fail(ErrorCode.PATH_BLOCKED, "could not return to tunnel position " + Fmt.pos(anchor) + "; " + progress());
		}
	}

	private void tickPlanStep(Minecraft mc) {
		if (dugSteps >= targetSteps) {
			done(finishNote("completed", null));
			return;
		}
		if (!Creative.isCreative(mc) && Inv.freeSlots(mc.player) == 0) {
			fail(ErrorCode.MISSING_MATERIALS, "inventory is full at " + Fmt.pos(anchor),
				"store items with chest action=put, or drop junk");
			return;
		}
		TunnelPlanner.Pos curP = new TunnelPlanner.Pos(anchor.getX(), anchor.getY(), anchor.getZ());
		TunnelPlanner.Pos nextP = TunnelPlanner.nextStand(curP, mode, dir);
		nextStand = toBlockPos(nextP);
		List<TunnelPlanner.Pos> slice = TunnelPlanner.mineSlice(nextP, mode);
		List<TunnelPlanner.Pos> adj = TunnelPlanner.adjacentCells(slice, curP);
		List<TunnelPlanner.Pos> waterCells = TunnelPlanner.waterWatchCells(slice, curP);

		// 1. 檢查待挖格本身：世界邊界、基岩/不可破壞、守門員保護、岩漿/水、stop_on 礦、工具
		List<BlockPos> toDig = new ArrayList<>(slice.size());
		for (TunnelPlanner.Pos p : slice) {
			BlockPos bp = toBlockPos(p);
			if (!mc.level.isLoaded(bp) || bp.getY() < mc.level.getMinY() || bp.getY() >= mc.level.getMaxY()) {
				fail(ErrorCode.PATH_BLOCKED, "reached world limit or unloaded chunk at " + Fmt.pos(bp));
				return;
			}
			BlockState st = mc.level.getBlockState(bp);
			if (isLava(st)) {
				done(finishNote("hazard", "hazard=lava at=" + Fmt.pos(bp)));
				return;
			}
			if (isWater(st)) {
				done(finishNote("hazard", "hazard=water at=" + Fmt.pos(bp)));
				return;
			}
			recordIfOre(bp, st);
			if (!stopOnBlocks.isEmpty() && stopOnBlocks.contains(st.getBlock())) {
				done(finishNote("ore", "ore=" + RuleGuard.shortId(st) + " at=" + Fmt.pos(bp)));
				return;
			}
			if (st.isAir() || st.canBeReplaced()) continue;
			if (st.getDestroySpeed(mc.level, bp) < 0) {
				fail(ErrorCode.PATH_BLOCKED, "hit unbreakable block " + RuleGuard.shortId(st) + " at " + Fmt.pos(bp));
				return;
			}
			RuleGuard.Verdict v = agent.guard.canBreak(mc.level, bp);
			if (!v.allowed()) {
				fail(v.code(), v.reason(), v.hint());
				return;
			}
			if (!Creative.isCreative(mc) && !hasMiningTool(mc, st)) {
				String matchMsg = toolMatcher.isAny() ? "" : " matching " + toolMatcher.raw();
				fail(ErrorCode.MISSING_TOOL, "no suitable tool" + matchMsg + " for " + RuleGuard.shortId(st) + " at " + Fmt.pos(bp),
					"craft or equip a suitable pickaxe");
				return;
			}
			toDig.add(bp);
		}

		// 2. 掃描相鄰格：岩漿、水流、沿途礦脈、stop_on 礦
		for (TunnelPlanner.Pos p : adj) {
			BlockPos bp = toBlockPos(p);
			if (!mc.level.isLoaded(bp)) continue;
			BlockState st = mc.level.getBlockState(bp);
			if (isLava(st)) {
				done(finishNote("hazard", "hazard=lava at=" + Fmt.pos(bp)));
				return;
			}
			recordIfOre(bp, st);
			if (!stopOnBlocks.isEmpty() && stopOnBlocks.contains(st.getBlock())) {
				done(finishNote("ore", "ore=" + RuleGuard.shortId(st) + " at=" + Fmt.pos(bp)));
				return;
			}
		}
		for (TunnelPlanner.Pos p : waterCells) {
			BlockPos bp = toBlockPos(p);
			if (!mc.level.isLoaded(bp)) continue;
			if (isWater(mc.level.getBlockState(bp))) {
				done(finishNote("hazard", "hazard=water at=" + Fmt.pos(bp)));
				return;
			}
		}

		// 3. 檢查目標落腳處下方是否為空洞／深坑(落差 > 2 格或正下方為岩漿)
		boolean[] solidBelow = new boolean[TunnelPlanner.MAX_SAFE_DROP + 1];
		for (int d = 0; d < solidBelow.length; d++) {
			BlockPos under = nextStand.below(d + 1);
			BlockState ust = mc.level.getBlockState(under);
			if (isLava(ust)) {
				done(finishNote("hazard", "hazard=lava at=" + Fmt.pos(under)));
				return;
			}
			solidBelow[d] = !ust.getCollisionShape(mc.level, under).isEmpty();
		}
		if (TunnelPlanner.isUnsafeDrop(TunnelPlanner.dropDepth(solidBelow))) {
			done(finishNote("drop", "drop_at=" + Fmt.pos(nextStand.below())));
			return;
		}
		// 若落腳處正下方 1 格為空(落差 1–2 格)，先不盲目踩空導致偏離隧道高度，停下交由 AI 補地或改向
		if (!solidBelow[0]) {
			done(finishNote("drop", "gap_at=" + Fmt.pos(nextStand.below())));
			return;
		}

		sliceCells = toDig;
		sliceIndex = 0;
		breakingStarted = false;
		if (sliceCells.isEmpty()) {
			beginMoveNext();
		} else {
			phase = Phase.DIG_CELL;
		}
	}

	private void tickDigCell(Minecraft mc) {
		if (sliceIndex >= sliceCells.size()) {
			beginMoveNext();
			return;
		}
		BlockPos bp = sliceCells.get(sliceIndex);
		BlockState st = mc.level.getBlockState(bp);
		if (st.isAir() || st.canBeReplaced()) {
			mc.gameMode.stopDestroyBlock();
			breakingStarted = false;
			sliceIndex++;
			markProgress();
			return;
		}
		if (!Moves.canReach(mc.player, bp)) {
			fail(ErrorCode.PATH_BLOCKED, "block " + Fmt.pos(bp) + " out of reach from " + Fmt.pos(mc.player.blockPosition()));
			return;
		}
		if (!breakingStarted || st.getBlock() != breakingOriginal) {
			if (!Creative.isCreative(mc) && !equipToolFor(mc, st)) {
				fail(ErrorCode.MISSING_TOOL, "no suitable tool left for " + RuleGuard.shortId(st), "craft or equip a pickaxe");
				return;
			}
			breakingOriginal = st.getBlock();
			breakingFace = Moves.faceToward(mc.player, bp);
			Moves.lookAt(mc.player, Moves.hit(bp, breakingFace).getLocation());
			if (!mc.gameMode.startDestroyBlock(bp, breakingFace)) {
				fail(ErrorCode.RULE_PROTECTED_BLOCK, "could not break " + Fmt.pos(bp) + " (protected or blocked)");
				return;
			}
			breakingStarted = true;
			breakTicks = 0;
			BlockState after = mc.level.getBlockState(bp);
			if (after.isAir() || after.canBeReplaced()) {
				breakingStarted = false;
				sliceIndex++;
				markProgress();
			}
			return;
		}
		if (++breakTicks > 20 * 25) {
			fail(ErrorCode.TIMEOUT, "timed out breaking " + RuleGuard.shortId(st) + " at " + Fmt.pos(bp));
			return;
		}
		Moves.lookAt(mc.player, Moves.hit(bp, breakingFace).getLocation());
		if (!mc.gameMode.continueDestroyBlock(bp, breakingFace)) {
			fail(ErrorCode.RULE_PROTECTED_BLOCK, "breaking aborted at " + Fmt.pos(bp));
			return;
		}
		mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
	}

	private void beginMoveNext() {
		moveTicks = 0;
		moveFallbackBari = false;
		phase = Phase.MOVE_NEXT;
	}

	private void tickMoveNext(Minecraft mc) {
		Vec3 center = Vec3.atBottomCenterOf(nextStand);
		double dx = center.x - mc.player.getX();
		double dz = center.z - mc.player.getZ();
		double horiz = Math.hypot(dx, dz);
		if (mc.player.blockPosition().equals(nextStand) && horiz <= 0.38) {
			mc.options.keyUp.setDown(false);
			if (moveFallbackBari) Bari.stopAll();
			anchor = nextStand;
			dugSteps++;
			stepsSinceTorch++;
			markProgress();
			int light = mc.level.getBrightness(LightLayer.BLOCK, anchor);
			if (TunnelPlanner.shouldPlaceTorch(torchEvery, stepsSinceTorch, light)) {
				if (!Creative.isCreative(mc) && Inv.countWithOffhand(mc.player, Items.TORCH) == 0) {
					fail(ErrorCode.MISSING_MATERIALS,
						"out of torches after " + dugSteps + "/" + targetSteps + " steps at " + Fmt.pos(anchor),
						"craft torches (coal or charcoal from smelting logs), or pass torch_every=0 for a short tunnel (max 12)");
					return;
				}
				torchWaitTicks = 0;
				torchPlacedAttempt = false;
				phase = Phase.PLACE_TORCH;
			} else {
				phase = Phase.PLAN_STEP;
			}
			return;
		}
		moveTicks++;
		if (!moveFallbackBari) {
			Moves.lookAt(mc.player, new Vec3(center.x, mc.player.getEyeY(), center.z));
			mc.options.keySprint.setDown(false);
			mc.options.keyUp.setDown(true);
			if (moveTicks > 40) {
				mc.options.keyUp.setDown(false);
				moveFallbackBari = true;
				path(new GoalBlock(nextStand));
			}
		} else if (!Bari.busy() && moveTicks > 90) {
			fail(ErrorCode.PATH_BLOCKED, "could not step into " + Fmt.pos(nextStand));
		}
	}

	private void tickPlaceTorch(Minecraft mc) {
		BlockState cur = mc.level.getBlockState(anchor);
		if (cur.is(Blocks.TORCH) || cur.is(Blocks.WALL_TORCH)) {
			torchesPlaced++;
			stepsSinceTorch = 0;
			phase = Phase.PLAN_STEP;
			return;
		}
		if (!torchPlacedAttempt) {
			torchPlacedAttempt = true;
			if (!cur.isAir() && !cur.canBeReplaced()) {
				stepsSinceTorch = 0;
				phase = Phase.PLAN_STEP;
				return;
			}
			BlockPos floor = anchor.below();
			if (!mc.level.getBlockState(floor).isFaceSturdy(mc.level, floor, Direction.UP)
				|| !agent.guard.canPlaceInto(mc.level, anchor).allowed()) {
				stepsSinceTorch = 0;
				phase = Phase.PLAN_STEP;
				return;
			}
			if (!Inv.selectInHandWithOffhand(mc, s -> s.is(Items.TORCH))) {
				fail(ErrorCode.MISSING_MATERIALS,
					"out of torches after " + dugSteps + "/" + targetSteps + " steps at " + Fmt.pos(anchor),
					"craft torches (coal or charcoal from smelting logs), or pass torch_every=0 for a short tunnel (max 12)");
				return;
			}
			Moves.useOn(mc, floor, Direction.UP);
			return;
		}
		if (++torchWaitTicks >= 6) {
			if (mc.level.getBlockState(anchor).is(Blocks.TORCH) || mc.level.getBlockState(anchor).is(Blocks.WALL_TORCH)) {
				torchesPlaced++;
			}
			stepsSinceTorch = 0;
			phase = Phase.PLAN_STEP;
		}
	}

	private boolean hasMiningTool(Minecraft mc, BlockState st) {
		if (!toolMatcher.isAny()) return Inv.findBestMiningTool(mc.player, st, toolMatcher, true) >= 0;
		if (!st.requiresCorrectToolForDrops()) return true;
		return Inv.findBestMiningTool(mc.player, st, ItemMatcher.ANY, true) >= 0;
	}

	private boolean equipToolFor(Minecraft mc, BlockState st) {
		if (!toolMatcher.isAny()) {
			int idx = Inv.findBestMiningTool(mc.player, st, toolMatcher, true);
			if (idx < 0) return false;
			if (idx == Inv.OFFHAND_SLOT) {
				if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
				Inv.swapToOffhand(mc, mc.player.getInventory().getSelectedSlot());
				return Inv.canMineWithTool(mc.player.getMainHandItem(), st, toolMatcher);
			}
			return Inv.selectSlotInHand(mc, idx);
		}
		BreakTask.equipBestTool(mc, st);
		return !st.requiresCorrectToolForDrops() || mc.player.getMainHandItem().isCorrectToolForDrops(st);
	}

	private void recordIfOre(BlockPos pos, BlockState st) {
		if (!TaggedBlocks.isOre(st)) return;
		seenOres.computeIfAbsent(RuleGuard.shortId(st), _ -> new LinkedHashSet<>()).add(pos.immutable());
	}

	private String formatSeenOres() {
		if (seenOres.isEmpty()) return "";
		List<String> parts = new ArrayList<>();
		for (var e : seenOres.entrySet()) {
			BlockPos nearest = null;
			double best = Double.MAX_VALUE;
			for (BlockPos p : e.getValue()) {
				double d = anchor == null ? 0 : p.distSqr(anchor);
				if (d < best) {
					best = d;
					nearest = p;
				}
			}
			parts.add(e.getKey() + ":" + e.getValue().size() + (nearest == null ? "" : "@" + Fmt.pos(nearest)));
		}
		return String.join(";", parts);
	}

	private String finishNote(String stopped, String extraRaw) {
		Kv kv = Kv.of().put("stopped", stopped);
		if (extraRaw != null && !extraRaw.isEmpty()) kv.raw(extraRaw);
		if (torchEvery == 0 && dugSteps > 0 && startPos != null && anchor != null) {
			kv.put("unlit", Fmt.pos(startPos) + ".." + Fmt.pos(anchor));
		}
		return kv.toString();
	}

	private static BlockPos toBlockPos(TunnelPlanner.Pos p) {
		return new BlockPos(p.x(), p.y(), p.z());
	}

	private static boolean isLava(BlockState st) {
		return st.getFluidState().is(Fluids.LAVA) || st.getFluidState().is(Fluids.FLOWING_LAVA);
	}

	private static boolean isWater(BlockState st) {
		return st.getFluidState().is(Fluids.WATER) || st.getFluidState().is(Fluids.FLOWING_WATER);
	}

	@Override
	protected void onStop(Minecraft mc) {
		if (mc.options != null) mc.options.keyUp.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		Bari.stopAll();
	}

	@Override
	protected void onResume(Minecraft mc) {
		breakingStarted = false;
		if (anchor != null && mc.player.blockPosition().distManhattan(anchor) > 1) {
			moveTicks = 0;
			phase = Phase.RETURN_ANCHOR;
			path(new GoalBlock(anchor));
		} else {
			phase = Phase.PLAN_STEP;
		}
	}
}
