package blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 目前計畫(docs/SPEC.md S5.6)：目標、建議和依序要做的步驟，加上做到第幾步。
 * 規劃服務送來的({@code planner})和 AI 自己寫的({@code ai})都存在這裡；模組只負責保管和提醒下一步，
 * 步驟有沒有做完由 AI 判斷(呼叫 plan(action=done))。
 * MCP 執行緒和 client thread 都會讀寫，所以全部 synchronized。
 */
public final class Plan {
	public static final int MAX_STEPS = 8;
	/** 規劃服務的步驟和建議合計最多 1,500 字(bin/blockagent-planner)，單步最多 500 字。 */
	public static final int MAX_STEP = 500;
	public static final int MAX_GOAL = 500;
	public static final int MAX_RECOMMENDATION = 1500;

	private String goal;
	private String recommendation = "";
	private String source = "";
	private List<String> steps = List.of();
	private int index;

	/** 換成新計畫；回傳 null 表示成功，否則是錯誤原因(不改變目前計畫)。 */
	public synchronized String set(String goal, String recommendation, List<String> steps, String source) {
		String g = clean(goal);
		if (g.isEmpty() || g.length() > MAX_GOAL) return "goal must be 1 to " + MAX_GOAL + " characters";
		String rec = clean(recommendation);
		if (rec.length() > MAX_RECOMMENDATION) return "recommendation is over " + MAX_RECOMMENDATION + " characters";
		if (steps == null) return "steps must not be empty";
		List<String> list = new ArrayList<>();
		for (String s : steps) {
			String c = clean(s);
			if (c.isEmpty()) continue;
			if (c.length() > MAX_STEP) return "each step must be at most " + MAX_STEP + " characters";
			list.add(c);
		}
		if (list.isEmpty() || list.size() > MAX_STEPS) return "steps must have 1 to " + MAX_STEPS + " entries";
		this.goal = g;
		this.recommendation = rec;
		this.steps = List.copyOf(list);
		this.source = source == null ? "" : source;
		this.index = 0;
		return null;
	}

	/** 目前這一步做完，往下一步；回傳做完的是第幾步(從 1 起算)，沒有計畫或已經全部做完時回傳 0。 */
	public synchronized int done() {
		if (goal == null || index >= steps.size()) return 0;
		index++;
		return index;
	}

	public synchronized void clear() {
		goal = null;
		recommendation = "";
		source = "";
		steps = List.of();
		index = 0;
	}

	public synchronized boolean isEmpty() {
		return goal == null;
	}

	public synchronized boolean finished() {
		return goal != null && index >= steps.size();
	}

	public synchronized String goal() {
		return goal;
	}

	/** 目前要做的步驟；沒有計畫或已經做完時是 null。 */
	public synchronized String current() {
		return goal == null || index >= steps.size() ? null : steps.get(index);
	}

	/** 附在任務結果、閒置提醒等事件後面的一段：{@code plan_step=2/5 "…"}；做完時是 {@code plan=finished}；沒有計畫時是空字串。 */
	public synchronized String reminder() {
		if (goal == null) return "";
		if (index >= steps.size()) return "plan=finished";
		return "plan_step=" + (index + 1) + "/" + steps.size() + " " + quote(steps.get(index));
	}

	/** 給 AI 和規劃服務看的完整計畫。 */
	public synchronized String render() {
		if (goal == null) return "plan=none";
		StringBuilder sb = new StringBuilder();
		sb.append("goal=").append(quote(goal)).append(" source=").append(source.isEmpty() ? "ai" : source)
			.append(" progress=").append(Math.min(index, steps.size())).append('/').append(steps.size());
		if (!recommendation.isEmpty()) sb.append("\nrecommendation=").append(quote(recommendation));
		for (int i = 0; i < steps.size(); i++) {
			String mark = i < index ? "done" : i == index ? "now" : "todo";
			sb.append('\n').append(i + 1).append(". [").append(mark).append("] ").append(steps.get(i));
		}
		return sb.toString();
	}

	public synchronized JsonObject toJson() {
		JsonObject o = new JsonObject();
		if (goal == null) return o;
		o.addProperty("goal", goal);
		o.addProperty("recommendation", recommendation);
		o.addProperty("source", source);
		JsonArray arr = new JsonArray();
		steps.forEach(arr::add);
		o.add("steps", arr);
		o.addProperty("index", index);
		return o;
	}

	/** 讀存檔；格式不對就當成沒有計畫。 */
	public synchronized void loadJson(JsonObject o) {
		clear();
		if (o == null || !o.has("goal")) return;
		try {
			List<String> list = new ArrayList<>();
			for (JsonElement e : o.getAsJsonArray("steps")) list.add(e.getAsString());
			String rec = o.has("recommendation") ? o.get("recommendation").getAsString() : "";
			String src = o.has("source") ? o.get("source").getAsString() : "";
			if (set(o.get("goal").getAsString(), rec, list, src) != null) {
				clear();
				return;
			}
			int i = o.has("index") ? o.get("index").getAsInt() : 0;
			index = Math.max(0, Math.min(i, steps.size()));
		} catch (RuntimeException e) {
			clear();
		}
	}

	private static String clean(String s) {
		return s == null ? "" : s.strip().replaceAll("\\s*\\R\\s*", " ");
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
