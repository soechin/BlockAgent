package blockagent.game.tasks;

import blockagent.core.ItemMatcher;
import blockagent.core.WorkstationPolicy;
import blockagent.game.Agent;
import blockagent.game.Creative;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * 附魔台任務(docs/SPEC.md S4.2f)：支援 {@code preview}(讀取 3 格附魔選項與最高等級)與 {@code apply}(執行附魔)。
 * 繼承 {@link ContainerTask} 共用開啟、同步與反射恢復流程。
 */
public final class EnchantTask extends ContainerTask {
	public enum Mode {PREVIEW, APPLY}

	private enum SubPhase {PUT, WAIT_OFFERS, WAIT_ENCHANTED}

	private final Mode mode;
	private final BlockPos pos;
	private final Item item;
	private final ItemMatcher matcher;
	private final int option;
	private final Predicate<ItemStack> pullOffhandKeep;

	private SubPhase subPhase = SubPhase.PUT;
	private int subWait;
	private ItemStack beforeItem = ItemStack.EMPTY;
	private int beforeLevel;
	private int beforeLapis;
	private String resultNote;
	private ErrorCode pendingFailCode;
	private String pendingFailReason;
	private String pendingFailHint;

	public EnchantTask(Agent agent, Mode mode, BlockPos pos, Item item, ItemMatcher matcher, int option,
					   Predicate<ItemStack> pullOffhandKeep) {
		super(agent, "enchant");
		this.mode = mode;
		this.pos = pos.immutable();
		this.item = item;
		this.matcher = matcher == null ? ItemMatcher.ANY : matcher;
		this.option = option;
		this.pullOffhandKeep = pullOffhandKeep;
	}

	@Override
	public String describe() {
		return mode == Mode.PREVIEW ? "預覽附魔" : "附魔裝備";
	}

	@Override
	public String progress() {
		Kv kv = Kv.of().put("action", mode == Mode.PREVIEW ? "preview" : "apply").put("pos", Fmt.pos(pos));
		if (mode == Mode.APPLY) kv.put("option", option);
		return kv.toString();
	}

	@Override
	protected void start(Minecraft mc) {
		if (pullOffhandKeep != null && !mc.player.getOffhandItem().isEmpty()) {
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			if (!Inv.pullOffhandToMain(mc, pullOffhandKeep)) {
				fail(ErrorCode.MISSING_MATERIALS, "a needed item is in the offhand and the main inventory is full",
					"drop or store one item, then enchant again");
				return;
			}
		}
		beginContainer(mc, pos);
	}

	@Override
	protected boolean isExpectedMenu(AbstractContainerMenu menu) {
		return menu instanceof EnchantmentMenu;
	}

	@Override
	protected void openContainerBlock(Minecraft mc) {
		Inv.selectInHand(mc, ItemStack::isEmpty);
		super.openContainerBlock(mc);
	}

	@Override
	protected String openTimeoutReason() {
		return "enchanting table screen did not open at " + Fmt.pos(pos);
	}

	@Override
	protected void onMenuSynced(Minecraft mc) {
		subPhase = SubPhase.PUT;
		subWait = 0;
	}

	@Override
	protected void work(Minecraft mc) {
		EnchantmentMenu menu = (EnchantmentMenu) mc.player.containerMenu;
		switch (subPhase) {
			case PUT -> {
				int placed = MenuOps.placeInto(mc, menu, 0, s -> Inv.matches(s, item, matcher), Inv.comparator(matcher), 1);
				if (placed < 1 || menu.getSlot(0).getItem().isEmpty()) {
					closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
						"could not place " + Inv.id(item) + " into enchanting table", null);
					return;
				}
				if (mode == Mode.APPLY && !Creative.isCreative(mc)) {
					int haveLapis = menu.getGoldCount();
					if (haveLapis < option) {
						MenuOps.placeInto(mc, menu, 1, s -> s.is(Items.LAPIS_LAZULI), option - haveLapis);
					}
					if (menu.getGoldCount() < option) {
						closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
							"need " + option + " lapis_lazuli (have " + menu.getGoldCount() + ")", "get lapis_lazuli first");
						return;
					}
				}
				subPhase = SubPhase.WAIT_OFFERS;
				subWait = 0;
			}
			case WAIT_OFFERS -> {
				subWait++;
				boolean ready = WorkstationPolicy.enchantOffersReady(menu.costs, menu.enchantClue);
				if ((!ready || subWait < 2) && subWait < 25) return;
				String[] clues = decodeClues(mc, menu);
				WorkstationPolicy.EnchantPreview preview = WorkstationPolicy.buildEnchantPreview(menu.costs, clues, menu.levelClue);
				if (!preview.hasAny()) {
					closeWithFailure(mc, menu, ErrorCode.INVALID_ARGUMENT,
						"item cannot be enchanted (already enchanted or not enchantable)", "use an unenchanted enchantable item");
					return;
				}
				if (mode == Mode.PREVIEW) {
					resultNote = Kv.of()
						.put("item", Fmt.item(menu.getSlot(0).getItem()))
						.put("offers", preview.summary())
						.put("max_level", preview.maxLevel())
						.put("have_level", mc.player.experienceLevel)
						.toString();
					collectInputs(mc, menu);
					finishContainer();
					return;
				}
				int idx = option - 1;
				int needLv = menu.costs[idx];
				if (needLv <= 0) {
					closeWithFailure(mc, menu, ErrorCode.INVALID_ARGUMENT,
						"enchant option " + option + " is not available", "call enchant action=preview first");
					return;
				}
				if (!Creative.isCreative(mc) && mc.player.experienceLevel < needLv) {
					closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
						"need_level=" + needLv + " have_level=" + mc.player.experienceLevel + " | not enough experience levels",
						"gain more levels or pick a lower option");
					return;
				}
				beforeItem = menu.getSlot(0).getItem().copy();
				beforeLevel = mc.player.experienceLevel;
				beforeLapis = menu.getGoldCount();
				mc.gameMode.handleInventoryButtonClick(menu.containerId, idx);
				subPhase = SubPhase.WAIT_ENCHANTED;
				subWait = 0;
			}
			case WAIT_ENCHANTED -> {
				subWait++;
				ItemStack cur = menu.getSlot(0).getItem();
				boolean changed = (!cur.isEmpty() && !ItemStack.isSameItemSameComponents(beforeItem, cur))
					|| mc.player.experienceLevel < beforeLevel
					|| menu.getGoldCount() < beforeLapis;
				if (!changed && subWait < 25) return;
				ItemStack result = cur.copy();
				collectInputs(mc, menu);
				if (!changed) {
					pendingFailCode = ErrorCode.TIMEOUT;
					pendingFailReason = "enchantment did not apply";
					pendingFailHint = "check levels and lapis_lazuli, then retry";
					finishContainer();
					return;
				}
				resultNote = Kv.of()
					.put("result", Fmt.item(result))
					.put("level", mc.player.experienceLevel)
					.put("lapis_left", Inv.countWithOffhand(mc.player, Items.LAPIS_LAZULI))
					.toString();
				finishContainer();
			}
		}
	}

	private static String[] decodeClues(Minecraft mc, EnchantmentMenu menu) {
		String[] out = new String[3];
		var reg = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		for (int i = 0; i < 3; i++) {
			int id = menu.enchantClue[i];
			if (id < 0) continue;
			final int slotIdx = i;
			reg.get(id).ifPresent(h -> {
				String n = h.getRegisteredName();
				out[slotIdx] = n.startsWith("minecraft:") ? n.substring(10) : n;
			});
		}
		return out;
	}

	private static void collectInputs(Minecraft mc, EnchantmentMenu menu) {
		for (int s = 0; s <= 1; s++) {
			if (!menu.getSlot(s).getItem().isEmpty()) MenuOps.quickMove(mc, menu, s);
		}
	}

	private void closeWithFailure(Minecraft mc, EnchantmentMenu menu, ErrorCode code, String reason, String hint) {
		collectInputs(mc, menu);
		pendingFailCode = code;
		pendingFailReason = reason;
		pendingFailHint = hint;
		finishContainer();
	}

	@Override
	protected void onClosed(Minecraft mc) {
		if (pendingFailCode != null) {
			fail(pendingFailCode, pendingFailReason, pendingFailHint);
		} else {
			done(resultNote);
		}
	}
}
