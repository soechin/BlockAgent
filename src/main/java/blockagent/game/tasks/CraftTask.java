package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalGetToBlock;
import blockagent.core.CombatPolicy;
import blockagent.core.Recipes;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成(M2.8 的 craft)：依事前檢查的計畫一步一步做。
 * 需要工作台時，附近有就走過去用(別人的也可以)，沒有就用背包材料做一個放下。
 */
public final class CraftTask extends Task {
	private enum Phase {NEXT_STEP, PLACE_TABLE, WALK, OPEN, FILL, TAKE, CLEAR, DONE}

	private final Recipes.Plan plan;
	private final String target;
	private final int count;
	private final java.util.Set<String> pullOffhandKeep;
	private BlockPos tablePos;
	private Phase phase = Phase.NEXT_STEP;
	private int stepIdx = -1;
	private int craftsLeft;
	private int batch;
	private List<int[]> cells; // {gridIndex, ingredientIndex}
	private int cellIdx;
	private int waitTicks;
	private int startTargetCount = -1;
	private boolean ownTableUsed;
	/** 剛拿出產物：拿的是什麼、拿之前背包裡有幾個(null 表示這一批還沒拿)。 */
	private Item takeItem;
	private int takeBefore;
	private int takeState;
	/** beginBatch 計算 cells 槽位編號時綁定的畫面；若中途畫面變了立刻中止，不靠 needsTable 猜(避免多步驟 2×2 在工作台裡點到盔甲欄)。 */
	private AbstractContainerMenu batchMenu;

	public CraftTask(Agent agent, Recipes.Plan plan, String target, int count, BlockPos tableNearby) {
		this(agent, plan, target, count, tableNearby, null);
	}

	/** {@code pullOffhandKeep} 非 null 表示合成會用到副手材料，開工時先換回主背包並避開這些保留物品 ID(R12)。 */
	public CraftTask(Agent agent, Recipes.Plan plan, String target, int count, BlockPos tableNearby, java.util.Set<String> pullOffhandKeep) {
		super(agent, "craft");
		this.plan = plan;
		this.target = target;
		this.count = count;
		this.tablePos = tableNearby;
		this.pullOffhandKeep = pullOffhandKeep == null ? null : java.util.Set.copyOf(pullOffhandKeep);
	}

	static Item item(String id) {
		return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
	}

	public static BlockPos findTable(Agent agent, Minecraft mc) {
		BlockPos reachable = Moves.scan(mc, List.of(Blocks.CRAFTING_TABLE), 6, 1, p -> tableInReach(mc, p)).stream().findFirst().orElse(null);
		if (reachable != null) return reachable;
		if (avoidWalkingForTable(agent, mc)) return null;
		BlockPos owned = Moves.scan(mc, List.of(Blocks.CRAFTING_TABLE), 16, 1, agent.guard::isOwned).stream().findFirst().orElse(null);
		if (owned != null) return owned;
		return Moves.scan(mc, List.of(Blocks.CRAFTING_TABLE), 16, 1, null).stream().findFirst().orElse(null);
	}

	private static boolean avoidWalkingForTable(Agent agent, Minecraft mc) {
		return !mc.player.getAbilities().invulnerable && agent.observation.danger(mc).level() == CombatPolicy.DangerLevel.HIGH;
	}

	private static boolean localPlacementVisible(Minecraft mc, BlockPos pos) {
		var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(pos.below()),
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getBlockPos().equals(pos.below()) && hit.getDirection() == Direction.UP;
	}

	private static boolean tableInReach(Minecraft mc, BlockPos table) {
		if (!Moves.canReach(mc.player, table)) return false;
		var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(table),
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getBlockPos().equals(table) && hit.getType() == HitResult.Type.BLOCK;
	}

	@Override
	public String describe() {
		return "合成 " + name(item(target)) + "×" + count;
	}

	static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	@Override
	public String progress() {
		Minecraft mc = Minecraft.getInstance();
		int have = mc.player == null || startTargetCount < 0 ? 0 : Inv.count(mc.player, item(target)) - startTargetCount;
		return "item=" + shortId(target) + " made=" + Math.max(0, have) + "/" + count + " step=" + Math.min(plan.steps().size(), Math.max(0, stepIdx + 1)) + "/" + plan.steps().size();
	}

	@Override
	protected void start(Minecraft mc) {
		if (pullOffhandKeep != null && !mc.player.getOffhandItem().isEmpty()) {
			if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
			if (!Inv.pullOffhandToMain(mc, s -> pullOffhandKeep.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()))) {
				fail(ErrorCode.MISSING_MATERIALS, "a needed material is in the offhand and the main inventory is full",
					"drop or store one item, then craft again");
				return;
			}
		}
		startTargetCount = Inv.count(mc.player, item(target));
	}

	private Recipes.Step step() {
		return plan.steps().get(stepIdx);
	}

	private boolean onTable() {
		return mc().player.containerMenu instanceof CraftingMenu;
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	@Override
	public boolean moves() {
		return phase == Phase.WALK;
	}

	@Override
	protected void tick(Minecraft mc) {
		switch (phase) {
			case NEXT_STEP -> {
				stepIdx++;
				if (stepIdx >= plan.steps().size()) {
					phase = Phase.DONE;
					return;
				}
				Recipes.Step s = step();
				craftsLeft = s.crafts();
				if (s.recipe().needsTable() && !onTable()) {
					if (tablePos == null || !mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)
						|| !tableInReach(mc, tablePos) && avoidWalkingForTable(agent, mc)) {
						phase = Phase.PLACE_TABLE;
					} else {
						phase = tableInReach(mc, tablePos) ? Phase.OPEN : Phase.WALK;
						if (phase == Phase.WALK) path(new GoalGetToBlock(tablePos));
					}
				} else {
					beginBatch(mc);
				}
				waitTicks = 0;
			}
			case PLACE_TABLE -> {
				MenuOps.close(mc);
				if (waitTicks++ == 0) {
					boolean sheltered = avoidWalkingForTable(agent, mc) && !mc.level.canSeeSky(mc.player.blockPosition().above());
					BlockPos spot = Moves.findPlaceSpot(mc, p -> agent.guard.canPlaceInto(mc.level, p).allowed()
						&& (!sheltered || !mc.level.canSeeSky(p.above()) && localPlacementVisible(mc, p)));
					if (spot == null || !Inv.selectInHand(mc, s -> s.is(Items.CRAFTING_TABLE))) {
						fail(ErrorCode.NO_STATION, spot == null ? "no free spot to place a crafting table" : "no crafting table in inventory");
						return;
					}
					Moves.useOn(mc, spot.below(), Direction.UP);
					tablePos = spot;
					ownTableUsed = true;
				} else if (mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
					phase = Phase.OPEN;
					waitTicks = 0;
				} else if (waitTicks > 20) {
					fail(ErrorCode.NO_STATION, "could not place the crafting table");
				}
			}
			case WALK -> {
				boolean inReach = tableInReach(mc, tablePos);
				if (!inReach && avoidWalkingForTable(agent, mc)) {
					Bari.stopAll();
					phase = Phase.PLACE_TABLE;
					waitTicks = 0;
				} else if (inReach) {
					Bari.stopAll();
					phase = Phase.OPEN;
					waitTicks = 0;
				} else if (!Bari.busy() && waitTicks++ > 40) {
					fail(ErrorCode.PATH_BLOCKED, "cannot reach the crafting table at " + blockagent.game.Fmt.pos(tablePos));
				}
			}
			case OPEN -> {
				if (onTable()) {
					markOpenedContainer(true);
					beginBatch(mc);
				} else if (waitTicks++ % 20 == 0) {
					if (waitTicks > 60) {
						failToOpen("could not open the crafting table");
						return;
					}
					Moves.useOn(mc, tablePos, Moves.faceToward(mc.player, tablePos));
				}
			}
			case FILL -> fillOneCell(mc);
			case TAKE -> {
				if (!checkTableMenu(mc)) return;
				AbstractContainerMenu m = mc.player.containerMenu;
				var res = m.getSlot(0).getItem();
				String want = step().recipe().result();
				if (res.isEmpty() || !BuiltInRegistries.ITEM.getKey(res.getItem()).toString().equals(want)) {
					if (waitTicks++ < 5) return; // 等伺服器更新結果格
					fail(ErrorCode.MISSING_MATERIALS, "grid did not produce " + shortId(want) + " (result slot: "
						+ (res.isEmpty() ? "empty" : Inv.id(res) + ":" + res.getCount()) + "); the server's recipes may differ");
					return;
				}
				takeItem = res.getItem();
				takeBefore = Inv.count(mc.player, takeItem);
				takeState = m.getStateId();
				MenuOps.quickMove(mc, m, 0);
				phase = Phase.CLEAR;
				waitTicks = 0;
			}
			case CLEAR -> {
				if (!checkTableMenu(mc)) return;
				// 一次做幾個是伺服器算的(客戶端只預測得到 1 個)：等伺服器送回更新(stateId 變了)再數，最多等 2 秒
				if (takeItem != null && waitTicks++ < 40 && (waitTicks < 3 || mc.player.containerMenu.getStateId() == takeState)) return;
				if (!settleTake(mc)) return;
				AbstractContainerMenu m = mc.player.containerMenu;
				for (int g : gridSlots(m)) if (m.getSlot(g).hasItem()) MenuOps.quickMove(mc, m, g);
				if (craftsLeft > 0) beginBatch(mc);
				else phase = Phase.NEXT_STEP;
			}
			case DONE -> {
				MenuOps.close(mc);
				markOpenedContainer(false);
				int made = Inv.count(mc.player, item(target)) - startTargetCount;
				done(ownTableUsed ? "placed_table=" + blockagent.game.Fmt.pos(tablePos) : "");
			}
		}
	}

	private static List<Integer> gridSlots(AbstractContainerMenu m) {
		int n = m instanceof CraftingMenu ? 9 : 4;
		List<Integer> out = new ArrayList<>();
		for (int i = 1; i <= n; i++) out.add(i);
		return out;
	}

	/**
	 * 照背包實際多出來的數量算這一批做了幾次(C7)：背包滿了的話只拿得到一部分，不能當成整批都做好了。
	 * 回傳 false 表示任務已經失敗。
	 */
	private boolean settleTake(Minecraft mc) {
		if (takeItem == null) return true;
		int crafted = Math.max(0, Inv.count(mc.player, takeItem) - takeBefore) / Math.max(1, step().recipe().resultCount());
		takeItem = null;
		craftsLeft -= crafted;
		if (crafted < batch) {
			fail(ErrorCode.INTERRUPTED, "inventory is full: crafted " + crafted + " of " + batch + " " + shortId(step().recipe().result())
				+ " in this batch", "drop or store some items, then craft again");
			return false;
		}
		return true;
	}

	private void beginBatch(Minecraft mc) {
		Recipes.Recipe r = step().recipe();
		AbstractContainerMenu m = mc.player.containerMenu;
		if (!(m instanceof CraftingMenu) && m != mc.player.inventoryMenu) {
			// 還開著別的容器(例如熔爐)：在那個畫面點「合成格」其實是點熔爐的燃料格和背包格，
			// 會誤報缺料。先關掉，改用背包的 2×2 合成格。
			MenuOps.close(mc);
			markOpenedContainer(false);
			m = mc.player.inventoryMenu;
		}
		int gridW = m instanceof CraftingMenu ? 3 : 2;
		batchMenu = m;
		// 每格最多放到材料的堆疊上限(弓 1、雪球 16…)，一批就只能做這麼多次(C7)
		batch = Math.min(craftsLeft, 64);
		for (Recipes.Ingredient ing : r.grid()) {
			if (ing == null) continue;
			int idx = Inv.find(mc.player, st -> ing.options().contains(BuiltInRegistries.ITEM.getKey(st.getItem()).toString()));
			if (idx >= 0) batch = Math.min(batch, mc.player.getInventory().getItem(idx).getMaxStackSize());
		}
		cells = new ArrayList<>();
		if (r.kind() == Recipes.Kind.SHAPED) {
			for (int y = 0; y < r.height(); y++) {
				for (int x = 0; x < r.width(); x++) {
					int idx = y * r.width() + x;
					if (r.grid().get(idx) != null) cells.add(new int[]{1 + y * gridW + x, idx});
				}
			}
		} else {
			for (int i = 0; i < r.grid().size(); i++) cells.add(new int[]{1 + i, i});
		}
		cellIdx = 0;
		phase = Phase.FILL;
		waitTicks = 0;
	}

	/** 這一批綁定的容器畫面若在中途被關掉或換掉(不管是不是 needsTable 的 2×2 配方)，立刻中止，絕不把槽位點進玩家盔甲欄(P3-H7)。 */
	private boolean checkTableMenu(Minecraft mc) {
		if (batchMenu != null && mc.player.containerMenu != batchMenu) {
			fail(ErrorCode.INTERRUPTED, "crafting screen was closed");
			return false;
		}
		if (step().recipe().needsTable() && !(mc.player.containerMenu instanceof CraftingMenu)) {
			fail(ErrorCode.INTERRUPTED, "crafting table screen was closed");
			return false;
		}
		return true;
	}

	private void fillOneCell(Minecraft mc) {
		if (!checkTableMenu(mc)) return;
		if (cellIdx >= cells.size()) {
			phase = Phase.TAKE;
			waitTicks = 0;
			return;
		}
		AbstractContainerMenu m = mc.player.containerMenu;
		int[] c = cells.get(cellIdx);
		Recipes.Ingredient ing = step().recipe().grid().get(c[1]);
		int placed = MenuOps.placeInto(mc, m, c[0], s -> ing.options().contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()), batch);
		if (placed < batch) {
			for (int g : gridSlots(m)) if (m.getSlot(g).hasItem()) MenuOps.quickMove(mc, m, g);
			fail(ErrorCode.MISSING_MATERIALS, "not enough " + ing.label().replace("minecraft:", "") + ": short by " + (batch - placed));
			return;
		}
		cellIdx++;
	}

	@Override
	protected void onSuspend(Minecraft mc) {
		// 若在 CLEAR 等待伺服器同步時被 EAT/SELF_DEFENSE 暫停，且伺服器已把產物送進背包，
		// 先在反射消耗麵包/箭之前把這一批結算掉，避免恢復時誤判背包滿(P3-H7)
		if (phase == Phase.CLEAR && takeItem != null && mc.player != null) {
			int expected = batch * Math.max(1, step().recipe().resultCount());
			if (Inv.count(mc.player, takeItem) - takeBefore >= expected) {
				craftsLeft -= batch;
				takeItem = null;
			}
		}
		super.onSuspend(mc);
	}

	/**
	 * 反射接手時合成格清空、工作台關掉了(onSuspend = onStop)：這一步還沒做完就重新打開工作台(要的話)再從這一批開始(H7)。
	 * 不然 3×3 的配方會點到背包畫面的盔甲欄。
	 */
	@Override
	protected void onResume(Minecraft mc) {
		markOpenedContainer(false);
		batchMenu = null;
		waitTicks = 0;
		switch (phase) {
			case OPEN, FILL, TAKE, CLEAR -> {
				// 拿出產物後才被暫停：先把這一批算完(暫停期間伺服器已經同步了)
				if (phase == Phase.CLEAR && !settleTake(mc)) return;
				if (craftsLeft <= 0) {
					phase = Phase.NEXT_STEP;
				} else if (step().recipe().needsTable()) {
					if (tablePos == null || !mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) phase = Phase.PLACE_TABLE;
					else if (tableInReach(mc, tablePos)) phase = Phase.OPEN;
					else {
						phase = Phase.WALK;
						path(new GoalGetToBlock(tablePos));
					}
				} else {
					beginBatch(mc);
				}
			}
			// 已經放好的工作台不要再放一次；若被反射拉遠，先走回工作台旁再開(P3-H7)
			case PLACE_TABLE -> {
				if (tablePos != null && mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
					if (tableInReach(mc, tablePos)) phase = Phase.OPEN;
					else {
						phase = Phase.WALK;
						path(new GoalGetToBlock(tablePos));
					}
				} else {
					waitTicks = 0;
				}
			}
			case WALK -> super.onResume(mc);
			default -> {
			}
		}
	}

	@Override
	protected void onStop(Minecraft mc) {
		batchMenu = null;
		Bari.stopAll();
		if (mc.player != null && mc.player.containerMenu != null) {
			AbstractContainerMenu m = mc.player.containerMenu;
			if (!m.getCarried().isEmpty()) {
				int free = -1;
				for (int i : MenuOps.playerSlots(m)) if (!m.getSlot(i).hasItem()) {
					free = i;
					break;
				}
				if (free >= 0) Inv.click(mc, m, free, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
			}
			boolean craftingGrid = m instanceof CraftingMenu || m == mc.player.inventoryMenu;
			if (status != Status.DONE && craftingGrid) for (int g : gridSlots(m)) if (m.getSlot(g).hasItem()) MenuOps.quickMove(mc, m, g);
		}
		if (openedContainer) MenuOps.close(mc);
	}
}
