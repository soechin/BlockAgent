package blockagent.testkit;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * T18(a)–(e)：生存進程的地獄階段(docs/SPEC.md S4.2k、S7.3a、S3.2、S4.3)。
 * 蓋門點火 → 一般走路不穿門 → 走進門到地獄 → 地獄門框受保護、不報黑暗、能採地獄疙瘩 → 跨維度結束任務 → 主世界的疙瘩受保護。
 */
@SuppressWarnings("UnstableApiUsage")
public class NetherGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("NetherGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			k.flatArena(c, 12);
			k.start();
			buildPortal(k, c);
			walkAroundPortal(k, c);
			enterPortal(k);
			netherRules(k);
			dimensionChangeEndsTask(k, c);
			overworldWartProtected(k, c);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** 任務開始後一直讀事件到結束(task_done 或 task_failed)，回傳全部文字。 */
	private static String finish(TestKit k, McpTestClient.Result r, int maxSec) {
		check(r.ok(), "任務啟動失敗：" + r.text());
		StringBuilder all = new StringBuilder(r.text());
		if (!r.text().contains("WAIT task_running")) return all.toString();
		long deadline = System.currentTimeMillis() + maxSec * 1000L;
		while (System.currentTimeMillis() < deadline && all.indexOf("task_done:") < 0 && all.indexOf("task_failed:") < 0) {
			all.append('\n').append(k.ai.call(k.ctx, "wait_events", args("max_wait_sec", 2)).text());
		}
		return all.toString();
	}

	private static boolean blockIs(TestKit k, BlockPos p, net.minecraft.world.level.block.Block b) {
		return k.ctx.computeOnClient(mc -> mc.level.getBlockState(p).is(b));
	}

	/** T18(a)：缺黑曜石回 MISSING_MATERIALS；補齊後蓋好 10 格框、墊 4 角、點火，門亮起來。 */
	private void buildPortal(TestKit k, BlockPos c) {
		k.clearInventory();
		k.give("minecraft:obsidian", 9);
		k.give("minecraft:cobblestone", 4);
		k.give("minecraft:flint_and_steel", 1);
		BlockPos origin = c.offset(3, 0, -1);
		var build = args("action", "build", "x", origin.getX(), "y", origin.getY(), "z", origin.getZ(), "axis", "z", "wait_sec", 60);
		k.expectError(k.ai.call(k.ctx, "nether_portal", build), "MISSING_MATERIALS", "T18(a) 只有 9 個黑曜石");
		k.give("minecraft:obsidian", 1);
		String r = finish(k, k.ai.call(k.ctx, "nether_portal", build), 90);
		check(r.contains("lit=true"), "T18(a) 應蓋好並點燃傳送門：" + r);
		check(blockIs(k, origin.offset(0, 1, 1), Blocks.NETHER_PORTAL) && blockIs(k, origin.offset(0, 3, 2), Blocks.NETHER_PORTAL),
			"T18(a) 門裡應是傳送門方塊");
		check(blockIs(k, origin, Blocks.COBBLESTONE) && blockIs(k, origin.offset(0, 4, 3), Blocks.COBBLESTONE),
			"T18(a) 角落應墊圓石");
		check(k.ctx.computeOnClient(mc -> mc.player.getInventory().countItem(Items.OBSIDIAN) == 0), "T18(a) 10 個黑曜石都用在框上");
		System.out.println("[ok] T18(a) 蓋門、墊角、點火");
	}

	/** T18(b)：一般 goto 的直線剛好穿過傳送門，Baritone 要繞過去，不能誤傳到地獄。 */
	private void walkAroundPortal(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c.offset(0, 0, 1)));
		k.ctx.waitTicks(5);
		BlockPos target = c.offset(7, 0, 1);
		String r = finish(k, k.ai.call(k.ctx, "goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ(), "wait_sec", 30)), 40);
		check(r.contains("arrived=") && k.ctx.computeOnClient(mc -> mc.level.dimension().equals(Level.OVERWORLD)),
			"T18(b) goto 應繞過傳送門走到另一邊：" + r);
		System.out.println("[ok] T18(b) 一般走路不穿過傳送門");
	}

	/** T18(c)：nether_portal enter 走進門、站到換維度，結果帶抵達點與對應座標。 */
	private void enterPortal(TestKit k) {
		String r = finish(k, k.ai.call(k.ctx, "nether_portal", args("action", "enter", "wait_sec", 40)), 60);
		check(r.contains("dim=the_nether") && r.contains("linked_xz=") && r.contains("from=overworld"),
			"T18(c) 應進入地獄並回報對應座標：" + r);
		check(k.waitUntil(40, mc -> mc.level.dimension().equals(Level.NETHER)), "T18(c) 客戶端應在地獄");
		System.out.println("[ok] T18(c) 走進傳送門到地獄");
	}

	/** T18(d)(e)：地獄出口門框不能拆；地獄裡不報黑暗；地獄疙瘩算天然、mine 採得到。 */
	private void netherRules(TestKit k) {
		k.ctx.waitTicks(10);
		BlockPos frame = k.ctx.computeOnClient(mc -> {
			BlockPos me = mc.player.blockPosition();
			BlockPos best = null;
			for (BlockPos p : BlockPos.betweenClosed(me.offset(-4, -3, -4), me.offset(4, 4, 4))) {
				if (!mc.level.getBlockState(p).is(Blocks.OBSIDIAN)) continue;
				boolean nextToPortal = false;
				for (Direction d : Direction.values()) nextToPortal |= mc.level.getBlockState(p.relative(d)).is(Blocks.NETHER_PORTAL);
				if (nextToPortal && mc.player.isWithinBlockInteractionRange(p, 0.0)) best = p.immutable();
			}
			return best;
		});
		check(frame != null, "T18(d) 地獄出口附近應找得到門框黑曜石");
		k.give("minecraft:diamond_pickaxe", 1);
		k.expectError(k.ai.call(k.ctx, "break_block", args("x", frame.getX(), "y", frame.getY(), "z", frame.getZ())),
			"RULE_PROTECTED_BLOCK", "T18(d) 地獄生成的門框不能拆");

		// 封閉平台：暗處、有地獄疙瘩(成熟)長在靈魂沙上
		k.onServer(s -> {
			var nether = s.getLevel(Level.NETHER);
			nether.getChunk(0, 0);
			for (BlockPos p : BlockPos.betweenClosed(-4, 86, -4, 4, 92, 4)) {
				boolean shell = Math.abs(p.getX()) == 4 || Math.abs(p.getZ()) == 4 || p.getY() == 86 || p.getY() == 92;
				nether.setBlock(p, (shell || p.getY() == 87 ? Blocks.NETHERRACK : Blocks.AIR).defaultBlockState(), 3, 512);
			}
			nether.setBlock(new BlockPos(2, 87, 0), Blocks.SOUL_SAND.defaultBlockState(), 3, 512);
			nether.setBlock(new BlockPos(2, 88, 0), Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3), 3, 512);
		});
		k.cmd("execute in minecraft:the_nether run tp @p -1.5 88 0.5");
		k.ctx.waitTicks(10);
		k.agent().events.drainAll();
		k.ctx.waitTicks(100);
		String events = k.ai.call(k.ctx, "wait_events", args("max_wait_sec", 1)).text();
		check(!events.contains("low_light"), "T18(e) 地獄沒有天空光，不該報黑暗：" + events);
		String r = finish(k, k.ai.call(k.ctx, "mine", args("block", "nether_wart", "count", 1, "radius", 8, "wait_sec", 30)), 40);
		check(k.ctx.computeOnClient(mc -> mc.player.getInventory().countItem(Items.NETHER_WART) >= 1),
			"T18(e) 地獄裡的地獄疙瘩應採得到：" + r);
		System.out.println("[ok] T18(d)(e) 門框保護、地獄不報黑暗、採到地獄疙瘩");
	}

	/** T18(b)：任務進行中被傳到別的維度，任務結束並說明原因。 */
	private void dimensionChangeEndsTask(TestKit k, BlockPos c) {
		k.agent().events.drainAll();
		var r = k.ai.call(k.ctx, "goto", args("x", 60, "y", 88, "z", 0, "wait_sec", 0));
		check(r.ok(), "goto 應能開始：" + r.text());
		k.cmd("execute in minecraft:overworld run tp @p " + TestKit.pos(c));
		check(k.waitUntil(160, mc -> mc.level.dimension().equals(Level.OVERWORLD)), "T18(b) 未返回主世界");
		String events = k.eventsUntil("task_failed:", 8);
		check(events.contains("another dimension"), "T18(b) 跨維度應結束任務：" + events);
		System.out.println("[ok] T18(b) 跨維度結束任務");
	}

	/** T18(e)：主世界的地獄疙瘩是別人種的，不能拆。 */
	private void overworldWartProtected(TestKit k, BlockPos c) {
		BlockPos wart = c.offset(-2, 0, 0);
		k.setBlock(wart.below(), Blocks.SOUL_SAND.defaultBlockState());
		k.setBlock(wart, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
		k.ctx.waitTicks(5);
		k.expectError(k.ai.call(k.ctx, "break_block", args("x", wart.getX(), "y", wart.getY(), "z", wart.getZ())),
			"RULE_PROTECTED_BLOCK", "T18(e) 主世界的地獄疙瘩不能拆");
		System.out.println("[ok] T18(e) 主世界的地獄疙瘩受保護");
	}
}
