package blockagent.game;

import blockagent.core.Event;
import blockagent.core.Session;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * 暫停鍵、畫面收合、使用者接手(docs/SPEC.md S8.2、S8.3、S8.5)。
 */
public final class Control {
	public static final KeyMapping.Category CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("blockagent", "main"));

	private final Agent agent;
	public KeyMapping toggleKey;
	public KeyMapping hudKey;
	public KeyMapping chatKey;
	public KeyMapping cursorKey;
	private volatile boolean hudCollapsed;
	/** AI 進行中，使用者自己抓回了游標(點遊戲畫面或按游標鍵)。關閉 AI 開的畫面後要照樣抓回去。 */
	private volatile boolean userGrabbed;
	/** 使用者最後一次用滑鼠轉視角的時間(毫秒)。 */
	private volatile long lastUserTurnMs = Long.MIN_VALUE / 2;
	/** 使用者轉視角後，這段時間內鏡頭不跟著 AI 走(S8.2a)。 */
	static final long LOOK_AROUND_MS = 3000;

	Control(Agent agent) {
		this.agent = agent;
	}

	public void registerKeys() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blockagent.toggle",
			InputConstants.Type.KEYBOARD, InputConstants.KEY_GRAVE, CATEGORY));
		hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blockagent.hud",
			InputConstants.Type.KEYBOARD, InputConstants.KEY_BACKSLASH, CATEGORY));
		chatKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blockagent.chat",
			InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		cursorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blockagent.cursor",
			InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT, CATEGORY));
	}

	public boolean hudCollapsed() {
		return hudCollapsed;
	}

	void tick(Minecraft mc) {
		while (toggleKey.consumeClick()) onToggle();
		while (hudKey.consumeClick()) hudCollapsed = !hudCollapsed;
		while (cursorKey.consumeClick()) toggleCursor(mc);
		while (chatKey.consumeClick()) {
			boolean on = !agent.chatPolicy.enabled();
			agent.chatPolicy.setEnabled(on);
			agent.events.add("chat_toggled", Event.Level.PROMPT, on ? "chat=on" : "chat=off | say will be rejected | hint=act without chatting");
			agent.log.log(ActionLog.Source.user, "chat_toggle", on ? "on" : "off");
		}
		if (agent.session.isRunning() && agent.config.takeoverDetection) {
			String key = pressedPlayKey(mc);
			if (key != null) takeover(key);
		}
		if (agent.session.isRunning()) Bari.syncFreeLook(userLooking());
	}

	// ---- 游標與鏡頭(S8.2a) ----

	/** 游標鍵：抓住游標轉視角，或放開游標移出遊戲視窗。只在遊戲畫面(沒開介面)有作用。 */
	private void toggleCursor(Minecraft mc) {
		if (mc.player == null || mc.gui.screen() != null || mc.gui.overlay() != null) return;
		if (mc.mouseHandler.isMouseGrabbed()) {
			userGrabbed = false;
			mc.mouseHandler.releaseMouse();
		} else {
			userGrabbed = true;
			mc.mouseHandler.grabMouse();
		}
	}

	/** AI 進行中，使用者點遊戲畫面抓回了游標(MouseHandlerMixin)。 */
	public void onUserGrabbedMouse() {
		userGrabbed = true;
	}

	/** AI 進行中關閉畫面時，要不要照原版抓回游標(GuiMixin)。 */
	public boolean userGrabbed() {
		return userGrabbed;
	}

	/** 使用者用滑鼠轉了視角(MouseHandlerMixin)。立刻讓 Baritone 不再轉鏡頭，免得下一個 tick 被轉回去。 */
	public void onUserTurn() {
		lastUserTurnMs = System.currentTimeMillis();
		if (agent.session.isRunning()) Bari.syncFreeLook(true);
	}

	/** 使用者最近有沒有在用滑鼠看別的地方。 */
	public boolean userLooking() {
		return System.currentTimeMillis() - lastUserTurnMs < LOOK_AROUND_MS;
	}

	/** 開始或恢復 AI 時放開游標(S8.2)。 */
	void releaseMouse() {
		userGrabbed = false;
		Minecraft.getInstance().mouseHandler.releaseMouse();
	}

	/** 暫停鍵(S8.2)。 */
	public void onToggle() {
		Session.State after = agent.session.toggle("暫停鍵");
		agent.log.log(ActionLog.Source.user, "toggle", after.name());
		if (after == Session.State.PAUSED) {
			stopEverything("paused by user");
			// 暫停時已經試著抓回游標，但那時任務開的容器畫面還開著；關掉之後再抓一次(H11)
			restoreMouse();
			agent.events.add("user_paused", Event.Level.URGENT, "all actions and reflexes stopped | hint=keep calling wait_events until user_resumed");
		}
	}

	/** 使用者接手(S8.5)。 */
	void takeover(String key) {
		if (!agent.session.pause("使用者接手")) return;
		stopEverything("user took over");
		restoreMouse();
		agent.log.log(ActionLog.Source.user, "takeover", key);
		agent.events.add("user_took_over", Event.Level.URGENT, "user is controlling the character | hint=keep calling wait_events until user_resumed");
	}

	/** 只在遊戲視窗有焦點且沒有開畫面時，偵測實際按下的角色操作鍵；回傳按鍵名稱(例如 key.use)，沒有就回傳 null。 */
	private String pressedPlayKey(Minecraft mc) {
		if (mc.gui.screen() != null || !mc.isWindowActive() || mc.player == null) return null;
		var o = mc.options;
		KeyMapping[] keys = {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keyAttack, o.keyUse};
		for (KeyMapping k : keys) {
			InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(k);
			if (bound.getType() == InputConstants.Type.KEYBOARD && bound.getValue() > 0 && InputConstants.isKeyDown(bound.getValue())) {
				return k.getName();
			}
			if (bound.getType() == InputConstants.Type.MOUSE) {
				int v = bound.getValue();
				if (v == InputConstants.MOUSE_BUTTON_LEFT && mc.mouseHandler.isLeftPressed()
					|| v == InputConstants.MOUSE_BUTTON_RIGHT && mc.mouseHandler.isRightPressed()
					|| v == InputConstants.MOUSE_BUTTON_MIDDLE && mc.mouseHandler.isMiddlePressed()) {
					return k.getName();
				}
			}
		}
		return null;
	}

	/** 在遊戲畫面取回視角控制。選單開著時由原版在關閉選單後恢復。 */
	void restoreMouse() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.level != null && mc.gui.screen() == null && mc.gui.overlay() == null) {
			mc.mouseHandler.grabMouse();
		}
	}

	/** 停下所有動作(S8.2 第 2–5 步)。 */
	public void stopEverything(String reason) {
		Minecraft mc = Minecraft.getInstance();
		try {
			Bari.stopAll();
		} catch (RuntimeException ignored) {
			// Baritone 還沒初始化(例如不在世界裡)
		}
		KeyMapping.releaseAll();
		if (mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
			if (mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
		}
		// 取消任務之後就問不到它開過容器了，要先記下來(H11：原本這個檢查永遠是 false)
		boolean taskContainer = agent.tasks.openedContainer();
		// 先透過 stopAll 停下所有反射，再取消任務，避免 tasks.cancel 觸發 dropUnstuck 在斷線時跑未受保護的 end()
		agent.reflexes.stopAll();
		agent.tasks.cancel(reason);
		if (taskContainer && mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
			mc.player.closeContainer();
		}
	}
}
