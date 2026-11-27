package blockagent.game.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CraftTask} 容器同步與補格純邏輯測試。 */
class CraftTaskTest {

	@Test
	void advanceSyncSettlesAfterConsecutiveQuietTicksWithEmptyCursor() {
		CraftTask.SyncProgress t1 = CraftTask.advanceSync(10, 10, true, 0);
		assertEquals(10, t1.lastStateId());
		assertEquals(1, t1.quietTicks());
		assertFalse(t1.settled());

		CraftTask.SyncProgress t2 = CraftTask.advanceSync(t1.lastStateId(), 10, true, t1.quietTicks());
		assertEquals(10, t2.lastStateId());
		assertEquals(2, t2.quietTicks());
		assertTrue(t2.settled());
	}

	@Test
	void advanceSyncWaitsThroughIntermediateStateIdAndCarriedSnapshot() {
		// 模擬放木板觸發中繼配方：先收到游標非空的中途快照(stateId 10 -> 12)，再收到歸還後快照(12 -> 13)
		CraftTask.SyncProgress mid = CraftTask.advanceSync(10, 12, false, 1);
		assertEquals(12, mid.lastStateId());
		assertEquals(0, mid.quietTicks());
		assertFalse(mid.settled());

		// 即使 stateId 沒變，只要游標還沒清空也不能視為穩定
		CraftTask.SyncProgress stillCarried = CraftTask.advanceSync(mid.lastStateId(), 12, false, mid.quietTicks());
		assertEquals(0, stillCarried.quietTicks());
		assertFalse(stillCarried.settled());

		// 最終回補快照抵達：游標清空但 stateId 剛變化，先歸零安靜計數
		CraftTask.SyncProgress finalPacket = CraftTask.advanceSync(stillCarried.lastStateId(), 13, true, stillCarried.quietTicks());
		assertEquals(13, finalPacket.lastStateId());
		assertEquals(0, finalPacket.quietTicks());
		assertFalse(finalPacket.settled());

		// 接著連續兩個安靜 tick 才算對齊
		CraftTask.SyncProgress q1 = CraftTask.advanceSync(finalPacket.lastStateId(), 13, true, finalPacket.quietTicks());
		assertFalse(q1.settled());
		CraftTask.SyncProgress q2 = CraftTask.advanceSync(q1.lastStateId(), 13, true, q1.quietTicks());
		assertTrue(q2.settled());
	}

	@Test
	void cellShortfallComputesRemainingNeeded() {
		assertEquals(2, CraftTask.cellShortfall(0, 2));
		assertEquals(1, CraftTask.cellShortfall(1, 2));
		assertEquals(0, CraftTask.cellShortfall(2, 2));
		assertEquals(0, CraftTask.cellShortfall(5, 2));
		assertEquals(2, CraftTask.cellShortfall(-1, 2));
	}
}
