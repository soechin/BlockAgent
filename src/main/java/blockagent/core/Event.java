package blockagent.core;

/**
 * 通知 AI 的事件(docs/SPEC.md S5.2)。
 *
 * @param type  事件種類，例如 chat、task_done、reflex
 * @param level 輕重
 * @param text  給 AI 看的一行說明
 * @param group 合併時用來分組的鍵(同組的事件合成一行)；null 表示用 type
 */
public record Event(String type, Level level, String text, String group, long timeMs) {
	public enum Level {
		/** 立刻讓 wait_events 返回。 */
		URGENT,
		/** 2 秒內返回，讓同時發生的事一起送。 */
		PROMPT,
		/** 累積起來，最多每 15 秒送一則合併摘要。 */
		BATCH
	}

	public String groupKey() {
		return group == null ? type : group;
	}
}
