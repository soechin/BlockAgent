package blockagent.testkit;

import blockagent.game.Bari;
import blockagent.game.ChatGateway;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * T3(朋友說話與私訊過濾，M1.5、M2.5)與 T4(AI 發言與 Mixin 最後防線，M4.2–M4.5)。
 * ChatPolicy 的細部規則(指令變形、頻率限制、長度截斷)由單元測試 CoreLogicTest 覆蓋。
 */
@SuppressWarnings("UnstableApiUsage")
public class ChatGameTest implements FabricClientGameTest {
	/** 伺服器收到的聊天(伺服器端紀錄)。 */
	static final List<String> SERVER_CHAT = new CopyOnWriteArrayList<>();
	/** 伺服器收到的 /me、/say(伺服器端紀錄)，用來確認指令有沒有送到。 */
	static final List<String> SERVER_SAY = new CopyOnWriteArrayList<>();
	static volatile boolean registered;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("ChatGameTest")) return;
		if (!registered) {
			registered = true;
			ServerMessageEvents.CHAT_MESSAGE.register((msg, sender, params) -> SERVER_CHAT.add(msg.signedContent()));
			ServerMessageEvents.COMMAND_MESSAGE.register((msg, source, params) -> SERVER_SAY.add(msg.signedContent()));
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			k.flatArena(c, 8);
			TestKit.Friend ming = k.spawnFriend("Ming", c.offset(3, 0, 0));
			k.start();
			t3(k, ming);
			t4(k);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void t3(TestKit k, TestKit.Friend ming) {
		// (a) 朋友公開說話：AI 收到原文
		int before = TestKit.ACTIONS.get();
		ming.say("好餓");
		String ev = k.eventsUntil("好餓", 20);
		check(ev.contains("chat: Ming=\"好餓\"") || ev.contains("Ming=\"好餓\""), "T3(a) 應該收到原文「好餓」，實際事件：" + ev);
		System.out.println("[ok] T3(a) 收到：" + ev.strip());

		// (b) 說話後模組沒有自動做任何事
		k.ctx.waitTicks(20);
		check(TestKit.ACTIONS.get() == before, "T3(b) 朋友說話後模組送出了 " + (TestKit.ACTIONS.get() - before) + " 個操作");
		check(!k.ctx.computeOnClient(mc -> Bari.busy()), "T3(b) Baritone 不應該啟動");
		check(!k.agent().tasks.hasActiveTask(), "T3(b) 不應該有任務");

		// (c) 三種私訊都不會進到事件佇列或聊天紀錄
		k.agent().events.drainAll();
		ming.whisper("秘密一號");
		ming.teamWhisper("秘密二號");
		ming.pluginWhisper("秘密三號");
		k.ctx.waitTicks(10);
		String queued = k.agent().events.drainAll().toString();
		var chat = k.ai.call(k.ctx, "get_chat", args("count", 100));
		for (String secret : List.of("秘密一號", "秘密二號", "秘密三號")) {
			check(!queued.contains(secret), "T3(c) 私訊「" + secret + "」出現在事件佇列裡");
			check(!chat.text().contains(secret), "T3(c) 私訊「" + secret + "」出現在 get_chat 裡");
		}
		System.out.println("[ok] T3(b)(c) 沒有自動行為，三種私訊都沒有交給 AI");
	}

	private void t4(TestKit k) {
		var a = k.agent();
		a.chatPolicy.setMinIntervalMs(0);
		SERVER_CHAT.clear();

		var ok = k.ai.call(k.ctx, "say", args("text", "你好"));
		check(ok.ok(), "T4 say 失敗：" + ok.text());
		k.ctx.waitTicks(10);
		check(SERVER_CHAT.contains("[AI] 你好"), "T4 伺服器應該收到「[AI] 你好」，實際：" + SERVER_CHAT);

		k.expectError(k.ai.call(k.ctx, "say", args("text", "/give @s diamond")), "CHAT_BLOCKED", "T4 擋下指令");

		// 最後防線(ClientPacketListenerMixin)：AI 發言中不加前綴就送出 → 被丟棄；送指令 → 被丟棄
		k.ctx.runOnClient(mc -> ChatGateway.withAiFlag(() -> {
			mc.getConnection().sendChat("沒有前綴");
			mc.getConnection().sendCommand("give @s diamond");
		}));
		k.ctx.waitTicks(10);
		check(!SERVER_CHAT.contains("沒有前綴"), "T4 沒有前綴的發言應該被最後防線丟棄");
		check(SERVER_CHAT.stream().allMatch(m -> m.startsWith("[AI] ")), "T4 伺服器收到的每一則都要有 [AI]：" + SERVER_CHAT);
		System.out.println("[ok] T4 伺服器收到的全部聊天：" + SERVER_CHAT);

		// H6：AI 在控制時預設要檢查，沒有包 withAiFlag 的聊天和指令(含點連結的 sendUnattendedCommand)也一樣
		SERVER_SAY.clear();
		k.ctx.runOnClient(mc -> {
			mc.getConnection().sendChat("沒有旗標");
			mc.getConnection().sendCommand("me 沒有旗標的指令");
			mc.getConnection().sendUnattendedCommand("me 點連結的指令", null);
		});
		k.ctx.waitTicks(10);
		check(!SERVER_CHAT.contains("沒有旗標"), "H6 AI 控制中沒有前綴的聊天應該被丟棄：" + SERVER_CHAT);
		check(SERVER_SAY.isEmpty(), "H6 AI 控制中的指令應該被丟棄：" + SERVER_SAY);
		// 使用者自己在聊天畫面打的放行(聊天和指令都是)
		k.ctx.runOnClient(mc -> {
			var screen = new net.minecraft.client.gui.screens.ChatScreen("", false);
			screen.handleChatInput("使用者自己說的", false);
			screen.handleChatInput("/me 使用者的指令", false);
		});
		k.ctx.waitTicks(10);
		check(SERVER_CHAT.contains("使用者自己說的"), "H6 使用者從聊天畫面打的應該送出：" + SERVER_CHAT);
		check(SERVER_SAY.contains("使用者的指令"), "H6 使用者從聊天畫面打的指令應該送出：" + SERVER_SAY);
		System.out.println("[ok] H6 預設檢查：其他來源的聊天和指令被丟棄，使用者從聊天畫面送出的放行");
	}
}
