package blockagent.testkit;

import blockagent.game.Bari;
import blockagent.game.combat.CoverRoute;
import blockagent.game.reflex.Reflexes;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * 戰鬥(docs/SPEC.md S9.1、S9.1b、S9.1c；docs/REVIEW.md R1、R2、R3、R7)：夜裡在平地放真的怪，讓反射自己處理，
 * 從遊戲狀態和 ActionLog 驗證。每個情境都有上限時間，結束時清掉怪物、補滿血。
 * <p>
 * 預設在一般建置／{@code runClientGameTest} 時跳過(耗時較久)；需要時顯式執行：
 * {@code ./gradlew runClientGameTest -PtestOnly=CombatGameTest} 或 {@code -PcombatTest=true}；
 * 只跑其中幾個情境：再加 {@code -PtestCase=route,group_geared}(名稱見 runTest)。
 */
@SuppressWarnings("UnstableApiUsage")
public class CombatGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skipOptIn("CombatGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			k.flatArena(c, 30);
			// 苦力怕炸了也不破壞場地
			k.cmd("gamerule mob_griefing false");
			k.start();
			// 情境名稱給 -PtestCase= 用，例如 -PtestCase=route,group_geared 只跑這兩個
			if (TestKit.runCase("danger")) darknessIsAdvisory(k, c);
			k.cmd("time set 18000");
			if (TestKit.runCase("difficulty_safety")) difficultyRetreat(k, c);
			if (TestKit.runCase("hard_hunt") && k.difficulty() == Difficulty.HARD) hardHuntWithdraws(k, c);
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
			if (TestKit.runCase("resume_home")) resumeHomeAfterEscaping(k, c, ShooterBehavior.STILL);
			if (TestKit.runCase("resume_home_pursuit")) resumeHomeAfterEscaping(k, c, ShooterBehavior.PURSUE);
			if (TestKit.runCase("resume_home_moved")) resumeHomeAfterEscaping(k, c, ShooterBehavior.MOVE_INTO_ROUTE);
			if (TestKit.runCase("surrounded")) surroundedDestinationIsReported(k, c);
			if (TestKit.runCase("ring_unarmed")) surroundedOnAllSides(k, c, false);
			if (TestKit.runCase("ring_geared")) surroundedOnAllSides(k, c, true);
			if (TestKit.runCase("retreat")) retreatRecoversThenCancelsOnSecondRetreat(k, c);
			if (TestKit.runCase("retreat_wall")) retreatBehindWallDoesNotOscillate(k, c);
			if (TestKit.runCase("terrain_high")) elevatedArcherAndUnreachableCover(k, c);
			if (TestKit.runCase("cover_route")) retreatPrefersLessExposedRoute(k, c);
			if (TestKit.runCase("bow_duel")) bowAndShieldDuel(k, c);
			if (TestKit.runCase("bow_creeper")) shootCreeperBeforeItSwells(k, c);
			if (TestKit.runCase("bow_distance")) finishDrawAcrossDistanceThreshold(k, c);
			if (TestKit.runCase("bow_cancel_close")) logDrawCancelWhenTargetCloses(k, c);
			if (TestKit.runCase("bow_cancel_blocked")) logDrawCancelWhenTrajectoryCloses(k, c);
			if (TestKit.runCase("bow_cancel_shield")) logDrawCancelForShield(k, c);
			if (TestKit.runCase("bow_cancel_client_state")) cancelDrawAfterClientStateClears(k, c);
			if (TestKit.runCase("bow_cancel_retarget")) logDrawCancelForRetarget(k, c);
			if (TestKit.runCase("bow_embedded")) logArrowAfterLaunch(k, c, false);
			if (TestKit.runCase("bow_unconfirmed")) logArrowAfterLaunch(k, c, true);
			if (TestKit.runCase("bow_roof")) turnToMeleeWhenShotIsBlocked(k, c);
			if (TestKit.runCase("enderman")) doNotShootEnderman(k, c);
			if (TestKit.runCase("crossbow")) useLoadedCrossbow(k, c);
			if (TestKit.runCase("piglin")) respondToPiglinCrossbow(k, c);
			if (TestKit.runCase("blaze_melee")) huntBlazeWithoutBow(k, c);
			if (TestKit.runCase("deep_water")) avoidDeepWaterPursuit(k, c);
			if (TestKit.runCase("shallow_water")) canHuntInShallowWater(k, c);
			if (TestKit.runCase("blast_priority")) avoidCreeperWhileDisengaging(k, c);
			if (TestKit.runCase("retarget")) continueDefenseAfterKill(k, c);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// ---- 情境 ----

	/** S9.1c：短暫進出暗處、持續昏暗、補光，以及黑暗中遇到威脅的事件。 */
	private void darknessIsAdvisory(TestKit k, BlockPos c) {
		k.clearInventory();
		BlockPos dark = c.east(12);
		String bounds = TestKit.pos(dark.offset(-1, 0, -1)) + " " + TestKit.pos(dark.offset(1, 2, 1));
		k.cmd("fill " + bounds + " minecraft:stone");
		k.cmd("fill " + TestKit.pos(dark) + " " + TestKit.pos(dark.above()) + " minecraft:air");
		k.ctx.waitTicks(20);
		k.agent().events.drainAll();

		k.cmd("tp @p " + TestKit.pos(dark));
		k.ctx.waitTicks(25);
		String brief = k.ai.call(k.ctx, "get_self").text();
		check(brief.contains("danger=medium reasons=dark,gear_weak")
			&& brief.contains("block_light=0 sky_light=0 sky_visible=false"), "白天的暗處應是中度風險：" + brief);
		check(!brief.contains("low_light:") && !brief.contains("danger_rising:"), "短暫昏暗不應發出告警：" + brief);

		k.cmd("tp @p " + TestKit.pos(c));
		k.ctx.waitTicks(10);
		k.cmd("tp @p " + TestKit.pos(dark));
		k.ctx.waitTicks(40);
		String early = k.ai.call(k.ctx, "get_self").text();
		check(!early.contains("low_light:"), "回到亮處後應重新計算昏暗時間：" + early);
		k.ctx.waitTicks(25);
		String sustained = k.ai.call(k.ctx, "get_self").text();
		check(sustained.lines().anyMatch(line -> line.contains("low_light: danger=medium")
				&& line.contains("pos=" + TestKit.pos(dark).replace(' ', ','))
				&& line.contains("block_light=0 sky_light=0 sky_visible=false"))
			&& !sustained.contains("danger_rising:"),
			"持續昏暗應送出附光照與座標的提醒：" + sustained);
		k.ctx.waitTicks(25);
		String repeated = k.ai.call(k.ctx, "get_self").text();
		check(!repeated.contains("low_light:"), "同一段昏暗只提醒一次：" + repeated);
		k.setBlock(dark, Blocks.TORCH.defaultBlockState());
		k.ctx.waitTicks(15);
		String lit = k.ai.call(k.ctx, "get_self").text();
		check(lit.contains("danger=low"), "補光後應回到低度風險：" + lit);

		k.cmd("tp @p " + TestKit.pos(c));
		k.cmd("fill " + bounds + " minecraft:air");
		k.cmd("time set 18000");
		k.setBlock(c.east(), Blocks.TORCH.defaultBlockState());
		k.ctx.waitTicks(15);
		String outdoorLit = k.ai.call(k.ctx, "get_self").text();
		check(outdoorLit.contains("danger=low reasons=outdoors,gear_weak"), "夜間露天的火把應照亮腳下：" + outdoorLit);

		k.setBlock(c.east(), Blocks.AIR.defaultBlockState());
		for (int z = 0; z < 3; z++) {
			k.cmd("execute at @p run summon minecraft:zombie ~20 ~ ~" + z + " {NoAI:1b,PersistenceRequired:1b}");
		}
		k.ctx.waitTicks(22);
		String high = k.ai.call(k.ctx, "get_self").text();
		check(high.contains("danger_rising: danger=high") && high.contains("hostiles:3")
			&& !high.contains("low_light:"), "遇到怪群應直接告警：" + high);
		k.cmd("kill @e[type=minecraft:zombie]");
		System.out.println("[ok] S9.1c 短暫昏暗、持續提醒、補光與怪群告警");
	}

	private void difficultyRetreat(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.difficulty("normal");
		Difficulty difficulty = k.difficulty();
		boolean hard = difficulty == Difficulty.HARD;
		String self = k.ai.call(k.ctx, "get_self").text();
		check(self.contains("difficulty=" + difficulty.getSerializedName()), "應回報伺服器的難度：" + self);
		k.give("minecraft:bread", 8);
		int mark = mark(k);
		k.cmd("summon minecraft:skeleton " + TestKit.pos(c.east(10)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		k.onServer(s -> {
			var player = k.me(s);
			player.setHealth(10);
			player.getFoodData().setFoodLevel(12);
			player.getFoodData().setSaturation(0);
		});
		k.ctx.waitTicks(24);
		String started = since(k, mark);
		check(started.contains("retreat_start") == hard, "10 HP 遇到射手時只有困難會提早撤退：" + started);
		k.cmd("kill @e[type=minecraft:skeleton]");
		if (hard) {
			k.ctx.waitTicks(20);
			check(!since(k, mark).contains("\"action\":\"retreat_recover\""), "射手消失後仍應留出兩秒安全時間：" + since(k, mark));
			check(waitLog(k, mark, "\"action\":\"retreat_recover\"", 50), "安全後應進入回血：" + since(k, mark));
			check(waitFor(k, 20 * 15, () -> !anyReflex(k)), "吃東西回血後應結束撤退：" + since(k, mark));
		}
		k.difficulty("easy");
		System.out.println("[ok] 難度 " + difficulty.getSerializedName() + " 的撤退門檻與回血");
	}

	private void hardHuntWithdraws(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		int mark = mark(k);
		k.cmd("summon minecraft:zombie " + TestKit.pos(c.east(3)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.ctx.waitTicks(5);
		var result = k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 30));
		String log = since(k, mark);
		check(result.text().contains("withdrew from an unsafe fight") && log.contains("hunt_odds")
			&& !log.contains("hunt_attack") && alive(k) && count(k, EntityTypes.ZOMBIE) == 1,
			"困難模式空手狩獵應先安全脫身：" + result.text() + "\n" + log);
		System.out.println("[ok] 困難模式的 hunt 會從勝算不足的戰鬥脫身");
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
		if (k.difficulty() == Difficulty.HARD) {
			boolean escaped = waitFor(k, 20 * 25, () -> nearestGroupMob(k) > 16 && !anyReflex(k));
			String log = since(k, mark);
			check(escaped && alive(k) && log.contains("disengage_outmatched") && !log.contains("self_defense_attack"),
				"困難模式空手對殭屍應拉開距離：" + log);
			System.out.println("[ok] 困難模式空手甩掉單隻殭屍");
			return;
		}
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
		k.difficulty("normal");
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
		k.difficulty("easy");
		System.out.println("[ok] R1 盾牌擋箭(耐久 -" + shieldDamage + ")、自動備戰、打死骷髏");
	}

	/** 苦力怕：拿石劍打了就退(出手前衝刺擊退)，在牠炸之前打死，自己沒被炸到。 */
	private void creeperHitAndBack(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:stone_sword", 1);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:creeper ~5 ~ ~ {PersistenceRequired:1b}");
		boolean gone = waitFor(k, 20 * 30, () -> count(k, EntityTypes.CREEPER) == 0);
		check(waitLog(k, mark, "fight_summary", 40), "苦力怕交戰結束應留下統計：" + since(k, mark));
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
		k.difficulty("normal");
		boolean hard = k.difficulty() == Difficulty.HARD;
		for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword")) {
			k.give("minecraft:" + item, 1);
		}
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:zombie ~4 ~ ~ {PersistenceRequired:1b}");
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~-12 ~ ~ {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		boolean settled = waitFor(k, 20 * 60, () -> (groupCount(k) == 0 || hard && nearestGroupMob(k) > 16) && !anyReflex(k));
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		k.difficulty("easy");
		check(settled, "全副武裝應該打完混戰或在困難模式安全脫身：" + log);
		check(alive(k) && hp >= 8, "混戰後血量應該 ≥ 8(剩 " + hp + ")：" + log);
		if (hard) {
			System.out.println("[ok] 困難混戰：剩怪=" + groupCount(k) + " hp=" + hp + " | " + summaries(log));
			return;
		}
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
		k.difficulty("normal");
		boolean hard = k.difficulty() == Difficulty.HARD;
		for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword")) {
			k.give("minecraft:" + item, 1);
		}
		int mark = mark(k);
		summonGroup(k);
		boolean settled = waitFor(k, 20 * 60, () -> (groupCount(k) == 0 || nearestGroupMob(k) > 16) && !anyReflex(k));
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		k.difficulty("easy");
		System.out.println("[data] A1 全副武裝對一群：剩 " + groupCount(k) + " 隻 hp=" + hp + " | " + odds(log) + summaries(log));
		check(alive(k), "全副武裝對一群不應該死：" + summaries(log));
		check(settled, "60 秒內應該打完或甩掉整群：" + summaries(log));
		check(hp >= 6, "對一群打完血量應該 ≥ 6(剩 " + hp + ")：" + summaries(log));
		if (hard) {
			System.out.println("[ok] 困難模式鐵裝對怪群：剩怪=" + groupCount(k) + " hp=" + hp);
			return;
		}
		// 鐵甲 + 盾 + 石劍：勝算夠(兩隻骷髏差 90 度，盾牌對著目標那隻)，應該打而不是跑。
		// 自衛只處理衝著自己來的；沒靠近的(16 格外、沒打過自己)不追，所以不要求全部打死
		int kills = TestKit.sumFightMetric(log, "kills=");
		String left = "skeleton=" + count(k, EntityTypes.SKELETON) + " zombie=" + count(k, EntityTypes.ZOMBIE);
		check(odds(log).isEmpty(), "全副武裝不應該決定脫身：" + odds(log) + " | " + summaries(log));
		check(kills >= 2, "全副武裝應該打死衝過來的(打死 " + kills + " 隻，剩 " + left + ")：" + summaries(log));
		check(groupCount(k) == 0 || nearestGroupMob(k) > 8, "結束時不應該還有怪貼在身邊(剩 " + left + ")");
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

	private enum ShooterBehavior { STILL, PURSUE, MOVE_INTO_ROUTE }

	/** 在回家途中遇到射手，實測脫身後接回同一個 goto，量測回程與重啟自衛。 */
	private void resumeHomeAfterEscaping(TestKit k, BlockPos c, ShooterBehavior behavior) throws Exception {
		reset(k, c);
		k.ctx.runOnClient(mc -> k.agent().danger.clear());
		k.difficulty("normal");
		k.give("minecraft:bread", 8);
		BlockPos home = c.offset(0, 0, 42);
		BlockPos west = c.offset(-9, 0, 13);
		BlockPos east = c.offset(8, 0, 14);
		int mark = mark(k);
		var go = k.ai.call(k.ctx, "goto", args("x", home.getX(), "y", home.getY(), "z", home.getZ(), "wait_sec", 0));
		check(go.ok(), "回家 goto 失敗：" + go.text());
		var originalTask = k.agent().tasks.current();
		check(k.waitUntil(80, mc -> mc.player.blockPosition().getZ() >= c.getZ() + 3), "應先開始走回家");
		for (BlockPos archer : List.of(west, east)) {
			k.cmd("summon minecraft:skeleton " + TestKit.pos(archer) + " {NoAI:1b,PersistenceRequired:1b}");
		}
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		k.onServer(server -> server.overworld().getEntities(EntityTypes.SKELETON, mob -> mob.isAlive()).forEach(mob -> {
			mob.setAggressive(true);
			mob.setNoAi(behavior != ShooterBehavior.PURSUE);
		}));
		check(waitLog(k, mark, "self_defense_start", 100), "應在回家途中進入自衛：" + since(k, mark));
		check(waitFor(k, 20 * 20, () -> since(k, mark).contains("self_defense_end")),
			"應有脫身結算：" + since(k, mark));
		BlockPos escapedAt = k.myPos();
		check(k.agent().tasks.current() == originalTask, "應接回原本的 goto：" + since(k, mark));
		if (behavior == ShooterBehavior.MOVE_INTO_ROUTE) {
			check(k.waitUntil(100, mc -> {
				var executor = Bari.b().getPathingBehavior().getCurrent();
				return executor != null && executor.getPath().length() - executor.getPosition() > 18;
			}), "應先算出恢復後的路徑");
			BlockPos acrossRoute = k.ctx.computeOnClient(mc -> {
				var executor = Bari.b().getPathingBehavior().getCurrent();
				return executor.getPath().positions().get(executor.getPosition() + 16);
			});
			k.cmd("tp @e[type=minecraft:skeleton,limit=1,sort=nearest] " + TestKit.pos(acrossRoute));
		}
		double closestToEncounter = Double.MAX_VALUE;
		double closestToLiveShooter = Double.MAX_VALUE;
		for (int t = 0; t < 20 * 30 && k.agent().tasks.hasActiveTask(); t++) {
			BlockPos at = k.myPos();
			closestToEncounter = Math.min(closestToEncounter, Math.sqrt(Math.min(at.distSqr(west), at.distSqr(east))));
			closestToLiveShooter = Math.min(closestToLiveShooter, nearestGroupMob(k));
			k.ctx.waitTick();
		}
		String log = since(k, mark);
		int starts = log.split("\"action\":\"self_defense_start\"", -1).length - 1;
		boolean arrived = k.myPos().distManhattan(home) <= 3;
		int replans = log.split("\"action\":\"goto_replan\"", -1).length - 1;
		System.out.println("[data] 脫身後回家 behavior=" + behavior + " escaped=" + escapedAt.toShortString() + " end="
			+ k.myPos().toShortString() + " arrived=" + arrived + " starts=" + starts + " replans=" + replans
			+ " closestToEncounter=" + closestToEncounter + " closestToLiveShooter=" + closestToLiveShooter
			+ " | " + summaries(log));
		check(alive(k) && arrived, "應保有原目標並成功回家：" + log);
		if (behavior == ShooterBehavior.STILL) {
			check(starts == 1 && replans == 0, "固定射手情境應只自衛一次並沿原定避讓路徑回家：" + log);
		} else if (behavior == ShooterBehavior.MOVE_INTO_ROUTE) {
			check(starts == 1 && replans > 0 && replans <= 2, "射手移到原路時應有限重算並避免再觸發自衛：" + log);
			check(closestToLiveShooter >= 8, "重算後應遠離切入路線的射手，最近 " + closestToLiveShooter + " 格");
		}
		k.ai.call(k.ctx, "stop");
		k.ctx.runOnClient(mc -> k.agent().danger.clear());
		k.difficulty("easy");
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

	/**
	 * C：被遠程怪包圍(3 隻骷髏在 12 格外、每隔 120 度一隻，再加 2 隻殭屍從 5 格外過來)，空地沒有掩體，
	 * normal 難度、背包有麵包、滿血。盾牌只擋得了一個方向，也找不到三隻都看不到的位置。
	 * 空手：要跑出射程、甩掉整群；全副武裝：打得贏就打，打不贏也要脫身。兩種都不能死(死了又重生也算死)。
	 */
	private void surroundedOnAllSides(TestKit k, BlockPos c, boolean geared) throws Exception {
		reset(k, c);
		String label = geared ? "C2 全副武裝被遠程包圍" : "C1 空手被遠程包圍";
		k.difficulty("normal");
		k.give("minecraft:bread", 8);
		if (geared) {
			for (String item : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "stone_sword")) {
				k.give("minecraft:" + item, 1);
			}
		}
		int mark = mark(k);
		for (String o : List.of("~ ~ ~-12", "~10 ~ ~6", "~-10 ~ ~6")) {
			k.cmd("execute as @p at @s run summon minecraft:skeleton " + o + " {PersistenceRequired:1b}");
		}
		k.cmd("execute as @p at @s run summon minecraft:zombie ~5 ~ ~ {PersistenceRequired:1b}");
		k.cmd("execute as @p at @s run summon minecraft:zombie ~-5 ~ ~ {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		long t0 = k.agent().tick();
		float[] lowest = {20};
		boolean settled = waitFor(k, 20 * 60, () -> {
			lowest[0] = Math.min(lowest[0], k.ctx.computeOnClient(mc -> mc.player.getHealth()));
			return (groupCount(k) == 0 || nearestGroupMob(k) > 16) && !anyReflex(k);
		});
		long secs = (k.agent().tick() - t0) / 20;
		String log = since(k, mark);
		float hp = k.ctx.computeOnClient(mc -> mc.player.getHealth());
		k.difficulty("easy");
		int starts = log.split("self_defense_start", -1).length - 1;
		int retreats = log.split("retreat_start", -1).length - 1;
		int kills = TestKit.sumFightMetric(log, "kills=");
		String left = "skeleton=" + count(k, EntityTypes.SKELETON) + " zombie=" + count(k, EntityTypes.ZOMBIE);
		System.out.println("[data] " + label + "：" + secs + " 秒、打死 " + kills + " 隻、剩 " + left + "、最低血量 " + lowest[0] + "、結束血量 " + hp
			+ "、自衛 " + starts + " 次、撤退 " + retreats + " 次、最近的怪 " + Math.round(nearestGroupMob(k)) + " 格 | " + odds(log) + summaries(log));
		check(alive(k), label + "不應該死：" + summaries(log));
		check(settled, label + "：60 秒內應該打完或甩掉整群(剩 " + left + ")：" + summaries(log));
		check(groupCount(k) == 0 || nearestGroupMob(k) > 8, label + "：結束時不應該還有怪貼在身邊(剩 " + left + ")");
		if (!geared) {
			check(!log.contains("skeleton：charge"), "空手不應該衝骷髏：" + summaries(log));
			check(starts <= 3, "空手被圍觸發自衛 " + starts + " 次，應該一次甩掉(≤ 3 次)：" + summaries(log));
		}
		System.out.println("[ok] " + label + "：最低血量 " + lowest[0] + "、結束 " + hp + "、打死 " + kills + " 隻");
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

	/**
	 * 局部地形 1：4 格內有怪但隔一道牆看不到視線，低血撤退必須先拉開距離再進 RECOVER，不可每 2 tick 在 RUN/RECOVER 之間震盪。
	 */
	private void retreatBehindWallDoesNotOscillate(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:bread", 8);
		// 在玩家東側 2 格蓋一道 3×3 石牆，牆後 1 格放不動的殭屍(距離 3 格、視線被牆完全擋住)
		k.cmd("fill " + (c.getX() + 2) + " " + c.getY() + " " + (c.getZ() - 1) + " "
			+ (c.getX() + 2) + " " + (c.getY() + 2) + " " + (c.getZ() + 1) + " minecraft:stone");
		k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(3, 0, 0)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.ctx.waitTicks(5);
		int mark = mark(k);
		k.agent().events.drainAll();
		lowHealth(k);
		String ev = k.eventsUntil("reflex=retreat", 40);
		String log = since(k, mark);
		k.cmd("fill " + (c.getX() + 2) + " " + c.getY() + " " + (c.getZ() - 1) + " "
			+ (c.getX() + 2) + " " + (c.getY() + 2) + " " + (c.getZ() + 1) + " minecraft:air");
		int recoverLogs = log.split("retreat_recover", -1).length - 1;
		check(ev.contains("result=recovered"), "隔牆低血撤退應該拉開後回血結束：" + ev + "\n" + log);
		check(recoverLogs <= 2, "不該在 RUN/RECOVER 間震盪(retreat_recover=" + recoverLogs + ")：" + log);
		check(k.ctx.computeOnClient(mc -> mc.player.getHealth()) > 10, "撤退結束時血量應該 > 10");
		System.out.println("[ok] 地形撤退：隔牆 3 格低血撤退無震盪(retreat_recover=" + recoverLogs + ")");
	}

	/**
	 * 局部地形 2：高台上的骷髏往下射、旁邊放一個看得到但四周封死走不進去的假掩體格；空手脫身不能卡在假掩體前挨射，必須退回逃跑脫身。
	 */
	private void elevatedArcherAndUnreachableCover(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.difficulty("normal");
		k.give("minecraft:bread", 8);
		// 東側 10 格蓋 3 格高台放骷髏
		k.cmd("fill " + (c.getX() + 9) + " " + c.getY() + " " + (c.getZ() - 1) + " "
			+ (c.getX() + 11) + " " + (c.getY() + 2) + " " + (c.getZ() + 1) + " minecraft:stone");
		// 西側 4 格蓋一個四周 3 格高封閉的 1×1 空心石框(內部符合 findCover 的隱藏條件但完全走不進去)
		k.cmd("fill " + (c.getX() - 5) + " " + c.getY() + " " + (c.getZ() - 1) + " "
			+ (c.getX() - 3) + " " + (c.getY() + 2) + " " + (c.getZ() + 1) + " minecraft:stone");
		k.cmd("fill " + (c.getX() - 4) + " " + c.getY() + " " + c.getZ() + " "
			+ (c.getX() - 4) + " " + (c.getY() + 1) + " " + c.getZ() + " minecraft:air");
		int mark = mark(k);
		k.cmd("summon minecraft:skeleton " + TestKit.pos(c.offset(10, 3, 0)) + " {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		boolean escaped = waitFor(k, 20 * 35, () -> nearestGroupMob(k) > 16 && !anyReflex(k));
		String log = since(k, mark);
		double nearest = nearestGroupMob(k);
		k.difficulty("easy");
		k.cmd("fill " + (c.getX() - 6) + " " + c.getY() + " " + (c.getZ() - 2) + " "
			+ (c.getX() + 12) + " " + (c.getY() + 4) + " " + (c.getZ() + 2) + " minecraft:air");
		check(alive(k), "高台骷髏＋不可達掩體脫身不該死亡：" + summaries(log));
		check(escaped, "應略過不可達掩體並跑出高台骷髏射程(目前最近 " + Math.round(nearest) + " 格)：" + summaries(log));
		System.out.println("[ok] 地形高台＋不可達掩體：成功脫身(距離 " + Math.round(nearest) + " 格)");
	}


	/** 直線較近的西側掩體需要承受更多兩側火力；較遠的南側掩體沿路較早避開一位射手。 */
	private void retreatPrefersLessExposedRoute(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:bread", 8);
		int[][] walls = {{0, -4, 2, -4}, {-3, -1, -3, 0}, {1, 4, 1, 6}};
		for (int[] wall : walls) {
			k.cmd("fill " + TestKit.pos(c.offset(wall[0], 0, wall[1])) + " "
				+ TestKit.pos(c.offset(wall[2], 2, wall[3])) + " minecraft:stone");
		}
		try {
			k.cmd("summon minecraft:skeleton " + TestKit.pos(c.offset(9, 0, -4)) + " {NoAI:1b,PersistenceRequired:1b}");
			k.cmd("summon minecraft:skeleton " + TestKit.pos(c.offset(9, 0, 5)) + " {NoAI:1b,PersistenceRequired:1b}");
			k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
			check(k.waitUntil(60, mc -> mc.level.getEntitiesOfClass(Skeleton.class,
				mc.player.getBoundingBox().inflate(16), mc.player::hasLineOfSight).size() == 2), "起點應同時暴露於兩位射手");
			BlockPos cover = k.ctx.computeOnClient(mc -> new CoverRoute().choose(mc,
				mc.level.getEntitiesOfClass(Skeleton.class,
					mc.player.getBoundingBox().inflate(16)), k.agent().tick()));
			check(c.offset(-2, 0, 6).equals(cover), "應優先選沿路曝露較少的南側掩體，選到 " + cover);
			long started = k.agent().tick();
			lowHealth(k);
			check(k.waitUntil(20 * 8, mc -> mc.player.blockPosition().getZ() - c.getZ() >= 4
				&& mc.level.getEntitiesOfClass(Skeleton.class,
					mc.player.getBoundingBox().inflate(16), mc.player::hasLineOfSight).isEmpty()),
				"低血撤退應沿南側切斷兩位射手的視線");
			System.out.println("[ok] 兩側射手：撤往沿路曝露較少的掩體，切斷視線耗時 " + (k.agent().tick() - started) + " tick");
		} finally {
			for (int[] wall : walls) {
				k.cmd("fill " + TestKit.pos(c.offset(wall[0], 0, wall[1])) + " "
					+ TestKit.pos(c.offset(wall[2], 2, wall[3])) + " minecraft:air");
			}
		}
	}

	/** R17、R19：同一場對射要能在箭到前舉盾，也要能還擊。 */
	private void bowAndShieldDuel(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.difficulty("normal");
		giveArmor(k, k.difficulty() == Difficulty.HARD ? "diamond" : "iron");
		k.give("minecraft:shield", 1);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 24);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~12 ~ ~ {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		check(k.waitUntil(100, mc -> mc.player.getOffhandItem().is(Items.SHIELD) && mc.player.getUseItem().is(Items.BOW)),
			"對射開始時應備妥弓盾：" + since(k, mark));
		// 骷髏自然射擊可能全數偏離，額外送一支飛向角色且足以扣盾耐久的箭。
		k.onServer(server -> {
			ServerPlayer player = k.me(server);
			var level = player.level();
			var archer = level.getEntities(EntityTypes.SKELETON, mob -> mob.isAlive()).getFirst();
			var arrow = EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND);
			check(arrow != null, "應能生成朝玩家飛來的箭");
			arrow.setOwner(archer);
			arrow.setBaseDamage(6);
			arrow.setPos(player.getX() + 10, player.getY() + 1.4, player.getZ());
			arrow.setDeltaMovement(-1.25, 0.15, 0);
			level.addFreshEntity(arrow);
		});
		check(waitFor(k, 20 * 45, () -> count(k, EntityTypes.SKELETON) == 0), "弓盾對射應能擊殺骷髏：" + since(k, mark));
		check(waitLog(k, mark, "fight_summary", 40), "對射應留下統計：" + since(k, mark));
		String log = since(k, mark);
		String summary = summaryOf(log, "skeleton");
		int shots = log.split("\"action\":\"self_defense_shoot\"", -1).length - 1;
		int arrows = k.server.computeOnServer(s -> k.me(s).getInventory().countItem(Items.ARROW));
		check(alive(k) && log.contains("shield_vs_bow") && log.contains("bow_duel"), "應交替射擊與舉盾：" + log);
		check(shots > 0 && shots == 24 - arrows && summary.contains(" shots=" + shots + " "),
			"射擊紀錄、伺服器箭數和統計應一致：剩箭=" + arrows + " " + summary);
		check(summary.matches(".*shield_blocks=[1-9][0-9]*.*"), "對射時盾應擋到至少一箭：" + summary);
		k.difficulty("easy");
		System.out.println("[ok] R17 R19 弓盾對射 " + summary);
	}

	private void shootCreeperBeforeItSwells(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 16);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:creeper ~10 ~ ~ {NoAI:1b,PersistenceRequired:1b}");
		k.ai.call(k.ctx, "hunt", args("target", "creeper", "wait_sec", 25));
		String log = since(k, mark);
		String summary = summaryOf(log, "creeper");
		check(count(k, EntityTypes.CREEPER) == 0 && summary.contains("result=killed"), "應射倒苦力怕：" + log);
		check(summary.contains("bow_creeper") && summary.contains("swings=0") && summary.matches(".*shots=[1-9][0-9]*.*"),
			"苦力怕在遠處時應用弓擊倒：" + summary);
		int fired = TestKit.sumFightMetric(log, "shots=");
		check(fired > 0 && TestKit.sumFightMetric(log, "arrow_hits=") == fired, "每支命中苦力怕的箭都應有伺服器傷害證據：" + log);
		var hitIds = Pattern.compile("hunt_shot_result[^\n]*result=hit shot=([0-9]+)").matcher(log).results()
			.map(m -> m.group(1)).toList();
		check(hitIds.size() == fired && hitIds.stream().distinct().count() == fired, "每支箭應只計一次命中：" + log);
		System.out.println("[ok] R19 遠射苦力怕 " + summary);
	}

	private void finishDrawAcrossDistanceThreshold(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 16);
		int mark = mark(k);
		k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(10, 0, 0)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 0));
		check(k.waitUntil(100, mc -> mc.player.getUseItem().is(Items.BOW) && mc.player.getTicksUsingItem() >= 8),
			"應在殭屍進入門檻前先蓄力：" + since(k, mark));
		k.cmd("tp @e[type=minecraft:zombie] " + TestKit.pos(c.offset(5, 0, 0)));
		check(waitLog(k, mark, "hunt_shoot", 30), "移進六格門檻後仍應放完這箭：" + since(k, mark));
		check(waitFor(k, 20, () -> k.server.computeOnServer(s -> k.me(s).getInventory().countItem(Items.ARROW)) == 15),
			"蓄力越過門檻後應真正耗用一支箭");
		check(!since(k, mark).contains("hunt_attack"), "射擊前不應換成近戰：" + since(k, mark));
		System.out.println("[ok] R16 目標跨進六格仍射完當前這箭");
	}

	private void logDrawCancelWhenTargetCloses(TestKit k, BlockPos c) throws Exception {
		int mark = startDrawingAtZombie(k, c, false);
		k.cmd("tp @e[type=minecraft:zombie] " + TestKit.pos(c.offset(2, 0, 0)));
		check(waitLog(k, mark, "reason=target_too_close", 40), "目標貼近應留下收弓原因：" + since(k, mark));
		check(!since(k, mark).contains("hunt_shoot"), "貼近而收弓時不應計一次射擊：" + since(k, mark));
		k.ai.call(k.ctx, "stop");
		System.out.println("[ok] 目標貼近時記錄 target_too_close");
	}

	private void logDrawCancelWhenTrajectoryCloses(TestKit k, BlockPos c) throws Exception {
		int mark = startDrawingAtZombie(k, c, false);
		BlockPos roof = c.offset(1, 1, 0);
		try {
			k.cmd("setblock " + TestKit.pos(roof) + " minecraft:stone_slab[type=bottom]");
			check(waitLog(k, mark, "reason=trajectory_blocked", 40), "彈道受阻應留下收弓原因：" + since(k, mark));
			k.ai.call(k.ctx, "stop");
			System.out.println("[ok] 拉弓途中彈道受阻時記錄 trajectory_blocked");
		} finally {
			k.setBlock(roof, Blocks.AIR.defaultBlockState());
		}
	}

	private void logDrawCancelForShield(TestKit k, BlockPos c) throws Exception {
		int mark = startDrawingAtZombie(k, c, true);
		k.cmd("execute at @p run summon minecraft:arrow ~5 ~1 ~ {Motion:[-1.5d,0.0d,0.0d]}");
		check(waitLog(k, mark, "reason=shield", 40), "來箭時應記錄為舉盾而收弓：" + since(k, mark));
		k.ai.call(k.ctx, "stop");
		System.out.println("[ok] 擋來箭時記錄 shield");
	}

	private void cancelDrawAfterClientStateClears(TestKit k, BlockPos c) throws Exception {
		int mark = startDrawingAtZombie(k, c, false);
		check(k.server.computeOnServer(s -> k.me(s).getUseItem().is(Items.BOW)), "伺服器應已收到拉弓");
		k.ctx.runOnClient(mc -> {
			// 模擬客戶端先被上一筆使用狀態清掉，伺服器仍在拉弓。
			mc.player.stopUsingItem();
			k.agent().tasks.cancel("test stopped hunt");
		});
		k.ctx.waitTicks(10);
		check(k.server.computeOnServer(s -> !k.me(s).isUsingItem() && k.me(s).getInventory().countItem(Items.ARROW) == 16),
			"收弓應清掉伺服器的拉弓，且維持箭數：" + since(k, mark));
		check(since(k, mark).contains("hunt_draw_cancel"), "客戶端狀態消失後仍應記錄收弓");
		System.out.println("[ok] 客戶端使用狀態消失時仍能取消伺服器拉弓");
	}

	private void logDrawCancelForRetarget(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		giveIronArmor(k);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 16);
		int mark = mark(k);
		k.onServer(server -> {
			spawnStationaryArcher(k.me(server), c.offset(12, 0, 0));
		});
		check(k.waitUntil(100, mc -> mc.player.getUseItem().is(Items.BOW) && mc.player.getTicksUsingItem() >= 5),
			"應先對遠處的骷髏拉弓：" + since(k, mark));
		k.onServer(server -> {
			spawnStationaryArcher(k.me(server), c.offset(0, 0, 2));
		});
		check(waitLog(k, mark, "reason=target_changed", 30), "更危險的射手貼近時應留下換目標收弓紀錄：" + since(k, mark));
		System.out.println("[ok] 更換敵人時記錄 target_changed");
	}

	private static void spawnStationaryArcher(ServerPlayer player, BlockPos pos) {
		var level = player.level();
		Skeleton archer = EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND);
		check(archer != null, "應能生成骷髏射手");
		archer.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		archer.setNoAi(true);
		archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		archer.setAggressive(true);
		level.addFreshEntity(archer);
	}

	private int startDrawingAtZombie(TestKit k, BlockPos c, boolean shield) throws Exception {
		reset(k, c);
		if (shield) k.give("minecraft:shield", 1);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 16);
		int mark = mark(k);
		k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(10, 0, 0)) + " {NoAI:1b,PersistenceRequired:1b}");
		check(k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 0)).ok(), "應開始狩獵");
		check(k.waitUntil(100, mc -> mc.player.getUseItem().is(Items.BOW) && mc.player.getTicksUsingItem() >= 5),
			"應先拉弓：" + since(k, mark));
		return mark;
	}

	private void logArrowAfterLaunch(TestKit k, BlockPos c, boolean discard) throws Exception {
		reset(k, c);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 1);
		int mark = mark(k);
		BlockPos wall = c.offset(23, 0, 0);
		k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(29, 0, 0)) + " {NoAI:1b,PersistenceRequired:1b}");
		check(k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 0)).ok(), "應開始狩獵");
		try {
			boolean placed = false;
			for (int i = 0; i < 100 && !placed; i++) {
				placed = k.server.computeOnServer(server -> {
					var level = server.overworld();
					var arrows = level.getEntities(EntityTypes.ARROW, a -> a.getOwner() == k.me(server) && a.getX() < wall.getX());
					if (arrows.isEmpty()) return false;
					if (discard) {
						arrows.forEach(Entity::discard);
					} else {
						level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
						level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
					}
					return true;
				});
				if (!placed) k.ctx.waitTick();
			}
			check(placed, "應在箭命中前介入測試：" + since(k, mark));
			String result = discard ? "unconfirmed" : "embedded";
			check(waitLog(k, mark, "result=" + result, 80), "應關聯相符的射擊結果：" + since(k, mark));
			check(!since(k, mark).contains("result=hit"), "沒有伺服器傷害證據時不能計作命中：" + since(k, mark));
			k.ai.call(k.ctx, "stop");
			System.out.println("[ok] 箭飛出後的結果 " + result);
		} finally {
			k.setBlock(wall, Blocks.AIR.defaultBlockState());
			k.setBlock(wall.above(), Blocks.AIR.defaultBlockState());
		}
	}

	private void turnToMeleeWhenShotIsBlocked(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 16);
		k.give("minecraft:stone_sword", 1);
		int mark = mark(k);
		BlockPos roof = c.offset(1, 1, 0);
		k.cmd("setblock " + TestKit.pos(roof) + " minecraft:stone_slab[type=bottom]");
		try {
			k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(10, 0, 0)) + " {NoAI:1b,PersistenceRequired:1b}");
			k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 25));
			String log = since(k, mark);
			int firstTactic = log.indexOf("hunt_tactic");
			check(count(k, EntityTypes.ZOMBIE) == 0 && firstTactic >= 0
				&& log.substring(firstTactic, log.indexOf('\n', firstTactic)).contains("：melee"),
				"低簷卡住彈道時應先改近戰；繞出低簷後有射線可再用弓：" + log);
			System.out.println("[ok] R19 低簷擋住射線時改用近戰移動 " + summaryOf(log, "zombie"));
		} finally {
			k.setBlock(roof, Blocks.AIR.defaultBlockState());
		}
	}

	private void doNotShootEnderman(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:bow", 1);
		k.give("minecraft:arrow", 16);
		k.give("minecraft:iron_sword", 1);
		int mark = mark(k);
		k.cmd("summon minecraft:enderman " + TestKit.pos(c.offset(9, 0, 0)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.ai.call(k.ctx, "hunt", args("target", "enderman", "wait_sec", 25));
		String log = since(k, mark);
		check(log.contains("hunt_attack") && !log.contains("bow_first") && !log.contains("hunt_shoot"),
			"對終界使者應直接用近戰：" + log);
		System.out.println("[ok] R18 終界使者使用近戰 " + summaryOf(log, "enderman"));
	}

	private void useLoadedCrossbow(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.cmd("tp @p " + TestKit.pos(c) + " 0 0");
		k.ctx.waitTicks(4);
		k.give("minecraft:crossbow[minecraft:charged_projectiles=[{id:\"minecraft:arrow\",count:1}]]", 1);
		int mark = mark(k);
		k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(10, 0, 0)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 0));
		check(waitLog(k, mark, "hunt_shoot", 100), "只有一把已裝箭的弩時也應能先射擊：" + since(k, mark));
		check(waitFor(k, 20, () -> k.server.computeOnServer(s ->
			k.me(s).getMainHandItem().is(Items.CROSSBOW)
				&& k.me(s).getMainHandItem().get(DataComponents.CHARGED_PROJECTILES).isEmpty())),
			"伺服器收到射擊後弩應變成空弩");
		check(waitLog(k, mark, "result=hit", 40) && since(k, mark).contains("weapon=crossbow"),
			"弩的命中也應連到實際射出的箭：" + since(k, mark));
		check(since(k, mark).split("weapon=crossbow", -1).length == 2, "單支箭的弩只能記一次發射：" + since(k, mark));
		System.out.println("[ok] R18 可射擊已裝單支箭的弩");
	}

	/**
	 * T18(f)：沒弓用劍 hunt 浮在空中的烈焰使者——走到牠底下等牠靠近再砍(不往空中座標走)，
	 * 被火球點燃時附近沒水也不觸發著火反射中斷打獵(S9.1、S9.1b)。
	 */
	private void huntBlazeWithoutBow(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.difficulty("normal");
		k.give("minecraft:diamond_sword", 1);
		k.give("minecraft:iron_chestplate", 1);
		k.give("minecraft:iron_helmet", 1);
		k.give("minecraft:shield", 1);
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:blaze ~6 ~3 ~ {PersistenceRequired:1b}");
		k.ctx.waitTicks(5);
		var r = k.ai.call(k.ctx, "hunt", args("target", "blaze", "wait_sec", 60));
		boolean dead = waitFor(k, 20 * 30, () -> count(k, EntityTypes.BLAZE) == 0);
		String log = since(k, mark);
		check(dead && alive(k), "沒弓也應打死烈焰使者並活著：" + r.text() + "\n" + log);
		check(log.contains("hunt_attack") && !log.contains("on_fire"), "應近戰命中，且著火不觸發反射：" + log);
		k.difficulty("easy");
		System.out.println("[ok] T18(f) 沒弓近戰打死烈焰使者");
	}

	private void respondToPiglinCrossbow(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.difficulty("normal");
		k.cmd("item replace entity @p armor.head with minecraft:golden_helmet");
		k.cmd("item replace entity @p weapon.offhand with minecraft:shield");
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:piglin ~9 ~ ~ {IsImmuneToZombification:1b,PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:piglin] weapon.mainhand with minecraft:crossbow");
		k.ctx.waitTicks(40);
		check(count(k, EntityTypes.PIGLIN) == 1 && !since(k, mark).contains("self_defense_start"),
			"穿金甲遇中立豬布林弩手應不先動手：" + since(k, mark));
		k.cmd("item replace entity @p armor.head with minecraft:iron_helmet");
		check(waitLog(k, mark, "self_defense_start", 20 * 12), "無金甲應能識別豬布林弩手：" + since(k, mark));
		check(waitLog(k, mark, "shield_vs_bow", 20 * 8), "應對豬布林的弩舉盾：" + since(k, mark));
		String log = since(k, mark);
		var start = Pattern.compile("self_defense_start[^\n]*piglin[^\n]*dist=([0-9]+\\.[0-9]+)").matcher(log);
		check(start.find() && Double.parseDouble(start.group(1)) > 6, "應在舊有六格範圍外觸發自衛：" + log);
		k.difficulty("easy");
		System.out.println("[ok] R33 金甲中立、無金甲時在六格外對弩手舉盾");
	}

	private void avoidDeepWaterPursuit(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:stone_sword", 1);
		BlockPos start = c.offset(4, -4, -2);
		BlockPos end = c.offset(11, -1, 2);
		k.cmd("fill " + TestKit.pos(start) + " " + TestKit.pos(end) + " minecraft:water");
		try {
			k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(7, -4, 0)) + " {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}");
			int mark = mark(k);
			var result = k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 8));
			check(result.text().contains("deep water"), "無弓的深水追擊應回報原因：" + result.text() + "\n" + since(k, mark));
			check(!since(k, mark).contains("drowning_start") && k.ctx.computeOnClient(mc -> !mc.player.isInWater()),
				"應保持在岸上：" + since(k, mark));

			k.give("minecraft:bow", 1);
			k.give("minecraft:arrow", 12);
			BlockPos exposed = c.offset(10, -3, 0);
			k.cmd("tp @e[type=minecraft:zombie] " + TestKit.pos(exposed));
			check(k.waitUntil(40, mc -> mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(16),
				e -> e.getType() == EntityTypes.ZOMBIE && e.blockPosition().equals(exposed)).size() == 1),
				"應等到客戶端收到深水目標的新位置");
			k.ai.call(k.ctx, "hunt", args("target", "zombie", "wait_sec", 0));
			check(waitLog(k, mark, "bow_from_shore", 80) && waitLog(k, mark, "hunt_shoot", 60),
				"有可用射線時應從岸上射擊：" + since(k, mark));
			check(!since(k, mark).contains("drowning_start") && k.ctx.computeOnClient(mc -> !mc.player.isInWater()),
				"射擊時應留在岸上：" + since(k, mark));
			k.ai.call(k.ctx, "stop");
			System.out.println("[ok] R20 無弓時不下深水追擊，有弓時從岸上射擊");
		} finally {
			k.cmd("fill " + TestKit.pos(start) + " " + TestKit.pos(end) + " minecraft:stone");
		}
	}

	private void avoidCreeperWhileDisengaging(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.difficulty("normal");
		int mark = mark(k);
		k.cmd("execute as @p at @s run summon minecraft:skeleton ~12 ~ ~ {PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		check(waitLog(k, mark, "back_off", 20 * 8), "空手遇射手應先脫身：" + since(k, mark));
		k.cmd("execute as @p at @s run summon minecraft:creeper ~1 ~ ~ {NoAI:1b,PersistenceRequired:1b}");
		k.onServer(server -> server.overworld().getEntities(EntityTypes.CREEPER, mob -> mob.isAlive()).forEach(mob -> mob.ignite()));
		check(waitLog(k, mark, "creeper_run", 30), "正在脫身也要優先躲即將引爆的苦力怕：" + since(k, mark));
		check(waitFor(k, 80, () -> count(k, EntityTypes.CREEPER) == 0) && alive(k), "應躲過苦力怕爆炸：" + since(k, mark));
		k.difficulty("easy");
		System.out.println("[ok] 脫身時仍優先避開引爆中的苦力怕");
	}

	private void canHuntInShallowWater(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		k.give("minecraft:stone_sword", 1);
		BlockPos start = c.offset(4, -1, -2);
		BlockPos end = c.offset(11, -1, 2);
		k.cmd("fill " + TestKit.pos(start) + " " + TestKit.pos(end) + " minecraft:water");
		try {
			k.cmd("summon minecraft:spider " + TestKit.pos(c.offset(8, -1, 0)) + " {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}");
			int mark = mark(k);
			var result = k.ai.call(k.ctx, "hunt", args("target", "spider", "wait_sec", 20));
			check(count(k, EntityTypes.SPIDER) == 0, "應能對一格深水上的蜘蛛完成近戰：" + result.text() + since(k, mark));
			System.out.println("[ok] R20 一格淺水仍可近戰");
		} finally {
			k.cmd("fill " + TestKit.pos(start) + " " + TestKit.pos(end) + " minecraft:stone");
		}
	}

	private void continueDefenseAfterKill(TestKit k, BlockPos c) throws Exception {
		reset(k, c);
		giveIronArmor(k);
		k.give("minecraft:iron_sword", 1);
		int mark = mark(k);
		k.cmd("summon minecraft:zombie " + TestKit.pos(c.offset(2, 0, 0)) + " {NoAI:1b,Health:6.0f,PersistenceRequired:1b}");
		k.cmd("damage @p 1 minecraft:mob_attack by @e[type=minecraft:zombie,limit=1]");
		check(waitLog(k, mark, "self_defense_start", 60), "應先對殭屍進入自衛：" + since(k, mark));
		k.cmd("summon minecraft:skeleton " + TestKit.pos(c.offset(8, 0, 1)) + " {NoAI:1b,PersistenceRequired:1b}");
		k.cmd("item replace entity @e[type=minecraft:skeleton] weapon.mainhand with minecraft:bow");
		k.onServer(server -> server.overworld().getEntities(EntityTypes.SKELETON, mob -> mob.isAlive())
			.forEach(mob -> mob.setAggressive(true)));
		check(waitFor(k, 20 * 20, () -> count(k, EntityTypes.ZOMBIE) == 0 && count(k, EntityTypes.SKELETON) == 0),
			"應處理已在交戰範圍內的骷髏：" + since(k, mark));
		check(waitLog(k, mark, "fight_summary", 40), "混戰應留下交戰統計：" + since(k, mark));
		String log = since(k, mark);
		check(TestKit.sumFightMetric(log, "kills=") == 2 && summaries(log).contains("targets=zombie,skeleton")
			&& log.contains("self_defense_retarget") && log.split("\"action\":\"self_defense_start\"", -1).length == 2,
			"擊殺後應在同一次自衛內換目標：" + log);
		System.out.println("[ok] R32 混戰換目標維持同一次自衛 " + summaries(log));
	}

	private static void giveIronArmor(TestKit k) {
		giveArmor(k, "iron");
	}

	private static void giveArmor(TestKit k, String material) {
		for (String part : List.of("helmet", "chestplate", "leggings", "boots")) {
			k.give("minecraft:" + material + "_" + part, 1);
		}
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

	/** 上次 reset 時 ActionLog 的行數，{@link #alive} 從這裡找有沒有死過。 */
	private static int resetMark;

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
		try {
			resetMark = mark(k);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** 在測試執行緒上每 10 tick 檢查一次(條件可以查伺服器)。 */
	private static boolean waitFor(TestKit k, int maxTicks, java.util.function.BooleanSupplier cond) {
		for (int i = 0; i < maxTicks; i += 10) {
			if (cond.getAsBoolean()) return true;
			k.ctx.waitTicks(10);
		}
		return cond.getAsBoolean();
	}

	private static String summaries(String log) {
		return String.join(" ; ", TestKit.fightSummaries(log));
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

	/** 從上次 reset 到現在都沒死過：只看當下血量會漏掉「死了又自動重生」(重生後是滿血)。 */
	private static boolean alive(TestKit k) {
		return k.ctx.computeOnClient(mc -> mc.player.isAlive() && mc.player.getHealth() > 0) && !since(k, resetMark).contains("死在 ");
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
