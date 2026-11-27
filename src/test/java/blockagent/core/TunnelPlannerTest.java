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
		assertNull(TunnelPlanner.validate(Mode.STAIRS, 32, -54, 64, 8));
		assertNull(TunnelPlanner.validate(Mode.STRAIGHT, 12, null, 64, 0));
		assertNotNull(TunnelPlanner.validate(Mode.STRAIGHT, 13, null, 64, 0));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS, 16, -54, 64, 0));
		assertNotNull(TunnelPlanner.validate(Mode.STRAIGHT, 8, 20, 64, 8));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS, 8, 64, 64, 8));
		assertNotNull(TunnelPlanner.validate(Mode.STAIRS, 8, 70, 64, 8));
	}

	@Test
	void targetStepsUsesEarlierOfLengthOrTargetY() {
		assertEquals(10, TunnelPlanner.targetSteps(Mode.STAIRS, 30, 54, 64));
		assertEquals(16, TunnelPlanner.targetSteps(Mode.STAIRS, 16, -54, 64));
		assertEquals(20, TunnelPlanner.targetSteps(Mode.STRAIGHT, 20, null, 64));
	}

	@Test
	void sliceGeometryStraight1x2AndStairs1x3TopDown() {
		Pos cur = new Pos(0, 10, 0);
		Pos nextStraight = TunnelPlanner.nextStand(cur, Mode.STRAIGHT, Dir.EAST);
		assertEquals(new Pos(1, 10, 0), nextStraight);
		assertEquals(List.of(new Pos(1, 11, 0), new Pos(1, 10, 0)),
			TunnelPlanner.mineSlice(nextStraight, Mode.STRAIGHT));

		Pos nextStairs = TunnelPlanner.nextStand(cur, Mode.STAIRS, Dir.NORTH);
		assertEquals(new Pos(0, 9, -1), nextStairs);
		assertEquals(List.of(new Pos(0, 11, -1), new Pos(0, 10, -1), new Pos(0, 9, -1)),
			TunnelPlanner.mineSlice(nextStairs, Mode.STAIRS));
	}

	@Test
	void adjacentCellsExcludeMineSliceAndPlayerBody() {
		Pos cur = new Pos(0, 10, 0);
		Pos next = TunnelPlanner.nextStand(cur, Mode.STRAIGHT, Dir.EAST);
		List<Pos> slice = TunnelPlanner.mineSlice(next, Mode.STRAIGHT);
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
