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
			// 驗證 tall_grass 掃描不會觸發 Baritone NPE(P2-7)，以及近處生物列出座標與幼體數(P1-5)
			server.runCommand("execute as @p at @s run setblock ~2 ~ ~2 tall_grass[half=lower]");
			server.runCommand("execute as @p at @s run setblock ~2 ~1 ~2 tall_grass[half=upper]");
			server.runCommand("execute as @p at @s run summon minecraft:chicken ~2 ~ ~-2 {NoAI:1b}");
			server.runCommand("execute as @p at @s run summon minecraft:chicken ~3 ~ ~-2 {NoAI:1b,Age:-24000}");
			ctx.waitTicks(5);
			var sur = c.call(ctx, "get_surroundings", McpTestClient.args("radius", 16, "detail", true));
			check(sur.ok(), "get_surroundings 失敗：" + sur.text());
			check(sur.text().contains("- fire n=") && sur.text().contains("- crafting_table n="),
				"get_surroundings 應該分別列出火和工作台：" + sur.text());
			check(sur.text().contains("chicken") && sur.text().contains("babies=1")
				&& sur.text().contains("at=") && sur.text().contains(":baby"),
				"get_surroundings 近處應列出生物座標與幼體狀態：" + sur.text());
			check(sur.text().matches("(?s).*time=(day|dusk|night|dawn) (dusk|night|day)_in=\\d+(s|min) weather=.*"), "時間格式不對：" + sur.text());

			var grass = c.call(ctx, "find_blocks", McpTestClient.args("type", "short_grass,tall_grass", "radius", 16));
			check(grass.ok() && grass.text().contains("tall_grass"), "find_blocks 應該找到 tall_grass 且無異常：" + grass.text());

			// 驗證一層薄雪(snow[layers=1])：clear_area 射線要打得到、build_blueprint 要能覆蓋成功(P0-2)
			var base = ctx.computeOnClient(mc -> mc.player.blockPosition());
			int bx = base.getX(), by = base.getY(), bz = base.getZ();
			server.runCommand("fill " + (bx - 2) + " " + (by - 1) + " " + (bz + 2) + " " + (bx - 1) + " " + (by - 1) + " " + (bz + 3) + " minecraft:stone");
			server.runCommand("fill " + (bx - 2) + " " + by + " " + (bz + 2) + " " + (bx - 1) + " " + (by + 1) + " " + (bz + 3) + " minecraft:air");
			server.runCommand("setblock " + (bx - 2) + " " + by + " " + (bz + 2) + " minecraft:snow[layers=1]");
			server.runCommand("setblock " + (bx - 1) + " " + by + " " + (bz + 2) + " minecraft:snow[layers=1]");
			ctx.waitTicks(5);
			var cleared = c.call(ctx, "clear_area", McpTestClient.args(
				"block", "snow",
				"from", (bx - 2) + "," + by + "," + (bz + 2),
				"to", (bx - 1) + "," + by + "," + (bz + 2),
				"wait_sec", 30));
			check(cleared.ok() && cleared.text().contains("cleared=2") && cleared.text().contains("unreachable=0"),
				"clear_area 應該清掉兩格一層雪：" + cleared.text());

			server.runCommand("setblock " + (bx - 2) + " " + by + " " + (bz + 3) + " minecraft:snow[layers=1]");
			server.runCommand("setblock " + (bx - 1) + " " + by + " " + (bz + 3) + " minecraft:snow[layers=1]");
			server.runCommand("give @p minecraft:cobblestone 8");
			ctx.waitTicks(5);
			var parts = new com.google.gson.JsonArray();
			var part = new com.google.gson.JsonObject();
			part.addProperty("from", "0,0,0");
			part.addProperty("to", "1,0,0");
			part.addProperty("block", "cobblestone");
			parts.add(part);
			var bpArgs = McpTestClient.args("action", "create", "name", "雪地矮牆",
				"origin", (bx - 2) + "," + by + "," + (bz + 3), "wait_sec", 30);
			bpArgs.add("parts", parts);
			var built = c.call(ctx, "build_blueprint", bpArgs);
			check(built.ok() && built.text().contains("result=built"),
				"build_blueprint 應該清掉一層雪並完成放置：" + built.text());

			// 驗證 mine 開工自動把主背包裡的鐵鎬換上快捷列，不拿快捷列的木鎬挖爆鐵礦(P0-1)
			server.runCommand("clear @p");
			server.runCommand("item replace entity @p hotbar.0 with minecraft:wooden_pickaxe");
			for (int slot = 1; slot < 9; slot++) {
				server.runCommand("item replace entity @p hotbar." + slot + " with minecraft:dirt");
			}
			server.runCommand("item replace entity @p inventory.6 with minecraft:iron_pickaxe");
			server.runCommand("setblock " + (bx + 2) + " " + by + " " + bz + " minecraft:iron_ore");
			server.runCommand("setblock " + (bx + 2) + " " + (by + 1) + " " + bz + " minecraft:iron_ore");
			ctx.waitTicks(5);
			var mined = c.call(ctx, "mine", McpTestClient.args("block", "iron_ore", "count", 2, "radius", 8, "wait_sec", 30));
			check(mined.ok() && mined.text().contains("task_done: task=mine") && mined.text().contains("mined=2/2"),
				"mine 應該成功挖完 2 塊鐵礦：" + mined.text());
			int rawIron = ctx.computeOnClient(mc -> blockagent.game.Inv.count(mc.player, net.minecraft.world.item.Items.RAW_IRON));
			boolean ironInHotbar = ctx.computeOnClient(mc -> {
				for (int i = 0; i < 9; i++) {
					if (mc.player.getInventory().getItem(i).is(net.minecraft.world.item.Items.IRON_PICKAXE)) return true;
				}
				return false;
			});
			int woodDamage = ctx.computeOnClient(mc -> {
				for (int i = 0; i < blockagent.game.Inv.MAIN_SIZE; i++) {
					var s = mc.player.getInventory().getItem(i);
					if (s.is(net.minecraft.world.item.Items.WOODEN_PICKAXE)) return s.getDamageValue();
				}
				return -1;
			});
			check(ironInHotbar && rawIron >= 2 && woodDamage == 0,
				"鐵鎬應該已換進快捷列、取得 2 個粗鐵且木鎬無損(inHotbar=" + ironInHotbar + ", rawIron=" + rawIron + ", woodDamage=" + woodDamage + ")");

			// 過完夜晚跨進白天要發 daybreak(M17)
			server.runCommand("time set 13000");
			ctx.waitTicks(5);
			server.runCommand("time set 23500");
			ctx.waitTicks(5);
			var dawn = c.call(ctx, "wait_events", McpTestClient.args("max_wait_sec", 5));
			check(dawn.text().contains("daybreak: time=day weather="), "應該收到 daybreak 事件：" + dawn.text());

			var status = c.call(ctx, "set_status", McpTestClient.args("text", "屋外有苦力怕，在避難屋等天亮"));
			check(status.ok(), "設定 HUD 失敗：" + status.text());
			ctx.waitFor(mc -> !a.hud.line().contains("苦力怕")
				&& a.hud.lines().equals(List.of(a.hud.line(), "屋外有苦力怕，在避難屋等天亮")));

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
