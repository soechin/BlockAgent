package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalNear;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * 種田：till_and_plant(鋤地+播種)與 harvest(收成自己種的作物並補種)。
 */
public final class FarmTask extends Task {
	public enum Mode {TILL_AND_PLANT, HARVEST}

	private enum Phase {NEXT, WALK, ACT, WAIT, VERIFY_PLANT, REPLANT, DONE}

	public static final Map<Block, Item> SEED_OF = Map.of(
		Blocks.WHEAT, Items.WHEAT_SEEDS, Blocks.CARROTS, Items.CARROT, Blocks.POTATOES, Items.POTATO, Blocks.BEETROOTS, Items.BEETROOT_SEEDS);
	public static final Map<Item, Block> CROP_OF = Map.of(
		Items.WHEAT_SEEDS, Blocks.WHEAT, Items.CARROT, Blocks.CARROTS, Items.POTATO, Blocks.POTATOES, Items.BEETROOT_SEEDS, Blocks.BEETROOTS,
		Items.MELON_SEEDS, Blocks.MELON_STEM, Items.PUMPKIN_SEEDS, Blocks.PUMPKIN_STEM);

	/** 成熟藤蔓掛果時換成另一種方塊，採掉果實後換回原種。 */
	public static boolean sameStem(String from, String to) {
		return stemIdPair(from, to, "melon") || stemIdPair(from, to, "pumpkin");
	}

	private static boolean stemIdPair(String from, String to, String crop) {
		String stem = "minecraft:" + crop + "_stem", attached = "minecraft:attached_" + crop + "_stem";
		return stem.equals(from) && attached.equals(to) || attached.equals(from) && stem.equals(to);
	}

	public static boolean harvestable(Agent agent, Level level, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof CropBlock crop) return crop.isMaxAge(state) && agent.guard.isOwned(level, pos);
		Block attachedStem = state.is(Blocks.MELON) ? Blocks.ATTACHED_MELON_STEM
			: state.is(Blocks.PUMPKIN) ? Blocks.ATTACHED_PUMPKIN_STEM : null;
		if (attachedStem == null) return false;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos stem = pos.relative(side);
			BlockState adjacent = level.getBlockState(stem);
			if (adjacent.is(attachedStem) && adjacent.getValue(BlockStateProperties.HORIZONTAL_FACING) == side.getOpposite()
				&& agent.guard.isOwned(level, stem)) return true;
		}
		return false;
	}

	private final Mode mode;
	private final List<BlockPos> targets; // TILL：要鋤的地面；HARVEST：作物的位置
	private final Item seed;
	private final boolean replant;
	private Phase phase = Phase.NEXT;
	private Phase afterWalk = Phase.ACT;
	private int idx = -1;
	private int doneCount;
	private int waitTicks;
	private Block harvestedCrop;
	private boolean pickingUp;
	private final java.util.List<String> skipped = new java.util.ArrayList<>();

	public FarmTask(Agent agent, Mode mode, List<BlockPos> targets, Item seed, boolean replant) {
		super(agent, mode == Mode.TILL_AND_PLANT ? "till_and_plant" : "harvest");
		this.mode = mode;
		this.targets = targets;
		this.seed = seed;
		this.replant = replant;
	}

	@Override
	public String describe() {
		return mode == Mode.TILL_AND_PLANT ? "種田" : "收成";
	}

	@Override
	public Progress hudProgress() {
		return new Progress(doneCount, targets.size());
	}

	@Override
	public String progress() {
		return (mode == Mode.TILL_AND_PLANT ? "planted=" : "harvested=") + doneCount + "/" + targets.size();
	}

	@Override
	public boolean moves() {
		return true;
	}

	private BlockPos cur() {
		return targets.get(idx);
	}

	@Override
	protected void tick(Minecraft mc) {
		switch (phase) {
			case NEXT -> {
				idx++;
				if (idx >= targets.size()) {
					phase = Phase.DONE;
					return;
				}
				waitTicks = 0;
				afterWalk = Phase.ACT;
				if (Moves.canReach(mc.player, cur())) phase = Phase.ACT;
				else {
					path(new GoalNear(cur(), 2));
					phase = Phase.WALK;
				}
			}
			case WALK -> {
				if (Moves.canReach(mc.player, cur())) {
					Bari.stopAll();
					phase = afterWalk;
					afterWalk = Phase.ACT;
					waitTicks = 0;
				} else if (!Bari.busy() && waitTicks++ > 40) {
					skipped.add(blockagent.game.Fmt.pos(cur()) + ":unreachable");
					phase = Phase.NEXT;
				}
			}
			case ACT -> {
				if (mode == Mode.TILL_AND_PLANT) {
					BlockState st = mc.level.getBlockState(cur());
					if (!st.is(Blocks.FARMLAND)) {
						if (!Inv.selectInHand(mc, s -> s.is(ItemTags.HOES))) {
							fail(ErrorCode.MISSING_TOOL, "hoe is gone");
							return;
						}
						Moves.useOnWithShieldBypass(mc, cur(), Direction.UP);
					}
					phase = Phase.WAIT;
				} else {
					BlockState st = mc.level.getBlockState(cur());
					if (!harvestable(agent, mc.level, cur(), st)) {
						phase = Phase.NEXT;
						return;
					}
					harvestedCrop = st.getBlock();
					int tool = Inv.ensureHotbarTool(mc, st);
					if (tool >= 0) mc.player.getInventory().setSelectedSlot(tool);
					Direction face = Moves.faceToward(mc.player, cur());
					if (!mc.gameMode.startDestroyBlock(cur(), face)) {
						skipped.add(blockagent.game.Fmt.pos(cur()) + ":rule");
						phase = Phase.NEXT;
						return;
					}
					mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
					phase = Phase.WAIT;
				}
				waitTicks = 0;
			}
			case WAIT -> {
				waitTicks++;
				if (mode == Mode.TILL_AND_PLANT) {
					if (mc.level.getBlockState(cur()).is(Blocks.FARMLAND)) {
						if (!Inv.selectInHand(mc, s -> s.getItem() == seed)) {
							fail(ErrorCode.MISSING_MATERIALS, "ran out of " + Inv.id(seed));
							return;
						}
						Moves.useOn(mc, cur(), Direction.UP);
						waitTicks = 0;
						phase = Phase.VERIFY_PLANT;
					} else if (waitTicks > 10) {
						skipped.add(blockagent.game.Fmt.pos(cur()) + ":untillable");
						phase = Phase.NEXT;
					}
				} else {
					if (!mc.level.getBlockState(cur()).isAir()) {
						mc.gameMode.continueDestroyBlock(cur(), Moves.faceToward(mc.player, cur()));
						if (waitTicks > 40) phase = Phase.NEXT;
						return;
					}
					// 走到作物位置撿掉落物(作物不一定剛好在第 1 tick 消失，L6：只在還沒開始走時規劃一次)
					if (!pickingUp) {
						pickingUp = true;
						waitTicks = 0; // 撿東西最多 1 秒，從作物消失開始算
						path(new GoalNear(cur(), 0));
					}
					if (waitTicks > 20 || mc.player.blockPosition().equals(cur())) {
						doneCount++;
						markProgress();
						pickingUp = false;
						phase = replant && SEED_OF.containsKey(harvestedCrop) ? Phase.REPLANT : Phase.NEXT;
						waitTicks = 0;
					}
				}
			}
			case VERIFY_PLANT -> {
				if (mc.level.getBlockState(cur().above()).is(CROP_OF.get(seed))) {
					doneCount++;
					markProgress();
					phase = Phase.NEXT;
				} else if (++waitTicks > 20) {
					skipped.add(blockagent.game.Fmt.pos(cur()) + ":not_planted");
					phase = Phase.NEXT;
				}
			}
			case REPLANT -> {
				Item s = SEED_OF.get(harvestedCrop);
				if (s != null && mc.level.getBlockState(cur().below()).is(Blocks.FARMLAND) && Inv.selectInHand(mc, st -> st.getItem() == s)) {
					Moves.useOn(mc, cur().below(), Direction.UP);
				}
				phase = Phase.NEXT;
			}
			case DONE -> done(skipped.isEmpty() ? "" : "skipped=" + String.join(",", skipped.subList(0, Math.min(8, skipped.size())))
				+ (skipped.size() > 8 ? ",+" + (skipped.size() - 8) : ""));
		}
	}

	@Override
	protected void onStop(Minecraft mc) {
		Bari.stopAll();
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
	}

	/** 反射接手時停下了挖掘和走路，人也可能被帶走：這一格從頭來(H7)。 */
	@Override
	protected void onResume(Minecraft mc) {
		waitTicks = 0;
		if (idx < 0 || idx >= targets.size()) return;
		switch (phase) {
			case ACT, WAIT, WALK -> {
				// 收成時作物已經挖掉了：離太遠先走回目標旁再撿和補種，避免 1 秒逾時在遠處補種失敗(P3-M13)
				if (mode == Mode.HARVEST && phase == Phase.WAIT && mc.level.getBlockState(cur()).isAir()) {
					pickingUp = false;
					if (!Moves.canReach(mc.player, cur())) {
						afterWalk = Phase.WAIT;
						phase = Phase.WALK;
						path(new GoalNear(cur(), 2));
					}
					return;
				}
				if (Moves.canReach(mc.player, cur())) {
					phase = afterWalk;
					afterWalk = Phase.ACT;
				} else {
					path(new GoalNear(cur(), 2));
					phase = Phase.WALK;
				}
			}
			case REPLANT -> {
				if (!Moves.canReach(mc.player, cur())) {
					afterWalk = Phase.REPLANT;
					phase = Phase.WALK;
					path(new GoalNear(cur(), 2));
				}
			}
			default -> {
			}
		}
	}
}
