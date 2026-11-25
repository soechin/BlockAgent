package blockagent.testkit;

import blockagent.core.Session;
import blockagent.game.Agent;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.List;

/**
 * 冒煙測試：模組載得起來、MCP 伺服器有回應、工具清單快照一致、暫停鍵與 HUD 基本運作。不呼叫任何 AI。
 */
@SuppressWarnings("UnstableApiUsage")
public class SmokeGameTest implements FabricClientGameTest {
	static void check(boolean cond, String msg) {
		if (!cond) throw new AssertionError(msg);
	}

	static java.nio.file.Path projectRoot() {
		for (java.nio.file.Path p = java.nio.file.Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
			if (java.nio.file.Files.exists(p.resolve("settings.gradle"))) return p;
		}
		throw new AssertionError("找不到專案根目錄(settings.gradle)");
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("SmokeGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			Agent a = Agent.get();
			check(a != null, "Agent 沒有建立");
			check(a.session.state() == Session.State.READY, "進入世界後應該是 READY，實際是 " + a.session.state());

			McpTestClient c = McpTestClient.fromRuntimeFile();
			// 工具清單快照：CLI 測試(T8)用它來比對 Claude Code 和 Codex 實際看到的工具
			var tools = c.listTools();
			// 快照從專案根目錄找(遊戲測試的工作目錄在 build 底下)；找不到就失敗，不自己建一份(M16)
			java.nio.file.Path snap = projectRoot().resolve("src/test/resources/blockagent/tools-snapshot.json");
			check(java.nio.file.Files.exists(snap), "找不到工具清單快照：" + snap);
			String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(tools.getAsJsonArray("tools")) + "\n";
			if (!java.nio.file.Files.readString(snap).equals(json)) {
				java.nio.file.Files.writeString(snap.resolveSibling("tools-snapshot.actual.json"), json);
				throw new AssertionError("工具清單和 " + snap + " 不同(新的寫在 tools-snapshot.actual.json)，確認後覆蓋快照");
			}

			// 按鍵要有翻譯，控制設定裡才不會顯示原始的 key.blockagent.*(L4)
			check(ctx.computeOnClient(mc -> net.minecraft.locale.Language.getInstance().has("key.blockagent.toggle")
				&& net.minecraft.locale.Language.getInstance().has("key.category.blockagent.main")), "BlockAgent 的按鍵沒有翻譯");

			var self = c.call(ctx, "get_self");
			check(self.ok(), "get_self 失敗：" + self.text());

			var paused = c.call(ctx, "goto", McpTestClient.args("x", 0, "y", 64, "z", 0));
			check(!paused.ok() && "PAUSED".equals(paused.code()), "READY 時動作工具應該回傳 PAUSED，實際：" + paused.text());

			ctx.getInput().pressKey(a.control.toggleKey);
			ctx.waitTicks(2);
			check(a.session.state() == Session.State.RUNNING, "按暫停鍵後應該是 RUNNING，實際是 " + a.session.state());

			// 合併掃描後各組還要分得開；火是危險(L5)。靈魂火放在靈魂沙上不會熄
			var server = sp.getServer();
			server.runCommand("execute as @p at @s run setblock ~3 ~-1 ~ soul_sand");
			server.runCommand("execute as @p at @s run setblock ~3 ~ ~ soul_fire");
			server.runCommand("execute as @p at @s run setblock ~-3 ~ ~ crafting_table");
			ctx.waitTicks(5);
			var sur = c.call(ctx, "get_surroundings");
			check(sur.ok(), "get_surroundings 失敗：" + sur.text());
			check(sur.text().contains("- fire n=") && sur.text().contains("- crafting_table n="),
				"get_surroundings 應該分別列出火和工作台：" + sur.text());
			check(sur.text().matches("(?s).*time=(day|dusk|night|dawn) (dusk|night|day)_in=\\d+(s|min) weather=.*"), "時間格式不對：" + sur.text());

			// 過完夜晚跨進白天要發 daybreak(M17)
			server.runCommand("time set 13000");
			ctx.waitTicks(5);
			server.runCommand("time set 23500");
			ctx.waitTicks(5);
			var dawn = c.call(ctx, "wait_events", McpTestClient.args("max_wait_sec", 5));
			check(dawn.text().contains("daybreak: time=day weather="), "應該收到 daybreak 事件：" + dawn.text());

			var status = c.call(ctx, "set_status", McpTestClient.args(
				"text", "在避難屋等天亮", "thought", "屋外有苦力怕。",
				"short_goal", "勘察空地", "long_goal", "建立基地"));
			check(status.ok(), "設定 HUD 失敗：" + status.text());
			ctx.waitFor(mc -> a.hud.line().contains("在避難屋等天亮") && a.hud.lines().equals(List.of(
				a.hud.line(), "目標 建立基地 › 勘察空地", "想法 屋外有苦力怕。")));

			ctx.getInput().pressKey(a.control.toggleKey);
			ctx.waitTicks(2);
			check(a.session.state() == Session.State.PAUSED, "再按一次應該是 PAUSED，實際是 " + a.session.state());
			var ev = c.call(ctx, "wait_events", McpTestClient.args("max_wait_sec", 3));
			check(ev.text().contains("[URGENT] user_paused:"), "應該收到 user_paused 事件：" + ev.text());
			System.out.println("[ok] SmokeGameTest");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
