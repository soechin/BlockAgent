package blockagent.game;

import blockagent.core.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * 死掉自動復活(docs/SPEC.md S8.8)：工作階段進行中死掉時，取消任務，等 1 秒(讓 died 事件帶上死因)
 * 再按「重生」，跟玩家在死亡畫面按按鈕一樣({@link LocalPlayer#respawn()})。復活後通知 AI 現在在哪、東西掉在哪。
 * 極限模式不能重生，只通知一次。暫停時不動，交給使用者(死亡畫面開著時暫停鍵不會作用，要使用者自己按重生)。
 */
public final class Respawn {
	/** 死掉後等多久才按重生。 */
	static final int DELAY_TICKS = 20;
	/** 按了沒反應時，多久再按一次。 */
	static final int RETRY_TICKS = 100;

	private final Agent agent;
	private long deadSince = -1;
	private long lastRequest = -1;
	private BlockPos deathPos;
	private boolean hardcoreReported;

	Respawn(Agent agent) {
		this.agent = agent;
	}

	/** 換世界時清掉上個世界的死亡狀態(H11)。 */
	void reset() {
		deadSince = -1;
		lastRequest = -1;
		deathPos = null;
		hardcoreReported = false;
	}

	/** 每個 tick 呼叫(只在 RUNNING 時)。回傳 true 表示現在是死的，這個 tick 其他東西都不用跑。 */
	boolean tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		long t = agent.tick();
		if (p.isDeadOrDying()) {
			if (deadSince < 0) {
				deadSince = t;
				deathPos = p.blockPosition();
				agent.tasks.cancel("died");
				// 死掉時反射也停下：不然按著的跳鍵、使用鍵和盾牌會留到重生後(H11)
				agent.reflexes.stopAll();
				Bari.stopAll();
			}
			if (mc.level.getLevelData().isHardcore()) {
				if (!hardcoreReported) {
					hardcoreReported = true;
					agent.events.add("respawn_unavailable", Event.Level.URGENT, "hardcore=true | cannot respawn in a hardcore world | hint=tell the user");
				}
				return true;
			}
			if (t - deadSince >= DELAY_TICKS && (lastRequest < 0 || t - lastRequest >= RETRY_TICKS)) {
				lastRequest = t;
				agent.log.log(ActionLog.Source.system, "respawn", "死在 " + deathPos.toShortString() + "，按重生");
				p.respawn();
			}
			return true;
		}
		if (deadSince >= 0) {
			if (lastRequest >= 0) {
				agent.events.add("respawned", Event.Level.PROMPT, blockagent.mcp.Kv.of().put("pos", Fmt.pos(p.blockPosition())).put("death_pos", Fmt.pos(deathPos))
					+ " | dropped items despawn about 5 min after death | hint=go back for them if it is safe");
			}
			deadSince = -1;
			lastRequest = -1;
		}
		return false;
	}
}
