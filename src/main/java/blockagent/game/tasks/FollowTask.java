package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalNear;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * 跟著一位玩家走(follow)。只在 AI 呼叫時開始，時間到、對方不見、stop、暫停或接手就停(需求 M2.5：不做自動跟隨)。
 */
public final class FollowTask extends Task {
	private static final int REPATH_INTERVAL = 10;

	private final String playerName;
	private final int distance;
	private final int maxSec;
	private BlockPos destination;
	private long lastPathTick = Long.MIN_VALUE / 2;
	private double lastDistance = -1;

	public FollowTask(Agent agent, String playerName, int distance, int maxSec) {
		super(agent, "follow");
		this.playerName = playerName;
		this.distance = distance;
		this.maxSec = maxSec;
	}

	@Override
	public String describe() {
		return "跟著 " + playerName + "(保持約 " + distance + " 格)";
	}

	@Override
	public String progress() {
		return "player=" + playerName + " followed=" + elapsed() / 20 + "/" + maxSec + "s" + (lastDistance < 0 ? "" : " dist=" + Math.round(lastDistance));
	}

	@Override
	public boolean moves() {
		return true;
	}

	public static Player findPlayer(Minecraft mc, String name) {
		for (Player p : mc.level.players()) {
			if (p != mc.player && p.getName().getString().equalsIgnoreCase(name)) return p;
		}
		return null;
	}

	@Override
	protected void tick(Minecraft mc) {
		Player target = findPlayer(mc, playerName);
		if (target == null) {
			fail(ErrorCode.NOT_FOUND, playerName + " is no longer nearby (offline, other dimension, or out of range)");
			return;
		}
		lastDistance = mc.player.distanceTo(target);
		if (elapsed() >= maxSec * 20L) {
			done("");
			return;
		}
		if (lastDistance <= distance + 1.5) {
			// 已經在旁邊：停下來，不要擠到對方身上
			if (destination != null) {
				Bari.stopAll();
				destination = null;
			}
			markProgress();
			return;
		}
		BlockPos at = target.blockPosition();
		if (agent.tick() - lastPathTick < REPATH_INTERVAL) return;
		if (at.equals(destination) && Bari.busy()) return;
		destination = at;
		lastPathTick = agent.tick();
		path(new GoalNear(at, distance));
	}

	@Override
	protected void onStop(Minecraft mc) {
		Bari.stopAll();
		destination = null;
	}

	@Override
	protected void onResume(Minecraft mc) {
		destination = null; // 下一個 tick 依對方目前的位置重新找路
	}
}
