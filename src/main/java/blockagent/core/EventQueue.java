package blockagent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * 事件佇列：分輕重、合併、長輪詢(docs/SPEC.md S5.1–S5.4)。
 */
public final class EventQueue {
	public static final long PROMPT_DELAY_MS = 2_000;
	public static final long BATCH_INTERVAL_MS = 15_000;
	public static final int CAPACITY = 200;
	public static final String TASK_PROGRESS = "task_progress";

	private final Clock clock;
	private final List<Event> pending = new ArrayList<>();
	private int dropped;
	private long lastBatchDelivery;

	public EventQueue(Clock clock) {
		this.clock = clock;
		this.lastBatchDelivery = clock.millis();
	}

	public void add(String type, Event.Level level, String text) {
		add(new Event(type, level, text, null, clock.millis()));
	}

	public void add(String type, Event.Level level, String text, String group) {
		add(new Event(type, level, text, group, clock.millis()));
	}

	public synchronized void add(Event e) {
		pending.add(e);
		while (pending.size() > CAPACITY) {
			int idx = -1;
			for (int i = 0; i < pending.size(); i++) {
				if (pending.get(i).level() == Event.Level.BATCH) {
					idx = i;
					break;
				}
			}
			pending.remove(idx >= 0 ? idx : 0);
			dropped++;
		}
		notifyAll();
	}

	public synchronized int size() {
		return pending.size();
	}

	/** 現在是否該讓 wait_events 返回。 */
	synchronized boolean ready() {
		return ready(e -> true);
	}

	/** 同上，但只有 {@code wakes} 接受的事件能讓它返回；其他事件留在佇列裡，跟著一起送出。 */
	synchronized boolean ready(Predicate<Event> wakes) {
		if (pending.isEmpty()) return false;
		long now = clock.millis();
		boolean hasBatch = false;
		for (Event e : pending) {
			if (!wakes.test(e)) continue;
			switch (e.level()) {
				case URGENT -> {
					return true;
				}
				case PROMPT -> {
					if (now - e.timeMs() >= PROMPT_DELAY_MS) return true;
				}
				case BATCH -> hasBatch = true;
			}
		}
		return hasBatch && now - lastBatchDelivery >= BATCH_INTERVAL_MS
			&& now - firstBatchTime() >= 0;
	}

	private long firstBatchTime() {
		for (Event e : pending) if (e.level() == Event.Level.BATCH) return e.timeMs();
		return Long.MAX_VALUE;
	}

	/**
	 * 長輪詢：等到有該送出的事件或逾時。逾時回傳空清單。
	 * 為了能用假時鐘測試，等待以 50 ms 為單位輪詢條件。
	 */
	public List<Event> await(long maxWaitMs) throws InterruptedException {
		return await(maxWaitMs, () -> true);
	}

	public List<Event> await(long maxWaitMs, BooleanSupplier keepWaiting) throws InterruptedException {
		return await(maxWaitMs, keepWaiting, e -> true);
	}

	/** {@code wakes}：哪些事件能讓等待提早結束(S5.1 的 task_only 不讓任務進度叫醒)。 */
	public List<Event> await(long maxWaitMs, BooleanSupplier keepWaiting, Predicate<Event> wakes) throws InterruptedException {
		long deadline = clock.millis() + maxWaitMs;
		synchronized (this) {
			while (keepWaiting.getAsBoolean() && !ready(wakes)) {
				long left = deadline - clock.millis();
				if (left <= 0) return List.of();
				wait(Math.min(left, 50));
			}
			return drainAll();
		}
	}

	/**
	 * 等背景任務結束時，哪些事件能提早叫醒(S5.1)。
	 * 任務進度、系統廣播聊天(例如進度通知)、遠距離敵怪察覺與非緊急反射留在佇列裡，
	 * 跟著任務結束或下一次工具呼叫一起送出，免得打斷正在跑的動作或引發串接 BUSY。
	 */
	public static boolean wakesTaskWait(Event e) {
		if (TASK_PROGRESS.equals(e.type())) return false;
		if (e.level() == Event.Level.BATCH) {
			if ("hostile_near".equals(e.type()) || "reflex".equals(e.type())) return false;
			if ("chat".equals(e.type()) && e.text() != null && e.text().startsWith("system=")) return false;
		}
		return true;
	}

	/** 移除還沒送出的某種事件，例如任務結束後已經過時的任務進度。 */
	public synchronized void removeType(String type) {
		pending.removeIf(e -> e.type().equals(type));
	}

	/** 取出並清空所有事件(S5.4 的未讀事件摘要用)。 */
	public synchronized List<Event> drainAll() {
		List<Event> out = new ArrayList<>(pending);
		boolean hadBatch = out.stream().anyMatch(e -> e.level() == Event.Level.BATCH);
		pending.clear();
		if (hadBatch) lastBatchDelivery = clock.millis();
		return out;
	}

	public synchronized int takeDroppedCount() {
		int d = dropped;
		dropped = 0;
		return d;
	}

	/**
	 * 把事件整理成給 AI 看的文字(docs/SPEC.md S3.0e)：{@code [EVENTS]} 之後一則一行，
	 * 每行帶出等級和事件型別({@code [URGENT] user_paused: ...})，AI 依型別分支(A15)。
	 * 合併類(BATCH)同組的事件合成一行：{@code [BATCH] chat(2): Ming="好餓"; Amy="哈哈"}。
	 */
	public static String render(List<Event> events, int droppedCount) {
		if (events.isEmpty() && droppedCount == 0) return "";
		StringBuilder sb = new StringBuilder("[EVENTS]");
		if (droppedCount > 0) sb.append("\n[WARN] events_dropped: n=").append(droppedCount);
		for (Event e : events) {
			if (e.level() != Event.Level.BATCH) line(sb, e.level(), e.type(), e.text());
		}
		Map<String, List<Event>> groups = new LinkedHashMap<>();
		for (Event e : events) {
			if (e.level() == Event.Level.BATCH) groups.computeIfAbsent(e.groupKey(), k -> new ArrayList<>()).add(e);
		}
		for (var en : groups.entrySet()) {
			List<Event> g = en.getValue();
			if (g.size() == 1) {
				line(sb, Event.Level.BATCH, en.getKey(), g.getFirst().text());
			} else {
				StringBuilder joined = new StringBuilder();
				for (Event e : g) {
					if (!joined.isEmpty()) joined.append("; ");
					joined.append(e.text());
				}
				line(sb, Event.Level.BATCH, en.getKey() + "(" + g.size() + ")", joined.toString());
			}
		}
		return sb.toString();
	}

	private static void line(StringBuilder sb, Event.Level level, String label, String text) {
		sb.append("\n[").append(level.name()).append("] ").append(label).append(':');
		String t = text == null ? "" : text.strip().replace("\r", " ").replace("\n", " ");
		if (!t.isEmpty()) sb.append(' ').append(t);
	}
}
