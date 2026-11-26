package blockagent.testkit;

import blockagent.game.Agent;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 只在開發環境存在的測試功能(docs/SPEC.md S10.1、M8.1)。全部在伺服器端執行，不是 MCP 工具。
 */
@SuppressWarnings("UnstableApiUsage")
public final class TestKit {
	/** 送給伺服器的操作次數(mixin.ActionCounter)。 */
	public static final java.util.concurrent.atomic.AtomicInteger ACTIONS = new java.util.concurrent.atomic.AtomicInteger();

	public final ClientGameTestContext ctx;
	public final TestSingleplayerContext sp;
	public final TestServerContext server;
	public final McpTestClient ai;

	public TestKit(ClientGameTestContext ctx, TestSingleplayerContext sp) throws Exception {
		this.ctx = ctx;
		this.sp = sp;
		this.server = sp.getServer();
		sp.getConnection().waitForChunksRender();
		this.ai = McpTestClient.fromRuntimeFile();
		cmd("difficulty easy");
		cmd("gamemode survival @a");
		cmd("gamerule advance_time false");
		cmd("gamerule advance_weather false");
		cmd("time set 1000");
		cmd("weather clear");
		cmd("gamerule spawn_monsters false");
		cmd("gamerule spawn_mobs false");
	}

	/** -Dblockagent.test.only=A,B 時，只跑列出的測試。 */
	public static boolean skip(String name) {
		String only = System.getProperty("blockagent.test.only");
		if (only == null || only.isBlank()) return false;
		boolean skip = !java.util.Arrays.asList(only.split(",")).contains(name);
		if (skip) System.out.println("[skip] " + name);
		return skip;
	}

	/**
	 * 耗時測試預設跳過(避免每次建置都跑)：只在顯式指定 -PtestOnly=name、-PtestCase=... 或 -PcombatTest=true 時才執行。
	 */
	public static boolean skipOptIn(String name) {
		String only = System.getProperty("blockagent.test.only");
		if (only != null && !only.isBlank()) return skip(name);
		String cases = System.getProperty("blockagent.test.case");
		if (cases != null && !cases.isBlank()) return false;
		if ("true".equalsIgnoreCase(System.getProperty("blockagent.test.combat"))) return false;
		System.out.println("[skip] " + name + " (pass -PtestOnly=" + name + ", -PtestCase=... or -PcombatTest=true to run)");
		return true;
	}

	/** -Dblockagent.test.case=a,b 時，類別裡只跑列出的情境；沒給就全部跑。 */
	public static boolean runCase(String name) {
		String only = System.getProperty("blockagent.test.case");
		if (only == null || only.isBlank()) return true;
		boolean run = java.util.Arrays.asList(only.split(",")).contains(name);
		if (!run) System.out.println("[skip] case " + name);
		return run;
	}

	public static int sumFightMetric(String log, String prefix) {
		int sum = 0;
		for (String line : fightSummaries(log)) {
			int idx = line.indexOf(prefix);
			if (idx < 0) continue;
			int start = idx + prefix.length();
			int end = start;
			while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
			if (end > start) sum += Integer.parseInt(line.substring(start, end));
		}
		return sum;
	}

	public static List<String> fightSummaries(String log) {
		var out = new java.util.ArrayList<String>();
		for (String line : log.split("\n")) {
			if (!line.contains("fight_summary")) continue;
			int i = line.indexOf("\"result\":\"");
			if (i >= 0) out.add(line.substring(i + 10, line.lastIndexOf('"')));
		}
		return out;
	}

	public static void check(boolean cond, String msg) {
		if (!cond) throw new AssertionError(msg);
	}

	public Agent agent() {
		return Agent.get();
	}

	public void cmd(String command) {
		server.runCommand(command);
	}

	public void onServer(Consumer<net.minecraft.server.MinecraftServer> fn) {
		server.runOnServer(fn::accept);
	}

	public ServerPlayer me(net.minecraft.server.MinecraftServer s) {
		return s.getPlayerList().getPlayers().stream().filter(p -> !(p instanceof FakePlayer)).findFirst().orElseThrow();
	}

	public BlockPos myPos() {
		return ctx.computeOnClient(mc -> mc.player.blockPosition());
	}

	public void setBlock(BlockPos p, BlockState st) {
		onServer(s -> s.overworld().setBlock(p, st, 3, 512));
	}

	public void give(String item, int count) {
		cmd("give @p " + item + " " + count);
		ctx.waitTicks(2);
	}

	public void clearInventory() {
		cmd("clear @p");
		ctx.waitTicks(2);
	}

	/** 讓 AI 開始玩(按暫停鍵 READY → RUNNING)。 */
	public void start() {
		if (!agent().session.isRunning()) ctx.getInput().pressKey(agent().control.toggleKey);
		ctx.waitTicks(2);
		check(agent().session.isRunning(), "應該是 RUNNING，實際是 " + agent().session.state());
		agent().events.drainAll(); // 清掉開始時的事件，不必空等 wait_events
	}

	/** 一直讀事件，直到出現 needle 或逾時；回傳讀到的所有事件文字。 */
	public String eventsUntil(String needle, int maxSec) {
		StringBuilder all = new StringBuilder();
		long deadline = System.currentTimeMillis() + maxSec * 1000L;
		while (System.currentTimeMillis() < deadline) {
			var r = ai.call(ctx, "wait_events", McpTestClient.args("max_wait_sec", 2));
			all.append(r.text()).append('\n');
			if (all.indexOf(needle) >= 0) break;
		}
		return all.toString();
	}

	/** 推進 tick 直到條件成立；回傳是否成立。 */
	public boolean waitUntil(int maxTicks, java.util.function.Predicate<net.minecraft.client.Minecraft> cond) {
		for (int i = 0; i < maxTicks; i++) {
			if (ctx.computeOnClient(cond::test)) return true;
			ctx.waitTick();
		}
		return ctx.computeOnClient(cond::test);
	}

	public void expectError(McpTestClient.Result r, String code, String what) {
		check(!r.ok(), what + "：應該失敗，實際成功：" + r.text());
		check(code.equals(r.code()), what + "：錯誤碼應該是 " + code + "，實際是 " + r.code() + "(" + r.text() + ")");
		check(r.reason() != null && !r.reason().isBlank(), what + "：失敗時一定要有原因");
		System.out.println("[ok] " + what + " → " + r.code() + "：" + r.reason());
	}

	public static String pos(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	public void pressToggle() {
		ctx.getInput().pressKey(agent().control.toggleKey);
		ctx.waitTicks(1);
	}

	/** 一個平坦的測試場地：清空周圍、鋪石頭地面。 */
	public void flatArena(BlockPos center, int r) {
		cmd("fill " + (center.getX() - r) + " " + (center.getY()) + " " + (center.getZ() - r) + " "
			+ (center.getX() + r) + " " + (center.getY() + 6) + " " + (center.getZ() + r) + " minecraft:air");
		cmd("fill " + (center.getX() - r) + " " + (center.getY() - 1) + " " + (center.getZ() - r) + " "
			+ (center.getX() + r) + " " + (center.getY() - 1) + " " + (center.getZ() + r) + " minecraft:stone");
		cmd("tp @p " + center.getX() + " " + center.getY() + " " + center.getZ());
		ctx.waitTicks(5);
	}

	// ---- 模擬朋友(FakeFriend) ----

	public final class Friend {
		public final String name;
		FakePlayer player;

		Friend(String name) {
			this.name = name;
		}

		public int id() {
			return server.computeOnServer(s -> player.getId());
		}

		/** 朋友公開說話。 */
		public void say(String text) {
			onServer(s -> s.getPlayerList().broadcastChatMessage(PlayerChatMessage.unsigned(player.getUUID(), text), player,
				ChatType.bind(ChatType.CHAT, player)));
			ctx.waitTicks(2);
		}

		/** 朋友用 /msg 傳私訊給 AI。 */
		public void whisper(String text) {
			onServer(s -> me(s).sendChatMessage(OutgoingChatMessage.create(PlayerChatMessage.unsigned(player.getUUID(), text)), false,
				ChatType.bind(ChatType.MSG_COMMAND_INCOMING, player)));
			ctx.waitTicks(2);
		}

		/** 朋友傳隊伍私訊。 */
		public void teamWhisper(String text) {
			onServer(s -> me(s).sendChatMessage(OutgoingChatMessage.create(PlayerChatMessage.unsigned(player.getUUID(), text)), false,
				ChatType.bind(ChatType.TEAM_MSG_COMMAND_INCOMING, player)));
			ctx.waitTicks(2);
		}

		/** 伺服器外掛常見的私訊格式(系統訊息)。 */
		public void pluginWhisper(String text) {
			onServer(s -> me(s).sendSystemMessage(Component.literal("[" + name + " -> me] " + text)));
			ctx.waitTicks(2);
		}

		/** 朋友放方塊(伺服器端放，不是 AI 放的)。 */
		public void place(BlockPos p, BlockState st) {
			setBlock(p, st);
		}

		public void moveTo(double x, double y, double z) {
			onServer(s -> player.snapTo(x, y, z));
			ctx.waitTicks(2);
		}

		public float health() {
			return server.computeOnServer(s -> player.getHealth());
		}
	}

	/** 在伺服器端加入一個假玩家，客戶端會看到它是一般玩家。 */
	public Friend spawnFriend(String name, BlockPos at) {
		Friend f = new Friend(name);
		onServer(s -> {
			ServerLevel lvl = s.overworld();
			GameProfile gp = new GameProfile(UUID.nameUUIDFromBytes(("blockagent-test:" + name).getBytes(StandardCharsets.UTF_8)), name);
			FakePlayer fp = FakePlayer.get(lvl, gp);
			fp.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
			s.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fp)));
			lvl.addNewPlayer(fp);
			f.player = fp;
		});
		ctx.waitTicks(10);
		boolean seen = ctx.computeOnClient(mc -> mc.level.players().stream().anyMatch(p -> p.getName().getString().equals(name)));
		check(seen, "客戶端看不到假玩家 " + name + "(S13 風險 #3)");
		return f;
	}
}
