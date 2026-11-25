package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalGetToBlock;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 走到某個方塊並使用它：睡覺(sleep)、箱子看內容/放/拿東西(chest action=list|put|take)。
 * 箱子的結果都附上關上前的內容，AI 不必猜裡面有什麼(A2)。
 */
public final class StationTask extends Task {
	public enum Mode {SLEEP, CHEST_LIST, CHEST_PUT, CHEST_TAKE}

	private enum Phase {WALK, USE, WORK, DONE}

	private final Mode mode;
	private final BlockPos pos;
	private final Map<String, Integer> items;
	private final Map<String, Integer> moved = new LinkedHashMap<>();
	private Phase phase;
	private int waitTicks;
	private String failMessage;
	private String contents;
	/** 箱子畫面出現的 tick；從這時候開始等內容同步(M14)。 */
	private long openedAt = -1;

	public StationTask(Agent agent, Mode mode, BlockPos pos, Map<String, Integer> items) {
		super(agent, mode == Mode.SLEEP ? "sleep" : "chest");
		this.mode = mode;
		this.pos = pos.immutable();
		this.items = items;
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
		blockagent.mcp.Kv kv = blockagent.mcp.Kv.of().put("pos", blockagent.game.Fmt.pos(pos));
		if (mode == Mode.SLEEP) return kv.put("state", phase == Phase.DONE ? "asleep" : "walking").toString();
		if (mode == Mode.CHEST_LIST) return kv.toString();
		return kv.put("moved", blockagent.mcp.Kv.counts(moved)).toString();
	}

	@Override
	public boolean moves() {
		return phase == Phase.WALK;
	}

	@Override
	protected void start(Minecraft mc) {
		// 睡覺要離床很近(比伸手碰得到還近)，所以一定先走到床旁邊
		boolean near = mode == Mode.SLEEP
			? mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)) <= 1.8
			: Moves.canReach(mc.player, pos);
		if (near) phase = Phase.USE;
		else {
			phase = Phase.WALK;
			BlockPos spot = mode == Mode.SLEEP ? standSpotNear(mc, pos) : null;
			path(spot != null ? new baritone.api.pathing.goals.GoalBlock(spot) : new GoalGetToBlock(pos));
		}
	}

	@Override
	public void onSystemMessage(String text) {
		if (mode == Mode.SLEEP && phase == Phase.WORK) failMessage = text;
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

	static Item item(String id) {
		// 跟工具的參數檢查一樣不分大小寫、去掉空白(M14：OAK_LOG 走到箱子前才拋例外)
		String norm = id.strip().toLowerCase(java.util.Locale.ROOT);
		return BuiltInRegistries.ITEM.getValue(Identifier.parse(norm.contains(":") ? norm : "minecraft:" + norm));
	}

	@Override
	protected void tick(Minecraft mc) {
		switch (phase) {
			case WALK -> {
				boolean arrived = mode == Mode.SLEEP
					? mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)) <= 2.0
					: Moves.canReach(mc.player, pos);
				if (arrived) {
					Bari.stopAll();
					phase = Phase.USE;
					waitTicks = 0;
				} else if ((!Bari.busy() && waitTicks++ > 40) || elapsed() > 20 * 60) {
					fail(ErrorCode.PATH_BLOCKED, "cannot reach " + blockagent.game.Fmt.pos(pos));
				}
			}
			case USE -> {
				Inv.selectInHand(mc, ItemStack::isEmpty);
				var r = Moves.useOn(mc, pos, Moves.faceToward(mc.player, pos));
				if (!r.consumesAction() && mode != Mode.SLEEP) {
					failToOpen("could not open " + blockagent.game.Fmt.pos(pos));
					return;
				}
				mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
				phase = Phase.WORK;
				waitTicks = 0;
			}
			case WORK -> {
				waitTicks++;
				if (mode == Mode.SLEEP) {
					if (mc.player.isSleeping()) {
						phase = Phase.DONE;
					} else if (failMessage != null && waitTicks > 2) {
						fail(ErrorCode.NO_STATION, "cannot sleep: " + failMessage);
					} else if (waitTicks > 40) {
						fail(ErrorCode.NO_STATION, "cannot sleep (not night, monsters nearby, or bed occupied)");
					}
					return;
				}
				AbstractContainerMenu m = mc.player.containerMenu;
				if (!(m instanceof ChestMenu || m instanceof ShulkerBoxMenu)) {
					if (waitTicks > 40) failToOpen("chest screen did not open");
					return;
				}
				markOpenedContainer(true);
				if (openedAt < 0) openedAt = agent.tick();
				// 等伺服器送來箱子內容(stateId 不再是 0)，最少等 3 tick；連線很慢時最多等 2 秒(M14：不然會讀成空箱)
				long since = agent.tick() - openedAt;
				if (since < 3 || (m.getStateId() == 0 && since < 40)) return;
				for (var en : items.entrySet()) {
					Item it = item(en.getKey());
					int want = en.getValue() - moved.getOrDefault(Inv.id(it), 0);
					if (want <= 0) continue;
					int got = mode == Mode.CHEST_PUT ? put(mc, m, it, want) : take(mc, m, it, want);
					moved.merge(Inv.id(it), got, Integer::sum);
				}
				contents = contents(m);
				MenuOps.close(mc);
				markOpenedContainer(false);
				phase = Phase.DONE;
			}
			case DONE -> {
				if (mode == Mode.SLEEP) {
					done("asleep=true");
					return;
				}
				if (mode == Mode.CHEST_LIST) {
					done(contents);
					return;
				}
				boolean all = items.entrySet().stream().allMatch(e -> moved.getOrDefault(Inv.id(item(e.getKey())), 0) >= e.getValue());
				if (all) done(contents);
				else fail(ErrorCode.MISSING_MATERIALS, "moved only part of the items (not enough, chest full, or blocked by a rule); " + contents);
			}
		}
	}

	/** 箱子目前的內容(同種合併)和空格數，例如 {@code contents=oak_log:12,torch:5 free=20}；附魔等特殊物品另外列出。 */
	private static String contents(AbstractContainerMenu m) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		java.util.List<String> special = new java.util.ArrayList<>();
		int free = 0;
		for (int i : MenuOps.containerSlots(m)) {
			ItemStack s = m.getSlot(i).getItem();
			if (s.isEmpty()) {
				free++;
				continue;
			}
			counts.merge(Inv.id(s), s.getCount(), Integer::sum);
			// 附魔、藥水、書、自訂名稱這類同 id 也不一樣的物品，另外列出(只有耐久的不算)
			boolean notable = blockagent.game.Fmt.props(s).stream().anyMatch(prop -> !prop.startsWith("dur="));
			if (notable && special.size() < 8) special.add(blockagent.game.Fmt.item(s));
		}
		blockagent.mcp.Kv kv = blockagent.mcp.Kv.of().put("contents", blockagent.mcp.Kv.counts(counts)).put("free", free);
		if (!special.isEmpty()) kv.put("special", String.join(",", special));
		return kv.toString();
	}

	private int put(Minecraft mc, AbstractContainerMenu m, Item it, int want) {
		int moved = 0;
		for (int target : MenuOps.containerSlots(m)) {
			if (moved >= want) break;
			ItemStack t = m.getSlot(target).getItem();
			if (!t.isEmpty() && (t.getItem() != it || t.getCount() >= t.getMaxStackSize())) continue;
			int room = t.isEmpty() ? it.getDefaultMaxStackSize() : t.getMaxStackSize() - t.getCount();
			moved += MenuOps.placeInto(mc, m, target, s -> s.getItem() == it, Math.min(room, want - moved));
		}
		return moved;
	}

	private int take(Minecraft mc, AbstractContainerMenu m, Item it, int want) {
		int before = MenuOps.countInPlayer(m, s -> s.getItem() == it);
		for (int src : MenuOps.containerSlots(m)) {
			int now = MenuOps.countInPlayer(m, s -> s.getItem() == it) - before;
			if (now >= want) break;
			ItemStack s = m.getSlot(src).getItem();
			if (s.isEmpty() || s.getItem() != it) continue;
			if (s.getCount() <= want - now) MenuOps.quickMove(mc, m, src);
			else {
				// 只拿需要的數量：拿到游標，一個一個放進背包。先補同一種還沒滿的那疊，沒有才用空格(M14)
				Inv.click(mc, m, src, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
				int left = want - now;
				for (int i : MenuOps.playerSlots(m)) {
					if (left <= 0 || m.getCarried().isEmpty()) break;
					ItemStack d = m.getSlot(i).getItem();
					if (d.isEmpty() || !ItemStack.isSameItemSameComponents(d, m.getCarried())) continue;
					int k = Math.min(left, d.getMaxStackSize() - d.getCount());
					for (int j = 0; j < k; j++) Inv.click(mc, m, i, 1, net.minecraft.world.inventory.ContainerInput.PICKUP);
					left -= k;
				}
				if (left > 0 && !m.getCarried().isEmpty()) {
					for (int i : MenuOps.playerSlots(m)) {
						if (m.getSlot(i).hasItem()) continue;
						for (int k = 0; k < left; k++) Inv.click(mc, m, i, 1, net.minecraft.world.inventory.ContainerInput.PICKUP);
						break;
					}
				}
				Inv.click(mc, m, src, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
			}
		}
		return MenuOps.countInPlayer(m, s -> s.getItem() == it) - before;
	}

	@Override
	protected void onStop(Minecraft mc) {
		Bari.stopAll();
		if (openedContainer) MenuOps.close(mc);
	}

	/** 反射接手時箱子關掉了、人可能也被帶走了：重新走過去打開(H7)。 */
	@Override
	protected void onResume(Minecraft mc) {
		markOpenedContainer(false);
		openedAt = -1;
		waitTicks = 0;
		// 清掉暫停前殘留的睡覺失敗訊息(例如「附近有怪物」，打完怪恢復後不能拿舊訊息立刻失敗，P3-M13)
		failMessage = null;
		boolean pathed = false;
		if (phase == Phase.USE || phase == Phase.WORK) {
			if (mode == Mode.SLEEP || Moves.canReach(mc.player, pos)) phase = Phase.USE;
			else {
				phase = Phase.WALK;
				path(new GoalGetToBlock(pos));
				pathed = true;
			}
		}
		if (mode == Mode.SLEEP && phase == Phase.USE && mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)) > 1.8) {
			phase = Phase.WALK;
			BlockPos spot = standSpotNear(mc, pos);
			path(spot != null ? new baritone.api.pathing.goals.GoalBlock(spot) : new GoalGetToBlock(pos));
		} else if (phase == Phase.WALK && !pathed) {
			super.onResume(mc);
		}
	}
}
