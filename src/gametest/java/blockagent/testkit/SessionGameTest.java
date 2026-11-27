package blockagent.testkit;

import baritone.api.BaritoneAPI;
import blockagent.game.ActionLog;
import blockagent.game.Bari;
import blockagent.testkit.mixin.WindowInvoker;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * T5(暫停鍵 1 秒內停下、滑鼠與接手、暫停時反射不動，M6.1、M6.7、M7.10)、
 * T6(失焦不暫停，M6.6)、T7(離開世界時停止並通知，M6.9)。
 * 專用伺服器斷線情境併在 ServerAndCreativeGameTest 共用同一台專用伺服器。
 */
@SuppressWarnings("UnstableApiUsage")
public class SessionGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("SessionGameTest")) return;
		try {
			try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
				TestKit k = new TestKit(ctx, sp);
				BlockPos c = k.myPos();
				k.flatArena(c, 20);
				t5(k, c);
				pausedNoReflex(k);
				eatReflex(k);
				craftAndSmelt(k);
				offhandCraftAndEquip(k);
				t6(k, c);
				endSessionAndVerifyHandback(k);
				t6Control(k);
			}
			t7LeftWorld(ctx);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void t5(TestKit k, BlockPos c) {
		// 預設鍵不能和原版按鍵衝突
		boolean conflict = k.ctx.computeOnClient(mc -> {
			for (KeyMapping mine : new KeyMapping[] {k.agent().control.toggleKey, k.agent().control.cursorKey}) {
				for (KeyMapping km : mc.options.keyMappings) if (km != mine && !km.isUnbound() && km.same(mine)) return true;
			}
			return false;
		});
		check(!conflict, "T5 暫停鍵或游標鍵和其他按鍵衝突");

		// 遠處放礦，開始挖
		for (int i = 0; i < 10; i++) k.setBlock(c.offset(15, -1, -10 + i * 2), Blocks.IRON_ORE.defaultBlockState());
		k.give("minecraft:stone_pickaxe", 1);
		k.ctx.runOnClient(mc -> mc.mouseHandler.grabMouse());
		check(k.ctx.computeOnClient(mc -> mc.mouseHandler.isMouseGrabbed()), "T5 交棒前應可用滑鼠轉視角");
		k.start();
		check(k.ctx.computeOnClient(mc -> !mc.mouseHandler.isMouseGrabbed() && mc.gui.screen() == null),
			"T5 交棒給 AI 後應在遊戲畫面釋放游標");

		BlockPos table = c.offset(0, 0, 2);
		k.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState());
		var opened = k.ai.call(k.ctx, "use_block", args("x", table.getX(), "y", table.getY(), "z", table.getZ()));
		check(opened.ok(), "T5 打開工作台失敗：" + opened.text());
		k.ctx.waitForScreen(CraftingScreen.class);
		k.ctx.runOnClient(mc -> mc.player.closeContainer());
		k.ctx.waitForScreen(null);
		check(k.ctx.computeOnClient(mc -> !mc.mouseHandler.isMouseGrabbed()), "T5 關閉工作台後游標應保持釋放");

		check(k.ctx.computeOnClient(mc -> mc.isWindowActive() && mc.gui.screen() == null), "T5 滑鼠測試需要遊戲視窗有焦點");
		// 點遊戲畫面抓回游標(S8.2a)：按住 5 tick，沒被吃掉的話接手偵測一定會抓到
		k.ctx.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 5);
		k.ctx.waitTicks(2);
		check(k.ctx.computeOnClient(mc -> mc.mouseHandler.isMouseGrabbed()), "T5 AI 進行中點遊戲畫面應抓回游標");
		check(k.agent().session.isRunning(), "T5 點遊戲畫面抓回游標不應算使用者接手");
		check(k.ctx.computeOnClient(mc -> !mc.options.keyAttack.isDown()), "T5 抓回游標的那一下不應算攻擊");

		float yaw = k.ctx.computeOnClient(mc -> mc.player.getYRot());
		k.ctx.getInput().moveCursor(0, 0); // 原版會略過抓取滑鼠後的第一個移動事件
		k.ctx.getInput().moveCursor(100, 0);
		k.ctx.runOnClient(mc -> mc.mouseHandler.handleAccumulatedMovement());
		check(k.waitUntil(20, mc -> Math.abs(mc.player.getYRot() - yaw) > 2), "T5 滑鼠移動沒有轉動視角");
		k.ctx.waitTicks(2);
		check(k.agent().session.isRunning(), "T5 單純轉動視角不應暫停 AI");
		check(k.agent().control.userLooking() && BaritoneAPI.getSettings().freeLook.value, "T5 使用者轉視角時 Baritone 不應轉鏡頭");

		// 游標鍵：放開、再抓住、再放開
		k.ctx.getInput().pressKey(k.agent().control.cursorKey);
		check(k.ctx.computeOnClient(mc -> !mc.mouseHandler.isMouseGrabbed()), "T5 按游標鍵應放開游標");
		k.ctx.getInput().pressKey(k.agent().control.cursorKey);
		check(k.ctx.computeOnClient(mc -> mc.mouseHandler.isMouseGrabbed()), "T5 再按游標鍵應抓回游標");
		k.ctx.getInput().pressKey(k.agent().control.cursorKey);
		check(k.ctx.computeOnClient(mc -> !mc.mouseHandler.isMouseGrabbed()), "T5 第三次按游標鍵應放開游標");
		check(k.agent().session.isRunning(), "T5 游標鍵不應暫停 AI");

		var r = k.ai.call(k.ctx, "mine", args("block", "iron_ore", "count", 10, "wait_sec", 0));
		check(r.ok(), "T5 mine 失敗：" + r.text());
		check(k.waitUntil(100, mc -> Bari.busy() && mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.001), "T5 Baritone 沒有開始移動");
		// 使用者停止轉視角 3 秒後，鏡頭跟著前進方向(S8.2a)
		check(k.waitUntil(120, mc -> {
			if (k.agent().control.userLooking() || BaritoneAPI.getSettings().freeLook.value) return false;
			var v = mc.player.getDeltaMovement();
			double len = Math.sqrt(v.x * v.x + v.z * v.z);
			if (len < 0.05) return false;
			var look = mc.player.getLookAngle();
			double lookLen = Math.sqrt(look.x * look.x + look.z * look.z);
			return lookLen > 0.1 && (look.x * v.x + look.z * v.z) / (len * lookLen) > 0.8;
		}), "T5 AI 走路時鏡頭沒有朝向前進方向");

		k.pressToggle();
		int afterPress = TestKit.ACTIONS.get();
		k.ctx.waitTicks(20);
		boolean stopped = k.ctx.computeOnClient(mc -> !Bari.busy()
			&& !mc.options.keyUp.isDown() && !mc.options.keyJump.isDown() && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown()
			&& !mc.gameMode.isDestroying() && mc.player.getDeltaMovement().horizontalDistanceSqr() < 0.0001);
		String detail = k.ctx.computeOnClient(mc -> "busy=" + Bari.busy() + " pathing=" + Bari.b().getPathingBehavior().isPathing()
			+ " mine=" + Bari.b().getMineProcess().isActive() + " goal=" + Bari.b().getCustomGoalProcess().isActive()
			+ " up=" + mc.options.keyUp.isDown() + " jump=" + mc.options.keyJump.isDown() + " attack=" + mc.options.keyAttack.isDown()
			+ " use=" + mc.options.keyUse.isDown() + " destroying=" + mc.gameMode.isDestroying()
			+ " v=" + mc.player.getDeltaMovement().horizontalDistanceSqr());
		check(stopped, "T5 按下暫停鍵後 1 秒內沒有停下：" + detail);
		check(TestKit.ACTIONS.get() == afterPress, "T5 暫停後還送出了 " + (TestKit.ACTIONS.get() - afterPress) + " 個操作");
		check(!k.agent().tasks.hasActiveTask(), "T5 任務應該被取消");
		check(k.ctx.computeOnClient(mc -> mc.mouseHandler.isMouseGrabbed()), "T5 暫停後應恢復滑鼠轉視角");
		String ev = k.eventsUntil("user_paused:", 5);
		check(ev.contains("[URGENT] user_paused:") && ev.contains("[PROMPT] task_cancelled: task=mine"), "T5 事件：" + ev);
		System.out.println("[ok] T5 暫停後 20 tick 內停下：" + ev.strip());

		// 在遊戲畫面按下右鍵、介面打開後才放開：放開要清掉按著的狀態，恢復後不能馬上又被判成接手
		k.ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		k.ctx.waitTicks(2);
		check(k.ctx.computeOnClient(mc -> mc.mouseHandler.isRightPressed()), "T5 模擬按下右鍵沒有生效");
		k.ctx.getInput().pressKey(o -> o.keyInventory);
		check(k.waitUntil(20, mc -> mc.gui.screen() != null), "T5 物品欄沒有打開");
		k.ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		k.ctx.runOnClient(mc -> mc.player.closeContainer());
		check(k.waitUntil(20, mc -> mc.gui.screen() == null), "T5 物品欄沒有關上");
		check(k.ctx.computeOnClient(mc -> !mc.mouseHandler.isRightPressed()), "T5 介面開著時放開右鍵，狀態應該清掉");

		k.pressToggle(); // 恢復
		k.eventsUntil("user_resumed:", 5);
		k.ctx.waitTicks(5);
		check(k.agent().session.isRunning(), "T5 恢復後不應又被判成使用者接手，狀態是 " + k.agent().session.state());
		check(k.ctx.computeOnClient(mc -> !mc.mouseHandler.isMouseGrabbed()), "T5 恢復 AI 時應再次釋放游標");
	}

	/**
	 * 進食反射(docs/REVIEW.md C5)：飢餓度 10、身上有 1 塊腐肉和 10 根胡蘿蔔時吃胡蘿蔔，
	 * 不會因為結束條件改看腐肉的數量而每 2 tick 開始又中止。
	 */
	private void eatReflex(TestKit k) throws Exception {
		k.clearInventory();
		k.give("minecraft:rotten_flesh", 1);
		k.give("minecraft:carrot", 10);
		k.ctx.waitTicks(5);
		var logFile = k.agent().log.file();
		long before = Files.readAllLines(logFile).stream().filter(l -> l.contains("eat_start")).count();
		k.onServer(s -> k.me(s).getFoodData().setFoodLevel(10));
		// 先等客戶端看到飢餓度掉下來，不然一開始就以為吃飽了
		check(k.waitUntil(40, mc -> mc.player.getFoodData().getFoodLevel() <= 10), "C5 客戶端沒收到飢餓度變化");
		check(k.waitUntil(300, mc -> mc.player.getFoodData().getFoodLevel() >= 15), "C5 進食反射沒有把飢餓度吃回來");
		k.ctx.waitTicks(10);
		long starts = Files.readAllLines(logFile).stream().filter(l -> l.contains("eat_start")).count() - before;
		int carrots = k.ctx.computeOnClient(mc -> blockagent.game.Inv.count(mc.player, net.minecraft.world.item.Items.CARROT));
		int flesh = k.ctx.computeOnClient(mc -> blockagent.game.Inv.count(mc.player, net.minecraft.world.item.Items.ROTTEN_FLESH));
		check(flesh == 1, "C5 飢餓度還高時不應該吃腐肉");
		check(carrots < 10, "C5 應該吃了胡蘿蔔");
		check(starts <= (10 - carrots) + 1, "C5 進食反射反覆開始又中止：開始 " + starts + " 次，只吃了 " + (10 - carrots) + " 根胡蘿蔔");
		k.clearInventory();
		k.onServer(s -> k.me(s).getFoodData().setFoodLevel(20));
		k.ctx.waitTicks(5);
		k.agent().events.drainAll();
		System.out.println("[ok] C5 進食反射吃了 " + (10 - carrots) + " 根胡蘿蔔，開始 " + starts + " 次");
	}

	/**
	 * 線 2b(docs/REVIEW.md C6、C7、H7)：
	 * 雪塊要 4 顆雪球、雪球一疊只有 16 顆，做 20 個要分兩批，不能一次塞 20 顆；
	 * 用木鏟(一疊 1 個)當燃料燒 2 個粗鐵要分兩次補燃料，燒到一半被反射暫停(關掉熔爐畫面)後要自己重新打開接著做。
	 */
	private void craftAndSmelt(TestKit k) throws Exception {
		k.clearInventory();
		k.give("minecraft:snowball", 80);
		k.ctx.waitTicks(5);
		var craft = k.ai.call(k.ctx, "craft", args("item", "snow_block", "count", 20));
		String craftText = craft.text().contains("task_done:") || craft.text().contains("task_failed:") ? craft.text()
			: craft.text() + "\n" + k.eventsUntil("task_done:", 30);
		check(craftText.contains("task_done: task=craft"), "C7 雪塊應該做好：" + craftText);
		int snow = k.ctx.computeOnClient(mc -> blockagent.game.Inv.count(mc.player, net.minecraft.world.item.Items.SNOW_BLOCK));
		check(snow == 20, "C7 應該做出 20 個雪塊，實際 " + snow);
		System.out.println("[ok] C7 雪塊分批做好 20 個");

		k.clearInventory();
		k.give("minecraft:raw_iron", 2);
		k.give("minecraft:wooden_shovel", 2);
		k.give("minecraft:furnace", 1);
		k.ctx.waitTicks(5);
		var smelt = k.ai.call(k.ctx, "smelt", args("item", "raw_iron", "count", 2, "fuel", "wooden_shovel", "wait_sec", 0));
		check(smelt.ok(), "smelt 開始失敗：" + smelt.text());
		check(k.waitUntil(200, mc -> mc.player.containerMenu instanceof net.minecraft.world.inventory.FurnaceMenu
			&& !mc.player.containerMenu.getSlot(0).getItem().isEmpty()), "H7 熔爐沒有開始燒");
		// 模擬反射接手：暫停任務(關掉熔爐畫面)，過一下再恢復
		k.ctx.runOnClient(mc -> k.agent().tasks.suspend());
		k.ctx.waitTicks(10);
		check(k.ctx.computeOnClient(mc -> mc.player.containerMenu == mc.player.inventoryMenu), "H7 暫停時應該關掉熔爐畫面");
		k.ctx.runOnClient(mc -> k.agent().tasks.resume());
		String ev = k.eventsUntil("task_done: task=smelt", 60);
		check(ev.contains("task_done: task=smelt"), "C6/C7/H7 熔煉應該完成：" + ev);
		int ingots = k.ctx.computeOnClient(mc -> blockagent.game.Inv.count(mc.player, net.minecraft.world.item.Items.IRON_INGOT));
		check(ingots == 2, "C6/C7 應該燒出 2 個鐵錠，實際 " + ingots);
		k.clearInventory();
		k.agent().events.drainAll();
		System.out.println("[ok] C6/C7/H7 木鏟分兩次補燃料，暫停後重新打開熔爐，燒出 2 個鐵錠");
	}

	/**
	 * R12：材料在副手時，craft 能自動將副手材料換回主背包完成合成，equip(mainhand) 也能直接換到主手。
	 */
	private void offhandCraftAndEquip(TestKit k) {
		k.clearInventory();
		k.give("minecraft:jungle_log", 4);
		k.ctx.waitTicks(5);
		check(k.ai.call(k.ctx, "equip", args("item", "jungle_log", "slot", "offhand")).ok(), "R12 放原木到副手失敗");
		var craft = k.ai.call(k.ctx, "craft", args("item", "jungle_planks", "count", 4));
		String craftText = craft.text().contains("task_done:") || craft.text().contains("task_failed:") ? craft.text()
			: craft.text() + "\n" + k.eventsUntil("task_done:", 20);
		check(craft.ok() && craftText.contains("task_done: task=craft"), "R12 副手有原木時應能合成木板：" + craftText);
		int planks = k.ctx.computeOnClient(mc -> blockagent.game.Inv.countWithOffhand(mc.player, net.minecraft.world.item.Items.JUNGLE_PLANKS));
		check(planks >= 4, "R12 應該做出至少 4 個叢林木板，實際 " + planks);

		k.clearInventory();
		k.give("minecraft:oak_log", 2);
		k.ctx.waitTicks(5);
		check(k.ai.call(k.ctx, "equip", args("item", "oak_log", "slot", "offhand")).ok(), "R12 放橡木原木到副手失敗");
		var eqMain = k.ai.call(k.ctx, "equip", args("item", "oak_log", "slot", "mainhand"));
		check(eqMain.ok(), "R12 equip(mainhand) 應該找得到副手物品：" + eqMain.text());
		check(k.ctx.computeOnClient(mc -> mc.player.getMainHandItem().is(net.minecraft.world.item.Items.OAK_LOG)), "R12 橡木原木應該換到主手");

		k.clearInventory();
		k.agent().events.drainAll();
		System.out.println("[ok] R12 副手材料合成與 equip(mainhand) 驗證通過");
	}

	/** 暫停時反射絕對不能動(原 T10 暫停檢查，M7.10)。 */
	private void pausedNoReflex(TestKit k) throws Exception {
		k.pressToggle(); // 暫停
		k.eventsUntil("user_paused:", 3);
		var logFile = k.agent().log.file();
		long before = Files.readAllLines(logFile).stream().filter(l -> l.contains("\"source\":\"" + ActionLog.Source.reflex + "\"")).count();
		k.give("minecraft:bread", 3);
		k.onServer(s -> {
			k.me(s).getFoodData().setFoodLevel(6);
			k.me(s).setRemainingFireTicks(100);
		});
		k.ctx.waitTicks(60);
		long after = Files.readAllLines(logFile).stream().filter(l -> l.contains("\"source\":\"" + ActionLog.Source.reflex + "\"")).count();
		check(after == before, "T10 暫停時反射不應該動(多了 " + (after - before) + " 筆反射紀錄)");
		check(k.ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel()) <= 6, "T10 暫停時不應該自己吃東西");
		k.onServer(s -> {
			var p = k.me(s);
			p.setRemainingFireTicks(0);
			p.setHealth(p.getMaxHealth());
			p.getFoodData().setFoodLevel(20);
		});
		k.ctx.waitTicks(5);
		k.pressToggle(); // 恢復
		k.agent().events.drainAll();
		System.out.println("[ok] 暫停時反射沒有動作");
	}

	private void t6(TestKit k, BlockPos c) {
		BlockPos target = c.offset(-18, 0, 0);
		var r = k.ai.call(k.ctx, "goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ(), "wait_sec", 0));
		check(r.ok(), "T6 goto 失敗：" + r.text());
		k.ctx.waitTicks(10);
		double x0 = k.ctx.computeOnClient(mc -> mc.player.getX());
		unfocusFor(k, 1500);
		boolean ok = k.ctx.computeOnClient(mc -> mc.gui.screen() == null && !mc.isPaused());
		double x1 = k.ctx.computeOnClient(mc -> mc.player.getX());
		check(ok, "T6 失焦後跳出了選單或遊戲暫停了");
		check(Math.abs(x1 - x0) > 2, "T6 失焦後應該繼續走(移動 " + Math.abs(x1 - x0) + " 格)");
		check(k.agent().tasks.hasActiveTask() || k.eventsUntil("task_done: task=goto", 1).contains("task_done: task=goto"), "T6 任務不應該被中斷");
		check(k.ctx.computeOnClient(mc -> mc.options.pauseOnLostFocus), "T6 不應該改使用者的 pauseOnLostFocus");
		System.out.println("[ok] T6 失焦 1.5 秒，沒有暫停，移動了 " + Math.round(Math.abs(x1 - x0)) + " 格");

		k.ai.call(k.ctx, "stop");
		// 焦點設回來：T7 要確認結束時交還滑鼠，失焦時抓不到滑鼠
		k.ctx.runOnClient(mc -> ((WindowInvoker) (Object) mc.getWindow()).blockagent$setFocused(true));
		k.ctx.waitTicks(5);
	}

	/**
	 * T6 對照組：工作階段結束後 Mixin 不再介入，同樣的步驟會打開暫停選單(否則 T6 證明不了什麼)。
	 * 正式 jar 不放測試開關(docs/REVIEW.md M8)，所以改在 T7 結束工作階段之後做。
	 */
	private void t6Control(TestKit k) {
		check(!k.agent().session.inWorldSession(), "T6 對照組要在工作階段結束後執行");
		try {
			unfocusFor(k, 1000);
			boolean pausedScreen = k.ctx.computeOnClient(mc -> mc.gui.screen() instanceof PauseScreen);
			check(pausedScreen, "T6 對照組：工作階段結束後失焦應該跳出暫停選單(否則 T6 證明不了什麼)");
			System.out.println("[ok] T6 對照組：Mixin 不介入時會跳出暫停選單");
		} finally {
			k.ctx.runOnClient(mc -> {
				((WindowInvoker) (Object) mc.getWindow()).blockagent$setFocused(true);
				mc.gui.setScreen(null);
			});
			k.ctx.waitTicks(5);
		}
	}

	/** 用真實時間模擬失焦(原版的判斷是失焦超過 500 ms)；每個 tick 都重設，避免作業系統的焦點事件把它改回來。 */
	private static void unfocusFor(TestKit k, long ms) {
		long end = System.currentTimeMillis() + ms;
		boolean everUnfocused = false;
		while (System.currentTimeMillis() < end) {
			k.ctx.runOnClient(mc -> ((WindowInvoker) (Object) mc.getWindow()).blockagent$setFocused(false));
			everUnfocused |= !k.ctx.computeOnClient(mc -> mc.getWindow().isFocused());
			k.ctx.waitTick();
		}
		check(everUnfocused, "T6 視窗的焦點狀態沒有變成 false，模擬失焦無效");
	}

	/** 結束工作階段後：停止接受動作且不再放開使用者按住的移動鍵(供接著跑 T6 對照組)。 */
	private void endSessionAndVerifyHandback(TestKit k) {
		k.ctx.runOnClient(_ -> k.agent().session.end(blockagent.core.Session.EndReason.LEFT_WORLD));
		k.ctx.waitTicks(2);
		k.expectError(k.ai.call(k.ctx, "goto", args("x", 0, "y", 0, "z", 0)), "SESSION_ENDED", "工作階段結束後不接受動作");

		var start = k.ctx.computeOnClient(mc -> mc.player.position());
		k.ctx.getInput().holdKeyFor(o -> o.keyUp, 20);
		double moved = k.ctx.computeOnClient(mc -> mc.player.position().distanceTo(start));
		check(moved > 2.0, "結束後使用者按住前進鍵 1 秒只移動了 " + moved + " 格(按鍵被放開了？)");
	}

	private void t7LeftWorld(ClientGameTestContext ctx) throws Exception {
		McpTestClient ai;
		CompletableFuture<McpTestClient.Result> wait;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			k.start();
			ai = k.ai;
			wait = ai.callAsync("wait_events", args("max_wait_sec", 60));
			ctx.waitTicks(5);
		} // 離開世界
		for (int i = 0; i < 40 && !wait.isDone(); i++) ctx.waitTick();
		check(wait.isDone(), "T7(b) 離開世界後 wait_events 沒有在 2 秒內返回");
		String text = wait.join().text();
		check(text.contains("left_world"), "T7(b) 應該收到 left_world：" + text);
		var r = ai.call(ctx, "goto", args("x", 0, "y", 0, "z", 0));
		check(!r.ok() && "SESSION_ENDED".equals(r.code()), "T7(b) 離開後不接受動作：" + r.text());
		System.out.println("[ok] T7(b) 離開世界：" + text.strip());
	}
}
