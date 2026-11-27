package blockagent.game;

import blockagent.BlockAgentMod;
import blockagent.Config;
import blockagent.core.BlockClassifier;
import blockagent.core.ChatPolicy;
import blockagent.core.Clock;
import blockagent.core.Event;
import blockagent.core.EventQueue;
import blockagent.core.IdleTracker;
import blockagent.core.OwnershipLedger;
import blockagent.core.Plan;
import blockagent.core.PrivateMessageFilter;
import blockagent.core.Recipes;
import blockagent.core.VanillaData;
import blockagent.core.Session;
import blockagent.game.reflex.Reflexes;
import blockagent.game.tasks.TaskRunner;
import blockagent.mcp.AiPresence;
import blockagent.mcp.McpServer;
import blockagent.mcp.RuntimeFile;
import blockagent.mcp.ToolRegistry;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模組的中樞：持有所有元件，處理進入/離開世界與每個 tick 的順序(docs/SPEC.md S2.2)。
 */
public final class Agent {
	private static Agent instance;

	/** MCP prompt 名稱，內容是打包在 jar 裡的 prompts/how-to-play.md(build.gradle 的 processResources)。 */
	public static final String HOW_TO_PLAY = "how_to_play";
	static final String HOW_TO_PLAY_RESOURCE = "/blockagent/prompts/how-to-play.md";

	static String howToPlay() {
		try (var in = Agent.class.getResourceAsStream(HOW_TO_PLAY_RESOURCE)) {
			return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			return null;
		}
	}

	public final Config config;
	public final Path gameDir;
	public final Path dataDir;
	public final Session session;
	public final EventQueue events;
	public final IdleTracker idle;
	public final ChatPolicy chatPolicy;
	public final PrivateMessageFilter privateFilter;
	public final BlockClassifier classifier;
	public final OwnershipLedger ledger = new OwnershipLedger();
	public final Memory memory = new Memory();
	public final Plan plan = new Plan();
	public final ActionLog log = new ActionLog();
	public final blockagent.game.combat.ArcheryLog archery = new blockagent.game.combat.ArcheryLog(log);
	public final RuleGuard guard;
	public final OwnershipTracker tracker;
	public final BuildCommands blockCommands = new BuildCommands(this);
	public final ChatGateway chat;
	public final Control control;
	public final Observation observation;
	public final TaskRunner tasks;
	public final Reflexes reflexes;
	/** 尋路要繞開的怪物範圍，包含脫身時記下的位置(S9.1b)。 */
	public final blockagent.game.combat.DangerZones danger = new blockagent.game.combat.DangerZones();
	public final Respawn respawn;
	public final Hud hud;
	public final Recipes recipes;
	public final ToolRegistry tools = new ToolRegistry();
	private McpServer mcp;
	/** 最近的任務失敗和被規則擋下的動作(給規劃快照，S11.7)。MCP、client、Baritone 執行緒都會寫，用 synchronized。 */
	private final java.util.ArrayDeque<String> recentProblems = new java.util.ArrayDeque<>();
	static final int RECENT_PROBLEMS = 6;
	private volatile Path planFile;
	private long planRevision; // guarded by plan
	private long worldEpoch;   // client thread only

	private volatile String worldKey;
	/** AI 用 set_status 寫在 HUD 第二行的一句話(S8.3)。 */
	private volatile String statusText = "";
	private volatile long tick;
	/** 斷線原因(由 Mixin 在伺服器斷線時設定)；null 表示使用者自己離開。 */
	private volatile String disconnectReason;

	public static Agent get() {
		return instance;
	}

	public Agent(Config config, Path gameDir) throws IOException {
		instance = this;
		this.config = config;
		this.gameDir = gameDir;
		this.dataDir = gameDir.resolve("blockagent");
		Clock clock = Clock.SYSTEM;
		this.session = new Session(clock, config.sessionMinutes * 60_000L);
		this.events = new EventQueue(clock);
		this.idle = new IdleTracker(clock, config.idleReminderSeconds * 1000L);
		this.chatPolicy = new ChatPolicy(clock, config.chatEnabled, config.chatIntervalSeconds * 1000L);
		this.privateFilter = new PrivateMessageFilter(config.privateMessagePatterns);
		if (!privateFilter.invalidPatterns().isEmpty()) {
			blockagent.BlockAgentMod.LOG.warn("config 的 privateMessagePatterns 有格式錯誤的 regex，已略過：{}", privateFilter.invalidPatterns());
		}
		this.classifier = BlockClassifier.fromJson(Agent.class.getResourceAsStream("/blockagent/natural_blocks.json"));
		this.guard = new RuleGuard(this);
		this.tracker = new OwnershipTracker(this);
		this.chat = new ChatGateway(this);
		this.control = new Control(this);
		this.observation = new Observation(this);
		this.tasks = new TaskRunner(this);
		this.reflexes = new Reflexes(this);
		this.respawn = new Respawn(this);
		this.hud = new Hud(this);
		this.recipes = VanillaData.fromClasspath(Agent.class.getClassLoader()).recipes();
		Tools.registerAll(this);
		// R14：參數解析失敗(未知方塊 id、數值超出範圍…)也寫進行動紀錄
		tools.setOnError((name, msg) -> log.log(ActionLog.Source.ai, name, msg));
		tools.setResponseSuffix(this::unreadSummary);
		session.addListener(this::onSessionChange);
	}

	public void startMcp() throws IOException {
		String token = McpServer.newToken();
		mcp = new McpServer(tools, token, BlockAgentMod.version(), Instructions.TEXT);
		// A13：一般 MCP client 沒有專用 CLI 注入提示詞，用 prompts/get 也拿得到玩法說明(打包在 jar 裡)
		mcp.addPrompt(HOW_TO_PLAY, "How to play Minecraft with these tools (guide in Traditional Chinese; reply to people in Traditional Chinese)",
			Agent::howToPlay);
		mcp.setOnInitialize(ci -> {
			log.log(ActionLog.Source.system, "mcp_initialize", ci.clientName() + " " + ci.clientVersion() + " protocol=" + ci.protocolVersion());
			BlockAgentMod.LOG.info("AI 端已連線：{} {}(MCP {})", ci.clientName(), ci.clientVersion(), ci.protocolVersion());
		});
		mcp.setOnClose(() -> {
			log.log(ActionLog.Source.system, "mcp_close", "AI 端結束連線");
			BlockAgentMod.LOG.info("AI 端結束連線");
		});
		String planToken = McpServer.newToken();
		mcp.setPlanBridge(planToken, new McpServer.PlanBridge() {
			@Override
			public JsonObject context() {
				return planSnapshot();
			}

			@Override
			public String accept(JsonObject result) {
				try {
					return ClientThread.call(() -> acceptPlan(result, planLocation()));
				} catch (Exception e) {
					return "game did not respond";
				}
			}
		});
		mcp.setStatusSupplier(() -> {
			JsonObject o = new JsonObject();
			o.addProperty("state", session.state().name());
			return o;
		});
		int port = mcp.start(config.mcpPort);
		RuntimeFile.write(RuntimeFile.defaultHome(), port, token, planToken);
		BlockAgentMod.LOG.info("MCP 伺服器：http://127.0.0.1:{}/mcp", port);
	}

	public void shutdown() {
		if (mcp != null) mcp.close();
		RuntimeFile.delete(RuntimeFile.defaultHome());
		saveLedger();
		log.close();
		Bari.shutdownExecutor();
	}

	/** AI 端在不在(S8.3)，給 HUD 用。 */
	public AiPresence aiPresence() {
		return mcp == null ? AiPresence.NONE : mcp.presence();
	}

	public int mcpPort() {
		return mcp == null ? -1 : mcp.port();
	}

	public long tick() {
		return tick;
	}

	public String worldKey() {
		return worldKey;
	}

	String statusText() {
		return statusText;
	}

	/** AI 寫的那句話；空字串表示不顯示。 */
	void setStatusText(String text) {
		statusText = text == null ? "" : text;
	}

	public void setDisconnectReason(String r) {
		disconnectReason = r;
	}

	// ---- 世界 ----

	public void onJoin(Minecraft mc) {
		if (pendingDisconnect) finishDisconnect();
		worldEpoch++;
		worldKey = WorldKey.current(mc, dataDir.resolve("worlds"));
		disconnectReason = null;
		// 上個世界沒送出去的事件(例如 session_ended、died)不帶到新世界(M5)
		events.drainAll();
		events.takeDroppedCount();
		tracker.reset();
		respawn.reset();
		guard.clearCache();
		Path dir = dataDir.resolve("worlds").resolve(worldKey);
		memory.load(dir.resolve("memory.json"));
		loadLedger(dir.resolve("ownership.json"));
		loadPlan(dir.resolve("plan.json"));
		synchronized (recentProblems) {
			recentProblems.clear();
		}
		log.open(dataDir.resolve("logs").resolve(worldKey));
		log.log(ActionLog.Source.system, "join", worldKey);
		session.setTimeLimitMs(config.sessionMinutes * 60_000L);
		reflexes.resetEnabled();
		observation.resetWorld();
		danger.clear();
		chat.clear();
		setStatusText("");
		session.enterWorld();
		Bari.applySettings();
		if (!Bari.mixinTargetsPresent() && mc.player != null) {
			// 混淆版 Baritone 會讓模組的 Baritone Mixin 靜默失效(H10)：在聊天欄提醒使用者(只顯示在自己的畫面)
			mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"[BlockAgent] 裝的 Baritone 可能是混淆版，尋路時不會避開別人的方塊。請改裝 baritone-unoptimized-fabric。")
				.withStyle(net.minecraft.ChatFormatting.RED));
		}
				events.add("world_joined", Event.Level.PROMPT, blockagent.mcp.Kv.of().put("world", worldKey).put("memory", memory.size())
			+ " | AI not started yet | hint=wait_events until session_started" + (memory.size() > 0 ? ", then memory_read" : ""));
	}

	public void onDisconnect(Minecraft mc) {
		// 先停下所有動作；斷線原因由 Mixin 在 onDisconnect 記下，但它和 Fabric 斷線事件的先後不一定，
		// 所以等下一個 tick 再判斷是「斷線」還是「自己離開」(S8.7)
		control.stopEverything("session ended");
		log.log(ActionLog.Source.system, "disconnect_event", disconnectReason);
		pendingDisconnect = true;
		if (disconnectReason != null) finishDisconnect();
	}

	private volatile boolean pendingDisconnect;

	private void finishDisconnect() {
		if (!pendingDisconnect) return;
		pendingDisconnect = false;
		Session.EndReason reason = disconnectReason != null ? Session.EndReason.DISCONNECTED : Session.EndReason.LEFT_WORLD;
		session.end(reason);
		saveLedger();
		ledgerFile = null;
		ledger.clearTransient();
		tracker.reset();
		memory.unload();
		synchronized (plan) {
			planFile = null;
			plan.clear();
		}
		archery.disconnect();
		log.log(ActionLog.Source.system, "disconnect", reason.name() + (disconnectReason != null ? " " + disconnectReason : ""));
		log.close();
		worldKey = null;
	}

	private Path ledgerFile;

	private void loadLedger(Path file) {
		ledgerFile = file;
		try {
			if (Files.exists(file)) {
				ledger.loadJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
			} else {
				ledger.loadJson(null);
			}
		} catch (IOException | RuntimeException e) {
			BlockAgentMod.LOG.warn("讀取所有權帳本失敗：{}", e.toString());
			ledger.loadJson(null);
		}
	}

	void saveLedger() {
		if (ledgerFile == null) return;
		try {
			blockagent.core.AtomicFiles.writeString(ledgerFile, new GsonBuilder().create().toJson(ledger.toJson()));
		} catch (IOException e) {
			BlockAgentMod.LOG.warn("寫入所有權帳本失敗：{}", e.toString());
		}
	}

	// ---- 計畫(S5.6)與規劃服務(S11.7) ----

	void loadPlan(Path file) {
		synchronized (plan) {
			planFile = file;
			try {
				plan.loadJson(Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : null);
			} catch (IOException | RuntimeException e) {
				BlockAgentMod.LOG.warn("讀取計畫失敗：{}", e.toString());
				plan.clear();
			}
		}
	}

	/** 計畫改了：存檔。呼叫端應持有 {@code synchronized (plan)} 或透過這裡序列化。 */
	void planChanged() {
		synchronized (plan) {
			planRevision++;
			Path file = planFile;
			if (file != null) {
				try {
					blockagent.core.AtomicFiles.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(plan.toJson()));
				} catch (IOException e) {
					BlockAgentMod.LOG.warn("寫入計畫失敗：{}", e.toString());
				}
			}
		}
	}

	private static String clip(String s, int max) {
		String t = ChatPolicy.sanitize(s);
		return t.codePointCount(0, t.length()) <= max ? t : t.substring(0, t.offsetByCodePoints(0, max));
	}

	/** 附在事件後面的計畫提醒：{@code " | plan_step=2/5 \"…\""}；沒有計畫時是空字串。 */
	public String planSuffix() {
		String r = plan.reminder();
		return r.isEmpty() ? "" : " | " + r;
	}

	/** 記下一筆問題(任務失敗、被規則擋下)，只留最近幾筆。 */
	public void noteProblem(String line) {
		if (line == null || line.isBlank()) return;
		String l = line.strip().replace('\n', ' ');
		if (l.length() > 240) l = l.substring(0, 240);
		synchronized (recentProblems) {
			recentProblems.addLast(java.time.LocalTime.now().withNano(0) + " " + l);
			while (recentProblems.size() > RECENT_PROBLEMS) recentProblems.removeFirst();
		}
	}

	java.util.List<String> recentProblems() {
		synchronized (recentProblems) {
			return java.util.List.copyOf(recentProblems);
		}
	}

	record PlanLocation(long world, String dimension, BlockPos position) {
		JsonObject basis(long revision) {
			JsonObject out = new JsonObject();
			out.addProperty("world", world);
			out.addProperty("revision", revision);
			out.addProperty("dimension", dimension);
			out.addProperty("position", position.asLong());
			return out;
		}
	}

	private PlanLocation planLocation() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null || mc.level == null || worldKey == null || pendingDisconnect ? null
			: new PlanLocation(worldEpoch, mc.level.dimension().identifier().toString(), mc.player.blockPosition());
	}

	/** 規劃文字及其依據在同一個 client tick 取樣，避免把已變動的計畫版本附到舊快照。 */
	private JsonObject planSnapshot() {
		try {
			return ClientThread.call(() -> {
				PlanLocation here = planLocation();
				JsonObject out = new JsonObject();
				synchronized (plan) {
					out.addProperty("snapshot", planContext(here == null ? "not in a world" : observation.self(Minecraft.getInstance()),
						session.state().name(), plan.render(), memory.all(), recentProblems(),
						tools.list().stream().map(ToolRegistry.Tool::name).toList()));
					if (here != null) out.add("basis", here.basis(planRevision));
				}
				return out;
			});
		} catch (Exception e) {
			throw new IllegalStateException("could not read planner snapshot", e);
		}
	}

	static final int SNAPSHOT_MEMORY_CHARS = 4000;

	static String planContext(String self, String state, String planText, java.util.Map<String, String> mem,
							  java.util.List<String> problems, java.util.Collection<String> toolNames) {
		StringBuilder sb = new StringBuilder();
		// 由靜態到動態排列，讓規劃模型的 prompt 前綴盡量不變、提高快取命中(R9)
		sb.append("[player_tools]\n").append(String.join(", ", toolNames));
		sb.append("\n[memory]");
		if (mem.isEmpty()) sb.append("\nmemory=none");
		int used = 0;
		for (var e : mem.entrySet()) {
			String line = "\n- " + e.getKey() + "=" + blockagent.mcp.Kv.quote(e.getValue());
			if (used + line.length() > SNAPSHOT_MEMORY_CHARS) {
				sb.append("\n- (more entries omitted)");
				break;
			}
			sb.append(line);
			used += line.length();
		}
		sb.append("\n[current_plan]\n").append(planText);
		sb.append("\n[recent_problems]");
		if (problems.isEmpty()) sb.append("\nnone");
		for (String p : problems) sb.append("\n- ").append(p);
		sb.append("\n[self]\n").append(self).append("\nsession=").append(state);
		return sb.toString();
	}

	/** 規劃服務送回結果(POST /plan/result)：成功時換成新計畫，失敗時只通知 AI。回傳 null 表示接受。 */
	String acceptPlan(JsonObject r, PlanLocation here) {
		if (here == null || !memory.loaded()) return "not in a world";
		String goal = str(r, "goal");
		if (goal == null || goal.isBlank()) return "goal is required";
		JsonObject basis = r.has("basis") && r.get("basis").isJsonObject() ? r.getAsJsonObject("basis") : null;
		try {
			if (basis != null && basis.get("world").getAsLong() != here.world()) return "snapshot belongs to another world";
		} catch (RuntimeException e) {
			return "invalid snapshot basis";
		}
		if (r.has("error") && r.get("error").isJsonObject()) {
			JsonObject e = r.getAsJsonObject("error");
			String code = str(e, "code"), message = str(e, "message");
			String c = code == null || !code.matches("[A-Z_]{1,32}") ? "FAILED" : code;
			String m = message == null ? "" : clip(message, 200);
			events.add("plan_failed", Event.Level.PROMPT, blockagent.mcp.Kv.of().put("goal", clip(goal, 80)).put("code", c)
				+ (m.isEmpty() ? "" : " | " + m) + " | hint=go on with what you know; plan(action=set) to write your own plan");
			log.log(ActionLog.Source.system, "plan_failed", m.isEmpty() ? c : c + " " + m);
			return null;
		}
		if (r.has("recommendation") && !r.get("recommendation").isJsonNull() && str(r, "recommendation") == null) {
			return "recommendation must be a string";
		}
		java.util.List<String> steps = new java.util.ArrayList<>();
		if (!r.has("steps") || !r.get("steps").isJsonArray()) return "steps must be an array";
		for (var e : r.getAsJsonArray("steps")) {
			if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return "steps must contain only strings";
			steps.add(e.getAsString());
		}
		synchronized (plan) {
			String stale;
			try {
				stale = stalePlanReason(basis, here, planRevision);
			} catch (RuntimeException e) {
				return "invalid snapshot basis";
			}
			Plan proposed = stale == null ? plan : new Plan();
			String err = proposed.set(goal, str(r, "recommendation"), steps, "planner");
			if (err != null) return err;
			if (stale != null) {
				events.add("plan_stale", Event.Level.PROMPT, "reason=" + stale + " " + planDetails(proposed)
					+ " | hint=review these suggestions against the current state; use plan(action=set) to adopt updated steps");
				log.log(ActionLog.Source.system, "plan_stale", stale + " " + clip(goal, 80));
				return null;
			}
			planChanged();
			events.add("plan_ready", Event.Level.PROMPT, planEventText(plan));
			log.log(ActionLog.Source.system, "plan_ready", plan.render());
		}
		return null;
	}

	private static String stalePlanReason(JsonObject basis, PlanLocation here, long revision) {
		if (basis == null) return "snapshot_unavailable";
		if (!here.dimension().equals(basis.get("dimension").getAsString())) return "dimension_changed";
		if (basis.get("revision").getAsLong() != revision) return "plan_advanced";
		BlockPos old = BlockPos.of(basis.get("position").getAsLong());
		return here.position().distSqr(old) > 64 ? "moved_from_snapshot" : null;
	}

	/** plan_ready 事件的一行：目標、建議和所有步驟。 */
	static String planEventText(Plan p) {
		return planDetails(p) + " | hint=say what you will do, start step 1, and call plan(action=done) each time a step is complete";
	}

	private static String planDetails(Plan p) {
		String goal, rendered;
		synchronized (p) {
			goal = p.goal();
			rendered = p.render();
		}
		String[] lines = rendered.split("\n");
		StringBuilder steps = new StringBuilder();
		String rec = "";
		for (String l : lines) {
			if (l.startsWith("recommendation=")) rec = " " + l;
			else if (!l.startsWith("goal=")) steps.append(steps.isEmpty() ? "" : " ").append(l.replace(" [now] ", " ").replace(" [todo] ", " "));
		}
		return blockagent.mcp.Kv.of().put("goal", goal) + rec + " | " + steps;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString()
			? o.get(k).getAsString() : null;
	}

	// ---- 工作階段 ----

	private void onSessionChange(Session.State from, Session.State to, String cause) {
		log.log(ActionLog.Source.system, "session", from + " -> " + to + " (" + cause + ")");
		switch (to) {
			case RUNNING -> {
				control.releaseMouse();
				Bari.applySettings();
				if (from == Session.State.READY) {
					events.add("session_started", Event.Level.URGENT, "time_limit=" + (session.hasTimeLimit() ? config.sessionMinutes + "min" : "none")
						+ " | hint=get_self, get_surroundings, memory_read" + planSuffix());
				} else {
					events.add("user_resumed", Event.Level.URGENT, "AI resumed | hint=get_self and get_surroundings again" + planSuffix());
				}
				idle.touch();
			}
			case PAUSED -> control.restoreMouse();
			case ENDED -> {
				control.stopEverything("session ended");
				if (session.endReason() == Session.EndReason.TIME_LIMIT) control.restoreMouse();
				String why = switch (session.endReason()) {
					case TIME_LIMIT -> "reason=time_limit";
					case LEFT_WORLD -> "reason=left_world";
					case DISCONNECTED -> "reason=disconnected" + (disconnectReason != null ? " detail=" + blockagent.mcp.Kv.quote(disconnectReason) : "");
					case null -> "reason=ended";
				};
				events.add("session_ended", Event.Level.URGENT, why + " | no further actions will run | hint=summarize in 2-3 sentences and end your turn");
			}
			default -> {
			}
		}
	}

	// ---- tick(S2.2 的順序：Control → Reflex → TaskRunner → 事件) ----

	public void onEndTick(Minecraft mc) {
		tick++;
		if (pendingDisconnect) finishDisconnect();
		control.tick(mc);
		if (mc.player == null || mc.level == null) return;
		tracker.tick(mc);
		archery.tick(mc.level, tick);
		// 暫停、待命時使用者自己放的方塊也要定期存檔(M5)
		if (tick % 200 == 0 && ledger.takeDirty()) saveLedger();
		if (session.tick()) {
			events.add("time_warning", Event.Level.BATCH, "left_min=5 | hint=save progress to memory and wrap up");
		}
		// ENDED 時的停止只在狀態改變那一刻做一次(onSessionChange)。之後角色交還給使用者，
		// 不能每個 tick 都 stopEverything：裡面的 KeyMapping.releaseAll() 會一直放開使用者按住的移動鍵。
		if (!session.isRunning()) return;
		// 死掉時只負責重生(S8.8)，反射和任務都不跑
		if (respawn.tick(mc)) return;
		Bari.enforceSettings(tick);
		reflexes.tick(mc);
		tasks.tick(mc);
		landIfFlying(mc);
		observation.tick(mc);
		if (idle.poll(tasks.hasActiveTask())) {
			String hint;
			synchronized (plan) {
				hint = plan.current() != null ? "continue the plan" + planSuffix()
					: plan.finished() ? "the plan is finished; pick a next goal (request_plan without goal if available)"
					: "pick a next goal (request_plan without goal if available)";
			}
			events.add("idle_reminder", Event.Level.PROMPT, "no task and no recent actions | hint=" + hint);
		}
	}

	/**
	 * 創造模式飛行中要移動時先落地(S4.6d)：Baritone 只會走路，飛行時照走路的方式按鍵會快得不正常。
	 * 跟玩家連按兩下空白鍵關掉飛行一樣(LocalPlayer.aiStep 的做法)。
	 */
	private void landIfFlying(Minecraft mc) {
		var abilities = mc.player.getAbilities();
		if (!abilities.flying) return;
		var t = tasks.current();
		if (!Bari.busy() && (t == null || !t.moves())) return;
		abilities.flying = false;
		mc.player.onUpdateAbilities();
		log.log(ActionLog.Source.system, "stop_flying", "要移動了，先關掉飛行");
	}

	/** 每個工具回應最後附上的未讀事件摘要(S5.4)。 */
	String unreadSummary(String toolName) {
		// HUD 更新的回應可能被呼叫端略過，保留事件給後續的 wait_events。
		// wait_events 自己決定要送哪些事件：task_only=true 時刻意留下的 task_progress 不能在這裡倒出去(M4)。
		if ("set_status".equals(toolName) || "wait_events".equals(toolName)) return "";
		return EventQueue.render(events.drainAll(), events.takeDroppedCount());
	}
}
