package blockagent.game.tasks;

import blockagent.game.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** 在打開的容器畫面裡搬東西(全部是正常的點擊)。 */
public final class MenuOps {
	private MenuOps() {
	}

	/** 這個畫面裡屬於自己背包(主背包+快捷列)的格子。 */
	public static List<Integer> playerSlots(AbstractContainerMenu m) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < m.slots.size(); i++) {
			Slot s = m.getSlot(i);
			if (s.container instanceof Inventory && s.getContainerSlot() < Inv.MAIN_SIZE) out.add(i);
		}
		return out;
	}

	/** 不屬於自己背包的格子(箱子、熔爐、合成格)。 */
	public static List<Integer> containerSlots(AbstractContainerMenu m) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < m.slots.size(); i++) if (!(m.getSlot(i).container instanceof Inventory)) out.add(i);
		return out;
	}

	public static int findPlayerSlot(AbstractContainerMenu m, Predicate<ItemStack> pred) {
		return findPlayerSlot(m, pred, null);
	}

	/** 找符合條件且依 {@code order}(可為 null)最優先的玩家背包格。 */
	public static int findPlayerSlot(AbstractContainerMenu m, Predicate<ItemStack> pred, Comparator<ItemStack> order) {
		int best = -1;
		ItemStack bestStack = ItemStack.EMPTY;
		for (int i : playerSlots(m)) {
			ItemStack s = m.getSlot(i).getItem();
			if (s.isEmpty() || !pred.test(s)) continue;
			if (order == null) return i;
			if (best < 0 || order.compare(s, bestStack) < 0) {
				best = i;
				bestStack = s;
			}
		}
		return best;
	}

	public static int countInPlayer(AbstractContainerMenu m, Predicate<ItemStack> pred) {
		int n = 0;
		for (int i : playerSlots(m)) {
			ItemStack s = m.getSlot(i).getItem();
			if (!s.isEmpty() && pred.test(s)) n += s.getCount();
		}
		return n;
	}

	/**
	 * 從自己背包拿 n 個符合條件的物品放進 target 格(target 必須是空的或同一種物品)。回傳 target 格實際增加了幾個：
	 * 格子放不下(堆疊上限、燃料格只收燃料)時點擊會彈回來，不能當成放進去了(docs/REVIEW.md C7)。
	 */
	public static int placeInto(Minecraft mc, AbstractContainerMenu m, int target, Predicate<ItemStack> pred, int n) {
		return placeInto(mc, m, target, pred, null, n);
	}

	public static int placeInto(Minecraft mc, AbstractContainerMenu m, int target, Predicate<ItemStack> pred,
								Comparator<ItemStack> order, int n) {
		int placed = 0;
		Slot slot = m.getSlot(target);
		for (int guard = 0; guard < 64 && placed < n; guard++) {
			ItemStack t = slot.getItem();
			int src = findPlayerSlot(m, s -> pred.test(s) && (t.isEmpty() || ItemStack.isSameItemSameComponents(s, t)), order);
			if (src < 0) break;
			ItemStack srcStack = m.getSlot(src).getItem();
			int before = t.isEmpty() ? 0 : t.getCount();
			int room = slot.getMaxStackSize(srcStack) - before;
			if (room <= 0 || !slot.mayPlace(srcStack)) break;
			int srcCount = srcStack.getCount();
			int k = Math.min(Math.min(n - placed, srcCount), room);
			Inv.click(mc, m, src, 0, ContainerInput.PICKUP);
			if (k == srcCount && slot.getItem().isEmpty()) {
				Inv.click(mc, m, target, 0, ContainerInput.PICKUP);
			} else {
				for (int j = 0; j < k; j++) Inv.click(mc, m, target, 1, ContainerInput.PICKUP);
			}
			ItemStack after = slot.getItem();
			int added = (after.isEmpty() ? 0 : after.getCount()) - before;
			returnCarried(mc, m, src);
			if (added <= 0) break;
			placed += added;
		}
		return placed;
	}

	/** 游標上還拿著東西：放回原本的格子，那格被占走了就放到任何一個空格。 */
	private static void returnCarried(Minecraft mc, AbstractContainerMenu m, int src) {
		if (!m.getCarried().isEmpty()) Inv.click(mc, m, src, 0, ContainerInput.PICKUP);
		if (!m.getCarried().isEmpty()) {
			for (int i : playerSlots(m)) {
				if (m.getSlot(i).getItem().isEmpty()) {
					Inv.click(mc, m, i, 0, ContainerInput.PICKUP);
					break;
				}
			}
		}
	}

	public static void quickMove(Minecraft mc, AbstractContainerMenu m, int slot) {
		Inv.click(mc, m, slot, 0, ContainerInput.QUICK_MOVE);
	}

	public static void close(Minecraft mc) {
		if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
	}
}
