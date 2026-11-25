package blockagent.core;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作階段狀態(docs/SPEC.md S8.1)。
 * NO_WORLD → 進入世界 → READY → 按鍵 → RUNNING ⇄ PAUSED → 時間到/離開/斷線 → ENDED
 */
public final class Session {
	public enum State {NO_WORLD, READY, RUNNING, PAUSED, ENDED}

	public enum EndReason {TIME_LIMIT, LEFT_WORLD, DISCONNECTED}

	public interface Listener {
		void onChange(State from, State to, String cause);
	}

	private final Clock clock;
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private volatile State state = State.NO_WORLD;
	private volatile long startedAt = -1;
	private volatile long timeLimitMs;
	private volatile EndReason endReason;
	private volatile boolean warnedFiveMinutes;

	public Session(Clock clock, long timeLimitMs) {
		this.clock = Objects.requireNonNull(clock);
		this.timeLimitMs = timeLimitMs;
	}

	public void addListener(Listener l) {
		listeners.add(l);
	}

	public State state() {
		return state;
	}

	public EndReason endReason() {
		return endReason;
	}

	public boolean isRunning() {
		return state == State.RUNNING;
	}

	/** 世界裡、有工作階段(READY/RUNNING/PAUSED)。失焦不暫停也只在這時生效(S8.4)。 */
	public boolean inWorldSession() {
		return state == State.READY || state == State.RUNNING || state == State.PAUSED;
	}

	public void setTimeLimitMs(long ms) {
		this.timeLimitMs = ms;
	}

	public long timeLimitMs() {
		return timeLimitMs;
	}

	/** 從第一次開始到現在經過的毫秒；還沒開始是 0。 */
	public long elapsedMs() {
		return startedAt < 0 ? 0 : clock.millis() - startedAt;
	}

	/** 有沒有設定時間上限(0 或負數表示不限時)。 */
	public boolean hasTimeLimit() {
		return timeLimitMs > 0;
	}

	/** 剩餘毫秒；還沒開始時回傳完整上限；不限時回傳 Long.MAX_VALUE。 */
	public long remainingMs() {
		if (!hasTimeLimit()) return Long.MAX_VALUE;
		if (startedAt < 0) return timeLimitMs;
		return Math.max(0, timeLimitMs - (clock.millis() - startedAt));
	}

	public synchronized void enterWorld() {
		endReason = null;
		startedAt = -1;
		warnedFiveMinutes = false;
		set(State.READY, "進入世界");
	}

	/** 暫停鍵：READY/PAUSED → RUNNING，RUNNING → PAUSED。回傳新狀態；ENDED/NO_WORLD 不變。 */
	public synchronized State toggle(String cause) {
		switch (state) {
			case READY -> {
				startedAt = clock.millis();
				set(State.RUNNING, cause);
			}
			case PAUSED -> set(State.RUNNING, cause);
			case RUNNING -> set(State.PAUSED, cause);
			default -> {
			}
		}
		return state;
	}

	/** 使用者接手(S8.5)：RUNNING → PAUSED。 */
	public synchronized boolean pause(String cause) {
		if (state != State.RUNNING) return false;
		set(State.PAUSED, cause);
		return true;
	}

	public synchronized void end(EndReason reason) {
		if (state == State.NO_WORLD || state == State.ENDED) {
			if (reason != EndReason.TIME_LIMIT) state = State.ENDED;
			if (endReason == null) endReason = reason;
			return;
		}
		endReason = reason;
		set(State.ENDED, reason.name());
	}

	/** 每 tick 呼叫：時間到就結束。回傳 true 表示剛好剩 5 分鐘(只回傳一次)。 */
	public synchronized boolean tick() {
		if (!hasTimeLimit() || startedAt < 0 || (state != State.RUNNING && state != State.PAUSED)) return false;
		long remaining = remainingMs();
		if (remaining <= 0) {
			end(EndReason.TIME_LIMIT);
			return false;
		}
		if (!warnedFiveMinutes && remaining <= 5 * 60_000L && timeLimitMs > 5 * 60_000L) {
			warnedFiveMinutes = true;
			return true;
		}
		return false;
	}

	private void set(State to, String cause) {
		State from = state;
		if (from == to) return;
		state = to;
		for (Listener l : listeners) {
			try {
				l.onChange(from, to, cause);
			} catch (RuntimeException ignored) {
				// 監聽者的錯誤不影響狀態
			}
		}
	}
}
