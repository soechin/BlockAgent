package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 挖一格(需要在觸及範圍內)。守門員在 MultiPlayerGameMode 層擋下不能挖的方塊。 */
public final class BreakTask extends Task {
	private final BlockPos pos;
	private final blockagent.core.ItemMatcher toolMatcher;
	private Block original;
	private Direction face;
	private boolean started;
	private boolean broken;

	public BreakTask(Agent agent, BlockPos pos) {
		this(agent, pos, blockagent.core.ItemMatcher.ANY);
	}

	public BreakTask(Agent agent, BlockPos pos, blockagent.core.ItemMatcher toolMatcher) {
		super(agent, "break_block");
		this.pos = pos.immutable();
		this.toolMatcher = toolMatcher == null ? blockagent.core.ItemMatcher.ANY : toolMatcher;
		this.timeoutTicks = 20 * 30;
	}

	@Override
	public String describe() {
		var level = Minecraft.getInstance().level;
		BlockState st = level == null ? null : level.getBlockState(pos);
		return st == null || st.isAir() ? "挖方塊" : "挖 " + name(st.getBlock());
	}

	@Override
	public String progress() {
		String at = "pos=" + blockagent.game.Fmt.pos(pos);
		if (broken) return at + " state=broken";
		if (status == Status.DONE) return at + " state=already_empty";
		return at + (started ? " state=breaking" : " state=pending");
	}

	/** 換上挖這個方塊最快的工具(背包裡有的話)。 */
	public static void equipBestTool(Minecraft mc, BlockState state) {
		var p = mc.player;
		// 以徒手為基準：只有比徒手快、或掉落需要正確工具時才拿工具。速度一樣就不拿會耗損耐久的東西，
		// 劍只在真的比較快時(蜘蛛網、竹子)才用(2026-11-22 試玩：拿鐵劍砍樹、挖石頭)。
		boolean needsTool = state.requiresCorrectToolForDrops();
		ItemStack best = null;
		float bestSpeed = 1.0f;
		for (int i = 0; i < Inv.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			float sp = s.getDestroySpeed(state);
			boolean correct = needsTool && s.isCorrectToolForDrops(state);
			boolean bestCorrect = best != null && needsTool && best.isCorrectToolForDrops(state);
			if ((correct && !bestCorrect) || (correct == bestCorrect && sp > bestSpeed + 0.01f)) {
				best = s;
				bestSpeed = sp;
			}
		}
		if (best != null) {
			ItemStack target = best;
			Inv.selectInHand(mc, s -> s == target);
		} else if (!Inv.worthDigging(p.getMainHandItem(), state)) {
			// 沒有更好的工具：換成空手或不會耗損的東西，不要用手上的劍或工具硬挖
			if (!Inv.selectInHand(mc, ItemStack::isEmpty)) Inv.selectInHand(mc, s -> !s.isDamageableItem());
		}
	}

	@Override
	protected void start(Minecraft mc) {
		BlockState st = mc.level.getBlockState(pos);
		if (st.isAir()) {
			done("note=already_empty");
			return;
		}
		if (!Moves.canReach(mc.player, pos)) {
			fail(ErrorCode.PATH_BLOCKED, "out of reach; goto closer first");
			return;
		}
		original = st.getBlock();
		if (!selectBreakingTool(mc, st)) return;
		face = Moves.faceToward(mc.player, pos);
		Moves.lookAt(mc.player, Moves.hit(pos, face).getLocation());
		started = mc.gameMode.startDestroyBlock(pos, face);
		if (!started) {
			fail(ErrorCode.RULE_PROTECTED_BLOCK, "this block may not be broken (likely someone else's; see the rule_blocked event)");
		} else if (mc.level.getBlockState(pos).getBlock() != original) {
			// 一碰就碎(創造模式，或花草這種硬度 0 的方塊)
			broken = true;
			done(blockagent.game.Creative.noDropsNote(mc));
		}
	}

	@Override
	protected void tick(Minecraft mc) {
		BlockState st = mc.level.getBlockState(pos);
		if (st.getBlock() != original) {
			broken = true;
			done(blockagent.game.Creative.noDropsNote(mc));
			return;
		}
		Moves.lookAt(mc.player, Moves.hit(pos, face).getLocation());
		if (!mc.gameMode.continueDestroyBlock(pos, face)) {
			fail(ErrorCode.RULE_PROTECTED_BLOCK, "breaking was aborted (likely someone else's block)");
			return;
		}
		mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
	}

	@Override
	protected void onStop(Minecraft mc) {
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
	}

	/** 反射接手時挖到一半停下了(onSuspend = onStop)：重新拿工具、從頭開始挖(H7)。 */
	@Override
	protected void onResume(Minecraft mc) {
		BlockState st = mc.level.getBlockState(pos);
		if (st.getBlock() != original) {
			broken = true;
			done(blockagent.game.Creative.noDropsNote(mc));
			return;
		}
		if (!Moves.canReach(mc.player, pos)) {
			fail(ErrorCode.PATH_BLOCKED, "out of reach after a reflex moved the player; goto closer first");
			return;
		}
		if (!selectBreakingTool(mc, st)) return;
		face = Moves.faceToward(mc.player, pos);
		Moves.lookAt(mc.player, Moves.hit(pos, face).getLocation());
		if (!mc.gameMode.startDestroyBlock(pos, face)) {
			fail(ErrorCode.RULE_PROTECTED_BLOCK, "this block may not be broken (likely someone else's; see the rule_blocked event)");
		}
	}

	private boolean selectBreakingTool(Minecraft mc, BlockState st) {
		if (toolMatcher.isAny() || blockagent.game.Creative.isCreative(mc)) {
			equipBestTool(mc, st);
			return true;
		}
		int idx = Inv.findBestMiningTool(mc.player, st, toolMatcher, true);
		if (idx < 0) {
			fail(ErrorCode.MISSING_TOOL, "no suitable tool matching " + toolMatcher.raw(), "equip or craft a matching tool");
			return false;
		}
		if (idx == Inv.OFFHAND_SLOT) {
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			Inv.swapToOffhand(mc, mc.player.getInventory().getSelectedSlot());
			return true;
		}
		return Inv.selectSlotInHand(mc, idx);
	}
}
