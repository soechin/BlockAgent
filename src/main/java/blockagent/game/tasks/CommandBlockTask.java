package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.BuildCommands;
import blockagent.game.Creative;
import blockagent.game.RuleGuard;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/** 放置或拆除一格，等伺服器回傳方塊更新才完成。 */
public final class CommandBlockTask extends Task {
	private final ClientLevel level;
	private final BlockPos pos;
	private final BlockState before;
	private final BlockState after;
	private long sentAt = -1;
	private boolean confirmed;

	public CommandBlockTask(Agent agent, ClientLevel level, BlockPos pos, BlockState before, BlockState after) {
		super(agent, after.isAir() ? "break_block" : "place_block");
		this.level = level;
		this.pos = pos;
		this.before = before;
		this.after = after;
	}

	@Override
	public String describe() {
		return after.isAir() ? "挖 " + name(before.getBlock()) : "放置 " + name(after.getBlock());
	}

	@Override
	public String progress() {
		String at = "pos=" + blockagent.game.Fmt.pos(pos) + (after.isAir() ? "" : " block=" + RuleGuard.shortId(after));
		if (confirmed) return at + (after.isAir() ? " state=broken" : " state=placed");
		if (status == Status.DONE) return at + " state=already_empty";
		return at + (sentAt < 0 ? " state=pending" : " state=awaiting_server");
	}

	@Override
	protected void start(Minecraft mc) {
		if (after.isAir() && before.isAir()) done("note=already_empty");
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mc.level != level) {
			fail(ErrorCode.INTERRUPTED, "dimension changed");
			return;
		}
		if (confirmed) {
			done(after.isAir() ? Creative.noDropsNote(mc) : "");
			return;
		}
		if (!Creative.isCreative(mc)) {
			fail(ErrorCode.WRONG_GAME_MODE, "no longer in creative mode");
			return;
		}
		if (!BuildCommands.available(mc)) {
			fail(ErrorCode.INTERRUPTED, "server revoked permission for this block command");
			return;
		}
		if (sentAt >= 0) {
			if (agent.tick() - sentAt >= BuildCommands.CONFIRM_TICKS) fail(ErrorCode.TIMEOUT, "no server confirmation within 5 s");
			return;
		}
		if (!agent.blockCommands.ready(mc)) return;
		if (!BuildCommands.validTarget(level, pos) || level.getBlockState(pos) != before) {
			fail(ErrorCode.PATH_BLOCKED, "target block changed or unloaded");
			return;
		}
		var verdict = after.isAir() ? agent.guard.canBreak(level, pos) : agent.guard.canPlaceInto(level, pos);
		if (!verdict.allowed()) {
			fail(verdict.code(), verdict.reason(), verdict.hint());
			return;
		}
		if (!after.isAir() && (!after.canSurvive(level, pos) || !level.isUnobstructed(after, pos, CollisionContext.empty()))) {
			fail(ErrorCode.PATH_BLOCKED, "block cannot stay there (no support, or occupied)");
			return;
		}
		if (!agent.blockCommands.fill(mc, pos, pos, before, after)) {
			fail(ErrorCode.INTERRUPTED, "block command could not complete");
			return;
		}
		sentAt = agent.tick();
		agent.tracker.expectCommandChange(level, pos, after.getBlock(), sentAt + BuildCommands.CONFIRM_TICKS, ignored -> confirmed = true);
	}
}
