package blockagent.testkit;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/** 2026-11-26 審查：操作、工作、觀察事件與維度切換的回歸測試。 */
@SuppressWarnings("UnstableApiUsage")
public class ReviewToolsGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("ReviewToolsGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			if ("outdoor_water".equals(System.getProperty("blockagent.test.case"))) {
				k.start();
				outdoorWaterGoto(k, c);
				return;
			}
			k.flatArena(c, 16);
			TestKit.Friend friend = k.spawnFriend("Ming", c.east(2));
			k.start();
			equipAndSwitches(k, c);
			sleepingFriendAndFollow(k, c, friend);
			farmingAndHeightFilter(k, c);
			smeltUsesAnnouncedFurnace(k, c);
			craftInShelter(k, c);
			dimensionChange(k, c);
			outdoorWaterGoto(k, c);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void equipAndSwitches(TestKit k, BlockPos c) {
		k.clearInventory();
		k.cmd("item replace entity @p armor.chest with minecraft:iron_chestplate");
		k.give("minecraft:diamond_chestplate", 1);
		var armor = k.ai.call(k.ctx, "equip", args("item", "diamond_chestplate", "slot", "armor"));
		check(armor.ok(), "R21 護甲無法換裝：" + armor.text());
		k.ctx.waitTicks(8);
		String equipped = k.server.computeOnServer(s -> k.me(s).getItemBySlot(EquipmentSlot.CHEST).getItem()
			+ " old=" + k.me(s).getInventory().countItem(Items.IRON_CHESTPLATE));
		check(equipped.contains("diamond_chestplate") && equipped.contains("old=1"), "R21 伺服器應穿新甲，舊甲回背包：" + equipped);

		BlockPos door = c.south(2), lever = c.west(2);
		k.cmd("setblock " + TestKit.pos(door) + " minecraft:oak_door[facing=north,half=lower,open=false]");
		k.cmd("setblock " + TestKit.pos(door.above()) + " minecraft:oak_door[facing=north,half=upper,open=false]");
		k.cmd("setblock " + TestKit.pos(lever) + " minecraft:lever[face=floor]");
		k.ctx.waitTicks(5);
		check(useBlock(k, door).text().contains("open=true"), "R24 門應回報 open=true");
		check(useBlock(k, door).text().contains("open=false"), "R24 門應回報 open=false");
		var pulled = useBlock(k, lever);
		check(pulled.ok() && pulled.text().contains("powered=true"), "R24 拉桿應回報 powered=true：" + pulled.text());
		k.setBlock(door.above(), Blocks.AIR.defaultBlockState());
		k.setBlock(door, Blocks.AIR.defaultBlockState());
		k.setBlock(lever, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] R21/R24 護甲互換、門和拉桿狀態");
	}

	private static McpTestClient.Result useBlock(TestKit k, BlockPos p) {
		return k.ai.call(k.ctx, "use_block", args("x", p.getX(), "y", p.getY(), "z", p.getZ()));
	}

	private void sleepingFriendAndFollow(TestKit k, BlockPos c, TestKit.Friend friend) {
		BlockPos bed = c.east(2);
		k.onServer(s -> placeRedBed(s.overworld(), bed));
		k.cmd("time set 18000");
		k.ctx.waitTicks(5);
		k.agent().events.drainAll();
		var waiting = k.ai.callAsync("wait_events", args("max_wait_sec", 8));
		k.ctx.waitTicks(5);
		// FakePlayer 刻意拒絕 startSleeping；透過正常 entity data 同步姿勢和睡眠座標。
		k.onServer(s -> {
			friend.player.snapTo(bed.getX() + 1.5, bed.getY() + 0.56, bed.getZ() + 0.5);
			friend.player.setPose(Pose.SLEEPING);
			friend.player.setSleepingPos(bed.east());
		});
		check(k.waitUntil(30, mc -> mc.level.players().stream().anyMatch(p -> p.getName().getString().equals(friend.name) && p.isSleeping())),
			"R22 未收到測試隊友的睡眠同步");
		for (int i = 0; i < 160 && !waiting.isDone(); i++) k.ctx.waitTick();
		String wake = waiting.join().text();
		check(wake.contains("player_sleeping:") && wake.contains(friend.name), "R22 隊友入睡應喚醒等待：" + wake);
		var players = k.ai.call(k.ctx, "get_players");
		check(players.text().contains("activity=sleeping"), "R22 隊友應顯示 sleeping：" + players.text());

		k.onServer(s -> {
			friend.player.clearSleepingPos();
			friend.player.setPose(Pose.STANDING);
			friend.player.setNoGravity(true);
			friend.player.snapTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
		});
		k.ctx.waitTicks(5);
		fallResumesFollow(k, c, friend);
		k.onServer(s -> friend.player.snapTo(c.getX() + 0.5, c.getY() + 4, c.getZ() + 0.5));
		k.ctx.waitTicks(5);
		k.clearInventory();
		var follow = k.ai.call(k.ctx, "follow", args("player", friend.name, "distance", 2, "max_sec", 15));
		String text = follow.text().contains("task_failed:") ? follow.text() : follow.text() + k.eventsUntil("task_failed:", 18);
		check(text.contains("code=PATH_BLOCKED") && text.contains("delta_y="), "R29 不可達的樓層應回報高度差：" + text);
		k.setBlock(bed, Blocks.AIR.defaultBlockState());
		k.setBlock(bed.east(), Blocks.AIR.defaultBlockState());
		k.cmd("time set 1000");
		System.out.println("[ok] R22/R29 隊友睡眠喚醒、不可達樓層回報");
	}

	private void fallResumesFollow(TestKit k, BlockPos c, TestKit.Friend friend) {
		k.give("minecraft:water_bucket", 1);
		var follow = k.ai.call(k.ctx, "follow", args("player", friend.name, "distance", 2, "max_sec", 15, "wait_sec", 0));
		check(follow.ok(), "R35 跟隨任務無法啟動：" + follow.text());
		var task = k.agent().tasks.current();
		k.cmd("tp @p " + TestKit.pos(c.above(11)));
		check(k.waitUntil(100, mc -> k.agent().reflexes.active() == blockagent.game.reflex.Reflexes.Kind.FALL),
			"R35 主世界墜落應觸發水桶反射");
		check(k.waitUntil(100, mc -> mc.player.onGround() && k.agent().reflexes.active() == null)
				&& k.agent().tasks.current() == task,
			"R35 確認落地後應保留原本的任務");
		k.ai.call(k.ctx, "stop");
		k.setBlock(c, Blocks.AIR.defaultBlockState());
		k.agent().events.drainAll();
		System.out.println("[ok] R35 落地後保留原本的跟隨任務");
	}

	private void farmingAndHeightFilter(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		BlockPos ground = c.north(2).below(), stem = ground.above(), fruit = stem.west();
		k.setBlock(ground, Blocks.DIRT.defaultBlockState());
		k.setBlock(fruit.below(), Blocks.DIRT.defaultBlockState());
		k.setBlock(c.offset(-3, -1, -2), Blocks.WATER.defaultBlockState());
		k.give("minecraft:stone_hoe", 1);
		k.give("minecraft:melon_seeds", 1);
		String soil = blockagent.game.Fmt.pos(ground);
		var farm = k.ai.call(k.ctx, "till_and_plant", args("from", soil, "to", soil, "seed", "melon_seeds"));
		check(farm.ok() && k.waitUntil(30, mc -> mc.level.getBlockState(stem).is(Blocks.MELON_STEM) && k.agent().guard.isOwned(stem)),
			"R28 西瓜種子應種下並記入所有權：" + farm.text());
		k.ctx.waitTicks(45);
		k.setBlock(fruit, Blocks.MELON.defaultBlockState());
		k.setBlock(stem, Blocks.ATTACHED_MELON_STEM.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		k.ctx.waitTicks(5);
		String at = blockagent.game.Fmt.pos(fruit);
		var harvest = k.ai.call(k.ctx, "harvest", args("from", at, "to", at));
		check(harvest.ok() && k.waitUntil(100, mc -> mc.level.getBlockState(fruit).isAir() && mc.level.getBlockState(stem).is(Blocks.MELON_STEM)),
			"R28 應收果實並保留藤蔓：" + harvest.text());

		BlockPos near = c.east(2), high = c.east(5).above(3);
		k.setBlock(near, Blocks.GOLD_ORE.defaultBlockState());
		k.setBlock(high, Blocks.GOLD_ORE.defaultBlockState());
		k.ctx.waitTicks(5);
		var found = k.ai.call(k.ctx, "find_blocks", args("type", "gold_ore", "radius", 16, "max", 1,
			"min_y", high.getY(), "max_y", high.getY()));
		check(found.ok() && found.text().contains("pos=" + blockagent.game.Fmt.pos(high)), "R37 高度篩選應先於 max：" + found.text());
		k.setBlock(near, Blocks.AIR.defaultBlockState());
		k.setBlock(high, Blocks.AIR.defaultBlockState());
		BlockPos log = c.east(3);
		k.setBlock(log, Blocks.OAK_LOG.defaultBlockState());
		k.ctx.waitTicks(3);
		var mine = k.ai.call(k.ctx, "mine", args("block", "oak_log", "count", 1, "radius", 8, "wait_sec", 0));
		check(mine.ok() && mine.text().contains("no axe in inventory"), "R32 無斧頭可挖原木，但先提醒：" + mine.text());
		k.ai.call(k.ctx, "stop");
		k.setBlock(log, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] R32 徒手挖原木提示缺斧");
		System.out.println("[ok] R28/R37 種西瓜、收果實、方塊搜尋高度篩選");
	}

	private void smeltUsesAnnouncedFurnace(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();
		BlockPos other = c.south(), owned = c.north(3);
		k.setBlock(other, Blocks.FURNACE.defaultBlockState());
		k.setBlock(owned, Blocks.FURNACE.defaultBlockState());
		k.ctx.waitTicks(5);
		k.ctx.runOnClient(mc -> k.agent().tracker.markOwned(owned, mc.level.getBlockState(owned)));
		k.give("minecraft:raw_iron", 1);
		k.give("minecraft:coal", 1);
		var smelt = k.ai.call(k.ctx, "smelt", args("item", "raw_iron", "count", 1, "fuel", "coal", "wait_sec", 0));
		check(smelt.ok() && smelt.text().contains("furnace=" + blockagent.game.Fmt.pos(owned)), "R30 應預報自有熔爐：" + smelt.text());
		check(k.waitUntil(120, mc -> mc.level.getBlockState(owned).getValue(BlockStateProperties.LIT)
			&& !mc.level.getBlockState(other).getValue(BlockStateProperties.LIT)), "R30 實際應用預報的熔爐");
		k.ai.call(k.ctx, "stop");
		k.setBlock(other, Blocks.AIR.defaultBlockState());
		k.setBlock(owned, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] R30 開工預報與實際使用同一熔爐");
	}

	private void craftInShelter(TestKit k, BlockPos c) throws Exception {
		k.cmd("tp @p " + TestKit.pos(c));
		k.cmd("time set 18000");
		k.clearInventory();
		k.give("minecraft:crafting_table", 1);
		k.give("minecraft:oak_planks", 8);
		k.give("minecraft:chest", 3);
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, 2, -2), c.offset(2, 2, 2))) {
			k.setBlock(p.immutable(), Blocks.OAK_PLANKS.defaultBlockState());
		}
		k.setBlock(c.east(6), Blocks.CRAFTING_TABLE.defaultBlockState());
		k.cmd("execute at @p run summon minecraft:zombie ~10 ~ ~ {NoAI:1b,PersistenceRequired:1b}");
		k.cmd("execute at @p run summon minecraft:zombie ~10 ~ ~1 {NoAI:1b,PersistenceRequired:1b}");
		k.cmd("execute at @p run summon minecraft:zombie ~10 ~ ~2 {NoAI:1b,PersistenceRequired:1b}");
		k.ctx.waitTicks(10);
		var craft = k.ai.call(k.ctx, "craft", args("item", "chest"));
		check(craft.ok() && craft.text().contains("uses=new_table")
			&& k.waitUntil(100, mc -> mc.player.getInventory().countItem(Items.CHEST) == 4),
			"R36 高危險時應在室內放工作台並完成合成：" + craft.text());
		check(k.myPos().distSqr(c) <= 1, "R36 應在原地合成");
		check(java.nio.file.Files.readAllLines(k.agent().log.file()).stream()
			.map(line -> com.google.gson.JsonParser.parseString(line).getAsJsonObject())
			.anyMatch(entry -> entry.get("action").getAsString().equals("task_start")
				&& entry.get("result").getAsString().startsWith("craft item=chest made=0/1")),
			"R31 開始合成的 made 應從 0 起算");
		System.out.println("[ok] R31/R36 高危險時原地合成、起始進度正確");
	}

	private static void placeRedBed(Level level, BlockPos foot) {
		var bed = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
		level.setBlock(foot, bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT), flags);
		level.setBlock(foot.east(), bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), flags);
	}

	private void dimensionChange(TestKit k, BlockPos returnTo) throws Exception {
		check(!baritone.api.BaritoneAPI.getSettings().allowWaterBucketFall.value, "R35 應關閉 Baritone 水桶跳落");
		k.onServer(s -> {
			var nether = s.getLevel(Level.NETHER);
			nether.getChunk(0, 0);
			for (BlockPos p : BlockPos.betweenClosed(-2, 87, -2, 3, 100, 3)) {
				nether.setBlock(p, (p.getY() == 87 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3, 512);
			}
			placeRedBed(nether, new BlockPos(0, 88, 1));
		});
		k.agent().events.drainAll();
		k.cmd("execute in minecraft:the_nether run tp @p 0.5 88 0.5");
		check(k.waitUntil(160, mc -> mc.level != null && mc.level.dimension().equals(Level.NETHER)), "R23 未抵達地獄");
		String events = k.eventsUntil("dimension_changed:", 6);
		check(events.contains("dim=minecraft:the_nether"), "R23 應送出維度事件：" + events);
		var self = k.ai.call(k.ctx, "get_self");
		check(self.text().contains("portal_cooldown=") && !self.text().contains("time="), "R23/R34 地獄應顯示冷卻並省略天色：" + self.text());
		var sleep = k.ai.call(k.ctx, "sleep", args("x", 0, "y", 88, "z", 1));
		k.expectError(sleep, "INVALID_ARGUMENT", "R34 地獄的床不能使用");
		check(sleep.reason().contains("safely"), "R34 應為維度規則拒絕：" + sleep.text());
		k.give("minecraft:water_bucket", 1);
		int mark = java.nio.file.Files.readAllLines(k.agent().log.file()).size();
		k.cmd("execute in minecraft:the_nether run tp @p 0.5 98 0.5");
		check(k.waitUntil(60, mc -> mc.player.getY() > 92)
			&& k.waitUntil(100, mc -> mc.level.dimension().equals(Level.NETHER) && mc.player.getY() < 89 && mc.player.onGround()),
			"R35 地獄測試角色應留在地獄並完成墜落");
		check(java.nio.file.Files.readAllLines(k.agent().log.file()).stream().skip(mark).noneMatch(line -> line.contains("fall_start"))
			&& k.ctx.computeOnClient(mc -> mc.player.getInventory().countItem(Items.WATER_BUCKET) == 1),
			"R35 地獄墜落時應保留水桶，並且不觸發倒水反射");
		k.cmd("execute in minecraft:overworld run tp @p " + TestKit.pos(returnTo));
		check(k.waitUntil(160, mc -> mc.level != null && mc.level.dimension().equals(Level.OVERWORLD)), "R23 未返回主世界");
		check(k.eventsUntil("dimension_changed:", 6).contains("dim=minecraft:overworld"), "R23 應送出返回主世界的維度事件");
		System.out.println("[ok] R23/R34/R35 維度切換、地獄床保護與水桶反射停用");
	}

	private void outdoorWaterGoto(TestKit k, BlockPos c) {
		k.flatArena(c, 24);
		k.cmd("time set 1000");
		BlockPos source = c.south(14);
		k.setBlock(source, Blocks.WATER.defaultBlockState());
		check(k.waitUntil(180, mc -> mc.level.getFluidState(source.east(7)).is(FluidTags.WATER)),
			"R25 測試場地的水還未流到岸邊");

		BlockPos destination = source.east(10);
		teleport(k, source);
		var started = k.ai.call(k.ctx, "goto", gotoArgs(destination, 0));
		check(started.ok() && k.waitUntil(100, mc -> mc.options.keyUp.isDown()),
			"R25 站在水源中央應啟動上岸流程：" + started.text());
		check(k.ai.call(k.ctx, "stop").ok(), "R25 應可中斷上岸");
		check(k.ctx.computeOnClient(mc -> !mc.options.keyUp.isDown() && !mc.options.keyJump.isDown()),
			"R25 停止後要放開移動鍵");

		assertGotoFrom(k, source, destination);
		assertGotoFrom(k, source.east(2), destination);
		assertGotoFrom(k, source.east(7), destination);

		// 低一格的淺水窪，水流格四周有高一格的岸。
		BlockPos dip = c.north(12);
		k.cmd("fill " + TestKit.pos(dip.offset(-6, 0, -6)) + " " + TestKit.pos(dip.offset(6, 0, 6)) + " minecraft:stone");
		k.cmd("fill " + TestKit.pos(dip.offset(-2, 0, -2)) + " " + TestKit.pos(dip.offset(2, 0, 2)) + " minecraft:air");
		k.setBlock(dip, Blocks.WATER.defaultBlockState());
		check(k.waitUntil(100, mc -> mc.level.getFluidState(dip.east(2)).is(FluidTags.WATER)), "R25 水窪尚未形成");
		assertGotoFrom(k, dip, dip.east(5).above());

		// 屋內積水留在原有範圍外；這裡用遮蔽起點驗證不搶走原本的失敗行為。
		k.setBlock(source.above(3), Blocks.STONE.defaultBlockState());
		teleport(k, source);
		check(k.waitUntil(20, mc -> !mc.level.canSeeSky(source.above())), "R25 測試起點應被遮蔽");
		var covered = k.ai.call(k.ctx, "goto", gotoArgs(destination, 15));
		check(covered.text().contains("code=PATH_BLOCKED") && !covered.text().contains("could not reach dry ground"),
			"R25 有屋頂的起點沿用正常尋路：" + covered.text());
		System.out.println("[ok] R25 室外淺水可從水源中央、水流中與較高的岸上岸，停止後放開按鍵");
	}

	private static void assertGotoFrom(TestKit k, BlockPos start, BlockPos end) {
		teleport(k, start);
		var arrived = k.ai.call(k.ctx, "goto", gotoArgs(end, 15));
		check(arrived.ok() && arrived.text().contains("task_done: task=goto") && k.myPos().equals(end),
			"R25 goto 應抵達乾地(" + TestKit.pos(start) + " → " + TestKit.pos(end) + ")：" + arrived.text());
	}

	private static void teleport(TestKit k, BlockPos pos) {
		k.cmd("tp @p " + TestKit.pos(pos));
		check(k.waitUntil(30, mc -> mc.player.blockPosition().equals(pos)), "R25 測試起點不同：" + k.myPos());
		k.agent().events.drainAll();
	}

	private static com.google.gson.JsonObject gotoArgs(BlockPos pos, int waitSeconds) {
		return args("x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "range", 0, "wait_sec", waitSeconds);
	}

}
