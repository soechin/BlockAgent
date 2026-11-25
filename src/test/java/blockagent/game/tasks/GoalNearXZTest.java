package blockagent.game.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** goto 省略 y 時的水平目標(GoalNearXZ)。 */
class GoalNearXZTest {
	@Test
	void ignoresYAndChecksHorizontalRange() {
		var goal = new GotoTask.GoalNearXZ(100, -200, 4);
		assertTrue(goal.isInGoal(100, 64, -200));
		assertTrue(goal.isInGoal(103, 180, -198), "高度差不影響水平到達判定");
		assertTrue(goal.isInGoal(104, -50, -200), "剛好在半徑邊界內");
		assertFalse(goal.isInGoal(103, 64, -197), "斜向距離 sqrt(18) > 4");
		assertFalse(goal.isInGoal(105, 64, -200));
	}

	@Test
	void zeroRangeRequiresExactXZ() {
		var exact = new GotoTask.GoalNearXZ(10, 20, 0);
		assertTrue(exact.isInGoal(10, 99, 20));
		assertTrue(exact.isInGoal(10, -64, 20));
		assertFalse(exact.isInGoal(11, 99, 20));
		assertFalse(exact.isInGoal(10, 99, 21));
	}

	@Test
	void distanceBlocksMeasuresLinearDistance() {
		assertEquals(100, GotoTask.distanceBlocks(0, 64, 0, 100, 64, 0));
		assertEquals(5, GotoTask.distanceBlocks(0, 64, 0, 3, 64, 4));
		assertEquals(13, GotoTask.distanceBlocks(0, 64, 0, 3, 76, 4), "有 y 時算三維直線距離");
		assertEquals(5, GotoTask.distanceBlocks(0, 64, 0, 3, null, 4), "省略 y 時只算水平距離");
		assertEquals(0, GotoTask.distanceBlocks(10, 64, -20, 10, null, -20));
	}
}
