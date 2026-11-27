package blockagent.game;

import blockagent.game.tasks.BuildTask;
import blockagent.game.tasks.ClearAreaTask;
import blockagent.mcp.Args;
import blockagent.mcp.Kv;
import blockagent.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reads the current world and inventory for both preview and construction. */
final class BuildPreflight {
	private static final Comparator<BlockPos> BUILD_ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getY)
		.thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX);
	/** 挖和拆由上往下(S4.8c)，免得站的地方先被挖空，屋頂也不會懸著。 */
	private static final Comparator<BlockPos> DIG_ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
		.thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX);
	static final BuildTarget AIR = new BuildTarget(Blocks.AIR.defaultBlockState(), Set.of());

	record Design(Map<BlockPos, BuildTarget> cells, AreaInspection.Region bounds, Item material, Set<BlockPos> reserved) {
		boolean blueprint() {
			return material == null;
		}
	}

	enum State {
		EXISTING("existing"), BUILDABLE("buildable"), DIG("dig"), DEMOLISH("demolish"),
		OCCUPIED("occupied"), UNSUPPORTED("unsupported"), UNLOADED("unloaded"), OUTSIDE("outside");

		final String label;

		State(String label) {
			this.label = label;
		}
	}

	record Cell(BlockPos pos, BlockState current, State state, String reason) {
		/** 預檢的阻擋明細：{@code x,y,z block=<現場> why=<原因>}。 */
		String description() {
			return Fmt.pos(pos) + " block=" + (current == null ? "unknown" : shortState(current)) + " why=" + reason;
		}

		/** 開工訊息裡的略過清單用：{@code x,y,z:<狀態>}。 */
		String brief() {
			return Fmt.pos(pos) + ":" + state.label;
		}
	}

	/**
	 * @param targets 要放(或指令模式要換成)的格子。生存模式不含門，也不含只要挖空的格子。
	 * @param digs    生存模式要先挖掉的格子(由上往下)，值是開工時看到的方塊；指令模式同樣列出，只用來計數。
	 * @param demolish digs 裡面屬於「拆掉自己蓋的舊設計」的格子。
	 * @param doors   最後用一般放置裝上的門(下半格)。
	 */
	record Check(boolean commands, Map<BlockPos, BuildTarget> targets, Map<BlockPos, BlockState> previous,
				 List<BlockPos> plants, List<Cell> blockers, Map<Integer, EnumMap<State, Integer>> layers,
				 int existing, Map<Item, Integer> missing, Map<BlockPos, BlockState> digs, Set<BlockPos> demolish,
				 Map<BlockPos, BuildTarget> doors) {
		/** 缺料清單 {@code id:數量,...}(S3.0b)。 */
		String missingList() {
			Map<String, Integer> m = new LinkedHashMap<>();
			missing.forEach((item, amount) -> m.put(Inv.id(item), amount));
			return Kv.counts(m);
		}

		/** 要放的方塊格數(不含只挖空的格子)，門另計。 */
		int placements() {
			int n = 0;
			for (BuildTarget target : targets.values()) if (!target.state().isAir()) n++;
			return n;
		}

		/** 生存模式開工前要清掉的所有格子：花草加上 digs，由上往下。 */
		Map<BlockPos, BlockState> clearing() {
			Map<BlockPos, BlockState> all = new TreeMap<>(DIG_ORDER);
			all.putAll(digs);
			for (BlockPos pos : plants) all.put(pos, previous.get(pos));
			return all;
		}

		/** 會動到的格子數：要放的、要挖或拆的、要裝門的，同一格只算一次(只挖不放的格子也算，M9)。 */
		int workCells() {
			Set<BlockPos> cells = new HashSet<>(targets.keySet());
			cells.addAll(digs.keySet());
			cells.addAll(doors.keySet());
			cells.addAll(plants);
			return cells.size();
		}

		boolean nothingToDo() {
			return targets.isEmpty() && digs.isEmpty() && doors.isEmpty();
		}

		/** 會做的事 {@code place=N dig=N demolish=N doors=N}(S4.8c)，沒有的項目省略。 */
		String work() {
			Kv kv = Kv.of().put("place", placements());
			int natural = digs.size() - demolish.size();
			kv.putIf(natural > 0, "dig", natural).putIf(!demolish.isEmpty(), "demolish", demolish.size())
				.putIf(!doors.isEmpty(), "doors", doors.size());
			return kv.toString();
		}

		/** 略過的格子 {@code skipped=N skipped_at=x,y,z:occupied;...}；沒有略過時是空字串。 */
		String skipped(int limit) {
			if (blockers.isEmpty()) return "";
			return "skipped=" + blockers.size() + " skipped_at="
				+ String.join(";", blockers.stream().limit(limit).map(Cell::brief).toList())
				+ (blockers.size() > limit ? ";..." : "");
		}

		/**
		 * dry_run 的預檢結果(唯讀)。label 是開頭的鍵值，例如 {@code design=wall}。
		 * 各高度的施工條件一行一層，阻擋明細每頁最多 32 格。
		 */
		ToolResult preview(String label, int offset) {
			StringBuilder text = new StringBuilder(Kv.of("DRY_RUN").raw(label).put("method", commands ? "commands" : "baritone")
				.put("existing", existing).put("buildable", workCells()).put("blocked", blockers.size()).toString());
			text.append("\n").append(Kv.of("work").raw(work()).putIf(!plants.isEmpty(), "plants_first", plants.size()));
			for (var layer : layers.entrySet()) {
				Kv kv = Kv.of("layer").put("y", layer.getKey());
				for (State state : State.values()) {
					if (!layer.getValue().containsKey(state)
						&& (state == State.OUTSIDE || state == State.DIG || state == State.DEMOLISH)) continue;
					kv.put(state.label, layer.getValue().getOrDefault(state, 0));
				}
				text.append("\n").append(kv);
			}
			text.append("\nmissing=").append(missingList());
			int end = Math.min(offset + AreaInspection.PAGE_SIZE, blockers.size());
			for (int i = offset; i < end; i++) text.append("\n- ").append(blockers.get(i).description());
			if (end < blockers.size()) text.append("\nnext_offset=").append(end);
			return ToolResult.ok(text.toString());
		}
	}

	private BuildPreflight() {
	}

	/** 方塊狀態的原版寫法，省略 minecraft:。 */
	static String shortState(BlockState state) {
		String s = BlockStateParser.serialize(state);
		return s.startsWith("minecraft:") ? s.substring(10) : s;
	}

	static Design wall(Args args) {
		BlockPos from = Tools.corner(args, "from"), to = Tools.corner(args, "to");
		int height = args.integer("height", 1, 1, 16);
		if (from.getX() != to.getX() && from.getZ() != to.getZ()) {
			throw new Args.BadArgument("a wall must be straight: from and to need the same x or the same z");
		}
		int y = Math.min(from.getY(), to.getY());
		var region = AreaInspection.Region.between(from.atY(y), to.atY(addHeight(y, height - 1)));
		region.volume();
		Item material = Tools.parseItem(args.str("material"));
		BuildTarget target = uniform(material);
		Map<BlockPos, BuildTarget> cells = new LinkedHashMap<>();
		for (BlockPos pos : region.positions()) cells.put(pos.immutable(), target);
		return new Design(cells, region, material, Set.of());
	}

	private static int addHeight(int y, int height) {
		if ((long) y + height > Integer.MAX_VALUE) throw new Args.BadArgument("height exceeds the coordinate range");
		return y + height;
	}

	private static BuildTarget uniform(Item material) {
		if (!(material instanceof BlockItem item)) throw new Args.BadArgument(Inv.id(material) + " is not a block");
		return new BuildTarget(item.getBlock().defaultBlockState(), Set.of());
	}

	static boolean isDoor(BuildTarget target) {
		return target.state().getBlock() instanceof DoorBlock;
	}

	static boolean isLowerDoor(BuildTarget target) {
		return isDoor(target) && target.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
	}

	/** 這份藍圖的舊版本在這格蓋的、而且是自己放的方塊(S4.8c 第 3 條)。舊版本可能有好幾版，符合其中一版就算。 */
	private static boolean ownOldDesign(Agent agent, Level level, BlockPos pos, BlockState current, List<BuildTarget> old) {
		if (old == null || !agent.guard.isOwned(pos) || current.getDestroySpeed(level, pos) < 0
			|| !agent.guard.canBreak(level, pos).allowed()) return false;
		for (BuildTarget target : old) if (!target.state().isAir() && target.matches(current)) return true;
		return false;
	}

	static Check inspect(Agent agent, Minecraft mc, Design design) {
		return inspect(agent, mc, design, Map.of());
	}

	/**
	 * 逐格比對設計和現場。{@code before} 是這份藍圖還沒施工完成的舊版本，每格列出各版的目標(沒有舊版本就是空的)。
	 * 只在舊版本有、這一版沒有的格子，自己蓋的舊方塊也要拆掉(S4.8c)。
	 */
	static Check inspect(Agent agent, Minecraft mc, Design design, Map<BlockPos, List<BuildTarget>> before) {
		boolean commands = BuildCommands.available(mc) && commandMaterials(mc, design);
		Check check = inspect(agent, mc, design, before, commands);
		// 指令會蓋掉有方塊實體的格子(例如舊版藍圖自己放的箱子)時，整個改用一般施工，保留原版的掉落與方塊實體處理(H5)
		if (commands && check.targets().keySet().stream().anyMatch(p -> {
			BlockState prev = check.previous().get(p);
			return prev != null && prev.hasBlockEntity();
		})) {
			return inspect(agent, mc, design, before, false);
		}
		return check;
	}

	/** 設計裡的方塊都能用指令放嗎：沒有方塊實體，也都在創造物品欄裡(不是管理員方塊)(S4.6f、H5)。 */
	private static boolean commandMaterials(Minecraft mc, Design design) {
		Set<net.minecraft.world.level.block.Block> blocks = new HashSet<>();
		for (BuildTarget target : design.cells().values()) {
			BlockState st = target.state();
			if (st.isAir() || !blocks.add(st.getBlock())) continue;
			if (st.hasBlockEntity() || !Creative.inCreativeTabs(mc, st.getBlock().asItem())) return false;
		}
		return true;
	}

	private static Check inspect(Agent agent, Minecraft mc, Design design, Map<BlockPos, List<BuildTarget>> before, boolean commands) {
		var level = mc.level;
		Map<BlockPos, BuildTarget> targets = new LinkedHashMap<>();
		Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
		Map<BlockPos, Cell> issues = new LinkedHashMap<>();
		List<BlockPos> plants = new ArrayList<>();
		Map<Integer, EnumMap<State, Integer>> layers = new TreeMap<>();
		Map<BlockPos, BlockState> digs = new TreeMap<>(DIG_ORDER);
		Set<BlockPos> demolish = new HashSet<>();
		Map<BlockPos, BuildTarget> doors = new LinkedHashMap<>();
		Set<BlockPos> all = new TreeSet<>(BUILD_ORDER);
		all.addAll(design.cells().keySet());
		all.addAll(before.keySet());
		var positions = List.copyOf(all);
		int existing = 0;
		for (BlockPos pos : positions) {
			var target = design.cells().get(pos);
			boolean valid = level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos);
			if (target == null) {
				// 只在上一版出現的格子：自己蓋的舊方塊拆掉，其他的不管
				if (!valid || !AreaInspection.loaded(level, pos)) continue;
				BlockState current = level.getBlockState(pos);
				if (ownOldDesign(agent, level, pos, current, before.get(pos))) {
					digs.put(pos, current);
					demolish.add(pos);
					previous.put(pos, current);
					if (commands) targets.put(pos, AIR);
				}
				continue;
			}
			if (!valid) {
				issues.put(pos, new Cell(pos, null, State.OUTSIDE, State.OUTSIDE.label));
				continue;
			}
			if (!AreaInspection.loaded(level, pos)) {
				issues.put(pos, new Cell(pos, null, State.UNLOADED, State.UNLOADED.label));
				continue;
			}
			BlockState current = level.getBlockState(pos);
			if (target.matches(current)) {
				count(layers, pos, State.EXISTING);
				existing++;
				continue;
			}
			if (!design.blueprint()) {
				boolean plant = BuildTask.clearablePlant(agent, level, pos, current);
				boolean open = current.isAir() || plant || current.canBeReplaced();
				var verdict = open && !(plant && !commands) ? agent.guard.canPlaceInto(level, pos) : RuleGuard.Verdict.ALLOW;
				if (open && verdict.allowed()) {
					targets.put(pos, target);
					previous.put(pos, current);
					if (plant && !commands) plants.add(pos);
				} else {
					String reason = verdict.allowed() ? "occupied want=" + shortState(target.state()) : "protected";
					issues.put(pos, new Cell(pos, current, State.OCCUPIED, reason));
				}
				continue;
			}
			// 藍圖(S4.8c)
			boolean door = isDoor(target);
			boolean plant = BuildTask.clearablePlant(agent, level, pos, current);
			boolean open = current.isAir() || current.canBeReplaced() || plant;
			if (open) {
				if (target.state().isAir()) { // 要空著：空氣、可替換方塊和花草都不用動
					count(layers, pos, State.EXISTING);
					existing++;
					continue;
				}
				var verdict = plant && !commands ? RuleGuard.Verdict.ALLOW : agent.guard.canPlaceInto(level, pos);
				if (!verdict.allowed()) {
					issues.put(pos, new Cell(pos, current, State.OCCUPIED, "protected"));
					continue;
				}
				previous.put(pos, current);
				if (door) {
					// 門的兩格都要先清空，下半格最後用一般放置裝上。水、雪片這類可替換的方塊放門時會直接蓋掉，
					// 不能當花草去挖(M9)
					if (!current.isAir()) {
						if (commands) targets.put(pos, AIR);
						else if (plant) plants.add(pos);
					}
					if (isLowerDoor(target)) doors.put(pos, target);
				} else {
					targets.put(pos, target);
					if (plant && !commands) plants.add(pos);
				}
				continue;
			}
			boolean natural = ClearAreaTask.eligible(agent, level, pos, current);
			boolean old = !natural && ownOldDesign(agent, level, pos, current, before.get(pos));
			if (!natural && !old) {
				String reason = target.state().isAir() ? "design_wants_air_but_block_is_not_natural_or_own"
					: "occupied want=" + shortState(target.state());
				var verdict = agent.guard.canBreak(level, pos);
				issues.put(pos, new Cell(pos, current, State.OCCUPIED, verdict.allowed() ? reason : "protected"));
				continue;
			}
			digs.put(pos, current);
			if (old) demolish.add(pos);
			previous.put(pos, current);
			if (door) {
				if (commands) targets.put(pos, AIR);
				if (isLowerDoor(target)) doors.put(pos, target);
			} else if (!target.state().isAir() || commands) {
				targets.put(pos, target);
			}
		}
		if (!commands) {
			Set<BlockPos> fill = new HashSet<>();
			targets.forEach((pos, target) -> {
				if (!target.state().isAir()) fill.add(pos);
			});
			var floating = BuildTask.floating(level, fill, digs.keySet());
			for (BlockPos pos : floating) {
				targets.remove(pos);
				digs.remove(pos);
				demolish.remove(pos);
				issues.put(pos, new Cell(pos, previous.remove(pos), State.UNSUPPORTED, State.UNSUPPORTED.label));
			}
			plants.removeIf(floating::contains);
		}
		for (BlockPos pos : digs.keySet()) count(layers, pos, demolish.contains(pos) ? State.DEMOLISH : State.DIG);
		for (BlockPos pos : targets.keySet()) if (!digs.containsKey(pos)) count(layers, pos, State.BUILDABLE);
		for (BlockPos pos : doors.keySet()) if (!digs.containsKey(pos)) count(layers, pos, State.BUILDABLE);
		var blockers = positions.stream().filter(issues::containsKey).map(issues::get).toList();
		blockers.forEach(cell -> count(layers, cell.pos(), cell.state()));
		Map<Item, Integer> missing = new LinkedHashMap<>();
		if (!commands) {
			Map<Item, Integer> required = new LinkedHashMap<>();
			for (BuildTarget target : targets.values()) {
				if (target.state().isAir()) continue;
				Item item = design.blueprint() ? target.state().getBlock().asItem() : design.material();
				required.merge(item, 1, Integer::sum);
			}
			for (BuildTarget door : doors.values()) required.merge(door.state().getBlock().asItem(), 1, Integer::sum);
			// 創造模式放方塊不會用掉，每種有 1 個就夠(牆、房子、藍圖都一樣，M9)
			boolean onePerType = Creative.isCreative(mc);
			required.forEach((item, amount) -> {
				int shortage = (onePerType ? 1 : amount) - Inv.count(mc.player, item);
				if (shortage > 0) missing.put(item, shortage);
			});
		}
		return new Check(commands, targets, previous, plants, blockers, layers, existing, missing, digs, demolish, doors);
	}

	private static void count(Map<Integer, EnumMap<State, Integer>> layers, BlockPos pos, State state) {
		layers.computeIfAbsent(pos.getY(), ignored -> new EnumMap<>(State.class)).merge(state, 1, Integer::sum);
	}
}
