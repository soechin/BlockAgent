package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalNear;
import blockagent.game.Agent;
import blockagent.game.Bari;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

/** 先走到生物或載具旁邊，再對它按右鍵的任務(use_on_entity、mount)共用的走近與互動。 */
abstract class EntityTask extends Task {
	private static final int REPATH_INTERVAL = 5;

	protected final Entity target;
	protected final String targetName;
	private BlockPos destination;
	private long lastPathTick = Long.MIN_VALUE / 2;

	protected EntityTask(Agent agent, String name, Entity target) {
		super(agent, name);
		this.target = target;
		this.targetName = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath() + " id=" + target.getId();
	}

	@Override
	public boolean moves() {
		return true;
	}

	/** 目標還在嗎。 */
	protected boolean present(Minecraft mc) {
		return mc.level.getEntity(target.getId()) == target && target.isAlive();
	}

	/** 走近；已經碰得到就停下並回傳 true。 */
	protected boolean approach(Minecraft mc) {
		var p = mc.player;
		if (p.hasLineOfSight(target) && p.isWithinEntityInteractionRange(target, 0)) {
			if (destination != null) {
				Bari.stopAll();
				destination = null;
			}
			return true;
		}
		BlockPos at = target.blockPosition();
		if (agent.tick() - lastPathTick >= REPATH_INTERVAL && !(at.equals(destination) && Bari.busy())) {
			destination = at;
			lastPathTick = agent.tick();
			path(new GoalNear(at, 1));
		}
		return false;
	}

	/** 看著目標按右鍵(規矩由 MultiPlayerGameModeMixin.interact 檢查)。 */
	protected InteractionResult interact(Minecraft mc) {
		var p = mc.player;
		Moves.lookAt(p, target.getBoundingBox().getCenter());
		InteractionResult r = mc.gameMode.interact(p, target, new EntityHitResult(target, target.getBoundingBox().getCenter()), InteractionHand.MAIN_HAND);
		if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		return r;
	}

	@Override
	protected void onStop(Minecraft mc) {
		if (destination != null) Bari.stopAll();
		destination = null;
	}

	@Override
	protected void onResume(Minecraft mc) {
		destination = null;
	}
}
