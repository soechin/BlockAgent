package blockagent.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 觀察摘要的共用格式(docs/SPEC.md S3.1)。時間的切點和倒數在 CoreLogicTest(DayClock)。 */
class ObservationTest {
	@Test
	void formatsMobPositionsWithBabyFlag() {
		String out = Observation.formatMobPositions(List.of(
			new Observation.MobEntry(55, 28, 110, -89, false),
			new Observation.MobEntry(57, 28, 110, -90, true)
		));
		assertEquals("55@28,110,-89;57@28,110,-90:baby", out);
	}

	@Test
	void shortTextIsUnchanged() {
		assertEquals("a=1\nb=2", Observation.truncate(new StringBuilder("a=1\nb=2"), 0, "hint"));
	}

	@Test
	void hiddenEntriesAreReported() {
		assertEquals("a=1\ntruncated=true hidden=3 | hint=detail=true lists more",
			Observation.truncate(new StringBuilder("a=1"), 3, "detail=true lists more"));
	}

	@Test
	void longTextIsCutAtALineBoundary() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; sb.length() <= Observation.MAX_CHARS + 200; i++) sb.append("- line=").append(i).append('\n');
		String out = Observation.truncate(sb, 0, "a smaller radius is shorter");
		String[] lines = out.split("\n");
		assertEquals("truncated=true | hint=a smaller radius is shorter", lines[lines.length - 1]);
		String body = out.substring(0, out.lastIndexOf('\n'));
		assertTrue(body.length() <= Observation.MAX_CHARS, "本文不能超過上限：" + body.length());
		for (int i = 0; i < lines.length - 1; i++) assertTrue(lines[i].matches("- line=\\d+"), "不能切在一行中間：" + lines[i]);
	}

	@Test
	void inventoryFullTriggersOncePerDropToZero() {
		// 初始已是 0 格(prevFree=-1)不應誤觸發
		var init = Observation.checkInventoryFull(-1, 0, false);
		assertFalse(init.fire());
		// 從 > 0 降至 0：觸發一次並標記已回報
		var drop = Observation.checkInventoryFull(1, 0, false);
		assertTrue(drop.fire());
		assertTrue(drop.reported());
		// 持續維持 0 格：不重發
		var stillFull = Observation.checkInventoryFull(0, 0, drop.reported());
		assertFalse(stillFull.fire());
		assertTrue(stillFull.reported());
		// 騰出空格後重置
		var freed = Observation.checkInventoryFull(0, 2, stillFull.reported());
		assertFalse(freed.fire());
		assertFalse(freed.reported());
		// 再次滿格時重新觸發一次
		var dropAgain = Observation.checkInventoryFull(2, 0, freed.reported());
		assertTrue(dropAgain.fire());
		assertTrue(dropAgain.reported());
	}
}
