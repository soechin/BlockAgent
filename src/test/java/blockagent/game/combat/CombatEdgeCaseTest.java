package blockagent.game.combat;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 掩體重試節流、起點容錯與換目標／弩瞄準的邊界測試。 */
class CombatEdgeCaseTest {
	@Test
	void coverRouteThrottlesOnlyAfterFailedSearch() {
		// 原本有有效掩體但剛失效：即使距前一次失敗不到 10 tick，也應立即重搜，不誤回 null
		assertFalse(CoverRoute.shouldThrottleRetry(true, 105, 100));
		// 原本無掩體且剛搜尋失敗：10 tick 內節流，滿 10 tick 再重試
		assertTrue(CoverRoute.shouldThrottleRetry(false, 105, 100));
		assertTrue(CoverRoute.shouldThrottleRetry(false, 109, 100));
		assertFalse(CoverRoute.shouldThrottleRetry(false, 110, 100));
	}

	@Test
	void coverRouteResolvesOriginForNonFullBlocksWaterAndJump() {
		BlockPos start = new BlockPos(10, 64, 10);
		assertEquals(start, CoverRoute.resolveOrigin(start, true, true, false, false));
		// 站在草徑／耕地／半磚上(onGround=true 但 canStand=false)：仍從目前格展開
		assertEquals(start, CoverRoute.resolveOrigin(start, false, true, false, false));
		// 在淺水中：仍從目前格展開往岸上找掩體
		assertEquals(start, CoverRoute.resolveOrigin(start, false, false, true, false));
		// 起跳瞬間(高 1 格且腳下可站立)：退回腳下格展開
		assertEquals(start.below(), CoverRoute.resolveOrigin(start, false, false, false, true));
		// 高空墜落：不規劃地面掩體
		assertNull(CoverRoute.resolveOrigin(start, false, false, false, false));
	}

	@Test
	void combatResetsOutmatchedOnDeathOrDeepWaterRetarget() {
		assertTrue(Combat.shouldResetOutmatchedOnRetarget(true, "disengage_outmatched"));
		assertTrue(Combat.shouldResetOutmatchedOnRetarget(true, "back_off"));
		assertTrue(Combat.shouldResetOutmatchedOnRetarget(false, "leave_deep_water"));
		// 正在逃離怪群且舊目標仍活著時，切目標不重置脫身狀態
		assertFalse(Combat.shouldResetOutmatchedOnRetarget(false, "disengage_outmatched"));
		assertFalse(Combat.shouldResetOutmatchedOnRetarget(false, "back_off"));
	}

	@Test
	void crossbowAimChecksBothYawAndPitch() {
		assertFalse(Combat.crossbowAimChanged(10f, 12f, -5f, -3f));
		assertFalse(Combat.crossbowAimChanged(179f, -179f, 0f, 0f), "跨 ±180 度差距只有 2 度");
		assertTrue(Combat.crossbowAimChanged(10f, 15f, 0f, 0f), "水平角差超過 4 度應重等同步");
		assertTrue(Combat.crossbowAimChanged(10f, 10f, -15f, -10f), "仰角差超過 4 度應重等同步");
	}
}
