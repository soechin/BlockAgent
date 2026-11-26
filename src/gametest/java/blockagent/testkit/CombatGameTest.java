package blockagent.testkit;

import blockagent.game.reflex.Reflexes;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.util.List;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * 戰鬥(docs/SPEC.md S9.1、S9.1b、S9.1c；docs/REVIEW.md R1、R2、R3、R7)：夜裡在平地放真的怪，讓反射自己處理，
 * 從遊戲狀態和 ActionLog 驗證。每個情境都有上限時間，結束時清掉怪物、補滿血。
 * <p>
 * 只跑這個：{@code ./gradlew runClientGameTest -PtestOnly=CombatGameTest}
 */
@SuppressWarnings("UnstableApiUsage")
public class CombatGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("CombatGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			k.flatArena(c, 30);
			k.start();
			dangerWhenUnarmedAtNight(k);
			unarmedDoesNotChargeSkeleton(k, c);
			unarmedDisengagesFromTwoZombies(k, c);
			unarmedBeatsOneZombie(k, c);
			huntUsesJumpCrits(k, c);
			shieldBlocksSkeletonAndAutoEquips(k, c);
			retreatRecoversThenCancelsOnSecondRetreat(k, c);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// ---- 情境 ----

	/** S9.1c：夜裡在戶外、空手沒甲：danger=high、gear=weak。 */
	private void dangerWhenUnarmedAtNight(TestKit k) {
		k.clearInventory();
		// 先在白天待一下(danger=low，重設 danger_rising)，再入夜：夜裡不死系才不會自燃；不會自然生怪(TestKit 關掉了)
		k.ctx.waitTicks(30);
		k.agent().events.drainAll();
		k.cmd("time set 18000");
		k.ctx.waitTicks(30);
		var self = k.ai.call(k.ctx, "get_self");
		check(self.ok(), "get_self 失敗：" + self.text());
		check(self.text().contains("danger=high") && self.text().contains("gear=weak") && self.text().contains("dark"),
			"夜裡空手在戶外應該是 danger=high gear=weak：" + self.text());
		// 待送的事件會跟著工具回應一起送出(S3.0e)：可能已經附在 get_self 的回應裡
		String ev = self.text().contains("danger_rising") ? self.text() : k.eventsUntil("danger_rising", 4);
		check(ev.contains("danger_rising"), "應該收到 danger_rising：" + self.text() + "\n" + ev);
		System.out.println("[ok] S9.1c 夜裡空手：danger=high、gear=weak、發出 danger_rising");
	}

	/** R7：空手沒盾遇到骷髏，不衝過去(不出現 charge、也沒有出手)，改成躲或拉開距離。 */
	private void unarmedDoesNotChargeSkeleton(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~10 ~ ~ {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		check(waitLog(k, mark, "self_defense_start", 20 * 10), "骷髏 10 格外瞄準時應該觸發自衛：" + since(k, mark));
		k.ctx.waitTicks(20 * 4);
		String log = since(k, mark);
		check(log.contains("hide_from_skeleton") || log.contains("back_off"), "空手對骷髏應該躲起來或拉開距離：" + log);
		check(!log.contains("skeleton：charge"), "空手沒盾不應該衝過去打骷髏：" + log);
		System.out.println("[ok] R7 空手遇到骷髏：躲或拉開距離，不衝");
	}

	/** R7：空手沒甲對 2 隻殭屍打不贏，拉開距離(disengage_outmatched)，不出手。 */
	private void unarmedDisengagesFromTwoZombies(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:zombie ~5 ~ ~ {PersistenceRequired:1b}");
		k.cmd("execute as @p at @s run summon minecraft:zombie ~5 ~ ~1 {PersistenceRequired:1b}");
		check(waitLog(k, mark, "disengage_outmatched", 20 * 15), "空手對 2 隻殭屍應該拉開距離：" + since(k, mark));
		String log = since(k, mark);
		check(!log.contains("self_defense_attack"), "打不贏的時候不應該出手：" + log);
		System.out.println("[ok] R7 空手對 2 隻殭屍：disengage_outmatched");
	}

	/** R7：空手、滿血、沒甲對 1 隻殭屍打得贏(預估掉 7.5 血)：照打而且打死。 */
	private void unarmedBeatsOneZombie(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:zombie ~4 ~ ~ {PersistenceRequired:1b}");
		boolean dead = waitFor(k, 20 * 40, () -> count(k, EntityTypes.ZOMBIE) == 0);
		String log = since(k, mark);
		check(dead, "空手滿血對 1 隻殭屍應該打得死：" + log);
		check(log.contains("self_defense_attack") && !log.contains("disengage_outmatched"), "應該照打，不是拉開距離：" + log);
		check(alive(k), "打殭屍時不應該死掉");
		System.out.println("[ok] R7 空手滿血打死 1 隻殭屍");
	}

	/** 跳劈：拿石劍 hunt 一隻不會動的殭屍，至少有一下是暴擊，而且有 fight_summary。 */
	private void huntUsesJumpCrits(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:stone_sword", 1);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:zombie ~3 ~ ~ {NoAI:1b,PersistenceRequired:1b}");
		k.ctx.waitTicks(5);
		var r = k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 30));
		String log = since(k, mark);
		// 只驗證戰鬥本身；撿不撿得到掉落物是 hunt 另外的事
		check(count(k, EntityTypes.ZOMBIE) == 0, "hunt 應該打死殭屍：" + r.text() + "\n" + log);
		check(log.contains("hunt_attack") && log.contains(" crit"), "拿劍應該跳劈(紀錄有 crit)：" + log);
		check(log.contains("fight_summary") && log.matches("(?s).*crits=[1-9].*"), "fight_summary 應該記到暴擊：" + log);
		System.out.println("[ok] 跳劈：hunt 拿石劍有暴擊");
	}

	/**
	 * R1、R2、自動備戰：背包裡有鐵甲、盾牌和石劍(都沒穿)，骷髏出現時自動穿上；牠射箭時盾牌擋下(盾牌耐久減少)，
	 * 而且最後打死牠、自己沒死。
	 */
	private void shieldBlocksSkeletonAndAutoEquips(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		// 一般難度：骷髏的箭傷害 ≥ 3，盾牌擋下才會扣耐久，看得出擋到了
		k.cmd("difficulty normal");
		for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword")) {
			k.give("minecraft:" + item, 1);
		}
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~11 ~ ~ {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		boolean geared = k.waitUntil(20 * 5, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
			&& mc.player.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS));
		check(geared, "骷髏出現時應該自動穿上鐵甲、把盾牌換到副手：" + since(k, mark));
		boolean dead = waitFor(k, 20 * 60, () -> count(k, EntityTypes.SKELETON) == 0);
		String log = since(k, mark);
		int shieldDamage = k.ctx.computeOnClient(mc -> mc.player.getOffhandItem().getDamageValue());
		check(dead, "全副武裝應該打死骷髏：" + log);
		check(alive(k), "全副武裝打骷髏不應該死");
		check(log.contains("shield_vs_bow"), "骷髏拉弓時應該舉盾：" + log);
		check(shieldDamage > 0 || log.matches("(?s).*shield_blocks=[1-9].*"), "盾牌應該至少擋下一箭(耐久沒變)：" + log);
		k.cmd("difficulty easy");
		System.out.println("[ok] R1 盾牌擋箭(耐久 -" + shieldDamage + ")、自動備戰、打死骷髏");
	}

	/**
	 * R3：低血量時撤退會暫停任務、跑開、停下吃東西，血量 > 10 才結束並接著做；同一個任務第二次撤退則取消任務。
	 * 殭屍用 NoAI 固定在原地，結果才穩定。
	 */
	private void retreatRecoversThenCancelsOnSecondRetreat(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:bread", 8);
		k.cmd("execute as @p at @s run summon minecraft:zombie ~7 ~ ~ {NoAI:1b,PersistenceRequired:1b}");
		k.ctx.waitTicks(5);
		// 往跟殭屍垂直的方向走一段，撤退時要暫停這個任務
		var go = k.ai.call(k.ctx, "goto", args("x", c.getX(), "y", c.getY(), "z", c.getZ() + 26, "wait_sec", 0));
		check(go.ok(), "goto 失敗：" + go.text());
		k.ctx.waitTicks(5);
		k.agent().events.drainAll();
		lowHealth(k);
		String ev = k.eventsUntil("reflex=retreat", 40);
		check(ev.contains("result=recovered") && ev.contains("task_resumed="), "第一次撤退應該回血後接著做任務：" + ev);
		check(k.ctx.computeOnClient(mc -> mc.player.getHealth()) > 10, "撤退結束時血量應該 > 10");
		check(k.agent().tasks.hasActiveTask(), "第一次撤退後 goto 應該還在進行");

		// 同一個任務第二次撤退：把殭屍拉到身邊再壓低血量
		k.cmd("execute as @p at @s run tp @e[type=minecraft:zombie,limit=1] ~5 ~ ~");
		lowHealth(k);
		ev = k.eventsUntil("reflex=retreat", 40);
		check(ev.contains("outcome=retreated_twice") && ev.contains("task_cancelled="), "同一個任務第二次撤退應該取消任務：" + ev);
		check(!k.agent().tasks.hasActiveTask(), "第二次撤退後任務應該被取消");
		System.out.println("[ok] R3 撤退：回血後接著做；同一任務第二次撤退就取消");
	}

	// ---- 工具 ----

	/** 血量 5、飢餓度 12、沒有飽和：撤退要吃兩個麵包才回得到 > 10。 */
	private static void lowHealth(TestKit k) {
		k.onServer(s -> {
			var p = k.me(s);
			p.setHealth(5);
			p.getFoodData().setFoodLevel(12);
			p.getFoodData().setSaturation(0);
		});
		k.ctx.waitTicks(2);
	}

	/** 清掉怪物、等反射結束、補滿血和飢餓、清空背包、回到場地中央。 */
	private static void reset(TestKit k, BlockPos c) {
		k.cmd("kill @e[type=!minecraft:player]");
		k.ai.call(k.ctx, "stop");
		k.waitUntil(20 * 10, mc -> k.agent().reflexes.active() != Reflexes.Kind.RETREAT
			&& k.agent().reflexes.active() != Reflexes.Kind.SELF_DEFENSE);
		k.onServer(s -> {
			var p = k.me(s);
			p.setHealth(p.getMaxHealth());
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5);
		});
		k.clearInventory();
		k.cmd("tp @p " + TestKit.pos(c));
		k.ctx.waitTicks(10);
		k.agent().events.drainAll();
	}

	/** 在測試執行緒上每 10 tick 檢查一次(條件可以查伺服器)。 */
	private static boolean waitFor(TestKit k, int maxTicks, java.util.function.BooleanSupplier cond) {
		for (int i = 0; i < maxTicks; i += 10) {
			if (cond.getAsBoolean()) return true;
			k.ctx.waitTicks(10);
		}
		return cond.getAsBoolean();
	}

	private static boolean alive(TestKit k) {
		return k.ctx.computeOnClient(mc -> mc.player.isAlive() && mc.player.getHealth() > 0);
	}

	private static int count(TestKit k, EntityType<?> type) {
		return k.server.computeOnServer(s -> s.overworld().getEntities(type, e -> e.isAlive()).size());
	}

	private static int mark(TestKit k) throws Exception {
		return Files.readAllLines(k.agent().log.file()).size();
	}

	private static String since(TestKit k, int mark) {
		try {
			List<String> lines = Files.readAllLines(k.agent().log.file());
			// ActionLog 是 Gson 寫的 JSON：= < > & ' 會被跳脫
			return String.join("\n", lines.subList(Math.min(mark, lines.size()), lines.size()))
				.replace("\\u003d", "=").replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&").replace("\\u0027", "'");
		} catch (Exception e) {
			return "(log unreadable: " + e + ")";
		}
	}

	private static boolean waitLog(TestKit k, int mark, String needle, int maxTicks) {
		for (int i = 0; i < maxTicks; i += 10) {
			if (since(k, mark).contains(needle)) return true;
			k.ctx.waitTicks(10);
		}
		return since(k, mark).contains(needle);
	}
}
