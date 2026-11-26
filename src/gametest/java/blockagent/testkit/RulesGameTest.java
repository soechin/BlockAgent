package blockagent.testkit;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * T1(違規動作全部被擋下+Mixin 最後防線，M5.1、M5.2、M5.4、M1.6)、T1b(聊天不影響規矩)、
 * 以及 Baritone 尋路遇到朋友方塊時不拆牆(原 T11(c))。
 */
@SuppressWarnings("UnstableApiUsage")
public class RulesGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("RulesGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			k.flatArena(c, 16);
			TestKit.Friend ming = k.spawnFriend("Ming", c.offset(2, 0, 0));
			t1(k, c, ming);
			ownership(k, c, ming);
			placeFacing(k, c);
			protectedMine(k, c, ming);
			reviewRegressions(k, c);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void t1(TestKit k, BlockPos c, TestKit.Friend ming) {
		BlockPos planks = c.offset(0, 0, 2);
		ming.place(planks, Blocks.OAK_PLANKS.defaultBlockState());
		BlockPos copper = c.offset(-2, 0, 0);
		ming.place(copper, Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState());
		BlockPos campfire = c.offset(1, 0, -1);
		ming.place(campfire, Blocks.CAMPFIRE.defaultBlockState());
		int[] wolfId = new int[1], villagerId = new int[1];
		k.onServer(s -> {
			var lvl = s.overworld();
			Wolf w = EntityTypes.WOLF.spawn(lvl, c.offset(0, 0, -2), EntitySpawnReason.COMMAND);
			w.tame(ming.player);
			w.setOrderedToSit(true);
			wolfId[0] = w.getId();
			Villager v = EntityTypes.VILLAGER.spawn(lvl, c.offset(-2, 0, -2), EntitySpawnReason.COMMAND);
			v.setNoAi(true);
			villagerId[0] = v.getId();
		});
		k.give("minecraft:stone_pickaxe", 1);
		k.give("minecraft:stone_axe", 1);
		k.give("minecraft:stone_shovel", 1);
		k.give("minecraft:dirt", 1);
		k.ctx.waitTicks(10);
		k.start();

		// 從 AI 端呼叫違規動作
		k.expectError(k.ai.call(k.ctx, "attack", args("target", String.valueOf(ming.id()))), "RULE_PROTECTED_ENTITY", "T1 打玩家");
		k.expectError(k.ai.call(k.ctx, "attack", args("target", String.valueOf(wolfId[0]))), "RULE_PROTECTED_ENTITY", "T1 打寵物");
		k.expectError(k.ai.call(k.ctx, "attack", args("target", String.valueOf(villagerId[0]))), "RULE_PROTECTED_ENTITY", "T1 打村民");
		// 不做交易功能：空手對村民按右鍵會打開交易畫面，所以不管手上拿什麼一律擋下(use_on_entity 不帶 item、mount 都會切空手)
		var trade = k.ai.call(k.ctx, "use_on_entity", args("target", villagerId[0]));
		k.expectError(trade, "RULE_PROTECTED_ENTITY", "T1 空手對村民互動");
		check(trade.hint() != null && trade.hint().contains("trading is not supported"), "T1 應該說明不支援交易：" + trade.text());
		k.expectError(k.ai.call(k.ctx, "mount", args("target", villagerId[0])), "RULE_PROTECTED_ENTITY", "T1 騎村民");
		check(k.ctx.computeOnClient(mc -> mc.gui.screen() == null), "T1 不應該打開交易畫面");
		k.expectError(k.ai.call(k.ctx, "break_block", args("x", planks.getX(), "y", planks.getY(), "z", planks.getZ())),
			"RULE_PROTECTED_BLOCK", "T1 拆別人的方塊");

		var place = k.ai.call(k.ctx, "place_block", args("item", "dirt", "x", planks.getX(), "y", planks.getY(), "z", planks.getZ()));
		k.expectError(place, "RULE_PROTECTED_BLOCK", "T1 放到別人的方塊那格");
		check(place.reason().contains("cannot place") && !place.reason().contains("cannot break"), "T1 放置被擋的訊息應該說放不下去：" + place.text());

		// T1b：朋友叫 AI 拆，結果一樣
		ming.say("拆掉那面牆");
		k.expectError(k.ai.call(k.ctx, "break_block", args("x", planks.getX(), "y", planks.getY(), "z", planks.getZ())),
			"RULE_PROTECTED_BLOCK", "T1b 朋友說了也不能拆");

		// 最後防線(MultiPlayerGameModeMixin)：繞過工具直接呼叫遊戲介面也會被擋
		float hpBefore = ming.health();
		boolean broke = k.ctx.computeOnClient(mc -> mc.gameMode.startDestroyBlock(planks, Direction.UP));
		check(!broke, "T1 最後防線：startDestroyBlock 應該回傳 false");
		int mingId = ming.id();
		k.ctx.runOnClient(mc -> mc.gameMode.attack(mc.player, mc.level.getEntity(mingId)));
		// 直接對村民按右鍵(不經過 use_on_entity)也擋下，不會打開交易畫面
		int vid = villagerId[0];
		boolean traded = k.ctx.computeOnClient(mc -> {
			var v = mc.level.getEntity(vid);
			return mc.gameMode.interact(mc.player, v, new net.minecraft.world.phys.EntityHitResult(v), InteractionHand.MAIN_HAND).consumesAction();
		});
		check(!traded, "T1 最後防線：interact 村民應該回傳 FAIL");
		k.ctx.waitTicks(5);
		check(k.ctx.computeOnClient(mc -> mc.gui.screen() == null), "T1 最後防線：不應該打開交易畫面");
		// 用工具改變別人的方塊(S7.3c)：斧頭去蠟、鏟子熄滅營火
		useWith(k, "stone_axe", copper);
		useWith(k, "stone_shovel", campfire);
		k.ctx.waitTicks(10);

		// 伺服器端確認：什麼都沒變
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(planks).is(Blocks.OAK_PLANKS)), "T1 木板被拆了");
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(copper).is(Blocks.COPPER_BLOCK.waxed().unaffected())), "T1 朋友的銅方塊被去蠟了");
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(campfire).getValue(CampfireBlock.LIT)), "T1 朋友的營火被熄滅了");
		check(ming.health() >= hpBefore, "T1 朋友被打了：" + ming.health());
		float wolfHp = k.server.computeOnServer(s -> ((Wolf) s.overworld().getEntity(wolfId[0])).getHealth());
		float wolfMax = k.server.computeOnServer(s -> ((Wolf) s.overworld().getEntity(wolfId[0])).getMaxHealth());
		check(wolfHp >= wolfMax, "T1 狗被打了");
		System.out.println("[ok] T1 保護規則與 Mixin 最後防線驗證通過");
		k.setBlock(campfire, Blocks.AIR.defaultBlockState());
	}

	/**
	 * 安全與所有權(docs/REVIEW.md C1、H2、H3)：蹲下放方塊不能疊到別人的半磚上、鋤頭不能鋤別人的草徑、
	 * 別人的木板旁邊不能點火、自己放的門上半格也算自己的。
	 */
	private void ownership(TestKit k, BlockPos c, TestKit.Friend ming) {
		BlockPos slab = c.offset(-1, 0, 3), path = c.offset(3, -1, 1), fireFloor = c.offset(1, -1, 2), door = c.offset(-3, 0, -1);
		ming.place(slab, Blocks.OAK_SLAB.defaultBlockState());
		ming.place(path, Blocks.DIRT_PATH.defaultBlockState());
		k.give("minecraft:oak_slab", 1);
		k.give("minecraft:stone_hoe", 1);
		k.give("minecraft:flint_and_steel", 1);
		k.give("minecraft:oak_door", 1);
		k.ctx.waitTicks(5);

		// C1：蹲下拿半磚點別人的半磚(會合成雙層半磚)
		var eq = k.ai.call(k.ctx, "equip", args("item", "oak_slab"));
		check(eq.ok(), "換上半磚失敗：" + eq.text());
		k.ctx.runOnClient(mc -> mc.options.keyShift.setDown(true));
		k.ctx.waitTicks(2);
		boolean sneaking = k.ctx.computeOnClient(mc -> mc.player.isSecondaryUseActive());
		k.ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(slab), Direction.UP, slab, false)));
		k.ctx.runOnClient(mc -> mc.options.keyShift.setDown(false));
		check(sneaking, "C1 測試沒有真的蹲下");
		// H2：鋤頭對別人的草徑、打火石點在別人的木板旁邊
		useWith(k, "stone_hoe", path);
		useWith(k, "flint_and_steel", fireFloor);
		k.ctx.waitTicks(10);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(slab).getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
			== net.minecraft.world.level.block.state.properties.SlabType.BOTTOM), "C1 蹲下放半磚疊到朋友的半磚上了");
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(path).is(Blocks.DIRT_PATH)), "H2 朋友的草徑被鋤成耕地了");
		check(!k.ctx.computeOnClient(mc -> k.agent().guard.isOwned(path)), "H2 朋友的草徑不能記成自己的");
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(fireFloor.above()).isAir()), "H2 朋友的木板旁邊點起火了");

		// H3：自己放的門，上半格也是自己的，可以單獨拆
		var placed = k.ai.call(k.ctx, "place_block", args("item", "oak_door", "x", door.getX(), "y", door.getY(), "z", door.getZ()));
		check(placed.ok(), "H3 門應該放好：" + placed.text());
		k.ctx.waitTicks(5);
		check(k.ctx.computeOnClient(mc -> k.agent().guard.isOwned(door) && k.agent().guard.isOwned(door.above())), "H3 門的兩格都要記成自己的");
		var broke = k.ai.call(k.ctx, "break_block", args("x", door.getX(), "y", door.getY() + 1, "z", door.getZ()));
		String brokeText = broke.text().contains("task_done:") ? broke.text() : broke.text() + "\n" + k.eventsUntil("task_", 20);
		check(broke.ok() && brokeText.contains("task_done: task=break_block"), "H3 應該可以拆自己的門上半格：" + brokeText);
		k.ctx.waitTicks(5);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(door).isAir() && s.overworld().getBlockState(door.above()).isAir()),
			"H3 門應該被拆掉");

		// P3-H1：細雪桶(SolidBucketItem extends BlockItem)不能倒進朋友的煉藥鍋
		BlockPos cauldron = c.offset(-2, 0, -2);
		ming.place(cauldron, Blocks.CAULDRON.defaultBlockState());
		k.give("minecraft:powder_snow_bucket", 1);
		k.ctx.waitTicks(5);
		useWith(k, "powder_snow_bucket", cauldron);
		k.ctx.waitTicks(10);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(cauldron).is(Blocks.CAULDRON)),
			"P3-H1 朋友的煉藥鍋被細雪桶填滿了");

		// P3-H2：對可替換方塊(短草)東面點火，實際火源在草東邊 1 格；若那格距離朋友木板 <= 2 格必須擋下
		BlockPos grass = c.offset(-1, 0, -3);
		BlockPos planks3 = grass.east(3);
		k.setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState());
		k.setBlock(grass.east(), Blocks.AIR.defaultBlockState());
		ming.place(planks3, Blocks.OAK_PLANKS.defaultBlockState());
		k.ctx.waitTicks(5);
		var eqFlint = k.ai.call(k.ctx, "equip", args("item", "flint_and_steel"));
		check(eqFlint.ok(), "換上打火石失敗：" + eqFlint.text());
		k.ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(grass), Direction.EAST, grass, false)));
		k.ctx.waitTicks(10);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(grass.east()).isAir()),
			"P3-H2 點可替換草的東面繞過了 2 格生火保護");

		// Fail-closed：對未滿的堆肥桶(原版拿打火石點會回 PASS)不蹲下點火，不能繞過生火保護
		k.setBlock(grass, Blocks.COMPOSTER.defaultBlockState());
		k.ctx.waitTicks(5);
		k.ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(grass), Direction.EAST, grass, false)));
		k.ctx.waitTicks(10);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(grass.east()).isAir()),
			"堆肥桶不蹲下點火不該繞過 2 格生火保護");

		// P3-M4：用蜂巢幫自己放的銅方塊上蠟後，所有權必須保留，之後還能自己拆掉
		BlockPos ownCopper = c.offset(-3, 0, -2);
		k.give("minecraft:copper_block", 1);
		k.give("minecraft:honeycomb", 1);
		k.ctx.waitTicks(5);
		var placedCopper = k.ai.call(k.ctx, "place_block", args("item", "copper_block", "x", ownCopper.getX(), "y", ownCopper.getY(), "z", ownCopper.getZ()));
		check(placedCopper.ok(), "P3-M4 銅方塊應該放好：" + placedCopper.text());
		k.ctx.waitTicks(5);
		useWith(k, "honeycomb", ownCopper);
		k.ctx.waitTicks(10);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(ownCopper).is(Blocks.COPPER_BLOCK.waxed().unaffected())),
			"P3-M4 自己的銅方塊應該上蠟成功");
		check(k.ctx.computeOnClient(mc -> k.agent().guard.isOwned(ownCopper)),
			"P3-M4 自己的銅方塊上蠟後不該失去所有權");

		System.out.println("[ok] C1/H2/H3/P3 蹲下放置、鋤頭、細雪桶煉藥鍋、草面點火、上蠟保所有權與門雙格皆驗證通過");
		k.setBlock(slab, Blocks.AIR.defaultBlockState());
		k.setBlock(path, Blocks.GRASS_BLOCK.defaultBlockState());
		k.setBlock(cauldron, Blocks.AIR.defaultBlockState());
		k.setBlock(grass, Blocks.AIR.defaultBlockState());
		k.setBlock(planks3, Blocks.AIR.defaultBlockState());
		k.setBlock(ownCopper, Blocks.AIR.defaultBlockState());
	}

	/**
	 * A6：place_block 的 facing。樓梯朝視線方向、熔爐背對玩家、原木看點哪一面，規則各不相同，
	 * 以伺服器上的方塊狀態為準驗證；沒有朝向的方塊、放不出那個方向時回 INVALID_ARGUMENT。
	 */
	private void placeFacing(TestKit k, BlockPos c) {
		k.give("minecraft:oak_stairs", 1);
		k.give("minecraft:furnace", 1);
		k.give("minecraft:oak_log", 1);
		k.give("minecraft:hopper", 1);
		BlockPos stairs = c.offset(2, 0, -2), furnace = c.offset(2, 0, 2), log = c.offset(-2, 0, 2), stone = log.west();
		// 漏斗朝西只能點西邊鄰格的東面；那格是工作台時不能點(會打開介面，P2-1)，只剩地板給得出 down
		BlockPos hopper = c.offset(1, 0, -3), table = hopper.west();
		k.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState());
		k.ctx.waitTicks(5);
		var h = k.ai.call(k.ctx, "place_block", args("item", "hopper", "x", hopper.getX(), "y", hopper.getY(), "z", hopper.getZ(), "facing", "west"));
		k.expectError(h, "INVALID_ARGUMENT", "A6 只有工作台能給出的朝向");
		check(h.text().contains("possible=down"), "A6 不該點工作台，應該只剩 down：" + h.text());
		check(k.ctx.computeOnClient(mc -> mc.gui.screen() == null), "A6 不應該打開工作台介面");
		k.setBlock(table, Blocks.AIR.defaultBlockState());

		var r = k.ai.call(k.ctx, "place_block", args("item", "oak_stairs", "x", stairs.getX(), "y", stairs.getY(), "z", stairs.getZ(), "facing", "east"));
		check(r.ok() && r.text().contains("task_done: task=place_block"), "A6 樓梯應該放好：" + r.text());
		r = k.ai.call(k.ctx, "place_block", args("item", "furnace", "x", furnace.getX(), "y", furnace.getY(), "z", furnace.getZ(), "facing", "north"));
		check(r.ok() && r.text().contains("task_done: task=place_block"), "A6 熔爐應該放好：" + r.text());

		// 原木的朝向是軸：只有地板可以貼時放不出 east(x 軸)，要先在旁邊有方塊
		r = k.ai.call(k.ctx, "place_block", args("item", "oak_log", "x", log.getX(), "y", log.getY(), "z", log.getZ(), "facing", "east"));
		k.expectError(r, "INVALID_ARGUMENT", "A6 沒有側面支撐時原木放不出 x 軸");
		check(r.text().contains("possible=axis_y"), "A6 應該列出放得出的朝向：" + r.text());
		k.setBlock(stone, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		k.ctx.waitTicks(5);
		r = k.ai.call(k.ctx, "place_block", args("item", "oak_log", "x", log.getX(), "y", log.getY(), "z", log.getZ(), "facing", "east"));
		check(r.ok() && r.text().contains("task_done: task=place_block"), "A6 原木應該放好：" + r.text());

		k.expectError(k.ai.call(k.ctx, "place_block", args("item", "stone", "x", c.getX() + 1, "y", c.getY(), "z", c.getZ() + 3, "facing", "north")),
			"INVALID_ARGUMENT", "A6 沒有朝向的方塊");

		check(k.server.computeOnServer(s -> {
			var lvl = s.overworld();
			var st = lvl.getBlockState(stairs);
			var fu = lvl.getBlockState(furnace);
			var lg = lvl.getBlockState(log);
			return st.is(Blocks.OAK_STAIRS) && st.getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.EAST
				&& fu.is(Blocks.FURNACE) && fu.getValue(net.minecraft.world.level.block.FurnaceBlock.FACING) == Direction.NORTH
				&& lg.is(Blocks.OAK_LOG) && lg.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == Direction.Axis.X;
		}), "A6 伺服器上的朝向不對");
		System.out.println("[ok] A6 place_block facing：樓梯朝東、熔爐朝北、原木 x 軸，沒有朝向的方塊回 INVALID_ARGUMENT");
		for (BlockPos p : new BlockPos[]{stairs, furnace, log, stone}) k.setBlock(p, Blocks.AIR.defaultBlockState());
	}

	/** Baritone 挖礦遇到朋友的木板包住唯一的礦：回報到不了，而且牆沒被拆(CalculationContextMixin)。 */
	private void protectedMine(TestKit k, BlockPos c, TestKit.Friend ming) {
		BlockPos ore = c.offset(-10, 1, 10);
		for (BlockPos p : BlockPos.betweenClosed(ore.offset(-1, -1, -1), ore.offset(1, 1, 1))) {
			ming.place(p.immutable(), Blocks.OAK_PLANKS.defaultBlockState());
		}
		k.setBlock(ore, Blocks.GOLD_ORE.defaultBlockState());
		k.give("minecraft:iron_pickaxe", 1);
		k.ctx.waitTicks(5);
		var r = k.ai.call(k.ctx, "mine", args("block", "gold_ore", "count", 1, "radius", 24));
		String text = r.text();
		if (r.ok()) {
			// 預設 wait_sec=30：多半已經把結果接在回應後面，沒有的話再等事件
			if (!text.contains("task_failed:")) text += "\n" + k.eventsUntil("task_failed:", 60);
			check(text.contains("[PROMPT] task_failed: task=mine code=PATH_BLOCKED") && text.contains("unreachable"),
				"Baritone 應該回報到不了(PATH_BLOCKED)：" + text);
		}
		boolean intact = k.server.computeOnServer(s -> {
			for (BlockPos p : BlockPos.betweenClosed(ore.offset(-1, -1, -1), ore.offset(1, 1, 1))) {
				if (!p.equals(ore) && !s.overworld().getBlockState(p).is(Blocks.OAK_PLANKS)) return false;
			}
			return true;
		});
		check(intact, "Baritone 挖礦時拆了朋友的木板");
		System.out.println("[ok] Baritone 挖礦被朋友的牆擋下，木板完好：" + text.lines().filter(l -> l.contains("task_failed") || l.startsWith("ERR")).findFirst().orElse(text));
	}

	/**
	 * 驗證 R5(尋路不拆自有建築)、R11(副手盾牌不擋鋤地)與 R13(stairs 群組、物品 ID 提示、inspect_area 上限、流動水提示)。
	 */
	private void reviewRegressions(TestKit k, BlockPos c) {
		k.clearInventory();
		k.agent().events.drainAll();

		// 先走回起點 c，確保後續放置都在伸手可及範圍內
		var back = k.ai.call(k.ctx, "goto", args("x", c.getX(), "y", c.getY(), "z", c.getZ()));
		String backText = back.text().contains("task_done:") ? back.text() : back.text() + "\n" + k.eventsUntil("task_", 30);
		check(back.ok() && backText.contains("task_done: task=goto"), "R5 回到起點失敗：" + backText);

		// R5：自己放的木板牆，goto 必須繞路而不能拆牆，但 break_block 仍可直接拆
		BlockPos wallBase = c.offset(3, 0, -1);
		k.give("minecraft:oak_planks", 6);
		k.give("minecraft:stone_axe", 1);
		k.ctx.waitTicks(5);
		for (int dz = 0; dz < 3; dz++) {
			for (int dy = 0; dy < 2; dy++) {
				BlockPos wp = wallBase.offset(0, dy, dz);
				var pr = k.ai.call(k.ctx, "place_block", args("item", "oak_planks", "x", wp.getX(), "y", wp.getY(), "z", wp.getZ()));
				check(pr.ok(), "R5 放置自家木板牆失敗：" + pr.text());
			}
		}
		k.ctx.waitTicks(5);
		BlockPos dest = c.offset(5, 0, 0);
		var go = k.ai.call(k.ctx, "goto", args("x", dest.getX(), "y", dest.getY(), "z", dest.getZ()));
		String goText = go.text().contains("task_done:") ? go.text() : go.text() + "\n" + k.eventsUntil("task_", 30);
		check(go.ok() && goText.contains("task_done: task=goto"), "R5 goto 應繞過自有牆抵達目標：" + goText);
		boolean wallIntact = k.server.computeOnServer(s -> {
			for (int dz = 0; dz < 3; dz++) {
				for (int dy = 0; dy < 2; dy++) {
					if (!s.overworld().getBlockState(wallBase.offset(0, dy, dz)).is(Blocks.OAK_PLANKS)) return false;
				}
			}
			return true;
		});
		check(wallIntact, "R5 Baritone 尋路拆毀了自己的木板牆");
		var br = k.ai.call(k.ctx, "break_block", args("x", wallBase.getX(), "y", wallBase.getY(), "z", wallBase.getZ()));
		String brText = br.text().contains("task_done:") ? br.text() : br.text() + "\n" + k.eventsUntil("task_", 20);
		check(br.ok() && brText.contains("task_done: task=break_block"), "R5 break_block 仍應能主動拆自己的木板：" + brText);

		// R11：副手拿盾牌時，use_item(stone_hoe) 與 till_and_plant 都能正常鋤地且盾牌留在副手
		BlockPos grass1 = c.offset(6, -1, 3), grass2 = c.offset(7, -1, 3);
		k.setBlock(grass1, Blocks.GRASS_BLOCK.defaultBlockState());
		k.setBlock(grass2, Blocks.GRASS_BLOCK.defaultBlockState());
		k.setBlock(grass1.above(), Blocks.AIR.defaultBlockState());
		k.setBlock(grass2.above(), Blocks.AIR.defaultBlockState());
		k.give("minecraft:shield", 1);
		k.give("minecraft:stone_hoe", 1);
		k.give("minecraft:wheat_seeds", 2);
		k.ctx.waitTicks(5);
		check(k.ai.call(k.ctx, "equip", args("item", "shield", "slot", "offhand")).ok(), "R11 裝備盾牌到副手失敗");
		var hoe = k.ai.call(k.ctx, "use_item", args("item", "stone_hoe", "x", grass1.getX(), "y", grass1.getY(), "z", grass1.getZ()));
		check(hoe.ok(), "R11 副手拿盾牌時 use_item(stone_hoe) 應該成功：" + hoe.text());
		var farm = k.ai.call(k.ctx, "till_and_plant", args(
			"from", grass2.getX() + "," + grass2.getY() + "," + grass2.getZ(),
			"to", grass2.getX() + "," + grass2.getY() + "," + grass2.getZ(),
			"seed", "wheat_seeds"));
		String farmText = farm.text().contains("task_done:") ? farm.text() : farm.text() + "\n" + k.eventsUntil("task_", 20);
		check(farm.ok() && farmText.contains("task_done: task=till_and_plant"), "R11 副手拿盾牌時 till_and_plant 應該成功：" + farmText);
		k.ctx.waitTicks(5);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(grass1).is(Blocks.FARMLAND)
			&& s.overworld().getBlockState(grass2).is(Blocks.FARMLAND)
			&& s.overworld().getBlockState(grass2.above()).is(Blocks.WHEAT)), "R11 伺服器端應變成耕地與小麥");
		check(k.ctx.computeOnClient(mc -> mc.player.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)), "R11 鋤地後盾牌應留在副手");

		// R13：find_blocks(stairs)、物品 ID 提示、inspect_area(limit=80)、流動水提示
		BlockPos stair = c.offset(5, 0, 5);
		k.setBlock(stair, Blocks.OAK_STAIRS.defaultBlockState());
		k.ctx.waitTicks(3);
		var foundStairs = k.ai.call(k.ctx, "find_blocks", args("type", "stairs", "radius", 16));
		check(foundStairs.ok() && foundStairs.text().contains("oak_stairs"), "R13 find_blocks(stairs) 應該找得到樓梯：" + foundStairs.text());
		var itemAsBlock = k.ai.call(k.ctx, "find_blocks", args("type", "wheat_seeds"));
		k.expectError(itemAsBlock, "INVALID_ARGUMENT", "R13 傳入物品 ID 應報錯");
		check(itemAsBlock.reason().contains("this is an item id, not a block id"), "R13 應該提示這是物品 ID：" + itemAsBlock.text());
		var area = k.ai.call(k.ctx, "inspect_area", args(
			"from", (c.getX() - 1) + "," + c.getY() + "," + (c.getZ() - 1),
			"to", (c.getX() + 1) + "," + (c.getY() + 1) + "," + (c.getZ() + 1),
			"limit", 80));
		check(area.ok(), "R13 inspect_area(limit=80) 應該允許：" + area.text());

		// 放一格水源在旁邊，讓 flowWater 自然變成穩定流動水(不會 3 tick 就蒸發成 air)
		BlockPos sourceWater = c.offset(5, 0, 1);
		BlockPos flowWater = c.offset(6, 0, 1);
		k.setBlock(sourceWater, Blocks.WATER.defaultBlockState());
		k.give("minecraft:bucket", 1);
		check(k.waitUntil(20, mc -> mc.level.getBlockState(flowWater).is(Blocks.WATER)
			&& !mc.level.getBlockState(flowWater).getFluidState().isSource()), "R13 測試用的流動水沒有流過來");
		var bucket = k.ai.call(k.ctx, "use_item", args("item", "bucket", "x", flowWater.getX(), "y", flowWater.getY(), "z", flowWater.getZ()));
		k.expectError(bucket, "INVALID_ARGUMENT", "R13 空桶撈流動水應報錯並提示");
		check(bucket.text().contains("flowing") && bucket.text().contains("level="), "R13 應附註流動水與 level：" + bucket.text());

		// 清理
		for (int dz = 0; dz < 3; dz++) for (int dy = 0; dy < 2; dy++) k.setBlock(wallBase.offset(0, dy, dz), Blocks.AIR.defaultBlockState());
		k.setBlock(grass1, Blocks.GRASS_BLOCK.defaultBlockState());
		k.setBlock(grass2, Blocks.GRASS_BLOCK.defaultBlockState());
		k.setBlock(grass2.above(), Blocks.AIR.defaultBlockState());
		k.setBlock(stair, Blocks.AIR.defaultBlockState());
		k.setBlock(sourceWater, Blocks.AIR.defaultBlockState());
		k.setBlock(flowWater, Blocks.AIR.defaultBlockState());
		k.clearInventory();
		k.agent().events.drainAll();
		System.out.println("[ok] R5/R11/R13 尋路不拆自家牆、副手盾牌鋤地、stairs/物品提示/limit=80/流動水提示皆驗證通過");
	}

	/** 手上換成指定工具，直接對方塊按右鍵(走最後防線，不經過工具)。 */
	private static void useWith(TestKit k, String item, BlockPos target) {
		var eq = k.ai.call(k.ctx, "equip", args("item", item));
		check(eq.ok(), "換上 " + item + " 失敗：" + eq.text());
		k.ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false)));
	}
}
