package blockagent.testkit;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.item.DyeColor;

import java.util.List;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * T16(b)–(f)：第一棒 5 項底層能力與日常擴充的遊戲測試(docs/SPEC.md S3.2a、S4.2b–S4.2e、S5.2)。
 */
@SuppressWarnings("UnstableApiUsage")
public class FirstWaveGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("FirstWaveGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			k.flatArena(c, 16);
			k.start();
			testConsumables(k);
			testEquipChestOffhand(k, c);
			testSignsFramesAndBooks(k, c);
			testSmeltFastAndListTake(k, c);
			testPlaceHalfSneakAndGuard(k, c);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** 等待背景任務回應(若期間因 damaged 等事件提早返回 WAIT task_running，補等至 task_done)。 */
	private static String finishTask(TestKit k, McpTestClient.Result r) {
		check(r.ok(), "任務啟動失敗：" + r.text());
		if (r.text().contains("WAIT task_running")) {
			return r.text() + "\n" + k.eventsUntil("task_done:", 15);
		}
		return r.text();
	}

	/** T16(b)：喝抗火藥水(match)與牛奶桶清除中毒、確認 effects= 回報與食物自動選擇不受影響。 */
	private void testConsumables(TestKit k) {
		k.clearInventory();
		k.onServer(s -> {
			var p = k.me(s);
			p.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.NIGHT_VISION));
			p.getInventory().add(PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE));
			p.getInventory().add(new ItemStack(Items.MILK_BUCKET));
			p.getInventory().add(new ItemStack(Items.BREAD, 2));
			p.getFoodData().setFoodLevel(20);
		});
		k.ctx.waitTicks(4);

		// 滿飽食度吃一般食物直接回 INVALID_ARGUMENT
		var fullBread = k.ai.call(k.ctx, "eat", args("item", "bread"));
		k.expectError(fullBread, "INVALID_ARGUMENT", "T16(b) 滿飽食度吃麵包應直接拒絕");

		// 滿飽食度喝指定抗火藥水：應成功並回報 effects= 含 fire_resistance
		String drinkFire = finishTask(k, k.ai.call(k.ctx, "eat", args("item", "potion", "match", "fire_resistance")));
		check(drinkFire.contains("fire_resistance") && drinkFire.contains("effects="),
			"T16(b) 喝抗火藥水應回報 fire_resistance 效果：" + drinkFire);
		check(k.server.computeOnServer(s -> k.me(s).hasEffect(MobEffects.FIRE_RESISTANCE)),
			"T16(b) 伺服器端玩家應獲得抗火效果");

		// 給予中毒效果，喝牛奶桶清除並回報 effects=none
		k.onServer(s -> k.me(s).addEffect(new MobEffectInstance(MobEffects.POISON, 600, 0)));
		k.ctx.waitTicks(4);
		String milk = finishTask(k, k.ai.call(k.ctx, "eat", args("item", "milk_bucket")));
		check(milk.contains("effects=none"), "T16(b) 喝牛奶應清除所有效果並回報 effects=none：" + milk);
		check(k.server.computeOnServer(s -> !k.me(s).hasEffect(MobEffects.POISON)), "T16(b) 伺服器端應已清除中毒");

		// 飢餓但未觸發 EAT 反射(<=14)時省略 item：自動挑安全食物(麵包)，不喝剩餘的夜視藥水
		k.onServer(s -> k.me(s).getFoodData().setFoodLevel(16));
		k.ctx.waitTicks(4);
		String autoEat = finishTask(k, k.ai.call(k.ctx, "eat", args()));
		check(autoEat.contains("ate=bread"), "T16(b) 自動進食應挑麵包而非藥水：" + autoEat);
		System.out.println("[ok] T16(b) 喝藥水、牛奶解毒、effects= 回報與自動食物選擇");
	}

	/** T16(c)：純卸裝、chest 依屬性精準取放、副手持有材料時的 smelt/drop/chest(put)/place_block。 */
	private void testEquipChestOffhand(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();
		k.cmd("item replace entity @p armor.head with minecraft:diamond_helmet");
		k.cmd("item replace entity @p weapon.offhand with minecraft:shield");
		k.ctx.waitTicks(4);

		var unHead = k.ai.call(k.ctx, "equip", args("item", "none", "slot", "head"));
		check(unHead.ok() && unHead.text().contains("OK unequipped"), "T16(c) 應可純卸頭盔：" + unHead.text());
		var unHeadAgain = k.ai.call(k.ctx, "equip", args("item", "none", "slot", "head"));
		check(unHeadAgain.ok() && unHeadAgain.text().contains("OK already_empty"), "T16(c) 空裝備欄卸下應回 already_empty：" + unHeadAgain.text());
		var unOff = k.ai.call(k.ctx, "equip", args("item", "none", "slot", "offhand"));
		check(unOff.ok() && unOff.text().contains("OK unequipped"), "T16(c) 應可純卸副手：" + unOff.text());
		k.ctx.waitTicks(4);
		check(k.server.computeOnServer(s -> k.me(s).getItemBySlot(EquipmentSlot.HEAD).isEmpty()
			&& k.me(s).getOffhandItem().isEmpty()
			&& k.me(s).getInventory().countItem(Items.DIAMOND_HELMET) == 1
			&& k.me(s).getInventory().countItem(Items.SHIELD) == 1),
			"T16(c) 卸下的頭盔和盾牌應回到主背包");

		// chest 用 id[屬性] 精準取出附魔書、藥水、高耐久鎬
		BlockPos chestPos = c.east(2);
		k.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
		k.clearInventory();
		k.onServer(s -> {
			var reg = s.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
			ChestBlockEntity be = (ChestBlockEntity) s.overworld().getBlockEntity(chestPos);
			// 附魔書使用 STORED_ENCHANTMENTS
			ItemStack mendingBook = new ItemStack(Items.ENCHANTED_BOOK);
			var storedMending = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
				net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
			storedMending.set(reg.getOrThrow(Enchantments.MENDING), 1);
			mendingBook.set(DataComponents.STORED_ENCHANTMENTS, storedMending.toImmutable());

			ItemStack sharpBook = new ItemStack(Items.ENCHANTED_BOOK);
			var storedSharp = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
				net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
			storedSharp.set(reg.getOrThrow(Enchantments.SHARPNESS), 3);
			sharpBook.set(DataComponents.STORED_ENCHANTMENTS, storedSharp.toImmutable());

			ItemStack wornPick = new ItemStack(Items.DIAMOND_PICKAXE);
			wornPick.setDamageValue(1400);
			ItemStack freshPick = new ItemStack(Items.DIAMOND_PICKAXE);
			freshPick.setDamageValue(20);

			be.setItem(0, sharpBook);
			be.setItem(1, mendingBook);
			be.setItem(2, PotionContents.createItemStack(Items.POTION, Potions.NIGHT_VISION));
			be.setItem(3, PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE));
			be.setItem(4, wornPick);
			be.setItem(5, freshPick);
			be.setChanged();
		});
		k.ctx.waitTicks(4);

		var takeItems = new com.google.gson.JsonObject();
		takeItems.addProperty("enchanted_book[mending]", 1);
		takeItems.addProperty("potion[fire_resistance]", 1);
		takeItems.addProperty("diamond_pickaxe[best_dur]", 1);
		var taken = k.ai.call(k.ctx, "chest", args("action", "take", "x", chestPos.getX(), "y", chestPos.getY(), "z", chestPos.getZ(), "items", takeItems));
		check(taken.ok() && taken.text().contains("task_done"), "T16(c) 依屬性取物應成功：" + taken.text());
		k.ctx.waitTicks(4);
		check(k.server.computeOnServer(s -> {
			var inv = k.me(s).getInventory();
			boolean hasMending = false, hasFirePot = false, hasFreshPick = false;
			for (int i = 0; i < 36; i++) {
				ItemStack st = inv.getItem(i);
				if (st.is(Items.ENCHANTED_BOOK)) {
					var stored = st.get(DataComponents.STORED_ENCHANTMENTS);
					if (stored != null && stored.entrySet().stream().anyMatch(e -> e.getKey().getRegisteredName().endsWith("mending"))) {
						hasMending = true;
					}
				}
				if (st.is(Items.POTION)) {
					var pc = st.get(DataComponents.POTION_CONTENTS);
					if (pc != null && pc.is(Potions.FIRE_RESISTANCE)) hasFirePot = true;
				}
				if (st.is(Items.DIAMOND_PICKAXE) && st.getDamageValue() == 20) hasFreshPick = true;
			}
			return hasMending && hasFirePot && hasFreshPick;
		}), "T16(c) 應精準拿到修補書、抗火藥水與高耐久鑽石鎬");

		// 副手持有材料：chest(put)、drop、place_block、smelt
		k.clearInventory();
		k.cmd("item replace entity @p weapon.offhand with minecraft:cobblestone 4");
		k.ctx.waitTicks(4);
		var putItems = new com.google.gson.JsonObject();
		putItems.addProperty("cobblestone", 2);
		var putRes = k.ai.call(k.ctx, "chest", args("action", "put", "x", chestPos.getX(), "y", chestPos.getY(), "z", chestPos.getZ(), "items", putItems));
		check(putRes.ok() && putRes.text().contains("task_done"), "T16(c) 副手材料 chest(put) 應成功：" + putRes.text());

		k.clearInventory();
		k.cmd("item replace entity @p weapon.offhand with minecraft:dirt 2");
		k.ctx.waitTicks(4);
		var dropRes = k.ai.call(k.ctx, "drop", args("item", "dirt", "count", 1));
		check(dropRes.ok() && dropRes.text().contains("count=1"), "T16(c) 副手材料 drop 應成功：" + dropRes.text());
		BlockPos placePos = c.west(2);
		var placeRes = k.ai.call(k.ctx, "place_block", args("item", "dirt", "x", placePos.getX(), "y", placePos.getY(), "z", placePos.getZ()));
		check(placeRes.ok() && placeRes.text().contains("OK placed"), "T16(c) 副手材料 place_block 應成功：" + placeRes.text());
		k.setBlock(placePos, Blocks.AIR.defaultBlockState());

		BlockPos furnPos = c.north(2);
		k.setBlock(furnPos, Blocks.FURNACE.defaultBlockState());
		k.clearInventory();
		k.cmd("item replace entity @p weapon.offhand with minecraft:raw_iron 1");
		k.give("minecraft:coal", 1);
		var smeltOff = k.ai.call(k.ctx, "smelt", args("item", "raw_iron", "count", 1, "fuel", "coal"));
		check(smeltOff.ok() && smeltOff.text().contains("task_done"), "T16(c) 副手原料 smelt 應成功：" + smeltOff.text());
		k.setBlock(furnPos, Blocks.AIR.defaultBlockState());
		k.setBlock(chestPos, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] T16(c) 純卸裝、屬性取放與副手一致化");
	}

	/** T16(d)：雙面告示牌、非空物品展示框、書台讀取(自動關介面)與背包成書分頁讀取(不開 GUI)。 */
	private void testSignsFramesAndBooks(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();

		// 雙面告示牌 + 空白告示牌
		BlockPos writtenSign = c.north(2);
		BlockPos blankSign = c.north(3);
		k.setBlock(writtenSign, Blocks.OAK_SIGN.defaultBlockState());
		k.setBlock(blankSign, Blocks.OAK_SIGN.defaultBlockState());
		k.onServer(s -> {
			SignBlockEntity be = (SignBlockEntity) s.overworld().getBlockEntity(writtenSign);
			be.setText(new SignText(
				List.of(Component.literal("Warehouse"), Component.literal("Iron"), Component.empty(), Component.empty()),
				List.of(Component.literal("Warehouse"), Component.literal("Iron"), Component.empty(), Component.empty()),
				DyeColor.BLACK, false), SignTextSlot.FRONT);
			be.setText(new SignText(
				List.of(Component.literal("BackNote"), Component.empty(), Component.empty(), Component.empty()),
				List.of(Component.literal("BackNote"), Component.empty(), Component.empty(), Component.empty()),
				DyeColor.BLACK, false), SignTextSlot.BACK);
			be.setChanged();
			s.overworld().sendBlockUpdated(writtenSign, be.getBlockState(), be.getBlockState(), 3);
		});

		// 非空物品展示框
		BlockPos wallPos = c.east(2);
		k.setBlock(wallPos, Blocks.STONE.defaultBlockState());
		k.onServer(s -> {
			ItemFrame frame = new ItemFrame(s.overworld(), wallPos.west(), Direction.WEST);
			frame.setItem(new ItemStack(Items.DIAMOND));
			s.overworld().addFreshEntity(frame);
		});
		k.ctx.waitTicks(6);

		String signCoord = blockagent.game.Fmt.pos(writtenSign);
		var area = k.ai.call(k.ctx, "inspect_area", args("from", signCoord, "to", signCoord, "mode", "blocks"));
		check(area.ok() && area.text().contains("front=\"Warehouse/Iron\"") && area.text().contains("back=\"BackNote\""),
			"T16(d) inspect_area 應讀出告示牌正反面文字：" + area.text());

		var surr = k.ai.call(k.ctx, "get_surroundings", args("radius", 8));
		check(surr.ok() && surr.text().contains("Warehouse/Iron") && surr.text().contains("BackNote")
				&& !surr.text().contains(blockagent.game.Fmt.pos(blankSign))
				&& surr.text().contains("frame item=diamond"),
			"T16(d) get_surroundings 應列有字告示牌與非空展示框，略過空白牌：" + surr.text());

		// 書台讀書並自動關閉 LecternMenu
		BlockPos lecternPos = c.south(2);
		k.setBlock(lecternPos, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true));
		k.onServer(s -> {
			LecternBlockEntity lbe = (LecternBlockEntity) s.overworld().getBlockEntity(lecternPos);
			ItemStack lecternBook = new ItemStack(Items.WRITTEN_BOOK);
			lecternBook.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough("Town Rules"), "Ming", 0,
				List.of(Filterable.passThrough(Component.literal("No griefing."))), true));
			lbe.setBook(lecternBook);
			lbe.setChanged();
		});
		k.ctx.waitTicks(5);
		var readLectern = k.ai.call(k.ctx, "use_block", args("x", lecternPos.getX(), "y", lecternPos.getY(), "z", lecternPos.getZ()));
		check(readLectern.ok() && readLectern.text().contains("Town Rules") && readLectern.text().contains("No griefing."),
			"T16(d) use_block 應讀出書台內容：" + readLectern.text());
		check(k.ctx.computeOnClient(mc -> mc.player.containerMenu == mc.player.inventoryMenu),
			"T16(d) 讀完書台後應自動關閉 LecternMenu");

		// 背包成書分頁讀取且不開書本 GUI
		k.onServer(s -> {
			ItemStack longBook = new ItemStack(Items.WRITTEN_BOOK);
			String longPage = "PageOneContent_".repeat(50);
			longBook.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough("Field Log"), "Amy", 0,
				List.of(
					Filterable.passThrough(Component.literal(longPage)),
					Filterable.passThrough(Component.literal(longPage)),
					Filterable.passThrough(Component.literal("FinalChapter"))),
				true));
			k.me(s).getInventory().add(longBook);
		});
		k.ctx.waitTicks(4);
		var p1 = k.ai.call(k.ctx, "use_item", args("item", "written_book", "match", "name=Field"));
		check(p1.ok() && p1.text().contains("Field Log") && p1.text().contains("truncated=true") && p1.text().contains("next_page=2"),
			"T16(d) 背包成書第 1 頁應回報 truncated 與 next_page=2：" + p1.text());
		check(k.ctx.computeOnClient(mc -> mc.gui.screen() == null), "T16(d) 讀背包成書不應打開原版書本 GUI");

		var p2 = k.ai.call(k.ctx, "use_item", args("item", "written_book", "page", 2));
		check(p2.ok() && p2.text().contains("FinalChapter"), "T16(d) 背包成書翻頁應讀到最後一頁：" + p2.text());

		k.setBlock(writtenSign, Blocks.AIR.defaultBlockState());
		k.setBlock(blankSign, Blocks.AIR.defaultBlockState());
		k.setBlock(wallPos, Blocks.AIR.defaultBlockState());
		k.setBlock(lecternPos, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] T16(d) 告示牌、展示框、書台與背包成書分頁讀取");
	}

	/** T16(e)：smelt 優先用高爐燒鐵礦、煙燻爐烤肉，以及 action=list/take。 */
	private void testSmeltFastAndListTake(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();

		BlockPos regular = c.east(2);
		BlockPos blast = c.north(3);
		BlockPos smoker = c.south(3);
		k.setBlock(regular, Blocks.FURNACE.defaultBlockState());
		k.setBlock(blast, Blocks.BLAST_FURNACE.defaultBlockState());
		k.setBlock(smoker, Blocks.SMOKER.defaultBlockState());
		k.ctx.waitTicks(5);

		k.give("minecraft:raw_iron", 1);
		k.give("minecraft:coal", 1);
		var ironSmelt = k.ai.call(k.ctx, "smelt", args("item", "raw_iron", "count", 1, "fuel", "coal"));
		check(ironSmelt.ok() && ironSmelt.text().contains("blast_furnace") && ironSmelt.text().contains("task_done"),
			"T16(e) 燒鐵礦應優先使用高爐：" + ironSmelt.text());

		k.give("minecraft:beef", 1);
		k.give("minecraft:coal", 1);
		var beefSmelt = k.ai.call(k.ctx, "smelt", args("item", "beef", "count", 1, "fuel", "coal"));
		check(beefSmelt.ok() && beefSmelt.text().contains("smoker") && beefSmelt.text().contains("task_done"),
			"T16(e) 烤牛肉應優先使用煙燻爐：" + beefSmelt.text());

		// 預先在普通熔爐放入產物，測試 action=list 與 action=take
		k.clearInventory();
		k.onServer(s -> {
			AbstractFurnaceBlockEntity fbe = (AbstractFurnaceBlockEntity) s.overworld().getBlockEntity(regular);
			fbe.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
			fbe.setItem(2, new ItemStack(Items.STONE, 5));
			fbe.setChanged();
		});
		k.ctx.waitTicks(4);

		var listed = k.ai.call(k.ctx, "smelt", args("action", "list", "x", regular.getX(), "y", regular.getY(), "z", regular.getZ()));
		check(listed.ok() && listed.text().contains("in=cobblestone:3") && listed.text().contains("out=stone:5") && listed.text().contains("progress="),
			"T16(e) smelt list 應回報爐內內容與進度：" + listed.text());

		var took = k.ai.call(k.ctx, "smelt", args("action", "take", "x", regular.getX(), "y", regular.getY(), "z", regular.getZ()));
		check(took.ok() && took.text().contains("took=stone:5"), "T16(e) smelt take 應取出既有產物：" + took.text());
		k.ctx.waitTicks(4);
		check(k.server.computeOnServer(s -> k.me(s).getInventory().countItem(Items.STONE) == 5),
			"T16(e) 伺服器端背包應有 5 個石頭");

		k.setBlock(regular, Blocks.AIR.defaultBlockState());
		k.setBlock(blast, Blocks.AIR.defaultBlockState());
		k.setBlock(smoker, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] T16(e) 高爐/煙燻爐優先與 smelt list/take");
	}

	/** T16(f)：place_block 的 half=top、sneak=true 貼放漏斗/火把、放完放開 keyShift，以及不可蹲下疊合他人半磚。 */
	private void testPlaceHalfSneakAndGuard(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();

		// 無 half 屬性應回 INVALID_ARGUMENT
		var badHalf = k.ai.call(k.ctx, "place_block", args("item", "stone", "x", c.getX() + 2, "y", c.getY(), "z", c.getZ(), "half", "top"));
		k.expectError(badHalf, "INVALID_ARGUMENT", "T16(f) stone 沒有 half 屬性應回 INVALID_ARGUMENT");

		// 放上半磚與倒置樓梯(旁邊立一根石柱作為側面支撐)
		BlockPos pillar = c.east(2);
		BlockPos slabPos = pillar.north();
		BlockPos stairPos = pillar.south();
		k.setBlock(pillar, Blocks.STONE.defaultBlockState());
		k.give("minecraft:oak_slab", 2);
		k.give("minecraft:oak_stairs", 1);

		var topSlab = k.ai.call(k.ctx, "place_block", args("item", "oak_slab", "x", slabPos.getX(), "y", slabPos.getY(), "z", slabPos.getZ(), "half", "top"));
		check(topSlab.ok() && topSlab.text().contains("type=top"), "T16(f) 應放下上半磚：" + topSlab.text());
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(slabPos).getValue(SlabBlock.TYPE) == SlabType.TOP),
			"T16(f) 伺服器端應為上半磚");

		var invStair = k.ai.call(k.ctx, "place_block", args("item", "oak_stairs", "x", stairPos.getX(), "y", stairPos.getY(), "z", stairPos.getZ(), "facing", "east", "half", "top"));
		check(invStair.ok() && invStair.text().contains("half=top"), "T16(f) 應放下倒置樓梯：" + invStair.text());
		check(k.server.computeOnServer(s -> {
			BlockState st = s.overworld().getBlockState(stairPos);
			return st.getValue(BlockStateProperties.HALF) == Half.TOP && st.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST;
		}), "T16(f) 伺服器端應為朝東倒置樓梯");

		// sneak=true 貼著箱子放漏斗、貼著工作台插火把
		BlockPos chestPos = c.north(2);
		BlockPos hopperPos = chestPos.above();
		BlockPos tablePos = c.south(2);
		BlockPos torchPos = tablePos.north();
		k.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
		k.setBlock(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState());
		k.give("minecraft:hopper", 1);
		k.give("minecraft:torch", 1);

		var hopper = k.ai.call(k.ctx, "place_block", args("item", "hopper", "x", hopperPos.getX(), "y", hopperPos.getY(), "z", hopperPos.getZ(), "facing", "down", "sneak", true));
		check(hopper.ok() && hopper.text().contains("hopper"), "T16(f) sneak=true 應可對箱子放漏斗：" + hopper.text());
		check(k.ctx.computeOnClient(mc -> !mc.options.keyShift.isDown() && mc.player.containerMenu == mc.player.inventoryMenu),
			"T16(f) 放完漏斗後應放開 keyShift 且不可打開箱子");
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(hopperPos).is(Blocks.HOPPER)),
			"T16(f) 伺服器端箱子上方應為漏斗");

		var torch = k.ai.call(k.ctx, "place_block", args("item", "torch", "x", torchPos.getX(), "y", torchPos.getY(), "z", torchPos.getZ(), "facing", "north", "sneak", true));
		check(torch.ok() && torch.text().contains("wall_torch"), "T16(f) sneak=true 應可對工作台側邊插火把：" + torch.text());
		check(k.ctx.computeOnClient(mc -> !mc.options.keyShift.isDown() && mc.player.containerMenu == mc.player.inventoryMenu),
			"T16(f) 放完火把後應放開 keyShift 且不可打開工作台");

		// 安全底線(S7.3b)：無法用 sneak=true 疊合他人的半磚
		BlockPos otherSlab = c.west(2);
		k.setBlock(otherSlab, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
		k.ctx.waitTicks(4);
		var blocked = k.ai.call(k.ctx, "place_block", args("item", "oak_slab", "x", otherSlab.getX(), "y", otherSlab.getY(), "z", otherSlab.getZ(), "sneak", true));
		k.expectError(blocked, "RULE_PROTECTED_BLOCK", "T16(f) 不可蹲下疊合別人的半磚");
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(otherSlab).getValue(SlabBlock.TYPE) == SlabType.BOTTOM),
			"T16(f) 伺服器端別人的半磚應保持 bottom 不變");

		k.setBlock(pillar, Blocks.AIR.defaultBlockState());
		k.setBlock(slabPos, Blocks.AIR.defaultBlockState());
		k.setBlock(stairPos, Blocks.AIR.defaultBlockState());
		k.setBlock(hopperPos, Blocks.AIR.defaultBlockState());
		k.setBlock(chestPos, Blocks.AIR.defaultBlockState());
		k.setBlock(torchPos, Blocks.AIR.defaultBlockState());
		k.setBlock(tablePos, Blocks.AIR.defaultBlockState());
		k.setBlock(otherSlab, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] T16(f) half=top、sneak=true 貼放、放開 keyShift 與他人半磚保護");
	}
}
