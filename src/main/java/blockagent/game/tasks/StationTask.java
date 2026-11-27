package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalGetToBlock;
import blockagent.core.ItemMatcher;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 走到某個方塊並使用它：睡覺(sleep)、箱子看內容/放/拿東西(chest action=list|put|take)。
 * 箱子操作共用 {@link ContainerTask} 生命週期，支援 {@link ItemMatcher} 屬性篩選與副手換回(S4.2d、S4.2e)。
 */
public final class StationTask extends ContainerTask {
	public enum Mode {SLEEP, CHEST_LIST, CHEST_PUT, CHEST_TAKE}

	public record ChestEntry(String key, Item item, ItemMatcher matcher, int count) {
	}

	private enum SleepPhase {WALK, USE, WAIT, DONE}

	private final Mode mode;
	private final BlockPos pos;
	private final List<ChestEntry> entries;
	private final Predicate<ItemStack> pullOffhandKeep;
	private final Map<String, Integer> moved = new LinkedHashMap<>();
	private SleepPhase sleepPhase = SleepPhase.WALK;
	private int sleepWaitTicks;
	private String failMessage;
	private String contents;

	public StationTask(Agent agent, Mode mode, BlockPos pos, List<ChestEntry> entries, Predicate<ItemStack> pullOffhandKeep) {
		super(agent, mode == Mode.SLEEP ? "sleep" : "chest");
		this.mode = mode;
		this.pos = pos.immutable();
		this.entries = entries == null ? List.of() : List.copyOf(entries);
		this.pullOffhandKeep = pullOffhandKeep;
	}

	@Override
	public String describe() {
		return switch (mode) {
			case SLEEP -> "去睡覺";
			case CHEST_LIST -> "查看箱子";
			case CHEST_PUT -> "放東西進箱子";
			case CHEST_TAKE -> "從箱子拿東西";
		};
	}

	@Override
	public String progress() {
		Kv kv = Kv.of().put("pos", Fmt.pos(pos));
		if (mode == Mode.SLEEP) return kv.put("state", sleepPhase == SleepPhase.DONE ? "asleep" : "walking").toString();
		if (mode == Mode.CHEST_LIST) return kv.toString();
		return kv.put("moved", Kv.counts(moved)).toString();
	}

	@Override
	public boolean moves() {
		return mode == Mode.SLEEP ? sleepPhase == SleepPhase.WALK : super.moves();
	}

	@Override
	protected void start(Minecraft mc) {
		if (mode == Mode.SLEEP) {
			containerPhase = ContainerPhase.CUSTOM;
			boolean near = mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)) <= 1.8;
			if (near) {
				sleepPhase = SleepPhase.USE;
			} else {
				sleepPhase = SleepPhase.WALK;
				BlockPos spot = standSpotNear(mc, pos);
				path(spot != null ? new baritone.api.pathing.goals.GoalBlock(spot) : new GoalGetToBlock(pos));
			}
			return;
		}
		if (pullOffhandKeep != null && !mc.player.getOffhandItem().isEmpty()) {
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			if (!Inv.pullOffhandToMain(mc, pullOffhandKeep)) {
				fail(ErrorCode.MISSING_MATERIALS, "a needed item is in the offhand and the main inventory is full",
					"drop or store one item, then chest again");
				return;
			}
		}
		beginContainer(mc, pos);
	}

	@Override
	public void onSystemMessage(String text) {
		if (mode == Mode.SLEEP && sleepPhase == SleepPhase.WAIT) failMessage = text;
	}

	/** 床旁邊一格可以站的位置(空氣、下面是實心方塊)。 */
	static BlockPos standSpotNear(Minecraft mc, BlockPos bed) {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos p = bed.relative(d);
			if (mc.level.getBlockState(p).isAir() && mc.level.getBlockState(p.above()).isAir()
				&& mc.level.getBlockState(p.below()).isFaceSturdy(mc.level, p.below(), net.minecraft.core.Direction.UP)) {
				double dd = p.distSqr(mc.player.blockPosition());
				if (dd < bd) {
					bd = dd;
					best = p;
				}
			}
		}
		return best;
	}

	@Override
	protected boolean isExpectedMenu(AbstractContainerMenu menu) {
		return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu;
	}

	@Override
	protected void openContainerBlock(Minecraft mc) {
		Inv.selectInHand(mc, ItemStack::isEmpty);
		super.openContainerBlock(mc);
	}

	@Override
	protected String openTimeoutReason() {
		return "chest screen did not open at " + Fmt.pos(pos);
	}

	@Override
	protected void tickCustom(Minecraft mc) {
		switch (sleepPhase) {
			case WALK -> {
				boolean arrived = mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)) <= 2.0;
				if (arrived) {
					Bari.stopAll();
					sleepPhase = SleepPhase.USE;
					sleepWaitTicks = 0;
				} else if ((!Bari.busy() && sleepWaitTicks++ > 40) || elapsed() > 20 * 60) {
					fail(ErrorCode.PATH_BLOCKED, "cannot reach " + Fmt.pos(pos));
				}
			}
			case USE -> {
				var state = mc.level.getBlockState(pos);
				if (!state.is(net.minecraft.tags.BlockTags.BEDS) || !blockagent.game.Observation.bedUsable(mc.level, pos, state)) {
					fail(ErrorCode.INVALID_ARGUMENT, "bed cannot be used safely here");
					return;
				}
				Inv.selectInHand(mc, ItemStack::isEmpty);
				Moves.useOn(mc, pos, Moves.faceToward(mc.player, pos));
				mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
				sleepPhase = SleepPhase.WAIT;
				sleepWaitTicks = 0;
			}
			case WAIT -> {
				sleepWaitTicks++;
				if (mc.player.isSleeping()) {
					sleepPhase = SleepPhase.DONE;
					done("asleep=true");
				} else if (failMessage != null && sleepWaitTicks > 2) {
					fail(ErrorCode.NO_STATION, "cannot sleep: " + failMessage);
				} else if (sleepWaitTicks > 40) {
					fail(ErrorCode.NO_STATION, "cannot sleep (not night, monsters nearby, or bed occupied)");
				}
			}
			case DONE -> done("asleep=true");
		}
	}

	@Override
	protected void work(Minecraft mc) {
		AbstractContainerMenu m = mc.player.containerMenu;
		for (ChestEntry e : entries) {
			int want = e.count() - moved.getOrDefault(e.key(), 0);
			if (want <= 0) continue;
			int got = mode == Mode.CHEST_PUT ? put(mc, m, e, want) : take(mc, m, e, want);
			moved.merge(e.key(), got, Integer::sum);
		}
		contents = contents(m);
		finishContainer();
	}

	@Override
	protected void onClosed(Minecraft mc) {
		if (mode == Mode.CHEST_LIST) {
			done(contents);
			return;
		}
		boolean all = entries.stream().allMatch(e -> moved.getOrDefault(e.key(), 0) >= e.count());
		if (all) done(contents);
		else fail(ErrorCode.MISSING_MATERIALS, "moved only part of the items (not enough, chest full, or blocked by a rule); " + contents);
	}

	/** 箱子目前的內容(同種合併)和空格數，例如 {@code contents=oak_log:12,torch:5 free=20}；附魔等特殊物品另外列出。 */
	private static String contents(AbstractContainerMenu m) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		List<String> special = new ArrayList<>();
		int free = 0;
		for (int i : MenuOps.containerSlots(m)) {
			ItemStack s = m.getSlot(i).getItem();
			if (s.isEmpty()) {
				free++;
				continue;
			}
			counts.merge(Inv.id(s), s.getCount(), Integer::sum);
			boolean notable = Fmt.props(s).stream().anyMatch(prop -> !prop.startsWith("dur="));
			if (notable && special.size() < 8) special.add(Fmt.item(s));
		}
		Kv kv = Kv.of().put("contents", Kv.counts(counts)).put("free", free);
		if (!special.isEmpty()) kv.put("special", String.join(",", special));
		return kv.toString();
	}

	private int put(Minecraft mc, AbstractContainerMenu m, ChestEntry e, int want) {
		Comparator<ItemStack> order = e.matcher().durPref() == ItemMatcher.DurPref.NONE
			? null : (a, b) -> e.matcher().comparePreference(Inv.infoOf(a), Inv.infoOf(b));
		int movedNow = 0;
		for (int target : MenuOps.containerSlots(m)) {
			if (movedNow >= want) break;
			ItemStack t = m.getSlot(target).getItem();
			if (!t.isEmpty() && (!t.is(e.item()) || t.getCount() >= t.getMaxStackSize())) continue;
			int room = t.isEmpty() ? e.item().getDefaultMaxStackSize() : t.getMaxStackSize() - t.getCount();
			movedNow += MenuOps.placeInto(mc, m, target, s -> Inv.matches(s, e.item(), e.matcher()), order, Math.min(room, want - movedNow));
		}
		return movedNow;
	}

	private int take(Minecraft mc, AbstractContainerMenu m, ChestEntry e, int want) {
		Predicate<ItemStack> pred = s -> Inv.matches(s, e.item(), e.matcher());
		int before = MenuOps.countInPlayer(m, pred);
		List<Integer> srcs = new ArrayList<>();
		for (int src : MenuOps.containerSlots(m)) {
			if (pred.test(m.getSlot(src).getItem())) srcs.add(src);
		}
		if (e.matcher().durPref() != ItemMatcher.DurPref.NONE && srcs.size() > 1) {
			srcs.sort((a, b) -> e.matcher().comparePreference(Inv.infoOf(m.getSlot(a).getItem()), Inv.infoOf(m.getSlot(b).getItem())));
		}
		for (int src : srcs) {
			int now = MenuOps.countInPlayer(m, pred) - before;
			if (now >= want) break;
			ItemStack s = m.getSlot(src).getItem();
			if (!pred.test(s)) continue;
			if (s.getCount() <= want - now) {
				MenuOps.quickMove(mc, m, src);
			} else {
				Inv.click(mc, m, src, 0, ContainerInput.PICKUP);
				int left = want - now;
				for (int i : MenuOps.playerSlots(m)) {
					if (left <= 0 || m.getCarried().isEmpty()) break;
					ItemStack d = m.getSlot(i).getItem();
					if (d.isEmpty() || !ItemStack.isSameItemSameComponents(d, m.getCarried())) continue;
					int k = Math.min(left, d.getMaxStackSize() - d.getCount());
					for (int j = 0; j < k; j++) Inv.click(mc, m, i, 1, ContainerInput.PICKUP);
					left -= k;
				}
				if (left > 0 && !m.getCarried().isEmpty()) {
					for (int i : MenuOps.playerSlots(m)) {
						if (m.getSlot(i).hasItem()) continue;
						for (int k = 0; k < left; k++) Inv.click(mc, m, i, 1, ContainerInput.PICKUP);
						break;
					}
				}
				Inv.click(mc, m, src, 0, ContainerInput.PICKUP);
			}
		}
		return MenuOps.countInPlayer(m, pred) - before;
	}

	@Override
	protected void onResume(Minecraft mc) {
		if (mode == Mode.SLEEP) {
			sleepWaitTicks = 0;
			failMessage = null;
			if (sleepPhase == SleepPhase.USE || sleepPhase == SleepPhase.WAIT) {
				if (mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)) <= 1.8) {
					sleepPhase = SleepPhase.USE;
				} else {
					sleepPhase = SleepPhase.WALK;
					BlockPos spot = standSpotNear(mc, pos);
					path(spot != null ? new baritone.api.pathing.goals.GoalBlock(spot) : new GoalGetToBlock(pos));
				}
			} else if (sleepPhase == SleepPhase.WALK) {
				super.onResume(mc);
			}
			return;
		}
		super.onResume(mc);
	}
}
