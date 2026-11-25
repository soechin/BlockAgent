package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Facing;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.CompletableFuture;

/**
 * 指定朝向放一格(place_block 的 facing，A6)。伺服器用上一次同步的視角決定朝向，而點擊封包不帶視角，
 * 所以先轉向等幾個 tick 讓視角送出去，再點；伺服器確認後讀方塊狀態，朝向不對就照實回報。
 */
public final class PlaceTask extends Task {
	private static final int TURN_TICKS = 3;
	private static final int CONFIRM_TIMEOUT = 200;

	private enum Phase {TURN, CONFIRM}

	private final ClientLevel level;
	private final BlockPos pos;
	private final Item item;
	private final Direction facing;
	private final Facing.Plan plan;
	private Phase phase = Phase.TURN;
	private int ticks;
	private CompletableFuture<BlockState> confirm;
	private net.minecraft.world.inventory.AbstractContainerMenu menuBefore;

	public PlaceTask(Agent agent, ClientLevel level, BlockPos pos, Item item, Direction facing, Facing.Plan plan) {
		super(agent, "place_block");
		this.level = level;
		this.pos = pos.immutable();
		this.item = item;
		this.facing = facing;
		this.plan = plan;
	}

	@Override
	public String describe() {
		return "放置 " + name(item);
	}

	/** 進行中帶目標位置和階段；結束時只留物品和朝向，因為結果尾巴已經有 pos=(自己的位置)、done 會給 state=，不要重複(P2-2)。 */
	@Override
	public String progress() {
		String what = "item=" + Inv.id(item) + " facing=" + facing.getSerializedName();
		if (!running()) return what;
		return "pos=" + Fmt.pos(pos) + " " + what + " phase=" + (phase == Phase.TURN ? "turning" : "confirming");
	}

	/** 反射(進食、自衛)接手時可能換了手上的物品或移動了位置：還沒點下去就從頭轉向、重選物品(P2-2)。 */
	@Override
	protected void onResume(Minecraft mc) {
		if (phase == Phase.TURN) ticks = 0;
	}

	private void turn(Minecraft mc) {
		var p = mc.player;
		p.setYRot(plan.yaw());
		p.setXRot(plan.pitch());
		p.yRotO = plan.yaw();
		p.xRotO = plan.pitch();
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mc.level != level) {
			fail(ErrorCode.INTERRUPTED, "dimension changed");
			return;
		}
		ticks++;
		switch (phase) {
			case TURN -> {
				turn(mc);
				if (ticks == 1) {
					if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
					if (!Inv.selectInHand(mc, s -> s.is(item))) fail(ErrorCode.MISSING_MATERIALS, "no " + Inv.id(item) + " in inventory");
					return;
				}
				if (ticks <= TURN_TICKS) return;
				BlockState cur = level.getBlockState(pos);
				if (!cur.isAir() && !cur.canBeReplaced()) {
					fail(ErrorCode.INVALID_ARGUMENT, "position became occupied by " + RuleGuard.shortId(cur));
					return;
				}
				if (!Moves.canReach(mc.player, pos)) {
					fail(ErrorCode.PATH_BLOCKED, "out of reach", "goto closer first");
					return;
				}
				if (!mc.player.getMainHandItem().is(item)) {
					fail(ErrorCode.MISSING_MATERIALS, "no " + Inv.id(item) + " in hand");
					return;
				}
				menuBefore = mc.player.containerMenu;
				long mark = agent.guard.blockMark();
				var r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, plan.hit());
				if (!r.consumesAction()) {
					var blocked = agent.guard.blockedAfter(mark);
					if (blocked != null) fail(blocked.code(), blocked.reason(), blocked.hint());
					else fail(ErrorCode.INVALID_ARGUMENT, "could not place (that face does not accept it)");
					return;
				}
				mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
				confirm = agent.tracker.confirmPlacement(level, pos);
				phase = Phase.CONFIRM;
				ticks = 0;
			}
			case CONFIRM -> {
				if (mc.player.containerMenu != menuBefore) {
					// 點到的方塊打開了介面(Facing 已經避開互動方塊，這裡是保險)：關掉，不要乾等確認逾時(P2-1)
					mc.player.closeContainer();
					fail(ErrorCode.INVALID_ARGUMENT, "right-click opened " + RuleGuard.shortId(level.getBlockState(plan.hit().getBlockPos()))
						+ " instead of placing", "pick a position next to a non-interactive block");
					return;
				}
				if (!confirm.isDone()) {
					if (ticks > CONFIRM_TIMEOUT) fail(ErrorCode.TIMEOUT, "no server confirmation for the placement");
					return;
				}
				BlockState st = confirm.join();
				if (st == null) {
					fail(ErrorCode.TIMEOUT, "no server confirmation for the placement");
				} else if (!st.is(plan.predicted().getBlock())) {
					fail(ErrorCode.INVALID_ARGUMENT, "block was not placed; now " + RuleGuard.shortId(st));
				} else if (!Facing.matches(st, facing)) {
					fail(ErrorCode.INVALID_ARGUMENT, "placed with facing=" + Facing.of(st) + " instead of " + facing.getSerializedName()
						+ " (the server may use different placement rules)", "break_block it, then place again from another side");
				} else {
					markProgress();
					String s = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(st);
					done("state=" + (s.startsWith("minecraft:") ? s.substring(10) : s));
				}
			}
		}
	}
}
