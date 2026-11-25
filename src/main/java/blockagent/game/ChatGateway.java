package blockagent.game;

import blockagent.core.ChatPolicy;
import blockagent.core.Event;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 收聊天(docs/SPEC.md S3.5)與發言(S6)。
 * 這裡只把訊息交給事件佇列，**不會**觸發任何動作(S4.7)。
 */
public final class ChatGateway {
	public record Line(String time, String sender, String text, boolean self) {
	}

	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final ThreadLocal<Boolean> AI_SENDING = ThreadLocal.withInitial(() -> false);
	private static final ThreadLocal<Boolean> USER_SENDING = ThreadLocal.withInitial(() -> false);

	private final Agent agent;
	private final Deque<Line> recent = new ArrayDeque<>();

	ChatGateway(Agent agent) {
		this.agent = agent;
	}

	public static boolean aiSending() {
		return AI_SENDING.get();
	}

	/** 以「AI 發言中」的身分執行(S6.3 的最後防線會檢查這段期間送出的聊天)。 */
	public static void withAiFlag(Runnable r) {
		AI_SENDING.set(true);
		try {
			r.run();
		} finally {
			AI_SENDING.set(false);
		}
	}

	/** 使用者自己從畫面送出(聊天畫面打字、點聊天或書本裡的連結、伺服器對話框)的期間(H6)。 */
	public static boolean userSending() {
		return USER_SENDING.get();
	}

	public static void withUserFlag(Runnable r) {
		boolean before = USER_SENDING.get();
		USER_SENDING.set(true);
		try {
			r.run();
		} finally {
			USER_SENDING.set(before);
		}
	}

	/**
	 * S6.3 最後防線要不要檢查這則聊天或指令：AI 送的一律檢查；AI 在控制時(RUNNING)，除了使用者自己從畫面送出的，
	 * 其他來源(沒包 withAiFlag 的程式碼、Baritone、其他模組)也檢查(使用者決定，2026-11-23)。
	 */
	public static boolean mustCheckOutgoing() {
		if (aiSending()) return true;
		Agent a = Agent.get();
		return a != null && a.session.isRunning() && !userSending();
	}

	// ---- 收 ----

	/**
	 * 公開聊天的類型(S3.5 第 1 點)：一般聊天、/say、/me。其他(私訊、隊伍私訊、伺服器自訂的類型)一律不轉交(M11)。
	 * 沒有類型的也不轉交。
	 */
	static boolean isPublicChatType(ChatType.Bound params) {
		if (params == null) return false;
		var t = params.chatType();
		return t.is(ChatType.CHAT) || t.is(ChatType.SAY_COMMAND) || t.is(ChatType.EMOTE_COMMAND);
	}

	/** 玩家聊天(S3.5 第 1、2、4 點)。 */
	public void onPlayerChat(Component message, PlayerChatMessage signed, GameProfile sender, ChatType.Bound params) {
		if (!agent.session.inWorldSession()) return;
		if (!isPublicChatType(params)) return; // 私訊或不認得的類型：丟棄
		String name = sender != null ? sender.name() : params.name().getString();
		String text;
		if (signed != null) {
			Component unsigned = signed.unsignedContent();
			text = unsigned != null ? unsigned.getString() : signed.signedContent();
		} else {
			text = message.getString();
		}
		boolean self = isSelf(sender != null ? sender.id() : signed != null ? signed.sender() : null);
		add(new Line(LocalTime.now().format(HM), self ? "me" : name, text, self));
		if (!self) {
			agent.events.add(new Event("chat", Event.Level.BATCH, name + "=" + blockagent.mcp.Kv.quote(text), "chat", System.currentTimeMillis()));
		}
	}

	/** 系統訊息(S3.5 第 3 點)。overlay 是動作列，不轉交。 */
	public void onGameMessage(Component message, boolean overlay) {
		if (!agent.session.inWorldSession() || agent.blockCommands.consumeFeedback(message)) return;
		String text = message.getString();
		if (overlay) {
			// 動作列訊息(例如「現在不能睡覺」)不轉交給 AI 的聊天，但讓任務知道失敗原因
			agent.tasks.onSystemMessage(text);
			return;
		}
		if (agent.privateFilter.isPrivate(text)) return;
		add(new Line(LocalTime.now().format(HM), "system", text, false));
		agent.events.add(new Event("chat", Event.Level.BATCH, "system=" + blockagent.mcp.Kv.quote(text), "chat", System.currentTimeMillis()));
		agent.tasks.onSystemMessage(text);
	}

	private boolean isSelf(UUID id) {
		var p = Minecraft.getInstance().player;
		return id != null && p != null && id.equals(p.getUUID());
	}

	private synchronized void add(Line l) {
		recent.addLast(l);
		while (recent.size() > 100) recent.removeFirst();
	}

	public synchronized List<Line> recent(int n) {
		List<Line> all = new ArrayList<>(recent);
		return all.subList(Math.max(0, all.size() - n), all.size());
	}

	public synchronized void clear() {
		recent.clear();
	}

	// ---- 發 ----

	/** 在 client thread 呼叫。回傳 null 表示送出成功，否則是被拒絕的結果。 */
	public ChatPolicy.Rejected say(String raw) {
		var r = agent.chatPolicy.prepare(raw);
		if (r instanceof ChatPolicy.Rejected rej) return rej;
		String msg = ((ChatPolicy.Ok) r).message();
		var conn = Minecraft.getInstance().getConnection();
		if (conn == null) return new ChatPolicy.Rejected("SESSION_ENDED", "not in a world");
		withAiFlag(() -> conn.sendChat(msg));
		// 伺服器轉回來的那一則會在 onPlayerChat 記成 me，這裡不重複記(M11)
		agent.log.log(ActionLog.Source.ai, "say", msg);
		return null;
	}
}
