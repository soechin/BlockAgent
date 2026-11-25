package blockagent.game;

import blockagent.core.Session;
import blockagent.mcp.AiPresence;
import blockagent.mcp.McpServer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 畫面左上角的 AI 狀態、想法與目標(docs/SPEC.md S8.3)。每 10 tick 更新一次文字。
 */
public final class Hud {
	private static final int GREEN = 0xFF55FF55, YELLOW = 0xFFFFFF55, GRAY = 0xFFAAAAAA, RED = 0xFFFF5555;

	private static final int DETAIL_COLOR = 0xFFE8E8E8;
	private static final int MAX_TEXT_WIDTH = 300;
	private static final String GOAL_PREFIX = "目標｜", GOAL_SEPARATOR = "/";

	private record HudLine(String text, int color) {}
	private record RenderLine(FormattedCharSequence text, int color) {}

	private final Agent agent;
	private String cachedLine = "";
	private Agent.HudStatus cachedPlan;
	private List<HudLine> cachedLines = List.of();
	private int cachedColor = GRAY;
	private long lastUpdateTick = -100;
	private int layoutWidth = -1;
	private int textWidth;
	private List<RenderLine> renderedLines = List.of();

	Hud(Agent agent) {
		this.agent = agent;
	}

	public void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("blockagent", "status"), this::render);
	}

	/** 目前畫面上的文字(測試用)。 */
	public String line() {
		return cachedLine;
	}

	/** 目前面板由上到下的文字(測試用)。 */
	public List<String> lines() {
		return cachedLines.stream().map(HudLine::text).toList();
	}

	void update() {
		Session s = agent.session;
		Agent.HudStatus status = agent.hudStatus();
		String what = status.text().isBlank() ? agent.tasks.describeCurrent() : status.text();
		layoutWidth = -1;
		long remainMin = s.hasTimeLimit() ? (s.remainingMs() + 59_999) / 60_000 : -1;
		switch (s.state()) {
			case RUNNING -> {
				AiPresence p = agent.aiPresence();
				cachedColor = p == AiPresence.NONE || p == AiPresence.SILENT ? GRAY : GREEN;
				cachedLine = runningLine(what, p, remainMin);
			}
			case PAUSED -> {
				cachedColor = YELLOW;
				cachedLine = "❚❚ AI 暫停｜按 ` 恢復";
			}
			case READY -> {
				cachedColor = GRAY;
				cachedLine = "○ AI 待命｜按 ` 開始";
			}
			case ENDED -> {
				cachedColor = RED;
				cachedLine = "■ AI 已結束";
			}
			default -> {
				cachedColor = GRAY;
				cachedLine = "";
			}
		}

		cachedPlan = s.isRunning() || s.state() == Session.State.PAUSED ? status : null;
	}

	/**
	 * 工作階段進行中的那一行(S8.3)。AI 端不在時換掉開頭，免得 AI 已經離開還顯示「AI 進行中｜想下一步」。
	 * 中間那段：有 set_status 或任務描述就用它，否則依 AI 端的活動顯示。
	 */
	/** remainMin 小於 0 表示不限時，不顯示剩餘時間。 */
	static String runningLine(String what, AiPresence p, long remainMin) {
		boolean has = what != null && !what.isBlank();
		String head, middle;
		switch (p) {
			case NONE -> {
				head = "● AI 未連線";
				middle = has ? what : "請啟動 Claude Code 或 Codex";
			}
			case SILENT -> {
				head = "● AI 沒有回應";
				middle = has ? what : "超過 " + McpServer.SILENT_AFTER_MS / 60_000 + " 分鐘沒有動靜";
			}
			case BUSY -> {
				head = "● AI 進行中";
				middle = has ? what : "等事件";
			}
			default -> {
				head = "● AI 進行中";
				middle = has ? what : "想下一步";
			}
		}
		return head + "｜" + middle + (remainMin >= 0 ? "｜剩 " + remainMin + " 分" : "");
	}

	private static String goalLine(Font font, int maxWidth, String longGoal, String shortGoal) {
		if (longGoal.isBlank()) return fitText(font, GOAL_PREFIX + shortGoal, maxWidth);
		if (shortGoal.isBlank()) return fitText(font, GOAL_PREFIX + longGoal, maxWidth);

		int available = maxWidth - font.width(GOAL_PREFIX + GOAL_SEPARATOR);
		if (available < 2 * font.width("…")) {
			return fitText(font, GOAL_PREFIX + longGoal + GOAL_SEPARATOR + shortGoal, maxWidth);
		}
		// 兩個目標各有可見空間，文字較短的一側可以讓出空間。
		int longWidth = Math.min(font.width(longGoal), Math.max(available / 2, available - font.width(shortGoal)));
		return GOAL_PREFIX + fitText(font, longGoal, longWidth) + GOAL_SEPARATOR + fitText(font, shortGoal, available - longWidth);
	}

	private static String fitText(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		int ellipsisWidth = font.width("…");
		if (maxWidth < ellipsisWidth) return font.plainSubstrByWidth(text, maxWidth);
		return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + "…";
	}

	private void layout(Font font, int maxWidth) {
		List<HudLine> rows = new ArrayList<>(3);
		rows.add(new HudLine(cachedLine, cachedColor));
		if (cachedPlan != null) {
			if (!cachedPlan.longGoal().isBlank() || !cachedPlan.shortGoal().isBlank()) {
				rows.add(new HudLine(goalLine(font, maxWidth, cachedPlan.longGoal(), cachedPlan.shortGoal()), DETAIL_COLOR));
			}
			if (!cachedPlan.thought().isBlank()) rows.add(new HudLine("想法｜" + cachedPlan.thought(), DETAIL_COLOR));
		}
		cachedLines = List.copyOf(rows);
		List<RenderLine> lines = new ArrayList<>();
		for (HudLine line : rows) {
			for (FormattedCharSequence text : font.split(Component.literal(line.text()), maxWidth)) {
				lines.add(new RenderLine(text, line.color()));
			}
		}
		textWidth = 0;
		for (RenderLine line : lines) textWidth = Math.max(textWidth, font.width(line.text()));
		renderedLines = lines;
		layoutWidth = maxWidth;
	}

	private void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden() || agent.session.state() == Session.State.NO_WORLD) return;
		if (agent.tick() - lastUpdateTick >= 10 || agent.tick() < lastUpdateTick) {
			update();
			lastUpdateTick = agent.tick();
		}
		if (cachedLine.isEmpty()) return;
		if (agent.control.hudCollapsed()) {
			g.fill(3, 3, 9, 9, 0x90000000);
			g.fill(4, 4, 8, 8, cachedColor);
			return;
		}
		int maxWidth = Math.min(MAX_TEXT_WIDTH, Math.max(1, g.guiWidth() - 8));
		if (layoutWidth != maxWidth) layout(mc.font, maxWidth);
		int lineHeight = mc.font.lineHeight + 2;
		g.fill(2, 2, 6 + textWidth, 3 + renderedLines.size() * lineHeight, 0x90000000);
		int y = 4;
		for (RenderLine line : renderedLines) {
			g.text(mc.font, line.text(), 4, y, line.color(), false);
			y += lineHeight;
		}
	}
}
