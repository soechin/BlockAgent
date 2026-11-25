package blockagent.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** 背包操作：全部透過正常的容器點擊(docs/SPEC.md S4.6a)。 */
public final class Inv {
	/** 主背包加快捷列：Inventory 的 0–35。 */
	public static final int MAIN_SIZE = 36;

	private Inv() {
	}

	/** Inventory 的索引 → InventoryMenu 的格子編號。 */
	public static int menuSlot(int invIndex) {
		return invIndex < 9 ? 36 + invIndex : invIndex;
	}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	public static String id(ItemStack s) {
		return id(s.getItem());
	}

	public static int count(Player p, Predicate<ItemStack> pred) {
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && pred.test(s)) n += s.getCount();
		}
		return n;
	}

	public static int count(Player p, Item item) {
		return count(p, s -> s.is(item));
	}

	/** 回傳 Inventory 索引，找不到回傳 -1。優先找快捷列。 */
	public static int find(Player p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && pred.test(s)) return i;
		}
		return -1;
	}

	public static int freeSlots(Player p) {
		int n = 0;
		for (int i = 0; i < MAIN_SIZE; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	public static Map<String, Integer> summary(Player p) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty()) m.merge(id(s), s.getCount(), Integer::sum);
		}
		return m;
	}

	/**
	 * 拿這個東西挖這格划不划算：會耗損耐久的工具，只有比徒手快、或掉落需要它時才值得用
	 * (2026-11-22 試玩：拿鐵劍、斧頭挖泥土)。BreakTask 和 Baritone 的選工具都照這條。
	 */
	public static boolean worthDigging(ItemStack s, net.minecraft.world.level.block.state.BlockState st) {
		if (!s.isDamageableItem()) return true;
		return s.getDestroySpeed(st) > 1.01f || (st.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(st));
	}

	/**
	 * 快捷列沒有可換的格子時，從背包把空格或不會耗損的東西換到 hotbarSlot(跟玩家按數字鍵換一樣)。
	 * 開著其他介面時不換。回傳是否換成功。
	 */
	public static boolean pullSpareInto(Minecraft mc, int hotbarSlot) {
		Player p = mc.player;
		if (p.containerMenu != p.inventoryMenu) return false;
		int idx = -1;
		for (int i = 9; i < MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) {
				idx = i;
				break;
			}
			if (idx < 0 && !s.isDamageableItem()) idx = i;
		}
		if (idx < 0) return false;
		click(mc, p.inventoryMenu, menuSlot(idx), hotbarSlot, ContainerInput.SWAP);
		return !p.getInventory().getItem(hotbarSlot).isDamageableItem();
	}

	/** 背包第 idx 格跟副手對調(跟玩家在背包按 F 一樣)。開著別的容器時不做(點背包畫面的格子會跟伺服器對不上，M13)。 */
	public static void swapToOffhand(Minecraft mc, int idx) {
		Player p = mc.player;
		if (p.containerMenu != p.inventoryMenu) return;
		click(mc, p.inventoryMenu, menuSlot(idx), 40, ContainerInput.SWAP);
	}

	/** 快捷列裡挖東西不會耗損的格子：優先空格，其次不會耗損的物品；都沒有回傳 -1。 */
	public static int spareHotbarSlot(Player p) {
		int fallback = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) return i;
			if (fallback < 0 && !s.isDamageableItem()) fallback = i;
		}
		return fallback;
	}

	/** 背包前後的差異，例如 {@code white_wool:+2,wheat:-1}(S3.0b)；沒有變化回傳空字串。 */
	public static String diff(Map<String, Integer> before, Map<String, Integer> after) {
		java.util.List<String> parts = new java.util.ArrayList<>();
		java.util.Set<String> keys = new java.util.LinkedHashSet<>(before.keySet());
		keys.addAll(after.keySet());
		for (String k : keys) {
			int d = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
			if (d != 0) parts.add(k + ":" + (d > 0 ? "+" : "-") + Math.abs(d));
		}
		return String.join(",", parts);
	}

	/** 背包裡所有物品的總數。 */
	public static int total(Player p) {
		int n = 0;
		for (int i = 0; i < MAIN_SIZE; i++) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	public static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ContainerInput type) {
		mc.gameMode.handleContainerInput(menu.containerId, slot, button, type, mc.player);
	}

	/** 把符合條件的物品拿到手上(必要時從主背包換到目前選的快捷列格)。 */
	public static boolean selectInHand(Minecraft mc, Predicate<ItemStack> pred) {
		Player p = mc.player;
		Inventory inv = p.getInventory();
		if (pred.test(inv.getSelectedItem())) return true;
		for (int i = 0; i < 9; i++) {
			if (pred.test(inv.getItem(i))) {
				inv.setSelectedSlot(i);
				return true;
			}
		}
		int idx = find(p, pred);
		// 開著別的容器時不能點背包畫面的格子(伺服器那邊開的是另一個畫面，M13)：只能用快捷列上的
		if (idx < 0 || p.containerMenu != p.inventoryMenu) return false;
		click(mc, p.inventoryMenu, menuSlot(idx), inv.getSelectedSlot(), ContainerInput.SWAP);
		return pred.test(inv.getSelectedItem());
	}

	/** 從背包丟出指定數量的物品，回傳實際丟出的數量。只在沒有開容器時呼叫。 */
	public static int dropItem(Minecraft mc, Item item, int amount) {
		Player p = mc.player;
		AbstractContainerMenu menu = p.inventoryMenu;
		int left = amount;
		for (int guard = 0; guard < MAIN_SIZE && left > 0; guard++) {
			int idx = find(p, s -> s.is(item));
			if (idx < 0) break;
			int slot = menuSlot(idx);
			int c = p.getInventory().getItem(idx).getCount();
			if (c <= left) {
				click(mc, menu, slot, 1, ContainerInput.THROW); // 丟整疊
				left -= c;
			} else if (left <= c - left) {
				// 丟的比留的少：一次丟一個(M13：原本從 64 疊丟 1 個要點 65 下)
				for (int k = 0; k < left; k++) click(mc, menu, slot, 0, ContainerInput.THROW);
				left = 0;
			} else {
				click(mc, menu, slot, 0, ContainerInput.PICKUP); // 整疊拿到游標
				for (int k = 0; k < c - left; k++) click(mc, menu, slot, 1, ContainerInput.PICKUP); // 一個一個放回
				click(mc, menu, -999, 0, ContainerInput.PICKUP); // 丟出游標上剩下的
				left = 0;
			}
		}
		return amount - left;
	}
}
