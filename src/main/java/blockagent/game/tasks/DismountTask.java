package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;

/** 從載具下來(dismount)：跟玩家一樣按住蹲下鍵，通常一兩個 tick 就下來。 */
public final class DismountTask extends Task {
	private static final int MAX_TICKS = 20;

	public DismountTask(Agent agent) {
		super(agent, "dismount");
	}

	@Override
	public String describe() {
		return "從載具下來";
	}

	@Override
	public String progress() {
		return "action=sneak";
	}

	@Override
	protected void start(Minecraft mc) {
		if (mc.player.getVehicle() == null) done("note=not_riding");
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mc.player.getVehicle() == null) {
			mc.options.keyShift.setDown(false);
			done("dismounted=true");
			return;
		}
		if (elapsed() > MAX_TICKS) {
			fail(ErrorCode.INVALID_ARGUMENT, "still riding after sneaking for 1 s");
			return;
		}
		mc.options.keyShift.setDown(true);
	}

	@Override
	protected void onStop(Minecraft mc) {
		mc.options.keyShift.setDown(false);
	}
}
