package blockagent.game;

import blockagent.core.Session;
import blockagent.game.reflex.Reflexes;
import blockagent.mcp.AiPresence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** HUD 第一行的狀態判斷(S8.3)：只有標頭著色；第一行只顯示任務，AI 端不在時不能顯示「想下一步」。 */
class HudTest {
	@Test
	void headDependsOnStateAndAiPresence() {
		assertEquals(new Hud.Head("●", Hud.GREEN), Hud.head(Session.State.RUNNING, AiPresence.ACTIVE));
		assertEquals(new Hud.Head("●", Hud.GREEN), Hud.head(Session.State.RUNNING, AiPresence.BUSY));
		assertEquals(new Hud.Head("● 無回應", Hud.ORANGE), Hud.head(Session.State.RUNNING, AiPresence.SILENT));
		assertEquals(new Hud.Head("○ 未連線", Hud.SOFT_RED), Hud.head(Session.State.RUNNING, AiPresence.NONE));
		assertEquals(new Hud.Head("❚❚ 暫停", Hud.YELLOW), Hud.head(Session.State.PAUSED, AiPresence.ACTIVE));
		assertEquals(new Hud.Head("○ 待命", Hud.GRAY), Hud.head(Session.State.READY, AiPresence.NONE));
		assertEquals(new Hud.Head("■ 已結束", Hud.RED), Hud.head(Session.State.ENDED, AiPresence.ACTIVE));
		assertNull(Hud.head(Session.State.NO_WORLD, AiPresence.ACTIVE), "沒有世界時不畫");
	}

	@Test
	void bodyShowsTaskThenPresenceDefault() {
		Session.State run = Session.State.RUNNING;
		assertEquals("挖 鐵礦", Hud.body(run, AiPresence.BUSY, "挖 鐵礦"));
		assertEquals("挖 鐵礦", Hud.body(run, AiPresence.SILENT, "挖 鐵礦"), "AI 沒有回應時仍然顯示任務");
		assertEquals("想下一步", Hud.body(run, AiPresence.ACTIVE, " "));
		assertEquals("等事件", Hud.body(run, AiPresence.BUSY, null));
		assertEquals("超過 1 分鐘沒有動靜", Hud.body(run, AiPresence.SILENT, ""));
		assertEquals("請啟動 Claude Code 或 Codex", Hud.body(run, AiPresence.NONE, null));
		assertEquals("按 ` 恢復", Hud.body(Session.State.PAUSED, AiPresence.ACTIVE, "挖 鐵礦"));
		assertEquals("按 ` 開始", Hud.body(Session.State.READY, AiPresence.NONE, null));
		assertEquals("", Hud.body(Session.State.ENDED, AiPresence.NONE, null));
	}

	@Test
	void everyReflexHasATag() {
		assertNull(Hud.reflexTag(null));
		for (Reflexes.Kind k : Reflexes.Kind.values()) assertNotNull(Hud.reflexTag(k), k.name());
		assertEquals("撤退", Hud.reflexTag(Reflexes.Kind.RETREAT));
	}
}
