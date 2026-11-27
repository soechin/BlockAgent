package blockagent.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 用 26.3 jar 內建的原版配方測試事前檢查(docs/SPEC.md S4.4、T11(a))。 */
class RecipesTest {
	static VanillaData data;
	static Recipes recipes;

	@BeforeAll
	static void load() throws Exception {
		data = VanillaData.fromClasspath(RecipesTest.class.getClassLoader());
		recipes = data.recipes();
	}

	@Test
	void loadsVanillaRecipes() {
		assertTrue(data.recipeCount() > 2000, "應該載入原版配方：" + data.recipeCount());
		assertFalse(recipes.craftingRecipesFor("stone_pickaxe").isEmpty());
		assertEquals("minecraft:iron_ingot", recipes.smeltingFor("raw_iron").result());
		assertTrue(data.itemTag("minecraft:planks").contains("minecraft:oak_planks"));
		assertTrue(data.itemTag("minecraft:logs").contains("minecraft:oak_log"), "tag 裡的 tag 要展開");
	}

	@Test
	void stonePickaxeMissingCobblestone() {
		// T11(a)：背包只有木板和木棍
		var plan = recipes.planCraft("stone_pickaxe", 1, Map.of("minecraft:oak_planks", 8, "minecraft:stick", 4), false);
		assertFalse(plan.ok());
		assertEquals(Map.of("cobblestone|#stone_tool_materials", 3), plan.missing());
	}

	@Test
	void stonePickaxeWithTableFromPlanks() {
		var plan = recipes.planCraft("stone_pickaxe", 1,
			Map.of("minecraft:oak_planks", 4, "minecraft:stick", 2, "minecraft:cobblestone", 3), false);
		assertTrue(plan.ok(), plan.missing().toString());
		assertTrue(plan.needsTable());
		assertTrue(plan.placesTable());
		assertEquals("minecraft:crafting_table", plan.steps().get(0).recipe().result());
		assertEquals("minecraft:stone_pickaxe", plan.steps().getLast().recipe().result());
	}

	@Test
	void tableFromLogsWhenNoPlanks() {
		var plan = recipes.planCraft("stone_pickaxe", 1,
			Map.of("minecraft:oak_log", 1, "minecraft:stick", 2, "minecraft:cobblestone", 3), false);
		assertTrue(plan.ok(), plan.missing().toString());
		assertEquals("minecraft:oak_planks", plan.steps().get(0).recipe().result());
		assertEquals("minecraft:crafting_table", plan.steps().get(1).recipe().result());
	}

	@Test
	void noTableNeededWhenNearby() {
		var plan = recipes.planCraft("stone_pickaxe", 1,
			Map.of("minecraft:stick", 2, "minecraft:cobblestone", 3), true);
		assertTrue(plan.ok());
		assertFalse(plan.placesTable());
		assertEquals(1, plan.steps().size());
	}

	@Test
	void sticksDoNotNeedTableAndMissingSticksAreReported() {
		var sticks = recipes.planCraft("stick", 8, Map.of("minecraft:birch_planks", 4), false);
		assertTrue(sticks.ok());
		assertFalse(sticks.needsTable());
		assertEquals(2, sticks.steps().getFirst().crafts());

		// 「單一目標」：缺木棍不會自動去做，只回報
		var pick = recipes.planCraft("stone_pickaxe", 1, Map.of("minecraft:cobblestone", 3, "minecraft:oak_planks", 10), true);
		assertEquals(Map.of("stick", 2), pick.missing());
	}

	@Test
	void whiteBedReportsMissingPlanksNotDye() {
		// 試玩時：3 個白羊毛、木板不夠，卻回報缺 white_dye 和別色的床(挑到 dye_white_bed)
		var plan = recipes.planCraft("white_bed", 1, Map.of("minecraft:white_wool", 3, "minecraft:oak_planks", 1), true);
		assertEquals(Map.of("oak_planks|#planks", 2), plan.missing());
		assertEquals("minecraft:white_bed", plan.steps().getLast().recipe().id());

		// 染色配方缺得比較少(2 vs 3)也一樣報主要配方
		var none = recipes.planCraft("white_bed", 1, Map.of("minecraft:white_wool", 3), true);
		assertEquals(Map.of("oak_planks|#planks", 3), none.missing());
	}

	@Test
	void primaryRecipeTriedBeforeDyeRecipe() {
		// 兩種做法都夠時，不要把別色的床拿去染
		var plan = recipes.planCraft("white_bed", 1, Map.of("minecraft:white_wool", 3, "minecraft:oak_planks", 3,
			"minecraft:white_dye", 1, "minecraft:orange_bed", 1), true);
		assertTrue(plan.ok());
		assertEquals("minecraft:white_bed", plan.steps().getLast().recipe().id());

		// 只有染色的材料時，還是可以用染色配方
		var dye = recipes.planCraft("white_bed", 1, Map.of("minecraft:white_dye", 1, "minecraft:orange_bed", 1), true);
		assertTrue(dye.ok(), dye.missing().toString());
		assertEquals("minecraft:dye_white_bed", dye.steps().getLast().recipe().id());
	}

	/** R13：需要工作台又缺料時，工作台缺的木板要另外標註 (for_crafting_table)，不和成品木板混在一起。 */
	@Test
	void woodenPickaxeSeparatesCraftingTableMissingPlanks() {
		var plan = recipes.planCraft("wooden_pickaxe", 1, Map.of(), false);
		assertFalse(plan.ok());
		assertEquals(3, plan.missing().get("oak_planks|#wooden_tool_materials"));
		assertEquals(2, plan.missing().get("stick"));
		assertEquals(4, plan.missing().get("oak_planks|#planks(for_crafting_table)"));
		assertEquals(3, plan.missing().size());
	}

	@Test
	void whiteWoolPrefersStringOverDye() {
		var empty = recipes.planCraft("white_wool", 1, Map.of(), false);
		assertFalse(empty.ok());
		assertEquals(Map.of("string", 4), empty.missing());
		assertEquals("minecraft:white_wool_from_string", empty.steps().getLast().recipe().id());

		var fromString = recipes.planCraft("white_wool", 1, Map.of("minecraft:string", 4), false);
		assertTrue(fromString.ok(), fromString.missing().toString());
		assertEquals("minecraft:white_wool_from_string", fromString.steps().getLast().recipe().id());

		var fromDye = recipes.planCraft("white_wool", 1, Map.of("minecraft:orange_wool", 1, "minecraft:white_dye", 1), false);
		assertTrue(fromDye.ok(), fromDye.missing().toString());
		assertEquals("minecraft:dye_white_wool", fromDye.steps().getLast().recipe().id());
	}

	@Test
	void fuelTable() {
		assertEquals(1600, Recipes.burnTicks("coal", true));
		assertEquals(300, Recipes.burnTicks("spruce_log", true));
		assertEquals(300, Recipes.burnTicks("oak_planks", true));
		assertEquals(150, Recipes.burnTicks("spruce_slab", true));
		assertEquals(100, Recipes.burnTicks("stick", true));
		assertEquals(50, Recipes.burnTicks("bamboo", true));
		assertEquals(200, Recipes.burnTicks("wooden_pickaxe", true));
		assertEquals(0, Recipes.burnTicks("dirt", false));
	}

	@Test
	void loadsBlastingAndSmokingRecipesAndFurnacePriority() {
		assertTrue(recipes.blastingRecipeCount() >= 20, "應載入高爐配方：" + recipes.blastingRecipeCount());
		assertTrue(recipes.smokingRecipeCount() >= 8, "應載入煙燻爐配方：" + recipes.smokingRecipeCount());
		assertEquals("minecraft:iron_ingot", recipes.blastingFor("raw_iron").result());
		assertEquals("minecraft:cooked_beef", recipes.smokingFor("beef").result());

		assertEquals(java.util.List.of(Recipes.FurnaceKind.BLAST_FURNACE, Recipes.FurnaceKind.FURNACE),
			recipes.supportedFurnaces("raw_iron"));
		assertEquals(java.util.List.of(Recipes.FurnaceKind.SMOKER, Recipes.FurnaceKind.FURNACE),
			recipes.supportedFurnaces("beef"));
		assertEquals(java.util.List.of(Recipes.FurnaceKind.FURNACE),
			recipes.supportedFurnaces("sand"));

		var ownBlast = new Recipes.FurnaceCandidate<>("own_blast", Recipes.FurnaceKind.BLAST_FURNACE, true, 100);
		var otherBlast = new Recipes.FurnaceCandidate<>("other_blast", Recipes.FurnaceKind.BLAST_FURNACE, false, 4);
		var ownFurnace = new Recipes.FurnaceCandidate<>("own_furnace", Recipes.FurnaceKind.FURNACE, true, 9);
		var otherFurnace = new Recipes.FurnaceCandidate<>("other_furnace", Recipes.FurnaceKind.FURNACE, false, 1);
		var ownSmoker = new Recipes.FurnaceCandidate<>("own_smoker", Recipes.FurnaceKind.SMOKER, true, 1);

		var ironKinds = recipes.supportedFurnaces("raw_iron");
		assertEquals("own_blast", Recipes.selectPreferredFurnace(
			java.util.List.of(otherFurnace, ownFurnace, otherBlast, ownBlast, ownSmoker), ironKinds));
		assertEquals("other_blast", Recipes.selectPreferredFurnace(
			java.util.List.of(otherFurnace, ownFurnace, otherBlast, ownSmoker), ironKinds));
		assertEquals("own_furnace", Recipes.selectPreferredFurnace(
			java.util.List.of(otherFurnace, ownFurnace, ownSmoker), ironKinds));
		assertEquals("other_furnace", Recipes.selectPreferredFurnace(
			java.util.List.of(otherFurnace, ownSmoker), ironKinds));
		assertNull(Recipes.selectPreferredFurnace(java.util.List.of(ownSmoker), ironKinds));
	}
}
