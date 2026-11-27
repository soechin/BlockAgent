package blockagent.game.tasks;

import blockagent.core.Recipes;
import blockagent.core.Recipes.FurnaceKind;
import blockagent.game.Agent;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 熔煉與爐子存取(docs/SPEC.md S4.2e)：支援普通熔爐、高爐、煙燻爐，以及 {@code list}/{@code take}。
 * 繼承 {@link ContainerTask} 共用開啟、同步與反射恢復流程。
 */
public final class SmeltTask extends ContainerTask {
	public enum Mode {SMELT, LIST, TAKE}

	private enum CustomPhase {FIND, PLACE}

	private enum WorkPhase {CHECK, PUT_INPUT, PUT_FUEL, WAIT}

	private final Mode mode;
	private final Item input;
	private final Item result;
	private final int count;
	private final Item fuel;
	private final int fuelCount;
	private final List<FurnaceKind> supported;
	private final Set<Item> pullOffhandKeep;
	private final Set<BlockPos> rejected = new HashSet<>();
	private final List<String> rejectedDetails = new ArrayList<>();
	private final long baseTimeoutTicks;
	private BlockPos furnace;
	private BlockPos preferredFurnace;
	private CustomPhase customPhase = CustomPhase.FIND;
	private WorkPhase workPhase = WorkPhase.CHECK;
	private int customWaitTicks;
	private int stallTicks;
	private int taken;
	/** 開始前產物格裡已經有的數量(也會一起拿出來，但不算進這次燒好的)。 */
	private int outBefore;
	private boolean placedOwn;
	/** 已經放進燃料格的燃料數；燃料比一格放得下的多時分批補(C7)。 */
	private int fuelPut;
	private String finishText;

	public SmeltTask(Agent agent, Item input, Item result, int count, Item fuel, int fuelCount, BlockPos preferredFurnace,
					 List<FurnaceKind> supported, Set<Item> pullOffhandKeep) {
		super(agent, "smelt");
		this.mode = Mode.SMELT;
		this.input = input;
		this.result = result;
		this.count = count;
		this.fuel = fuel;
		this.fuelCount = fuelCount;
		this.preferredFurnace = preferredFurnace == null ? null : preferredFurnace.immutable();
		this.supported = supported == null || supported.isEmpty() ? List.of(FurnaceKind.FURNACE) : List.copyOf(supported);
		this.pullOffhandKeep = pullOffhandKeep == null ? null : Set.copyOf(pullOffhandKeep);
		this.baseTimeoutTicks = this.timeoutTicks;
		this.timeoutTicks = smeltTimeoutTicks(baseTimeoutTicks, count, false);
	}

	public SmeltTask(Agent agent, Mode mode, BlockPos targetPos) {
		super(agent, "smelt");
		this.mode = mode;
		this.input = null;
		this.result = null;
		this.count = 0;
		this.fuel = null;
		this.fuelCount = 0;
		this.supported = List.of();
		this.pullOffhandKeep = null;
		this.baseTimeoutTicks = this.timeoutTicks;
		this.furnace = targetPos.immutable();
	}

	public static boolean isFurnaceBlock(BlockState st) {
		return furnaceKind(st) != null;
	}

	public static FurnaceKind furnaceKind(BlockState st) {
		if (st.is(Blocks.BLAST_FURNACE)) return FurnaceKind.BLAST_FURNACE;
		if (st.is(Blocks.SMOKER)) return FurnaceKind.SMOKER;
		if (st.is(Blocks.FURNACE)) return FurnaceKind.FURNACE;
		return null;
	}

	private static Block blockFor(FurnaceKind kind) {
		return switch (kind) {
			case BLAST_FURNACE -> Blocks.BLAST_FURNACE;
			case SMOKER -> Blocks.SMOKER;
			case FURNACE -> Blocks.FURNACE;
		};
	}

	/** 依配方支援的爐種與 S4.2e 優先順序找 32 格內最佳爐子。 */
	public static BlockPos findFurnace(Agent agent, Minecraft mc, List<FurnaceKind> supported, Set<BlockPos> rejected) {
		List<Block> blocks = new ArrayList<>(supported.size());
		for (FurnaceKind k : supported) blocks.add(blockFor(k));
		List<BlockPos> found = Moves.scan(mc, blocks, 32, 24, p -> !rejected.contains(p));
		if (found.isEmpty()) return null;
		BlockPos me = mc.player.blockPosition();
		List<Recipes.FurnaceCandidate<BlockPos>> candidates = new ArrayList<>(found.size());
		for (BlockPos p : found) {
			FurnaceKind k = furnaceKind(mc.level.getBlockState(p));
			if (k != null) candidates.add(new Recipes.FurnaceCandidate<>(p, k, agent.guard.isOwned(p), p.distSqr(me)));
		}
		return Recipes.selectPreferredFurnace(candidates, supported);
	}

	/** 計算熔煉逾時(S4.2e：高爐/煙燻爐每格 100 tick，普通熔爐每格 200 tick)。 */
	static long smeltTimeoutTicks(long baseTicks, int items, boolean fastFurnace) {
		return Math.max(baseTicks, items * (fastFurnace ? 100L : 200L) + 60 * 20L);
	}

	@Override
	public String describe() {
		return switch (mode) {
			case SMELT -> "熔煉 " + name(input) + " → " + name(result);
			case LIST -> "查看熔爐";
			case TAKE -> "取出熔爐產物";
		};
	}

	@Override
	public Progress hudProgress() {
		return mode == Mode.SMELT ? new Progress(taken, count) : null;
	}

	@Override
	public String progress() {
		if (mode == Mode.SMELT) return "output=" + Inv.id(result) + " got=" + taken + "/" + count;
		return "action=" + mode.name().toLowerCase(java.util.Locale.ROOT) + " pos=" + Fmt.pos(furnace);
	}

	@Override
	protected void start(Minecraft mc) {
		if (pullOffhandKeep != null && !mc.player.getOffhandItem().isEmpty()) {
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			if (!Inv.pullOffhandToMain(mc, s -> pullOffhandKeep.contains(s.getItem()))) {
				fail(ErrorCode.MISSING_MATERIALS, "a needed item is in the offhand and the main inventory is full",
					"drop or store one item, then smelt again");
				return;
			}
		}
		if (mode == Mode.SMELT) {
			if (preferredFurnace != null) updateTimeoutFor(mc, preferredFurnace);
			containerPhase = ContainerPhase.CUSTOM;
			customPhase = CustomPhase.FIND;
		} else {
			beginContainer(mc, furnace);
		}
	}

	private void updateTimeoutFor(Minecraft mc, BlockPos pos) {
		FurnaceKind k = furnaceKind(mc.level.getBlockState(pos));
		this.timeoutTicks = smeltTimeoutTicks(baseTimeoutTicks, count, k != null && k != FurnaceKind.FURNACE);
	}

	@Override
	protected boolean isExpectedMenu(AbstractContainerMenu menu) {
		return menu instanceof AbstractFurnaceMenu;
	}

	@Override
	protected String openTimeoutReason() {
		return "could not open the furnace at " + Fmt.pos(containerPos);
	}

	@Override
	protected void onWalkFailed(Minecraft mc) {
		if (mode != Mode.SMELT || workPhase == WorkPhase.PUT_FUEL || workPhase == WorkPhase.WAIT) {
			fail(ErrorCode.PATH_BLOCKED, mode == Mode.SMELT
				? "cannot get back to the furnace at " + Fmt.pos(furnace) + "; the input is in it"
				: "cannot reach the furnace at " + Fmt.pos(furnace));
			return;
		}
		rejected.add(furnace);
		workPhase = WorkPhase.CHECK;
		containerPhase = ContainerPhase.CUSTOM;
		customPhase = CustomPhase.FIND;
		customWaitTicks = 0;
	}

	@Override
	protected void onMenuClosedUnexpectedly(Minecraft mc) {
		if (mode != Mode.SMELT) {
			fail(ErrorCode.INTERRUPTED, "furnace screen was closed at " + Fmt.pos(furnace));
			return;
		}
		fail(ErrorCode.INTERRUPTED, workPhase == WorkPhase.WAIT
			? "furnace screen was closed; input and fuel remain in the furnace at " + Fmt.pos(furnace)
			: workPhase == WorkPhase.PUT_FUEL
				? "furnace screen was closed; input remains in the furnace at " + Fmt.pos(furnace)
				: "furnace screen was closed at " + Fmt.pos(furnace));
	}

	@Override
	protected void tickCustom(Minecraft mc) {
		switch (customPhase) {
			case FIND -> {
				BlockPos pref = preferredFurnace;
				preferredFurnace = null;
				if (pref != null) {
					FurnaceKind pk = furnaceKind(mc.level.getBlockState(pref));
					furnace = pk != null && supported.contains(pk) && !rejected.contains(pref) ? pref : findFurnace(agent, mc, supported, rejected);
				} else {
					furnace = findFurnace(agent, mc, supported, rejected);
				}
				if (furnace == null) {
					customPhase = CustomPhase.PLACE;
					customWaitTicks = 0;
				} else {
					updateTimeoutFor(mc, furnace);
					workPhase = WorkPhase.CHECK;
					beginContainer(mc, furnace);
				}
			}
			case PLACE -> {
				if (customWaitTicks++ == 0) {
					BlockPos spot = supported.contains(FurnaceKind.FURNACE)
						? Moves.findPlaceSpot(mc, p -> agent.guard.canPlaceInto(mc.level, p).allowed()) : null;
					if (spot == null || !Inv.selectInHand(mc, s -> s.is(Items.FURNACE))) {
						String busyNote = rejectedDetails.isEmpty() ? "" : " (" + String.join("; ", rejectedDetails) + ")";
						fail(ErrorCode.NO_STATION, rejected.isEmpty() ? "no furnace within 32 blocks or in inventory (craft furnace first)"
							: "nearby furnaces are busy with other items" + busyNote + " and none in inventory");
						return;
					}
					Moves.useOn(mc, spot.below(), Direction.UP);
					furnace = spot;
					placedOwn = true;
				} else if (mc.level.getBlockState(furnace).is(Blocks.FURNACE)) {
					updateTimeoutFor(mc, furnace);
					workPhase = WorkPhase.CHECK;
					beginContainer(mc, furnace);
				} else if (customWaitTicks > 20) {
					fail(ErrorCode.NO_STATION, "could not place the furnace");
				}
			}
		}
	}

	@Override
	protected void work(Minecraft mc) {
		AbstractFurnaceMenu m = (AbstractFurnaceMenu) mc.player.containerMenu;
		if (mode == Mode.LIST) {
			finishText = furnaceSnapshot(mc, m, null).toString();
			finishContainer();
			return;
		}
		if (mode == Mode.TAKE) {
			ItemStack out = m.getSlot(2).getItem();
			String took = "none";
			boolean partial = false;
			if (!out.isEmpty()) {
				Item outItem = out.getItem();
				int before = Inv.count(mc.player, outItem);
				MenuOps.quickMove(mc, m, 2);
				int got = Math.max(0, Inv.count(mc.player, outItem) - before);
				if (got > 0) took = Inv.id(outItem) + ":" + got;
				partial = !m.getSlot(2).getItem().isEmpty();
			}
			Kv kv = furnaceSnapshot(mc, m, took);
			if (partial) kv.put("note", "inventory full; some output remains");
			finishText = kv.toString();
			finishContainer();
			return;
		}
		switch (workPhase) {
			case CHECK -> {
				ItemStack in = m.getSlot(0).getItem(), f = m.getSlot(1).getItem(), out = m.getSlot(2).getItem();
				boolean blockedInput = !in.isEmpty() && (in.getItem() != input || in.getCount() + count > in.getMaxStackSize());
				int firstFuel = Math.min(fuelCount, m.getSlot(1).getMaxStackSize(new ItemStack(fuel)));
				boolean blockedFuel = !f.isEmpty() && (f.getItem() != fuel || f.getCount() + firstFuel > m.getSlot(1).getMaxStackSize(f));
				boolean blockedOut = !out.isEmpty() && (out.getItem() != result || out.getCount() + count > out.getMaxStackSize());
				if (!blockedInput && !blockedOut
					&& canClearOwnLeftoverFuel(agent.guard.isOwned(furnace), m.isLit(), in.isEmpty(), out.isEmpty(), blockedFuel, canReceive(mc.player, f))) {
					MenuOps.quickMove(mc, m, 1);
					f = m.getSlot(1).getItem();
					blockedFuel = !f.isEmpty() && (f.getItem() != fuel || f.getCount() + firstFuel > m.getSlot(1).getMaxStackSize(f));
				}
				if (blockedInput || blockedFuel || blockedOut) {
					rejectedDetails.add(describeBusy(furnace, in, f, out));
					rejected.add(furnace);
					closeAndCustom(mc);
					customPhase = CustomPhase.FIND;
					return;
				}
				outBefore = out.isEmpty() ? 0 : out.getCount();
				workPhase = WorkPhase.PUT_INPUT;
			}
			case PUT_INPUT -> {
				int placed = MenuOps.placeInto(mc, m, 0, s -> s.getItem() == input, count);
				if (placed < count) {
					fail(ErrorCode.MISSING_MATERIALS, "not enough input: put " + placed + "/" + count + " " + Inv.id(input));
					return;
				}
				workPhase = WorkPhase.PUT_FUEL;
			}
			case PUT_FUEL -> {
				fuelPut += MenuOps.placeInto(mc, m, 1, s -> s.getItem() == fuel, fuelCount - fuelPut);
				if (fuelPut == 0) {
					fail(ErrorCode.MISSING_MATERIALS, "not enough fuel: put 0/" + fuelCount + " " + Inv.id(fuel));
					return;
				}
				workPhase = WorkPhase.WAIT;
				stallTicks = 0;
			}
			case WAIT -> {
				ItemStack out = m.getSlot(2).getItem();
				if (!out.isEmpty() && out.getItem() == result) {
					int before = Inv.count(mc.player, result);
					MenuOps.quickMove(mc, m, 2);
					int got = Math.max(0, Inv.count(mc.player, result) - before);
					int old = Math.min(outBefore, got);
					outBefore -= old;
					taken += got - old;
				}
				if (taken >= count) {
					Kv kv = Kv.of().put("furnace", RuleGuard.shortId(mc.level.getBlockState(furnace)));
					if (placedOwn) kv.put("placed_furnace", Fmt.pos(furnace));
					finishText = kv.toString();
					finishContainer();
					return;
				}
				ItemStack fuelStack = m.getSlot(1).getItem();
				boolean noFuel = fuelStack.isEmpty() || !fuelStack.has(DataComponents.COOKING_FUEL);
				if (fuelStack.isEmpty() && fuelPut < fuelCount) {
					int added = MenuOps.placeInto(mc, m, 1, s -> s.getItem() == fuel, fuelCount - fuelPut);
					fuelPut += added;
					if (added > 0) {
						stallTicks = 0;
						return;
					}
				}
				if (shouldStall(m.isLit(), out.isEmpty(), noFuel, m.getSlot(0).getItem().isEmpty())) {
					if (stallTicks++ > 40) {
						fail(ErrorCode.MISSING_MATERIALS, noFuel ? "furnace ran out of fuel (put " + fuelPut + "/" + fuelCount + " " + Inv.id(fuel) + ")"
							: "the input was taken out of the furnace");
					}
				} else {
					stallTicks = 0;
				}
			}
		}
	}

	private Kv furnaceSnapshot(Minecraft mc, AbstractFurnaceMenu m, String took) {
		Kv kv = Kv.of().put("furnace", RuleGuard.shortId(mc.level.getBlockState(furnace)));
		if (took != null) kv.put("took", took);
		kv.put("in", Fmt.item(m.getSlot(0).getItem()))
			.put("fuel", Fmt.item(m.getSlot(1).getItem()))
			.put("out", Fmt.item(m.getSlot(2).getItem()))
			.put("lit", m.isLit());
		if (took == null) kv.put("progress", Math.round(m.getBurnProgress() * 100) + "%");
		return kv;
	}

	@Override
	protected void onClosed(Minecraft mc) {
		done(finishText == null ? "" : finishText);
	}

	/** 自己擁有且已熄火的空熔爐，只有燃料格殘留別種燃料時可自動取回(純邏輯，供單元測試直接驗證)。 */
	static boolean canClearOwnLeftoverFuel(boolean ownFurnace, boolean lit, boolean inputEmpty, boolean outputEmpty,
											boolean fuelBlocked, boolean canReceive) {
		return ownFurnace && !lit && inputEmpty && outputEmpty && fuelBlocked && canReceive;
	}

	private static boolean canReceive(net.minecraft.world.entity.player.Player player, ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (Inv.freeSlots(player) > 0) return true;
		var inv = player.getInventory();
		int space = 0;
		for (int i = 0; i < Inv.MAIN_SIZE; i++) {
			ItemStack slot = inv.getItem(i);
			if (ItemStack.isSameItemSameComponents(slot, stack)) {
				space += Math.max(0, slot.getMaxStackSize() - slot.getCount());
				if (space >= stack.getCount()) return true;
			}
		}
		return false;
	}

	private static String describeBusy(BlockPos pos, ItemStack in, ItemStack f, ItemStack out) {
		List<String> parts = new ArrayList<>();
		if (!in.isEmpty()) parts.add("in=" + Inv.id(in) + ":" + in.getCount());
		if (!f.isEmpty()) parts.add("fuel=" + Inv.id(f) + ":" + f.getCount());
		if (!out.isEmpty()) parts.add("out=" + Inv.id(out) + ":" + out.getCount());
		return Fmt.pos(pos) + (parts.isEmpty() ? "" : "[" + String.join(",", parts) + "]");
	}

	/** 熔爐是否已停下來(熄火、產物格空、且缺燃料或缺原料；供單元測試直接驗證)。 */
	static boolean shouldStall(boolean lit, boolean outputEmpty, boolean noFuel, boolean noInput) {
		return !lit && outputEmpty && (noFuel || noInput);
	}

	@Override
	protected void onResume(Minecraft mc) {
		if (mode == Mode.SMELT && containerPhase == ContainerPhase.CUSTOM && customPhase == CustomPhase.PLACE) {
			markOpenedContainer(false);
			if (placedOwn && furnace != null && mc.level.getBlockState(furnace).is(Blocks.FURNACE)) {
				beginContainer(mc, furnace);
			} else {
				customWaitTicks = placedOwn && furnace != null ? Math.max(customWaitTicks, 1) : 0;
			}
			return;
		}
		stallTicks = 0;
		super.onResume(mc);
	}

	/** 依燃燒時間算需要幾個燃料(S4.4、S4.2e：高爐與煙燻爐燃燒時間折半但速度兩倍，燃料總數相同)。 */
	public static int fuelNeeded(Item fuel, int items) {
		int burn = Recipes.burnTicks(BuiltInRegistries.ITEM.getKey(fuel).toString(), true);
		return Math.max(1, (items * 200 + burn - 1) / burn);
	}
}
