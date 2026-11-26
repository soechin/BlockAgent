package blockagent.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;

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
	 * 拿這個東西挖這格划不划算：會耗損耐久的工具，對方塊需要正確工具才能掉落時必須達標(避免拿木鎬挖爆鐵礦)；
	 * 否則必須比徒手快(避免拿鐵劍、斧頭挖泥土或樹葉)。BreakTask 和 Baritone 的選工具都照這條。
	 */
	public static boolean worthDigging(ItemStack s, BlockState st) {
		return worthDigging(s.isDamageableItem(), st.requiresCorrectToolForDrops(),
			st.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(st), s.getDestroySpeed(st));
	}

	static boolean worthDigging(boolean damageable, boolean requiresTool, boolean correctTool, float destroySpeed) {
		if (!damageable) return true;
		if (requiresTool) return correctTool;
		return destroySpeed > 1.01f;
	}

	/**
	 * 挑一格快捷列(0–8)來跟主背包物品交換：優先空格，其次不會耗損耐久且不受保護的物品，最後才動到其他不受保護的工具。
	 * 找不到任何可換格時回傳 {@code fallback}。
	 */
	public static int chooseHotbarSwapSlot(boolean[] occupied, boolean[] damageable, boolean[] protectedSlot, int fallback) {
		int nonDamageable = -1;
		int anyAllowed = -1;
		for (int i = 0; i < 9; i++) {
			if (!occupied[i]) return i;
			if (protectedSlot != null && protectedSlot[i]) continue;
			if (!damageable[i] && nonDamageable < 0) nonDamageable = i;
			if (anyAllowed < 0) anyAllowed = i;
		}
		if (nonDamageable >= 0) return nonDamageable;
		if (anyAllowed >= 0) return anyAllowed;
		return fallback;
	}

	/**
	 * 確保挖 {@code state} 用的最佳工具在快捷列(0–8)上，讓只看快捷列的 Baritone 用得到。
	 * 已在快捷列回傳該格索引；在主背包時透過正常背包交換換上快捷列並回傳目標格；沒有合適工具或開著容器無法換時回傳 -1。
	 */
	public static int ensureHotbarTool(Minecraft mc, BlockState state) {
		Player p = mc.player;
		Inventory inv = p.getInventory();
		boolean needsTool = state.requiresCorrectToolForDrops();
		int bestIdx = -1;
		float bestSpeed = needsTool ? 0f : 1.01f;
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (needsTool && !s.isCorrectToolForDrops(state)) continue;
			float sp = s.getDestroySpeed(state);
			if (!needsTool && (sp <= 1.01f || (s.is(ItemTags.SWORDS) && sp < 10f))) continue;
			if (bestIdx < 0 || sp > bestSpeed + 0.01f) {
				bestIdx = i;
				bestSpeed = sp;
			}
		}
		if (bestIdx < 0) return -1;
		if (bestIdx < 9) return bestIdx;
		if (p.containerMenu != p.inventoryMenu) return -1;
		boolean[] occupied = new boolean[9];
		boolean[] damageable = new boolean[9];
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			occupied[i] = !s.isEmpty();
			damageable[i] = occupied[i] && s.isDamageableItem();
		}
		int targetSlot = chooseHotbarSwapSlot(occupied, damageable, null, inv.getSelectedSlot());
		click(mc, p.inventoryMenu, menuSlot(bestIdx), targetSlot, ContainerInput.SWAP);
		return targetSlot;
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

	/** 暫時換走副手盾牌時，挑背包裡可以對調的格子：優先空格，其次非目前主手、非盾牌的格子；沒有回傳 -1(R11)。 */
	static int chooseOffhandShieldSwapSlot(boolean[] occupied, boolean[] blocksAttacks, int selectedSlot) {
		for (int i = 0; i < occupied.length; i++) {
			if (i != selectedSlot && !occupied[i]) return i;
		}
		for (int i = 0; i < occupied.length; i++) {
			if (i != selectedSlot && !blocksAttacks[i]) return i;
		}
		return -1;
	}

	public static int findOffhandShieldSwapSlot(Player p) {
		Inventory inv = p.getInventory();
		boolean[] occupied = new boolean[MAIN_SIZE];
		boolean[] blocks = new boolean[MAIN_SIZE];
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			occupied[i] = !s.isEmpty();
			blocks[i] = occupied[i] && s.has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS);
		}
		return chooseOffhandShieldSwapSlot(occupied, blocks, inv.getSelectedSlot());
	}

	/** 主背包加副手的物品數量(R12：Inv.count 只掃 0–35 格，看不到副手)。 */
	public static int countWithOffhand(Player p, Item item) {
		ItemStack off = p.getOffhandItem();
		return count(p, item) + (off.is(item) ? off.getCount() : 0);
	}

	/** 背包摘要加上副手的物品(R12)。 */
	public static Map<String, Integer> summaryWithOffhand(Player p) {
		Map<String, Integer> m = summary(p);
		ItemStack off = p.getOffhandItem();
		if (!off.isEmpty()) m.merge(id(off), off.getCount(), Integer::sum);
		return m;
	}

	/**
	 * 找一個主背包格子用來把副手換回主背包(R12，純查詢不修改狀態)：
	 * 優先空格；沒有空格時挑一格 {@code keepInMain} 不要的東西(先避開目前主手格)。找不到回傳 -1。
	 */
	public static int findOffhandPullSlot(Player p, Predicate<ItemStack> keepInMain) {
		if (p.getOffhandItem().isEmpty()) return -1;
		Inventory inv = p.getInventory();
		for (int i = 0; i < MAIN_SIZE; i++) {
			if (inv.getItem(i).isEmpty()) return i;
		}
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < MAIN_SIZE; i++) {
				if (pass == 0 && i == inv.getSelectedSlot()) continue;
				if (!keepInMain.test(inv.getItem(i))) return i;
			}
		}
		return -1;
	}

	/**
	 * 把副手的東西換回主背包(R12)：3×3 工作台畫面沒有副手格，合成材料在副手就點不到。
	 * 優先換到空格；沒有空格時換掉一格 {@code keepInMain} 不要的東西。回傳是否換成功。
	 */
	public static boolean pullOffhandToMain(Minecraft mc, Predicate<ItemStack> keepInMain) {
		Player p = mc.player;
		if (p.containerMenu != p.inventoryMenu || p.getOffhandItem().isEmpty()) return false;
		int slot = findOffhandPullSlot(p, keepInMain);
		if (slot < 0) return false;
		swapToOffhand(mc, slot);
		return true;
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
	// ---- 戰鬥裝備(docs/REVIEW.md R7、SPEC S9.1b) ----

	/** 近戰武器：背包索引(空手是 -1)、每下傷害、攻擊間隔(秒)。 */
	public record Weapon(int index, double damage, double interval) {
		public double dps() {
			return damage / interval;
		}
	}

	/** 空手：每拳 1 點，0.25 秒一拳。 */
	public static final Weapon FIST = new Weapon(-1, 1, 0.25);

	/** 主手拿這個東西時的傷害和攻擊間隔(玩家基礎攻擊力 1、攻擊速度 4，加上物品的屬性修飾)。 */
	public static Weapon weaponStats(int index, ItemStack s) {
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double damage = mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
		double speed = mods.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
		return new Weapon(index, Math.max(1, damage), speed > 0.1 ? 1 / speed : 10);
	}

	/**
	 * 主背包裡每秒傷害最高的劍或斧頭(不只看欄位順序，R7)；一樣好時優先目前主手。沒有就是空手。
	 *
	 * @param allowed 額外條件，例如旁邊有受保護生物時只能用斧頭
	 */
	public static Weapon bestWeapon(Player p, Predicate<ItemStack> allowed) {
		Inventory inv = p.getInventory();
		Weapon best = FIST;
		int selected = inv.getSelectedSlot();
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < MAIN_SIZE; i++) {
				if ((pass == 0) != (i == selected)) continue;
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || !(s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)) || !allowed.test(s)) continue;
				Weapon w = weaponStats(i, s);
				if (w.dps() > best.dps() + 1e-6) best = w;
			}
		}
		return best;
	}

	/** 把最好的劍或斧頭拿到主手；沒有可用的武器回傳 false。 */
	public static boolean selectBestWeapon(Minecraft mc, Predicate<ItemStack> allowed) {
		Weapon w = bestWeapon(mc.player, allowed);
		if (w.index() < 0) return false;
		ItemStack target = mc.player.getInventory().getItem(w.index());
		return selectInHand(mc, s -> s == target);
	}

	/** 這個東西穿在哪一格護甲欄、能擋多少；不是護甲回傳 null。 */
	private static EquipmentSlot armorSlot(ItemStack s) {
		var eq = s.get(DataComponents.EQUIPPABLE);
		if (eq == null || eq.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return null;
		return armorPoints(s, eq.slot()) > 0 ? eq.slot() : null;
	}

	private static double armorPoints(ItemStack s, EquipmentSlot slot) {
		return s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).compute(Attributes.ARMOR, 0, slot);
	}

	/**
	 * 身上 4 件護甲的護甲值總和，直接從裝備算。{@code getArmorValue()} 要等伺服器同步屬性(單人也要幾個 tick)，
	 * 剛自動穿上時還是 0，勝算會誤判成打不贏(2026-11-26 遊戲測試：全副武裝對一群卻馬上逃)。
	 */
	public static int wornArmor(Player p) {
		double sum = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack s = p.getItemBySlot(slot);
			if (!s.isEmpty()) sum += armorPoints(s, slot);
		}
		return Math.max(p.getArmorValue(), (int) Math.round(sum));
	}

	/** 把背包第 idx 格的護甲穿上(跟玩家在背包按 Shift 點一下一樣)。開著別的容器時不做。 */
	public static void wear(Minecraft mc, int idx) {
		Player p = mc.player;
		if (p.containerMenu != p.inventoryMenu) return;
		click(mc, p.inventoryMenu, menuSlot(idx), 0, ContainerInput.QUICK_MOVE);
	}

	/**
	 * 怪物來了自動備戰(S9.1b)：空著的護甲欄穿上背包裡最好的護甲；副手不是盾牌也不是不死圖騰時把盾牌換上去
	 * (副手原本的東西換回那一格)。開著別的容器時不做。回傳做了什麼，例如 {@code iron_chestplate,shield}；沒做事回傳空字串。
	 */
	public static String prepareForCombat(Minecraft mc) {
		Player p = mc.player;
		// 開著別的容器、或游標上拿著東西(合成到一半)時不動背包
		if (p.containerMenu != p.inventoryMenu || !p.containerMenu.getCarried().isEmpty()) return "";
		java.util.List<String> done = new java.util.ArrayList<>();
		Inventory inv = p.getInventory();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (!p.getItemBySlot(slot).isEmpty()) continue;
			int best = -1;
			double bestPts = 0;
			for (int i = 0; i < MAIN_SIZE; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || armorSlot(s) != slot) continue;
				double pts = armorPoints(s, slot);
				if (pts > bestPts) {
					best = i;
					bestPts = pts;
				}
			}
			if (best < 0) continue;
			String name = id(inv.getItem(best));
			wear(mc, best);
			if (!p.getItemBySlot(slot).isEmpty()) done.add(name);
		}
		ItemStack off = p.getOffhandItem();
		if (!off.has(DataComponents.BLOCKS_ATTACKS) && !off.is(Items.TOTEM_OF_UNDYING)) {
			int idx = find(p, s -> s.has(DataComponents.BLOCKS_ATTACKS));
			if (idx >= 0) {
				swapToOffhand(mc, idx);
				if (p.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS)) done.add(id(p.getOffhandItem()));
			}
		}
		return String.join(",", done);
	}

	/** 背包裡有沒有可以吃的東西(給備戰評估用)。 */
	public static boolean hasFood(Player p) {
		return find(p, s -> s.has(DataComponents.FOOD)) >= 0 || p.getOffhandItem().has(DataComponents.FOOD);
	}
}
