package blockagent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 目前計畫(docs/SPEC.md S5.6)。 */
class PlanTest {
	@Test
	void stepsAdvanceUntilFinished() {
		Plan p = new Plan();
		assertTrue(p.isEmpty());
		assertEquals("", p.reminder());
		assertEquals("plan=none", p.render());
		assertEquals(0, p.done(), "沒有計畫時不能往下");

		assertNull(p.set("過第一晚", "先求安全", List.of("砍 16 塊原木", " ", "蓋 5x5 的房子\n今晚睡裡面"), "planner"));
		assertEquals("砍 16 塊原木", p.current());
		assertEquals("plan_step=1/2 \"砍 16 塊原木\"", p.reminder(), "空白步驟略過");
		assertEquals(1, p.done());
		assertEquals("plan_step=2/2 \"蓋 5x5 的房子 今晚睡裡面\"", p.reminder(), "步驟裡的換行變成空白");
		assertEquals(2, p.done());
		assertTrue(p.finished());
		assertNull(p.current());
		assertEquals("plan=finished", p.reminder());
		assertEquals(0, p.done(), "做完之後不能再往下");
		assertTrue(p.render().contains("progress=2/2") && p.render().contains("2. [done]"), p.render());
	}

	@Test
	void invalidPlansKeepTheOldOne() {
		Plan p = new Plan();
		assertNull(p.set("目標", "", List.of("一"), "ai"));
		assertNotNull(p.set(" ", "", List.of("x"), "ai"));
		assertNotNull(p.set("g", "", List.of(), "ai"));
		assertNotNull(p.set("g", "", java.util.Collections.nCopies(Plan.MAX_STEPS + 1, "x"), "ai"));
		assertNotNull(p.set("g", "", List.of("x".repeat(Plan.MAX_STEP + 1)), "ai"));
		assertEquals("目標", p.goal());
		p.clear();
		assertTrue(p.isEmpty());
	}

	@Test
	void jsonRoundTripKeepsProgress() {
		Plan p = new Plan();
		p.set("找村莊", "往北", List.of("備糧", "往北走", "標記座標"), "planner");
		p.done();
		Plan q = new Plan();
		q.loadJson(p.toJson());
		assertEquals(p.render(), q.render());
		assertEquals("往北走", q.current());
		var broken = p.toJson();
		broken.addProperty("index", 99);
		q.loadJson(broken);
		assertTrue(q.finished(), "超出範圍的進度夾到最後");
		broken.remove("steps");
		q.loadJson(broken);
		assertTrue(q.isEmpty(), "格式不對就當成沒有計畫");
		q.loadJson(null);
		assertTrue(q.isEmpty());
	}
}
