package blockagent.game;

import blockagent.core.ChatPolicy;
import blockagent.game.tasks.BuildTask;
import blockagent.game.tasks.CommandBuildTask;
import blockagent.mcp.Args;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import blockagent.mcp.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 儲存施工意圖，續建時以目前的方塊和背包重新檢查(S4.8a、S4.8c)。 */
final class Blueprints {
	static final int MAX_PARTS = 256;
	private static final int LIST_PAGE_SIZE = 20;
	private static final int SHOW_PAGE_SIZE = 32;
	private static final int MAX_FILE_BYTES = 192_000;
	private static final Gson GSON = new Gson();

	/** 最多記住幾個還沒施工完成的舊版本。 */
	private static final int MAX_VERSIONS = 8;

	/**
	 * @param source   產生這份藍圖的 build_house 參數；自己寫的藍圖是 null。
	 * @param previous 還沒施工完成的舊版本(每版是 origin 和 parts)，由舊到新。修改之後施工完成前都留著，
	 *                 續建或再修改時才知道哪些自己蓋的舊方塊要拆。
	 */
	record Saved(String id, String name, String dimension, String origin, JsonArray parts, JsonObject source,
				 JsonArray previous) {
		Saved withPrevious(JsonArray previous) {
			return new Saved(id, name, dimension, origin, parts, source, previous);
		}

		/** 這一版本身(給下一次修改當上一版)。 */
		JsonObject version() {
			JsonObject v = new JsonObject();
			v.addProperty("origin", origin);
			v.add("parts", parts);
			return v;
		}
	}

	private Blueprints() {
	}

	static ToolResult run(Agent agent, Minecraft mc, Args args, String action) {
		try {
			return switch (action) {
				case "create" -> create(agent, mc, args);
				case "resume" -> withSaved(agent, mc, args.str("id"), blueprint -> start(agent, mc, blueprint,
					parse(mc, blueprint), before(mc, blueprint), args.bool("replace", false), "build_blueprint"));
				case "list" -> list(agent, mc, args.integer("offset", 0, 0, Integer.MAX_VALUE - LIST_PAGE_SIZE));
				case "show" -> withSaved(agent, mc, args.str("id"),
					blueprint -> show(agent, mc, blueprint, args.integer("offset", 0, 0, Integer.MAX_VALUE - SHOW_PAGE_SIZE)));
				case "update" -> update(agent, mc, args);
				case "demolish" -> demolish(agent, mc, args);
				case "delete" -> delete(agent, mc, args);
				default -> throw new Args.BadArgument("action must be create, resume, list, show, update, demolish or delete");
			};
		} catch (IOException | com.google.gson.JsonParseException e) {
			return ToolResult.error(ErrorCode.INTERNAL, "cannot access blueprint files");
		}
	}

	private static ToolResult create(Agent agent, Minecraft mc, Args args) throws IOException {
		Path directory = directory(agent, mc);
		if (directory == null) return unidentifiedWorld();
		boolean replace = args.bool("replace", false);
		ToolResult blocked = precheckBusy(agent, replace);
		if (blocked != null) return blocked;
		Saved blueprint = draft(mc, args, UUID.randomUUID().toString());
		var design = parse(mc, blueprint);
		blocked = precheckRiding(agent, mc, design, Map.of());
		if (blocked != null) return blocked;
		write(directory, blueprint);
		return start(agent, mc, blueprint, design, Map.of(), replace, "build_blueprint");
	}

	private static Saved draft(Minecraft mc, Args args, String id) {
		BlockPos origin = Tools.corner(args, "origin");
		JsonArray parts = args.objectArray("parts", MAX_PARTS);
		return new Saved(id, name(args, "blueprint"), dimension(mc), Fmt.pos(origin), parts, null, null);
	}

	private static String name(Args args, String fallback) {
		String name = ChatPolicy.sanitize(args.str("name", fallback)).strip();
		if (name.isEmpty() || name.length() > 80) throw new Args.BadArgument("name must be 1 to 80 characters");
		return name;
	}

	/** update：parts 整份替換，add_parts 加在原本的後面。原點和維度不變(S4.8c)。 */
	private static Saved revised(Saved old, Args args) {
		if (args.has("origin")) throw new Args.BadArgument("update cannot change origin; to move, demolish and then create");
		boolean whole = args.has("parts"), added = args.has("add_parts");
		if (whole == added) throw new Args.BadArgument("update needs exactly one of parts (replace all) or add_parts (append)");
		JsonArray parts;
		if (whole) {
			parts = args.objectArray("parts", MAX_PARTS);
		} else {
			JsonArray extra = args.objectArray("add_parts", MAX_PARTS);
			if (old.parts().size() + extra.size() > MAX_PARTS) {
				throw new Args.BadArgument("would have " + (old.parts().size() + extra.size()) + " parts; max " + MAX_PARTS);
			}
			parts = old.parts().deepCopy();
			parts.addAll(extra);
		}
		String name = args.has("name") ? name(args, old.name()) : old.name();
		return new Saved(old.id(), name, old.dimension(), old.origin(), parts, old.source(), carriedPrevious(old));
	}

	/** 還沒蓋完的舊版本留著，再加上目前這一版(最多 MAX_VERSIONS 版，太舊的丟掉)。 */
	static JsonArray carriedPrevious(Saved old) {
		JsonArray versions = old.previous() == null ? new JsonArray() : old.previous().deepCopy();
		versions.add(old.version());
		while (versions.size() > MAX_VERSIONS) versions.remove(0);
		return versions;
	}

	private static ToolResult update(Agent agent, Minecraft mc, Args args) throws IOException {
		Path directory = directory(agent, mc);
		return withSaved(agent, mc, args.str("id"), old -> {
			boolean replace = args.bool("replace", false);
			ToolResult blocked = precheckBusy(agent, replace);
			if (blocked != null) return blocked;
			Saved next = revised(old, args);
			var design = parse(mc, next);
			var prev = before(mc, next);
			blocked = precheckRiding(agent, mc, design, prev);
			if (blocked != null) return blocked;
			write(directory, next);
			return start(agent, mc, next, design, prev, replace, "build_blueprint");
		});
	}

	/** 拆掉現場跟這份藍圖相符、而且是自己放的方塊；全部拆完才刪紀錄(S4.8c)。 */
	private static ToolResult demolish(Agent agent, Minecraft mc, Args args) throws IOException {
		Path directory = directory(agent, mc);
		boolean replace = args.bool("replace", false);
		return withSaved(agent, mc, args.str("id"), blueprint -> {
			if (agent.tasks.hasActiveTask() && !replace) {
				return error(blueprint, ErrorCode.BUSY, "a background task is running; demolish after it ends", null);
			}
			Map<BlockPos, List<BuildTarget>> built = new LinkedHashMap<>(before(mc, blueprint));
			var design = parse(mc, blueprint);
			design.cells().forEach((pos, target) -> built.computeIfAbsent(pos, ignored -> new ArrayList<>()).add(target));
			var empty = new BuildPreflight.Design(Map.of(), design.bounds(), null, Set.of());
			var check = BuildPreflight.inspect(agent, mc, empty, built);
			String label = blueprint.name();
			if (check.digs().isEmpty()) {
				Files.deleteIfExists(directory.resolve(blueprint.id() + ".json"));
				return ToolResult.ok(Kv.of("OK deleted").put("id", blueprint.id()).put("removed", 0).put("note", "no own blocks left to remove"));
			}
			Runnable forget = () -> deleteQuietly(agent, directory, blueprint.id());
			String summary = Kv.of().put("id", blueprint.id()).put("remove", check.digs().size()).put("note", "record is deleted when done").toString();
			var bounds = bounds(check, design.bounds());
			var task = check.commands()
				? new CommandBuildTask(agent, mc.level, "build_blueprint", label, check.targets(), check.previous(), true, Map.of())
					.onComplete("demolished", forget)
				: new BuildTask(agent, "build_blueprint", label, bounds.min(), bounds.max(), Map.of(), check.digs(),
					check.demolish(), Map.of(), Set.of()).onComplete("demolished", forget);
			return agent.tasks.start(task, replace, summary);
		});
	}

	private static ToolResult delete(Agent agent, Minecraft mc, Args args) throws IOException {
		Path directory = directory(agent, mc);
		return withSaved(agent, mc, args.str("id"), blueprint -> {
			Files.deleteIfExists(directory.resolve(blueprint.id() + ".json"));
			return ToolResult.ok(Kv.of("OK deleted").put("id", blueprint.id()).quoted("name", blueprint.name()).put("note", "record only; blocks untouched"));
		});
	}

	private static ToolResult show(Agent agent, Minecraft mc, Saved blueprint, int offset) {
		var design = parse(mc, blueprint);
		var check = BuildPreflight.inspect(agent, mc, design, before(mc, blueprint));
		StringBuilder text = new StringBuilder(head(blueprint).toString());
		if (blueprint.source() != null) text.append("\nhouse_params=").append(GSON.toJson(blueprint.source()));
		if (blueprint.previous() != null) text.append("\nnote=\"unfinished revision; resume also removes own blocks from the old design\"");
		text.append("\n").append(Kv.of("site").put("matching", check.existing()).raw(check.work()).put("blocked", check.blockers().size()));
		JsonArray parts = blueprint.parts();
		text.append("\n").append(Kv.of("parts").put("count", parts.size()).put("note", "later parts override earlier ones"));
		int end = Math.min(offset + SHOW_PAGE_SIZE, parts.size());
		for (int i = offset; i < end; i++) text.append("\n").append(i).append(". ").append(GSON.toJson(parts.get(i)));
		if (end < parts.size()) text.append("\nnext_offset=").append(end);
		return ToolResult.ok(text.toString());
	}

	static ToolResult preview(Agent agent, Minecraft mc, Args spec, int offset) {
		if (!spec.has("id")) {
			Saved draft = draft(mc, spec, null);
			return BuildPreflight.inspect(agent, mc, parse(mc, draft)).preview(Kv.of().put("design", "blueprint").quoted("name", draft.name()).toString(), offset);
		}
		try {
			return withSaved(agent, mc, spec.str("id"), blueprint -> {
				Saved target = blueprint;
				if (spec.has("parts") || spec.has("add_parts")) target = revised(blueprint, spec);
				else if (spec.has("origin") || spec.has("name")) {
					throw new Args.BadArgument("with id, preview a change by giving parts or add_parts");
				}
				return BuildPreflight.inspect(agent, mc, parse(mc, target), before(mc, target))
					.preview(Kv.of().put("design", "blueprint").put("id", target.id()).quoted("name", target.name()).toString(), offset);
			});
		} catch (IOException | com.google.gson.JsonParseException e) {
			return ToolResult.error(ErrorCode.INTERNAL, "cannot read blueprint files");
		}
	}

	// ---- build_house(S4.8b) ----

	static ToolResult house(Agent agent, Minecraft mc, Args args) {
		try {
			Path directory = directory(agent, mc);
			if (directory == null) return unidentifiedWorld();
			boolean replace = args.bool("replace", false);
			// 先確認不會被反射、任務或載具擋下再存檔，免得回錯卻已經改了藍圖(H12、P3-M8)
			ToolResult blocked = precheckBusy(agent, replace);
			if (blocked != null) return blocked;
			if (!args.has("id")) {
				var house = Houses.generate(agent, mc, args);
				Saved blueprint = new Saved(UUID.randomUUID().toString(), name(args, "house"), dimension(mc),
					Fmt.pos(house.origin()), house.parts(), house.source(), null);
				var design = parse(mc, blueprint);
				blocked = precheckRiding(agent, mc, design, Map.of());
				if (blocked != null) return blocked;
				write(directory, blueprint);
				return note(start(agent, mc, blueprint, design, Map.of(), replace, "build_house"), house.note());
			}
			return withSaved(agent, mc, args.str("id"), old -> {
				var next = regenerated(agent, mc, old, args);
				var design = parse(mc, next.saved());
				var prev = before(mc, next.saved());
				ToolResult riding = precheckRiding(agent, mc, design, prev);
				if (riding != null) return riding;
				write(directory, next.saved());
				return note(start(agent, mc, next.saved(), design, prev, replace, "build_house"), next.note());
			});
		} catch (IOException | com.google.gson.JsonParseException e) {
			return ToolResult.error(ErrorCode.INTERNAL, "cannot access blueprint files");
		}
	}

	static ToolResult previewHouse(Agent agent, Minecraft mc, Args spec, int offset) {
		if (!spec.has("id")) {
			var house = Houses.generate(agent, mc, spec);
			Saved draft = new Saved(null, name(spec, "house"), dimension(mc), Fmt.pos(house.origin()), house.parts(), house.source(), null);
			return note(BuildPreflight.inspect(agent, mc, parse(mc, draft)).preview(Kv.of().put("design", "house").quoted("name", draft.name()).toString(), offset), house.note());
		}
		try {
			return withSaved(agent, mc, spec.str("id"), old -> {
				Saved next = regenerated(agent, mc, old, spec).saved();
				return BuildPreflight.inspect(agent, mc, parse(mc, next), before(mc, next))
					.preview(Kv.of().put("design", "house").put("id", next.id()).quoted("name", next.name()).toString(), offset);
			});
		} catch (IOException | com.google.gson.JsonParseException e) {
			return ToolResult.error(ErrorCode.INTERNAL, "cannot read blueprint files");
		}
	}

	private record Regenerated(Saved saved, String note) {
	}

	/** 用藍圖記下的參數加上這次給的參數，重新量地形、重算構件(S4.8b)。 */
	private static Regenerated regenerated(Agent agent, Minecraft mc, Saved old, Args args) {
		if (old.source() == null) throw new Args.BadArgument("blueprint " + old.id() + " was not made by build_house; modify it with build_blueprint action=update");
		JsonObject merged = mergeHouseSource(old.source(), args);
		var house = Houses.generate(agent, mc, new Args(merged));
		String name = args.has("name") ? name(args, old.name()) : old.name();
		return new Regenerated(new Saved(old.id(), name, old.dimension(), Fmt.pos(house.origin()), house.parts(),
			house.source(), carriedPrevious(old)), house.note());
	}

	/** 合併舊屋藍圖參數與本次修改參數，並將舊版地板層 y 存檔平滑轉成站立高度語意。 */
	static JsonObject mergeHouseSource(JsonObject oldSource, Args args) {
		JsonObject merged = oldSource.deepCopy();
		boolean legacy = !merged.has("y_mode") || !Houses.Y_MODE_STANDING.equals(merged.get("y_mode").getAsString());
		if (legacy) {
			if (!args.has("from")) shiftLegacyCornerY(merged, "from", "corner1");
			if (!args.has("to")) shiftLegacyCornerY(merged, "to", "corner2");
			// 舊版省略 floor 時預設等同 material；若未另傳 floor，換 material 時地板一起跟著換
			boolean floorWasDefault = !merged.has("floor")
				|| (merged.has("material") && merged.get("floor").equals(merged.get("material")));
			if (!args.has("floor") && floorWasDefault) {
				if (args.has("material")) merged.add("floor", args.json().get("material"));
				else if (merged.has("material")) merged.add("floor", merged.get("material"));
			}
			merged.addProperty("y_mode", Houses.Y_MODE_STANDING);
		}
		// 舊版存檔把預設屋頂(= 牆的材質)也存了：只換 material 時屋頂跟著換(M12)
		if (args.has("material") && merged.has("material") && !args.has("roof")
			&& merged.has("roof") && merged.get("roof").equals(merged.get("material"))) {
			merged.remove("roof");
		}
		for (var entry : args.json().entrySet()) {
			if (Set.of("id", "replace", "wait_sec", "dry_run", "offset").contains(entry.getKey())) continue;
			merged.add(entry.getKey(), entry.getValue());
		}
		return merged;
	}

	private static void shiftLegacyCornerY(JsonObject source, String primary, String fallback) {
		String key = source.has(primary) ? primary : source.has(fallback) ? fallback : null;
		if (key == null) return;
		BlockPos p = Tools.corner(new Args(source), key);
		source.addProperty(key, p.getX() + "," + (p.getY() + 1) + "," + p.getZ());
	}

	private static ToolResult note(ToolResult result, String note) {
		return result.withSuffix(note);
	}

	// ---- 共用 ----

	private static BuildPreflight.Design parse(Minecraft mc, Saved blueprint) {
		return parse(mc, blueprint.origin(), blueprint.parts());
	}

	/** 還沒施工完成的舊版本，每格列出各版的目標(沒有就是空的)。 */
	private static Map<BlockPos, List<BuildTarget>> before(Minecraft mc, Saved blueprint) {
		JsonArray previous = blueprint.previous();
		if (previous == null) return Map.of();
		Map<BlockPos, List<BuildTarget>> cells = new LinkedHashMap<>();
		for (var element : previous) {
			JsonObject version = element.getAsJsonObject();
			if (!version.has("origin") || !version.has("parts")) continue;
			parse(mc, version.get("origin").getAsString(), version.getAsJsonArray("parts")).cells()
				.forEach((pos, target) -> cells.computeIfAbsent(pos, ignored -> new ArrayList<>()).add(target));
		}
		return cells;
	}

	private static BuildPreflight.Design parse(Minecraft mc, String originText, JsonArray partsJson) {
		JsonObject saved = new JsonObject();
		saved.addProperty("origin", originText);
		saved.add("parts", partsJson);
		Args args = new Args(saved);
		BlockPos origin = Tools.corner(args, "origin");
		if (!mc.level.isInWorldBounds(origin)) throw new Args.BadArgument("blueprint origin is outside the world");
		Map<BlockPos, BuildTarget> cells = new LinkedHashMap<>();
		int total = 0;
		for (var part : args.objectArray("parts", MAX_PARTS)) {
			Args p = new Args(part.getAsJsonObject());
			BlockPos from = absolute(origin, Tools.corner(p, "from"));
			BlockPos to = p.has("to") ? absolute(origin, Tools.corner(p, "to")) : from;
			if (!mc.level.isInWorldBounds(from) || !mc.level.isInWorldBounds(to)) {
				throw new Args.BadArgument("a blueprint part is outside the world");
			}
			var region = AreaInspection.Region.between(from, to);
			total += region.volume();
			if (total > AreaInspection.MAX_VOLUME) throw new Args.BadArgument("blueprint parts total more than " + AreaInspection.MAX_VOLUME + " blocks");
			BuildTarget target = target(p.str("block"));
			for (BlockPos pos : region.positions()) cells.put(pos.immutable(), target);
		}
		// 門的上半格由遊戲自己長出來，設計裡只能是空的(S4.8a)
		for (var entry : List.copyOf(cells.entrySet())) {
			if (!BuildPreflight.isLowerDoor(entry.getValue())) continue;
			BlockPos up = entry.getKey().above();
			BuildTarget upper = cells.get(up);
			if (upper != null && !upper.state().isAir()) {
				throw new Args.BadArgument("the upper half " + Fmt.pos(up) + " of the door at " + Fmt.pos(entry.getKey()) + " must not be another block");
			}
			if (!mc.level.isInWorldBounds(up)) throw new Args.BadArgument("the upper half of a door is outside the world");
			cells.put(up, new BuildTarget(entry.getValue().state().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Set.of(DoorBlock.HALF)));
		}
		if (cells.isEmpty()) throw new Args.BadArgument("a blueprint needs at least one part");
		BlockPos min = null, max = null;
		for (BlockPos pos : cells.keySet()) {
			min = min == null ? pos : AreaInspection.Region.between(min, pos).min();
			max = max == null ? pos : AreaInspection.Region.between(max, pos).max();
		}
		var bounds = new AreaInspection.Region(min, max);
		bounds.volume();
		return new BuildPreflight.Design(cells, bounds, null, Set.of());
	}

	private static BuildTarget target(String input) {
		if (input.length() > 256) throw new Args.BadArgument("block is longer than 256 characters");
		try {
			var result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, input, false);
			BlockState state = result.blockState();
			Set<Property<?>> properties = new HashSet<>(result.properties().keySet());
			if (!state.isAir()) {
				var block = state.getBlock();
				boolean door = block instanceof DoorBlock;
				if (!(block.asItem() instanceof BlockItem item) || item instanceof GameMasterBlockItem
					|| (item instanceof DoubleHighBlockItem && !door) || item.getBlock() != block || block instanceof BedBlock) {
					throw new Args.BadArgument("blueprints support only single-block items and doors: " + input);
				}
				if (door) {
					if (properties.contains(DoorBlock.HALF) && state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
						throw new Args.BadArgument("write only the lower half of a door (the upper half grows by itself): " + input);
					}
					state = state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
					properties.add(DoorBlock.HALF);
				}
			}
			return new BuildTarget(state, properties);
		} catch (CommandSyntaxException e) {
			throw new Args.BadArgument("invalid block or state: " + input + " (" + e.getRawMessage().getString() + ")");
		}
	}

	/** 涵蓋設計和所有要處理的格子的範圍(Baritone 的 schematic 和撿掉落物都用它)。 */
	private static AreaInspection.Region bounds(BuildPreflight.Check check, AreaInspection.Region design) {
		BlockPos min = design.min(), max = design.max();
		List<BlockPos> extra = new ArrayList<>(check.digs().keySet());
		extra.addAll(check.targets().keySet());
		for (BlockPos pos : extra) {
			min = AreaInspection.Region.between(min, pos).min();
			max = AreaInspection.Region.between(max, pos).max();
		}
		return new AreaInspection.Region(min, max);
	}

	/** 寫入藍圖檔前的忙碌預檢：與 TaskRunner.claim 對齊，避免反射中或任務執行中先寫檔再失敗(H12、P3-M8)。 */
	private static ToolResult precheckBusy(Agent agent, boolean replace) {
		var reflex = agent.reflexes.active();
		if (reflex != null && reflex != blockagent.game.reflex.Reflexes.Kind.UNSTUCK) {
			return ToolResult.error(ErrorCode.BUSY, Kv.of().put("reflex", reflex.id()),
				"the " + reflex.id() + " reflex is running; nothing was saved",
				"wait_events a few seconds until the reflex event, then try again");
		}
		if (agent.tasks.hasActiveTask() && !replace) {
			return ToolResult.error(ErrorCode.BUSY, "a background task is running; nothing was saved",
				"wait for it to end, or pass replace=true");
		}
		return null;
	}

	/** 寫入藍圖檔前的載具預檢：坐載具時直接以 BuildPreflight.inspect 判斷會不會建立需要走路的任務(與 start() 單一來源對齊)。 */
	private static ToolResult precheckRiding(Agent agent, Minecraft mc, BuildPreflight.Design design, Map<BlockPos, List<BuildTarget>> before) {
		if (mc.player == null || mc.player.getVehicle() == null) return null;
		var check = BuildPreflight.inspect(agent, mc, design, before);
		if (check.nothingToDo() || (check.commands() && check.doors().isEmpty())) return null;
		return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("riding", Fmt.entityId(mc.player.getVehicle())),
			"cannot start a walking build while riding; nothing was saved", "dismount first");
	}

	private static ToolResult start(Agent agent, Minecraft mc, Saved blueprint, BuildPreflight.Design design,
									Map<BlockPos, List<BuildTarget>> before, boolean replace, String tool) {
		if (agent.tasks.hasActiveTask() && !replace) {
			return error(blueprint, ErrorCode.BUSY, "a background task is running; resume with the same id after it ends", null);
		}
		var check = BuildPreflight.inspect(agent, mc, design, before);
		String label = blueprint.name();
		String summary = Kv.of().put("id", blueprint.id()).put("matching", check.existing() + "/" + design.cells().size())
			.raw(check.work()).raw(plantsNote(check)).raw(check.skipped(6)).toString();
		Path directory = directory(agent, mc);
		Runnable applied = () -> {
			if (blueprint.previous() != null) updateQuietly(agent, directory, blueprint.withPrevious(null));
		};
		if (check.nothingToDo()) {
			if (!check.blockers().isEmpty()) return error(blueprint, ErrorCode.PATH_BLOCKED, Kv.of().raw(summary), "every remaining cell is blocked", "show the blueprint, clear the blockers, then resume");
			applied.run();
			return ToolResult.ok(Kv.of("OK done").put("task", tool).put("note", "site already matches the blueprint").raw(summary));
		}
		if (!check.missing().isEmpty()) {
			return error(blueprint, ErrorCode.MISSING_MATERIALS, Kv.of().raw(summary).put("missing", check.missingList()), "not enough materials",
				"gather them, then build_blueprint action=resume id=" + blueprint.id());
		}
		Runnable done = check.blockers().isEmpty() ? applied : () -> {
		};
		var bounds = bounds(check, design.bounds());
		var task = check.commands()
			? new CommandBuildTask(agent, mc.level, tool, label, check.targets(), check.previous(), true, check.doors())
				.onComplete("built", done)
			: new BuildTask(agent, tool, label, bounds.min(), bounds.max(), check.targets(), check.clearing(), check.demolish(),
				check.doors(), design.cells().keySet()).onComplete("built", done);
		String scaffolds = check.commands() ? null : BuildTask.scaffoldPlan(mc);
		return agent.tasks.start(task, replace, Kv.of().raw(summary).raw(scaffolds).toString());
	}

	private static String plantsNote(BuildPreflight.Check check) {
		if (check.plants().isEmpty()) return "";
		var names = check.plants().stream().map(check.previous()::get).map(RuleGuard::shortId).distinct().toList();
		return "clear_plants=" + check.plants().size() + " plants=" + String.join(",", names);
	}

	private interface SavedUse {
		ToolResult apply(Saved blueprint) throws IOException;
	}

	private static ToolResult withSaved(Agent agent, Minecraft mc, String id, SavedUse use) throws IOException {
		Path directory = directory(agent, mc);
		if (directory == null) return unidentifiedWorld();
		if (!isId(id)) throw new Args.BadArgument("id must be a saved blueprint id");
		Path file = directory.resolve(id + ".json");
		if (!Files.isRegularFile(file)) return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("id", id), "no such blueprint in this world", "build_blueprint action=list");
		Saved blueprint = read(file, id);
		if (!blueprint.dimension().equals(dimension(mc))) {
			return error(blueprint, ErrorCode.INVALID_ARGUMENT, Kv.of().put("dim", blueprint.dimension()), "blueprint is in another dimension", "go back to that dimension");
		}
		return use.apply(blueprint);
	}

	private static ToolResult list(Agent agent, Minecraft mc, int offset) throws IOException {
		Path directory = directory(agent, mc);
		if (directory == null) return unidentifiedWorld();
		if (!Files.isDirectory(directory)) return ToolResult.ok("blueprints=none");
		try (var files = Files.list(directory)) {
			var page = files.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".json") && isId(fileId(p)))
				.sorted().skip(offset).limit(LIST_PAGE_SIZE + 1L).toList();
			if (page.isEmpty()) return ToolResult.ok("blueprints=none");
			StringBuilder text = new StringBuilder("blueprints");
			for (Path file : page.subList(0, Math.min(page.size(), LIST_PAGE_SIZE))) {
				String id = fileId(file);
				try {
					Saved saved = read(file, id);
					text.append("\n- ").append(head(saved).putIf(saved.source() != null, "house", true)
						.putIf(saved.previous() != null, "unfinished_revision", true));
				} catch (IOException | RuntimeException e) {
					text.append("\n- ").append(Kv.of().put("id", id).put("error", "unreadable"));
				}
			}
			if (page.size() > LIST_PAGE_SIZE) text.append("\nnext_offset=").append(offset + LIST_PAGE_SIZE);
			return ToolResult.ok(text.toString());
		}
	}

	private static ToolResult error(Saved blueprint, ErrorCode code, String reason, String hint) {
		return error(blueprint, code, Kv.of(), reason, hint);
	}

	/** 藍圖相關的錯誤一律帶上 id，AI 才知道之後要續建哪一份。 */
	private static ToolResult error(Saved blueprint, ErrorCode code, Kv data, String reason, String hint) {
		boolean hasId = data != null && data.toString().startsWith("id=");
		return ToolResult.error(code, hasId ? data : Kv.of().put("id", blueprint.id()).add(data), reason, hint);
	}

	/** 藍圖的基本資料 {@code id=.. name=".." dim=.. origin=x,y,z}。 */
	private static Kv head(Saved blueprint) {
		String origin = blueprint.origin().replace(" ", "");
		return Kv.of().put("id", blueprint.id()).quoted("name", blueprint.name()).put("dim", blueprint.dimension().replace("minecraft:", ""))
			.put("origin", origin);
	}

	private static Path directory(Agent agent, Minecraft mc) {
		String world = agent.worldKey();
		if (world == null || world.equals("unknown")) return null;
		var server = mc.getCurrentServer();
		if (server != null && server.isLan()) {
			String host = ServerAddress.parseString(server.ip == null ? "" : server.ip).getHost();
			world += "-" + WorldKey.hash(host);
		}
		return agent.dataDir.resolve("worlds").resolve(world).resolve("blueprints");
	}

	private static ToolResult unidentifiedWorld() {
		return ToolResult.error(ErrorCode.SESSION_ENDED, "cannot identify the current world for blueprints");
	}

	private static String dimension(Minecraft mc) {
		return mc.level.dimension().identifier().toString();
	}

	private static BlockPos absolute(BlockPos origin, BlockPos relative) {
		try {
			return new BlockPos(Math.addExact(origin.getX(), relative.getX()), Math.addExact(origin.getY(), relative.getY()),
				Math.addExact(origin.getZ(), relative.getZ()));
		} catch (ArithmeticException e) {
			throw new Args.BadArgument("blueprint coordinates are outside the world");
		}
	}

	private static boolean isId(String id) {
		try {
			return UUID.fromString(id).toString().equals(id);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String fileId(Path path) {
		String file = path.getFileName().toString();
		return file.substring(0, file.length() - ".json".length());
	}

	private static Saved read(Path file, String id) throws IOException {
		if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("blueprint file too large");
		Saved saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
		if (saved == null || !id.equals(saved.id()) || saved.name() == null || saved.dimension() == null
			|| saved.origin() == null || saved.parts() == null) throw new IOException("invalid blueprint file");
		return saved;
	}

	private static void write(Path directory, Saved saved) throws IOException {
		String json = GSON.toJson(saved);
		if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) throw new Args.BadArgument("blueprint is too large to save");
		// 暫存檔再搬過去；不支援原子搬移時也要覆蓋舊檔(H12)
		blockagent.core.AtomicFiles.writeString(directory.resolve(saved.id() + ".json"), json);
	}

	/**
	 * 任務結束時(client thread)更新紀錄；失敗只記進紀錄，不影響任務結果。
	 * 施工期間藍圖被 delete 掉了就不寫回去，免得刪掉的藍圖又出現(H12)。
	 */
	private static void updateQuietly(Agent agent, Path directory, Saved saved) {
		try {
			if (!Files.exists(directory.resolve(saved.id() + ".json"))) return;
			write(directory, saved);
		} catch (IOException | RuntimeException e) {
			agent.log.log(ActionLog.Source.system, "blueprint_write_failed", saved.id() + " " + e.getMessage());
		}
	}

	private static void deleteQuietly(Agent agent, Path directory, String id) {
		try {
			Files.deleteIfExists(directory.resolve(id + ".json"));
		} catch (IOException e) {
			agent.log.log(ActionLog.Source.system, "blueprint_delete_failed", id + " " + e.getMessage());
		}
	}
}
