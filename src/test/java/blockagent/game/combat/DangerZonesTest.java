package blockagent.game.combat;

import blockagent.core.CombatPolicy;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DangerZones 記憶去重、半徑保留、活體合併與維度隔離測試。 */
class DangerZonesTest {
	@Test
	void zombieNextToSkeletonDoesNotOverwriteSkeletonRadius() {
		List<DangerZones.Zone> remembered = new ArrayList<>();
		var skel = new DangerZones.Zone(10, new BlockPos(10, 64, 10), CombatPolicy.AVOID_RANGED_RADIUS, "skeleton", "minecraft:overworld", 1200);
		var zomb = new DangerZones.Zone(11, new BlockPos(11, 64, 10), CombatPolicy.AVOID_MELEE_RADIUS, "zombie", "minecraft:overworld", 1200);
		DangerZones.mergeRemembered(remembered, skel, 24);
		DangerZones.mergeRemembered(remembered, zomb, 24);

		assertEquals(2, remembered.size(), "近戰怪不可覆蓋旁邊遠程怪的 16 格避怪圈");
		assertTrue(remembered.stream().anyMatch(z -> z.what().equals("skeleton") && z.radius() == CombatPolicy.AVOID_RANGED_RADIUS));
		assertTrue(remembered.stream().anyMatch(z -> z.what().equals("zombie") && z.radius() == CombatPolicy.AVOID_MELEE_RADIUS));
	}

	@Test
	void sameEntityUpdatesPositionWithoutDuplicating() {
		List<DangerZones.Zone> remembered = new ArrayList<>();
		DangerZones.mergeRemembered(remembered, new DangerZones.Zone(10, new BlockPos(0, 64, 0), 16, "skeleton", "minecraft:overworld", 1200), 24);
		DangerZones.mergeRemembered(remembered, new DangerZones.Zone(10, new BlockPos(12, 64, 0), 16, "skeleton", "minecraft:overworld", 1400), 24);

		assertEquals(1, remembered.size());
		assertEquals(new BlockPos(12, 64, 0), remembered.getFirst().center());
	}

	@Test
	void combineZonesDeduplicatesLiveEntitiesAndFiltersDimensionAndExpiry() {
		var liveSkel = new DangerZones.Zone(10, new BlockPos(5, 64, 5), 16, "skeleton", "minecraft:overworld", 100);
		List<DangerZones.Zone> remembered = new ArrayList<>(List.of(
			// 同一隻活體骷髏：不應重複計入
			new DangerZones.Zone(10, new BlockPos(4, 64, 5), 16, "skeleton", "minecraft:overworld", 1300),
			// 已經走出載入範圍的另一隻骷髏：應保留
			new DangerZones.Zone(20, new BlockPos(30, 64, 0), 16, "skeleton", "minecraft:overworld", 1300),
			// 地獄的記憶：在主世界不該套用
			new DangerZones.Zone(30, new BlockPos(5, 64, 5), 16, "blaze", "minecraft:the_nether", 1300),
			// 已過期：應清除
			new DangerZones.Zone(40, new BlockPos(8, 64, 8), 8, "zombie", "minecraft:overworld", 50)
		));

		List<DangerZones.Zone> combined = DangerZones.combineZones(List.of(liveSkel), remembered, "minecraft:overworld", 100);
		assertEquals(2, combined.size());
		assertEquals(List.of(10, 20), combined.stream().map(DangerZones.Zone::entityId).toList());
		// 過期的 40 號應已從 remembered 移除，不同維度的 30 號則保留給回到地獄時使用
		assertFalse(remembered.stream().anyMatch(z -> z.entityId() == 40));
		assertTrue(remembered.stream().anyMatch(z -> z.entityId() == 30));
	}
}
