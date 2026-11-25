package blockagent.game.tasks;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.BuildCommands;
import blockagent.game.Creative;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/** Walks to the original candidates and removes them with normal player interactions. */
public final class ClearAreaTask extends Task {
	private static final int ATTEMPT_TICKS = 20 * 30;
	private static final int CONFIRM_TICKS = 20 * 5;

	private final ClientLevel level;
	private final Iterator<Map.Entry<BlockPos, Block>> candidates;
	private final int total;
	private final int protectedCount;
	private Map.Entry<BlockPos, Block> target;
	private long deadline;
	private boolean walking;
	private BlockPos walkingFrom;
	private boolean digging;
	private boolean authorizingBreak;
	// 還沒開始就被停下時也不會把 Baritone 改成不准挖和放(M14：預設值原本是 false)
	private boolean oldAllowBreak = true;
	private boolean oldAllowPlace = true;
	private BlockState predicted;
	private BlockState confirmed;
	private int cleared;
	private int vanished;
	private int changed;
	private int unreachable;

	public ClearAreaTask(Agent agent, ClientLevel level, Map<BlockPos, Block> candidates, int protectedCount) {
		super(agent, "clear_area");
		this.level = level;
		this.candidates = candidates.entrySet().iterator();
		this.total = candidates.size();
		this.protectedCount = protectedCount;
	}

	public static boolean eligible(Agent agent, ClientLevel level, BlockPos pos, BlockState state) {
		return !state.isAir() && agent.guard.isNatural(state) && !agent.guard.isOwned(pos)
			&& agent.guard.canBreak(level, pos).allowed() && state.getDestroySpeed(level, pos) >= 0;
	}

	public boolean authorizesBreak(BlockPos pos) {
		return running() && !suspended && authorizingBreak && target != null && target.getKey().equals(pos);
	}

	@Override
	public String describe() {
		return "清除指定範圍的 " + total + " 格天然方塊";
	}

	@Override
	public String progress() {
		String text = "cleared=" + cleared + " vanished=" + vanished + " changed=" + changed + " unreachable=" + unreachable;
		if (protectedCount > 0) text += " protected=" + protectedCount;
		int remaining = total - cleared - vanished - changed - unreachable;
		return remaining == 0 ? text : text + " remaining=" + remaining;
	}

	@Override
	protected void start(Minecraft mc) {
		var settings = BaritoneAPI.getSettings();
		oldAllowBreak = settings.allowBreak.value;
		oldAllowPlace = settings.allowPlace.value;
		settings.allowBreak.value = false;
		settings.allowPlace.value = false;
		Bari.stopAll();
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mc.level != level) {
			fail(ErrorCode.INTERRUPTED, "dimension changed");
			return;
		}
		if (target == null) {
			if (!candidates.hasNext()) {
				done(Creative.noDropsNote(mc));
				return;
			}
			target = candidates.next();
			deadline = agent.tick() + ATTEMPT_TICKS;
		}
		BlockPos pos = target.getKey();
		if (predicted != null) {
			if (!countConfirmation()) {
				if (agent.tick() < deadline) return;
				unreachable++;
			}
		} else if (!BuildCommands.validTarget(level, pos)) {
			unreachable++;
		} else {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				vanished++;
			} else if (state.getBlock() != target.getValue() || !eligible(agent, level, pos, state)) {
				changed++;
			} else if (agent.tick() >= deadline || !approachAndBreak(mc, pos, state)) {
				unreachable++;
			} else {
				return;
			}
		}
		finishTarget(mc);
	}

	private boolean approachAndBreak(Minecraft mc, BlockPos pos, BlockState state) {
		BlockHitResult hit = Moves.canReach(mc.player, pos) ? sightLine(mc, mc.player.getEyePosition(), pos) : null;
		if (hit == null) {
			if (digging) {
				mc.gameMode.stopDestroyBlock();
				digging = false;
			}
			if (walking) {
				if (Bari.b().getCustomGoalProcess().isActive()) return true;
				if (walkingFrom.equals(mc.player.blockPosition())) return false;
			}
			path(approachGoal(mc, pos));
			walkingFrom = mc.player.blockPosition();
			walking = true;
			return true;
		}
		if (walking) {
			Bari.stopAll();
			walking = false;
		}
		if (!digging) BreakTask.equipBestTool(mc, state);
		Direction face = hit.getDirection();
		Moves.lookAt(mc.player, hit.getLocation());
		boolean acted;
		authorizingBreak = true;
		try {
			acted = digging ? mc.gameMode.continueDestroyBlock(pos, face) : mc.gameMode.startDestroyBlock(pos, face);
		} finally {
			authorizingBreak = false;
		}
		if (acted) mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		digging = acted;
		return acted || predicted != null;
	}

	private Goal approachGoal(Minecraft mc, BlockPos targetPos) {
		var stands = new ArrayList<Goal>();
		double reach = Math.min(mc.player.blockInteractionRange(), 5);
		int radius = (int) Math.ceil(reach);
		Vec3 center = Vec3.atCenterOf(targetPos);
		for (BlockPos feet : BlockPos.betweenClosed(targetPos.offset(-radius, -radius - 2, -radius),
			targetPos.offset(radius, radius, radius))) {
			if (feet.equals(mc.player.blockPosition()) || !BuildCommands.validTarget(level, feet)) continue;
			Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, mc.player.getEyeHeight(), 0);
			if (eye.distanceToSqr(center) > reach * reach) continue;
			BlockPos floor = feet.below();
			if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
				|| !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				|| !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
			if (sightLine(mc, eye, targetPos) != null) stands.add(new GoalBlock(feet.immutable()));
		}
		return stands.isEmpty() ? new GoalGetToBlock(targetPos) : new GoalComposite(stands.toArray(Goal[]::new));
	}

	private BlockHitResult sightLine(Minecraft mc, Vec3 eye, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		var hit = clipTo(mc, eye, center, pos);
		if (hit != null) return hit;
		for (Direction offset : Direction.values()) {
			Vec3 sample = center.add(offset.getStepX() * 0.375, offset.getStepY() * 0.375, offset.getStepZ() * 0.375);
			hit = clipTo(mc, eye, sample, pos);
			if (hit != null) return hit;
		}
		return null;
	}

	private BlockHitResult clipTo(Minecraft mc, Vec3 eye, Vec3 sample, BlockPos pos) {
		var hit = level.clip(new ClipContext(eye, sample, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() != HitResult.Type.MISS && hit.getBlockPos().equals(pos) ? hit : null;
	}

	@Override
	public void onSelfBroke(BlockPos pos, BlockState before) {
		if (authorizesBreak(pos)) {
			predicted = level.getBlockState(pos);
			deadline = agent.tick() + CONFIRM_TICKS;
		}
	}

	public void onServerBlockState(ClientLevel updatedLevel, BlockPos pos, BlockState state) {
		if (updatedLevel == level && predicted != null && confirmed == null && target != null
			&& target.getKey().equals(pos) && state.getBlock() != target.getValue()) {
			confirmed = state;
		}
	}

	private boolean countConfirmation() {
		if (confirmed == null) return false;
		if (confirmed.is(predicted.getBlock()) || (predicted.isAir() && confirmed.isAir())) cleared++;
		else changed++;
		confirmed = null;
		return true;
	}

	private void finishTarget(Minecraft mc) {
		stopMovingAndDigging(mc);
		target = null;
		predicted = null;
		confirmed = null;
		markProgress();
	}

	private void stopMovingAndDigging(Minecraft mc) {
		if (walking) Bari.stopAll();
		if (digging && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		walking = false;
		digging = false;
	}

	@Override
	public boolean moves() {
		return walking;
	}

	@Override
	public boolean requiresFoot() {
		return true;
	}

	/** 清空區域期間 Baritone 不准自己挖和放；被反射暫停時不算(H10、M14)。 */
	@Override
	public void adjustBaritone(baritone.api.Settings s) {
		if (suspended) return;
		s.allowBreak.value = false;
		s.allowPlace.value = false;
	}

	@Override
	protected void onSuspend(Minecraft mc) {
		stopMovingAndDigging(mc);
		// 反射(自衛、進食)接手時照原本的設定走路，不然逃不開也追不到
		var settings = BaritoneAPI.getSettings();
		settings.allowBreak.value = oldAllowBreak;
		settings.allowPlace.value = oldAllowPlace;
	}

	@Override
	protected void onResume(Minecraft mc) {
		adjustBaritone(BaritoneAPI.getSettings());
		deadline = agent.tick() + (predicted == null ? ATTEMPT_TICKS : CONFIRM_TICKS);
	}

	@Override
	protected void onStop(Minecraft mc) {
		countConfirmation();
		try {
			stopMovingAndDigging(mc);
		} finally {
			var settings = BaritoneAPI.getSettings();
			settings.allowBreak.value = oldAllowBreak;
			settings.allowPlace.value = oldAllowPlace;
		}
	}
}
