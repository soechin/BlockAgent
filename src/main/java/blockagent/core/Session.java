package blockagent.core;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作階段狀態(docs/SPEC.md S8.1)。
 * NO_WORLD → 進入世界 → READY → 按鍵 → RUNNING ⇄ PAUSED → 離開/斷線 → ENDED
 */
public final class Session {
	public enum State {NO_WORLD, READY, RUNNING, PAUSED, ENDED}

	public enum EndReason {LEFT_WORLD, DISCONNECTED}

	public interface Listener {
		void onChange(State from, State to, String cause);
	}

	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private volatile State state = State.NO_WORLD;
	private volatile EndReason endReason;

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

	public synchronized void enterWorld() {
		endReason = null;
		set(State.READY, "進入世界");
	}

	/** 暫停鍵：READY/PAUSED → RUNNING，RUNNING → PAUSED。回傳新狀態；ENDED/NO_WORLD 不變。 */
	public synchronized State toggle(String cause) {
		switch (state) {
			case READY, PAUSED -> set(State.RUNNING, cause);
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
			state = State.ENDED;
			if (endReason == null) endReason = reason;
			return;
		}
		endReason = reason;
		set(State.ENDED, reason.name());
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
