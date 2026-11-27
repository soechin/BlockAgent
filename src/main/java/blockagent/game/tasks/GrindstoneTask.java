package blockagent.game.tasks;

import blockagent.core.ItemMatcher;
import blockagent.game.Agent;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.function.Predicate;

/**
 * 砂輪任務(docs/SPEC.md S4.2h)：拆除非詛咒附魔換取經驗，或將兩件同種裝備合修並清附魔。
 * 關閉介面後原地短暫停留讓掉出的經驗球吸收再回報結果。
 */
public final class GrindstoneTask extends ContainerTask {
	private enum SubPhase {PUT, WAIT_RESULT, WAIT_TAKE}

	private final BlockPos pos;
	private final Item item;
	private final ItemMatcher matcher;
	private final Item secondItem;
	private final ItemMatcher secondMatcher;
	private final Predicate<ItemStack> pullOffhandKeep;

	private SubPhase subPhase = SubPhase.PUT;
	private int subWait;
	private ItemStack expectedResult = ItemStack.EMPTY;
	private int beforeXp;
	private int absorbTicks;
	private ErrorCode pendingFailCode;
	private String pendingFailReason;
	private String pendingFailHint;

	public GrindstoneTask(Agent agent, BlockPos pos, Item item, ItemMatcher matcher,
						  Item secondItem, ItemMatcher secondMatcher, Predicate<ItemStack> pullOffhandKeep) {
		super(agent, "grindstone");
		this.pos = pos.immutable();
		this.item = item;
		this.matcher = matcher == null ? ItemMatcher.ANY : matcher;
		this.secondItem = secondItem;
		this.secondMatcher = secondMatcher == null ? ItemMatcher.ANY : secondMatcher;
		this.pullOffhandKeep = pullOffhandKeep;
	}

	@Override
	public String describe() {
		return secondItem == null ? "砂輪拆附魔" : "砂輪合修";
	}

	@Override
	public String progress() {
		return Kv.of().put("pos", Fmt.pos(pos)).put("item", Inv.id(item)).toString();
	}

	@Override
	protected void start(Minecraft mc) {
		if (pullOffhandKeep != null && !mc.player.getOffhandItem().isEmpty()) {
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			if (!Inv.pullOffhandToMain(mc, pullOffhandKeep)) {
				fail(ErrorCode.MISSING_MATERIALS, "a needed item is in the offhand and the main inventory is full",
					"drop or store one item, then retry grindstone");
				return;
			}
		}
		beginContainer(mc, pos);
	}

	@Override
	protected boolean isExpectedMenu(AbstractContainerMenu menu) {
		return menu instanceof GrindstoneMenu;
	}

	@Override
	protected void openContainerBlock(Minecraft mc) {
		Inv.selectInHand(mc, ItemStack::isEmpty);
		super.openContainerBlock(mc);
	}

	@Override
	protected String openTimeoutReason() {
		return "grindstone screen did not open at " + Fmt.pos(pos);
	}

	@Override
	protected void onMenuSynced(Minecraft mc) {
		subPhase = SubPhase.PUT;
		subWait = 0;
	}

	@Override
	protected void work(Minecraft mc) {
		GrindstoneMenu menu = (GrindstoneMenu) mc.player.containerMenu;
		switch (subPhase) {
			case PUT -> {
				int placed1 = MenuOps.placeInto(mc, menu, GrindstoneMenu.INPUT_SLOT,
					s -> Inv.matches(s, item, matcher), Inv.comparator(matcher), 1);
				if (placed1 < 1 || menu.getSlot(GrindstoneMenu.INPUT_SLOT).getItem().isEmpty()) {
					closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
						"could not place " + Inv.id(item) + " into grindstone", null);
					return;
				}
				if (secondItem != null) {
					int placed2 = MenuOps.placeInto(mc, menu, GrindstoneMenu.ADDITIONAL_SLOT,
						s -> Inv.matches(s, secondItem, secondMatcher), Inv.comparator(secondMatcher), 1);
					if (placed2 < 1 || menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).getItem().isEmpty()) {
						closeWithFailure(mc, menu, ErrorCode.MISSING_MATERIALS,
							"could not place " + Inv.id(secondItem) + " into grindstone second slot", null);
						return;
					}
				}
				subPhase = SubPhase.WAIT_RESULT;
				subWait = 0;
			}
			case WAIT_RESULT -> {
				subWait++;
				ItemStack res = menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem();
				if ((res.isEmpty() || subWait < 2) && subWait < 20) return;
				if (res.isEmpty()) {
					closeWithFailure(mc, menu, ErrorCode.INVALID_ARGUMENT,
						"grindstone produced no result (no removable enchantments or items cannot be combined)",
						"provide an enchanted item or two identical damageable items");
					return;
				}
				expectedResult = res.copy();
				beforeXp = mc.player.totalExperience;
				MenuOps.quickMove(mc, menu, GrindstoneMenu.RESULT_SLOT);
				subPhase = SubPhase.WAIT_TAKE;
				subWait = 0;
			}
			case WAIT_TAKE -> {
				subWait++;
				boolean taken = menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem().isEmpty();
				if (!taken && subWait < 12) return;
				collectInputs(mc, menu);
				if (!taken) {
					pendingFailCode = ErrorCode.MISSING_MATERIALS;
					pendingFailReason = "could not take grindstone result (inventory may be full)";
					pendingFailHint = "free an inventory slot and retry";
					finishContainer();
					return;
				}
				absorbTicks = 0;
				closeAndCustom(mc);
			}
		}
	}

	@Override
	protected void tickCustom(Minecraft mc) {
		absorbTicks++;
		boolean orbsNear = !mc.level.getEntitiesOfClass(ExperienceOrb.class,
			new AABB(mc.player.blockPosition()).inflate(4.0), ExperienceOrb::isAlive).isEmpty();
		if (absorbTicks < 3 || (orbsNear && absorbTicks < 15)) return;
		int gained = Math.max(0, mc.player.totalExperience - beforeXp);
		done(Kv.of()
			.put("result", Fmt.item(expectedResult))
			.put("level", mc.player.experienceLevel)
			.put("xp_gained", gained)
			.toString());
	}

	private static void collectInputs(Minecraft mc, GrindstoneMenu menu) {
		for (int s : new int[]{GrindstoneMenu.INPUT_SLOT, GrindstoneMenu.ADDITIONAL_SLOT}) {
			if (!menu.getSlot(s).getItem().isEmpty()) MenuOps.quickMove(mc, menu, s);
		}
	}

	private void closeWithFailure(Minecraft mc, GrindstoneMenu menu, ErrorCode code, String reason, String hint) {
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
		}
	}
}
