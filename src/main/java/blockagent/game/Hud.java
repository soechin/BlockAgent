package blockagent.game;

import blockagent.core.Session;
import blockagent.game.reflex.Reflexes;
import blockagent.game.tasks.Task;
import blockagent.mcp.AiPresence;
import blockagent.mcp.McpServer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 畫面左上角的 AI 面板(docs/SPEC.md S8.3)：最多 2 行，每行都是單行，太長用「…」截短。
 * 第一行由模組產生(狀態、任務和進度、剩餘時間)；第二行是 AI 用 set_status 寫的一句話。
 * 只有狀態標頭著色，正文用白色。每 10 tick 更新一次內容。
 */
public final class Hud {
	static final int GREEN = 0xFF55FF55, ORANGE = 0xFFFFAA00, SOFT_RED = 0xFFFF7755, YELLOW = 0xFFFFFF55,
		GRAY = 0xFFAAAAAA, RED = 0xFFFF5555;

	private static final int BODY_COLOR = 0xFFF2F2F2, DETAIL_COLOR = 0xFFE8E8E8, DIM_COLOR = 0xFF999999;
	private static final int BACKGROUND = 0x60000000;
	private static final int MAX_TEXT_WIDTH = 340, PAD = 4, GAP = 4, MARGIN = 2;

	/** 狀態標頭：符號加(異常時的)狀態字。 */
	record Head(String label, int color) {}

	/** 一行：縮排、著色的標頭、選用的反射標籤、可截短的正文，以及接在正文後面的灰色文字(計數、剩餘時間)。 */
	private record Row(int indent, String head, int headColor, String tag, String body, int bodyColor, String trailer) {
		Row withBody(String b) {
			return new Row(indent, head, headColor, tag, b, bodyColor, trailer);
		}
	}

	private final Agent agent;
	private Head cachedHead;
	private String cachedTag, cachedBody, cachedTrailer, cachedSay;
	private long lastUpdateTick = -100;
	private int layoutWidth = -1;
	private int panelWidth;
	private List<Row> rows = List.of();
	private List<String> plainLines = List.of();

	Hud(Agent agent) {
		this.agent = agent;
	}

	public void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("blockagent", "status"), (g, _) -> render(g));
	}

	/** 第一行的文字(測試用)。 */
	public String line() {
		return cachedHead == null ? "" : join(cachedHead.label(), cachedTag, cachedBody, cachedTrailer);
	}

	/** 目前面板由上到下的文字(測試用；是畫面上截短後的樣子)。 */
	public List<String> lines() {
		return plainLines;
	}

	// ---- 純邏輯(單元測試) ----

	/** 狀態標頭；沒有世界時回 null(不畫)。 */
	static Head head(Session.State state, AiPresence presence) {
		return switch (state) {
			case RUNNING -> switch (presence) {
				case NONE -> new Head("○ 未連線", SOFT_RED);
				case SILENT -> new Head("● 無回應", ORANGE);
				default -> new Head("●", GREEN);
			};
			case PAUSED -> new Head("❚❚ 暫停", YELLOW);
			case READY -> new Head("○ 待命", GRAY);
			case ENDED -> new Head("■ 已結束", RED);
			default -> null;
		};
	}

	/** 反射接手時標頭後面的標籤(S9)。 */
	static String reflexTag(Reflexes.Kind kind) {
		if (kind == null) return null;
		return switch (kind) {
			case DROWNING -> "換氣";
			case LAVA_AVOID -> "離開岩漿";
			case ON_FIRE -> "滅火";
			case FALL -> "防摔";
			case RETREAT -> "撤退";
			case SELF_DEFENSE -> "自衛";
			case EAT -> "進食";
			case UNSTUCK -> "脫困";
		};
	}

	/** 第一行的正文：任務描述，沒有任務時依 AI 端狀態的預設文字。AI 寫的話在第二行，不會蓋掉任務。 */
	static String body(Session.State state, AiPresence presence, String taskDesc) {
		return switch (state) {
			case RUNNING -> {
				if (taskDesc != null && !taskDesc.isBlank()) yield taskDesc;
				yield switch (presence) {
					case NONE -> "請啟動 Claude Code 或 Codex";
					case SILENT -> "超過 " + McpServer.SILENT_AFTER_MS / 60_000 + " 分鐘沒有動靜";
					case BUSY -> "等事件";
					default -> "想下一步";
				};
			}
			case PAUSED -> "按 ` 恢復";
			case READY -> "按 ` 開始";
			default -> "";
		};
	}

	// ---- 更新與繪製 ----

	void update() {
		Session s = agent.session;
		Session.State state = s.state();
		AiPresence presence = agent.aiPresence();
		Task task = agent.tasks.current();
		boolean running = state == Session.State.RUNNING;

		cachedHead = head(state, presence);
		cachedTag = running ? reflexTag(agent.reflexes.active()) : null;
		cachedBody = body(state, presence, task == null ? null : task.describe());
		Task.Progress p = running && task != null ? task.hudProgress() : null;
		String count = p != null && p.total() > 0 ? p.done() + "/" + p.total() : null;
		String left = running && s.hasTimeLimit() ? "剩 " + (s.remainingMs() + 59_999) / 60_000 + " 分" : null;
		cachedTrailer = count == null ? left : left == null ? count : count + " · " + left;
		String say = agent.statusText();
		cachedSay = (running || state == Session.State.PAUSED) && !say.isBlank() ? say : null;
		layoutWidth = -1;
	}

	private static String join(String... parts) {
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part == null || part.isEmpty()) continue;
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(part);
		}
		return sb.toString();
	}

	static String fitText(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		int ellipsisWidth = font.width("…");
		if (maxWidth < ellipsisWidth) return font.plainSubstrByWidth(text, Math.max(0, maxWidth));
		return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + "…";
	}

	/** 扣掉縮排、標頭、標籤和尾巴後，留給正文的寬度。 */
	private static int bodyMaxWidth(Font font, int maxWidth, Row r) {
		int w = r.indent();
		if (!r.head().isEmpty()) w += font.width(r.head()) + GAP;
		if (r.tag() != null) w += font.width(r.tag()) + GAP;
		if (r.trailer() != null) w += GAP + font.width(r.trailer());
		return maxWidth - w;
	}

	/** 這一行實際畫出來的寬度(跟 render 一樣：每段非空的文字後面接 GAP，最後一段不算)。 */
	private static int rowWidth(Font font, Row r) {
		int w = 0;
		for (String part : new String[]{r.head(), r.tag(), r.body(), r.trailer()}) {
			if (part != null && !part.isEmpty()) w += font.width(part) + GAP;
		}
		return r.indent() + Math.max(0, w - GAP);
	}

	private void layout(Font font, int maxWidth) {
		List<Row> result = new ArrayList<>(2);
		Row first = new Row(0, cachedHead.label(), cachedHead.color(), cachedTag, cachedBody, BODY_COLOR, cachedTrailer);
		result.add(first.withBody(fitText(font, cachedBody, bodyMaxWidth(font, maxWidth, first))));
		if (cachedSay != null) {
			// 跟第一行的正文對齊，一眼看出是另一個來源
			Row say = new Row(font.width(cachedHead.label()) + GAP, "", 0, null, "", DETAIL_COLOR, null);
			result.add(say.withBody(fitText(font, cachedSay, bodyMaxWidth(font, maxWidth, say))));
		}
		int width = 0;
		List<String> plain = new ArrayList<>(result.size());
		for (Row r : result) {
			width = Math.max(width, rowWidth(font, r));
			plain.add(join(r.head(), r.tag(), r.body(), r.trailer()));
		}
		rows = List.copyOf(result);
		plainLines = List.copyOf(plain);
		panelWidth = Math.min(maxWidth, width);
		layoutWidth = maxWidth;
	}

	private void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden() || agent.session.state() == Session.State.NO_WORLD) return;
		if (agent.tick() - lastUpdateTick >= 10 || agent.tick() < lastUpdateTick) {
			update();
			lastUpdateTick = agent.tick();
		}
		if (cachedHead == null) return;
		if (agent.control.hudCollapsed()) {
			g.fill(3, 3, 9, 9, 0x90000000);
			g.fill(4, 4, 8, 8, cachedHead.color());
			return;
		}
		Font font = mc.font;
		int maxWidth = Math.min(MAX_TEXT_WIDTH, Math.max(1, g.guiWidth() - 2 * (MARGIN + PAD)));
		if (layoutWidth != maxWidth) layout(font, maxWidth);
		int lineHeight = font.lineHeight + 2;
		int left = MARGIN + PAD, right = left + panelWidth;
		g.fill(MARGIN, MARGIN, right + PAD, MARGIN + PAD + rows.size() * lineHeight - 2 + PAD, BACKGROUND);
		int y = MARGIN + PAD;
		for (Row r : rows) {
			int x = left + r.indent();
			x = draw(g, font, r.head(), x, y, r.headColor());
			if (r.tag() != null) x = draw(g, font, r.tag(), x, y, ORANGE);
			x = draw(g, font, r.body(), x, y, r.bodyColor());
			if (r.trailer() != null) draw(g, font, r.trailer(), x, y, DIM_COLOR);
			y += lineHeight;
		}
	}

	/** 畫一段文字，回傳下一段的起點(含間距)。 */
	private static int draw(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
		if (text.isEmpty()) return x;
		g.text(font, text, x, y, color, true);
		return x + font.width(text) + GAP;
	}
}
