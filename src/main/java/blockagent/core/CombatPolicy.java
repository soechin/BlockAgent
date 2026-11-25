package blockagent.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 戰鬥與反射的純邏輯決策(無 Minecraft 依賴，可直接由 JUnit 測試)。
 */
public final class CombatPolicy {
	private CombatPolicy() {
	}

	/**
	 * 近戰/苦力怕/遠程戰術中，是否該停在攻擊距離內等冷卻/舉盾，而不是呼叫 {@code approach()} 繞路。
	 * 隔著方塊看不到目標({@code !hasLineOfSight})時必須回傳 {@code false}，否則會在牆前卡死(P3-H4)。
	 */
	public static boolean shouldWaitInReach(boolean inReach, boolean hasLineOfSight) {
		return inReach && hasLineOfSight;
	}

	/**
	 * 尚未拉弓時，是否具備放箭前提(視線/彈道沒被屋頂或牆擋住、身上有弓)。
	 * 必須在放下盾牌之前先檢查，避免屋頂下每 tick 收盾又舉盾(P3-H5)。
	 */
	public static boolean canPrepareShot(boolean hasClearShot, boolean hasBow) {
		return hasClearShot && hasBow;
	}

	/**
	 * 脫困反射結束時，是否應算作連續失敗 3 次並中斷原任務。
	 * 被新任務替換({@code task_replaced})或取消({@code task_cancelled})時不算失敗(P3-M13)。
	 */
	public static boolean shouldReportUnstuckFailure(String how, boolean moved, int attempts) {
		if (moved || attempts < 3) return false;
		return !"task_replaced".equals(how) && !"task_cancelled".equals(how);
	}

	/**
	 * 自衛反射收手後的單隻生物冷卻表(H8、P3-M2)：
	 * <ul>
	 *   <li>被更高優先級反射搶佔({@code preempted == true})時不加冷卻，免得保命完忽略身邊的苦力怕。</li>
	 *   <li>只有在「冷卻開始<strong>之後</strong>」該生物再次攻擊自己({@code lastAttackedTick > startTick})才提早解除冷卻，
	 *       避免收手前 20 tick 內的舊傷在下一 tick 立刻繞過冷卻。</li>
	 * </ul>
	 */
	public static final class DefenseCooldown {
		private record Entry(long startTick, long untilTick) {
		}

		private final Map<Integer, Entry> entries = new HashMap<>();

		public void put(int entityId, long startTick, long durationTicks, boolean preempted) {
			if (preempted || durationTicks <= 0) return;
			entries.put(entityId, new Entry(startTick, startTick + durationTicks));
		}

		public void prune(long nowTick) {
			entries.values().removeIf(e -> e.untilTick() <= nowTick);
		}

		public boolean isEmpty() {
			return entries.isEmpty();
		}

		public void clear() {
			entries.clear();
		}

		/**
		 * @param entityId         候選目標 entity id
		 * @param nowTick          目前遊戲 tick
		 * @param recentAttackerId 最近打自己的生物 id(無則 -1)
		 * @param lastAttackedTick 最近一次被打的 tick
		 */
		public boolean isCoolingDown(int entityId, long nowTick, int recentAttackerId, long lastAttackedTick) {
			Entry e = entries.get(entityId);
			if (e == null) return false;
			if (e.untilTick() <= nowTick) {
				entries.remove(entityId);
				return false;
			}
			// 只有冷卻開始之後的新攻擊才解除冷卻
			if (entityId == recentAttackerId && lastAttackedTick > e.startTick()) return false;
			return true;
		}
	}
}
