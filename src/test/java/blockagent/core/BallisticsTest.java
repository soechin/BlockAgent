package blockagent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 弓的仰角(docs/SPEC.md S9.1b)。 */
class BallisticsTest {
	@Test
	void hitsTargetsAtVariousDistancesAndHeights() {
		double[][] cases = {{5, 0}, {12, 0}, {20, 0}, {30, 0}, {15, 5}, {15, -6}, {8, 3}, {40, -10}};
		for (double[] c : cases) {
			var sol = Ballistics.solve(c[0], c[1], Ballistics.FULL_BOW_SPEED);
			assertNotNull(sol, "應該打得到 " + c[0] + "," + c[1]);
			double y = Ballistics.heightAt(c[0], sol.pitchDeg(), Ballistics.FULL_BOW_SPEED);
			assertEquals(c[1], y, 0.5, "落點誤差太大：" + c[0] + "," + c[1]);
			assertTrue(sol.ticks() > 0 && sol.ticks() < 40, "飛行時間不合理：" + sol.ticks());
		}
	}

	@Test
	void aimsAboveTargetBecauseOfGravity() {
		var near = Ballistics.solve(10, 0, Ballistics.FULL_BOW_SPEED);
		var far = Ballistics.solve(30, 0, Ballistics.FULL_BOW_SPEED);
		assertTrue(near.pitchDeg() > 0, "平地也要稍微往上");
		assertTrue(far.pitchDeg() > near.pitchDeg(), "越遠要抬越高");
	}

	@Test
	void tooFarReturnsNull() {
		assertNull(Ballistics.solve(400, 0, Ballistics.FULL_BOW_SPEED));
		assertNull(Ballistics.solve(10, 200, Ballistics.FULL_BOW_SPEED));
	}
}
