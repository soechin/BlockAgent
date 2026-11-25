package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.schematic.ISchematic;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.BuildTarget;
import blockagent.game.Creative;
import blockagent.game.Inv;
import blockagent.game.RuleGuard;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 蓋牆或藍圖(Baritone BuilderProcess + 自訂 schematic)。Baritone 只填空氣和可替換方塊，不拆東西(buildIgnoreExisting)。
 * 開始蓋之前，先由上往下清掉擋在位置上的天然花草(S4.4b)，藍圖還會挖掉天然方塊、拆掉自己蓋的舊設計(S4.8c)，
 * 生存模式挖完撿起掉落物；蓋完最後裝門(S4.8a)。
 * 創造模式會在快捷列準備鷹架方塊，讓 Baritone 能往上墊高；蓋完(或失敗)再把自己墊的鷹架拆掉(S4.5a)。
 */
public final class BuildTask extends Task {
	private final String what;
	private final BlockPos origin;
	private final int sx, sy, sz;
	private final Map<BlockPos, BuildTarget> targets; // 絕對座標；挖不掉的格子會移除(僅在 client thread 讀寫)
	private final Set<BlockPos> protectedCells;
	/** Baritone 尋路執行緒會讀：藍圖裡要保持空著(不能墊鷹架)的格子(P3-H3，不碰 LinkedHashMap targets)。 */
	private final Set<BlockPos> forbiddenScaffolds = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final Set<BlockPos> reserved;
	/** Baritone 的尋路執行緒也會讀(M14)。 */
	private volatile Set<BlockPos> occupiedDoorways = Set.of();
	private BlockPos diggingScaffold;
	private ClientLevel level;
	/** 開始蓋之前要清掉的格子(由上往下)，值是開工時看到的方塊。 */
	private final Deque<Map.Entry<BlockPos, BlockState>> clearQueue;
	private final Set<BlockPos> clearPending;
	private final Set<BlockPos> demolish;
	private final DoorPlacer doors;
	private final List<String> notCleared = new ArrayList<>();
	private BlockPos digging;
	private int digTicks;
	private int dug;
	private int demolished;
	private int collectTicks = -1;
	private Runnable onComplete;
	private String verb = "built";
	private int idleTicks;
	private int clearWait;
	private boolean building;
	private int lastPlaced = -1;

	/** 這次任務中 Baritone 墊腳放下、不在目標裡的方塊(S4.5a)。 */
	private final Set<BlockPos> scaffold = new LinkedHashSet<>();
	private boolean trackScaffold;
	private boolean cleaning;
	private long cleanStart;
	private int cleanWait;
	private int scaffoldRemoved;
	private final List<String> scaffoldLeft = new ArrayList<>();
	private BlockPos cleanTarget;
	private int cleanIdle;
	/** 蓋完或失敗之後、拆完鷹架才送出的結果(errorCode 為 null 表示完成)。 */
	private ErrorCode endCode;
	private String endText;

	/** 30 秒都沒放下新的方塊(或清掉花草)就當成卡住，回報剩下哪幾格(S4.5)。 */
	static final int STALL_TICKS = 30 * 20;
	/** 拆鷹架最多花 30 秒，拆不到的就列出來交給 AI。 */
	static final int CLEAN_TICKS = 30 * 20;
	static final int SCAFFOLD_COUNT = 64;
	/** 一格最多挖 30 秒(黑曜石這類空手挖不動的方塊就放棄)。 */
	static final int DIG_TICKS = 30 * 20;
	/** 生存模式挖完之後，最多花 15 秒撿掉落物。 */
	static final int COLLECT_TICKS = 15 * 20;

	/** 擋在蓋建位置上、可以先清掉的方塊：天然、沒有流體，一碰就碎(花、草、蘑菇…)或可替換的薄層(如一層雪)，而且規矩允許挖。 */
	public static boolean clearablePlant(Agent agent, Level level, BlockPos p, BlockState st) {
		float speed = st.getDestroySpeed(level, p);
		return !st.isAir() && st.getFluidState().isEmpty() && speed >= 0f
			&& (speed == 0f || st.canBeReplaced())
			&& agent.guard.isNatural(st) && agent.guard.canBreak(level, p).allowed();
	}

	/** Baritone 可以拿來墊腳的物品(acceptableThrowawayItems)。 */
	static List<Item> throwawayItems() {
		return baritone.api.BaritoneAPI.getSettings().acceptableThrowawayItems.value;
	}

	/** 快捷列或副手有沒有 Baritone 能墊腳的方塊(Baritone 只從這裡拿，不翻背包)。 */
	static Item throwawayInHotbar(LocalPlayer p) {
		List<Item> ok = throwawayItems();
		for (int i = 0; i < 9; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && ok.contains(s.getItem())) return s.getItem();
		}
		ItemStack off = p.getOffhandItem();
		return !off.isEmpty() && ok.contains(off.getItem()) ? off.getItem() : null;
	}

	static Item scaffoldItem() {
		List<Item> ok = throwawayItems();
		return ok.contains(Items.DIRT) || ok.isEmpty() ? Items.DIRT : ok.getFirst();
	}

	/** 事前檢查用的說明(只讀狀態)：創造模式要怎麼準備鷹架；不是創造模式回傳 null。 */
	public static String scaffoldPlan(Minecraft mc) {
		if (!Creative.isCreative(mc)) return null;
		Item have = throwawayInHotbar(mc.player);
		if (have != null) return "scaffold=" + Inv.id(have) + " note=\"scaffold from hotbar, removed afterwards\"";
		for (int i = 0; i < 9; i++) {
			if (mc.player.getInventory().getItem(i).isEmpty()) {
				return "scaffold=" + Inv.id(scaffoldItem()) + ":" + SCAFFOLD_COUNT + " note=\"taken from creative inventory, removed afterwards\"";
			}
		}
		return "scaffold=none note=\"hotbar full; high cells may be unreachable\"";
	}

	/**
	 * 懸空的格子(S4.4c)：跟現有的方塊連不起來，Baritone 找不到可以貼著放的面。
	 * 從「旁邊有實心方塊」的格子開始，沿著要填的格子往外擴散，擴散不到的就是懸空。
	 */
	public static Set<BlockPos> floating(Level level, Set<BlockPos> fill) {
		return floating(level, fill, Set.of());
	}

	/** {@code clearing} 是開工前會挖空的格子，不能拿來當支撐。 */
	public static Set<BlockPos> floating(Level level, Set<BlockPos> fill, Set<BlockPos> clearing) {
		Deque<BlockPos> q = new ArrayDeque<>();
		Set<BlockPos> ok = new HashSet<>();
		for (BlockPos p : fill) {
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (fill.contains(n) || clearing.contains(n)) continue;
				BlockState st = level.getBlockState(n);
				if (!st.isAir() && !st.canBeReplaced()) {
					ok.add(p);
					q.add(p);
					break;
				}
			}
		}
		while (!q.isEmpty()) {
			BlockPos p = q.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (fill.contains(n) && ok.add(n)) q.add(n);
			}
		}
		Set<BlockPos> out = new LinkedHashSet<>(fill);
		out.removeAll(ok);
		return out;
	}

	/** 牆：只清花草，不拆東西。 */
	public BuildTask(Agent agent, String name, String what, BlockPos min, BlockPos max, Set<BlockPos> targets, Block material,
					 Map<BlockPos, BlockState> plants, Set<BlockPos> reserved) {
		this(agent, name, what, min, max, BuildTarget.uniform(targets, material), plants, Set.of(), Map.of(), Set.of(), reserved);
	}

	/**
	 * 藍圖(S4.8c)。{@code clear} 是要清掉的格子(花草、天然方塊和自己蓋的舊設計，由上往下)，{@code demolish} 是其中屬於舊設計的；
	 * {@code doors} 最後一扇一扇裝上；{@code protectedCells} 是設計裡的格子，Baritone 不能拆也不能拿來墊腳。
	 */
	public BuildTask(Agent agent, String name, String what, BlockPos min, BlockPos max, Map<BlockPos, BuildTarget> targets,
					 Map<BlockPos, BlockState> clear, Set<BlockPos> demolish, Map<BlockPos, BuildTarget> doors,
					 Set<BlockPos> protectedCells) {
		this(agent, name, what, min, max, targets, clear, protectedCells, doors, demolish, Set.of());
	}

	private BuildTask(Agent agent, String name, String what, BlockPos min, BlockPos max, Map<BlockPos, BuildTarget> targets,
					  Map<BlockPos, BlockState> clear, Set<BlockPos> protectedCells, Map<BlockPos, BuildTarget> doors,
					  Set<BlockPos> demolish, Set<BlockPos> reserved) {
		super(agent, name);
		this.what = what;
		this.origin = min;
		this.sx = max.getX() - min.getX() + 1;
		this.sy = max.getY() - min.getY() + 1;
		this.sz = max.getZ() - min.getZ() + 1;
		this.targets = new LinkedHashMap<>(targets);
		this.protectedCells = Set.copyOf(protectedCells);
		for (BlockPos p : this.protectedCells) {
			BuildTarget t = this.targets.get(p);
			if (t == null || t.state().isAir()) this.forbiddenScaffolds.add(p);
		}
		this.reserved = Set.copyOf(reserved);
		this.clearQueue = new ArrayDeque<>(clear.entrySet());
		this.clearPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
		this.clearPending.addAll(clear.keySet());
		this.demolish = Set.copyOf(demolish);
		this.doors = new DoorPlacer(agent, doors);
	}

	/** 完成時的說法(例如「拆好了」)，以及全部做完、沒有略過任何格子時要做的事(例如刪掉藍圖紀錄)。 */
	public BuildTask onComplete(String verb, Runnable action) {
		this.verb = verb;
		this.onComplete = action;
		return this;
	}

	private int placed(Minecraft mc) {
		if (mc.level != level || level == null) return 0;
		int n = 0;
		for (var entry : targets.entrySet()) if (entry.getValue().matches(level.getBlockState(entry.getKey()))) n++;
		return n;
	}

	/** 依最低尚未完成的一層準備快捷列，保留一格給鷹架。使用玩家背包的交換操作。 */
	private void stageMaterials(Minecraft mc) {
		if (protectedCells.isEmpty() || mc.player.containerMenu != mc.player.inventoryMenu) return;
		var inv = mc.player.getInventory();
		Set<Item> wanted = new LinkedHashSet<>();
		Set<Item> unavailable = new HashSet<>();
		int layer = Integer.MAX_VALUE;
		for (var entry : targets.entrySet()) {
			BlockPos pos = entry.getKey();
			if (pos.getY() > layer) break;
			// 草、雪層這類可替換的格子也還沒蓋(M14：原本只看空氣)
			BlockState cur = level.getBlockState(pos);
			if (entry.getValue().matches(cur) || !(cur.isAir() || cur.canBeReplaced())) continue;
			Item item = entry.getValue().state().getBlock().asItem();
			if (wanted.contains(item) || unavailable.contains(item)) continue;
			if (Inv.find(mc.player, stack -> stack.is(item)) < 0) {
				unavailable.add(item);
				continue;
			}
			layer = pos.getY();
			wanted.add(item);
			if (wanted.size() == 8) break;
		}
		for (Item item : wanted) {
			int source = Inv.find(mc.player, stack -> stack.is(item));
			if (source < 9) continue;
			Item scaffoldItem = throwawayInHotbar(mc.player);
			boolean[] occupied = new boolean[9];
			boolean[] damageable = new boolean[9];
			boolean[] protectedSlot = new boolean[9];
			for (int slot = 0; slot < 9; slot++) {
				ItemStack cur = inv.getItem(slot);
				occupied[slot] = !cur.isEmpty();
				damageable[slot] = occupied[slot] && cur.isDamageableItem();
				Item old = cur.getItem();
				protectedSlot[slot] = wanted.contains(old) || old == scaffoldItem;
			}
			int slot = Inv.chooseHotbarSwapSlot(occupied, damageable, protectedSlot, -1);
			if (slot >= 0) {
				Inv.click(mc, mc.player.inventoryMenu, Inv.menuSlot(source), slot, ContainerInput.SWAP);
			}
		}
	}

	public boolean protects(int x, int y, int z) {
		if (protectedCells.isEmpty() && occupiedDoorways.isEmpty()) return false;
		var pos = new BlockPos(x, y, z);
		// 還沒清掉的格子就是要挖的，不保護(Baritone 規劃執行緒也會讀，所以 clearPending 是執行緒安全的集合)
		if (clearPending.contains(pos)) return false;
		return protectedCells.contains(pos) || occupiedDoorways.contains(pos);
	}

	public boolean reserves(int x, int y, int z) {
		return !occupiedDoorways.isEmpty() && occupiedDoorways.contains(new BlockPos(x, y, z));
	}

	/**
	 * Baritone 不能拿來墊腳的格子：門口，以及藍圖設計裡要空著的格子(M14)。設計裡要放方塊的格子交給 Baritone 的
	 * 建造流程自己判斷(放對的方塊就是在蓋)，放錯的東西守門員會擋下。
	 * Baritone 尋路執行緒會呼叫此方法，因此只讀執行緒安全的 {@link #occupiedDoorways} 與 {@link #forbiddenScaffolds}(P3-H3)。
	 */
	public boolean forbidsScaffold(int x, int y, int z) {
		if (reserves(x, y, z)) return true;
		return !forbiddenScaffolds.isEmpty() && forbiddenScaffolds.contains(new BlockPos(x, y, z));
	}

	public boolean isBlueprint() {
		return !protectedCells.isEmpty();
	}

	public boolean permitsPlacement(Level level, BlockPos pos, Item item) {
		if (occupiedDoorways.contains(pos)) return false;
		BlockState cur = level.getBlockState(pos);
		boolean open = cur.isAir() || cur.canBeReplaced();
		if (doors.covers(pos)) return doors.allows(pos, item) && open;
		if (!protectedCells.contains(pos)) return true;
		BuildTarget target = targets.get(pos);
		return target != null && target.state().getBlock().asItem() == item && open;
	}

	@Override
	public String describe() {
		return (targets.isEmpty() && doors.isEmpty() ? "拆" : "蓋") + what;
	}

	@Override
	public Progress hudProgress() {
		if (targets.isEmpty() && doors.isEmpty()) return new Progress(dug + demolished, dug + demolished + clearQueue.size());
		Minecraft mc = Minecraft.getInstance();
		return new Progress(mc.level == null ? 0 : placed(mc), targets.size());
	}

	@Override
	public String progress() {
		Minecraft mc = Minecraft.getInstance();
		String s = "blocks=" + (mc.level == null ? 0 : placed(mc)) + "/" + targets.size();
		if (dug + demolished > 0 || !clearQueue.isEmpty()) {
			s += " cleared=" + (dug + demolished) + (clearQueue.isEmpty() ? "" : " to_clear=" + clearQueue.size());
		}
		if (doors.placed() > 0) s += " doors=" + doors.placed();
		if (cleaning) s += " state=removing_scaffold";
		else if (!scaffold.isEmpty()) s += " scaffold_left=" + scaffold.size();
		return s;
	}

	@Override
	public boolean moves() {
		return true;
	}

	private ISchematic schematic() {
		return new ISchematic() {
			@Override
			public boolean inSchematic(int x, int y, int z, BlockState current) {
				BuildTarget target = targets.get(origin.offset(x, y, z));
				return target != null && (protectedCells.isEmpty() || current == null || current.isAir() || current.canBeReplaced() || target.matches(current));
			}

			@Override
			public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
				BuildTarget target = targets.get(origin.offset(x, y, z));
				return target == null || (!protectedCells.isEmpty() && target.matches(current)) ? current : target.state();
			}

			@Override
			public int widthX() {
				return sx;
			}

			@Override
			public int heightY() {
				return sy;
			}

			@Override
			public int lengthZ() {
				return sz;
			}
		};
	}

	private void startBuilder() {
		building = true;
		// 一層一層由下往上蓋：不然屋頂可能先封起來，角落的柱子就放不進去了
		var s = baritone.api.BaritoneAPI.getSettings();
		s.buildInLayers.value = true;
		s.layerOrder.value = false;
		Bari.b().getBuilderProcess().build("blockagent-" + name, schematic(), origin);
	}

	@Override
	protected void start(Minecraft mc) {
		level = mc.level;
		// 在真正啟動(舊任務已 onStop 還原完畢)後才記下 layerOrder，避免 replace=true 連續建造漏還原(P3-H9)
		savedLayerOrder = baritone.api.BaritoneAPI.getSettings().layerOrder.value;
		occupiedDoorways = reserved.stream().filter(pos -> !level.getBlockState(pos).isAir())
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (Creative.isCreative(mc)) {
			if (throwawayInHotbar(mc.player) == null) Creative.takeIntoHotbar(mc, scaffoldItem(), SCAFFOLD_COUNT);
			trackScaffold = throwawayInHotbar(mc.player) != null;
		}
		stageMaterials(mc);
	}

	@Override
	public void onSelfPlaced(BlockPos pos, BlockState state) {
		if (!cleaning && !targets.containsKey(pos) && !doors.covers(pos)
			&& (trackScaffold || (reserved.contains(pos) && !occupiedDoorways.contains(pos)))) {
			scaffold.add(pos);
		}
	}

	/**
	 * 先清掉擋在位置上的方塊，由上往下：花草一下就打掉，天然方塊和自己蓋的舊設計用最快的工具挖。
	 * 挖之前再確認一次還是開工時看到的那種方塊(被別人換掉就不動)。
	 */
	private void clearTick(Minecraft mc) {
		var entry = clearQueue.peekFirst();
		BlockPos p = entry.getKey();
		BlockState st = mc.level.getBlockState(p);
		if (st.isAir() || st.canBeReplaced() && !clearablePlant(agent, mc.level, p, st)) { // 挖掉了(或自己消失)
			if (st.isAir() && digging != null) {
				if (demolish.contains(p)) demolished++;
				else dug++;
			}
			nextClear(mc, null);
			markProgress();
			return;
		}
		if (st.getBlock() != entry.getValue().getBlock()) {
			nextClear(mc, "now_" + RuleGuard.shortId(st));
			return;
		}
		if (Moves.canReach(mc.player, p)) {
			if (Bari.busy()) Bari.stopAll();
			var face = Moves.faceToward(mc.player, p);
			Moves.lookAt(mc.player, Moves.hit(p, face).getLocation());
			boolean acted;
			if (p.equals(digging)) {
				acted = mc.gameMode.continueDestroyBlock(p, face);
			} else {
				BreakTask.equipBestTool(mc, st);
				acted = mc.gameMode.startDestroyBlock(p, face);
				if (acted) digging = p;
			}
			// continueDestroyBlock 回 false 表示重新開始挖也被擋下了：不要乾等 30 秒(M14)
			if (!acted) {
				nextClear(mc, "rule");
				return;
			}
			mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			if (++digTicks > DIG_TICKS) nextClear(mc, "too_hard");
			return;
		}
		stopDigging(mc);
		if (clearWait++ == 0) path(new GoalNear(p, 2));
		else if (!Bari.busy() && clearWait > 60) nextClear(mc, "unreachable");
	}

	private void nextClear(Minecraft mc, String failure) {
		var entry = clearQueue.pollFirst();
		if (entry == null) return;
		if (failure != null) {
			notCleared.add(blockagent.game.Fmt.pos(entry.getKey()) + ":" + failure);
			// 挖不掉的格子就不蓋了，免得 Baritone 一直等那一格；同時同步給尋路執行緒的禁墊腳集合
			targets.remove(entry.getKey());
			if (protectedCells.contains(entry.getKey())) forbiddenScaffolds.add(entry.getKey());
		}
		clearPending.remove(entry.getKey());
		stopDigging(mc);
		clearWait = 0;
		digTicks = 0;
	}

	private void stopDigging(Minecraft mc) {
		if (digging != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		digging = null;
	}

	/** 生存模式挖完撿起附近的掉落物，最多 COLLECT_TICKS。 */
	private boolean collectTick(Minecraft mc) {
		if (collectTicks < 0) {
			if (Creative.isCreative(mc) || dug + demolished == 0) return true;
			collectTicks = 0;
		}
		if (collectTicks++ > COLLECT_TICKS) return true;
		var area = new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(origin),
			net.minecraft.world.phys.Vec3.atLowerCornerOf(origin.offset(sx, sy, sz))).inflate(4);
		var items = mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area);
		if (items.isEmpty()) return true;
		if (collectTicks % 20 == 1) {
			var player = mc.player;
			var closest = items.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(player))).orElseThrow();
			path(new baritone.api.pathing.goals.GoalBlock(closest.blockPosition()));
		}
		return false;
	}

	private String remaining(Minecraft mc) {
		List<String> left = new ArrayList<>();
		for (var entry : targets.entrySet()) {
			BlockPos p = entry.getKey();
			BlockState st = mc.level.getBlockState(p);
			if (entry.getValue().matches(st)) continue;
			// 被別人(或別的東西)佔掉的格子直接寫出來，AI 才知道不是站的位置的問題
			left.add(blockagent.game.Fmt.pos(p) + (st.isAir() || st.canBeReplaced() ? "" : ":has_" + RuleGuard.shortId(st)));
		}
		return "left=" + left.size() + " at=" + String.join(";", left.subList(0, Math.min(6, left.size()))) + (left.size() > 6 ? ";..." : "");
	}

	/** 蓋完或失敗：有鷹架就先拆，拆完才真的結束。 */
	private void finish(Minecraft mc, ErrorCode code, String text) {
		endCode = code;
		endText = text;
		if (scaffold.isEmpty()) {
			end();
			return;
		}
		Bari.stopAll();
		cleaning = true;
		cleanStart = agent.tick();
		cleanWait = 0;
		cleanTarget = null;
		noPlace(true);
		markProgress();
	}

	private void end() {
		String note = "";
		if (scaffoldRemoved > 0) note += " scaffold_removed=" + scaffoldRemoved;
		if (!scaffoldLeft.isEmpty()) {
			note += " scaffold_left=" + String.join(";", scaffoldLeft.subList(0, Math.min(6, scaffoldLeft.size())))
				+ (scaffoldLeft.size() > 6 ? ";...+" + (scaffoldLeft.size() - 6) : "") + " hint=\"break_block them\"";
		}
		Minecraft mc = Minecraft.getInstance();
		boolean missing = endCode == null && mc.level != null && placed(mc) < targets.size();
		if (missing) {
			// 拆鷹架的過程中少了目標格(不應該發生，但發生了要老實說)
			note += " missing_now=true " + remaining(mc);
		}
		// 少了格子就不算完成，不執行完成後的動作(例如刪掉藍圖紀錄，M14)
		if (endCode == null && !missing && onComplete != null && notCleared.isEmpty() && doors.failed().isEmpty()) onComplete.run();
		if (endCode == null) done((endText + note).strip());
		else fail(endCode, endText + note);
	}

	private static Integer savedFall;
	/** 開始前的 layerOrder(於 start() 擷取，蓋完要還原，H10、P3-H9)。 */
	private boolean savedLayerOrder = true;

	/**
	 * 拆鷹架時不准 Baritone 放或挖任何方塊：只用走的，免得走過去的路上又墊新的、或挖穿剛蓋好的牆。
	 * 只用走的就下不了高處(Baritone 預設最多跳下 3 格)，而創造模式不會摔傷，所以這段時間放寬往下跳的高度。
	 */
	private static void noPlace(boolean on) {
		var s = baritone.api.BaritoneAPI.getSettings();
		s.allowPlace.value = !on;
		s.allowBreak.value = !on;
		if (on && savedFall == null && Creative.isCreative(Minecraft.getInstance())) {
			savedFall = s.maxFallHeightNoWater.value;
			s.maxFallHeightNoWater.value = 64;
		} else if (!on && savedFall != null) {
			s.maxFallHeightNoWater.value = savedFall;
			savedFall = null;
		}
	}

	/** 還是自己墊的鷹架嗎(別人拆掉或換掉了就不管)。 */
	private boolean isScaffold(Minecraft mc, BlockPos p) {
		BlockState st = mc.level.getBlockState(p);
		return !st.isAir() && agent.guard.isOwned(p) && agent.guard.canBreak(mc.level, p).allowed();
	}

	/** 由上往下拆：站在鷹架頂上時，先拆腳下那格、掉下去、再拆下一格。 */
	private void cleanTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		int before = scaffold.size();
		scaffold.removeIf(b -> !isScaffold(mc, b));
		int removed = before - scaffold.size();
		if (removed > 0) {
			scaffoldRemoved += removed;
			markProgress();
			// 剛拆掉一格：等一下讓自己掉下去站穩，再找下一格
			cleanWait = 2;
		}
		if (scaffold.isEmpty() || agent.tick() - cleanStart > CLEAN_TICKS) {
			stopScaffoldDig(mc);
			for (BlockPos b : scaffold) scaffoldLeft.add(blockagent.game.Fmt.pos(b));
			scaffold.clear();
			cleaning = false;
			noPlace(false);
			Bari.stopAll();
			end();
			return;
		}
		if (cleanWait > 0) {
			cleanWait--;
			return;
		}
		BlockPos top = scaffold.stream()
			.max(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(b -> -b.distToCenterSqr(p.position())))
			.orElseThrow();
		if (Moves.canReach(p, top)) {
			if (Bari.busy()) Bari.stopAll();
			Direction face = Moves.faceToward(p, top);
			Moves.lookAt(p, Moves.hit(top, face).getLocation());
			// 挖的時候每 tick 都要繼續挖(M14：原本每挖一下就等 2 tick，慢了三倍)
			if (top.equals(diggingScaffold)) {
				mc.gameMode.continueDestroyBlock(top, face);
			} else {
				stopScaffoldDig(mc);
				BlockState state = mc.level.getBlockState(top);
				BreakTask.equipBestTool(mc, state);
				agent.log.log(blockagent.game.ActionLog.Source.ai, "scaffold_break", top.toShortString() + " " + RuleGuard.shortId(state));
				if (mc.gameMode.startDestroyBlock(top, face)) diggingScaffold = top;
			}
			cleanTarget = null;
			return;
		}
		stopScaffoldDig(mc);
		if (!top.equals(cleanTarget)) {
			cleanTarget = top;
			cleanWait = 0;
			path(new GoalNear(top, 1));
			cleanIdle = 0;
			return;
		}
		// 走不過去：Baritone 停下來 3 秒還是搆不到，就放棄這格
		if (!Bari.busy() && ++cleanIdle > 60) {
			agent.log.log(blockagent.game.ActionLog.Source.ai, "scaffold_skip", top.toShortString() + " 搆不到；站在 "
				+ String.format("%.2f, %.2f, %.2f", p.getX(), p.getY(), p.getZ()) + (p.getAbilities().flying ? "(飛行中)" : "") + (p.onGround() ? "" : "(沒踩在地上)"));
			scaffoldLeft.add(blockagent.game.Fmt.pos(top));
			scaffold.remove(top);
			cleanTarget = null;
		}
	}

	private void stopScaffoldDig(Minecraft mc) {
		if (diggingScaffold != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		diggingScaffold = null;
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mc.level != level) {
			fail(ErrorCode.INTERRUPTED, "dimension changed");
			return;
		}
		if (cleaning) {
			cleanTick(mc);
			return;
		}
		if (!clearQueue.isEmpty()) {
			clearTick(mc);
			return;
		}
		if (!collectTick(mc)) return;
		int n = placed(mc);
		if (n >= targets.size()) {
			if (building) {
				Bari.b().getBuilderProcess().onLostControl();
				building = false;
			}
			if (!doors.tick(mc, this::path)) return;
			finish(mc, null, "result=" + verb + summary());
			return;
		}
		if (!building) startBuilder();
		if (agent.tick() % 5 == 0) stageMaterials(mc);
		if (n > lastPlaced) {
			if (lastPlaced >= 0) markProgress();
			lastPlaced = n;
		}
		if (ticksSinceProgress() > STALL_TICKS) {
			finish(mc, ErrorCode.PATH_BLOCKED, "no new block placed for " + (STALL_TICKS / 20) + " s (leaves, terrain or standing spot in the way; "
				+ "clear nearby obstacles or move, then call again); " + remaining(mc));
			return;
		}
		if (!Bari.b().getBuilderProcess().isActive()) {
			if (++idleTicks > 40) {
				finish(mc, ErrorCode.PATH_BLOCKED, "builder stopped midway (out of materials, unreachable cells, or blocked by a rule); " + remaining(mc));
			}
		} else {
			idleTicks = 0;
		}
	}

	/** 「 dug=N demolished=N doors=N not_cleared=... doors_failed=...」(前面有空白，沒有東西時是空字串)。 */
	private String summary() {
		String s = "";
		if (dug > 0) s += (isBlueprint() || targets.isEmpty() ? " dug=" : " plants_cleared=") + dug;
		if (demolished > 0) s += " demolished=" + demolished;
		if (doors.placed() > 0) s += " doors=" + doors.placed();
		if (!notCleared.isEmpty()) s += " not_cleared=" + String.join(";", notCleared);
		if (!doors.failed().isEmpty()) s += " doors_failed=" + String.join(";", doors.failed());
		return s;
	}

	@Override
	protected void onStop(Minecraft mc) {
		try {
			stopDigging(mc);
			stopScaffoldDig(mc);
			Bari.stopAll();
			Bari.b().getBuilderProcess().onLostControl();
		} finally {
			// 不管上面有沒有出錯，Baritone 的設定都要還原(H10)
			var s = baritone.api.BaritoneAPI.getSettings();
			s.buildInLayers.value = false;
			s.layerOrder.value = savedLayerOrder;
			noPlace(false);
		}
	}

	/** 拆鷹架期間 Bari.applySettings 重設設定後，照樣不准挖和放(H10)。 */
	@Override
	public void adjustBaritone(baritone.api.Settings s) {
		if (cleaning && !suspended) {
			s.allowPlace.value = false;
			s.allowBreak.value = false;
		}
	}

	@Override
	protected void onShiftTimers(long pausedTicks) {
		if (cleaning) cleanStart += pausedTicks;
	}

	@Override
	protected void onResume(Minecraft mc) {
		if (cleaning) {
			noPlace(true);
			cleanTarget = null; // 重新規劃路線
		} else if (building) startBuilder();
		else {
			clearWait = 0;
			super.onResume(mc);
		}
	}
}
