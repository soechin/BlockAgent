package blockagent.core;

/**
 * 閒置提醒(docs/SPEC.md S5.3)：沒有任務，而且一段時間內 AI 除了 wait_events 沒有呼叫任何工具。
 */
public final class IdleTracker {
	private final Clock clock;
	private volatile long intervalMs;
	private volatile long lastActivity;

	public IdleTracker(Clock clock, long intervalMs) {
		this.clock = clock;
		this.intervalMs = intervalMs;
		this.lastActivity = clock.millis();
	}

	public void setIntervalMs(long ms) {
		this.intervalMs = ms;
	}

	/** AI 呼叫了 wait_events 以外的工具，或任務正在進行。 */
	public void touch() {
		lastActivity = clock.millis();
	}

	/** 定期呼叫；該提醒時回傳 true，並重新計時。 */
	public boolean poll(boolean taskActive) {
		if (taskActive) {
			touch();
			return false;
		}
		if (clock.millis() - lastActivity >= intervalMs) {
			touch();
			return true;
		}
		return false;
	}
}
