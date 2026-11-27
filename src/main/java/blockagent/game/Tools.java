package blockagent.game;

import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import blockagent.core.ChatPolicy;
import blockagent.core.EventQueue;
import blockagent.core.ItemMatcher;
import blockagent.core.Recipes;
import blockagent.core.TunnelPlanner;
import blockagent.core.WorkstationPolicy;
import blockagent.game.reflex.Reflexes;
import blockagent.game.tasks.AnvilTask;
import blockagent.game.tasks.BreakTask;
import blockagent.game.tasks.BuildTask;
import blockagent.game.tasks.ClearAreaTask;
import blockagent.game.tasks.CommandBlockTask;
import blockagent.game.tasks.CommandBuildTask;
import blockagent.game.tasks.CraftTask;
import blockagent.game.tasks.DigTunnelTask;
import blockagent.game.tasks.DismountTask;
import blockagent.game.tasks.EatTask;
import blockagent.game.tasks.EnchantTask;
import blockagent.game.tasks.FarmTask;
import blockagent.game.tasks.FishTask;
import blockagent.game.tasks.FollowTask;
import blockagent.game.tasks.GotoTask;
import blockagent.game.tasks.GrindstoneTask;
import blockagent.game.tasks.HuntTask;
import blockagent.game.tasks.LecternReadTask;
import blockagent.game.tasks.MineTask;
import blockagent.game.tasks.MountTask;
import blockagent.game.tasks.PlaceTask;
import blockagent.game.tasks.Moves;
import blockagent.game.tasks.SmeltTask;
import blockagent.game.tasks.StationTask;
import blockagent.game.tasks.UseEntityTask;
import blockagent.mcp.Args;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import blockagent.mcp.Schema;
import blockagent.mcp.ToolRegistry;
import blockagent.mcp.ToolRegistry.Hints;
import blockagent.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 所有 MCP 工具(docs/SPEC.md S4.2)。沒有自動幫忙、連線、切換世界、執行指令的工具(S4.2 最後一點)；
 * follow 只在 AI 自己呼叫時才跟(需求 M2.5)。
 * 說明、參數和回應都是給模型看的精簡英文(S3.0)；HUD 和聊天是給人看的，不在這裡。
 */
public final class Tools {
	private Tools() {
	}

	private interface GameFn extends Function<Minecraft, ToolResult> {
	}

	/** 背景任務等多久(S4.3)：預設 30 秒，0 表示開始後立刻回傳。 */
	static final int DEFAULT_WAIT_SEC = 30;

	// ---- 共用 ----

	private static ToolResult notInWorld() {
		return ToolResult.error(ErrorCode.SESSION_ENDED, "not in a world");
	}

	/** 觀察類：在世界裡就能用(包括暫停時)。 */
	private static ToolResult observe(GameFn fn) throws Exception {
		return ClientThread.call(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) return notInWorld();
			return fn.apply(mc);
		});
	}

	/** 動作類：只有 RUNNING 才能用(S8.1)。 */
	private static ToolResult act(Agent a, String action, com.google.gson.JsonObject args, GameFn fn) throws Exception {
		return actConfirmed(a, action, args, mc -> CompletableFuture.completedFuture(fn.apply(mc)));
	}

	private static ToolResult actConfirmed(Agent a, String action, com.google.gson.JsonObject args,
										   Function<Minecraft, CompletableFuture<ToolResult>> fn) throws Exception {
		a.idle.touch();
		var pending = ClientThread.call(() -> {
			Minecraft mc = Minecraft.getInstance();
			switch (a.session.state()) {
				case RUNNING -> {
				}
				case ENDED -> {
					return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.SESSION_ENDED, "session ended", "end your turn"));
				}
				case NO_WORLD -> {
					return CompletableFuture.completedFuture(notInWorld());
				}
				case READY -> {
					return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.PAUSED, "user has not started the AI yet",
						"call wait_events until session_started"));
				}
				default -> {
					return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.PAUSED, "paused by user"));
				}
			}
			if (mc.player == null || mc.level == null) return CompletableFuture.completedFuture(notInWorld());
			return fn.apply(mc);
		});
		ToolResult r = pending.join();
		a.log.log(ActionLog.Source.ai, action, args, r.isOk() ? "ok" : r.code() + " " + r.reason());
		return r;
	}

	/**
	 * 註冊會開始背景任務的工具。每個都多兩個參數(說明寫在 instructions，不在每個工具重複)：
	 * replace(取代進行中的任務)和 wait_sec(任務開始後等到結束才回傳，預設 30 秒，0 立刻回傳；S4.3、S5.1)。
	 */
	private static void taskTool(ToolRegistry t, Agent a, String name, String description, Hints hints, Schema schema,
								 ToolRegistry.Handler handler) {
		t.register(name, description, hints, schema
				.bool("replace", null, false).def(false)
				.integer("wait_sec", null, false).range(0, 120).def(DEFAULT_WAIT_SEC).build(),
			args -> {
				int sec = new Args(args).integer("wait_sec", DEFAULT_WAIT_SEC, 0, 120);
				ToolResult r = handler.call(args);
				if (sec == 0 || !r.isOk() || !"running".equals(statusOf(r))) return r;
				// awaitTask 已接手等待並決定要回哪些事件，不再讓 ToolRegistry 尾端把 task_progress 倒出來(M4、P3-M11)
				return r.withSuffixHandled(awaitTask(a, sec, true));
			});
	}

	private static String statusOf(ToolResult r) {
		var s = r.structured().get("status");
		return s == null || s.isJsonNull() ? null : s.getAsString();
	}

	/** 等事件(S5.1)。taskOnly：任務結束或本來就沒有任務時立刻回傳，任務進度不會叫醒。 */
	private static String awaitTask(Agent a, int sec, boolean taskOnly) throws InterruptedException {
		var task = a.tasks.pendingResult();
		java.util.function.BooleanSupplier keepWaiting = () -> !taskOnly || (task != null && a.tasks.pendingResult() == task);
		var events = taskOnly
			? a.events.await(sec * 1000L, keepWaiting, EventQueue::wakesTaskWait)
			: a.events.await(sec * 1000L, keepWaiting);
		String text = EventQueue.render(events, a.events.takeDroppedCount());
		if (!text.isEmpty()) {
			boolean ended = events.stream().anyMatch(e ->
				"task_done".equals(e.type()) || "task_failed".equals(e.type()) || "task_cancelled".equals(e.type()));
			if (taskOnly && !ended && keepWaiting.getAsBoolean()) {
				var cur = a.tasks.current();
				if (cur != null) {
					return text + "\n" + waitRunningLine(cur.name(), a.tasks.progressSnapshot(), -1, a.reflexes.unstuckAttempts())
						+ " | hint=task still running; wait_events task_only=true to keep waiting";
				}
			}
			return text;
		}
		if (!keepWaiting.getAsBoolean()) return "OK no_task";
		var cur = a.tasks.current();
		return taskOnly
			? waitRunningLine(cur == null ? null : cur.name(), a.tasks.progressSnapshot(), sec, a.reflexes.unstuckAttempts())
				+ " | hint=wait_events task_only=true to keep waiting"
			: Kv.of("OK no_events").put("waited", sec + "s").toString();
	}

	/** WAIT task_running 一行摘要：帶上目前進度與脫困次數，避免 AI 盲等看不出任務是否停滯。 */
	static String waitRunningLine(String taskName, String progress, int waitedSec, int unstuckAttempts) {
		Kv kv = Kv.of("WAIT task_running").put("task", taskName);
		if (progress != null && !progress.isBlank() && !"-".equals(progress)) kv.raw(progress);
		if (waitedSec >= 0) kv.put("waited", waitedSec + "s");
		if (unstuckAttempts > 0) kv.put("unstuck", unstuckAttempts);
		return kv.toString();
	}

	static final int STATUS_TEXT_MAX = 30;

	private static String hudText(String raw, int maxLength) {
		if (raw == null) return null;
		String text = ChatPolicy.sanitize(raw);
		if (text.codePointCount(0, text.length()) <= maxLength) return text;
		return text.substring(0, text.offsetByCodePoints(0, maxLength));
	}

	static String norm(String id) {
		String s = id.trim().toLowerCase(java.util.Locale.ROOT);
		return s.contains(":") ? s : "minecraft:" + s;
	}

	static Item parseItem(String id) {
		Identifier rl = Identifier.tryParse(norm(id));
		if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) throw new Args.BadArgument("unknown item id: " + id);
		return BuiltInRegistries.ITEM.getValue(rl);
	}

	static Block parseBlock(String id) {
		Identifier rl = Identifier.tryParse(norm(id));
		if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
			boolean isItem = rl != null && BuiltInRegistries.ITEM.containsKey(rl);
			throw new Args.BadArgument("unknown block id: " + id + (isItem ? " (this is an item id, not a block id)" : ""));
		}
		return BuiltInRegistries.BLOCK.getValue(rl);
	}

	static List<Block> blocksInTag(TagKey<Block> tag) {
		return TaggedBlocks.of(tag);
	}

	/** 對應到標準方塊 tag 的群組名稱(純對照表，方便單元測試驗證)。 */
	static TagKey<Block> blockGroupTag(String s) {
		return switch (s) {
			case "leaves" -> BlockTags.LEAVES;
			case "bed", "beds" -> BlockTags.BEDS;
			case "door", "doors" -> BlockTags.DOORS;
			case "plank", "planks" -> BlockTags.PLANKS;
			case "wool" -> BlockTags.WOOL;
			case "stair", "stairs" -> BlockTags.STAIRS;
			case "slab", "slabs" -> BlockTags.SLABS;
			case "fence", "fences" -> BlockTags.FENCES;
			case "trapdoor", "trapdoors" -> BlockTags.TRAPDOORS;
			case "crop", "crops" -> BlockTags.CROPS;
			case "sapling", "saplings" -> BlockTags.SAPLINGS;
			default -> null;
		};
	}

	/** mine/find_blocks/goto_block 的方塊參數：方塊 id，或群組(log、bed、door、wool、iron_ore 含深板岩…)。 */
	static List<Block> parseBlockGroup(String raw) {
		String s = raw.trim().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
		TagKey<Block> directTag = blockGroupTag(s);
		if (directTag != null) return blocksInTag(directTag);
		switch (s) {
			case "log", "logs", "wood" -> {
				return TaggedBlocks.naturalLogs();
			}
			case "chests" -> {
				return List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
			}
			case "furnaces" -> {
				return List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
			}
			case "stone" -> {
				return List.of(Blocks.STONE);
			}
			case "any_ore", "ores" -> {
				return TaggedBlocks.allOres();
			}
			default -> {
			}
		}
		if (s.endsWith("_ore") || s.endsWith("_ores")) {
			String base = s.endsWith("_ores") ? s : s + "s";
			TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(base));
			List<Block> list = blocksInTag(tag);
			if (!list.isEmpty()) return list;
		}
		return List.of(parseBlock(s));
	}

	/** 逗號(或頓號)分隔的方塊清單：去重，最多 8 種。find_blocks 和 clear_area 共用。 */
	static List<String> parseTypeList(String raw, String name) {
		List<String> types = new ArrayList<>();
		for (String s : raw.split("[,，、]")) if (!s.isBlank() && !types.contains(s.strip())) types.add(s.strip());
		if (types.isEmpty()) throw new Args.BadArgument(name + " must not be empty");
		if (types.size() > 8) throw new Args.BadArgument(name + ": at most 8 block types");
		return types;
	}

	/**
	 * 找生物或載具：數字是 entity id；否則是種類(例如 sheep、oak_boat)，找 range 格內最近、符合 filter 的那個。
	 * attack、hunt、use_on_entity、mount 共用(target 可以是字串或整數，Args.strOrInt)。
	 */
	static Entity resolveEntity(Minecraft mc, String target, double range, java.util.function.Predicate<Entity> filter) {
		LocalPlayer p = mc.player;
		try {
			// id 也要符合同樣的條件和距離，不然可以用 id 打到範圍外或不該選的實體(M10)
			Entity byId = mc.level.getEntity(Integer.parseInt(target.trim()));
			return byId != null && byId.isAlive() && byId.distanceTo(p) <= range && filter.test(byId) ? byId : null;
		} catch (NumberFormatException ignored) {
			// 不是 id，當成種類
		}
		String type = norm(target);
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity x : mc.level.getEntities(p, p.getBoundingBox().inflate(range), x -> x.isAlive() && filter.test(x))) {
			if (BuiltInRegistries.ENTITY_TYPE.getKey(x.getType()).toString().equals(type) && x.distanceTo(p) < bestD) {
				bestD = x.distanceTo(p);
				best = x;
			}
		}
		return best;
	}

	private static ToolResult entityNotFound(String target, int range) {
		return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("target", target).put("range", range), "no such entity nearby",
			"get_surroundings for ids, or move closer");
	}

	static BlockPos pos(Args a, String prefix) {
		return new BlockPos(a.integer(prefix + "x"), a.integer(prefix + "y"), a.integer(prefix + "z"));
	}

	/** 區域端點等 "x,y,z" 參數(S3.0b)。 */
	static BlockPos corner(Args a, String name) {
		String[] parts = a.str(name).split("[ ,]+");
		if (parts.length != 3) throw new Args.BadArgument(name + " must be \"x,y,z\"");
		try {
			return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
		} catch (NumberFormatException e) {
			throw new Args.BadArgument(name + " must be \"x,y,z\" (integers)");
		}
	}

	private static Schema xyz(Schema s) {
		return s.integer("x", null, true).integer("y", null, true).integer("z", null, true);
	}

	private static Schema area(Schema s) {
		return s.string("from", "Corner \"x,y,z\"", true).string("to", "Opposite corner \"x,y,z\", inclusive", true);
	}

	private static Schema blueprintPart() {
		return Schema.object().string("from", "Relative \"x,y,z\"", true)
			.string("to", "Relative opposite corner, inclusive; omit for one block", false)
			.string("block", "Block with optional state, e.g. oak_stairs[facing=north]; air = keep empty", true);
	}

	static ToolResult tooFar(BlockPos p) {
		return ToolResult.error(ErrorCode.PATH_BLOCKED, Kv.of().put("pos", Fmt.pos(p)), "out of reach", "goto closer first");
	}

	private static ToolResult invalidBlockTarget(BlockPos pos) {
		return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("pos", Fmt.pos(pos)), "position is unloaded or outside the world");
	}

	private static ToolResult missingItem(Item item, int have, int need) {
		return missingItem(item, have, need, null);
	}

	private static ToolResult missingItem(Item item, int have, int need, ItemMatcher matcher) {
		Kv kv = Kv.of().put("item", Inv.id(item)).put("have", have).put("need", need);
		if (matcher != null && !matcher.isAny()) kv.put("match", matcher.raw());
		return ToolResult.error(ErrorCode.MISSING_MATERIALS, kv, "not enough in inventory");
	}

	private static ItemMatcher parseMatch(Args g) {
		if (!g.has("match")) return ItemMatcher.ANY;
		try {
			return ItemMatcher.parse(g.str("match"));
		} catch (IllegalArgumentException e) {
			throw new Args.BadArgument(e.getMessage());
		}
	}

	private static final Set<Item> BAD_FOOD =
		Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW);

	// ---- 註冊 ----

	public static void registerAll(Agent a) {
		ToolRegistry t = a.tools;

		// ===== 觀察(S3.2) =====
		t.register("get_self", "Own status: pos, dimension, biome, facing, game mode, hp/food/air/xp, effects, held items "
				+ "(durability, enchantments, potion), armor, inventory, task, session, reflexes, time.",
			Hints.READ, Schema.object().build(), args -> observe(mc -> ToolResult.ok(a.observation.self(mc))));
		t.register("get_surroundings", "Nearby summary: ground, light, terrain, biome directions, time, weather, notable blocks "
				+ "with owner (incl. written signs), item frames, mobs per species (nearest id), vehicles, dropped items.",
			Hints.READ, Schema.object().integer("radius", null, false).range(4, 48).def(16)
				.bool("detail", "List more entries", false).def(false).build(),
			args -> {
				Args g = new Args(args);
				int r = g.integer("radius", 16, 4, 48);
				boolean d = g.bool("detail", false);
				return observe(mc -> ToolResult.ok(a.observation.surroundings(mc, r, d)));
			});
		t.register("inspect_area", "Read a box of blocks in the current dimension: per-block pos, state, owner (and sign text), "
				+ "or a summary of block counts. Unloaded cells are marked.",
			Hints.READ, area(Schema.object())
				.enumString("mode", "auto: per-block when small or filtered", false, "auto", "summary", "blocks").def("auto")
				.string("type", "Only this block id", false)
				.integer("offset", "Per-block page start", false).range(0, AreaInspection.MAX_VOLUME).def(0)
				.integer("limit", null, false).range(1, AreaInspection.MAX_LIMIT).def(AreaInspection.PAGE_SIZE).build(),
			args -> {
				Args g = new Args(args);
				var region = AreaInspection.Region.between(corner(g, "from"), corner(g, "to"));
				int volume = region.volume();
				Block filter = g.has("type") ? parseBlock(g.str("type")) : null;
				boolean details = switch (g.str("mode", "auto")) {
					case "auto" -> volume <= AreaInspection.PAGE_SIZE || filter != null || g.has("offset") || g.has("limit");
					case "summary" -> false;
					case "blocks" -> true;
					default -> throw new Args.BadArgument("mode must be auto, summary or blocks");
				};
				int offset = g.integer("offset", 0, 0, AreaInspection.MAX_VOLUME);
				int limit = g.integer("limit", AreaInspection.PAGE_SIZE, 1, AreaInspection.MAX_LIMIT);
				return observe(mc -> AreaInspection.inspect(mc.level, a.observation, region, details, filter, offset, limit));
			});
		t.register("find_blocks", "Find the nearest blocks of given types in loaded chunks, with owner.",
			Hints.READ, Schema.object().string("type", "Block id or group (log, bed, door, stairs, slab, fence, trapdoor, plank, wool, crop, sapling, iron_ore incl. deepslate); comma-separate up to 8", true)
				.integer("radius", null, false).range(4, 96).def(48).integer("max", "Per type", false).range(1, 50).def(10)
				.integer("min_y", "Minimum Y (inclusive)", false).integer("max_y", "Maximum Y (inclusive)", false)
				.bool("source_only", "Only fluid source blocks", false).def(false).build(),
			args -> {
				Args g = new Args(args);
				List<String> types = parseTypeList(g.str("type"), "type");
				Map<String, List<Block>> groups = new LinkedHashMap<>();
				for (String s : types) groups.put(s, parseBlockGroup(s));
				int r = g.integer("radius", 48, 4, 96), max = g.integer("max", 10, 1, 50);
				int minY = g.integer("min_y", Integer.MIN_VALUE), maxY = g.integer("max_y", Integer.MAX_VALUE);
				if (minY > maxY) throw new Args.BadArgument("min_y must be <= max_y");
				boolean sourceOnly = g.bool("source_only", false);
				return observe(mc -> findBlocks(a, mc, groups, r, max, minY, maxY, sourceOnly));
			});
		t.register("get_players", "Other players in tracking range: pos, direction, held item, armor, recent activity (guessed).",
			Hints.READ, Schema.object().build(), args -> observe(mc -> ToolResult.ok(a.observation.players(mc))));
		t.register("get_chat", "Recent public chat, verbatim (private messages never appear).",
			Hints.READ, Schema.object().integer("count", null, false).range(1, 100).def(20).build(),
			args -> {
				int n = new Args(args).integer("count", 20, 1, 100);
				var lines = a.chat.recent(n);
				if (lines.isEmpty()) return ToolResult.ok("chat=none");
				StringBuilder sb = new StringBuilder();
				for (var l : lines) {
					if (!sb.isEmpty()) sb.append('\n');
					sb.append("- ").append(l.time()).append(' ').append(l.sender()).append('=').append(Kv.quote(l.text()));
				}
				return ToolResult.ok(sb.toString());
			});
		t.register("get_task", "Current background task and its progress.", Hints.READ, Schema.object().build(),
			args -> observe(mc -> {
				var task = a.tasks.current();
				return ToolResult.ok(task == null ? "task=none" : Kv.of().put("task", task.name()).raw(task.progress()).toString());
			}));

		// ===== 叫醒(S5.1) =====
		t.register("wait_events", "Wait for game events (long poll). task_only=true returns as soon as the current task ends; "
				+ "progress events do not wake it.",
			Hints.READ, Schema.object().integer("max_wait_sec", null, false).range(1, 120).def(30)
				.bool("task_only", null, false).def(false).build(),
			args -> {
				Args g = new Args(args);
				int sec = g.integer("max_wait_sec", 30, 1, 120);
				boolean taskOnly = g.bool("task_only", false);
				if (a.session.state() == blockagent.core.Session.State.ENDED && a.events.size() == 0) {
					return ToolResult.error(ErrorCode.SESSION_ENDED, "session ended", "end your turn");
				}
				return ToolResult.ok(awaitTask(a, sec, taskOnly));
			});

		// ===== 發言(S6) =====
		t.register("say", "Say something in game chat ([AI] prefix is added). Must not start with /. Rate-limited.",
			Hints.CHAT, Schema.object().string("text", "Message, in the language the players use", true).build(),
			args -> {
				String text = new Args(args).str("text");
				return act(a, "say", args, mc -> {
					ChatPolicy.Rejected rej = a.chat.say(text);
					if (rej == null) return ToolResult.ok("OK said");
					return ToolResult.error(ErrorCode.valueOf(rej.code()), rej.reason());
				});
			});

		// ===== 記憶(S4.8) =====
		t.register("memory_read", "Read this world's memory (goals, places, notes).", Hints.READ, Schema.object().build(),
			args -> {
				if (!a.memory.loaded()) return notInWorld();
				var all = a.memory.all();
				if (all.isEmpty()) return ToolResult.ok("memory=none");
				StringBuilder sb = new StringBuilder();
				all.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "\n").append("- ").append(k).append('=').append(Kv.quote(v)));
				return ToolResult.ok(sb.toString());
			});
		t.register("memory_write", "Write one memory entry; the same key overwrites. Value up to 500 chars, total up to 16 KB.",
			Hints.IDEMPOTENT, Schema.object().string("key", "e.g. home, goal, user_prefs", true).string("value", null, true).build(),
			args -> {
				Args g = new Args(args);
				String err = a.memory.put(g.str("key"), g.str("value"));
				return err == null ? ToolResult.ok(Kv.of("OK saved").put("key", g.str("key")))
					: ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("key", g.str("key")), err);
			});
		t.register("memory_delete", "Delete one memory entry.", new Hints(false, true, true, false),
			Schema.object().string("key", null, true).build(),
			args -> {
				String k = new Args(args).str("key");
				return a.memory.delete(k) ? ToolResult.ok(Kv.of("OK deleted").put("key", k))
					: ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("key", k), "no such memory entry", "memory_read to list keys");
			});

		// ===== 計畫(S5.6) =====
		t.register("plan", "Your current plan (kept by the mod; a fresh result from request_plan replaces it). "
				+ "Task results and idle reminders show the current step as plan_step=n/m. "
				+ "show (default); done = the current step is complete, move to the next; set = write your own plan "
				+ "(replaces it); clear.",
			new Hints(false, false, false, false),
			Schema.object().enumString("action", null, false, "show", "done", "set", "clear").def("show")
				.string("goal", "For set: overall goal", false)
				.string("steps", "For set: 1 to 8 steps in order, one per line", false)
				.build(),
			args -> {
				Args g = new Args(args);
				String action = g.str("action", "show");
				if (!a.memory.loaded()) return notInWorld();
				switch (action) {
					case "show" -> {
						return ToolResult.ok(a.plan.render());
					}
					case "done" -> {
						int n;
						boolean empty;
						String next = null, suffix = "";
						synchronized (a.plan) {
							if (!a.memory.loaded()) return notInWorld();
							n = a.plan.done();
							empty = a.plan.isEmpty();
							if (n != 0) {
								a.planChanged();
								next = a.plan.current();
								suffix = a.planSuffix();
							}
						}
						if (n == 0) {
							return ToolResult.error(ErrorCode.NOT_FOUND, empty ? "there is no plan" : "the plan is already finished",
								"request_plan, or plan(action=set) to write one");
						}
						a.log.log(ActionLog.Source.ai, "plan", "done step " + n);
						return ToolResult.ok(next != null ? Kv.of("OK step_done").put("step", n) + suffix
							: Kv.of("OK plan_finished").put("step", n) + " | hint=pick a next goal (request_plan without goal if available)");
					}
					case "set" -> {
						if (!g.has("goal") || !g.has("steps")) throw new Args.BadArgument("set needs goal and steps");
						List<String> steps = java.util.Arrays.asList(g.str("steps").split("\\R"));
						String rendered, suffix;
						synchronized (a.plan) {
							if (!a.memory.loaded()) return notInWorld();
							String err = a.plan.set(g.str("goal"), "", steps, "ai");
							if (err != null) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, err);
							a.planChanged();
							rendered = a.plan.render();
							suffix = a.planSuffix();
						}
						a.log.log(ActionLog.Source.ai, "plan", "set " + rendered);
						return ToolResult.ok(Kv.of("OK plan_set") + suffix);
					}
					case "clear" -> {
						synchronized (a.plan) {
							if (!a.memory.loaded()) return notInWorld();
							a.plan.clear();
							a.planChanged();
						}
						a.log.log(ActionLog.Source.ai, "plan", "clear");
						return ToolResult.ok("OK plan_cleared");
					}
					default -> throw new Args.BadArgument("action must be show, done, set or clear");
				}
			});

		// ===== 反射開關(S9.4) =====
		t.register("list_reflexes", "List reflexes and whether each is on.", Hints.READ, Schema.object().build(),
			args -> {
				Kv kv = Kv.of();
				List<String> locked = new ArrayList<>();
				a.reflexes.states().forEach((k, v) -> {
					kv.put(k.id(), v ? "on" : "off");
					if (a.reflexes.isLocked(k)) locked.add(k.id());
				});
				if (!locked.isEmpty()) kv.put("locked_on_by_user", String.join(",", locked));
				return ToolResult.ok(kv);
			});
		t.register("set_reflex", "Turn one reflex on or off (all reset to on in a new session).", Hints.IDEMPOTENT,
			Schema.object().enumString("name", null, true,
					java.util.Arrays.stream(Reflexes.Kind.values()).map(Reflexes.Kind::id).toArray(String[]::new))
				.bool("enabled", null, true).build(),
			args -> {
				Args g = new Args(args);
				Reflexes.Kind k = Reflexes.Kind.byId(g.str("name"));
				if (k == null) throw new Args.BadArgument("unknown reflex: " + g.str("name"));
				boolean on = g.bool("enabled", true);
				// 關掉正在執行的反射會放開按鍵、停下 Baritone、恢復任務，要在 client thread 做(C3)
				String err = ClientThread.call(() -> a.reflexes.set(k, on));
				if (err != null) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("name", k.id()), err, "");
				a.log.log(ActionLog.Source.ai, "set_reflex", k.id() + "=" + on);
				return ToolResult.ok(Kv.of("OK reflex").put("name", k.id()).put("enabled", on));
			});

		// ===== 基本動作 =====
		t.register("stop", "Stop the current background task.", Hints.IDEMPOTENT, Schema.object().build(),
			args -> act(a, "stop", args, mc -> {
				var cur = a.tasks.current();
				a.tasks.cancel("stopped by AI");
				Bari.stopAll();
				return ToolResult.ok(cur != null ? Kv.of("OK stopped").put("task", cur.name()).toString() : "OK no_task");
			}));
		t.register("set_status", "Set the one sentence the user sees on the HUD under the task line: what you are doing and why, "
				+ "in the user's language. It stays until you set another; empty clears.",
			Hints.IDEMPOTENT, Schema.object().string("text", "Max " + STATUS_TEXT_MAX + " chars", true).build(),
			args -> {
				a.setStatusText(hudText(new Args(args).str("text"), STATUS_TEXT_MAX));
				return ToolResult.ok("OK status_set");
			});
		taskTool(t, a, "goto", "Walk to a position. Omit y to match x,z only and follow the surface (good for rough terrain).",
			Hints.ACTION, Schema.object().integer("x", null, true).integer("y", null, false).integer("z", null, true)
				.integer("range", "Arrival distance", false).range(0, 32).def(1),
			args -> {
				Args g = new Args(args);
				int x = g.integer("x"), z = g.integer("z");
				int range = g.integer("range", 1, 0, 32);
				boolean replace = g.bool("replace", false);
				if (g.has("y")) {
					BlockPos p = new BlockPos(x, g.integer("y"), z);
					return act(a, "goto", args, mc -> {
						ToolResult r = a.tasks.start(new GotoTask(a, "goto", new GoalNear(p, range), Fmt.pos(p), p), replace, null);
						if (r.isOk()) warnHostileDestination(a, mc, x, p.getY(), z);
						return r;
					});
				}
				return act(a, "goto", args, mc -> {
					ToolResult r = a.tasks.start(new GotoTask(a, "goto", new GotoTask.GoalNearXZ(x, z, range), x + ",~," + z, x, null, z), replace, null);
					if (r.isOk()) warnHostileDestination(a, mc, x, null, z);
					return r;
				});
			});
		taskTool(t, a, "goto_block", "Walk next to the nearest block of a type.",
			Hints.ACTION, Schema.object().string("type", "Block id or group", true).integer("radius", null, false).range(4, 96).def(48),
			args -> {
				Args g = new Args(args);
				List<Block> blocks = parseBlockGroup(g.str("type"));
				int r = g.integer("radius", 48, 4, 96);
				return act(a, "goto_block", args, mc -> {
					List<BlockPos> found = Moves.scan(mc, blocks, r, 1, null);
					if (found.isEmpty()) return notFoundBlocks(g.str("type"), r);
					BlockPos p = found.getFirst();
					return a.tasks.start(new GotoTask(a, "goto_block", new GoalGetToBlock(p), Fmt.pos(p), p), g.bool("replace", false),
						Kv.of().put("target", RuleGuard.shortId(mc.level.getBlockState(p))).put("pos", Fmt.pos(p)).toString());
				});
			});
		t.register("look_at", "Turn to look at a position.", Hints.IDEMPOTENT, xyz(Schema.object()).build(),
			args -> {
				BlockPos p = pos(new Args(args), "");
				return act(a, "look_at", args, mc -> {
					Moves.lookAt(mc.player, Vec3.atCenterOf(p));
					return ToolResult.ok(Kv.of("OK looking").put("pos", Fmt.pos(p)).put("face", Fmt.facing(mc.player.getYRot())));
				});
			});
		taskTool(t, a, "break_block", "Break one block within reach (usually a few seconds). Optional tool_match selects a specific "
				+ "tool by enchantment/durability (e.g. silk_touch, fortune).",
			Hints.DESTRUCTIVE, xyz(Schema.object())
				.string("tool_match", "Optional tool filter: silk_touch, fortune, efficiency:3, best_dur...", false),
			args -> {
				Args g = new Args(args);
				BlockPos p = pos(g, "");
				boolean replace = g.bool("replace", false);
				ItemMatcher toolMatcher = parseNamedMatch(g, "tool_match");
				return act(a, "break_block", args, mc -> {
					boolean direct = BuildCommands.available(mc);
					if (!BuildCommands.validTarget(mc.level, p)) return invalidBlockTarget(p);
					RuleGuard.Verdict v = a.guard.canBreak(mc.level, p);
					if (!v.allowed()) return ToolResult.error(v.code(), Kv.of().put("pos", Fmt.pos(p)), v.reason(), v.hint());
					BlockState before = mc.level.getBlockState(p);
					// 容器等方塊保留原版玩家挖掘的物品掉落與方塊實體處理。
					if (direct && !before.hasBlockEntity()) {
						return a.tasks.start(new CommandBlockTask(a, mc.level, p, before, Blocks.AIR.defaultBlockState()), replace, null);
					}
					if (!Moves.canReach(mc.player, p)) return tooFar(p);
					if (!toolMatcher.isAny() && !Creative.isCreative(mc)
						&& Inv.findBestMiningTool(mc.player, before, toolMatcher, true) < 0) {
						return ToolResult.error(ErrorCode.MISSING_TOOL,
							Kv.of().put("block", RuleGuard.shortId(before)).put("tool_match", toolMatcher.raw()),
							"no suitable tool matching " + toolMatcher.raw() + " in inventory",
							"equip or craft a matching tool, or omit tool_match");
					}
					return a.tasks.start(new BreakTask(a, p, toolMatcher), replace, null);
				});
			});
		taskTool(t, a, "place_block", "Place a block item at a position within reach, next to a solid face; waits for server "
				+ "confirmation. facing sets the placed block's facing (stairs, furnace, chest, hopper, wall torch, observer...; "
				+ "logs use its axis); half sets top/bottom for slabs, stairs and trapdoors; sneak=true holds shift to place "
				+ "against interactive blocks (chest, furnace, crafting table...). Results are verified.",
			Hints.ACTION, xyz(Schema.object()).string("item", "Block item id", true)
				.enumString("facing", "Facing of the placed block; omit for the default", false, "north", "south", "east", "west", "up", "down")
				.enumString("half", "Half for slabs, stairs and trapdoors", false, "bottom", "top")
				.bool("sneak", "Hold shift to place against interactive/block-entity neighbors", false).def(false),
			args -> {
				Args g = new Args(args);
				BlockPos p = pos(g, "");
				Item item = parseItem(g.str("item"));
				boolean replace = g.bool("replace", false);
				Direction facing = g.has("facing") ? Direction.byName(g.str("facing")) : null;
				if (g.has("facing") && facing == null) throw new Args.BadArgument("facing must be north, south, east, west, up or down");
				String half = g.has("half") ? g.str("half") : null;
				if (half != null && !half.equals("bottom") && !half.equals("top")) throw new Args.BadArgument("half must be bottom or top");
				boolean sneak = g.bool("sneak", false);
				if (facing != null || half != null || sneak) {
					return act(a, "place_block", args, mc -> placeStructured(a, mc, p, item, facing, half, sneak, replace));
				}
				return actConfirmed(a, "place_block", args, mc -> placeBlock(a, mc, p, item, replace));
			});
		t.register("use_block", "Right-click a block within reach: doors, switches, containers, or read a book on a lectern; "
				+ "reports open/powered/lit or book contents when applicable.",
			Hints.ACTION, xyz(Schema.object()).build(),
			args -> {
				BlockPos p = pos(new Args(args), "");
				ToolResult r = actConfirmed(a, "use_block", args, mc -> useBlock(a, mc, p));
				if (r.isOk() && "running".equals(statusOf(r))) return r.withSuffixHandled(awaitTask(a, 10, true));
				return r;
			});
		t.register("equip", "Equip an item to the main hand, off hand, or an armor slot; pass item=\"none\" with "
				+ "slot=offhand|head|chest|legs|feet to unequip back to the main inventory.",
			Hints.IDEMPOTENT, Schema.object().string("item", "Item id, or \"none\" to unequip", true)
				.enumString("slot", null, false, "mainhand", "offhand", "armor", "head", "chest", "legs", "feet").def("mainhand")
				.string("match", "Attribute filter: ench=, stored=, potion=, name=, best_dur, low_dur, or shorthand", false).build(),
			args -> {
				Args g = new Args(args);
				String rawItem = g.str("item").trim();
				String slot = g.str("slot", "mainhand");
				ItemMatcher matcher = parseMatch(g);
				if (rawItem.equalsIgnoreCase("none")) {
					if (!matcher.isAny()) throw new Args.BadArgument("match cannot be used with item=none");
					return act(a, "equip", args, mc -> unequip(mc, slot));
				}
				Item item = parseItem(rawItem);
				return act(a, "equip", args, mc -> equip(mc, item, slot, matcher));
			});
		taskTool(t, a, "eat", "Eat food or drink a consumable (potion, milk_bucket, honey_bottle). Omit item to pick the best safe food.",
			Hints.ACTION, Schema.object().string("item", null, false)
				.string("match", "Attribute filter, e.g. fire_resistance, potion=long_night_vision", false),
			args -> {
				Args g = new Args(args);
				Item item = g.has("item") ? parseItem(g.str("item")) : null;
				ItemMatcher matcher = parseMatch(g);
				boolean replace = g.bool("replace", false);
				return act(a, "eat", args, mc -> startEat(a, mc, item, matcher, replace));
			});
		t.register("attack", "Hit a mob once within reach (entity id, or type = nearest within 16). Players, pets and villagers "
				+ "are protected. To fight until it dies, use hunt.",
			Hints.DESTRUCTIVE, Schema.object().string("target", "Entity id (e.g. \"1538\") or type (e.g. zombie)", true).build(),
			args -> {
				String target = new Args(args).strOrInt("target");
				return act(a, "attack", args, mc -> attack(a, mc, target));
			});
		taskTool(t, a, "hunt", "Track a mob, kill it and pick up keepable drops. Same tactics as the self_defense reflex "
				+ "(hit-and-back on creepers, shield toward ranged mobs, bow at range if you have bow and arrows).",
			Hints.DESTRUCTIVE, Schema.object().string("target", "Entity id or type (nearest within 48), e.g. sheep", true),
			args -> {
				Args g = new Args(args);
				String target = g.strOrInt("target");
				boolean replace = g.bool("replace", false);
				return act(a, "hunt", args, mc -> {
					Entity e = resolveEntity(mc, target, 48, x -> x instanceof LivingEntity && x != mc.player);
					if (!(e instanceof LivingEntity mob) || !mob.isAlive()) return entityNotFound(target, 48);
					var verdict = a.guard.canAttack(mob);
					if (!verdict.allowed()) return ToolResult.error(verdict.code(), Kv.of().put("target", target), verdict.reason(), verdict.hint());
					return a.tasks.start(new HuntTask(a, mob), replace,
						Kv.of().put("target", Fmt.entityId(mob)).put("id", mob.getId()).toString());
				});
			});
		t.register("drop", "Drop items from the inventory (incl. offhand), optionally facing a position or filtered by match.",
			Hints.DESTRUCTIVE, Schema.object().string("item", null, true).integer("count", null, false).range(1, 64 * 36).def(1)
				.integer("toward_x", null, false).integer("toward_y", null, false).integer("toward_z", null, false)
				.string("match", "Attribute filter: ench=, stored=, potion=, name=, best_dur, low_dur, or shorthand", false).build(),
			args -> {
				Args g = new Args(args);
				Item item = parseItem(g.str("item"));
				int count = g.integer("count", 1, 1, 64 * 36);
				BlockPos toward = g.has("toward_x") ? pos(g, "toward_") : null;
				ItemMatcher matcher = parseMatch(g);
				return act(a, "drop", args, mc -> {
					int have = Inv.countWithOffhand(mc.player, item, matcher);
					if (have < count) return missingItem(item, have, count, matcher);
					if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
					if (toward != null) Moves.lookAt(mc.player, Vec3.atCenterOf(toward));
					int n = Inv.dropItem(mc, item, matcher, count);
					return ToolResult.ok(Kv.of("OK dropped").put("item", Inv.id(item)).put("count", n)
						.put("toward", toward == null ? null : Fmt.pos(toward)));
				});
			});

		t.register("creative_take", "Creative mode only: take items from the creative inventory into your inventory.",
			Hints.ACTION, Schema.object().string("item", null, true).integer("count", null, false).range(1, 64 * 36).def(1).build(),
			args -> {
				Args g = new Args(args);
				Item item = parseItem(g.str("item"));
				int count = g.integer("count", 1, 1, 64 * 36);
				return act(a, "creative_take", args, mc -> Creative.take(mc, item, count));
			});

		// ===== 高階命令(S4.1、S4.4) =====
		taskTool(t, a, "clear_area", "Walk over and clear natural blocks of given types in a box, like a normal player. "
				+ "Only cells loaded and matching at start are handled.",
			Hints.DESTRUCTIVE, area(Schema.object())
				.string("block", "Block id or group (e.g. birch_leaves, leaves); comma-separate up to 8, e.g. dirt,stone", true),
			args -> {
				Args g = new Args(args);
				var region = AreaInspection.Region.between(corner(g, "from"), corner(g, "to"));
				region.volume();
				Set<Block> blocks = new java.util.HashSet<>();
				for (String type : parseTypeList(g.str("block"), "block")) blocks.addAll(parseBlockGroup(type));
				return act(a, "clear_area", args, mc -> clearArea(a, mc, region, blocks, g.bool("replace", false)));
			});
		taskTool(t, a, "mine", "Find blocks, walk there, mine and pick them up until count is reached. Checks tool and inventory "
				+ "space first. Optional tool_match enforces a matching tool on target blocks only (normal tools for clearing stone).",
			Hints.DESTRUCTIVE, Schema.object().string("block", "Block id or group: log, stone, coal_ore, iron_ore (incl. deepslate)...", true)
				.integer("count", null, true).range(1, 640).integer("radius", null, false).range(8, 96).def(48)
				.bool("search", "If not enough are loaded, explore outward (up to 5 min)", false).def(false)
				.string("tool_match", "Optional tool filter for target blocks: fortune, silk_touch, best_dur...", false),
			args -> {
				Args g = new Args(args);
				List<Block> blocks = parseBlockGroup(g.str("block"));
				int count = g.integer("count", 1, 1, 640);
				int r = g.integer("radius", 48, 8, 96);
				boolean search = g.bool("search", false);
				String label = g.str("block");
				ItemMatcher toolMatcher = parseNamedMatch(g, "tool_match");
				return act(a, "mine", args, mc -> {
					BlockState st = blocks.getFirst().defaultBlockState();
					boolean creative = Creative.isCreative(mc);
					if (!toolMatcher.isAny() && !creative) {
						if (Inv.findBestMiningTool(mc.player, st, toolMatcher, true) < 0) {
							return ToolResult.error(ErrorCode.MISSING_TOOL,
								Kv.of().put("block", label).put("tool_match", toolMatcher.raw()),
								"no suitable tool matching " + toolMatcher.raw() + " in inventory",
								"craft or enchant a matching tool, or omit tool_match");
						}
					} else if (st.requiresCorrectToolForDrops() && !creative) {
						boolean ok = Inv.find(mc.player, s -> s.isCorrectToolForDrops(st)) >= 0;
						if (!ok) {
							String need = toolHint(st);
							return ToolResult.error(ErrorCode.MISSING_TOOL, Kv.of().put("block", label).put("need", need),
								"no suitable tool in inventory", "craft " + need + " first");
						}
					}
					if (Inv.freeSlots(mc.player) == 0 && !creative) {
						return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0), "inventory is full",
							"store items with chest action=put, or drop junk");
					}
					List<BlockPos> found = Moves.scan(mc, blocks, r, count, p -> a.guard.canBreak(mc.level, p).allowed());
					if (found.isEmpty() && !search) return notFoundBlocks(label, r);
					List<String> warnings = new ArrayList<>();
					if (found.size() < count) warnings.add(search ? "not enough loaded; will search outward" : "not enough loaded; may stop early");
					if (!creative && toolMatcher.isAny() && !st.requiresCorrectToolForDrops()) {
						var tag = st.is(BlockTags.MINEABLE_WITH_AXE) ? ItemTags.AXES
							: st.is(BlockTags.MINEABLE_WITH_SHOVEL) ? ItemTags.SHOVELS : null;
						if (tag != null && Inv.find(mc.player, stack -> stack.is(tag)) < 0) warnings.add("no " + toolHint(st) + " in inventory");
					}
					Kv pre = Kv.of().put("found", found.size() + (found.size() >= count ? "+" : ""))
						.putIf(!warnings.isEmpty(), "warn", String.join("; ", warnings)).raw(Creative.noDropsNote(mc));
					return a.tasks.start(new MineTask(a, blocks, label, count, r, search, toolMatcher), g.bool("replace", false), pre.toString());
				});
			});
		taskTool(t, a, "dig_tunnel", "Dig a stepped 1x3 staircase (stairs) or 1x2 horizontal tunnel (straight) step by step. Checks "
				+ "ahead for lava, water, drops, protected blocks and optional stop_on ores before swinging, places torches every "
				+ "torch_every steps (torch_every=0 is limited to 12 steps), and reports all ores exposed along the tunnel.",
			Hints.DESTRUCTIVE, Schema.object()
				.enumString("mode", "stairs (descend 1x3) or straight (level 1x2)", true, "stairs", "straight")
				.enumString("direction", null, true, "north", "south", "east", "west")
				.integer("length", "Max steps (1-64; max 12 when torch_every=0)", false).range(1, TunnelPlanner.MAX_LENGTH).def(TunnelPlanner.DEFAULT_LENGTH)
				.integer("target_y", "Stop at this y (stairs only)", false).range(-64, 320)
				.integer("torch_every", "Auto-place torch every N steps in darkness (0=unlit, max 12 steps)", false).range(0, TunnelPlanner.MAX_TORCH_EVERY).def(TunnelPlanner.DEFAULT_TORCH_EVERY)
				.string("stop_on", "Comma-separated ore ids/groups to stop before breaking (e.g. diamond_ore, any_ore)", false)
				.string("tool_match", "Optional tool filter for digging the tunnel", false),
			args -> {
				Args g = new Args(args);
				TunnelPlanner.Mode mode = "stairs".equals(g.str("mode")) ? TunnelPlanner.Mode.STAIRS
					: "straight".equals(g.str("mode")) ? TunnelPlanner.Mode.STRAIGHT : null;
				TunnelPlanner.Dir dir = TunnelPlanner.Dir.parse(g.str("direction"));
				if (mode == null) throw new Args.BadArgument("mode must be stairs or straight");
				if (dir == null) throw new Args.BadArgument("direction must be north, south, east or west");
				int length = g.integer("length", TunnelPlanner.DEFAULT_LENGTH, 1, TunnelPlanner.MAX_LENGTH);
				Integer targetY = g.has("target_y") ? g.integer("target_y", 0, -64, 320) : null;
				int torchEvery = g.integer("torch_every", TunnelPlanner.DEFAULT_TORCH_EVERY, 0, TunnelPlanner.MAX_TORCH_EVERY);
				Set<Block> stopOn = new java.util.LinkedHashSet<>();
				if (g.has("stop_on")) {
					for (String type : parseTypeList(g.str("stop_on"), "stop_on")) stopOn.addAll(parseBlockGroup(type));
				}
				ItemMatcher toolMatcher = parseNamedMatch(g, "tool_match");
				boolean replace = g.bool("replace", false);
				return act(a, "dig_tunnel", args, mc -> digTunnel(a, mc, mode, dir, length, targetY, torchEvery, stopOn, toolMatcher, replace));
			});
		taskTool(t, a, "craft", "Craft items. Prefers a reachable or owned crafting table; can place one nearby from inventory "
				+ "materials. Missing materials are reported up front; it never gathers.",
			Hints.ACTION, Schema.object().string("item", null, true).integer("count", null, false).range(1, 64 * 9).def(1),
			args -> {
				Args g = new Args(args);
				Item item = parseItem(g.str("item"));
				int count = g.integer("count", 1, 1, 64 * 9);
				return act(a, "craft", args, mc -> {
					String id = BuiltInRegistries.ITEM.getKey(item).toString();
					BlockPos table = CraftTask.findTable(a, mc);
					Map<String, Integer> inv = new HashMap<>();
					Inv.summaryWithOffhand(mc.player).forEach((k, v) -> inv.put("minecraft:" + k, v));
					if (a.recipes.craftingRecipesFor(id).isEmpty()) {
						return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(item)), "no crafting recipe",
							"smelt it, or get it another way");
					}
					Recipes.Plan plan = a.recipes.planCraft(id, count, inv, table != null);
					if (!plan.ok()) {
						Map<String, Integer> miss = new LinkedHashMap<>();
						plan.missing().forEach((k, v) -> miss.put(k.replace("minecraft:", ""), v));
						return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("item", Inv.id(item)).put("missing", Kv.counts(miss)),
							"not enough materials");
					}
					Set<String> pullOffhandKeep = null;
					if (!mc.player.getOffhandItem().isEmpty()) {
						// 材料在副手(R12)：先做純檢查，通過 BUSY 後再於 CraftTask.start 換回主背包(工作台畫面沒有副手格)
						Map<String, Integer> mainOnly = new HashMap<>();
						Inv.summary(mc.player).forEach((k, v) -> mainOnly.put("minecraft:" + k, v));
						if (!a.recipes.planCraft(id, count, mainOnly, table != null).ok()) {
							Set<String> keep = new java.util.HashSet<>();
							for (var st : plan.steps()) keep.addAll(st.consumes().keySet());
							if (plan.needsTable() && table == null) keep.add("minecraft:crafting_table");
							if (Inv.findOffhandPullSlot(mc.player, s -> keep.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) < 0) {
								return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("item", Inv.id(item)),
									"a needed material is in the offhand and the main inventory is full", "drop or store one item, then craft again");
							}
							pullOffhandKeep = keep;
						}
					}
					Kv pre = !plan.needsTable() ? Kv.of().put("uses", "inventory_grid")
						: table != null ? Kv.of().put("uses", "table").put("table", Fmt.pos(table)).put("owner", Fmt.owner(a, table, mc.level.getBlockState(table)))
						: Kv.of().put("uses", "new_table");
					return a.tasks.start(new CraftTask(a, plan, id, count, table, pullOffhandKeep), g.bool("replace", false), pre.toString());
				});
			});
		taskTool(t, a, "smelt", "Smelt in a furnace, blast_furnace or smoker within 32 blocks (prefers blast_furnace/smoker when "
				+ "supported, or places a furnace from inventory), or inspect/collect a furnace with action=list|take at x,y,z.",
			Hints.ACTION, Schema.object()
				.enumString("action", "smelt (default), list (inspect furnace), or take (collect output)", false, "smelt", "list", "take").def("smelt")
				.string("item", "Input (e.g. raw_iron) or wanted output (e.g. iron_ingot); required for smelt", false)
				.integer("count", "Required for smelt", false).range(1, 64)
				.string("fuel", "Omit to pick automatically", false)
				.integer("x", "Furnace x (required for list/take)", false)
				.integer("y", "Furnace y (required for list/take)", false)
				.integer("z", "Furnace z (required for list/take)", false),
			args -> {
				Args g = new Args(args);
				String action = g.str("action", "smelt");
				boolean replace = g.bool("replace", false);
				if (action.equals("list") || action.equals("take")) {
					if (!g.has("x") || !g.has("y") || !g.has("z")) {
						throw new Args.BadArgument("action=" + action + " requires x, y and z");
					}
					BlockPos target = pos(g, "");
					SmeltTask.Mode mode = action.equals("list") ? SmeltTask.Mode.LIST : SmeltTask.Mode.TAKE;
					return act(a, "smelt", args, mc -> smeltInspectOrTake(a, mc, mode, target, replace));
				}
				if (!action.equals("smelt")) throw new Args.BadArgument("action must be smelt, list or take");
				if (!g.has("item") || !g.has("count")) throw new Args.BadArgument("action=smelt requires item and count");
				Item item = parseItem(g.str("item"));
				int count = g.integer("count", 1, 1, 64);
				Item fuelArg = g.has("fuel") ? parseItem(g.str("fuel")) : null;
				return act(a, "smelt", args, mc -> smelt(a, mc, item, count, fuelArg, replace));
			});
		taskTool(t, a, "build_wall", "Build a straight wall between two points (same x or same z), height blocks up from the lower "
				+ "point. Fills empty cells only; never breaks anything. dry_run=true previews (also while paused).",
			Hints.ACTION, Schema.object().string("from", "\"x,y,z\"", true).string("to", "\"x,y,z\"", true)
				.integer("height", null, true).range(1, 16).string("material", "Block item id", true)
				.bool("dry_run", null, false).def(false).integer("offset", "dry_run blocker page start", false).range(0, AreaInspection.MAX_VOLUME).def(0),
			args -> {
				Args g = new Args(args);
				var design = BuildPreflight.wall(g);
				if (g.bool("dry_run", false)) {
					int offset = g.integer("offset", 0, 0, AreaInspection.MAX_VOLUME);
					return observe(mc -> BuildPreflight.inspect(a, mc, design).preview("design=wall", offset));
				}
				return act(a, "build_wall", args, mc -> build(a, mc, "build_wall", "牆", design, g.bool("replace", false)));
			});
		taskTool(t, a, "build_house", "Build a livable house (walls, windows, flat roof, door; keeps natural ground by default and fills only dips, pass floor to pave) fitted to the "
				+ "terrain; refuses if the ground varies more than 3. Saved as a blueprint: the id is in the result, resume with "
				+ "build_blueprint. Pass id to regenerate it with changed params. Touches only natural blocks and its own blocks. "
				+ "dry_run=true previews.",
			Hints.DESTRUCTIVE, Schema.object().string("from", "Outer wall corner \"x,y,z\"; y = interior standing level (floor sits at y-1)", false)
				.string("to", "Opposite outer wall corner \"x,y,z\"", false)
				.string("material", "Wall block id", false)
				.integer("height", "Interior height (3-8, default 4)", false).range(3, 8).def(4)
				.string("floor", "Optional floor block to pave the whole floor; omitted keeps natural ground and fills only dips with material", false)
				.string("roof", "Roof block; default material", false)
				.enumString("door_side", null, false, "north", "south", "east", "west").def("north")
				.string("door", "Wooden door id, or none for an opening; omitted: use a wooden door from inventory if any", false)
				.string("window", "e.g. glass_pane; one per wall except the door wall", false)
				.string("name", null, false)
				.string("id", "Regenerate this house; give only the params to change", false)
				.bool("dry_run", null, false).def(false).integer("offset", "dry_run blocker page start", false).range(0, AreaInspection.MAX_VOLUME).def(0),
			args -> {
				Args g = new Args(args);
				if (!g.has("id")) {
					for (String key : new String[]{"from", "to", "material"}) {
						if (!g.has(key)) throw new Args.BadArgument("a new house needs " + key + " (use id only to regenerate)");
					}
				}
				if (g.bool("dry_run", false)) {
					int offset = g.integer("offset", 0, 0, AreaInspection.MAX_VOLUME);
					return observe(mc -> Blueprints.previewHouse(a, mc, g, offset));
				}
				return act(a, "build_house", args, mc -> Blueprints.house(a, mc, g));
			});
		taskTool(t, a, "build_blueprint", "Save, build, show, modify or demolish multi-block blueprints. Building only touches "
				+ "natural blocks and blocks this blueprint placed. Kept after missing materials; resume by id. For a plain "
				+ "house use build_house.",
			Hints.DESTRUCTIVE, Schema.object().enumString("action", "create: save and build. resume: continue by id. list. show: "
						+ "parts and site diff. update: parts replaces all or add_parts appends, then builds; own blocks no longer in "
						+ "the design are removed. demolish: remove own blocks matching it, then delete the record. delete: forget "
						+ "the record only.", true,
					"create", "resume", "list", "show", "update", "demolish", "delete")
				.string("id", "Blueprint id (resume, show, update, demolish, delete)", false)
				.string("name", "Name (create; update renames)", false)
				.string("origin", "Absolute origin \"x,y,z\" (create)", false)
				.objectArray("parts", "Whole design (create, update); later parts override earlier ones", false, blueprintPart(), Blueprints.MAX_PARTS)
				.objectArray("add_parts", "Parts appended to the saved design (update)", false, blueprintPart(), Blueprints.MAX_PARTS)
				.bool("dry_run", "Preview create, update or resume without changing anything", false).def(false)
				.integer("offset", "Page start for list, show, dry_run", false).range(0, AreaInspection.MAX_VOLUME).def(0),
			args -> {
				Args g = new Args(args);
				String action = g.str("action");
				if (g.bool("dry_run", false)) {
					if (!Set.of("create", "update", "resume").contains(action)) {
						throw new Args.BadArgument("dry_run works with action create, update or resume");
					}
					if (action.equals("create") && g.has("id")) throw new Args.BadArgument("create does not take id");
					if (!action.equals("create") && !g.has("id")) throw new Args.BadArgument(action + " needs id");
					int offset = g.integer("offset", 0, 0, AreaInspection.MAX_VOLUME);
					return observe(mc -> Blueprints.preview(a, mc, g, offset));
				}
				if (action.equals("list") || action.equals("show") || action.equals("delete")) return observe(mc -> Blueprints.run(a, mc, g, action));
				return act(a, "build_blueprint", args, mc -> Blueprints.run(a, mc, g, action));
			});
		taskTool(t, a, "till_and_plant", "Till natural dirt or grass and plant seeds. Use ground-level y. Leave an empty adjacent cell over soil for melons/pumpkins to grow.",
			Hints.ACTION, area(Schema.object()).enumString("seed", null, true, "wheat_seeds", "carrot", "potato", "beetroot_seeds", "melon_seeds", "pumpkin_seeds"),
			args -> {
				Args g = new Args(args);
				BlockPos c1 = corner(g, "from"), c2 = corner(g, "to");
				Item seed = parseItem(g.str("seed"));
				if (!FarmTask.CROP_OF.containsKey(seed)) throw new Args.BadArgument("unsupported seed: " + g.str("seed"));
				return act(a, "till_and_plant", args, mc -> tillAndPlant(a, mc, c1, c2, seed, g.bool("replace", false)));
			});
		taskTool(t, a, "harvest", "Harvest ripe crops you planted and fruits linked to your melon or pumpkin stems. Keeps the stems for regrowth.",
			Hints.ACTION, area(Schema.object()).bool("replant", null, false).def(true),
			args -> {
				Args g = new Args(args);
				BlockPos c1 = corner(g, "from"), c2 = corner(g, "to");
				// 作物可能比給的高度低一格或高兩格；from/to 的 y 誰大都一樣(H12)，先擋極端整數溢位(P3-M10)
				var box = AreaInspection.Region.between(c1, c2);
				if (box.min().getY() == Integer.MIN_VALUE || box.max().getY() > Integer.MAX_VALUE - 2) {
					throw new Args.BadArgument("y coordinate out of supported range");
				}
				var scan = new AreaInspection.Region(box.min().below(), box.max().above(2));
				scan.volume();
				return act(a, "harvest", args, mc -> {
					List<BlockPos> crops = new ArrayList<>();
					for (BlockPos p : scan.positions()) {
						BlockState st = mc.level.getBlockState(p);
						if (FarmTask.harvestable(a, mc.level, p, st)) crops.add(p.immutable());
					}
					if (crops.isEmpty()) {
						return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("from", Fmt.pos(c1)).put("to", Fmt.pos(c2)),
							"no ripe crops you planted in this area", "wait for crops to grow, or check the area with inspect_area");
					}
					return a.tasks.start(new FarmTask(a, FarmTask.Mode.HARVEST, crops, null, g.bool("replant", true)), g.bool("replace", false),
						Kv.of().put("ripe", crops.size()).toString());
				});
			});
		taskTool(t, a, "sleep", "Walk to a bed and sleep; night or thunderstorm only. Omit x,y,z to use the nearest free bed within "
				+ "32 blocks (anyone's).",
			Hints.ACTION, Schema.object().integer("x", null, false).integer("y", null, false).integer("z", null, false),
			args -> {
				Args g = new Args(args);
				BlockPos chosen = g.has("x") ? pos(g, "") : null;
				return act(a, "sleep", args, mc -> sleep(a, mc, chosen, g.bool("replace", false)));
			});

		// ===== 跟其他玩家、生物、載具互動 =====
		taskTool(t, a, "follow", "Follow a player at a distance until time is up, they vanish, stop, or pause. Only when you decide to.",
			Hints.ACTION, Schema.object().string("player", "Name as shown by get_players", true)
				.integer("distance", null, false).range(2, 8).def(3).integer("max_sec", null, false).range(5, 600).def(60),
			args -> {
				Args g = new Args(args);
				String name = g.str("player").strip();
				int dist = g.integer("distance", 3, 2, 8), sec = g.integer("max_sec", 60, 5, 600);
				return act(a, "follow", args, mc -> {
					if (FollowTask.findPlayer(mc, name) == null) {
						return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("player", name), "player not nearby", "get_players for names");
					}
					return a.tasks.start(new FollowTask(a, name, dist, sec), g.bool("replace", false), null);
				});
			});
		taskTool(t, a, "use_on_entity", "Walk to a mob and right-click it holding an item: shear, breed, leash, milk... Omit item "
				+ "for an empty hand. State-changing use on players and tamed pets (anyone's, yours too) is blocked; villagers and "
				+ "wandering traders cannot be used at all (no trading).",
			Hints.ACTION, Schema.object().string("target", "Entity id or type, e.g. sheep", true)
				.string("item", "e.g. shears, wheat", false),
			args -> {
				Args g = new Args(args);
				String target = g.strOrInt("target");
				Item item = g.has("item") ? parseItem(g.str("item")) : null;
				return act(a, "use_on_entity", args, mc -> {
					Entity e = resolveEntity(mc, target, 48, x -> x != mc.player);
					if (e == null || !e.isAlive()) return entityNotFound(target, 48);
					if (item != null && Inv.count(mc.player, item) == 0) return missingItem(item, 0, 1);
					var v = a.guard.canInteract(e);
					if (!v.allowed()) return ToolResult.error(v.code(), Kv.of().put("target", target), v.reason(), v.hint());
					return a.tasks.start(new UseEntityTask(a, e, item), g.bool("replace", false),
						Kv.of().put("target", Fmt.entityId(e)).put("id", e.getId()).toString());
				});
			});
		taskTool(t, a, "mount", "Walk to and ride a boat, minecart or similar. The driver steers; you never drive. Dismount before walking. "
				+ "Tamed horses (anyone's, yours too), villagers and wandering traders are blocked.",
			Hints.ACTION, Schema.object().string("target", "Entity id or type, e.g. oak_boat", true),
			args -> {
				Args g = new Args(args);
				String target = g.strOrInt("target");
				return act(a, "mount", args, mc -> {
					Entity e = resolveEntity(mc, target, 24, x -> x != mc.player && !(x instanceof net.minecraft.world.entity.player.Player));
					if (e == null || !e.isAlive()) return entityNotFound(target, 24);
					if (mc.player.getVehicle() == e) return ToolResult.ok(Kv.of("OK already_riding").put("target", Fmt.entityId(e)).put("id", e.getId()));
					var v = a.guard.canInteract(e);
					if (!v.allowed()) return ToolResult.error(v.code(), Kv.of().put("target", target), v.reason(), v.hint());
					return a.tasks.start(new MountTask(a, e), g.bool("replace", false), null);
				});
			});
		taskTool(t, a, "dismount", "Get off a boat, minecart or similar (usually under 1 s).", Hints.IDEMPOTENT, Schema.object(),
			args -> act(a, "dismount", args, mc -> a.tasks.start(new DismountTask(a), new Args(args).bool("replace", false), null)));
		t.register("use_item", "Use an item (incl. offhand): on the block at x,y,z within reach (fill/pour bucket, flint_and_steel, "
				+ "bone_meal), or without x,y,z in the air — or read written_book/writable_book text directly without opening the book GUI.",
			Hints.DESTRUCTIVE, Schema.object().string("item", "e.g. water_bucket, bucket, flint_and_steel, bone_meal, written_book", true)
				.integer("x", null, false).integer("y", null, false).integer("z", null, false)
				.string("match", "Attribute filter: ench=, stored=, potion=, name=, best_dur, low_dur, or shorthand", false)
				.integer("page", "Start page (1-based) when reading a book without x,y,z", false).range(1, 100).build(),
			args -> {
				Args g = new Args(args);
				Item item = parseItem(g.str("item"));
				BlockPos p = g.has("x") ? pos(g, "") : null;
				ItemMatcher matcher = parseMatch(g);
				Integer page = g.has("page") ? g.integer("page", 1, 1, 100) : null;
				if (page != null && (p != null || (item != Items.WRITTEN_BOOK && item != Items.WRITABLE_BOOK))) {
					throw new Args.BadArgument("page is only for reading written_book or writable_book without x,y,z");
				}
				return act(a, "use_item", args, mc -> useItem(a, mc, item, p, matcher, page == null ? 1 : page));
			});
		taskTool(t, a, "fish", "Fish at water within 16 blocks until count catches or time is up. Needs a fishing rod.",
			Hints.ACTION, Schema.object().integer("count", "Catches", false).range(1, 16).def(1)
				.integer("max_sec", null, false).range(10, 300).def(120),
			args -> {
				Args g = new Args(args);
				int count = g.integer("count", 1, 1, 16), sec = g.integer("max_sec", 120, 10, 300);
				return act(a, "fish", args, mc -> {
					if (Inv.count(mc.player, Items.FISHING_ROD) == 0) {
						return ToolResult.error(ErrorCode.MISSING_TOOL, Kv.of().put("need", "fishing_rod"), "no fishing rod in inventory",
							"craft fishing_rod (3 stick + 2 string)");
					}
					return a.tasks.start(new FishTask(a, count, sec), g.bool("replace", false), null);
				});
			});

		taskTool(t, a, "chest", "Walk to a chest, barrel, shulker box or ender chest and list its contents, put items in (incl. "
				+ "offhand), or take items out. items keys accept item_id or item_id[filter] (e.g. potion[fire_resistance]). "
				+ "Anyone's chests and naturally generated ones are allowed. The result lists the contents.",
			Hints.ACTION, xyz(Schema.object().enumString("action", null, true, "list", "put", "take"))
				.itemCounts("items", "{item_id or item_id[filter]: count}; required for put and take", false),
			args -> {
				Args g = new Args(args);
				String action = g.str("action");
				BlockPos p = pos(g, "");
				StationTask.Mode mode = switch (action) {
					case "list" -> StationTask.Mode.CHEST_LIST;
					case "put" -> StationTask.Mode.CHEST_PUT;
					case "take" -> StationTask.Mode.CHEST_TAKE;
					default -> throw new Args.BadArgument("action must be list, put or take");
				};
				Map<String, Integer> rawItems = mode == StationTask.Mode.CHEST_LIST ? Map.of() : g.itemCounts("items");
				List<StationTask.ChestEntry> entries = new ArrayList<>();
				for (var en : rawItems.entrySet()) {
					ItemMatcher.Spec spec;
					try {
						spec = ItemMatcher.parseSpec(en.getKey());
					} catch (IllegalArgumentException e) {
						throw new Args.BadArgument(e.getMessage());
					}
					entries.add(new StationTask.ChestEntry(en.getKey(), parseItem(spec.itemId()), spec.matcher(), en.getValue()));
				}
				return act(a, "chest", args, mc -> {
					java.util.function.Predicate<ItemStack> pullOffhandKeep = null;
					if (mode == StationTask.Mode.CHEST_PUT) {
						boolean needsOffhand = false;
						for (StationTask.ChestEntry e : entries) {
							int have = Inv.countWithOffhand(mc.player, e.item(), e.matcher());
							if (have < e.count()) return missingItem(e.item(), have, e.count(), e.matcher());
							if (Inv.count(mc.player, e.item(), e.matcher()) < e.count()) needsOffhand = true;
						}
						if (needsOffhand) {
							java.util.function.Predicate<ItemStack> keep = s -> entries.stream().anyMatch(e -> Inv.matches(s, e.item(), e.matcher()));
							if (Inv.findOffhandPullSlot(mc.player, keep) < 0) {
								return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0),
									"a needed item is in the offhand and the main inventory is full", "drop or store one item, then chest again");
							}
							pullOffhandKeep = keep;
						}
					}
					BlockState st = mc.level.getBlockState(p);
					if (!isChest(st)) {
						return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("pos", Fmt.pos(p)).put("block", RuleGuard.shortId(st)),
							"not a chest", "find_blocks type=chest,barrel");
					}
					return a.tasks.start(new StationTask(a, mode, p, entries, pullOffhandKeep), g.bool("replace", false), null);
				});
			});
		taskTool(t, a, "enchant", "Use an enchanting table within 32 blocks (prefers own; omit x,y,z to auto-find). action=preview "
				+ "reads the 3 offers, clues and max_level (to verify 15 bookshelves = 30) and returns the item to inventory; "
				+ "action=apply spends lapis_lazuli and levels on option 1, 2 or 3.",
			Hints.ACTION, Schema.object()
				.enumString("action", "preview or apply", true, "preview", "apply")
				.string("item", "Item id to enchant (incl. offhand)", true)
				.string("match", "Optional attribute filter", false)
				.integer("option", "Enchant slot 1-3 (required for apply)", false).range(1, 3)
				.integer("x", "Optional table x", false).integer("y", "Optional table y", false).integer("z", "Optional table z", false),
			args -> {
				Args g = new Args(args);
				String action = g.str("action");
				EnchantTask.Mode mode = switch (action) {
					case "preview" -> EnchantTask.Mode.PREVIEW;
					case "apply" -> EnchantTask.Mode.APPLY;
					default -> throw new Args.BadArgument("action must be preview or apply");
				};
				Item item = parseItem(g.str("item"));
				ItemMatcher matcher = parseMatch(g);
				int option = 0;
				if (mode == EnchantTask.Mode.APPLY) {
					if (!g.has("option")) throw new Args.BadArgument("action=apply requires option (1-3)");
					option = g.integer("option", 1, 1, 3);
				}
				BlockPos target = optionalPos(g);
				boolean replace = g.bool("replace", false);
				int finalOption = option;
				return act(a, "enchant", args, mc -> enchant(a, mc, mode, item, matcher, finalOption, target, replace));
			});
		taskTool(t, a, "anvil", "Use an anvil within 32 blocks (prefers own; anyone's anvil allowed; omit x,y,z to auto-find). "
				+ "action=preview reports result, level cost and too_expensive; action=apply combines items/books, repairs or renames "
				+ "(incl. name_tag).",
			Hints.ACTION, Schema.object()
				.enumString("action", "preview or apply", true, "preview", "apply")
				.string("item", "Left slot item id (incl. offhand)", true)
				.string("match", "Optional filter for item", false)
				.string("second_item", "Optional right slot item id (book, tool, repair material)", false)
				.string("second_match", "Optional filter for second_item", false)
				.string("rename", "Optional new name (max 50 chars); at least second_item or rename is required", false)
				.integer("x", "Optional anvil x", false).integer("y", "Optional anvil y", false).integer("z", "Optional anvil z", false),
			args -> {
				Args g = new Args(args);
				String action = g.str("action");
				AnvilTask.Mode mode = switch (action) {
					case "preview" -> AnvilTask.Mode.PREVIEW;
					case "apply" -> AnvilTask.Mode.APPLY;
					default -> throw new Args.BadArgument("action must be preview or apply");
				};
				Item item = parseItem(g.str("item"));
				ItemMatcher matcher = parseMatch(g);
				Item secondItem = g.has("second_item") ? parseItem(g.str("second_item")) : null;
				ItemMatcher secondMatcher = secondItem != null ? parseNamedMatch(g, "second_match") : ItemMatcher.ANY;
				String rename = g.has("rename") ? g.str("rename") : null;
				if (secondItem == null && rename == null) {
					throw new Args.BadArgument("anvil requires at least second_item or rename");
				}
				if (rename != null && rename.length() > net.minecraft.world.inventory.AnvilMenu.MAX_NAME_LENGTH) {
					throw new Args.BadArgument("rename must be at most " + net.minecraft.world.inventory.AnvilMenu.MAX_NAME_LENGTH + " characters");
				}
				BlockPos target = optionalPos(g);
				boolean replace = g.bool("replace", false);
				return act(a, "anvil", args, mc -> anvil(a, mc, mode, item, matcher, secondItem, secondMatcher, rename, target, replace));
			});
		taskTool(t, a, "grindstone", "Use a grindstone within 32 blocks (prefers own; omit x,y,z to auto-find) to disenchant an item "
				+ "for XP or combine-repair two identical items while removing non-curse enchantments.",
			Hints.ACTION, Schema.object()
				.string("item", "Item id (incl. offhand)", true)
				.string("match", "Optional filter for item", false)
				.string("second_item", "Optional second identical item id to combine-repair", false)
				.string("second_match", "Optional filter for second_item", false)
				.integer("x", "Optional grindstone x", false).integer("y", "Optional grindstone y", false).integer("z", "Optional grindstone z", false),
			args -> {
				Args g = new Args(args);
				Item item = parseItem(g.str("item"));
				ItemMatcher matcher = parseMatch(g);
				Item secondItem = g.has("second_item") ? parseItem(g.str("second_item")) : null;
				ItemMatcher secondMatcher = secondItem != null ? parseNamedMatch(g, "second_match") : ItemMatcher.ANY;
				BlockPos target = optionalPos(g);
				boolean replace = g.bool("replace", false);
				return act(a, "grindstone", args, mc -> grindstone(a, mc, item, matcher, secondItem, secondMatcher, target, replace));
			});
	}

	// ---- 實作細節 ----

	static boolean isChest(BlockState st) {
		return st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST) || st.is(Blocks.BARREL) || st.is(BlockTags.SHULKER_BOXES) || st.is(Blocks.ENDER_CHEST);
	}

	/** 挖得到掉落物的最低工具(A14、H12：原本鎬子等級錯一階)。 */
	static String toolHint(BlockState st) {
		if (st.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
			if (st.is(BlockTags.NEEDS_DIAMOND_TOOL)) return "diamond_pickaxe+";
			if (st.is(BlockTags.NEEDS_IRON_TOOL)) return "iron_pickaxe+";
			if (st.is(BlockTags.NEEDS_STONE_TOOL)) return "stone_pickaxe+";
			return "wooden_pickaxe+";
		}
		if (st.is(BlockTags.MINEABLE_WITH_AXE)) return "axe";
		if (st.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
		if (st.is(BlockTags.MINEABLE_WITH_HOE)) return "hoe";
		return "suitable_tool";
	}

	private static ToolResult notFoundBlocks(String type, int radius) {
		return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("type", type).put("radius", radius), "none found in loaded chunks",
			"explore elsewhere, or mine with search=true");
	}

	private static ToolResult findBlocks(Agent a, Minecraft mc, Map<String, List<Block>> groups, int r, int max, int minY, int maxY, boolean sourceOnly) {
		List<String> missing = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		Vec3 me = mc.player.position();
		for (var en : groups.entrySet()) {
			List<BlockPos> found = Moves.scan(mc, en.getValue(), r, max, minY, maxY, sourceOnly ? p -> mc.level.getFluidState(p).isSource() : null);
			if (found.isEmpty()) {
				missing.add(en.getKey());
				continue;
			}
			if (!sb.isEmpty()) sb.append('\n');
			sb.append(Kv.of().put("type", en.getKey()).put("found", found.size()));
			for (BlockPos p : found) {
				BlockState st = mc.level.getBlockState(p);
				sb.append("\n- ").append(Fmt.rel(Kv.of(RuleGuard.shortId(st)).put("pos", Fmt.pos(p)), me, Vec3.atCenterOf(p))
					.put("owner", Fmt.owner(a, p, st)));
			}
		}
		if (sb.isEmpty()) {
			if (minY == Integer.MIN_VALUE && maxY == Integer.MAX_VALUE && !sourceOnly) return notFoundBlocks(String.join(",", missing), r);
			return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("type", String.join(",", missing)).put("radius", r)
				.putIf(minY != Integer.MIN_VALUE, "min_y", minY).putIf(maxY != Integer.MAX_VALUE, "max_y", maxY)
				.putIf(sourceOnly, "source_only", true), "none found in loaded chunks matching the filters");
		}
		if (!missing.isEmpty()) sb.append('\n').append(Kv.of().put("not_found", String.join(",", missing)).put("radius", r));
		return ToolResult.ok(sb.toString());
	}

	private static CompletableFuture<ToolResult> useBlock(Agent a, Minecraft mc, BlockPos p) {
		if (!Moves.canReach(mc.player, p)) return CompletableFuture.completedFuture(tooFar(p));
		BlockState st = mc.level.getBlockState(p);
		if (st.is(Blocks.LECTERN) && st.getValue(LecternBlock.HAS_BOOK)) {
			return CompletableFuture.completedFuture(a.tasks.start(new LecternReadTask(a, p), false, null));
		}
		Kv at = Kv.of().put("block", RuleGuard.shortId(st)).put("pos", Fmt.pos(p));
		if (a.tracker.placementPending(p)) {
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.BUSY, at, "still confirming a block change here"));
		}
		long mark = a.guard.blockMark();
		var r = Moves.useOn(mc, p, Moves.faceToward(mc.player, p));
		var blocked = a.guard.blockedAfter(mark);
		if (blocked != null) return CompletableFuture.completedFuture(ToolResult.error(blocked.code(), at, blocked.reason(), blocked.hint()));
		if (!r.consumesAction()) {
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.INVALID_ARGUMENT, at, "right-click had no effect"));
		}
		BlockState now = mc.level.getBlockState(p);
		// 拉桿的 POWERED 只在伺服器更新；按鈕可能在網路同步前仍維持未按下的狀態。
		if (now == st && (st.is(Blocks.LEVER) || st.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock
			&& !st.getValue(BlockStateProperties.POWERED))) {
			return a.tracker.confirmPlacement(mc.level, p).thenApply(state -> state == null
				? ToolResult.ok(Kv.of("OK used").add(at).put("state_pending", true))
				: usedBlock(at, state));
		}
		return CompletableFuture.completedFuture(usedBlock(at, now));
	}

	private static ToolResult usedBlock(Kv at, BlockState state) {
		Kv result = Kv.of("OK used").add(at).put("now", RuleGuard.shortId(state));
		for (var property : List.of(BlockStateProperties.OPEN, BlockStateProperties.POWERED, BlockStateProperties.LIT)) {
			if (state.hasProperty(property)) result.put(property.getName(), state.getValue(property));
		}
		return ToolResult.ok(result);
	}

	private static CompletableFuture<ToolResult> placeBlock(Agent a, Minecraft mc, BlockPos p, Item item, boolean replace) {
		if (!(item instanceof BlockItem bi)) {
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(item)), "not a block item"));
		}
		// 特殊 BlockItem 和雙格方塊(含 MC 26.3 的 straw_bed、pitcher_plant)會決定附著面或連帶佔用第二格，沿用原版放置。
		boolean direct = bi.getClass() == BlockItem.class
			&& RuleGuard.companionCell(bi.getBlock().defaultBlockState(), BlockPos.ZERO) == null
			&& BuildCommands.available(mc);
		if (!BuildCommands.validTarget(mc.level, p)) return CompletableFuture.completedFuture(invalidBlockTarget(p));
		BlockState cur = mc.level.getBlockState(p);
		direct &= BuildCommands.directAllowed(mc, cur, bi);
		if (!direct && !Moves.canReach(mc.player, p)) return CompletableFuture.completedFuture(tooFar(p));
		RuleGuard.Verdict v = a.guard.canPlaceInto(mc.level, p);
		if (!v.allowed()) return CompletableFuture.completedFuture(ToolResult.error(v.code(), Kv.of().put("pos", Fmt.pos(p)), v.reason(), v.hint()));
		if (!cur.isAir() && !cur.canBeReplaced()) {
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.INVALID_ARGUMENT,
				Kv.of().put("pos", Fmt.pos(p)).put("block", RuleGuard.shortId(cur)), "position is occupied", "break_block first if it is yours or natural"));
		}
		if (direct) {
			BlockState desired = bi.getBlock().defaultBlockState();
			if (cur == desired) return CompletableFuture.completedFuture(ToolResult.ok(Kv.of("OK already").put("item", Inv.id(item)).put("pos", Fmt.pos(p))));
			return CompletableFuture.completedFuture(a.tasks.start(new CommandBlockTask(a, mc.level, p, cur, desired), replace, null));
		}
		// 不貼箱子、工作台、門、按鈕這類方塊：沒蹲下右鍵會打開介面或切換方塊(P2-1，跟 facing 共用判斷)
		Direction support = null;
		for (Direction d : Direction.values()) {
			if (Facing.canPlaceAgainst(mc.level, mc.level.getBlockState(p.relative(d)), p.relative(d))) {
				support = d;
				break;
			}
		}
		if (support == null) {
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("pos", Fmt.pos(p)),
				"no adjacent solid, non-interactive block to place against", "place a supporting block first"));
		}
		if (a.tracker.placementPending(p)) {
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.BUSY, Kv.of().put("pos", Fmt.pos(p)),
				"still confirming the previous placement here", "retry in a moment"));
		}
		int idx = Inv.findBestWithOffhand(mc.player, item, ItemMatcher.ANY);
		if (idx < 0) return CompletableFuture.completedFuture(missingItem(item, 0, 1));
		// 不是背景任務，但會搶快捷列和視角：照樣尊重進行中的任務和 replace(P2-5)
		ToolResult busy = a.tasks.claim("place_block", replace);
		if (busy != null) return CompletableFuture.completedFuture(busy);
		if ((idx >= 9 || idx == Inv.OFFHAND_SLOT) && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		if (!Inv.selectInHandWithOffhand(mc, s -> s.is(item))) {
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(item)),
				"could not take the item into the hand"));
		}
		BlockPos against = p.relative(support);
		var menu = mc.player.containerMenu;
		long mark = a.guard.blockMark();
		var r = Moves.useOn(mc, against, support.getOpposite());
		if (!r.consumesAction()) {
			var blocked = a.guard.blockedAfter(mark);
			if (blocked != null) return CompletableFuture.completedFuture(ToolResult.error(blocked.code(), Kv.of().put("pos", Fmt.pos(p)), blocked.reason(), blocked.hint()));
			return CompletableFuture.completedFuture(ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("pos", Fmt.pos(p))
				.put("against", Fmt.pos(against)), "could not place (that face does not accept it)"));
		}
		BlockState predicted = mc.level.getBlockState(p);
		Block expected = predicted != cur && !predicted.isAir() ? predicted.getBlock() : bi.getBlock();
		return a.tracker.confirmPlacement(mc.level, p).thenApply(state -> {
			Kv at = Kv.of().put("item", Inv.id(item)).put("pos", Fmt.pos(p));
			if (state != null && state.is(expected)) return ToolResult.ok(Kv.of("OK placed").add(at).put("state", RuleGuard.shortId(state)));
			// 若右鍵意外打開了貼放面的容器介面，伺服器不會送出目標格更新(state 會是 null)，須在 TIMEOUT 前先檢查並關閉(P3-M6)
			LocalPlayer player = mc.player;
			if (player != null && player.containerMenu != menu) {
				mc.execute(() -> {
					if (mc.player == player && player.containerMenu != player.inventoryMenu) player.closeContainer();
				});
				return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("opened", Fmt.pos(against)),
					"right-click opened the block it was placed against", "pick a position next to a non-interactive block");
			}
			if (state == null) return ToolResult.error(ErrorCode.TIMEOUT, at, "no server confirmation for the placement", "inspect_area to check");
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("now", RuleGuard.shortId(state)), "block was not placed");
		});
	}

	/** place_block 指定 facing/half/sneak(A6、S4.2b)：創造模式能用指令時直接寫進狀態，否則模擬鄰格與點擊高度。 */
	private static ToolResult placeStructured(Agent a, Minecraft mc, BlockPos p, Item item, Direction facing, String half, boolean sneak, boolean replace) {
		Kv at = Kv.of().put("item", Inv.id(item)).put("pos", Fmt.pos(p))
			.put("facing", facing == null ? null : facing.getSerializedName())
			.put("half", half)
			.putIf(sneak, "sneak", true);
		if (!(item instanceof BlockItem bi)) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at, "not a block item");
		BlockState def = bi.getBlock().defaultBlockState();
		if (facing != null && Facing.of(def) == null && !(bi instanceof net.minecraft.world.item.StandingAndWallBlockItem)) {
			return noFacing(at, def);
		}
		if (half != null && !Facing.hasHalf(def)) return noHalf(at, def);
		if (!BuildCommands.validTarget(mc.level, p)) return invalidBlockTarget(p);
		BlockState cur = mc.level.getBlockState(p);
		RuleGuard.Verdict v = a.guard.canPlaceInto(mc.level, p);
		if (!v.allowed()) return ToolResult.error(v.code(), at, v.reason(), v.hint());
		if (!cur.isAir() && !cur.canBeReplaced()) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("block", RuleGuard.shortId(cur)), "position is occupied",
				"break_block first if it is yours or natural");
		}
		boolean direct = bi.getClass() == BlockItem.class
			&& RuleGuard.companionCell(def, BlockPos.ZERO) == null
			&& BuildCommands.available(mc) && BuildCommands.directAllowed(mc, cur, bi);
		if (direct) {
			BlockState desired = def;
			if (facing != null) {
				desired = Facing.apply(desired, facing);
				if (desired == null) {
					return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("block", RuleGuard.shortId(def)).put("possible", Facing.possible(def)),
						"this block cannot face " + facing.getSerializedName(), "pick one of possible");
				}
			}
			if (half != null) {
				desired = Facing.applyHalf(desired, half);
				if (desired == null) {
					return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("block", RuleGuard.shortId(def)).put("possible", "bottom,top"),
						"this block cannot have half=" + half, "pick bottom or top");
				}
			}
			if (cur == desired) return ToolResult.ok(Kv.of("OK already").add(at));
			return a.tasks.start(new CommandBlockTask(a, mc.level, p, cur, desired), replace, null);
		}
		if (!Moves.canReach(mc.player, p)) return tooFar(p);
		if (a.tracker.placementPending(p)) {
			return ToolResult.error(ErrorCode.BUSY, at, "still confirming the previous placement here", "retry in a moment");
		}
		if (Inv.countWithOffhand(mc.player, item) == 0) return missingItem(item, 0, 1);
		return switch (Facing.solve(mc.level, mc.player, p, bi, facing, half, sneak)) {
			case Facing.Found f -> a.tasks.start(new PlaceTask(a, mc.level, p, item, facing, half, sneak, f.plan()), replace,
				Kv.of().put("against", Fmt.pos(f.plan().hit().getBlockPos())).put("click", f.plan().hit().getDirection().getSerializedName()).toString());
			case Facing.NoFacing _ -> noFacing(at, def);
			case Facing.NoHalf _ -> noHalf(at, def);
			case Facing.NoWay w -> ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.putIf(!w.possible().isEmpty(), "possible", w.possible()),
				facing != null || half != null ? "no adjacent face here can give that placement state"
					: "no adjacent block face here can accept this block",
				sneak ? "place a supporting block on the needed side, or pick another state"
					: "use sneak=true for interactive neighbors, or place a supporting block first");
		};
	}

	private static ToolResult noFacing(Kv at, BlockState st) {
		return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("block", RuleGuard.shortId(st)), "this block has no facing property",
			"omit facing");
	}

	private static ToolResult noHalf(Kv at, BlockState st) {
		return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("block", RuleGuard.shortId(st)), "this block has no half property",
			"omit half");
	}

	/** use_item：不給座標且為成書/書與羽毛筆時直接讀內容(S3.2a)；其餘對方塊用或直接使用(支援副手與 match，S4.2d)。 */
	static ToolResult useItem(Agent a, Minecraft mc, Item item, BlockPos pos, ItemMatcher matcher, int page) {
		LocalPlayer p = mc.player;
		if (pos == null && (item == Items.WRITTEN_BOOK || item == Items.WRITABLE_BOOK)) {
			int idx = Inv.findBestWithOffhand(p, item, matcher);
			if (idx < 0) return missingItem(item, 0, 1, matcher);
			ItemStack stack = Inv.stackAt(p, idx);
			try {
				var view = Fmt.readBook(stack, page);
				return ToolResult.ok(Kv.of("OK read").put("item", Inv.id(item)).add(Fmt.bookKv(view)));
			} catch (IllegalArgumentException e) {
				return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(item)).put("page", page), e.getMessage());
			}
		}
		if (Inv.countWithOffhand(p, item, matcher) == 0) return missingItem(item, 0, 1, matcher);
		if (pos != null && !BuildCommands.validTarget(mc.level, pos)) return invalidBlockTarget(pos);
		if (pos != null && !Moves.canReach(p, pos)) return tooFar(pos);
		if (p.containerMenu != p.inventoryMenu) p.closeContainer();
		if (!Inv.selectBestInHand(mc, item, matcher)) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(item)), "could not take the item into the hand");
		}
		var before = Inv.summaryWithOffhand(p);
		BlockState was = pos == null ? null : mc.level.getBlockState(pos);
		long mark = a.guard.blockMark();
		net.minecraft.world.InteractionResult r;
		if (pos != null) {
			r = Moves.useOnWithShieldBypass(mc, pos, Moves.exposedFaceToward(mc, pos));
			if (!r.consumesAction() && a.guard.blockedAfter(mark) == null) r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		} else {
			r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		}
		Kv at = Kv.of().put("item", Inv.id(item)).put("pos", pos == null ? null : Fmt.pos(pos)).put("block", was == null ? null : RuleGuard.shortId(was));
		var blocked = a.guard.blockedAfter(mark);
		if (blocked != null) return ToolResult.error(blocked.code(), at, blocked.reason(), blocked.hint());
		if (!r.consumesAction() && item == Items.BUCKET && was != null && was.is(Blocks.WATER) && !was.getFluidState().isSource()) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("level", was.getFluidState().getAmount()),
				"water here is flowing (not a source block)", "target a water source block (inspect_area shows water[level=0])");
		}
		if (!r.consumesAction()) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at, "using the item had no effect");
		p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		String change = Inv.diff(before, Inv.summaryWithOffhand(p));
		return ToolResult.ok(Kv.of("OK used").add(at).putIf(!change.isEmpty(), "inv_change", change));
	}

	/** eat：未指定 item 挑營養最高的安全食物；指定 item 支援所有帶 CONSUMABLE 的食物與飲品(S4.2c)。 */
	private static ToolResult startEat(Agent a, Minecraft mc, Item item, ItemMatcher matcher, boolean replace) {
		LocalPlayer p = mc.player;
		Item chosenItem = item;
		ItemStack chosenStack = ItemStack.EMPTY;
		if (chosenItem == null) {
			int best = -1;
			for (int i = 0; i < Inv.MAIN_SIZE + 1; i++) {
				ItemStack s = i < Inv.MAIN_SIZE ? p.getInventory().getItem(i) : p.getOffhandItem();
				if (s.isEmpty() || BAD_FOOD.contains(s.getItem())) continue;
				var fp = s.get(DataComponents.FOOD);
				if (fp == null) continue;
				if (!matcher.isAny() && !matcher.matches(Inv.infoOf(s))) continue;
				if (fp.nutrition() > best) {
					best = fp.nutrition();
					chosenItem = s.getItem();
					chosenStack = s;
				}
			}
			if (chosenItem == null) {
				Kv kv = Kv.of().putIf(!matcher.isAny(), "match", matcher.raw());
				return ToolResult.error(ErrorCode.MISSING_MATERIALS, kv, "no food in inventory", "hunt, fish, or harvest crops");
			}
		} else {
			int idx = Inv.findBestWithOffhand(p, chosenItem, matcher);
			if (idx < 0) return missingItem(chosenItem, 0, 1, matcher);
			chosenStack = Inv.stackAt(p, idx);
		}
		Consumable consumable = chosenStack.get(DataComponents.CONSUMABLE);
		if (consumable == null) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(chosenItem)), "not a consumable item");
		}
		if (!consumable.canConsume(p, chosenStack)) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("item", Inv.id(chosenItem)),
				"cannot consume right now (food bar is full)");
		}
		return a.tasks.start(new EatTask(a, chosenItem, matcher, consumable), replace, null);
	}

	/**
	 * 目的地被怪物圍住(在遠程怪射程或近戰怪 8 格內)：尋路會盡量繞開，但目的地本身繞不開，先告訴 AI(S9.1b)。
	 */
	static void warnHostileDestination(Agent a, Minecraft mc, int x, Integer y, int z) {
		String near = a.danger.nearDestination(mc, a.observation.recentAttacker(mc, 100), x, y, z, a.tick());
		if (near.isEmpty()) return;
		a.events.add("destination_hostile", blockagent.core.Event.Level.PROMPT, Kv.of().put("target", x + "," + (y == null ? "~" : y) + "," + z)
			.put("hostiles", near) + " | hint=hostiles are around the destination; wait for daylight, clear them with good gear, or go somewhere else");
	}

	static ToolResult unequip(Minecraft mc, String slot) {
		EquipmentSlot target = switch (slot) {
			case "offhand" -> EquipmentSlot.OFFHAND;
			case "head" -> EquipmentSlot.HEAD;
			case "chest" -> EquipmentSlot.CHEST;
			case "legs" -> EquipmentSlot.LEGS;
			case "feet" -> EquipmentSlot.FEET;
			default -> null;
		};
		if (target == null) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("slot", slot),
				"item=none requires slot=offhand, head, chest, legs or feet");
		}
		LocalPlayer p = mc.player;
		ItemStack cur = p.getItemBySlot(target);
		if (cur.isEmpty()) return ToolResult.ok(Kv.of("OK already_empty").put("slot", slot));
		if (Inv.freeSlots(p) == 0) {
			return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0).put("slot", slot),
				"main inventory is full", "drop or store an item first");
		}
		String removed = Inv.id(cur);
		if (!Inv.unequip(mc, target)) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("slot", slot), "could not unequip item");
		}
		return ToolResult.ok(Kv.of("OK unequipped").put("item", removed).put("slot", slot));
	}

	static ToolResult equip(Minecraft mc, Item item, String slot, ItemMatcher matcher) {
		LocalPlayer p = mc.player;
		EquipmentSlot target = switch (slot) {
			case "mainhand" -> EquipmentSlot.MAINHAND;
			case "offhand" -> EquipmentSlot.OFFHAND;
			case "armor" -> {
				var eq = new ItemStack(item).get(DataComponents.EQUIPPABLE);
				yield eq == null || eq.slot() == EquipmentSlot.MAINHAND || eq.slot() == EquipmentSlot.OFFHAND ? null : eq.slot();
			}
			case "head" -> EquipmentSlot.HEAD;
			case "chest" -> EquipmentSlot.CHEST;
			case "legs" -> EquipmentSlot.LEGS;
			case "feet" -> EquipmentSlot.FEET;
			default -> null;
		};
		Kv what = Kv.of().put("item", Inv.id(item)).put("slot", slot).putIf(!matcher.isAny(), "match", matcher.raw());
		if (target == null) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, what, "slot=armor needs a wearable item");
		if (target.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
			var eq = new ItemStack(item).get(DataComponents.EQUIPPABLE);
			if (eq == null || eq.slot() != target) {
				return ToolResult.error(ErrorCode.INVALID_ARGUMENT, what, "item cannot be worn in slot=" + slot);
			}
		}
		ItemStack equipped = p.getItemBySlot(target);
		boolean alreadyMatches = Inv.matches(equipped, item, matcher);
		int bestIdx = target == EquipmentSlot.OFFHAND
			? Inv.findBest(p, item, matcher)
			: Inv.findBestWithOffhand(p, item, matcher);
		boolean betterInInv = bestIdx >= 0 && alreadyMatches && matcher.durPref() != ItemMatcher.DurPref.NONE
			&& matcher.comparePreference(Inv.infoOf(Inv.stackAt(p, bestIdx)), Inv.infoOf(equipped)) < 0;
		if (!alreadyMatches || betterInInv) {
			if (bestIdx < 0) return missingItem(item, 0, 1, matcher);
			if (bestIdx == Inv.OFFHAND_SLOT) {
				if (p.containerMenu != p.inventoryMenu) p.closeContainer();
				if (target == EquipmentSlot.MAINHAND) {
					Inv.swapToOffhand(mc, p.getInventory().getSelectedSlot());
				} else {
					if (!Inv.pullOffhandToMain(mc, s -> false)) {
						return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0),
							"item is in the offhand and the main inventory is full");
					}
					int idx = Inv.findBest(p, item, matcher);
					if (idx >= 0) Inv.wear(mc, idx);
				}
			} else {
				if ((target != EquipmentSlot.MAINHAND || bestIdx >= 9) && p.containerMenu != p.inventoryMenu) p.closeContainer();
				switch (target) {
					case MAINHAND -> Inv.selectSlotInHand(mc, bestIdx);
					case OFFHAND -> Inv.swapToOffhand(mc, bestIdx);
					default -> Inv.wear(mc, bestIdx);
				}
			}
		}
		if (!Inv.matches(p.getItemBySlot(target), item, matcher)) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, what.put("now", Inv.id(p.getItemBySlot(target))), "item was not equipped");
		}
		return ToolResult.ok(Kv.of("OK equipped").add(what));
	}

	static ToolResult attack(Agent a, Minecraft mc, String target) {
		LocalPlayer p = mc.player;
		Entity e = resolveEntity(mc, target, 16, x -> x instanceof LivingEntity);
		if (e == null || !e.isAlive()) return entityNotFound(target, 16);
		Kv who = Kv.of().put("target", Fmt.entityId(e)).put("id", e.getId());
		RuleGuard.Verdict v = a.guard.canAttack(e);
		if (!v.allowed()) return ToolResult.error(v.code(), who, v.reason(), v.hint());
		if (!p.isWithinEntityInteractionRange(e, 0)) {
			return ToolResult.error(ErrorCode.PATH_BLOCKED, who.put("dist", Math.round(e.distanceTo(p))), "out of reach",
				"hunt it, or goto closer first");
		}
		Moves.lookAt(p, e.getBoundingBox().getCenter());
		mc.gameMode.attack(p, e);
		p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		return ToolResult.ok(Kv.of("OK hit").add(who).put("hp_left", e instanceof LivingEntity le ? Math.round(le.getHealth()) : null));
	}

	private static ToolResult sleep(Agent a, Minecraft mc, BlockPos chosen, boolean replace) {
		BlockPos bed;
		if (chosen != null) {
			BlockState st = mc.level.getBlockState(chosen);
			Kv at = Kv.of().put("pos", Fmt.pos(chosen));
			if (!st.is(BlockTags.BEDS)) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at.put("block", RuleGuard.shortId(st)), "not a bed");
			if (Math.sqrt(chosen.distSqr(mc.player.blockPosition())) > 32) {
				return ToolResult.error(ErrorCode.PATH_BLOCKED, at, "bed is more than 32 blocks away", "goto closer first");
			}
			if (!Observation.bedUsable(mc.level, chosen, st)) {
				return ToolResult.error(ErrorCode.INVALID_ARGUMENT, at, "this bed cannot be used safely here");
			}
			if (st.getValue(BedBlock.OCCUPIED)) return ToolResult.error(ErrorCode.NO_STATION, at, "bed is occupied", "pick another bed");
			bed = chosen;
		} else {
			List<BlockPos> beds = Moves.scan(mc, blocksInTag(BlockTags.BEDS), 32, 16, null);
			if (beds.isEmpty() && !Observation.bedUsable(mc.level.environmentAttributes().getValue(
				EnvironmentAttributes.BED_RULE, mc.player.blockPosition()))) {
				return ToolResult.error(ErrorCode.INVALID_ARGUMENT, "beds cannot be used safely in this dimension");
			}
			if (beds.isEmpty()) return ToolResult.error(ErrorCode.NO_STATION, Kv.of().put("radius", 32), "no bed nearby", "craft a bed (3 wool + 3 planks)");
			bed = beds.stream().filter(b -> {
				BlockState state = mc.level.getBlockState(b);
				return Observation.bedUsable(mc.level, b, state) && !state.getValue(BedBlock.OCCUPIED);
			}).findFirst().orElse(null);
			if (bed == null) return ToolResult.error(ErrorCode.NO_STATION, Kv.of().put("radius", 32), "no safe, unoccupied bed nearby");
		}
		var bedRule = Observation.bedRule(mc.level, bed, mc.level.getBlockState(bed));
		if (!bedRule.canSleep(mc.level)) {
			if (!mc.level.dimension().equals(Level.OVERWORLD)) {
				return ToolResult.error(ErrorCode.INVALID_ARGUMENT, "this bed's rules do not allow sleep right now");
			}
			String sleepIn = blockagent.core.DayClock.sleepCountdown(mc.level.getOverworldClockTime());
			Kv data = Kv.of().raw(Observation.timeOfDay(mc));
			if (sleepIn != null) data.raw(sleepIn);
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, data,
				"can only sleep at night or in a thunderstorm", "do something else, or wait_events until can_sleep");
		}
		return a.tasks.start(new StationTask(a, StationTask.Mode.SLEEP, bed, List.of(), null), replace,
			Kv.of().put("bed", Fmt.pos(bed)).put("owner", Fmt.owner(a, bed, mc.level.getBlockState(bed))).toString());
	}

	private static ToolResult smeltInspectOrTake(Agent a, Minecraft mc, SmeltTask.Mode mode, BlockPos pos, boolean replace) {
		if (!BuildCommands.validTarget(mc.level, pos)) return invalidBlockTarget(pos);
		BlockState st = mc.level.getBlockState(pos);
		if (!SmeltTask.isFurnaceBlock(st)) {
			return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("pos", Fmt.pos(pos)).put("block", RuleGuard.shortId(st)),
				"not a furnace, blast_furnace or smoker", "find_blocks type=furnaces");
		}
		return a.tasks.start(new SmeltTask(a, mode, pos), replace,
			Kv.of().put("furnace", Fmt.pos(pos)).put("furnace_type", RuleGuard.shortId(st)).toString());
	}

	static ToolResult smelt(Agent a, Minecraft mc, Item item, int count, Item fuelArg, boolean replace) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		Recipes.Recipe r = a.recipes.cookingFor(id);
		Item input = item;
		if (r == null) {
			// 給的是產物：找背包(含副手)裡有的原料，優先選數量夠的，都不夠就選最多的(M10、S4.2d)
			int best = 0;
			for (Recipes.Recipe cand : a.recipes.cookingRecipesFor(id)) {
				for (String opt : cand.grid().getFirst().options()) {
					Item in = parseItem(opt);
					int have = Inv.countWithOffhand(mc.player, in);
					if (have > best && (best < count || r == null)) {
						r = cand;
						input = in;
						best = have;
					}
				}
			}
			if (r == null) {
				Kv what = Kv.of().put("item", Inv.id(item));
				if (a.recipes.cookingRecipesFor(id).isEmpty()) return ToolResult.error(ErrorCode.INVALID_ARGUMENT, what, "cannot be smelted");
				return ToolResult.error(ErrorCode.MISSING_MATERIALS, what, "no input in inventory that smelts into it");
			}
		}
		Item result = parseItem(r.result());
		int haveInput = Inv.countWithOffhand(mc.player, input);
		if (haveInput < count) return missingItem(input, haveInput, count);
		Item fuel = fuelArg;
		if (fuel != null && !fuel.getDefaultInstance().has(DataComponents.COOKING_FUEL)) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("fuel", Inv.id(fuel)), "not a furnace fuel",
				"omit fuel to pick one automatically, or use coal, charcoal, logs or planks");
		}
		if (fuel == null) {
			int best = 0;
			for (int i = 0; i < Inv.MAIN_SIZE + 1; i++) {
				ItemStack s = i < Inv.MAIN_SIZE ? mc.player.getInventory().getItem(i) : mc.player.getOffhandItem();
				if (s.isEmpty() || !s.has(DataComponents.COOKING_FUEL) || s.getItem() == input) continue;
				int burn = Recipes.burnTicks(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), true) * Inv.countWithOffhand(mc.player, s.getItem());
				if (burn > best) {
					best = burn;
					fuel = s.getItem();
				}
			}
			// 沒有其他燃料時，若原料本身可燃且扣掉要燒的數量後仍有餘量(例如只帶原木燒木炭)，允許用剩餘原料當燃料(S4.2e)
			if (fuel == null && input.getDefaultInstance().has(DataComponents.COOKING_FUEL)) {
				int fallbackNeed = SmeltTask.fuelNeeded(input, count);
				if (WorkstationPolicy.canUseInputAsFallbackFuel(true, haveInput, count, fallbackNeed)) {
					fuel = input;
				}
			}
			if (fuel == null) return ToolResult.error(ErrorCode.MISSING_MATERIALS, "no fuel in inventory", "get coal, charcoal, logs or planks");
		}
		int need = SmeltTask.fuelNeeded(fuel, count);
		int haveFuel = Inv.countWithOffhand(mc.player, fuel) - (fuel == input ? count : 0);
		if (haveFuel < need) return missingItem(fuel, haveFuel, need);
		String inputId = BuiltInRegistries.ITEM.getKey(input).toString();
		List<Recipes.FurnaceKind> supported = a.recipes.supportedFurnaces(inputId);
		BlockPos furnace = SmeltTask.findFurnace(a, mc, supported, Set.of());
		boolean canPlaceFurnace = supported.contains(Recipes.FurnaceKind.FURNACE) && Inv.countWithOffhand(mc.player, Items.FURNACE) > 0;
		if (furnace == null && !canPlaceFurnace) {
			return ToolResult.error(ErrorCode.NO_STATION, Kv.of().put("radius", 32), "no furnace nearby or in inventory", "craft furnace (8 cobblestone)");
		}
		Set<Item> pullOffhandKeep = null;
		if (!mc.player.getOffhandItem().isEmpty()) {
			int mainInput = Inv.count(mc.player, input);
			int mainFuel = Inv.count(mc.player, fuel) - (fuel == input ? count : 0);
			boolean needOffFurnace = furnace == null && Inv.count(mc.player, Items.FURNACE) == 0;
			if (mainInput < count || mainFuel < need || needOffFurnace) {
				Set<Item> keep = new java.util.HashSet<>();
				keep.add(input);
				keep.add(fuel);
				if (furnace == null) keep.add(Items.FURNACE);
				if (Inv.findOffhandPullSlot(mc.player, s -> keep.contains(s.getItem())) < 0) {
					return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0),
						"a needed item is in the offhand and the main inventory is full", "drop or store one item, then smelt again");
				}
				pullOffhandKeep = keep;
			}
		}
		Kv pre = Kv.of().put("input", Inv.id(input) + ":" + count).put("fuel", Inv.id(fuel) + ":" + need)
			.put("furnace", furnace == null ? "from_inventory" : Fmt.pos(furnace));
		if (furnace != null) pre.put("furnace_type", RuleGuard.shortId(mc.level.getBlockState(furnace)));
		return a.tasks.start(new SmeltTask(a, input, result, count, fuel, need, furnace, supported, pullOffhandKeep), replace, pre.toString());
	}

	private static ToolResult clearArea(Agent a, Minecraft mc, AreaInspection.Region region, Set<Block> blocks, boolean replace) {
		var candidates = new LinkedHashMap<BlockPos, Block>();
		int protectedCount = 0;
		int unloaded = 0;
		for (BlockPos pos : region.positions()) {
			if (!BuildCommands.validTarget(mc.level, pos)) {
				unloaded++;
				continue;
			}
			BlockState state = mc.level.getBlockState(pos);
			if (state.isAir() || !blocks.contains(state.getBlock())) continue;
			if (ClearAreaTask.eligible(a, mc.level, pos, state)) candidates.put(pos.immutable(), state.getBlock());
			else protectedCount++;
		}
		Kv skipped = Kv.of().putIf(protectedCount > 0, "protected", protectedCount).putIf(unloaded > 0, "unloaded", unloaded);
		if (candidates.isEmpty()) {
			return ToolResult.ok(Kv.of("OK done").put("task", "clear_area").put("targets", 0).add(skipped)
				.put("note", "no matching natural blocks"));
		}
		return a.tasks.start(new ClearAreaTask(a, mc.level, candidates, protectedCount), replace,
			Kv.of().put("targets", candidates.size()).add(skipped).toString());
	}

	private static ToolResult build(Agent a, Minecraft mc, String name, String what, BuildPreflight.Design design, boolean replace) {
		var check = BuildPreflight.inspect(a, mc, design);
		int count = check.targets().size();
		Kv summary = Kv.of().put("fill", count).putIf(check.existing() > 0, "existing", check.existing());
		Map<BlockPos, BlockState> plants = new java.util.LinkedHashMap<>();
		for (BlockPos p : check.plants()) plants.put(p, check.previous().get(p));
		if (!check.plants().isEmpty()) {
			var names = check.plants().stream().map(check.previous()::get).map(RuleGuard::shortId).distinct().toList();
			summary.put("clear_plants", check.plants().size()).put("plants", String.join(",", names));
		}
		String skipped = check.skipped(5);
		if (!check.missing().isEmpty()) {
			int missing = check.missing().get(design.material());
			// 創造非指令模式只需要 1 個材料，不能用 fill - missing 反推持有量(P3-M7)
			int have = Inv.count(mc.player, design.material());
			return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("missing", Inv.id(design.material()) + ":" + missing)
				.put("fill", count).put("have", have).raw(skipped), "not enough materials");
		}
		if (count == 0) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().raw(skipped),
				skipped.isEmpty() ? "nothing to fill (already built)" : "no cell can be filled");
		}
		Block block = ((BlockItem) design.material()).getBlock();
		if (check.commands()) {
			return a.tasks.start(new CommandBuildTask(a, mc.level, name, what, block, check.previous()), replace, summary.raw(skipped).toString());
		}
		String scaffolds = BuildTask.scaffoldPlan(mc);
		return a.tasks.start(new BuildTask(a, name, what, design.bounds().min(), design.bounds().max(),
			check.targets().keySet(), block, plants, design.reserved()), replace,
			summary.raw(skipped).raw(scaffolds).toString());
	}

	static ToolResult tillAndPlant(Agent a, Minecraft mc, BlockPos c1, BlockPos c2, Item seed, boolean replace) {
		if (Inv.find(mc.player, s -> s.is(ItemTags.HOES)) < 0) {
			return ToolResult.error(ErrorCode.MISSING_TOOL, Kv.of().put("need", "hoe"), "no hoe in inventory", "craft wooden_hoe or better");
		}
		int y = Math.min(c1.getY(), c2.getY());
		AreaInspection.Region.between(c1.atY(y), c2.atY(y)).volume(); // 太大的區域會卡住 client thread(H12)
		List<BlockPos> ground = new ArrayList<>();
		int skipped = 0, others = 0;
		for (BlockPos p : BlockPos.betweenClosed(new BlockPos(c1.getX(), y, c1.getZ()), new BlockPos(c2.getX(), y, c2.getZ()))) {
			BlockState st = mc.level.getBlockState(p);
			boolean tillable = st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT_PATH) || (st.is(Blocks.FARMLAND) && a.guard.isOwned(p));
			if (!tillable || !mc.level.getBlockState(p.above()).isAir()) {
				skipped++;
				continue;
			}
			if (!st.is(Blocks.FARMLAND) && !a.guard.isNatural(st) && !a.guard.isOwned(p)) {
				others++;
				continue;
			}
			ground.add(p.immutable());
		}
		if (ground.isEmpty()) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("y", y).putIf(others > 0, "others", others),
				"no tillable dirt or grass with air above at this y", "set y to the ground level");
		}
		int seeds = Inv.count(mc.player, seed);
		if (seeds < ground.size()) return missingItem(seed, seeds, ground.size());
		boolean water = false;
		for (BlockPos p : BlockPos.betweenClosed(new BlockPos(Math.min(c1.getX(), c2.getX()) - 4, y - 1, Math.min(c1.getZ(), c2.getZ()) - 4),
			new BlockPos(Math.max(c1.getX(), c2.getX()) + 4, y, Math.max(c1.getZ(), c2.getZ()) + 4))) {
			if (mc.level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
				water = true;
				break;
			}
		}
		Kv pre = Kv.of().put("plant", ground.size()).putIf(skipped > 0, "skipped", skipped).putIf(others > 0, "others", others)
			.putIf(!water, "note", "no water within 4 blocks; farmland will dry out");
		return a.tasks.start(new FarmTask(a, FarmTask.Mode.TILL_AND_PLANT, ground, seed, false), replace, pre.toString());
	}

	private static ItemMatcher parseNamedMatch(Args g, String key) {
		if (!g.has(key)) return ItemMatcher.ANY;
		try {
			return ItemMatcher.parse(g.str(key));
		} catch (IllegalArgumentException e) {
			throw new Args.BadArgument(e.getMessage());
		}
	}

	private static BlockPos optionalPos(Args g) {
		boolean hx = g.has("x"), hy = g.has("y"), hz = g.has("z");
		if (!hx && !hy && !hz) return null;
		if (!hx || !hy || !hz) throw new Args.BadArgument("x, y and z must all be provided together");
		return pos(g, "");
	}

	private static BlockPos findWorkstation(Agent a, Minecraft mc, List<Block> blocks, int radius) {
		List<BlockPos> own = Moves.scan(mc, blocks, radius, 1, a.guard::isOwned);
		if (!own.isEmpty()) return own.getFirst();
		List<BlockPos> any = Moves.scan(mc, blocks, radius, 1, null);
		return any.isEmpty() ? null : any.getFirst();
	}

	private static ToolResult enchant(Agent a, Minecraft mc, EnchantTask.Mode mode, Item item, ItemMatcher matcher,
									  int option, BlockPos target, boolean replace) {
		BlockPos station = target;
		if (station != null) {
			BlockState st = mc.level.getBlockState(station);
			if (!st.is(Blocks.ENCHANTING_TABLE)) {
				return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("pos", Fmt.pos(station)).put("block", RuleGuard.shortId(st)),
					"not an enchanting_table", "find_blocks type=enchanting_table");
			}
		} else {
			station = findWorkstation(a, mc, List.of(Blocks.ENCHANTING_TABLE), 32);
			if (station == null) {
				return ToolResult.error(ErrorCode.NO_STATION, Kv.of().put("radius", 32),
					"no enchanting_table within 32 blocks", "place an enchanting_table nearby");
			}
		}
		int haveItem = Inv.countWithOffhand(mc.player, item, matcher);
		if (haveItem < 1) return missingItem(item, 0, 1, matcher);
		boolean creative = Creative.isCreative(mc);
		if (mode == EnchantTask.Mode.APPLY && !creative) {
			int haveLapis = Inv.countWithOffhand(mc.player, Items.LAPIS_LAZULI);
			if (haveLapis < option) return missingItem(Items.LAPIS_LAZULI, haveLapis, option);
		}
		boolean needsOffhand = Inv.count(mc.player, item, matcher) < 1
			|| (mode == EnchantTask.Mode.APPLY && !creative && Inv.count(mc.player, Items.LAPIS_LAZULI) < option);
		java.util.function.Predicate<ItemStack> pullOffhandKeep = null;
		if (needsOffhand) {
			java.util.function.Predicate<ItemStack> keep = s -> Inv.matches(s, item, matcher)
				|| (mode == EnchantTask.Mode.APPLY && s.is(Items.LAPIS_LAZULI));
			if (Inv.findOffhandPullSlot(mc.player, keep) < 0) {
				return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0),
					"a needed item is in the offhand and the main inventory is full", "drop or store one item, then enchant again");
			}
			pullOffhandKeep = keep;
		}
		Kv pre = Kv.of().put("table", Fmt.pos(station)).put("owner", Fmt.owner(a, station, mc.level.getBlockState(station)));
		return a.tasks.start(new EnchantTask(a, mode, station, item, matcher, option, pullOffhandKeep), replace, pre.toString());
	}

	private static ToolResult anvil(Agent a, Minecraft mc, AnvilTask.Mode mode, Item item, ItemMatcher matcher,
									Item secondItem, ItemMatcher secondMatcher, String rename, BlockPos target, boolean replace) {
		List<Block> anvils = List.of(Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL);
		BlockPos station = target;
		if (station != null) {
			BlockState st = mc.level.getBlockState(station);
			if (!st.is(BlockTags.ANVIL)) {
				return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("pos", Fmt.pos(station)).put("block", RuleGuard.shortId(st)),
					"not an anvil", "find_blocks type=anvil");
			}
		} else {
			station = findWorkstation(a, mc, anvils, 32);
			if (station == null) {
				return ToolResult.error(ErrorCode.NO_STATION, Kv.of().put("radius", 32),
					"no anvil within 32 blocks", "craft and place an anvil nearby");
			}
		}
		ToolResult inputErr = checkTwoInputs(mc, item, matcher, secondItem, secondMatcher);
		if (inputErr != null) return inputErr;
		java.util.function.Predicate<ItemStack> pullOffhandKeep = checkOffhandPullForTwo(mc, item, matcher, secondItem, secondMatcher);
		if (pullOffhandKeep == OFFHAND_FULL_SENTINEL) {
			return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0),
				"a needed item is in the offhand and the main inventory is full", "drop or store one item, then retry anvil");
		}
		Kv pre = Kv.of().put("anvil", Fmt.pos(station)).put("owner", Fmt.owner(a, station, mc.level.getBlockState(station)));
		return a.tasks.start(new AnvilTask(a, mode, station, item, matcher, secondItem, secondMatcher, rename, pullOffhandKeep), replace, pre.toString());
	}

	private static ToolResult grindstone(Agent a, Minecraft mc, Item item, ItemMatcher matcher,
										 Item secondItem, ItemMatcher secondMatcher, BlockPos target, boolean replace) {
		BlockPos station = target;
		if (station != null) {
			BlockState st = mc.level.getBlockState(station);
			if (!st.is(Blocks.GRINDSTONE)) {
				return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("pos", Fmt.pos(station)).put("block", RuleGuard.shortId(st)),
					"not a grindstone", "find_blocks type=grindstone");
			}
		} else {
			station = findWorkstation(a, mc, List.of(Blocks.GRINDSTONE), 32);
			if (station == null) {
				return ToolResult.error(ErrorCode.NO_STATION, Kv.of().put("radius", 32),
					"no grindstone within 32 blocks", "craft and place a grindstone nearby");
			}
		}
		ToolResult inputErr = checkTwoInputs(mc, item, matcher, secondItem, secondMatcher);
		if (inputErr != null) return inputErr;
		java.util.function.Predicate<ItemStack> pullOffhandKeep = checkOffhandPullForTwo(mc, item, matcher, secondItem, secondMatcher);
		if (pullOffhandKeep == OFFHAND_FULL_SENTINEL) {
			return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0),
				"a needed item is in the offhand and the main inventory is full", "drop or store one item, then retry grindstone");
		}
		Kv pre = Kv.of().put("grindstone", Fmt.pos(station)).put("owner", Fmt.owner(a, station, mc.level.getBlockState(station)));
		return a.tasks.start(new GrindstoneTask(a, station, item, matcher, secondItem, secondMatcher, pullOffhandKeep), replace, pre.toString());
	}

	private static final java.util.function.Predicate<ItemStack> OFFHAND_FULL_SENTINEL = _ -> false;

	private static ToolResult checkTwoInputs(Minecraft mc, Item item, ItemMatcher matcher, Item secondItem, ItemMatcher secondMatcher) {
		int c1 = Inv.countWithOffhand(mc.player, item, matcher);
		if (c1 < 1) return missingItem(item, 0, 1, matcher);
		if (secondItem == null) return null;
		int c2 = Inv.countWithOffhand(mc.player, secondItem, secondMatcher);
		if (c2 < 1) return missingItem(secondItem, 0, 1, secondMatcher);
		int union = Inv.countWithOffhand(mc.player, s -> Inv.matches(s, item, matcher) || Inv.matches(s, secondItem, secondMatcher));
		if (!WorkstationPolicy.hasDistinctInputs(c1, c2, union)) {
			return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("item", Inv.id(item)).put("second_item", Inv.id(secondItem)),
				"need two distinct matching items in inventory");
		}
		return null;
	}

	private static java.util.function.Predicate<ItemStack> checkOffhandPullForTwo(Minecraft mc, Item item, ItemMatcher matcher,
																				   Item secondItem, ItemMatcher secondMatcher) {
		int main1 = Inv.count(mc.player, item, matcher);
		boolean needPull = main1 < 1;
		if (!needPull && secondItem != null) {
			int main2 = Inv.count(mc.player, secondItem, secondMatcher);
			int mainUnion = Inv.count(mc.player, s -> Inv.matches(s, item, matcher) || Inv.matches(s, secondItem, secondMatcher));
			needPull = !WorkstationPolicy.hasDistinctInputs(main1, main2, mainUnion);
		}
		if (!needPull) return null;
		java.util.function.Predicate<ItemStack> keep = s -> Inv.matches(s, item, matcher)
			|| (secondItem != null && Inv.matches(s, secondItem, secondMatcher));
		if (Inv.findOffhandPullSlot(mc.player, keep) < 0) return OFFHAND_FULL_SENTINEL;
		return keep;
	}

	private static ToolResult digTunnel(Agent a, Minecraft mc, TunnelPlanner.Mode mode, TunnelPlanner.Dir dir,
										int length, Integer targetY, int torchEvery, Set<Block> stopOn,
										ItemMatcher toolMatcher, boolean replace) {
		int startY = mc.player.blockPosition().getY();
		String err = TunnelPlanner.validate(mode, length, targetY, startY, torchEvery);
		if (err != null) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT,
				Kv.of().put("mode", mode == TunnelPlanner.Mode.STAIRS ? "stairs" : "straight").put("length", length).put("torch_every", torchEvery),
				err, torchEvery == 0 ? "pass length<=12 or bring torches with torch_every=8" : null);
		}
		int steps = TunnelPlanner.targetSteps(mode, length, targetY, startY);
		if (steps <= 0) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("y", startY), "nothing to dig");
		}
		boolean creative = Creative.isCreative(mc);
		if (!creative) {
			if (torchEvery > 0 && Inv.countWithOffhand(mc.player, Items.TORCH) == 0) {
				return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("missing", "torch:1"),
					"no torches in inventory",
					"craft torches (coal or charcoal from smelting logs), or pass torch_every=0 for a short tunnel (max 12)");
			}
			if (Inv.freeSlots(mc.player) == 0) {
				return ToolResult.error(ErrorCode.MISSING_MATERIALS, Kv.of().put("free_slots", 0), "inventory is full",
					"store items with chest action=put, or drop junk");
			}
			BlockState stone = Blocks.STONE.defaultBlockState();
			if (!toolMatcher.isAny()) {
				if (Inv.findBestMiningTool(mc.player, stone, toolMatcher, true) < 0) {
					return ToolResult.error(ErrorCode.MISSING_TOOL, Kv.of().put("tool_match", toolMatcher.raw()),
						"no suitable tool matching " + toolMatcher.raw() + " in inventory",
						"craft or equip a matching pickaxe, or omit tool_match");
				}
			} else if (Inv.findBestMiningTool(mc.player, stone, ItemMatcher.ANY, true) < 0) {
				return ToolResult.error(ErrorCode.MISSING_TOOL, Kv.of().put("need", "wooden_pickaxe+"),
					"no pickaxe in inventory", "craft a pickaxe first");
			}
		}
		Kv pre = Kv.of()
			.put("mode", mode == TunnelPlanner.Mode.STAIRS ? "stairs" : "straight")
			.put("direction", dir.name().toLowerCase(java.util.Locale.ROOT))
			.put("steps", steps)
			.put("torch_every", torchEvery);
		return a.tasks.start(new DigTunnelTask(a, mode, dir, steps, torchEvery, stopOn, toolMatcher), replace, pre.toString());
	}
}
