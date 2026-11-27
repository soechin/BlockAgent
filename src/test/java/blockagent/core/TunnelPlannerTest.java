package blockagent.core;

import blockagent.core.TunnelPlanner.Dir;
import blockagent.core.TunnelPlanner.Mode;
import blockagent.core.TunnelPlanner.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TunnelPlannerTest {
	@Test
	void validateEnforcesUnlitCapAndTargetYBounds() {
		assertNull(TunnelPlanner.validate(Mode.STAIRS_DOWN, 32, -54, 64, 8));
		assertNull(TunnelPlanner.validate(Mode.STAIRS_UP, 32, 64, 20, 8));
		assertNull(TunnelPlanner.validate(Mode.FLAT, 12, null, 64, 0));
		assertNotNull(TunnelPlanner.validate(Mode.FLAT, 13, null, 64, 0));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS_DOWN, 16, -54, 64, 0));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS_UP, 16, 64, 20, 0));
		assertNotNull(TunnelPlanner.validate(Mode.FLAT, 8, 20, 64, 8));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS_DOWN, 8, 64, 64, 8));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS_DOWN, 8, 70, 64, 8));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS_UP, 8, 20, 20, 8));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS_UP, 8, 10, 20, 8));
		assertEquals(Mode.STAIRS_DOWN, Mode.parse("stairs_down"));
		assertEquals(Mode.STAIRS_UP, Mode.parse("stairs_up"));
		assertEquals(Mode.FLAT, Mode.parse("flat"));
		assertNull(Mode.parse("stairs"));
		assertNull(Mode.parse("straight"));
		assertNull(Mode.parse("diagonal"));
		assertEquals("stairs_down", Mode.STAIRS_DOWN.wireName());
		assertEquals("stairs_up", Mode.STAIRS_UP.wireName());
		assertEquals("flat", Mode.FLAT.wireName());
	}

	@Test
	void targetStepsUsesEarlierOfLengthOrTargetY() {
		assertEquals(10, TunnelPlanner.targetSteps(Mode.STAIRS_DOWN, 30, 54, 64));
		assertEquals(16, TunnelPlanner.targetSteps(Mode.STAIRS_DOWN, 16, -54, 64));
		assertEquals(12, TunnelPlanner.targetSteps(Mode.STAIRS_UP, 30, 32, 20));
		assertEquals(10, TunnelPlanner.targetSteps(Mode.STAIRS_UP, 10, 64, 20));
		assertEquals(20, TunnelPlanner.targetSteps(Mode.FLAT, 20, null, 64));
	}

	@Test
	void sliceGeometryFlat1x2AndStairs1x3TopDown() {
		Pos cur = new Pos(0, 10, 0);
		Pos nextFlat = TunnelPlanner.nextStand(cur, Mode.FLAT, Dir.EAST);
		assertEquals(new Pos(1, 10, 0), nextFlat);
		assertEquals(List.of(new Pos(1, 11, 0), new Pos(1, 10, 0)),
			TunnelPlanner.mineSlice(cur, nextFlat, Mode.FLAT));

		Pos nextStairs = TunnelPlanner.nextStand(cur, Mode.STAIRS_DOWN, Dir.NORTH);
		assertEquals(new Pos(0, 9, -1), nextStairs);
		assertEquals(List.of(new Pos(0, 11, -1), new Pos(0, 10, -1), new Pos(0, 9, -1)),
			TunnelPlanner.mineSlice(cur, nextStairs, Mode.STAIRS_DOWN));

		Pos nextUp = TunnelPlanner.nextStand(cur, Mode.STAIRS_UP, Dir.WEST);
		assertEquals(new Pos(-1, 11, 0), nextUp);
		assertEquals(List.of(new Pos(0, 12, 0), new Pos(-1, 12, 0), new Pos(-1, 11, 0)),
			TunnelPlanner.mineSlice(cur, nextUp, Mode.STAIRS_UP));
		// 向上階梯鄰接與水流檢查要涵蓋頭頂上方兩格，避免挖穿上方湖泊或岩漿
		List<Pos> upWater = TunnelPlanner.waterWatchCells(TunnelPlanner.mineSlice(cur, nextUp, Mode.STAIRS_UP), cur);
		assertTrue(upWater.contains(new Pos(0, 13, 0)));
		assertTrue(upWater.contains(new Pos(-1, 13, 0)));
	}

	@Test
	void adjacentCellsExcludeMineSliceAndPlayerBody() {
		Pos cur = new Pos(0, 10, 0);
		Pos next = TunnelPlanner.nextStand(cur, Mode.FLAT, Dir.EAST);
		List<Pos> slice = TunnelPlanner.mineSlice(next, Mode.FLAT);
		List<Pos> adj = TunnelPlanner.adjacentCells(slice, cur);
		assertFalse(adj.contains(cur));
		assertFalse(adj.contains(cur.offset(0, 1, 0)));
		for (Pos c : slice) assertFalse(adj.contains(c));
		// 前方、頂部、底部、左右壁都應納入檢查
		assertTrue(adj.contains(new Pos(2, 10, 0)));
		assertTrue(adj.contains(new Pos(1, 12, 0)));
		assertTrue(adj.contains(new Pos(1, 9, 0)));
		assertTrue(adj.contains(new Pos(1, 10, 1)));
		assertTrue(adj.contains(new Pos(1, 10, -1)));

		List<Pos> waterWatch = TunnelPlanner.waterWatchCells(slice, cur);
		assertTrue(waterWatch.contains(new Pos(1, 12, 0)));
		assertTrue(waterWatch.contains(new Pos(2, 10, 0)));
		assertFalse(waterWatch.contains(new Pos(1, 9, 0)));
	}

	@Test
	void dropDepthAndTorchThreshold() {
		assertEquals(0, TunnelPlanner.dropDepth(new boolean[]{true, true, true}));
		assertEquals(2, TunnelPlanner.dropDepth(new boolean[]{false, false, true}));
		assertFalse(TunnelPlanner.isUnsafeDrop(2));
		assertEquals(3, TunnelPlanner.dropDepth(new boolean[]{false, false, false}));
		assertTrue(TunnelPlanner.isUnsafeDrop(3));

		assertFalse(TunnelPlanner.shouldPlaceTorch(0, 8, 0));
		assertFalse(TunnelPlanner.shouldPlaceTorch(8, 7, 0));
		assertFalse(TunnelPlanner.shouldPlaceTorch(8, 8, 8));
		assertTrue(TunnelPlanner.shouldPlaceTorch(8, 8, 7));
		assertTrue(TunnelPlanner.shouldPlaceTorch(8, 8, 0));
	}
}
