package blockagent.core;

/**
 * 一天 24000 tick 的階段與倒數(docs/SPEC.md S3.2、docs/REVIEW.md M17)。切點對齊原版：
 * <ul>
 *   <li>{@code day}：0–11999 與 23460–23999(23460 起不死系在晴天會自燃)</li>
 *   <li>{@code dusk}：12000–13187(晴天 12542 起可以睡)</li>
 *   <li>{@code night}：13188–22799(地表開始生怪)</li>
 *   <li>{@code dawn}：22800–23459</li>
 * </ul>
 * 不依賴 Minecraft，方便單元測試。
 */
public final class DayClock {
	public static final long DAY = 24000;
	public static final long DUSK_START = 12000;
	/** 晴天開始可上床睡覺的 tick(原版 12542–23459)。 */
	public static final long SLEEP_START = 12542;
	public static final long SLEEP_END = 23459;
	public static final long NIGHT_START = 13188;
	public static final long DAWN_START = 22800;
	public static final long DAY_START = 23460;
	/** 倒數低於這個 tick 數(2 分鐘)就改用秒數，也是 Fmt.status 開始附倒數的門檻。 */
	public static final long SOON = 2 * 60 * 20;

	private DayClock() {
	}

	public static long normalize(long clock) {
		return Math.floorMod(clock, DAY);
	}

	public static String phase(long clock) {
		long t = normalize(clock);
		if (t < DUSK_START || t >= DAY_START) return "day";
		if (t < NIGHT_START) return "dusk";
		if (t < DAWN_START) return "night";
		return "dawn";
	}

	/** 天色暗了(黃昏到天亮前)：自己經歷過這段之後跨進白天才發 daybreak。 */
	public static boolean isDark(long clock) {
		return !phase(clock).equals("day");
	}

	/** 現在能不能上床睡覺(晴天 12542–23459，或雷雨隨時可睡)。 */
	public static boolean canSleep(long clock, boolean thundering) {
		if (thundering) return true;
		long t = normalize(clock);
		return t >= SLEEP_START && t <= SLEEP_END;
	}

	/** 離晴天可上床睡覺(12542)還有幾 tick；已經可以睡時為 0。 */
	public static long ticksUntilSleep(long clock) {
		long t = normalize(clock);
		if (t >= SLEEP_START && t <= SLEEP_END) return 0;
		return t < SLEEP_START ? SLEEP_START - t : DAY - t + SLEEP_START;
	}

	/** 距可睡時間的倒數欄位(例如 {@code sleep_in=27s})；已經可以睡時為 null。 */
	public static String sleepCountdown(long clock) {
		long left = ticksUntilSleep(clock);
		return left == 0 ? null : "sleep_in=" + duration(left);
	}

	/** 下一個要倒數的鍵：白天倒數到黃昏、黃昏倒數到生怪、晚上和黎明倒數到天亮。 */
	public static String countdownKey(long clock) {
		return switch (phase(clock)) {
			case "day" -> "dusk_in";
			case "dusk" -> "night_in";
			default -> "day_in";
		};
	}

	/** 離下一個切點還有幾 tick。 */
	public static long ticksLeft(long clock) {
		long t = normalize(clock);
		long target = switch (phase(t)) {
			case "day" -> t >= DAY_START ? DAY + DUSK_START : DUSK_START;
			case "dusk" -> NIGHT_START;
			default -> DAY_START;
		};
		return target - t;
	}

	/** 倒數的寫法：四捨五入後 2 分鐘(120 秒)以上寫分鐘({@code 5min})，以下寫秒數({@code 50s})，避免 2390..2399 印出 120s(Low)。 */
	public static String duration(long ticks) {
		long sec = Math.round(ticks / 20.0);
		if (sec < 120) return Math.max(1, sec) + "s";
		return Math.round(ticks / 1200.0) + "min";
	}

	/** 觀察工具用：{@code time=day dusk_in=5min}(天氣另外加)。 */
	public static String describe(long clock) {
		return "time=" + phase(clock) + " " + countdownKey(clock) + "=" + duration(ticksLeft(clock));
	}

	/** 任務結果用的精簡版：只有快換階段(2 分鐘內)才附倒數，例如 {@code time=day dusk_in=90s}。 */
	public static String brief(long clock) {
		long left = ticksLeft(clock);
		return left < SOON ? describe(clock) : "time=" + phase(clock);
	}
}
