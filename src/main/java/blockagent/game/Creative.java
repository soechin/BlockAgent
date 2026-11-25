package blockagent.game;

import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import blockagent.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * 創造模式(docs/SPEC.md S4.6c)：從創造物品欄拿東西，跟玩家打開物品欄拿一樣。
 * 這是 S4.6a「不用創造模式的物品封包」唯一的例外，而且只在創造模式有效。
 */
public final class Creative {
	private Creative() {
	}

	/** creative_take 放東西的那一刻才為 true；守門員只在這時放行 handleCreativeModeItemAdd。只在 client thread 讀寫。 */
	private static boolean taking;

	public static boolean takingNow() {
		return taking;
	}

	public static boolean isCreative(Minecraft mc) {
		return mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE;
	}

	/** 遊戲模式的英文 id(survival、creative、adventure、spectator)。 */
	public static String modeName(GameType t) {
		return t.getName();
	}

	/** 創造模式時附在挖掘結果後面的提醒(一段 KV，不是創造模式時是空字串)。 */
	public static String noDropsNote(Minecraft mc) {
		return isCreative(mc) ? "note=\"creative: broken blocks do not drop; use creative_take for materials\"" : "";
	}

	/** 物品有沒有出現在創造物品欄(不含管理員物品，例如指令方塊)。 */
	static boolean inCreativeTabs(Minecraft mc, Item item) {
		CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), false, mc.level.registryAccess());
		for (ItemStack s : CreativeModeTabs.searchTab().getDisplayItems()) {
			if (s.is(item)) return true;
		}
		return false;
	}

	public static ToolResult take(Minecraft mc, Item item, int count) {
		if (!isCreative(mc)) {
			// 不在世界裡時沒有 gameMode(M13)
			return ToolResult.error(ErrorCode.WRONG_GAME_MODE, Kv.of().put("mode", mc.gameMode == null ? "none" : modeName(mc.gameMode.getPlayerMode())),
				"creative_take works only in creative mode", "");
		}
		if (!inCreativeTabs(mc, item)) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(item)),
				"not in the creative inventory (operator items such as command blocks are excluded)");
		}
		Inventory inv = mc.player.getInventory();
		ItemStack proto = new ItemStack(item);
		int max = proto.getMaxStackSize();
		int left = count;
		taking = true;
		try {
			// 先補滿背包裡同樣的物品，再用空格
			for (int pass = 0; pass < 2 && left > 0; pass++) {
				for (int i = 0; i < Inv.MAIN_SIZE && left > 0; i++) {
					ItemStack cur = inv.getItem(i);
					ItemStack next;
					int put;
					if (pass == 0) {
						if (cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, proto)) continue;
						put = Math.min(left, max - cur.getCount());
						if (put <= 0) continue;
						next = cur.copyWithCount(cur.getCount() + put);
					} else {
						if (!cur.isEmpty()) continue;
						put = Math.min(left, max);
						next = proto.copyWithCount(put);
					}
					setSlot(mc, i, next);
					left -= put;
				}
			}
		} finally {
			taking = false;
		}
		int taken = count - left;
		if (taken == 0) return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("item", Inv.id(item)), "inventory is full",
			"drop or store items first");
		return ToolResult.ok(Kv.of("OK took").put("item", Inv.id(item)).put("count", taken + "/" + count)
			.putIf(left > 0, "note", "inventory full"));
	}

	/** 跟原版創造物品欄一樣：先改客戶端的格子，再告訴伺服器(伺服器不會再同步回來)。只在 taking 為 true 時呼叫。 */
	private static void setSlot(Minecraft mc, int invIndex, ItemStack next) {
		// InventoryMenu 的格子編號：快捷列 0–8 是 36–44，背包 9–35 不變
		int menuSlot = invIndex < 9 ? 36 + invIndex : invIndex;
		mc.player.inventoryMenu.getSlot(menuSlot).set(next);
		mc.gameMode.handleCreativeModeItemAdd(next, menuSlot);
	}

	/**
	 * 蓋建用的鷹架方塊(S4.5a)：放進快捷列的空格(Baritone 只從快捷列拿墊腳的方塊)。
	 * 回傳 false 表示不是創造模式、物品不在創造物品欄，或快捷列沒有空格。
	 */
	public static boolean takeIntoHotbar(Minecraft mc, Item item, int count) {
		if (!isCreative(mc) || !inCreativeTabs(mc, item)) return false;
		Inventory inv = mc.player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (!inv.getItem(i).isEmpty()) continue;
			taking = true;
			try {
				setSlot(mc, i, new ItemStack(item, Math.min(count, new ItemStack(item).getMaxStackSize())));
			} finally {
				taking = false;
			}
			return true;
		}
		return false;
	}
}
