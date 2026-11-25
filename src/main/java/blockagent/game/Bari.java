package blockagent.game;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;

/** Baritone 的存取與固定設定(docs/SPEC.md S1.4)。 */
public final class Bari {
	private Bari() {
	}

	public static IBaritone b() {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}

	public static void applySettings() {
		Settings s = BaritoneAPI.getSettings();
		s.chatControl.value = false;
		s.chatControlAnyway.value = false;
		s.prefixControl.value = false;
		s.allowInventory.value = false;
		s.buildIgnoreExisting.value = true;
		s.allowParkour.value = false;
		s.allowParkourPlace.value = false;
		Agent agent = Agent.get();
		s.freeLook.value = agent != null && agent.control.userLooking();
		s.chatDebug.value = false;
		s.desktopNotifications.value = false;
		s.allowBreak.value = true;
		s.allowPlace.value = true;
		// 蓋建時不挖掉已經蓋好的方塊(預設只是成本乘 10，必要時還是會挖)
		s.breakCorrectBlockPenaltyMultiplier.value = 1_000_000d;
		// 不拿劍挖方塊(2026-11-22 試玩：拿鐵劍砍樹、挖石頭，白白耗損耐久)
		s.useSwordToMine.value = false;
		// 尋路避開敵對生物(S9.1b)：8 格內有怪的路線成本較高。Baritone 已排除沒生氣的終界使者、殭屍化豬布林和亮處的蜘蛛
		s.avoidance.value = true;
		// 任務自己要的設定(清空區域、拆鷹架時不准挖和放；docs/REVIEW.md H10：重設時不能把它們改回去)
		var task = agent == null ? null : agent.tasks.current();
		if (task != null) task.adjustBaritone(s);
	}

	/** 每 20 tick 檢查一次，被改動就改回去。 */
	public static void enforceSettings(long tick) {
		if (tick % 20 != 0) return;
		Settings s = BaritoneAPI.getSettings();
		if (s.chatControl.value || s.chatControlAnyway.value || s.prefixControl.value || s.allowInventory.value
			|| !s.buildIgnoreExisting.value || s.allowParkour.value || s.allowParkourPlace.value
			|| s.breakCorrectBlockPenaltyMultiplier.value < 1_000_000d || !s.avoidance.value
			|| s.chatDebug.value || s.desktopNotifications.value || s.useSwordToMine.value) {
			applySettings();
		}
	}

	/**
	 * 走路時鏡頭跟著前進方向(freeLook=false)；使用者用滑鼠看別的地方時改成 true，
	 * Baritone 只改移動方向、不動鏡頭(S1.4、S8.2a)。
	 */
	public static void syncFreeLook(boolean userLooking) {
		Settings s = BaritoneAPI.getSettings();
		if (s.freeLook.value != userLooking) s.freeLook.value = userLooking;
	}

	/** 取消所有 Baritone 行為並清空輸入覆蓋(S8.2 第 2 步)。 */
	public static void stopAll() {
		IBaritone b = b();
		b.getPathingBehavior().cancelEverything();
		b.getMineProcess().cancel();
		// setGoal(null) 不會把流程狀態清掉(isActive 仍是 true)，要用 onLostControl
		b.getCustomGoalProcess().onLostControl();
		b.getGetToBlockProcess().onLostControl();
		b.getBuilderProcess().onLostControl();
		b.getMineProcess().onLostControl();
		b.getInputOverrideHandler().clearAllKeys();
	}

	/**
	 * 裝的是不是 unoptimized 版的 Baritone(H10)。混淆版(baritone-standalone-fabric)的類別名稱被改掉了，
	 * 模組的三個 Baritone Mixin 會靜默略過，守門員在尋路時就不會生效，所以啟動時要明講。
	 */
	public static boolean mixinTargetsPresent() {
		ClassLoader cl = Bari.class.getClassLoader();
		for (String c : new String[]{"baritone.pathing.movement.CalculationContext", "baritone.process.BuilderProcess", "baritone.utils.ToolSet"}) {
			try {
				Class.forName(c, false, cl);
			} catch (ClassNotFoundException | LinkageError e) {
				return false;
			}
		}
		return true;
	}

	public static boolean busy() {
		IBaritone b = b();
		return b.getPathingBehavior().isPathing() || b.getMineProcess().isActive() || b.getCustomGoalProcess().isActive()
			|| b.getGetToBlockProcess().isActive() || b.getBuilderProcess().isActive();
	}

	/**
	 * 遊戲關閉時關掉 Baritone 的執行緒池。它的執行緒不是 daemon，不關的話 JVM 不會結束，
	 * 26.3 的 ClientShutdownWatchdog 會因此產生一份 crash report。
	 */
	public static void shutdownExecutor() {
		try {
			Object ex = Class.forName("baritone.Baritone").getMethod("getExecutor").invoke(null);
			if (ex instanceof java.util.concurrent.ExecutorService es) es.shutdownNow();
		} catch (ReflectiveOperationException | RuntimeException e) {
			blockagent.BlockAgentMod.LOG.warn("關閉 Baritone 執行緒池失敗：{}", e.toString());
		}
	}
}
