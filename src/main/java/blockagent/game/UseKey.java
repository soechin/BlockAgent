package blockagent.game;

import blockagent.mixin.MinecraftAccessor;
import blockagent.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;

/**
 * 按住使用鍵吃東西(EatTask 和進食反射共用)。
 * <p>
 * 原版 handleKeybinds 在「使用鍵按著、rightClickDelay == 0、沒在使用物品」時會呼叫 startUseItem()。
 * 吃完的那一 tick 正好符合這三個條件，於是又按了一次右鍵：準星對著熔爐、箱子、工作台時會把它打開，
 * 對著門會開關門。之後的 craft 以為自己在背包的 2×2 合成格，其實在熔爐畫面裡點格子，結果誤報缺料。
 * 所以按住使用鍵的期間，每 tick 都把右鍵冷卻撐在 0 以上；吃東西本身不受影響(正在使用時不看這個冷卻)。
 */
public final class UseKey {
	/** 原版每次右鍵後的冷卻。 */
	private static final int HOLD_DELAY = 4;

	private UseKey() {
	}

	/** 開始吃：按住使用鍵並對手上的物品按一次使用。 */
	public static void startEating(Minecraft mc) {
		startUsing(mc, InteractionHand.MAIN_HAND);
	}

	/** 按住使用鍵並對這隻手的物品按一次使用(吃東西、拉弓、舉盾)。 */
	public static void startUsing(Minecraft mc, InteractionHand hand) {
		mc.options.keyUse.setDown(true);
		holdOff(mc);
		mc.gameMode.useItem(mc.player, hand);
	}

	/** 按著使用鍵的每一 tick 都要呼叫，避免吃完時原版自己再按一次右鍵。 */
	public static void holdOff(Minecraft mc) {
		MinecraftAccessor acc = (MinecraftAccessor) mc;
		if (acc.blockagent$getRightClickDelay() < HOLD_DELAY) acc.blockagent$setRightClickDelay(HOLD_DELAY);
	}

	/** 放開使用鍵；還在吃就停下。 */
	public static void release(Minecraft mc) {
		mc.options.keyUse.setDown(false);
		if (mc.player != null && mc.gameMode != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
	}

	/**
	 * 取消正在使用的物品，不放出去(拉到一半的弓、三叉戟)。原版沒有「取消使用」的動作，所以：
	 * <ul>
	 *   <li>主手：換一格快捷列並立刻刷出換格封包({@code ensureHasSentCarriedItem})，避免同 tick 又選回原格時伺服器完全沒收到取消(P3-H8)。</li>
	 *   <li>副手：送一對 {@code SWAP_ITEM_WITH_OFFHAND}(原版伺服器端收到會呼叫 {@code stopUsingItem()} 且不會放箭/丟三叉戟，兩次交換後雙手物品不變)。</li>
	 * </ul>
	 * 最後停下客戶端這邊，免得原版每 tick 又試著放開(H8)。
	 */
	public static void cancelUse(Minecraft mc) {
		mc.options.keyUse.setDown(false);
		var p = mc.player;
		if (p == null || !p.isUsingItem()) return;
		if (p.getUsedItemHand() == InteractionHand.MAIN_HAND) {
			cancelMainHandUse(mc, p.getInventory().getSelectedSlot());
			return;
		} else if (mc.getConnection() != null && !p.isSpectator()) {
			var swap = new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN);
			mc.getConnection().send(swap);
			mc.getConnection().send(swap);
		}
		p.stopUsingItem();
	}

	/** 取消已送出的主手使用，即使舊同步封包暫時清掉了客戶端的使用狀態。 */
	public static void cancelMainHandUse(Minecraft mc, int usedSlot) {
		mc.options.keyUse.setDown(false);
		var player = mc.player;
		if (player == null) return;
		var inventory = player.getInventory();
		if (inventory.getSelectedSlot() == usedSlot) inventory.setSelectedSlot((usedSlot + 1) % 9);
		if (mc.gameMode != null) ((MultiPlayerGameModeInvoker) mc.gameMode).blockagent$ensureHasSentCarriedItem();
		player.stopUsingItem();
	}
}
