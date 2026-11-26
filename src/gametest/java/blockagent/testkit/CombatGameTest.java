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
 * 只跑這個：{@code ./gradlew runClientGameTest -PtestOnly=CombatGameTest}；
 * 只跑其中幾個情境：再加 {@code -PtestCase=route,group_geared}(名稱見 runTest)。
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
			// 苦力怕炸了也不破壞場地
			k.cmd("gamerule mob_griefing false");
			k.start();
			// 情境名稱給 -PtestCase= 用，例如 -PtestCase=route,group_geared 只跑這兩個
			if (TestKit.runCase("danger")) dangerWhenUnarmedAtNight(k);
			else k.cmd("time set 18000");
			if (TestKit.runCase("unarmed_skeleton")) unarmedDoesNotChargeSkeleton(k, c);
			if (TestKit.runCase("unarmed_two_zombies")) unarmedDisengagesFromTwoZombies(k, c);
			if (TestKit.runCase("unarmed_one_zombie")) unarmedBeatsOneZombie(k, c);
			if (TestKit.runCase("crit")) huntUsesJumpCrits(k, c);
			if (TestKit.runCase("shield")) shieldBlocksSkeletonAndAutoEquips(k, c);
			if (TestKit.runCase("creeper")) creeperHitAndBack(k, c);
			if (TestKit.runCase("mixed")) zombieAndSkeletonTogether(k, c);
			if (TestKit.runCase("chase")) outmatchedChase(k, c);
			if (TestKit.runCase("group_geared")) gearedVsGroup(k, c);
			if (TestKit.runCase("group_cover")) unarmedEscapesGroupWithCover(k, c);
			if (TestKit.runCase("route")) routeHomeAvoidsGroup(k, c);
			if (TestKit.runCase("surrounded")) surroundedDestinationIsReported(k, c);
			if (TestKit.runCase("retreat")) retreatRecoversThenCancelsOnSecondRetreat(k, c);
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

	/** 苦力怕：拿石劍打了就退(出手前衝刺擊退)，在牠炸之前打死，自己沒被炸到。 */
	private void creeperHitAndBack(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:stone_sword", 1);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:creeper ~5 ~ ~ {PersistenceRequired:1b}");
		boolean gone = waitFor(k, 20 * 30, () -> count(k, EntityTypes.CREEPER) == 0);
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		check(gone, "30 秒內應該解決苦力怕：" + log);
		check(log.contains("hit_and_back"), "打苦力怕應該打了就退：" + log);
		check(summaryOf(log, "creeper").contains("result=killed"), "苦力怕應該是被打死的，不是自己炸掉：" + log);
		check(hp >= 14, "不應該被苦力怕炸到(血量剩 " + hp + ")：" + log);
		System.out.println("[ok] 苦力怕：打了就退、在爆炸前打死，血量 " + hp + " | " + summaryOf(log, "creeper"));
	}

	/** 混戰：殭屍貼身、骷髏在遠處射箭；全副武裝要兩隻都打死，血量不能掉太多。 */
	private void zombieAndSkeletonTogether(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.cmd("difficulty normal");
		for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword")) {
			k.give("minecraft:" + item, 1);
		}
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:zombie ~4 ~ ~ {PersistenceRequired:1b}");
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~-12 ~ ~ {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		boolean cleared = waitFor(k, 20 * 60, () -> count(k, EntityTypes.ZOMBIE) == 0 && count(k, EntityTypes.SKELETON) == 0);
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		k.cmd("difficulty easy");
		check(cleared, "全副武裝應該打死殭屍和骷髏：" + log);
		check(alive(k) && hp >= 8, "混戰後血量應該 ≥ 8(剩 " + hp + ")：" + log);
		// 殭屍被擊退後重新貼上時冷卻總是滿的：滿了也要跳劈，不然實戰打不出暴擊(修正前 0/5)
		check(summaryOf(log, "zombie").matches(".*crits=[1-9].*"), "自衛打殭屍應該有跳劈暴擊：" + summaries(log));
		System.out.println("[ok] 混戰：殭屍 + 骷髏都打死，血量 " + hp + " | " + summaries(log));
	}

	/**
	 * 空手被 2 隻殭屍追：打不贏要甩掉牠們，不能一直被追著打、反覆觸發自衛。先記下數據(觸發次數、挨打、血量)。
	 */
	private void outmatchedChase(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:zombie ~5 ~ ~ {PersistenceRequired:1b}");
		k.cmd("execute as @p at @s run summon minecraft:zombie ~5 ~ ~1 {PersistenceRequired:1b}");
		k.ctx.waitTicks(20 * 30);
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		int starts = log.split("self_defense_start", -1).length - 1;
		int retreats = log.split("retreat_start", -1).length - 1;
		System.out.println("[data] 空手被 2 隻殭屍追 30 秒：self_defense_start=" + starts + " retreat_start=" + retreats + " hp=" + hp
			+ " | " + summaries(log));
		check(alive(k), "被追 30 秒不應該死：" + log);
		check(!log.contains("self_defense_attack"), "打不贏不應該出手：" + log);
		// 甩掉牠們，不要被追著反覆觸發(修正前 30 秒觸發 8 次)
		check(starts <= 3, "被追 30 秒觸發自衛 " + starts + " 次，應該甩掉牠們(≤ 3 次)：" + summaries(log));
		// 站著沒任務，冷卻過了牠們追上來打一兩下是正常的；修正前被追著打到 14.8
		check(hp >= 16, "甩掉牠們就不該一直挨打(血量剩 " + hp + ")：" + summaries(log));
		System.out.println("[ok] 空手被 2 隻殭屍追：甩掉牠們(自衛 " + starts + " 次)、沒出手，血量 " + hp);
	}

	// ---- 一群怪(晚上通常是一群一起來) ----

	/** A1：全副武裝遇到一群(2 隻骷髏分在兩個方向 + 2 隻殭屍)：打得贏就打完、打不贏就脫身；不能死、血量不能掉太多。 */
	private void gearedVsGroup(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.cmd("difficulty normal");
		for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword")) {
			k.give("minecraft:" + item, 1);
		}
		int mark = mark(k);
		summonGroup(k);
		boolean settled = waitFor(k, 20 * 60, () -> groupCount(k) == 0 || (nearestGroupMob(k) > 16 && !anyReflex(k)));
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		k.cmd("difficulty easy");
		System.out.println("[data] A1 全副武裝對一群：剩 " + groupCount(k) + " 隻 hp=" + hp + " | " + odds(log) + summaries(log));
		check(alive(k), "全副武裝對一群不應該死：" + summaries(log));
		check(settled, "60 秒內應該打完或甩掉整群：" + summaries(log));
		// 鐵甲 + 盾 + 石劍：勝算夠(兩隻骷髏差 90 度，盾牌對著目標那隻)，應該打而不是跑。
		// 自衛只處理衝著自己來的；沒靠近的(16 格外、沒打過自己)不追，所以不要求全部打死
		int kills = summaries(log).split("result=killed", -1).length - 1;
		String left = "skeleton=" + count(k, EntityTypes.SKELETON) + " zombie=" + count(k, EntityTypes.ZOMBIE);
		check(odds(log).isEmpty(), "全副武裝不應該決定脫身：" + odds(log) + " | " + summaries(log));
		check(kills >= 2, "全副武裝應該打死衝過來的(打死 " + kills + " 隻，剩 " + left + ")：" + summaries(log));
		check(groupCount(k) == 0 || nearestGroupMob(k) > 8, "結束時不應該還有怪貼在身邊(剩 " + left + ")");
		check(hp >= 6, "對一群打完血量應該 ≥ 6(剩 " + hp + ")：" + summaries(log));
		System.out.println("[ok] A1 全副武裝對一群：打死 " + kills + " 隻、剩 " + left + "，血量 " + hp);
	}

	/** A2：空手遇到同樣一群，場上有柱子：借掩護脫身、甩掉整群，沒死、沒有反覆觸發。 */
	private void unarmedEscapesGroupWithCover(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		// 一圈 1×1×3 的石柱，當掩體
		for (int[] o : new int[][]{{3, 3}, {-3, 3}, {3, -3}, {-3, -3}, {6, 0}, {-6, 0}, {0, 6}, {0, -6}, {8, 8}, {-8, 8}, {8, -8}, {-8, -8}}) {
			k.cmd("fill " + (c.getX() + o[0]) + " " + c.getY() + " " + (c.getZ() + o[1]) + " " + (c.getX() + o[0]) + " " + (c.getY() + 2) + " "
				+ (c.getZ() + o[1]) + " minecraft:stone");
		}
		int mark = mark(k);
		summonGroup(k);
		k.ctx.waitTicks(20 * 40);
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		int starts = log.split("self_defense_start", -1).length - 1;
		double nearest = nearestGroupMob(k);
		k.cmd("fill " + (c.getX() - 8) + " " + c.getY() + " " + (c.getZ() - 8) + " " + (c.getX() + 8) + " " + (c.getY() + 2) + " " + (c.getZ() + 8)
			+ " minecraft:air");
		System.out.println("[data] A2 空手對一群(有柱子)：self_defense_start=" + starts + " nearest=" + Math.round(nearest) + " hp=" + hp
			+ " | " + odds(log) + summaries(log));
		check(alive(k), "空手對一群不應該死：" + summaries(log));
		check(log.contains("hide_from_skeleton") || log.contains("back_off") || log.contains("disengage_outmatched"), "應該脫身：" + log);
		check(!log.contains("skeleton：charge"), "空手不應該衝骷髏：" + summaries(log));
		check(starts <= 4, "40 秒觸發自衛 " + starts + " 次，應該甩掉整群(≤ 4 次)：" + summaries(log));
		check(hp >= 6, "脫身後血量應該 ≥ 6(剩 " + hp + ")：" + summaries(log));
		System.out.println("[ok] A2 空手借掩護脫身：自衛 " + starts + " 次，血量 " + hp + "，最近的怪 " + Math.round(nearest) + " 格");
	}

	/**
	 * B：回家的直線旁邊有怪(不動的骷髏和殭屍)：繞開遠程怪到射程外、近戰怪 8 格外。
	 * 回程時骷髏已經不在了(移走)，但脫身時記下了牠的位置：照樣繞開。
	 */
	private void routeHomeAvoidsGroup(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		BlockPos home = c.offset(0, 0, 40);
		BlockPos skel = c.offset(5, 0, 20), zomb = c.offset(4, 0, 6);
		k.cmd("summon minecraft:skeleton " + TestKit.pos(skel) + " {NoAI:1b,PersistenceRequired:1b}");
		k.cmd("summon minecraft:zombie " + TestKit.pos(zomb) + " {NoAI:1b,PersistenceRequired:1b}");
		// 帶 NBT 叫出來的骷髏不會自動拿弓；沒弓的骷髏是近戰怪(只繞 8 格)
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		k.ctx.waitTicks(10);
		double[] min = walk(k, home, List.of(skel, zomb));
		System.out.println("[data] B 去程：離骷髏最近 " + Math.round(min[0]) + " 格、離殭屍最近 " + Math.round(min[1]) + " 格、到家=" + (min[2] > 0));
		check(min[2] > 0, "應該走到家");
		check(min[0] >= 13, "回家應該繞開骷髏到射程外(最近 " + Math.round(min[0]) + " 格)");
		check(min[1] >= 6, "回家應該繞開殭屍(最近 " + Math.round(min[1]) + " 格)");

		// 記住剛才那群怪的位置，再把骷髏移走(看不到了)、殭屍清掉：回程照樣繞開骷髏原本的位置
		k.ctx.runOnClient(mc -> k.agent().danger.remember(mc, null, 64, k.agent().tick()));
		k.cmd("tp @e[type=minecraft:skeleton] " + c.getX() + " " + c.getY() + " " + (c.getZ() - 300));
		k.cmd("kill @e[type=minecraft:zombie]");
		k.ctx.waitTicks(20);
		double[] back = walk(k, c, List.of(skel));
		System.out.println("[data] B 回程(骷髏已移走、靠記憶)：離原位置最近 " + Math.round(back[0]) + " 格、到達=" + (back[2] > 0));
		check(back[2] > 0, "回程應該走到");
		check(back[0] >= 13, "回程應該照記憶繞開骷髏原本的位置(最近 " + Math.round(back[0]) + " 格)");
		k.ctx.runOnClient(mc -> k.agent().danger.clear());
		System.out.println("[ok] B 回家繞開怪：骷髏 " + Math.round(min[0]) + " 格、殭屍 " + Math.round(min[1]) + " 格、靠記憶 " + Math.round(back[0]) + " 格");
	}

	/** B2：目的地被怪圍住：goto 不卡住，回報 destination_hostile 給 AI。 */
	private void surroundedDestinationIsReported(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		BlockPos home = c.offset(0, 0, -25);
		for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}}) {
			k.cmd("summon minecraft:zombie " + TestKit.pos(home.offset(o[0], 0, o[1])) + " {NoAI:1b,PersistenceRequired:1b}");
		}
		k.cmd("summon minecraft:skeleton " + TestKit.pos(home.offset(0, 0, -5)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		k.ctx.waitTicks(10);
		var r = k.ai.call(k.ctx, "goto", args("x", home.getX(), "y", home.getY(), "z", home.getZ(), "wait_sec", 0));
		k.ai.call(k.ctx, "stop");
		check(r.ok(), "goto 不應該失敗：" + r.text());
		check(r.text().contains("destination_hostile") && r.text().contains("zombie:3") && r.text().contains("skeleton:1"),
			"目的地被怪圍住應該回報 destination_hostile：" + r.text());
		System.out.println("[ok] B2 目的地被怪圍住：回報 destination_hostile");
	}

	/** 2 隻骷髏分在東、南兩個方向(12 格)，2 隻殭屍在 5 格外。 */
	private static void summonGroup(TestKit k) {
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~-12 ~ ~ {PersistenceRequired:1b}");
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~ ~ ~12 {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		k.cmd("execute as @p at @s run summon minecraft:zombie ~5 ~ ~ {PersistenceRequired:1b}");
		k.cmd("execute as @p at @s run summon minecraft:zombie ~5 ~ ~1 {PersistenceRequired:1b}");
	}

	private static int groupCount(TestKit k) {
		return count(k, EntityTypes.SKELETON) + count(k, EntityTypes.ZOMBIE);
	}

	/** 離自己最近的骷髏或殭屍有幾格(沒有了回傳很大的數)。 */
	private static double nearestGroupMob(TestKit k) {
		var me = k.ctx.computeOnClient(mc -> mc.player.position());
		return k.server.computeOnServer(s -> {
			double best = 1e9;
			for (var e : s.overworld().getEntities(EntityTypes.SKELETON, x -> x.isAlive())) best = Math.min(best, e.position().distanceTo(me));
			for (var e : s.overworld().getEntities(EntityTypes.ZOMBIE, x -> x.isAlive())) best = Math.min(best, e.position().distanceTo(me));
			return best;
		});
	}

	private static boolean anyReflex(TestKit k) {
		return k.agent().reflexes.active() != null;
	}

	/** goto 到 to，每 tick 記下離各點的最近水平距離；回傳 [離第 1 點, 離第 2 點, 到了沒(1/0)]。 */
	private static double[] walk(TestKit k, BlockPos to, List<BlockPos> points) {
		var r = k.ai.call(k.ctx, "goto", args("x", to.getX(), "y", to.getY(), "z", to.getZ(), "wait_sec", 0));
		check(r.ok(), "goto 失敗：" + r.text());
		double[] min = {1e9, 1e9, 0};
		for (int t = 0; t < 20 * 45; t++) {
			var pos = k.ctx.computeOnClient(mc -> mc.player.position());
			for (int i = 0; i < points.size() && i < 2; i++) {
				var pt = points.get(i);
				double dx = pos.x - (pt.getX() + 0.5), dz = pos.z - (pt.getZ() + 0.5);
				min[i] = Math.min(min[i], Math.sqrt(dx * dx + dz * dz));
			}
			if (!k.agent().tasks.hasActiveTask()) break;
			k.ctx.waitTick();
		}
		var end = k.ctx.computeOnClient(mc -> mc.player.blockPosition());
		min[2] = end.distManhattan(to) <= 3 ? 1 : 0;
		k.ai.call(k.ctx, "stop");
		return min;
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

	/** 紀錄裡所有 fight_summary 的內容，一行一筆。 */
	private static String summaries(String log) {
		StringBuilder sb = new StringBuilder();
		for (String line : log.split("\n")) {
			if (!line.contains("fight_summary")) continue;
			int i = line.indexOf("\"result\":\"");
			if (i >= 0) sb.append(line, i + 10, line.lastIndexOf('"')).append(" ; ");
		}
		return sb.toString();
	}

	/** 決定脫身時記下的勝算依據。 */
	private static String odds(String log) {
		StringBuilder sb = new StringBuilder();
		for (String line : log.split("\n")) {
			int i = line.indexOf("_odds\",\"result\":\"");
			if (i >= 0) sb.append(line, i + 17, line.lastIndexOf('"')).append(" ; ");
		}
		return sb.toString();
	}

	/** 打這種怪的 fight_summary(沒有回傳空字串)。 */
	private static String summaryOf(String log, String target) {
		for (String part : summaries(log).split(" ; ")) if (part.contains("targets=" + target) || part.contains("," + target)) return part;
		return "";
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
