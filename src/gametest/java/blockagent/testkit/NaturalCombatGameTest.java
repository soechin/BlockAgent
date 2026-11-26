package blockagent.testkit;

import blockagent.game.reflex.Reflexes;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * 自然地形夜間實戰壓測：不用 flatArena，直接用原版 WorldPresets.NORMAL 生成真實世界，
 * 透過 {@code findClosestBiome3d} 自動跳到三種典型地形(開闊地、密林、陡坡山地)，在午夜 normal 難度＋自然生怪下實測。
 * <p>
 * 預設跳過(不隨 build／一般 runClientGameTest 執行)；手動跑：
 * <ul>
 *   <li>預設種子：{@code ./gradlew runClientGameTest -PtestOnly=NaturalCombatGameTest}</li>
 *   <li>隨機新地圖：{@code ./gradlew runClientGameTest -PtestOnly=NaturalCombatGameTest -PtestSeed=random}</li>
 *   <li>多張地圖連測：{@code ./gradlew runClientGameTest -PtestOnly=NaturalCombatGameTest -PtestSeed=seedA,seedB,random}</li>
 *   <li>只跑單一情境：再加 {@code -PtestCase=open_early_trek,forest_geared_ambush,hills_geared_trek,hills_early_ambush}</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public class NaturalCombatGameTest implements FabricClientGameTest {
	private static final String DEFAULT_SEED = "blockagent-combat-1";
	private static final Set<String> OPEN_BIOMES = Set.of("plains", "sunflower_plains", "savanna", "meadow", "desert");
	private static final Set<String> FOREST_BIOMES = Set.of("forest", "dark_forest", "birch_forest", "old_growth_birch_forest", "taiga", "snowy_taiga", "jungle");
	private static final Set<String> HILL_BIOMES = Set.of("windswept_hills", "windswept_gravelly_hills", "windswept_forest", "stony_peaks", "jagged_peaks", "snowy_slopes", "savanna_plateau", "badlands");
	private static int resetMark;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skipOptIn("NaturalCombatGameTest")) return;
		List<String> seeds = resolveSeeds();
		for (String seed : seeds) {
			runOneWorld(ctx, seed);
		}
	}

	private static List<String> resolveSeeds() {
		String raw = System.getProperty("blockagent.test.seed");
		if (raw == null || raw.isBlank()) return List.of(DEFAULT_SEED);
		List<String> out = new ArrayList<>();
		for (String part : raw.split(",")) {
			String s = part.strip();
			if (s.isEmpty()) continue;
			if ("random".equalsIgnoreCase(s)) out.add("wild-" + ThreadLocalRandom.current().nextInt(100_000, 999_999));
			else out.add(s);
		}
		return out.isEmpty() ? List.of(DEFAULT_SEED) : out;
	}

	private void runOneWorld(ClientGameTestContext ctx, String seed) {
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.adjustSettings(creator -> {
				Holder<WorldPreset> normal = creator.getSettings().worldgenLoadContext()
					.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL);
				creator.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
				creator.setSeed(seed);
			})
			.create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos spawn = dryLandNear(k, k.myPos().getX(), k.myPos().getZ());
			k.cmd("tp @p " + TestKit.pos(spawn));
			k.cmd("difficulty normal");
			k.cmd("time set 18000");
			k.cmd("gamerule mob_griefing false");
			k.cmd("gamerule spawn_monsters true");
			k.cmd("gamerule spawn_mobs true");
			k.start();

			BlockPos openSpot = findBiomeSpot(k, spawn, OPEN_BIOMES, spawn);
			BlockPos forestSpot = findBiomeSpot(k, spawn, FOREST_BIOMES, dryLandNear(k, spawn.getX() + 60, spawn.getZ() - 40));
			BlockPos hillSpot = findBiomeSpot(k, spawn, HILL_BIOMES, dryLandNear(k, spawn.getX() - 60, spawn.getZ() + 40));

			System.out.println("[natural] seed=" + seed
				+ " open=" + openSpot.toShortString() + "(" + biomeAt(k, openSpot) + ")"
				+ " forest=" + forestSpot.toShortString() + "(" + biomeAt(k, forestSpot) + ")"
				+ " hills=" + hillSpot.toShortString() + "(" + biomeAt(k, hillSpot) + ")");

			if (TestKit.runCase("open_early_trek")) openEarlyTrek(k, seed, openSpot);
			if (TestKit.runCase("forest_geared_ambush")) forestGearedAmbush(k, seed, forestSpot);
			if (TestKit.runCase("hills_geared_trek")) hillsGearedTrek(k, seed, hillSpot);
			if (TestKit.runCase("hills_early_ambush")) hillsEarlyAmbush(k, seed, dryLandNear(k, hillSpot.getX() - 40, hillSpot.getZ() - 30));
		} catch (Exception e) {
			throw new RuntimeException("seed=" + seed, e);
		}
	}

	/** 情境 1：開闊地(plains/savanna)無樹幹掩體，前期無甲石劍夜行 70 格＋2 組巡邏怪。 */
	private void openEarlyTrek(TestKit k, String seed, BlockPos start) throws Exception {
		reset(k, start);
		k.give("minecraft:stone_sword", 1);
		k.give("minecraft:bread", 12);

		BlockPos dest = dryLandNear(k, start.getX() + 65, start.getZ() + 25);
		spawnPatrol(k, start.getX() + 20, start.getZ() + 8, List.of(EntityTypes.ZOMBIE, EntityTypes.ZOMBIE, EntityTypes.SKELETON));
		spawnPatrol(k, start.getX() + 44, start.getZ() + 17, List.of(EntityTypes.CREEPER, EntityTypes.SKELETON, EntityTypes.SKELETON));

		runTrek(k, "[" + seed + "] 開闊地前期夜行(" + biomeAt(k, start) + ")", start, dest, 75, false);
	}

	/** 情境 2：密林(forest/dark_forest/taiga)樹幹與低枝環境，全套含弓箭遭遇 2 骷髏＋1 苦力怕＋2 殭屍。 */
	private void forestGearedAmbush(TestKit k, String seed, BlockPos site) throws Exception {
		reset(k, site);
		for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword", "bow")) {
			k.give("minecraft:" + item, 1);
		}
		k.give("minecraft:arrow", 24);
		k.give("minecraft:bread", 12);

		int mark = mark(k);
		spawnAt(k, site.getX() + 5, site.getZ() + 2, EntityTypes.ZOMBIE);
		spawnAt(k, site.getX() - 5, site.getZ() - 2, EntityTypes.ZOMBIE);
		spawnAt(k, site.getX() + 4, site.getZ() - 6, EntityTypes.CREEPER);
		spawnAt(k, site.getX() + 10, site.getZ() + 4, EntityTypes.SKELETON);
		spawnAt(k, site.getX() - 9, site.getZ() + 7, EntityTypes.SKELETON);

		runAmbush(k, "[" + seed + "] 密林全套遭遇戰(" + biomeAt(k, site) + ")", site, mark, 75, true);
	}

	/** 情境 3：陡坡山地(windswept_hills/stony_peaks)大高低差，中期鐵套＋盾＋石劍夜行 80 格＋3 組巡邏怪。 */
	private void hillsGearedTrek(TestKit k, String seed, BlockPos start) throws Exception {
		reset(k, start);
		for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword")) {
			k.give("minecraft:" + item, 1);
		}
		k.give("minecraft:bread", 16);

		BlockPos dest = dryLandNear(k, start.getX() + 75, start.getZ() + 30);
		spawnPatrol(k, start.getX() + 18, start.getZ() + 8, List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON));
		spawnPatrol(k, start.getX() + 40, start.getZ() + 16, List.of(EntityTypes.CREEPER, EntityTypes.ZOMBIE, EntityTypes.SKELETON));
		spawnPatrol(k, start.getX() + 60, start.getZ() + 24, List.of(EntityTypes.ZOMBIE, EntityTypes.ZOMBIE, EntityTypes.SKELETON));

		runTrek(k, "[" + seed + "] 山地鐵套夜行(" + biomeAt(k, start) + ")", start, dest, 95, true);
	}

	/** 情境 4：陡坡山地弱裝遭遇戰(只有石劍＋麵包、無甲無盾)，面對 2 殭屍＋1 骷髏驗證脫身與撤退保命。 */
	private void hillsEarlyAmbush(TestKit k, String seed, BlockPos site) throws Exception {
		reset(k, site);
		k.give("minecraft:stone_sword", 1);
		k.give("minecraft:bread", 12);

		int mark = mark(k);
		spawnAt(k, site.getX() + 5, site.getZ() + 1, EntityTypes.ZOMBIE);
		spawnAt(k, site.getX() + 5, site.getZ() - 2, EntityTypes.ZOMBIE);
		spawnAt(k, site.getX() - 10, site.getZ() + 3, EntityTypes.SKELETON);

		runAmbush(k, "[" + seed + "] 山地弱裝遭遇戰(" + biomeAt(k, site) + ")", site, mark, 55, false);
	}

	// ---- 執行與統計 ----

	private void runTrek(TestKit k, String label, BlockPos start, BlockPos dest, int maxSecs, boolean expectProgress) throws Exception {
		int mark = mark(k);
		var go = k.ai.call(k.ctx, "goto", args("x", dest.getX(), "z", dest.getZ(), "range", 4, "wait_sec", 0));
		check(go.ok(), label + " goto 失敗：" + go.text());

		long t0 = k.agent().tick();
		float minHp = 20f;
		int minY = start.getY(), maxY = start.getY();
		double bestDist = horizDist(start, dest);
		boolean arrived = false;
		boolean taskStopped = false;

		for (int i = 0; i < maxSecs * 2; i++) {
			k.ctx.waitTicks(10);
			var pos = k.ctx.computeOnClient(mc -> mc.player.blockPosition());
			float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
			minHp = Math.min(minHp, hp);
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			double dist = horizDist(pos, dest);
			bestDist = Math.min(bestDist, dist);
			if (dist <= 6) {
				arrived = true;
				break;
			}
			if (!k.agent().tasks.hasActiveTask() && k.agent().reflexes.active() == null) {
				taskStopped = true;
				break;
			}
		}

		long secs = (k.agent().tick() - t0) / 20;
		var endPos = k.ctx.computeOnClient(mc -> mc.player.blockPosition());
		float endHp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		String log = since(k, mark);
		k.ai.call(k.ctx, "stop");

		int fights = countOccurrences(log, "\"action\":\"fight_summary\"");
		int kills = countOccurrences(log, "result=killed");
		int disengaged = countOccurrences(log, "result=disengaged");
		int retreats = countOccurrences(log, "\"action\":\"retreat_start\"");
		int deaths = countOccurrences(log, "死在 ");
		int totalCrits = sumMetric(log, "crits=");
		int totalSwings = sumMetric(log, "swings=");
		int totalBlocks = sumMetric(log, "shield_blocks=");
		int totalHits = sumMetric(log, "hits_taken=");
		double moved = horizDist(start, endPos);

		System.out.println("[natural-data] " + label + "："
			+ "耗時=" + secs + "s 抵達=" + arrived + " 任務提前結束=" + taskStopped
			+ " 位移=" + Math.round(moved) + "格(最近距終點" + Math.round(bestDist) + "格)"
			+ " 海拔=" + minY + ".." + maxY + "(落差" + (maxY - minY) + ")"
			+ " 最低HP=" + String.format("%.1f", minHp) + " 結束HP=" + String.format("%.1f", endHp)
			+ " 死亡=" + deaths
			+ " 戰鬥=" + fights + "(擊殺=" + kills + ",脫身=" + disengaged + ")"
			+ " 撤退=" + retreats
			+ " 揮劍=" + totalSwings + "(暴擊=" + totalCrits + ")"
			+ " 盾擋=" + totalBlocks + " 挨打=" + totalHits);
		for (String line : summaries(log)) {
			System.out.println("  [fight] " + line);
		}

		check(alive(k), label + " 在自然地形夜行時死亡(死亡次數=" + deaths + ")：\n" + String.join("\n", summaries(log)));
		check(moved >= 15 || arrived, label + " 幾乎沒前進(位移=" + Math.round(moved) + "格)");
		if (expectProgress) {
			check(fights > 0 || arrived, label + " 既沒到達也沒交戰");
		}
		System.out.println("[ok] " + label + " 存活完成(最低HP=" + String.format("%.1f", minHp) + " 結束HP=" + String.format("%.1f", endHp) + ")");
	}

	private void runAmbush(TestKit k, String label, BlockPos site, int mark, int maxSecs, boolean expectFight) throws Exception {
		long t0 = k.agent().tick();
		float minHp = 20f;
		int minY = site.getY(), maxY = site.getY();
		int calmSteps = 0;
		for (int i = 0; i < maxSecs * 2; i++) {
			k.ctx.waitTicks(10);
			var pos = k.ctx.computeOnClient(mc -> mc.player.blockPosition());
			float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
			minHp = Math.min(minHp, hp);
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			boolean calm = k.agent().reflexes.active() == null && visibleHostiles(k, 16) == 0;
			calmSteps = calm ? calmSteps + 1 : 0;
			if ((nearbyHostiles(k, 16) == 0 && k.agent().reflexes.active() == null && i >= 6) || (i >= 20 && calmSteps >= 12)) break;
		}
		long secs = (k.agent().tick() - t0) / 20;
		float endHp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		String log = since(k, mark);
		int left16 = nearbyHostiles(k, 16);
		int fights = countOccurrences(log, "\"action\":\"fight_summary\"");
		int kills = countOccurrences(log, "result=killed");
		int disengaged = countOccurrences(log, "result=disengaged");
		int retreats = countOccurrences(log, "\"action\":\"retreat_start\"");
		int deaths = countOccurrences(log, "死在 ");
		int totalCrits = sumMetric(log, "crits=");
		int totalSwings = sumMetric(log, "swings=");
		int totalBlocks = sumMetric(log, "shield_blocks=");
		int totalHits = sumMetric(log, "hits_taken=");

		System.out.println("[natural-data] " + label + "："
			+ "耗時=" + secs + "s 16格內剩怪=" + left16
			+ " 海拔=" + minY + ".." + maxY + "(落差" + (maxY - minY) + ")"
			+ " 最低HP=" + String.format("%.1f", minHp) + " 結束HP=" + String.format("%.1f", endHp)
			+ " 死亡=" + deaths
			+ " 戰鬥=" + fights + "(擊殺=" + kills + ",脫身=" + disengaged + ")"
			+ " 撤退=" + retreats
			+ " 揮劍=" + totalSwings + "(暴擊=" + totalCrits + ")"
			+ " 盾擋=" + totalBlocks + " 挨打=" + totalHits);
		for (String line : summaries(log)) {
			System.out.println("  [fight] " + line);
		}

		check(alive(k), label + " 死亡(死亡次數=" + deaths + ")：\n" + String.join("\n", summaries(log)));
		if (expectFight) check(fights > 0, label + " 應該有發生戰鬥");
		System.out.println("[ok] " + label + " 存活完成(最低HP=" + String.format("%.1f", minHp) + " 結束HP=" + String.format("%.1f", endHp) + ")");
	}

	// ---- 生態域與地表查詢 ----

	private static BlockPos findBiomeSpot(TestKit k, BlockPos origin, Set<String> biomeNames, BlockPos fallback) {
		return k.server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			var found = level.findClosestBiome3d(
				h -> h.unwrapKey().map(key -> biomeNames.contains(key.identifier().getPath())).orElse(false),
				origin, 1600, 32, 64);
			BlockPos base = found != null ? found.getFirst() : fallback;
			return dryLandNear(level, base.getX(), base.getZ());
		});
	}

	private static BlockPos dryLandNear(TestKit k, int x, int z) {
		return k.server.computeOnServer(s -> dryLandNear(s.overworld(), x, z));
	}

	/** 優先找附近非水／非岩漿的實心陸地格，找不到則退回原座標地表。 */
	private static BlockPos dryLandNear(ServerLevel level, int cx, int cz) {
		for (int r = 0; r <= 24; r += 4) {
			for (int dx = -r; dx <= r; dx += (r == 0 ? 1 : r)) {
				for (int dz = -r; dz <= r; dz += (r == 0 ? 1 : r)) {
					int x = cx + dx, z = cz + dz;
					level.getChunk(x >> 4, z >> 4);
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					BlockPos pos = new BlockPos(x, y, z);
					if (level.getFluidState(pos).isEmpty() && level.getFluidState(pos.below()).isEmpty()
						&& !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
						return pos;
					}
				}
			}
		}
		level.getChunk(cx >> 4, cz >> 4);
		return new BlockPos(cx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz), cz);
	}

	private static String biomeAt(TestKit k, BlockPos pos) {
		return k.server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
			return level.getBiome(pos).unwrapKey().map(key -> key.identifier().getPath()).orElse("?");
		});
	}

	private static void spawnAt(TestKit k, int x, int z, EntityType<? extends Mob> type) {
		spawnPatrol(k, x, z, List.of(type));
	}

	/** 在 (x, z) 附近的真實地表生成一組怪(骷髏自動給弓、持久化不消失)。 */
	private static void spawnPatrol(TestKit k, int x, int z, List<EntityType<? extends Mob>> types) {
		int[][] offsets = {{0, 0}, {3, 1}, {-2, 2}, {2, -2}};
		k.onServer(s -> {
			ServerLevel level = s.overworld();
			for (int i = 0; i < types.size(); i++) {
				int sx = x + offsets[i % offsets.length][0];
				int sz = z + offsets[i % offsets.length][1];
				level.getChunk(sx >> 4, sz >> 4);
				int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
				Mob mob = types.get(i).create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				if (mob == null) continue;
				mob.snapTo(sx + 0.5, sy, sz + 0.5, 0, 0);
				mob.setPersistenceRequired();
				if (types.get(i) == EntityTypes.SKELETON) {
					mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
				}
				level.addFreshEntity(mob);
			}
		});
		k.ctx.waitTicks(4);
	}

	private static int nearbyHostiles(TestKit k, double radius) {
		var me = k.ctx.computeOnClient(mc -> mc.player.position());
		return k.server.computeOnServer(s -> {
			int n = 0;
			for (var t : List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER, EntityTypes.SPIDER)) {
				for (var e : s.overworld().getEntities(t, x -> x.isAlive() && x.position().distanceTo(me) <= radius)) n++;
			}
			return n;
		});
	}

	private static int visibleHostiles(TestKit k, double radius) {
		return k.ctx.computeOnClient(mc -> {
			var p = mc.player;
			int n = 0;
			for (var e : mc.level.getEntities(p, p.getBoundingBox().inflate(radius), x -> x instanceof Enemy && x.isAlive())) {
				if (e.distanceTo(p) <= radius && p.hasLineOfSight(e)) n++;
			}
			return n;
		});
	}

	private static void reset(TestKit k, BlockPos at) throws Exception {
		k.cmd("kill @e[type=!minecraft:player]");
		k.ai.call(k.ctx, "stop");
		k.waitUntil(20 * 10, mc -> k.agent().reflexes.active() != Reflexes.Kind.RETREAT
			&& k.agent().reflexes.active() != Reflexes.Kind.SELF_DEFENSE);
		k.ctx.runOnClient(mc -> k.agent().danger.clear());
		k.onServer(s -> {
			var p = k.me(s);
			p.setHealth(p.getMaxHealth());
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5);
		});
		k.clearInventory();
		k.cmd("tp @p " + TestKit.pos(at));
		// 跨生態域長距離傳送後留時間讓客戶端區塊與 Baritone 快取同步
		k.ctx.waitTicks(30);
		k.agent().events.drainAll();
		resetMark = mark(k);
	}

	private static double horizDist(BlockPos a, BlockPos b) {
		return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
	}

	private static boolean alive(TestKit k) {
		return k.ctx.computeOnClient(mc -> mc.player.isAlive() && mc.player.getHealth() > 0) && !since(k, resetMark).contains("死在 ");
	}

	private static int countOccurrences(String text, String needle) {
		return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
	}

	private static int sumMetric(String log, String prefix) {
		int sum = 0;
		for (String line : summaries(log)) {
			int idx = line.indexOf(prefix);
			if (idx < 0) continue;
			int start = idx + prefix.length();
			int end = start;
			while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
			if (end > start) sum += Integer.parseInt(line.substring(start, end));
		}
		return sum;
	}

	private static List<String> summaries(String log) {
		ArrayList<String> out = new ArrayList<>();
		for (String line : log.split("\n")) {
			if (!line.contains("fight_summary")) continue;
			int i = line.indexOf("\"result\":\"");
			if (i >= 0) out.add(line.substring(i + 10, line.lastIndexOf('"')));
		}
		return out;
	}

	private static int mark(TestKit k) throws Exception {
		var f = k.agent().log.file();
		return f != null && Files.exists(f) ? Files.readAllLines(f).size() : 0;
	}

	private static String since(TestKit k, int mark) {
		try {
			var f = k.agent().log.file();
			if (f == null || !Files.exists(f)) return "";
			List<String> lines = Files.readAllLines(f);
			return String.join("\n", lines.subList(Math.min(mark, lines.size()), lines.size()))
				.replace("\\u003d", "=").replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&").replace("\\u0027", "'");
		} catch (Exception e) {
			return "(log unreadable: " + e + ")";
		}
	}
}
