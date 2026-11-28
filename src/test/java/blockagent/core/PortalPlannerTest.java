package blockagent.core;

import blockagent.core.PortalPlanner.Cell;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PortalPlannerTest {
	@Test
	void layoutAlongXHasTenFrameFourCornersAndSixInterior() {
		var l = PortalPlanner.layout(10, 64, -3, true);
		assertEquals(PortalPlanner.OBSIDIAN, l.frame().size());
		assertEquals(4, l.corners().size());
		assertEquals(6, l.interior().size());
		Set<Cell> all = new HashSet<>(l.frame());
		all.addAll(l.corners());
		all.addAll(l.interior());
		assertEquals(20, all.size(), "格子不能重疊");
		assertTrue(l.corners().containsAll(Set.of(new Cell(10, 64, -3), new Cell(13, 64, -3), new Cell(10, 68, -3), new Cell(13, 68, -3))));
		assertTrue(l.frame().containsAll(Set.of(new Cell(11, 64, -3), new Cell(12, 68, -3), new Cell(10, 65, -3), new Cell(13, 67, -3))));
		assertTrue(l.interior().containsAll(Set.of(new Cell(11, 65, -3), new Cell(12, 67, -3))));
		assertEquals(new Cell(11, 64, -3), l.base());
		assertTrue(l.frame().contains(l.base()), "點火點是底框");
		for (Cell c : all) assertEquals(-3, c.z(), "沿 x 展開時 z 不變");
	}

	@Test
	void layoutAlongZKeepsX() {
		var l = PortalPlanner.layout(0, 70, 5, false);
		assertEquals(new Cell(0, 70, 6), l.base());
		assertTrue(l.interior().contains(new Cell(0, 71, 6)));
		assertTrue(l.interior().contains(new Cell(0, 73, 7)));
		assertTrue(l.corners().contains(new Cell(0, 74, 8)));
		for (Cell c : l.frame()) assertEquals(0, c.x());
	}

	@Test
	void linkedCoordinatesUseEightToOne() {
		assertArrayEquals(new int[]{12, -2}, PortalPlanner.linkedXZ(100, -9, true));
		assertArrayEquals(new int[]{-1, 0}, PortalPlanner.linkedXZ(-1, 7, true), "負座標往下取整");
		assertArrayEquals(new int[]{96, -16}, PortalPlanner.linkedXZ(12, -2, false));
	}
}
