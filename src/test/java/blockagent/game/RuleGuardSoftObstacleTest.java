package blockagent.game;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RuleGuard#isSoftPathObstacle} 採礦尋路軟障礙分類測試。 */
class RuleGuardSoftObstacleTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void allowsSoftPlantsAndSnowButRejectsSolidTerrain() {
		assertTrue(RuleGuard.isSoftPathObstacle(Blocks.OAK_LEAVES.defaultBlockState()));
		assertTrue(RuleGuard.isSoftPathObstacle(Blocks.AZALEA_LEAVES.defaultBlockState()));
		assertTrue(RuleGuard.isSoftPathObstacle(Blocks.SHORT_GRASS.defaultBlockState()));
		assertTrue(RuleGuard.isSoftPathObstacle(Blocks.DANDELION.defaultBlockState()));
		assertTrue(RuleGuard.isSoftPathObstacle(Blocks.SNOW.defaultBlockState()));
		assertTrue(RuleGuard.isSoftPathObstacle(Blocks.COBWEB.defaultBlockState()));
		assertTrue(RuleGuard.isSoftPathObstacle(Blocks.VINE.defaultBlockState()));

		assertFalse(RuleGuard.isSoftPathObstacle(Blocks.STONE.defaultBlockState()));
		assertFalse(RuleGuard.isSoftPathObstacle(Blocks.DEEPSLATE.defaultBlockState()));
		assertFalse(RuleGuard.isSoftPathObstacle(Blocks.DIRT.defaultBlockState()));
		assertFalse(RuleGuard.isSoftPathObstacle(Blocks.GRANITE.defaultBlockState()));
		assertFalse(RuleGuard.isSoftPathObstacle(Blocks.OAK_LOG.defaultBlockState()));
		assertFalse(RuleGuard.isSoftPathObstacle(Blocks.IRON_ORE.defaultBlockState()));
		assertFalse(RuleGuard.isSoftPathObstacle(Blocks.OAK_PLANKS.defaultBlockState()));
	}
}
