package blockagent.testkit;

import blockagent.game.Agent;
import blockagent.game.BuildCommands;
import blockagent.game.ChatGateway;
import blockagent.game.Creative;
import blockagent.game.Inv;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * 共用一台專用伺服器驗證所有伺服器端相容與權限底線：
 * (1) T12 創造模式：creative_take、飛行落地走路與挖掘、伺服器移動檢查不拉回(0 次 correction)。
 * (2) T14 權限切換：無 op 走 Baritone(拒絕懸空)、有 op 走 /fill 並保護既有方塊、未授權原始 /fill 被 Mixin 擋下。
 * (3) T7(c) 伺服器踢人斷線：立刻結束工作階段並通知 AI。
 */
@SuppressWarnings("UnstableApiUsage")
public class ServerAndCreativeGameTest implements FabricClientGameTest {
	public static final AtomicInteger CORRECTIONS = new AtomicInteger();

	/** 走路(含疾跑跳)每 tick 的水平位移上限。 */
	static final double WALK_MAX_PER_TICK = 0.7;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("ServerAndCreativeGameTest")) return;
		Properties props = new Properties();
		props.setProperty("gamemode", "creative");
		props.setProperty("spawn-protection", "0");
		props.setProperty("spawn-monsters", "false");

		boolean kicked = false;
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props)) {
			TestDedicatedServerConnection conn = server.connect();
			try {
				conn.waitForChunksRender();
				McpTestClient ai = McpTestClient.fromRuntimeFile();
				BlockPos c = ctx.computeOnClient(mc -> mc.player.blockPosition());
				server.runCommand("gamerule spawn_mobs false");
				server.runCommand("gamerule advance_time false");
				server.runCommand("time set 1000");
				server.runCommand("gamemode creative @a");
				server.runCommand("fill " + (c.getX() - 20) + " " + c.getY() + " " + (c.getZ() - 20) + " "
					+ (c.getX() + 20) + " " + (c.getY() + 8) + " " + (c.getZ() + 20) + " minecraft:air");
				server.runCommand("fill " + (c.getX() - 20) + " " + (c.getY() - 1) + " " + (c.getZ() - 20) + " "
					+ (c.getX() + 20) + " " + (c.getY() - 1) + " " + (c.getZ() + 20) + " minecraft:stone");
				server.runCommand("tp @a " + c.getX() + " " + c.getY() + " " + c.getZ());
				ctx.waitTicks(15);

				ctx.getInput().pressKey(Agent.get().control.toggleKey);
				ctx.waitTicks(2);
				check(Agent.get().session.isRunning(), "應該是 RUNNING");
				Agent.get().events.drainAll();

				creativeTake(ctx, ai, server);
				creativeMovementAndMining(ctx, ai, server, c);
				creativeBuildPermissions(ctx, ai, server, c);

				// T7(c)：伺服器主動踢掉玩家
				kicked = true;
				disconnectedByKick(ctx, ai, server);
			} finally {
				if (kicked) {
					ctx.setScreen(() -> null);
				} else {
					conn.close();
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void creativeTake(ClientGameTestContext ctx, McpTestClient ai, TestServerContext server) {
		String self = ai.call(ctx, "get_self", args()).text();
		check(self.contains("mode=creative") && self.contains("creative_take"), "T12(a) get_self 應該寫出創造模式：" + self);

		server.runCommand("clear @a");
		ctx.waitTicks(2);
		var r = ai.call(ctx, "creative_take", args("item", "oak_log", "count", 70));
		check(r.ok(), "T12(b) creative_take 應該成功：" + r.text());
		ctx.waitTicks(5);
		int client = ctx.computeOnClient(mc -> Inv.count(mc.player, Items.OAK_LOG));
		int srv = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.OAK_LOG));
		check(client == 70 && srv == 70, "T12(b) 客戶端和伺服器都應該有 70 個 oak_log：client=" + client + " server=" + srv);

		var admin = ai.call(ctx, "creative_take", args("item", "command_block"));
		check(!admin.ok() && "INVALID_ARGUMENT".equals(admin.code()), "T12(b) 管理員物品應該被拒：" + admin.text());
		server.runCommand("clear @a");
		ctx.waitTicks(2);
		System.out.println("[ok] T12(a)(b) creative_take 與管理員物品限制");
	}

	private void creativeMovementAndMining(ClientGameTestContext ctx, McpTestClient ai, TestServerContext server, BlockPos c) {
		Run move = flyThenGoto(ctx, ai, server, c.offset(14, 0, 0));
		Run dig = mineLogs(ctx, ai, server, c.offset(-6, 0, 6));
		ctx.waitTicks(15);
		int corrections = CORRECTIONS.get();
		Vec3 cp = ctx.computeOnClient(mc -> mc.player.position());
		Vec3 sp = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().position());
		check(corrections == 0, "T12(e) 伺服器把玩家拉回了 " + corrections + " 次(移動不正常)");
		check(cp.distanceTo(sp) < 0.5, "T12(e) 伺服器和客戶端的位置不一致：client=" + cp + " server=" + sp);
		System.out.println("[ok] T12(c)-(e) 專服移動：" + move + "；挖掘：" + dig + "；伺服器拉回 0 次");
	}

	private void creativeBuildPermissions(ClientGameTestContext ctx, McpTestClient ai, TestServerContext server, BlockPos c) {
		BlockPos from = c.offset(4, 14, 4);
		BlockPos to = from.east(3);
		BlockPos protectedBlock = from.east(2);
		var wallArgs = args("from", xyz(from), "to", xyz(to), "height", 1, "material", "oak_planks");

		// 無 op：走 Baritone，懸空應被拒
		check(ctx.computeOnClient(mc -> Creative.isCreative(mc) && !BuildCommands.available(mc)), "專用伺服器一般玩家沒有 /fill 權限");
		var denied = ai.call(ctx, "build_wall", wallArgs);
		check(!denied.ok() && "INVALID_ARGUMENT".equals(denied.code()) && denied.text().contains(":unsupported"), "無權限時應走 Baritone：" + denied.text());

		// 創造模式不用指令時，每種材料有 1 個就夠(M9)
		server.runCommand("give @a oak_planks 1");
		for (int i = 0; i < 20 && ctx.computeOnClient(mc -> Inv.count(mc.player, Items.OAK_PLANKS)) == 0; i++) ctx.waitTick();
		BlockPos ground = c.offset(-5, 0, -5);
		var preview = ai.call(ctx, "build_wall", args("from", xyz(ground), "to", xyz(ground.east(3)), "height", 1,
			"material", "oak_planks", "dry_run", true));
		check(preview.ok() && preview.text().contains("method=baritone") && preview.text().contains("buildable=4")
			&& preview.text().contains("missing=none"), "創造模式 1 個木板應該夠蓋 4 格的牆：" + preview.text());
		server.runCommand("clear @a");

		// 授與 op：改用 /fill，但保留既有方塊，且未授權的原始 /fill 仍被 Mixin 擋下
		server.runCommand("op @a");
		for (int i = 0; i < 40 && !ctx.computeOnClient(BuildCommands::available); i++) ctx.waitTick();
		check(ctx.computeOnClient(BuildCommands::available), "op 後應取得建造權限");

		server.runOnServer(s -> s.overworld().setBlock(protectedBlock, Blocks.GLASS.defaultBlockState(), 3, 512));
		for (int i = 0; i < 20 && !ctx.computeOnClient(mc -> mc.level.getBlockState(protectedBlock).is(Blocks.GLASS)); i++) ctx.waitTick();

		var started = ai.call(ctx, "build_wall", wallArgs);
		check(started.ok() && started.text().startsWith("OK started task=build_wall") && started.text().contains("skipped=1"),
			"授權後應能蓋懸空牆並略過既有方塊：" + started.text());
		checkToolText(started.text());
		for (int i = 0; i < 60 && ctx.computeOnClient(mc -> Agent.get().tasks.hasActiveTask()); i++) ctx.waitTick();
		check(server.computeOnServer(s -> {
			for (BlockPos p : BlockPos.betweenClosed(from, to)) {
				if (!s.overworld().getBlockState(p).is(p.equals(protectedBlock) ? Blocks.GLASS : Blocks.OAK_PLANKS)) return false;
			}
			return true;
		}), "指令建造應保留既有玻璃並填完其餘格子");
		check(ctx.computeOnClient(mc -> Agent.get().guard.isOwned(from) && !Agent.get().guard.isOwned(protectedBlock)), "所有權只能包含確認建造的方塊");

		// 未經建造流程授權的原始 /fill 仍應被 Mixin 擋下
		String outside = TestKit.pos(from.north());
		ctx.runOnClient(mc -> ChatGateway.withAiFlag(() -> mc.getConnection().sendCommand("fill "
			+ outside + " " + outside + " minecraft:oak_planks replace minecraft:air")));
		ctx.waitTicks(10);
		check(server.computeOnServer(s -> s.overworld().getBlockState(from.north()).isAir()), "未授權的原始 /fill 仍應被擋下");

		// 單格保護與拆除
		var badBreak = ai.call(ctx, "break_block", args("x", protectedBlock.getX(), "y", protectedBlock.getY(), "z", protectedBlock.getZ()));
		check(!badBreak.ok() && "RULE_PROTECTED_BLOCK".equals(badBreak.code()), "不能拆別人的玻璃：" + badBreak.text());
		var ownBreak = ai.call(ctx, "break_block", args("x", from.getX(), "y", from.getY(), "z", from.getZ()));
		check(ownBreak.ok(), "應能拆除自己的方塊：" + ownBreak.text());
		for (int i = 0; i < 40 && ctx.computeOnClient(mc -> Agent.get().tasks.hasActiveTask()); i++) ctx.waitTick();
		check(server.computeOnServer(s -> s.overworld().getBlockState(from).isAir()) && !ctx.computeOnClient(mc -> Agent.get().guard.isOwned(from)),
			"拆除自己的方塊後應為空氣並移除所有權");
		System.out.println("[ok] T14(d)(e) 專用伺服器 /fill 權限切換、方塊保護與所有權驗證通過");
	}

	private void disconnectedByKick(ClientGameTestContext ctx, McpTestClient ai, TestServerContext server) {
		Agent.get().events.drainAll();
		CompletableFuture<McpTestClient.Result> wait = ai.callAsync("wait_events", args("max_wait_sec", 60));
		ctx.waitTicks(5);
		server.runCommand("kick @a 測試：伺服器把玩家踢掉");
		for (int i = 0; i < 100 && !wait.isDone(); i++) ctx.waitTick();
		check(wait.isDone(), "T7(c) 斷線後 wait_events 沒有返回");
		String text = wait.join().text();
		check(text.contains("disconnected"), "T7(c) 應該收到 disconnected：" + text);
		var r = ai.call(ctx, "goto", args("x", 0, "y", 0, "z", 0));
		check(!r.ok() && "SESSION_ENDED".equals(r.code()), "T7(c) 斷線後不接受動作：" + r.text());
		System.out.println("[ok] T7(c) 斷線：" + text.strip());
	}

	record Run(int ticks, double maxStep, int flyingTicks, double maxReach, int broken) {
		@Override
		public String toString() {
			return String.format("%d tick(%.1f 秒)，最大步幅 %.2f 格，飛行 %d tick%s",
				ticks, ticks / 20.0, maxStep, flyingTicks,
				broken > 0 ? String.format("，挖 %d 格(最遠 %.2f 格)", broken, maxReach) : "");
		}
	}

	static Run sample(ClientGameTestContext ctx, List<BlockPos> watch, int maxTicks) {
		Set<BlockPos> left = new HashSet<>(watch);
		Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
		double maxStep = 0, maxReach = 0;
		int flying = 0, broken = 0, t = 0;
		while (t < maxTicks) {
			ctx.waitTick();
			t++;
			double[] m = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ(), mc.player.getAbilities().flying ? 1 : 0,
				Agent.get().tasks.hasActiveTask() ? 1 : 0});
			double dx = m[0] - last.x, dz = m[1] - last.z;
			maxStep = Math.max(maxStep, Math.sqrt(dx * dx + dz * dz));
			last = new Vec3(m[0], 0, m[1]);
			if (m[2] == 1) flying++;
			for (BlockPos p : new ArrayList<>(left)) {
				double d = ctx.computeOnClient(mc -> mc.level.getBlockState(p).isAir() ? mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) : -1);
				if (d >= 0) {
					left.remove(p);
					broken++;
					maxReach = Math.max(maxReach, d);
				}
			}
			if (m[3] == 0) break;
		}
		return new Run(t, maxStep, flying, maxReach, broken);
	}

	static Run flyThenGoto(ClientGameTestContext ctx, McpTestClient ai, TestServerContext server, BlockPos to) {
		server.runCommand("execute as @a at @s run tp @s ~ ~4 ~");
		ctx.waitTicks(1);
		ctx.runOnClient(mc -> {
			mc.player.getAbilities().flying = true;
			mc.player.onUpdateAbilities();
		});
		ctx.waitTicks(10);
		CORRECTIONS.set(0);
		check(ctx.computeOnClient(mc -> mc.player.getAbilities().flying), "T12(c) 測試前應該在飛");
		// wait_sec=0：開始後馬上回來，一個 tick 一個 tick 取樣移動
		var r = ai.call(ctx, "goto", args("x", to.getX(), "y", to.getY(), "z", to.getZ(), "range", 0, "wait_sec", 0));
		check(r.ok(), "T12(c) goto 應該開始：" + r.text());
		Run run = sample(ctx, List.of(), 600);
		check(run.flyingTicks() <= 2, "T12(c) 開始移動後應該馬上落地，實際還飛了 " + run.flyingTicks() + " tick");
		check(run.maxStep() <= WALK_MAX_PER_TICK, "T12(c) 每 tick 的位移超過走路範圍：" + run);
		check(ctx.computeOnClient(mc -> mc.player.blockPosition().distManhattan(to)) <= 1, "T12(c) 應該走到 " + to.toShortString());
		return run;
	}

	static Run mineLogs(ClientGameTestContext ctx, McpTestClient ai, TestServerContext server, BlockPos base) {
		List<BlockPos> logs = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			logs.add(base.above(i));
			logs.add(base.offset(3, i, 2));
		}
		server.runOnServer(s -> {
			for (BlockPos p : logs) s.overworld().setBlock(p, Blocks.OAK_LOG.defaultBlockState(), 3, 512);
		});
		ctx.waitTicks(10);
		double range = ctx.computeOnClient(mc -> mc.player.blockInteractionRange());
		var r = ai.call(ctx, "mine", args("block", "oak_log", "count", 6, "radius", 24, "wait_sec", 0));
		check(r.ok(), "T12(d) mine 應該開始：" + r.text());
		check(r.text().contains("creative: broken blocks do not drop"), "T12(d) 事前檢查應該提醒創造模式不會掉落：" + r.text());
		Run run = sample(ctx, logs, 900);
		check(run.broken() == 6, "T12(d) 應該挖掉 6 格：" + run);
		check(run.maxReach() <= range + 0.87, "T12(d) 挖的時候離方塊太遠(" + run.maxReach() + " > " + range + "+0.87)：" + run);
		check(run.maxStep() <= WALK_MAX_PER_TICK, "T12(d) 每 tick 的位移超過走路範圍：" + run);
		return run;
	}

	private static String xyz(BlockPos pos) {
		return TestKit.pos(pos).replace(' ', ',');
	}

	private static void checkToolText(String text) {
		check(!text.contains("/fill") && !text.contains("Baritone") && !text.contains("用指令"),
			"工具應回報遊戲結果而非實作細節：" + text);
	}
}
