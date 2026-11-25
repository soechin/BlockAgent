package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** 坐上船、礦車等載具(mount)。坐上去之後由掌舵的人決定往哪走；這一版不會自己開船。 */
public final class MountTask extends EntityTask {
	private static final int WAIT_TICKS = 100;

	private long usedTick = -1;

	public MountTask(Agent agent, Entity target) {
		super(agent, "mount", target);
	}

	@Override
	public String describe() {
		return "坐上 " + hudName();
	}

	@Override
	public String progress() {
		return "target=" + targetName + (usedTick < 0 ? " state=walking" : " state=mounting");
	}

	@Override
	protected void tick(Minecraft mc) {
		var p = mc.player;
		if (p.getVehicle() == target) {
			var driver = target.getControllingPassenger();
			done(driver == null || driver == p ? "" : "driver=" + driver.getName().getString());
			return;
		}
		if (!present(mc)) {
			fail(ErrorCode.NOT_FOUND, targetName + " is no longer nearby");
			return;
		}
		if (usedTick >= 0) {
			if (agent.tick() - usedTick > WAIT_TICKS) fail(ErrorCode.INVALID_ARGUMENT, "did not get on " + targetName + " (maybe full)");
			return;
		}
		if (!approach(mc)) return;
		Inv.selectInHand(mc, ItemStack::isEmpty); // 空手比較不會變成對載具用物品；沒有空格也照樣試
		long mark = agent.guard.blockMark();
		var r = interact(mc);
		if (!r.consumesAction()) {
			var v = agent.guard.blockedAfter(mark);
			if (v != null) fail(v.code(), v.reason(), v.hint());
			else fail(ErrorCode.INVALID_ARGUMENT, "right-clicking " + targetName + " had no effect (full, or not rideable)");
			return;
		}
		usedTick = agent.tick();
		markProgress();
	}
}
