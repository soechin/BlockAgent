package blockagent.game;

import blockagent.mcp.Args;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** build_house 站立高度語意、預設保留自然地面與舊版藍圖重算相容性(docs/SPEC.md S4.8b)。 */
class HousesTest {
	@BeforeAll
	static void boot() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void floorLayerAlignsWithStandingHeight() {
		assertEquals(63, Houses.floorLayerY(64, 64, false), "站在草地(y=64)傳入 y=64 時，地板層應對齊地表(y=63)");
		assertEquals(62, Houses.floorLayerY(64, 63, false), "兩個角高度不同時取低點再減 1");
		assertEquals(64, Houses.floorLayerY(64, 64, true), "舊版地板層語意維持原值");
	}

	@Test
	void naturalFloorOnlyFillsDipsAndNonSturdyCells() {
		assertNull(Houses.naturalFloorFill(63, 63, true), "平地實心草地不需要鋪地板");
		assertNull(Houses.naturalFloorFill(65, 63, true), "高出地面的土坡由室內挖平，不需額外補地基");
		assertEquals(new Houses.FillSpan(63, 63), Houses.naturalFloorFill(62, 63, false), "低 1 格凹洞補 1 格到地板層");
		assertEquals(new Houses.FillSpan(61, 63), Houses.naturalFloorFill(60, 63, false), "低 3 格斜坡補滿 61–63");
		assertEquals(new Houses.FillSpan(63, 63), Houses.naturalFloorFill(63, 63, false), "耕地/草徑等非實心表層只替換那一格");
	}

	@Test
	void legacyHouseSourceUpgradesYAndKeepsFloorOnRegenerate() {
		JsonObject legacy = new JsonObject();
		legacy.addProperty("from", "10,63,20");
		legacy.addProperty("to", "16,63,26");
		legacy.addProperty("height", 4);
		legacy.addProperty("material", "minecraft:oak_planks");
		legacy.addProperty("roof", "minecraft:oak_planks");

		JsonObject changeHeight = new JsonObject();
		changeHeight.addProperty("height", 5);
		JsonObject mergedHeight = Blueprints.mergeHouseSource(legacy, new Args(changeHeight));
		assertEquals("10,64,20", mergedHeight.get("from").getAsString(), "舊版地板層 y=63 應升級成站立高度 y=64");
		assertEquals("16,64,26", mergedHeight.get("to").getAsString());
		assertEquals(Houses.Y_MODE_STANDING, mergedHeight.get("y_mode").getAsString());
		assertEquals("minecraft:oak_planks", mergedHeight.get("floor").getAsString(), "舊版預設整片地板重算時保留");

		JsonObject changeMat = new JsonObject();
		changeMat.addProperty("material", "spruce_planks");
		JsonObject mergedMat = Blueprints.mergeHouseSource(legacy, new Args(changeMat));
		assertEquals("spruce_planks", mergedMat.get("material").getAsString());
		assertEquals("spruce_planks", mergedMat.get("floor").getAsString(), "舊版換牆材時預設地板跟著換");
		assertFalse(mergedMat.has("roof"), "舊版同材質屋頂換牆材時移除，讓它跟著牆換");
	}

	@Test
	void modernHouseWithoutFloorKeepsNaturalGroundAcrossRegenerate() {
		JsonObject modern = new JsonObject();
		modern.addProperty("from", "10,64,20");
		modern.addProperty("to", "16,64,26");
		modern.addProperty("y_mode", Houses.Y_MODE_STANDING);
		modern.addProperty("height", 4);
		modern.addProperty("material", "minecraft:oak_planks");

		JsonObject changeHeight = new JsonObject();
		changeHeight.addProperty("height", 5);
		JsonObject merged = Blueprints.mergeHouseSource(modern, new Args(changeHeight));
		assertEquals("10,64,20", merged.get("from").getAsString(), "新版 y 不再重複加 1");
		assertFalse(merged.has("floor"), "未指定 floor 時重算繼續保留自然地面");

		JsonObject addFloor = new JsonObject();
		addFloor.addProperty("floor", "cobblestone");
		JsonObject withFloor = Blueprints.mergeHouseSource(modern, new Args(addFloor));
		assertEquals("cobblestone", withFloor.get("floor").getAsString(), "事後補傳 floor 可升級成鋪滿地板");
	}
}
