package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalGetToBlock;
import blockagent.core.Recipes;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 熔煉(M2.8 的 smelt)：找附近的熔爐(別人的也可以)，沒有就放一個背包裡的熔爐；
 * 放入原料和燃料，等燒好，拿出產物(S7.5)。原料格、燃料格或產物格被別的東西佔住時，改找別的熔爐。
 */
public final class SmeltTask extends Task {
	private enum Phase {FIND, PLACE, WALK, OPEN, SYNC, CHECK, PUT_INPUT, PUT_FUEL, WAIT, DONE}

	private final Item input;
	private final Item result;
	private final int count;
	private final Item fuel;
	private final int fuelCount;
	private final Set<BlockPos> rejected = new HashSet<>();
	private BlockPos furnace;
	private Phase phase = Phase.FIND;
	private int waitTicks;
	private int taken;
	/** 開始前產物格裡已經有的數量(也會一起拿出來，但不算進這次燒好的)。 */
	private int outBefore;
	private boolean placedOwn;
	/** 已經放進燃料格的燃料數；燃料比一格放得下的多時分批補(C7)。 */
	private int fuelPut;
	/** 被反射暫停後重新打開熔爐，要回到哪個階段(H7)；null 表示照一般流程走 CHECK。 */
	private Phase afterOpen;

	public SmeltTask(Agent agent, Item input, Item result, int count, Item fuel, int fuelCount) {
		super(agent, "smelt");
		this.input = input;
		this.result = result;
		this.count = count;
		this.fuel = fuel;
		this.fuelCount = fuelCount;
		this.timeoutTicks = Math.max(timeoutTicks, count * 200L + 60 * 20L);
	}

	@Override
	public String describe() {
		return "熔煉 " + Inv.id(input) + " → " + Inv.id(result) + " " + taken + "/" + count;
	}

	@Override
	public String progress() {
		return "output=" + Inv.id(result) + " got=" + taken + "/" + count;
	}

	private boolean menuOpen(Minecraft mc) {
		return mc.player.containerMenu instanceof FurnaceMenu;
	}

	@Override
	public boolean moves() {
		return phase == Phase.WALK;
	}

	@Override
	protected void tick(Minecraft mc) {
		switch (phase) {
			case FIND -> {
				List<BlockPos> list = Moves.scan(mc, List.of(Blocks.FURNACE), 32, 10, p -> !rejected.contains(p));
				BlockPos own = list.stream().filter(agent.guard::isOwned).findFirst().orElse(null);
				furnace = own != null ? own : list.isEmpty() ? null : list.getFirst();
				if (furnace == null) {
					phase = Phase.PLACE;
				} else {
					phase = Moves.canReach(mc.player, furnace) ? Phase.OPEN : Phase.WALK;
					if (phase == Phase.WALK) path(new GoalGetToBlock(furnace));
				}
				waitTicks = 0;
			}
			case PLACE -> {
				if (waitTicks++ == 0) {
					BlockPos spot = Moves.findPlaceSpot(mc, p -> agent.guard.canPlaceInto(mc.level, p).allowed());
					if (spot == null || !Inv.selectInHand(mc, s -> s.is(Items.FURNACE))) {
						fail(ErrorCode.NO_STATION, rejected.isEmpty() ? "no furnace within 32 blocks or in inventory (craft furnace first)"
							: "nearby furnaces are busy with other items and none in inventory");
						return;
					}
					Moves.useOn(mc, spot.below(), Direction.UP);
					furnace = spot;
					placedOwn = true;
				} else if (mc.level.getBlockState(furnace).is(Blocks.FURNACE)) {
					phase = Phase.OPEN;
					waitTicks = 0;
				} else if (waitTicks > 20) {
					fail(ErrorCode.NO_STATION, "could not place the furnace");
				}
			}
			case WALK -> {
				if (Moves.canReach(mc.player, furnace)) {
					Bari.stopAll();
					phase = Phase.OPEN;
					waitTicks = 0;
				} else if (!Bari.busy() && waitTicks++ > 40) {
					if (afterOpen == Phase.PUT_FUEL || afterOpen == Phase.WAIT) {
						// 原料(和燃料)已經放進去了，回不去就不能換別的熔爐
						fail(ErrorCode.PATH_BLOCKED, "cannot get back to the furnace at " + blockagent.game.Fmt.pos(furnace) + "; the input is in it");
						return;
					}
					afterOpen = null;
					rejected.add(furnace);
					phase = Phase.FIND;
				}
			}
			case OPEN -> {
				if (menuOpen(mc)) {
					markOpenedContainer(true);
					phase = Phase.SYNC;
					waitTicks = 0;
				} else if (waitTicks++ % 20 == 0) {
					if (waitTicks > 60) {
						failToOpen("could not open the furnace at " + blockagent.game.Fmt.pos(furnace));
						return;
					}
					Moves.useOn(mc, furnace, Moves.faceToward(mc.player, furnace));
				}
			}
			case SYNC -> {
				if (!menuOpen(mc)) {
					fail(ErrorCode.INTERRUPTED, afterOpen == Phase.WAIT
						? "furnace screen was closed; input and fuel remain in the furnace at " + blockagent.game.Fmt.pos(furnace)
						: afterOpen == Phase.PUT_FUEL
							? "furnace screen was closed; input remains in the furnace at " + blockagent.game.Fmt.pos(furnace)
							: "furnace screen was closed at " + blockagent.game.Fmt.pos(furnace));
					return;
				}
				AbstractFurnaceMenu m = (AbstractFurnaceMenu) mc.player.containerMenu;
				// 等伺服器送回內容與燃燒狀態封包(恢復進入 PUT_*/WAIT 也一樣要等，P3-H6)
				if (waitTicks++ < 3 || (m.getStateId() == 0 && waitTicks < 40)) return;
				phase = afterOpen != null ? afterOpen : Phase.CHECK;
				afterOpen = null;
				waitTicks = 0;
			}
			case CHECK -> {
				if (!menuOpen(mc)) {
					fail(ErrorCode.INTERRUPTED, "furnace screen was closed at " + blockagent.game.Fmt.pos(furnace));
					return;
				}
				AbstractFurnaceMenu m = (AbstractFurnaceMenu) mc.player.containerMenu;
				ItemStack in = m.getSlot(0).getItem(), f = m.getSlot(1).getItem(), out = m.getSlot(2).getItem();
				boolean blockedInput = !in.isEmpty() && (in.getItem() != input || in.getCount() + count > in.getMaxStackSize());
				// 燃料可以分批補，只要第一批放得下(木棒 80 根、木鏟一次只能放 1 個)
				int firstFuel = Math.min(fuelCount, m.getSlot(1).getMaxStackSize(new ItemStack(fuel)));
				boolean blockedFuel = !f.isEmpty() && (f.getItem() != fuel || f.getCount() + firstFuel > m.getSlot(1).getMaxStackSize(f));
				boolean blockedOut = !out.isEmpty() && (out.getItem() != result || out.getCount() + count > out.getMaxStackSize());
				if (blockedInput || blockedFuel || blockedOut) {
					MenuOps.close(mc);
					markOpenedContainer(false);
					rejected.add(furnace);
					phase = Phase.FIND;
					return;
				}
				outBefore = out.isEmpty() ? 0 : out.getCount();
				phase = Phase.PUT_INPUT;
			}
			case PUT_INPUT -> {
				if (!menuOpen(mc)) {
					fail(ErrorCode.INTERRUPTED, "furnace screen was closed before placing input at " + blockagent.game.Fmt.pos(furnace));
					return;
				}
				var m = mc.player.containerMenu;
				int placed = MenuOps.placeInto(mc, m, 0, s -> s.getItem() == input, count);
				if (placed < count) {
					fail(ErrorCode.MISSING_MATERIALS, "not enough input: put " + placed + "/" + count + " " + Inv.id(input));
					return;
				}
				phase = Phase.PUT_FUEL;
			}
			case PUT_FUEL -> {
				if (!menuOpen(mc)) {
					fail(ErrorCode.INTERRUPTED, "furnace screen was closed; input remains in the furnace at " + blockagent.game.Fmt.pos(furnace));
					return;
				}
				var m = mc.player.containerMenu;
				fuelPut += MenuOps.placeInto(mc, m, 1, s -> s.getItem() == fuel, fuelCount - fuelPut);
				if (fuelPut == 0) {
					fail(ErrorCode.MISSING_MATERIALS, "not enough fuel: put 0/" + fuelCount + " " + Inv.id(fuel));
					return;
				}
				phase = Phase.WAIT;
				waitTicks = 0;
			}
			case WAIT -> {
				if (!menuOpen(mc)) {
					fail(ErrorCode.INTERRUPTED, "furnace screen was closed; input and fuel remain in the furnace at " + blockagent.game.Fmt.pos(furnace));
					return;
				}
				AbstractFurnaceMenu m = (AbstractFurnaceMenu) mc.player.containerMenu;
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
					phase = Phase.DONE;
					return;
				}
				ItemStack fuelStack = m.getSlot(1).getItem();
				// 燃料格是空的，或留著非燃料(例如燒完岩漿桶留下的空桶)都算沒燃料(Low)
				boolean noFuel = fuelStack.isEmpty() || !fuelStack.has(net.minecraft.core.component.DataComponents.COOKING_FUEL);
				// 燃料燒完了而且還有沒放進去的燃料：補進去(C7)
				if (fuelStack.isEmpty() && fuelPut < fuelCount) {
					int added = MenuOps.placeInto(mc, m, 1, s -> s.getItem() == fuel, fuelCount - fuelPut);
					fuelPut += added;
					if (added > 0) {
						waitTicks = 0;
						return;
					}
				}
				// 熄火、產物格也空了，而且沒有燃料(燒完了)或沒有原料(被拿走了)：停了(C6：原本只看原料格，燃料燒完時永遠不成立)
				if (shouldStall(m.isLit(), out.isEmpty(), noFuel, m.getSlot(0).getItem().isEmpty())) {
					if (waitTicks++ > 40) {
						fail(ErrorCode.MISSING_MATERIALS, noFuel ? "furnace ran out of fuel (put " + fuelPut + "/" + fuelCount + " " + Inv.id(fuel) + ")"
							: "the input was taken out of the furnace");
					}
				} else {
					waitTicks = 0;
				}
			}
			case DONE -> {
				MenuOps.close(mc);
				markOpenedContainer(false);
				done(placedOwn ? "placed_furnace=" + blockagent.game.Fmt.pos(furnace) : "");
			}
		}
	}

	@Override
	protected void onStop(Minecraft mc) {
		Bari.stopAll();
		if (openedContainer) MenuOps.close(mc);
	}

	/**
	 * 反射接手時熔爐畫面被關掉了(onSuspend = onStop)：要用到熔爐畫面的階段先重新打開，再回到原本的階段(H7)。
	 * 不然 CHECK 會把背包畫面當熔爐畫面、PUT_* 會點到 2×2 合成格。
	 */
	/** 熔爐是否已停下來(熄火、產物格空、且缺燃料或缺原料；供單元測試直接驗證)。 */
	static boolean shouldStall(boolean lit, boolean outputEmpty, boolean noFuel, boolean noInput) {
		return !lit && outputEmpty && (noFuel || noInput);
	}

	@Override
	protected void onResume(Minecraft mc) {
		markOpenedContainer(false);
		switch (phase) {
			case OPEN, SYNC, CHECK, PUT_INPUT, PUT_FUEL, WAIT -> {
				afterOpen = (phase == Phase.OPEN || phase == Phase.SYNC) ? afterOpen : phase;
				phase = Moves.canReach(mc.player, furnace) ? Phase.OPEN : Phase.WALK;
				if (phase == Phase.WALK) path(new GoalGetToBlock(furnace));
				waitTicks = 0;
			}
			case WALK -> {
				waitTicks = 0;
				super.onResume(mc);
			}
			// 熔爐已經放過了就不要再放一次；若被反射拉遠，先走回熔爐旁再開(P3-H7)
			case PLACE -> {
				if (placedOwn && furnace != null && mc.level.getBlockState(furnace).is(Blocks.FURNACE)) {
					phase = Moves.canReach(mc.player, furnace) ? Phase.OPEN : Phase.WALK;
					if (phase == Phase.WALK) path(new GoalGetToBlock(furnace));
					waitTicks = 0;
				} else {
					waitTicks = placedOwn && furnace != null ? Math.max(waitTicks, 1) : 0;
				}
			}
			default -> {
			}
		}
	}

	/** 依燃燒時間算需要幾個燃料(S4.4)。 */
	public static int fuelNeeded(Item fuel, int items) {
		int burn = Recipes.burnTicks(BuiltInRegistries.ITEM.getKey(fuel).toString(), true);
		return Math.max(1, (items * 200 + burn - 1) / burn);
	}
}
