package blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 原版配方資料與 craft/smelt 的事前檢查(docs/SPEC.md S4.4)。
 * 只用背包裡**已經有**的東西計算，不會規劃去採集(M2.8 使用者選了「單一目標」)。
 * 唯一的例外是工作台：需要時可以用背包裡的原木先做木板、再做工作台(S4.1)。
 */
public final class Recipes {
	/** 一格材料：可以是任何一個列出的物品。tag 在 resolve 時展開。 */
	public record Ingredient(List<String> options, String label) {
		boolean matches(String item) {
			return options.contains(item);
		}
	}

	public enum Kind {SHAPED, SHAPELESS, SMELTING, BLASTING, SMOKING}

	/** 支援的熔爐種類(S4.2e)。 */
	public enum FurnaceKind {FURNACE, BLAST_FURNACE, SMOKER}

	/** 候選爐子(純資料，供優先順序單元測試與 SmeltTask 共用)。 */
	public record FurnaceCandidate<T>(T target, FurnaceKind kind, boolean owned, double distSq) {
	}

	/**
	 * @param grid   SHAPED：width*height 格，空格是 null；SHAPELESS：所有材料；SMELTING/BLASTING/SMOKING：一個材料
	 */
	public record Recipe(String id, Kind kind, int width, int height, List<Ingredient> grid, String result, int resultCount) {
		public boolean needsTable() {
			if (kind == Kind.SHAPED) return width > 2 || height > 2;
			return kind == Kind.SHAPELESS && grid.size() > 4;
		}
	}

	/** 要做的一步合成。 */
	public record Step(Recipe recipe, int crafts, Map<String, Integer> consumes) {
	}

	public record Plan(List<Step> steps, boolean needsTable, boolean placesTable, Map<String, Integer> missing) {
		public boolean ok() {
			return missing.isEmpty();
		}
	}

	private final Map<String, List<Recipe>> byResult = new HashMap<>();
	private final Map<String, List<Recipe>> smeltingByResult = new HashMap<>();
	private final Map<String, Recipe> smeltingByInput = new HashMap<>();
	private final Map<String, List<Recipe>> blastingByResult = new HashMap<>();
	private final Map<String, Recipe> blastingByInput = new HashMap<>();
	private final Map<String, List<Recipe>> smokingByResult = new HashMap<>();
	private final Map<String, Recipe> smokingByInput = new HashMap<>();

	/** tag(不含 #，例如 minecraft:planks)→ 物品 id 清單。 */
	private final Function<String, Collection<String>> tagResolver;

	public Recipes(Function<String, Collection<String>> tagResolver) {
		this.tagResolver = tagResolver;
	}

	static String norm(String id) {
		return id.contains(":") ? id : "minecraft:" + id;
	}

	public void addJson(String id, String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		String type = o.get("type").getAsString();
		switch (type) {
			case "minecraft:crafting_shaped" -> {
				JsonArray pat = o.getAsJsonArray("pattern");
				JsonObject key = o.getAsJsonObject("key");
				int h = pat.size();
				int w = 0;
				for (JsonElement r : pat) w = Math.max(w, r.getAsString().length());
				List<Ingredient> grid = new ArrayList<>();
				for (JsonElement r : pat) {
					String row = r.getAsString();
					for (int x = 0; x < w; x++) {
						char c = x < row.length() ? row.charAt(x) : ' ';
						grid.add(c == ' ' ? null : ingredient(key.get(String.valueOf(c))));
					}
				}
				add(new Recipe(id, Kind.SHAPED, w, h, grid, resultId(o), resultCount(o)));
			}
			case "minecraft:crafting_shapeless" -> {
				List<Ingredient> list = new ArrayList<>();
				for (JsonElement e : o.getAsJsonArray("ingredients")) list.add(ingredient(e));
				add(new Recipe(id, Kind.SHAPELESS, 0, 0, list, resultId(o), resultCount(o)));
			}
			case "minecraft:smelting" -> {
				Recipe r = new Recipe(id, Kind.SMELTING, 1, 1, List.of(ingredient(o.get("ingredient"))), resultId(o), resultCount(o));
				smeltingByResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
				for (String in : r.grid().getFirst().options()) smeltingByInput.putIfAbsent(in, r);
			}
			case "minecraft:blasting" -> {
				Recipe r = new Recipe(id, Kind.BLASTING, 1, 1, List.of(ingredient(o.get("ingredient"))), resultId(o), resultCount(o));
				blastingByResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
				for (String in : r.grid().getFirst().options()) blastingByInput.putIfAbsent(in, r);
			}
			case "minecraft:smoking" -> {
				Recipe r = new Recipe(id, Kind.SMOKING, 1, 1, List.of(ingredient(o.get("ingredient"))), resultId(o), resultCount(o));
				smokingByResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
				for (String in : r.grid().getFirst().options()) smokingByInput.putIfAbsent(in, r);
			}
			default -> {
				// 其他種類(切石機、鍛造台、特殊配方)不支援
			}
		}
	}

	private void add(Recipe r) {
		byResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
	}

	private static String resultId(JsonObject o) {
		JsonElement r = o.get("result");
		return norm(r.isJsonObject() ? r.getAsJsonObject().get("id").getAsString() : r.getAsString());
	}

	private static int resultCount(JsonObject o) {
		JsonElement r = o.get("result");
		return r.isJsonObject() && r.getAsJsonObject().has("count") ? r.getAsJsonObject().get("count").getAsInt() : 1;
	}

	private Ingredient ingredient(JsonElement e) {
		Set<String> opts = new LinkedHashSet<>();
		String label;
		if (e.isJsonArray()) {
			List<String> names = new ArrayList<>();
			for (JsonElement x : e.getAsJsonArray()) {
				String s = x.getAsString();
				expand(s, opts);
				names.add(s);
			}
			label = String.join("|", names);
		} else {
			String s = e.getAsString();
			expand(s, opts);
			label = s;
		}
		return new Ingredient(List.copyOf(opts), label);
	}

	private void expand(String s, Set<String> out) {
		if (s.startsWith("#")) {
			Collection<String> items = tagResolver.apply(norm(s.substring(1)));
			if (items != null) for (String i : items) out.add(norm(i));
		} else {
			out.add(norm(s));
		}
	}

	public List<Recipe> craftingRecipesFor(String item) {
		return byResult.getOrDefault(norm(item), List.of());
	}

	public List<Recipe> smeltingRecipesFor(String result) {
		return smeltingByResult.getOrDefault(norm(result), List.of());
	}

	/** 這個物品放進普通熔爐會燒成什麼(null 表示不能燒)。 */
	public Recipe smeltingFor(String input) {
		return smeltingByInput.get(norm(input));
	}

	public Recipe blastingFor(String input) {
		return blastingByInput.get(norm(input));
	}

	public Recipe smokingFor(String input) {
		return smokingByInput.get(norm(input));
	}

	public List<Recipe> blastingRecipesFor(String result) {
		return blastingByResult.getOrDefault(norm(result), List.of());
	}

	public List<Recipe> smokingRecipesFor(String result) {
		return smokingByResult.getOrDefault(norm(result), List.of());
	}

	public int blastingRecipeCount() {
		return blastingByResult.values().stream().mapToInt(List::size).sum();
	}

	public int smokingRecipeCount() {
		return smokingByResult.values().stream().mapToInt(List::size).sum();
	}

	/** 這個原料可以在任何一種熔爐(普通/高爐/煙燻爐)燒出的配方。 */
	public Recipe cookingFor(String input) {
		String k = norm(input);
		Recipe r = smeltingByInput.get(k);
		if (r != null) return r;
		r = blastingByInput.get(k);
		return r != null ? r : smokingByInput.get(k);
	}

	/** 這個產物可以用任何一種熔爐燒出的配方(去重同 id)。 */
	public List<Recipe> cookingRecipesFor(String result) {
		String k = norm(result);
		List<Recipe> out = new ArrayList<>(smeltingByResult.getOrDefault(k, List.of()));
		if (out.isEmpty()) {
			out.addAll(blastingByResult.getOrDefault(k, List.of()));
			out.addAll(smokingByResult.getOrDefault(k, List.of()));
		}
		return out;
	}

	/** 這個原料支援哪些爐種(S4.2e：高爐/煙燻爐優先列出)。 */
	public List<FurnaceKind> supportedFurnaces(String input) {
		String k = norm(input);
		List<FurnaceKind> out = new ArrayList<>(3);
		if (blastingByInput.containsKey(k)) out.add(FurnaceKind.BLAST_FURNACE);
		if (smokingByInput.containsKey(k)) out.add(FurnaceKind.SMOKER);
		if (smeltingByInput.containsKey(k)) out.add(FurnaceKind.FURNACE);
		return out;
	}

	/**
	 * 依 S4.2e 優先順序挑選最佳爐子：自有加速爐 → 其他加速爐 → 自有普通熔爐 → 其他普通熔爐；
	 * 同層級取距離最近者。只考慮 {@code supported} 允許的爐種。
	 */
	public static <T> T selectPreferredFurnace(List<FurnaceCandidate<T>> candidates, Collection<FurnaceKind> supported) {
		FurnaceCandidate<T> best = null;
		int bestRank = Integer.MAX_VALUE;
		for (FurnaceCandidate<T> c : candidates) {
			if (!supported.contains(c.kind())) continue;
			int rank = furnaceRank(c.kind(), c.owned());
			if (best == null || rank < bestRank || (rank == bestRank && c.distSq() < best.distSq())) {
				best = c;
				bestRank = rank;
			}
		}
		return best == null ? null : best.target();
	}

	private static int furnaceRank(FurnaceKind kind, boolean owned) {
		boolean fast = kind != FurnaceKind.FURNACE;
		if (fast && owned) return 0;
		if (fast) return 1;
		return owned ? 2 : 3;
	}

	public int size() {
		return byResult.values().stream().mapToInt(List::size).sum()
			+ smeltingByResult.size() + blastingByResult.size() + smokingByResult.size();
	}

	// ---- 事前檢查 ----

	/**
	 * 用背包計算能不能做出 count 個 item。
	 *
	 * @param inventory    物品 id → 數量(會被複製，不會修改)
	 * @param tableNearby  附近有可以用的工作台
	 */
	public Plan planCraft(String item, int count, Map<String, Integer> inventory, boolean tableNearby) {
		String target = norm(item);
		List<Recipe> options = craftingRecipesFor(target);
		if (options.isEmpty()) return new Plan(List.of(), false, false, Map.of("no_recipe:" + shortId(target), count));

		// 主要配方先試，其次一般替代配方(例如 4 線合白羊毛)，最後才試染色配方(例如把別色的床或羊毛染色)
		List<Recipe> ordered = new ArrayList<>(options);
		ordered.sort((a, b) -> Integer.compare(recipePriority(a), recipePriority(b)));
		Plan best = null;
		int bestPriority = Integer.MAX_VALUE;
		for (Recipe r : ordered) {
			Plan p = planWith(r, count, inventory, tableNearby);
			if (p.ok()) return p;
			// 缺料時報優先度較高的配方缺什麼；同優先度才挑缺得最少的
			int priority = recipePriority(r);
			if (best == null || priority < bestPriority || (priority == bestPriority && total(p.missing()) < total(best.missing()))) {
				best = p;
				bestPriority = priority;
			}
		}
		return best;
	}

	/**
	 * 0=主要配方(檔名與成品同名，例如 white_bed、stick)；
	 * 1=非染色替代配方(例如 white_wool_from_string)；
	 * 2=染色配方(dye_*)。
	 */
	static int recipePriority(Recipe r) {
		if (isPrimary(r)) return 0;
		if (r.id() != null && shortId(norm(r.id())).startsWith("dye_")) return 2;
		return 1;
	}

	/**
	 * 主要配方：原版的配方檔名和成品同名(white_bed、stick、torch)；
	 * 其他做法另外取名(dye_white_bed、stick_from_bamboo_item、iron_ingot_from_nuggets)。
	 */
	static boolean isPrimary(Recipe r) {
		return r.id() != null && shortId(norm(r.id())).equals(shortId(r.result()));
	}

	private static int total(Map<String, Integer> m) {
		return m.values().stream().mapToInt(Integer::intValue).sum();
	}

	private Plan planWith(Recipe r, int count, Map<String, Integer> inventory, boolean tableNearby) {
		Map<String, Integer> inv = new HashMap<>(inventory);
		int crafts = (count + r.resultCount() - 1) / r.resultCount();
		Map<String, Integer> missing = new LinkedHashMap<>();
		Map<String, Integer> consumes = allocate(r, crafts, inv, missing);
		List<Step> steps = new ArrayList<>();
		boolean placesTable = false;
		boolean needsTable = r.needsTable();
		if (needsTable && !tableNearby && inv.getOrDefault("minecraft:crafting_table", 0) == 0) {
			// 需要工作台：用剩下的木板，不夠的話用原木先做木板
			placesTable = true;
			Map<String, Integer> tableMissing = new LinkedHashMap<>();
			List<Step> tableSteps = planTable(inv, tableMissing);
			steps.addAll(tableSteps);
			// 工作台自己缺的木板另外標註，免得和成品配方的木板缺料混成同名兩筆
			tableMissing.forEach((k, v) -> missing.merge(k + "(for_crafting_table)", v, Integer::sum));
		}
		steps.add(new Step(r, crafts, consumes));
		return new Plan(steps, needsTable, placesTable, missing);
	}

	/** 依配方從背包分配材料；不夠的記進 missing。 */
	private Map<String, Integer> allocate(Recipe r, int crafts, Map<String, Integer> inv, Map<String, Integer> missing) {
		Map<String, Integer> consumes = new LinkedHashMap<>();
		// 同一種材料(同一個 Ingredient 內容)合併計算
		Map<Ingredient, Integer> needs = new LinkedHashMap<>();
		for (Ingredient ing : r.grid()) if (ing != null) needs.merge(ing, crafts, Integer::sum);
		for (var en : needs.entrySet()) {
			Ingredient ing = en.getKey();
			int need = en.getValue();
			// 優先用數量最多的那種
			List<String> sorted = new ArrayList<>(ing.options());
			sorted.sort((a, b) -> Integer.compare(inv.getOrDefault(b, 0), inv.getOrDefault(a, 0)));
			for (String opt : sorted) {
				if (need == 0) break;
				int have = inv.getOrDefault(opt, 0);
				int take = Math.min(have, need);
				if (take > 0) {
					inv.put(opt, have - take);
					consumes.merge(opt, take, Integer::sum);
					need -= take;
				}
			}
			if (need > 0) missing.merge(describe(ing), need, Integer::sum);
		}
		return consumes;
	}

	private List<Step> planTable(Map<String, Integer> inv, Map<String, Integer> missing) {
		List<Step> steps = new ArrayList<>();
		Recipe table = craftingRecipesFor("minecraft:crafting_table").stream().findFirst().orElse(null);
		if (table == null) {
			missing.merge("crafting_table", 1, Integer::sum);
			return steps;
		}
		Ingredient planks = table.grid().stream().filter(i -> i != null).findFirst().orElseThrow();
		int havePlanks = planks.options().stream().mapToInt(o -> inv.getOrDefault(o, 0)).sum();
		if (havePlanks < 4) {
			// 用原木做木板：找一個「原木 → 木板」的配方
			int needPlanks = 4 - havePlanks;
			for (String plank : planks.options()) {
				for (Recipe pr : craftingRecipesFor(plank)) {
					if (pr.kind() != Kind.SHAPELESS || pr.grid().size() != 1) continue;
					Ingredient log = pr.grid().getFirst();
					int logs = log.options().stream().mapToInt(o -> inv.getOrDefault(o, 0)).sum();
					int crafts = Math.min(logs, (needPlanks + pr.resultCount() - 1) / pr.resultCount());
					if (crafts <= 0) continue;
					Map<String, Integer> tmpMissing = new LinkedHashMap<>();
					Map<String, Integer> c = allocate(pr, crafts, inv, tmpMissing);
					inv.merge(pr.result(), crafts * pr.resultCount(), Integer::sum);
					steps.add(new Step(pr, crafts, c));
					needPlanks -= crafts * pr.resultCount();
					if (needPlanks <= 0) break;
				}
				if (needPlanks <= 0) break;
			}
		}
		Map<String, Integer> c = allocate(table, 1, inv, missing);
		steps.add(new Step(table, 1, c));
		return steps;
	}

	/** 缺少的材料要怎麼寫給 AI 看(S3.0b)：單一物品寫 id；tag 寫第一個物品加 {@code |#tag}；幾種擇一用 {@code |} 串。 */
	static String describe(Ingredient ing) {
		String label = ing.label();
		if (label.startsWith("#")) {
			String first = ing.options().isEmpty() ? label : shortId(ing.options().getFirst());
			return first + "|" + shortTag(label);
		}
		return label.contains("|") ? label.replace("minecraft:", "") : shortId(label);
	}

	static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	static String shortTag(String tag) {
		return tag.startsWith("#minecraft:") ? "#" + tag.substring(11) : tag;
	}

	// ---- 燃料 ----

	/** 常見燃料的燃燒時間(tick)。26.3 的燃料改成物品資料元件，需要戰利品情境才算得出來，所以用原版數值的表。 */
	public static int burnTicks(String item, boolean isFuel) {
		if (!isFuel) return 0;
		String id = norm(item);
		return switch (id) {
			case "minecraft:coal", "minecraft:charcoal" -> 1600;
			case "minecraft:coal_block" -> 16000;
			case "minecraft:blaze_rod" -> 2400;
			case "minecraft:lava_bucket" -> 20000;
			case "minecraft:dried_kelp_block" -> 4001;
			case "minecraft:bamboo", "minecraft:scaffolding" -> 50;
			case "minecraft:stick", "minecraft:bowl", "minecraft:dead_bush",
				 "minecraft:azalea", "minecraft:flowering_azalea", "minecraft:leaf_litter" -> 100;
			case "minecraft:bamboo_block", "minecraft:stripped_bamboo_block", "minecraft:bamboo_mosaic",
				 "minecraft:crafting_table", "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
				 "minecraft:bookshelf", "minecraft:chiseled_bookshelf", "minecraft:jukebox", "minecraft:note_block",
				 "minecraft:composter", "minecraft:lectern", "minecraft:loom", "minecraft:smithing_table",
				 "minecraft:cartography_table", "minecraft:fletching_table", "minecraft:mangrove_roots",
				 "minecraft:daylight_detector" -> 300;
			default -> {
				if (id.endsWith("_carpet")) yield 50;
				if (id.endsWith("_sapling") || id.endsWith("_wool") || id.endsWith("_button")) yield 100;
				if (id.endsWith("_slab")) yield 150;
				if (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_planks")
					|| id.endsWith("_stem") || id.endsWith("_hyphae")
					|| id.endsWith("_fence") || id.endsWith("_fence_gate")
					|| id.endsWith("_stairs") || id.endsWith("_trapdoor")
					|| id.endsWith("_pressure_plate")) {
					yield 300;
				}
				yield 200; // 木門、告示牌、木船、木製工具等
			}
		};
	}
}
