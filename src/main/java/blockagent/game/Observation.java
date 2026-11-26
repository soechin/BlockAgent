package blockagent.game;

import blockagent.core.CombatPolicy;
import blockagent.core.DayClock;
import blockagent.core.Event;
import blockagent.mcp.Kv;
import blockagent.game.reflex.Reflexes;
import blockagent.game.tasks.Moves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.monster.Enemy;
import blockagent.game.combat.Combat;
import blockagent.game.combat.Threats;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 交給 AI 的摘要(docs/SPEC.md S3)，以及受傷、天黑、怪物靠近等事件(S5.2)。
 */
public final class Observation {
	/** 一般摘要的上限(S3.1)。英文 KV 比中文長，但 token 少，所以字元數放寬。 */
	public static final int MAX_CHARS = 2400;

	/** 其他玩家最近的行為(S3.4)，保留 30 秒。 */
	private record Seen(String what, long tick) {
	}

	private final Agent agent;
	private final Map<Integer, Seen> breaking = new HashMap<>();
	private final List<long[]> placedByOthers = new ArrayList<>(); // {pos, tick}
	private final Set<Integer> reportedHostiles = new HashSet<>();
	private final Set<UUID> sleepingPlayers = new HashSet<>();
	private ResourceKey<Level> observedDimension;
	private float lastHealth = -1;
	/** 最近一次打自己的生物(entity id，-1 表示沒有)和 tick。 */
	private volatile int lastAttackerId = -1;
	private volatile long lastAttackedTick = -1000;

	public void onSelfDamaged(int causeId) {
		if (causeId >= 0) {
			lastAttackerId = causeId;
			lastAttackedTick = agent.tick();
		}
	}

	/** 最近 ticks 個 tick 內打過自己的生物，沒有就回傳 null。 */
	public Entity recentAttacker(Minecraft mc, int ticks) {
		if (lastAttackerId < 0 || agent.tick() - lastAttackedTick > ticks || mc.level == null) return null;
		return mc.level.getEntity(lastAttackerId);
	}

	/** 最近一次被打的攻擊者 id(無則 -1)與 tick(供自衛反射冷卻比對是否為收手後的新攻擊，P3-M2)。 */
	public int lastAttackerId() {
		return lastAttackerId;
	}

	public long lastAttackedTick() {
		return lastAttackedTick;
	}
	private boolean healthLowReported;
	private boolean nightReported;
	/** 已經發過 danger_rising，降回 low 之前不重發(S9.1c)。 */
	private boolean dangerReported;
	private boolean sleepReadyReported;
	/** 這次觀察到天黑過(黃昏到天亮前)，跨進白天時發一次 daybreak(M17)。 */
	private boolean sawDark;
	/** 上次執行 tick() 的 tick：暫停期間不會執行，恢復後的第一個 tick 不拿舊血量比(L5)。 */
	private long lastObservedTick = -1;

	Observation(Agent agent) {
		this.agent = agent;
	}

	// ---- 共用格式(S3.0、S3.2) ----

	record MobEntry(int id, int x, int y, int z, boolean baby) {
	}

	/** 格式化近處生物的個體座標清單：{@code 55@28,110,-89;57@28,110,-90:baby}。 */
	static String formatMobPositions(List<MobEntry> entries) {
		List<String> parts = new ArrayList<>(entries.size());
		for (MobEntry e : entries) {
			parts.add(e.id() + "@" + e.x() + "," + e.y() + "," + e.z() + (e.baby() ? ":baby" : ""));
		}
		return String.join(";", parts);
	}

	static String truncate(StringBuilder sb, int hidden, String hint) {
		String s = sb.toString();
		if (s.length() <= MAX_CHARS && hidden == 0) return s;
		if (s.length() > MAX_CHARS) s = s.substring(0, s.lastIndexOf('\n', MAX_CHARS) > 0 ? s.lastIndexOf('\n', MAX_CHARS) : MAX_CHARS);
		return s + "\n" + Kv.of().put("truncated", true).putIf(hidden > 0, "hidden", hidden) + " | hint=" + hint;
	}

	/** 遊戲內時間(S3.2)：{@code time=day dusk_in=5min weather=clear}，切點見 {@link DayClock}。 */
	public static String timeOfDay(Minecraft mc) {
		return DayClock.describe(mc.level.getOverworldClockTime()) + " weather=" + weather(mc);
	}

	static String weather(Minecraft mc) {
		return mc.level.isThundering() ? "thunder" : mc.level.isRaining() ? "rain" : "clear";
	}

	// ---- get_self(S3.2、M1.1、A5) ----

	/**
	 * 危險程度(S9.1c)：附近會不會生怪(夜裡在戶外，或在室內/地底而且腳下光照 0)、24 格內幾隻威脅、血量、備戰程度。
	 */
	public CombatPolicy.Danger danger(Minecraft mc) {
		return danger(mc, CombatPolicy.readiness(Combat.assess(mc.player)));
	}

	private CombatPolicy.Danger danger(Minecraft mc, CombatPolicy.Readiness r) {
		LocalPlayer p = mc.player;
		BlockPos feet = p.blockPosition();
		boolean outdoors = mc.level.canSeeSky(feet.above());
		boolean night = mc.level.dimension().equals(Level.OVERWORLD) && DayClock.phase(mc.level.getOverworldClockTime()).equals("night");
		boolean unlit = mc.level.getBrightness(LightLayer.BLOCK, feet) == 0 && mc.level.getBrightness(LightLayer.SKY, feet) <= 7;
		boolean dark = (outdoors && night) || (!outdoors && unlit);
		Entity attacker = recentAttacker(mc, 100);
		int hostiles = 0;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(24), e -> Threats.isThreat(e, attacker))) {
			if (e.distanceTo(p) <= 24) hostiles++;
		}
		return CombatPolicy.danger(dark, outdoors, hostiles, p.getHealth(), r.gear());
	}

	/** 例如 {@code danger=high reasons=dark,outdoors,gear_weak gear=weak missing=shield,armor}。 */
	private Kv dangerKv(Minecraft mc) {
		CombatPolicy.Readiness r = CombatPolicy.readiness(Combat.assess(mc.player));
		CombatPolicy.Danger d = danger(mc, r);
		return Kv.of().put("danger", d.label()).put("reasons", d.reasons().isEmpty() ? "none" : String.join(",", d.reasons()))
			.put("gear", r.label()).put("missing", r.missing().isEmpty() ? "none" : String.join(",", r.missing()));
	}

	public String self(Minecraft mc) {
		LocalPlayer p = mc.player;
		StringBuilder sb = new StringBuilder();
		BlockPos pos = p.blockPosition();
		Kv where = Kv.of().put("pos", Fmt.pos(pos)).put("dim", mc.level.dimension().identifier().getPath())
			.put("biome", biomeName(mc, pos)).put("face", Fmt.facing(p.getYRot()))
			.put("mode", Creative.modeName(mc.gameMode.getPlayerMode())).putIf(p.getAbilities().flying, "flying", true)
			.put("portal_cooldown", p.getPortalCooldown());
		if (p.isOnPortalCooldown()) where.put("portal_hint", "Stay outside the portal until portal_cooldown reaches 0 (ticks)");
		sb.append(where);
		if (Creative.isCreative(mc)) sb.append("\nnote=\"creative: broken blocks do not drop; take materials with creative_take\"");
		if (p.getAbilities().flying) sb.append("\nnote=\"flying; you land automatically before moving\"");
		if (p.getVehicle() != null) {
			var v = p.getVehicle();
			var driver = v.getControllingPassenger();
			sb.append('\n').append(Kv.of().put("riding", Fmt.entityId(v)).put("id", v.getId())
				.put("driver", driver == null ? "none" : driver == p ? "self" : driver.getName().getString())
				.put("note", "dismount before walking"));
		}
		sb.append('\n').append(Kv.of().put("hp", Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth()))
			.put("food", p.getFoodData().getFoodLevel() + "/20").put("sat", (double) p.getFoodData().getSaturationLevel())
			.put("air", p.getAirSupply() + "/" + p.getMaxAirSupply()).put("xp", p.experienceLevel));
		sb.append("\neffects=").append(Fmt.effects(p.getActiveEffects()));
		sb.append('\n').append(Kv.of().put("hand", Fmt.item(p.getMainHandItem())).put("offhand", Fmt.item(p.getOffhandItem())));
		sb.append("\narmor=head:").append(Fmt.item(p.getItemBySlot(EquipmentSlot.HEAD)))
			.append(",chest:").append(Fmt.item(p.getItemBySlot(EquipmentSlot.CHEST)))
			.append(",legs:").append(Fmt.item(p.getItemBySlot(EquipmentSlot.LEGS)))
			.append(",feet:").append(Fmt.item(p.getItemBySlot(EquipmentSlot.FEET)));
		if (!p.getAbilities().invulnerable) sb.append('\n').append(dangerKv(mc));
		sb.append('\n').append(Kv.of("inv").put("free", Inv.freeSlots(p)).put("items", Kv.counts(Inv.summary(p))));
		List<String> food = new ArrayList<>(), fuel = new ArrayList<>(), special = new ArrayList<>();
		for (int i = 0; i < Inv.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			if (s.has(DataComponents.FOOD) && !food.contains(Inv.id(s))) food.add(Inv.id(s));
			if (s.has(DataComponents.COOKING_FUEL) && !fuel.contains(Inv.id(s))) fuel.add(Inv.id(s));
			// 附魔、藥水、書這類同 id 也不一樣的東西另外列出(只有耐久的工具不算，手上的已經寫在 hand=)
			if (i != p.getInventory().getSelectedSlot() && special.size() < 8
				&& Fmt.props(s).stream().anyMatch(prop -> !prop.startsWith("dur="))) special.add(Fmt.item(s));
		}
		if (!special.isEmpty()) sb.append("\nspecial=").append(String.join(",", special));
		if (!food.isEmpty() || !fuel.isEmpty()) {
			sb.append('\n').append(Kv.of().putIf(!food.isEmpty(), "food_items", String.join(",", food))
				.putIf(!fuel.isEmpty(), "fuel_items", String.join(",", fuel)));
		}
		var task = agent.tasks.current();
		sb.append('\n').append(task == null ? "task=none" : Kv.of().put("task", task.name()).raw(task.progress()).toString());
		sb.append('\n').append(Kv.of().put("session", agent.session.state())
			.put("left_min", agent.session.hasTimeLimit() ? String.valueOf(agent.session.remainingMs() / 60_000) : "unlimited"));
		sb.append("\nreflexes=").append(agent.reflexes.summary());
		if (mc.level.dimension().equals(Level.OVERWORLD)) sb.append('\n').append(timeOfDay(mc));
		if (agent.memory.size() > 0) sb.append('\n').append(Kv.of().put("memory", agent.memory.size()).put("hint", "memory_read"));
		return truncate(sb, 0, "output truncated");
	}

	// ---- get_surroundings(S3.2、M1.2、A1) ----

	private record Group(String label, List<Block> blocks) {
	}

	private static TagKey<Block> blockTag(String name) {
		return TagKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.withDefaultNamespace(name));
	}

	private static List<Block> tagged(TagKey<Block> tag) {
		return TaggedBlocks.of(tag);
	}

	/** 水和岩漿一片就是上千格，另外掃，免得擠掉其他組的掃描上限。 */
	private static final Set<String> FLUID_GROUPS = Set.of("water", "lava");

	private static List<Group> notableGroups() {
		return List.of(
			new Group("logs", TaggedBlocks.naturalLogs()),
			new Group("coal_ore", tagged(blockTag("coal_ores"))),
			new Group("iron_ore", tagged(BlockTags.IRON_ORES)),
			new Group("copper_ore", tagged(BlockTags.COPPER_ORES)),
			new Group("gold_ore", tagged(BlockTags.GOLD_ORES)),
			new Group("redstone_ore", tagged(blockTag("redstone_ores"))),
			new Group("lapis_ore", tagged(blockTag("lapis_ores"))),
			new Group("diamond_ore", tagged(blockTag("diamond_ores"))),
			new Group("emerald_ore", tagged(blockTag("emerald_ores"))),
			new Group("water", List.of(Blocks.WATER)),
			new Group("lava", List.of(Blocks.LAVA)),
			new Group("fire", List.of(Blocks.FIRE, Blocks.SOUL_FIRE)),
			new Group("crafting_table", List.of(Blocks.CRAFTING_TABLE)),
			new Group("furnace", List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER)),
			new Group("chest", List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL)),
			new Group("bed", tagged(BlockTags.BEDS)),
			new Group("door", tagged(BlockTags.DOORS))
		);
	}

	/** 所有權的固定三個值 own、other、nat(docs/SPEC.md S3.0b)。 */
	public String ownerLabel(BlockPos pos, BlockState st) {
		return Fmt.owner(agent, pos, st);
	}

	private static final int[][] COMPASS_DIRS = {
		{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
	};
	private static final int[] BIOME_SAMPLE_DISTANCES = {32, 64, 96};

	private static String biomeName(Minecraft mc, BlockPos pos) {
		return mc.level.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
	}

	/**
	 * 檢查已載入區塊中各方向的生態域，讓 AI 看得出往哪個方向能離開目前生態域：
	 * {@code biome=plains nearby=N:64:forest,E:32:river}(方向:距離:生態域；已載入的都一樣時是 none)。
	 */
	private static String biomeSummary(Minecraft mc, BlockPos feet, boolean underground) {
		String here = biomeName(mc, feet);
		List<String> others = new ArrayList<>();
		int minY = mc.level.getMinY();
		int maxY = mc.level.getMaxY() - 1;
		for (int i = 0; i < COMPASS_DIRS.length; i++) {
			int dx = COMPASS_DIRS[i][0], dz = COMPASS_DIRS[i][1];
			boolean diag = dx != 0 && dz != 0;
			for (int dist : BIOME_SAMPLE_DISTANCES) {
				int step = diag ? (int) Math.round(dist * 0.7071) : dist;
				int sx = feet.getX() + dx * step;
				int sz = feet.getZ() + dz * step;
				BlockPos probe = new BlockPos(sx, feet.getY(), sz);
				if (!AreaInspection.loaded(mc.level, probe)) break;
				int sy = underground ? feet.getY()
					: Math.clamp(mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz), minY, maxY);
				String b = biomeName(mc, new BlockPos(sx, sy, sz));
				if (!b.equals(here)) {
					others.add(Fmt.COMPASS[i] + ":" + dist + ":" + b);
					break;
				}
			}
		}
		return Kv.of().put("biome", here).put("nearby", others.isEmpty() ? "none" : String.join(",", others)).toString();
	}

	/**
	 * 一次掃完所有固體方塊組(H12)：Baritone 的掃描主要花在走過區塊，16 組各掃一次太慢。
	 * 水和岩漿各自掃。每組依距離取最近的 cap 個。
	 */
	private static Map<String, List<BlockPos>> scanGroups(Minecraft mc, List<Group> groups, int radius, int cap) {
		Map<Block, String> owner = new HashMap<>();
		List<Block> solid = new ArrayList<>();
		Map<String, List<BlockPos>> out = new HashMap<>();
		for (Group g : groups) {
			if (FLUID_GROUPS.contains(g.label())) {
				out.put(g.label(), Moves.scan(mc, g.blocks(), radius, cap, null));
				continue;
			}
			for (Block b : g.blocks()) {
				if (owner.putIfAbsent(b, g.label()) == null) solid.add(b);
			}
		}
		Map<String, Integer> counts = new HashMap<>();
		// 已依距離排序：每組只留最近的 cap 個；上限用總數，讓 Moves.scan 不夠時自己加大範圍重掃
		for (BlockPos pos : Moves.scan(mc, solid, radius, MAX_SOLID_HITS, null)) {
			String label = owner.get(mc.level.getBlockState(pos).getBlock());
			if (label == null || counts.merge(label, 1, Integer::sum) > cap) continue;
			out.computeIfAbsent(label, k -> new ArrayList<>()).add(pos);
		}
		return out;
	}

	/** 合併掃描最多留幾格：森林裡原木很多，太少會把遠一點的礦石擠掉。 */
	private static final int MAX_SOLID_HITS = 4096;

	public String surroundings(Minecraft mc, int radius, boolean detail) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.position();
		BlockPos feet = p.blockPosition();
		StringBuilder sb = new StringBuilder();
		BlockState under = mc.level.getBlockState(feet.below());
		boolean sky = mc.level.canSeeSky(feet.above());
		boolean underground = !sky && feet.getY() < 50;
		int drop = 0;
		for (int i = 1; i <= 6; i++) {
			BlockPos ahead = feet.relative(p.getDirection(), 1).below(i);
			if (mc.level.getBlockState(ahead).isAir()) drop = i;
			else break;
		}
		sb.append(Kv.of().put("under", RuleGuard.shortId(under)).put("light", mc.level.getMaxLocalRawBrightness(feet))
			.put("terrain", sky ? "surface" : underground ? "underground" : "covered").put("y", feet.getY())
			.put("face", Fmt.facing(p.getYRot())).putIf(drop >= 4, "drop_ahead", drop).putIf(p.isInWater(), "in_water", true));
		sb.append('\n').append(biomeSummary(mc, feet, underground));
		if (mc.level.dimension().equals(Level.OVERWORLD)) sb.append('\n').append(timeOfDay(mc));

		sb.append('\n').append(Kv.of("blocks").put("r", radius));
		int groupsShown = 0, hidden = 0;
		int cap = detail ? 20 : 8;
		List<Group> groups = notableGroups();
		Map<String, List<BlockPos>> scanned = scanGroups(mc, groups, radius, cap);
		for (Group g : groups) {
			List<BlockPos> found = scanned.getOrDefault(g.label(), List.of());
			if (found.isEmpty()) continue;
			BlockPos n = found.getFirst();
			BlockState st = mc.level.getBlockState(n);
			Map<String, Integer> owners = new TreeMap<>();
			for (BlockPos b : found) owners.merge(ownerLabel(b, mc.level.getBlockState(b)), 1, Integer::sum);
			Kv line = Kv.of(g.label()).put("n", found.size() + (found.size() >= cap ? "+" : "")).put("nearest", RuleGuard.shortId(st))
				.put("pos", Fmt.pos(n));
			Fmt.rel(line, eye, Vec3.atCenterOf(n)).put("owner", ownerLabel(n, st));
			if (owners.size() > 1) line.put("owners", Kv.counts(owners));
			sb.append("\n- ").append(line);
			groupsShown++;
		}
		if (groupsShown == 0) sb.append(" none");

		// 生物：每種都列出最近的 id 與方向，避免近處一種生物(例如兔子)遮住較遠的目標(例如羊)
		Map<String, List<Entity>> byKind = new LinkedHashMap<>();
		for (String k : new String[]{"hostile", "neutral", "protected", "passive"}) byKind.put(k, new ArrayList<>());
		Map<String, List<Entity>> vehicles = new LinkedHashMap<>();
		Map<String, List<ItemEntity>> drops = new LinkedHashMap<>();
		AABB box = p.getBoundingBox().inflate(radius);
		Entity attacker = recentAttacker(mc, 100);
		for (Entity e : mc.level.getEntities(p, box, e -> !(e instanceof AbstractClientPlayer) && e.isAlive())) {
			if (e instanceof ItemEntity it) {
				if (it.getItem().isEmpty()) continue;
				drops.computeIfAbsent(Inv.id(it.getItem()), k -> new ArrayList<>()).add(it);
				continue;
			}
			if (e instanceof VehicleEntity) {
				vehicles.computeIfAbsent(Fmt.entityId(e), k -> new ArrayList<>()).add(e);
				continue;
			}
			if (!(e instanceof LivingEntity)) continue;
			// 中立生物(終界使者、殭屍化豬布林…)沒生氣時單獨列出，別去惹(S9.1b)
			String kind = Threats.isThreat(e, attacker) ? "hostile"
				: e instanceof Enemy ? "neutral"
				: e.getType().getCategory() == MobCategory.MONSTER ? "hostile"
				: RuleGuard.isProtectedEntity(e) ? "protected" : "passive";
			byKind.get(kind).add(e);
		}
		sb.append("\nmobs");
		boolean anyMob = false;
		int maxSpecies = detail ? 12 : 8;
		boolean showPositions = detail || radius <= 16;
		int maxPerSpecies = detail ? 6 : showPositions ? 3 : 1;
		for (var en : byKind.entrySet()) {
			if (en.getValue().isEmpty()) continue;
			anyMob = true;
			Map<String, List<Entity>> byType = new HashMap<>();
			for (Entity e : en.getValue()) byType.computeIfAbsent(Fmt.entityId(e), k -> new ArrayList<>()).add(e);
			for (List<Entity> list : byType.values()) list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
			var species = byType.entrySet().stream()
				.sorted(Comparator.<Map.Entry<String, List<Entity>>>comparingDouble(e -> e.getValue().getFirst().distanceToSqr(p))
					.thenComparing(Map.Entry::getKey))
				.toList();
			int shown = Math.min(species.size(), maxSpecies);
			hidden += species.size() - shown;
			for (int i = 0; i < shown; i++) {
				var sp = species.get(i);
				List<Entity> list = sp.getValue();
				Entity nearest = list.getFirst();
				int babies = 0;
				for (Entity e : list) if (e instanceof LivingEntity le && le.isBaby()) babies++;
				Kv line = Kv.of(en.getKey()).word(sp.getKey()).put("id", nearest.getId()).putIf(list.size() > 1, "n", list.size());
				if (showPositions) line.put("pos", Fmt.pos(nearest.blockPosition()));
				Fmt.rel(line, eye, nearest.position());
				line.putIf(babies > 0, "babies", babies);
				if (list.size() > 1 && maxPerSpecies > 1) {
					int limit = Math.min(list.size(), maxPerSpecies);
					List<String> ids = new ArrayList<>(limit);
					List<MobEntry> entries = showPositions ? new ArrayList<>(limit) : List.of();
					for (int j = 0; j < limit; j++) {
						Entity e = list.get(j);
						ids.add(String.valueOf(e.getId()));
						if (showPositions) {
							BlockPos bp = e.blockPosition();
							entries.add(new MobEntry(e.getId(), bp.getX(), bp.getY(), bp.getZ(), e instanceof LivingEntity le && le.isBaby()));
						}
					}
					line.put("ids", String.join(",", ids));
					if (showPositions) line.put("at", formatMobPositions(entries));
				}
				List<String> states = new ArrayList<>();
				for (Entity e : list) {
					String st = Threats.state(e);
					if (!st.isEmpty() && states.size() < 4) states.add(e.getId() + ":" + st);
				}
				if (!states.isEmpty()) line.put("states", String.join(",", states));
				sb.append("\n- ").append(line);
			}
		}
		if (!anyMob) sb.append(" none");
		// 載具(A1)：船、礦車，mount 用得到
		for (var en : vehicles.entrySet()) {
			List<Entity> list = en.getValue();
			list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
			Entity v = list.getFirst();
			Entity rider = v.getFirstPassenger();
			Kv line = Kv.of("vehicle").word(en.getKey()).put("id", v.getId()).putIf(list.size() > 1, "n", list.size()).put("pos", Fmt.pos(v.position()));
			Fmt.rel(line, eye, v.position()).put("rider", rider == null ? "none" : rider == p ? "self" : rider.getName().getString());
			sb.append("\n- ").append(line);
		}
		// 地上的掉落物(A1)：依物品 id 合併計數，列最近那一堆的位置
		var dropList = drops.entrySet().stream()
			.sorted(Comparator.comparingDouble(e -> e.getValue().stream().mapToDouble(x -> x.distanceToSqr(p)).min().orElse(0)))
			.toList();
		int maxDrops = detail ? 12 : 6;
		for (int i = 0; i < Math.min(dropList.size(), maxDrops); i++) {
			var en = dropList.get(i);
			ItemEntity nearest = en.getValue().stream().min(Comparator.comparingDouble(x -> x.distanceToSqr(p))).orElseThrow();
			int count = en.getValue().stream().mapToInt(x -> x.getItem().getCount()).sum();
			Kv line = Kv.of("drop").word(en.getKey()).put("n", count).put("pos", Fmt.pos(nearest.position()));
			sb.append("\n- ").append(Fmt.rel(line, eye, nearest.position()));
		}
		hidden += Math.max(0, dropList.size() - maxDrops);
		int others = mc.level.players().size() - 1;
		if (others > 0) sb.append('\n').append(Kv.of().put("players", others).put("hint", "get_players"));
		return truncate(sb, hidden, "detail=true lists more; a smaller radius is shorter");
	}

	// ---- get_players(S3.2、M1.3、M1.4、A4) ----

	public String players(Minecraft mc) {
		LocalPlayer me = mc.player;
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (AbstractClientPlayer o : mc.level.players()) {
			if (o == me) continue;
			n++;
			Kv line = Kv.of().put("player", o.getName().getString()).put("pos", Fmt.pos(o.blockPosition()));
			Fmt.rel(line, me.position(), o.position()).put("hand", Fmt.item(o.getMainHandItem()));
			List<String> armor = new ArrayList<>();
			for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				ItemStack a = o.getItemBySlot(s);
				if (!a.isEmpty()) armor.add(Inv.id(a));
			}
			if (!armor.isEmpty()) line.put("armor", String.join(",", armor));
			line.put("activity", activity(mc, o));
			if (!sb.isEmpty()) sb.append('\n');
			sb.append("- ").append(line);
		}
		if (n == 0) return "players=none note=\"players outside tracking range are not visible\"";
		return truncate(sb, 0, "output truncated");
	}

	/** 推斷的最近行為(S3.4)：{@code mining:iron_ore,moving} 這類固定詞。 */
	private String activity(Minecraft mc, AbstractClientPlayer o) {
		if (o.isSleeping()) return "sleeping";
		long now = agent.tick();
		List<String> acts = new ArrayList<>();
		Seen b = breaking.get(o.getId());
		if (b != null && now - b.tick() < 600) acts.add(b.what());
		boolean holdsBlock = o.getMainHandItem().getItem() instanceof BlockItem;
		for (long[] pl : placedByOthers) {
			if (now - pl[1] < 600 && BlockPos.of(pl[0]).distSqr(o.blockPosition()) <= 25 && holdsBlock) {
				acts.add("building");
				break;
			}
		}
		if (o.isSwinging()) {
			for (Entity e : mc.level.getEntities(o, o.getBoundingBox().inflate(4), e -> e instanceof LivingEntity le && le.hurtTime > 0 && !(e instanceof AbstractClientPlayer))) {
				acts.add("fighting:" + Fmt.entityId(e));
				break;
			}
		}
		if (o.hurtTime > 0) acts.add("hurt");
		if (acts.isEmpty()) acts.add(o.getDeltaMovement().horizontalDistanceSqr() > 0.001 ? "moving" : "idle");
		return String.join(",", acts);
	}

	/** ClientboundBlockDestructionPacket：誰在挖哪一格(S3.4)。 */
	public void onBlockDestruction(int breakerId, BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || breakerId == mc.player.getId()) return;
		BlockState st = mc.level.getBlockState(pos);
		String what = (st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) && RuleGuard.shortId(st).endsWith("_ore")
			? "mining:" : "breaking:") + RuleGuard.shortId(st);
		breaking.put(breakerId, new Seen(what, agent.tick()));
	}

	public void onOtherPlaced(BlockPos pos) {
		placedByOthers.add(new long[]{pos.asLong(), agent.tick()});
		if (placedByOthers.size() > 200) placedByOthers.removeFirst();
	}

	// ---- 事件(S5.2) ----

	void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		long t = agent.tick();
		placedByOthers.removeIf(x -> t - x[1] > 600);
		breaking.values().removeIf(s -> t - s.tick() > 600);

		var dimension = mc.level.dimension();
		boolean changedDimension = observedDimension != null && !observedDimension.equals(dimension);
		if (changedDimension) {
			agent.events.add("dimension_changed", Event.Level.PROMPT, Kv.of()
				.put("from", observedDimension.identifier()).put("dim", dimension.identifier())
				.put("pos", Fmt.pos(p.blockPosition())).put("portal_cooldown", p.getPortalCooldown()).toString());
			sleepingPlayers.clear();
			reportedHostiles.clear();
			breaking.clear();
			placedByOthers.clear();
			lastAttackerId = -1;
			nightReported = false;
			sawDark = false;
			lastHealth = -1;
		}
		observedDimension = dimension;
		float h = p.getHealth();
		// 暫停或待命時這裡不會執行：恢復後的第一個 tick 不跟暫停前的血量或天黑記憶比，免得補發假的 damaged、can_sleep 或正午 daybreak(L5、Low)
		boolean firstOrResumed = lastObservedTick < 0 || t - lastObservedTick > 1;
		if (lastObservedTick >= 0 && t - lastObservedTick > 1) {
			lastHealth = -1;
			sawDark = false;
		}
		lastObservedTick = t;
		if (lastHealth >= 0 && h < lastHealth - 0.01f) {
			float lost = lastHealth - h;
			Entity by = recentAttacker(mc, 5);
			if (by != null) {
				agent.events.add("attacked", Event.Level.URGENT, Kv.of().put("by", by instanceof AbstractClientPlayer pl ? pl.getName().getString() : Fmt.entityId(by))
					.put("id", by.getId()).put("lost", Math.round(lost)).put("hp", Math.round(h)).toString());
			} else {
				agent.events.add(new Event("damaged", Event.Level.BATCH, Kv.of().put("lost", Math.round(lost)).put("hp", Math.round(h)).toString(),
					"damaged", System.currentTimeMillis()));
			}
		}
		if (h <= Reflexes.LOW_HEALTH && !healthLowReported) {
			healthLowReported = true;
			agent.events.add("health_low", Event.Level.URGENT, "hp=" + Math.round(h) + "/" + Math.round(p.getMaxHealth()) + " | hint=eat, retreat or take shelter");
		} else if (h > Reflexes.LOW_HEALTH + 4) {
			healthLowReported = false;
		}
		lastHealth = h;

		Set<UUID> sleepingNow = new HashSet<>();
		for (AbstractClientPlayer other : mc.level.players()) {
			if (other == p || !other.isSleeping()) continue;
			sleepingNow.add(other.getUUID());
			if (!sleepingPlayers.contains(other.getUUID())) {
				agent.events.add("player_sleeping", Event.Level.PROMPT, Kv.of()
					.put("player", other.getName().getString()).put("pos", Fmt.pos(other.blockPosition())).toString());
			}
		}
		sleepingPlayers.retainAll(sleepingNow);
		sleepingPlayers.addAll(sleepingNow);

		if (dimension.equals(Level.OVERWORLD)) {
			long clock = mc.level.getOverworldClockTime();
			String phase = DayClock.phase(clock);
			var rule = mc.level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, p.blockPosition());
			boolean sleepable = bedUsable(rule) && rule.canSleep(mc.level);
			if (firstOrResumed || changedDimension) sleepReadyReported = sleepable;
			if (phase.equals("dusk") && !nightReported) {
				// 黃昏開始：離地表生怪約 1 分鐘，晴天 12542 起可以睡(M17)
				nightReported = true;
				String sleepIn = DayClock.sleepCountdown(clock);
				CombatPolicy.Readiness r = CombatPolicy.readiness(Combat.assess(p));
				agent.events.add("night_falling", Event.Level.BATCH,
					DayClock.describe(clock) + (sleepIn != null ? " " + sleepIn : "") + " " + Kv.of().put("gear", r.label())
						.put("missing", r.missing().isEmpty() ? "none" : String.join(",", r.missing())) + " | hint=head to shelter or a bed");
			} else if (phase.equals("day")) {
				nightReported = false;
			}
			if (sleepable && !sleepReadyReported) {
				sleepReadyReported = true;
				agent.events.add("can_sleep", Event.Level.PROMPT, DayClock.describe(clock) + " | hint=you can sleep in a bed now");
			} else if (!sleepable) {
				sleepReadyReported = false;
			}
			if (DayClock.isDark(clock)) {
				sawDark = true;
			} else if (sawDark) {
				// 剛過完夜晚：叫醒等天亮的 wait_events；下雨時不死系不會自燃
				sawDark = false;
				String w = weather(mc);
				agent.events.add("daybreak", Event.Level.PROMPT, "time=day weather=" + w + " | hint="
					+ (w.equals("clear") ? "undead burn in sunlight; safe to go out" : "undead do not burn in rain; stay careful"));
			}
		}

		if (t % 20 == 0 && !p.getAbilities().invulnerable) {
			CombatPolicy.DangerLevel level = danger(mc).level();
			if (level == CombatPolicy.DangerLevel.HIGH && !dangerReported) {
				dangerReported = true;
				agent.events.add("danger_rising", Event.Level.PROMPT, dangerKv(mc)
					+ " | hint=head to shelter" + (dimension.equals(Level.OVERWORLD) ? " or a bed" : "")
					+ ", light the area up, or wall yourself in; make what missing lists");
			} else if (level == CombatPolicy.DangerLevel.LOW) {
				dangerReported = false;
			}
		}
		if (t % 20 == 0) {
			Set<Integer> now = new HashSet<>();
			Entity attacker = recentAttacker(mc, 100);
			for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(16), e -> Threats.isThreat(e, attacker))) {
				now.add(e.getId());
				if (!reportedHostiles.contains(e.getId())) {
					agent.events.add(new Event("hostile_near", Event.Level.BATCH,
						Fmt.rel(Kv.of(Fmt.entityId(e)).put("id", e.getId()), p.position(), e.position()).toString(),
						"hostile_near", System.currentTimeMillis()));
				}
			}
			reportedHostiles.retainAll(now);
			reportedHostiles.addAll(now);
		}
	}

	/** 原版床的使用規則以床頭的位置為準，草床和普通床也各有自己的規則。 */
	static BedRule bedRule(Level level, BlockPos pos, BlockState state) {
		if (!(state.getBlock() instanceof AbstractBedBlock bed)) return null;
		BlockPos head = state.getValue(AbstractBedBlock.PART) == BedPart.FOOT
			? pos.relative(AbstractBedBlock.getConnectedDirection(state)) : pos;
		return bed.getBedRule(level, head);
	}

	static boolean bedUsable(BedRule rule) {
		return rule != null && !rule.destroyOnUse() && rule.canSleep() != BedRule.Rule.NEVER;
	}

	public static boolean bedUsable(Level level, BlockPos pos, BlockState state) {
		return bedUsable(bedRule(level, pos, state));
	}

	public void resetWorld() {
		observedDimension = null;
		sleepingPlayers.clear();
		breaking.clear();
		placedByOthers.clear();
		reportedHostiles.clear();
		lastHealth = -1;
		lastAttackerId = -1;
		healthLowReported = false;
		nightReported = false;
		dangerReported = false;
		sleepReadyReported = false;
		sawDark = false;
		lastObservedTick = -1;
	}
}
