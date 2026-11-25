package blockagent.game;

import blockagent.mcp.Args;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AreaInspection.Region 體積與整數溢位防禦測試(P3-M10)。 */
class AreaInspectionTest {
	@Test
	void acceptsExactMaxVolumeAndRejectsOneOver() {
		var ok = AreaInspection.Region.between(new BlockPos(0, 0, 0), new BlockPos(31, 31, 31));
		assertEquals(AreaInspection.MAX_VOLUME, ok.volume());
		var tooBig = AreaInspection.Region.between(new BlockPos(0, 0, 0), new BlockPos(32, 31, 31));
		assertThrows(Args.BadArgument.class, tooBig::volume);
	}

	@Test
	void rejectsInvertedOrOverflowedCoordinates() {
		// 模擬 Integer.MIN_VALUE.below() 或 Integer.MAX_VALUE.above(2) 溢位後造成的 max < min 反轉
		var inverted = new AreaInspection.Region(new BlockPos(0, Integer.MAX_VALUE, 0), new BlockPos(0, Integer.MIN_VALUE, 0));
		assertThrows(Args.BadArgument.class, inverted::volume);
		var invertedY = new AreaInspection.Region(new BlockPos(0, 10, 0), new BlockPos(0, 5, 0));
		assertThrows(Args.BadArgument.class, invertedY::volume);
	}
}
