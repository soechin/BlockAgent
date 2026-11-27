package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Facing;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
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
 * 指定朝向、上下半或蹲下貼放一格(place_block 的 facing/half/sneak，docs/SPEC.md S4.2b)。
 * 伺服器用上一次同步的視角與蹲下狀態決定放置，而點擊封包不帶視角與按鍵，
 * 所以先轉向/按住蹲下鍵等 3 tick 讓狀態送出去，再點；伺服器確認後讀方塊狀態驗證。
 */
public final class PlaceTask extends Task {
	private static final int TURN_TICKS = 3;
	private static final int CONFIRM_TIMEOUT = 200;

	private enum Phase {TURN, CONFIRM}

	private final ClientLevel level;
	private final BlockPos pos;
	private final Item item;
	private final Direction facing;
	private final String half;
	private final boolean sneak;
	private final Facing.Plan plan;
	private Phase phase = Phase.TURN;
	private int ticks;
	private CompletableFuture<BlockState> confirm;
	private net.minecraft.world.inventory.AbstractContainerMenu menuBefore;

	public PlaceTask(Agent agent, ClientLevel level, BlockPos pos, Item item, Direction facing, String half, boolean sneak, Facing.Plan plan) {
		super(agent, "place_block");
		this.level = level;
		this.pos = pos.immutable();
		this.item = item;
		this.facing = facing;
		this.half = half;
		this.sneak = sneak;
		this.plan = plan;
	}

	@Override
	public String describe() {
		return "放置 " + name(item);
	}

	@Override
	public String progress() {
		Kv kv = Kv.of();
		if (running()) kv.put("pos", Fmt.pos(pos));
		kv.put("item", Inv.id(item));
		if (facing != null) kv.put("facing", facing.getSerializedName());
		if (half != null) kv.put("half", half);
		if (sneak) kv.put("sneak", true);
		if (running()) kv.put("phase", phase == Phase.TURN ? "turning" : "confirming");
		return kv.toString();
	}

	/** 反射(進食、自衛)接手時可能換了手上的物品或移動了位置：還沒點下去就從頭轉向/蹲下、重選物品(P2-2)。 */
	@Override
	protected void onResume(Minecraft mc) {
		if (phase == Phase.TURN) ticks = 0;
	}

	@Override
	protected void onStop(Minecraft mc) {
		if (sneak && mc.options != null) mc.options.keyShift.setDown(false);
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
				if (sneak) mc.options.keyShift.setDown(true);
				if (ticks == 1) {
					if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
					if (!Inv.selectInHandWithOffhand(mc, s -> s.is(item))) {
						fail(ErrorCode.MISSING_MATERIALS, "no " + Inv.id(item) + " in inventory");
					}
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
				if (sneak) mc.options.keyShift.setDown(false);
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
					mc.player.closeContainer();
					fail(ErrorCode.INVALID_ARGUMENT, "right-click opened " + RuleGuard.shortId(level.getBlockState(plan.hit().getBlockPos()))
						+ " instead of placing", sneak ? "check that the target face accepts this block" : "use sneak=true, or pick a non-interactive neighbor");
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
				} else if (facing != null && !Facing.matches(st, facing)) {
					fail(ErrorCode.INVALID_ARGUMENT, "placed with facing=" + Facing.of(st) + " instead of " + facing.getSerializedName()
						+ " (the server may use different placement rules)", "break_block it, then place again from another side");
				} else if (half != null && !Facing.matchesHalf(st, half)) {
					fail(ErrorCode.INVALID_ARGUMENT, "placed with half=" + Facing.halfOf(st) + " instead of " + half
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
