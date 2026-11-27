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
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * 鐵砧任務(docs/SPEC.md S4.2g)：支援 {@code preview} 與 {@code apply}(合併裝備、附魔書、修補材料與改名/命名牌)。
 * 處理完成時鐵砧 12% 機率降級或碎裂關閉介面的原版行為。
 */
public final class AnvilTask extends ContainerTask {
	public enum Mode {PREVIEW, APPLY}

	private enum SubPhase {PUT, WAIT_RESULT, WAIT_TAKE}

	private final Mode mode;
	private final BlockPos pos;
	private final Item item;
	private final ItemMatcher matcher;
	private final Item secondItem;
	private final ItemMatcher secondMatcher;
	private final String rename;
	private final Predicate<ItemStack> pullOffhandKeep;

	private SubPhase subPhase = SubPhase.PUT;
	private int subWait;
	private ItemStack expectedResult = ItemStack.EMPTY;
	private int appliedCost;
	private String resultNote;
	private ErrorCode pendingFailCode;
	private String pendingFailReason;
	private String pendingFailHint;

	public AnvilTask(Agent agent, Mode mode, BlockPos pos, Item item, ItemMatcher matcher,
					 Item secondItem, ItemMatcher secondMatcher, String rename,
					 Predicate<ItemStack> pullOffhandKeep) {
		super(agent, "anvil");
		this.mode = mode;
		this.pos = pos.immutable();
		this.item = item;
		this.matcher = matcher == null ? ItemMatcher.ANY : matcher;
		this.secondItem = secondItem;
		this.secondMatcher = secondMatcher == null ? ItemMatcher.ANY : secondMatcher;
		this.rename = rename;
		this.pullOffhandKeep = pullOffhandKeep;
	}

	@Override
	public String describe() {
		return mode == Mode.PREVIEW ? "預覽鐵砧" : "使用鐵砧";
	}

	@Override
	public String progress() {
		return Kv.of().put("action", mode == Mode.PREVIEW ? "preview" : "apply").put("pos", Fmt.pos(pos)).toString();
	}

	@Override
	protected void start(Minecraft mc) {
		if (pullOffhandKeep != null && !mc.player.getOffhandItem().isEmpty()) {
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			if (!Inv.pullOffhandToMain(mc, pullOffhandKeep)) {
				fail(ErrorCode.MISSING_MATERIALS, "a needed item is in the offhand and the main inventory is full",
					"drop or store one item, then retry anvil");
				return;
			}
		}
		beginContainer(mc, pos);
	}

	@Override
	protected boolean isExpectedMenu(AbstractContainerMenu menu) {
		return menu instanceof AnvilMenu;
	}

	@Override
	protected void openContainerBlock(Minecraft mc) {
		Inv.selectInHand(mc, ItemStack::isEmpty);
		super.openContainerBlock(mc);
	}

	@Override
	protected String openTimeoutReason() {
		return "anvil screen did not open at " + Fmt.pos(pos);
	}

	@Override
	protected void onMenuSynced(Minecraft mc) {
		subPhase = SubPhase.PUT;
		subWait = 0;
	}

	@Override
	protected void onMenuClosedUnexpectedly(Minecraft mc) {
		// 鐵砧完成時有 12% 機率碎裂並由伺服器關閉介面：若已進入取產物階段且鐵砧已消失，視為完成並標註 anvil_broken=true
		if (subPhase == SubPhase.WAIT_TAKE && !expectedResult.isEmpty()) {
			markOpenedContainer(false);
			boolean broken = mc.level != null && !mc.level.getBlockState(pos).is(BlockTags.ANVIL);
			done(formatApplyResult(mc, broken));
			return;
		}
		super.onMenuClosedUnexpectedly(mc);
	}

	@Override
	protected void work(Minecraft mc) {
		AnvilMenu menu = (AnvilMenu) mc.player.containerMenu;
		switch (subPhase) {
			case PUT -> {
				int placed1 = MenuOps.placeInto(mc, menu, AnvilMenu.INPUT_SLOT,
					s -> Inv.matches(s, item, matcher), Inv.comparator(matcher), 1);
				if (placed1 < 1 || menu.getSlot(AnvilMenu.INPUT_SLOT).getItem().isEmpty()) {
					closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
						"could not place " + Inv.id(item) + " into anvil", null);
					return;
				}
				if (secondItem != null) {
					int want = secondItem.getDefaultMaxStackSize() > 1 ? 4 : 1;
					int placed2 = MenuOps.placeInto(mc, menu, AnvilMenu.ADDITIONAL_SLOT,
						s -> Inv.matches(s, secondItem, secondMatcher), Inv.comparator(secondMatcher), want);
					if (placed2 < 1 || menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().isEmpty()) {
						closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
							"could not place " + Inv.id(secondItem) + " into anvil second slot", null);
						return;
					}
				}
				if (rename != null) {
					menu.setItemName(rename);
					if (mc.player.connection != null) {
						mc.player.connection.send(new ServerboundRenameItemPacket(rename));
					}
				}
				subPhase = SubPhase.WAIT_RESULT;
				subWait = 0;
			}
			case WAIT_RESULT -> {
				subWait++;
				ItemStack res = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
				int cost = menu.getCost();
				boolean creative = Creative.isCreative(mc);
				boolean tooExpensive = WorkstationPolicy.anvilTooExpensive(cost, creative);
				if ((res.isEmpty() && !tooExpensive || cost <= 0 || subWait < 3) && subWait < 20) return;
				if (tooExpensive) {
					if (mode == Mode.PREVIEW) {
						resultNote = Kv.of()
							.put("result", res.isEmpty() ? "none" : Fmt.item(res))
							.put("cost", cost)
							.put("have_level", mc.player.experienceLevel)
							.put("too_expensive", true)
							.toString();
						collectInputs(mc, menu);
						finishContainer();
					} else {
						closeWithFailure(mc, menu, ErrorCode.INVALID_ARGUMENT,
							"cost=" + cost + " | too expensive (limit is " + WorkstationPolicy.ANVIL_MAX_COST + " levels)",
							"combine in a cheaper order or use a fresh item");
					}
					return;
				}
				if (res.isEmpty()) {
					closeWithFailure(mc, menu, ErrorCode.INVALID_ARGUMENT,
						"anvil produced no result (incompatible items or unchanged name)",
						"check item compatibility or provide a different rename");
					return;
				}
				if (mode == Mode.PREVIEW) {
					resultNote = Kv.of()
						.put("result", Fmt.item(res))
						.put("cost", cost)
						.put("have_level", mc.player.experienceLevel)
						.put("too_expensive", false)
						.toString();
					collectInputs(mc, menu);
					finishContainer();
					return;
				}
				if (!creative && mc.player.experienceLevel < cost) {
					closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
						"need_level=" + cost + " have_level=" + mc.player.experienceLevel + " | not enough experience levels",
						"gain more levels before using the anvil");
					return;
				}
				expectedResult = res.copy();
				appliedCost = cost;
				MenuOps.quickMove(mc, menu, AnvilMenu.RESULT_SLOT);
				subPhase = SubPhase.WAIT_TAKE;
				subWait = 0;
			}
			case WAIT_TAKE -> {
				subWait++;
				boolean taken = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty();
				if (!taken && subWait < 12) return;
				collectInputs(mc, menu);
				if (!taken) {
					pendingFailCode = ErrorCode.MISSING_MATERIALS;
					pendingFailReason = "could not take anvil result (inventory may be full)";
					pendingFailHint = "free an inventory slot and retry";
					finishContainer();
					return;
				}
				boolean broken = mc.level != null && !mc.level.getBlockState(pos).is(BlockTags.ANVIL);
				resultNote = formatApplyResult(mc, broken);
				finishContainer();
			}
		}
	}

	private String formatApplyResult(Minecraft mc, boolean anvilBroken) {
		return Kv.of()
			.put("result", Fmt.item(expectedResult))
			.put("cost", appliedCost)
			.put("level", mc.player.experienceLevel)
			.putIf(anvilBroken, "anvil_broken", true)
			.toString();
	}

	private static void collectInputs(Minecraft mc, AnvilMenu menu) {
		for (int s : new int[]{AnvilMenu.INPUT_SLOT, AnvilMenu.ADDITIONAL_SLOT}) {
			if (!menu.getSlot(s).getItem().isEmpty()) MenuOps.quickMove(mc, menu, s);
		}
	}

	private void closeWithFailure(Minecraft mc, AnvilMenu menu, ErrorCode code, String reason, String hint) {
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
