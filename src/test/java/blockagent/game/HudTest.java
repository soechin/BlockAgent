package blockagent.game;

import blockagent.mcp.AiPresence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 工作階段進行中那一行的文字(S8.3)：AI 端不在時不能顯示「AI 進行中｜想下一步」。 */
class HudTest {
	@Test
	void runningLineDependsOnAiPresence() {
		assertEquals("● AI 進行中｜想下一步｜剩 42 分", Hud.runningLine(null, AiPresence.ACTIVE, 42));
		assertEquals("● AI 進行中｜想下一步", Hud.runningLine(null, AiPresence.ACTIVE, -1), "不限時就不顯示剩餘時間");
		assertEquals("● AI 進行中｜等事件｜剩 42 分", Hud.runningLine("", AiPresence.BUSY, 42));
		assertEquals("● AI 進行中｜挖鐵礦 4/10｜剩 42 分", Hud.runningLine("挖鐵礦 4/10", AiPresence.BUSY, 42));
		assertEquals("● AI 未連線｜請啟動 Claude Code 或 Codex｜剩 42 分", Hud.runningLine(null, AiPresence.NONE, 42));
		assertEquals("● AI 沒有回應｜超過 1 分鐘沒有動靜｜剩 42 分", Hud.runningLine(" ", AiPresence.SILENT, 42));
		// 有任務時仍然顯示任務，只是開頭換掉
		assertEquals("● AI 沒有回應｜蓋小屋 3/60｜剩 42 分", Hud.runningLine("蓋小屋 3/60", AiPresence.SILENT, 42));
	}
}
