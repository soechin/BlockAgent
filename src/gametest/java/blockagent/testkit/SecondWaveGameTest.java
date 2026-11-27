package blockagent.testkit;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;

import static blockagent.testkit.McpTestClient.args;
import static blockagent.testkit.TestKit.check;

/**
 * T17(b)–(f)：第二棒中盤挖礦與附魔修裝的遊戲測試(docs/SPEC.md S4.2e–S4.2j)。
 */
@SuppressWarnings("UnstableApiUsage")
public class SecondWaveGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestKit.skip("SecondWaveGameTest")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestKit k = new TestKit(ctx, sp);
			BlockPos c = k.myPos();
			k.flatArena(c, 18);
			k.start();
			testEnchant(k, c);
			testAnvil(k, c);
			testGrindstone(k, c);
			testToolMatch(k, c);
			testSmeltCharcoalFallbackAndDigTunnel(k, c);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static String finishTask(TestKit k, McpTestClient.Result r) {
		check(r.ok(), "任務啟動失敗：" + r.text());
		if (r.text().contains("WAIT task_running")) {
			return r.text() + "\n" + k.eventsUntil("task_done:", 25);
		}
		return r.text();
	}

	/** T17(b)：附魔台 preview(無書架 vs 15 書架 max_level=30)、收包不掉物、等級/青金石不足與 apply 附魔。 */
	private void testEnchant(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();
		BlockPos table = c.east(3);
		k.setBlock(table, Blocks.ENCHANTING_TABLE.defaultBlockState());
		k.cmd("xp set @p 0 levels");
		k.cmd("xp set @p 0 points");
		k.onServer(s -> k.me(s).getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE)));
		k.ctx.waitTicks(4);

		// 無書架預覽：應回 offers= 與 max_level(<30)，並把物品收進背包
		String preNoBooks = finishTask(k, k.ai.call(k.ctx, "enchant", args("action", "preview", "item", "diamond_pickaxe")));
		check(preNoBooks.contains("offers=") && preNoBooks.contains("max_level=") && !preNoBooks.contains("max_level=30"),
			"T17(b) 無書架附魔台預覽應低於 30 級：" + preNoBooks);
		check(k.server.computeOnServer(s -> k.me(s).getInventory().countItem(Items.DIAMOND_PICKAXE) == 1),
			"T17(b) 預覽結束後鑽石鎬應收回背包");

		// 擺出 15 座書架(5×5 外框留空 1 格，y 與 y+1 層)
		int placed = 0;
		for (int dy = 0; dy <= 1 && placed < 15; dy++) {
			for (int dx = -2; dx <= 2 && placed < 15; dx++) {
				for (int dz = -2; dz <= 2 && placed < 15; dz++) {
					if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
					if (dx == -2 && dz == 0) continue; // 留出西側入口讓玩家走近
					k.setBlock(table.offset(dx, dy, dz), Blocks.BOOKSHELF.defaultBlockState());
					placed++;
				}
			}
		}
		k.ctx.waitTicks(4);

		String pre30 = finishTask(k, k.ai.call(k.ctx, "enchant", args("action", "preview", "item", "diamond_pickaxe")));
		check(pre30.contains("max_level=30"), "T17(b) 15 書架附魔台預覽應達 max_level=30：" + pre30);

		// 缺青金石：事前檢查回 MISSING_MATERIALS
		var noLapis = k.ai.call(k.ctx, "enchant", args("action", "apply", "item", "diamond_pickaxe", "option", 3));
		k.expectError(noLapis, "MISSING_MATERIALS", "T17(b) 沒有青金石應回 MISSING_MATERIALS");

		// 有青金石但 0 級：任務中檢查等級不足回 MISSING_MATERIALS
		k.give("minecraft:lapis_lazuli", 6);
		var lowLv = k.ai.call(k.ctx, "enchant", args("action", "apply", "item", "diamond_pickaxe", "option", 3));
		String lowLvText = lowLv.text().contains("WAIT task_running") ? k.eventsUntil("task_failed:", 15) : lowLv.text();
		check(lowLvText.contains("MISSING_MATERIALS") && lowLvText.contains("need_level="),
			"T17(b) 等級不足應以 MISSING_MATERIALS 失敗：" + lowLvText);

		// 給 30 級執行 option=3 附魔
		k.cmd("xp set @p 30 levels");
		k.ctx.waitTicks(4);
		String applied = finishTask(k, k.ai.call(k.ctx, "enchant", args("action", "apply", "item", "diamond_pickaxe", "option", 3)));
		check(applied.contains("result=") && applied.contains("diamond_pickaxe") && applied.contains("ench="),
			"T17(b) 附魔成功應回報含附魔的 result=：" + applied);
		check(k.server.computeOnServer(s -> {
			var p = k.me(s);
			boolean hasEnch = false;
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (st.is(Items.DIAMOND_PICKAXE) && !st.getEnchantments().isEmpty()) hasEnch = true;
			}
			return hasEnch && p.experienceLevel < 30 && p.getInventory().countItem(Items.LAPIS_LAZULI) == 3;
		}), "T17(b) 伺服器端應有附魔鑽石鎬且消耗等級與 3 個青金石");

		// 清理場地
		for (int dy = 0; dy <= 1; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					k.setBlock(table.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
				}
			}
		}
		System.out.println("[ok] T17(b) 附魔台預覽(30級書架)、缺料/缺等級與執行附魔");
	}

	/** T17(c)：鐵砧預覽/合書/修補/命名牌改名、等級不足與 Too Expensive，且允許使用他人鐵砧。 */
	private void testAnvil(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();
		BlockPos anvilPos = c.north(3);
		// 直接由伺服器放置(owner=other)，驗證允許使用他人鐵砧
		k.setBlock(anvilPos, Blocks.ANVIL.defaultBlockState());
		k.cmd("xp set @p 20 levels");
		k.onServer(s -> {
			var p = k.me(s);
			var enchReg = s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
			p.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
			ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
			ItemEnchantments.Mutable mut = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			mut.set(enchReg.getOrThrow(Enchantments.EFFICIENCY), 3);
			book.set(DataComponents.STORED_ENCHANTMENTS, mut.toImmutable());
			p.getInventory().add(book);

			ItemStack damagedIron = new ItemStack(Items.IRON_PICKAXE);
			damagedIron.setDamageValue(120);
			p.getInventory().add(damagedIron);
			p.getInventory().add(new ItemStack(Items.IRON_INGOT, 4));
			p.getInventory().add(new ItemStack(Items.NAME_TAG, 1));
		});
		k.ctx.waitTicks(4);

		// 預覽合書
		String pre = finishTask(k, k.ai.call(k.ctx, "anvil",
			args("action", "preview", "item", "diamond_pickaxe", "second_item", "enchanted_book", "second_match", "efficiency")));
		check(pre.contains("owner=other") && pre.contains("cost=") && pre.contains("too_expensive=false"),
			"T17(c) 鐵砧預覽應回報 owner=other、cost 與 too_expensive=false：" + pre);

		// 執行合書
		String merged = finishTask(k, k.ai.call(k.ctx, "anvil",
			args("action", "apply", "item", "diamond_pickaxe", "second_item", "enchanted_book", "second_match", "efficiency")));
		check(merged.contains("result=") && merged.contains("diamond_pickaxe") && merged.contains("efficiency:3"),
			"T17(c) 鐵砧合書應產出效率 III 鑽石鎬：" + merged);

		// 確保鐵砧還在(萬一 12% 碎掉先補一座供後續驗證)
		k.setBlock(anvilPos, Blocks.ANVIL.defaultBlockState());
		k.ctx.waitTicks(2);

		// 用鐵錠修補殘血鐵鎬
		String repaired = finishTask(k, k.ai.call(k.ctx, "anvil",
			args("action", "apply", "item", "iron_pickaxe", "second_item", "iron_ingot")));
		check(repaired.contains("result=") && repaired.contains("iron_pickaxe"), "T17(c) 鐵砧修補應成功：" + repaired);
		check(k.server.computeOnServer(s -> {
			var p = k.me(s);
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (st.is(Items.IRON_PICKAXE) && st.getDamageValue() < 120) return true;
			}
			return false;
		}), "T17(c) 伺服器端鐵鎬耐久應回復");

		k.setBlock(anvilPos, Blocks.ANVIL.defaultBlockState());
		k.ctx.waitTicks(2);

		// 幫命名牌改名
		String renamed = finishTask(k, k.ai.call(k.ctx, "anvil",
			args("action", "apply", "item", "name_tag", "rename", "Lucky")));
		check(renamed.contains("Lucky"), "T17(c) 鐵砧改名命名牌應回報 Lucky：" + renamed);
		check(k.server.computeOnServer(s -> {
			var p = k.me(s);
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				var name = st.get(DataComponents.CUSTOM_NAME);
				if (st.is(Items.NAME_TAG) && name != null && "Lucky".equals(name.getString())) return true;
			}
			return false;
		}), "T17(c) 伺服器端命名牌應已改名為 Lucky");

		// Too Expensive 驗證(純改名在原版會封頂 39 級，須搭配修補或合併才會 >= 40)
		k.setBlock(anvilPos, Blocks.ANVIL.defaultBlockState());
		k.cmd("xp set @p 50 levels");
		k.onServer(s -> {
			var p = k.me(s);
			ItemStack expSword = new ItemStack(Items.GOLDEN_SWORD);
			expSword.setDamageValue(20);
			expSword.set(DataComponents.REPAIR_COST, 40);
			p.getInventory().add(expSword);
			p.getInventory().add(new ItemStack(Items.GOLD_INGOT, 1));
		});
		k.ctx.waitTicks(4);
		var tooExp = k.ai.call(k.ctx, "anvil",
			args("action", "apply", "item", "golden_sword", "second_item", "gold_ingot"));
		String tooExpText = tooExp.text().contains("WAIT task_running") ? k.eventsUntil("task_failed:", 15) : tooExp.text();
		check(tooExpText.contains("INVALID_ARGUMENT") && tooExpText.contains("too expensive"),
			"T17(c) 超過 39 級應以 too expensive 拒絕：" + tooExpText);

		k.setBlock(anvilPos, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] T17(c) 鐵砧預覽、合書、修補、命名牌改名、他人鐵砧與 Too Expensive");
	}

	/** T17(d)：砂輪單件拆附魔回經驗與兩件同種裝備合修。 */
	private void testGrindstone(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();
		BlockPos gs = c.south(3);
		k.setBlock(gs, Blocks.GRINDSTONE.defaultBlockState());
		k.cmd("xp set @p 0 levels");
		k.cmd("xp set @p 0 points");
		k.onServer(s -> {
			var p = k.me(s);
			var enchReg = s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
			ItemStack sword = new ItemStack(Items.IRON_SWORD);
			sword.enchant(enchReg.getOrThrow(Enchantments.SHARPNESS), 3);
			p.getInventory().add(sword);

			ItemStack p1 = new ItemStack(Items.IRON_PICKAXE);
			p1.setDamageValue(180);
			ItemStack p2 = new ItemStack(Items.IRON_PICKAXE);
			p2.setDamageValue(180);
			p.getInventory().add(p1);
			p.getInventory().add(p2);
		});
		k.ctx.waitTicks(4);

		String disench = finishTask(k, k.ai.call(k.ctx, "grindstone", args("item", "iron_sword", "match", "sharpness")));
		check(disench.contains("result=") && disench.contains("iron_sword") && !disench.contains("sharpness"),
			"T17(d) 砂輪拆附魔應回報無附魔的鐵劍：" + disench);
		check(k.server.computeOnServer(s -> {
			var p = k.me(s);
			boolean cleanSword = false;
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (st.is(Items.IRON_SWORD) && st.getEnchantments().isEmpty()) cleanSword = true;
			}
			return cleanSword && (p.totalExperience > 0 || p.experienceLevel > 0 || p.experienceProgress > 0f);
		}), "T17(d) 伺服器端應移除附魔並吸收經驗");

		String combine = finishTask(k, k.ai.call(k.ctx, "grindstone",
			args("item", "iron_pickaxe", "second_item", "iron_pickaxe")));
		check(combine.contains("result=") && combine.contains("iron_pickaxe"), "T17(d) 砂輪合修兩把鐵鎬應成功：" + combine);
		check(k.server.computeOnServer(s -> {
			var p = k.me(s);
			int count = 0;
			boolean repaired = false;
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (st.is(Items.IRON_PICKAXE)) {
					count++;
					if (st.getDamageValue() < 150) repaired = true;
				}
			}
			return count == 1 && repaired;
		}), "T17(d) 合修後應只剩 1 把且耐久顯著提升");

		k.setBlock(gs, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] T17(d) 砂輪拆附魔回經驗與兩件合修");
	}

	/** T17(e)：break_block 與 mine 的 tool_match(silk_touch / fortune)，且開路普通石頭不誤用時運鎬。 */
	private void testToolMatch(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();
		BlockPos icePos = c.east(2);
		k.setBlock(icePos, Blocks.PACKED_ICE.defaultBlockState());
		k.onServer(s -> {
			var p = k.me(s);
			var enchReg = s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
			ItemStack plain = new ItemStack(Items.DIAMOND_PICKAXE);
			ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
			silk.enchant(enchReg.getOrThrow(Enchantments.SILK_TOUCH), 1);
			ItemStack fortune = new ItemStack(Items.DIAMOND_PICKAXE);
			fortune.enchant(enchReg.getOrThrow(Enchantments.FORTUNE), 3);
			p.getInventory().add(plain);
			p.getInventory().add(silk);
			p.getInventory().add(fortune);
		});
		k.ctx.waitTicks(4);

		// 缺少指定附魔工具回 MISSING_TOOL
		var noMending = k.ai.call(k.ctx, "break_block",
			args("x", icePos.getX(), "y", icePos.getY(), "z", icePos.getZ(), "tool_match", "mending"));
		k.expectError(noMending, "MISSING_TOOL", "T17(e) 沒有修補工具應回 MISSING_TOOL");

		// silk_touch 挖藍冰/冰磚(天然方塊)：應使用絲綢鎬(耐久損 1)
		String brk = finishTask(k, k.ai.call(k.ctx, "break_block",
			args("x", icePos.getX(), "y", icePos.getY(), "z", icePos.getZ(), "tool_match", "silk_touch")));
		check(brk.contains("broken"), "T17(e) silk_touch break_block 應成功：" + brk);
		check(k.server.computeOnServer(s -> {
			var p = k.me(s);
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (st.is(Items.DIAMOND_PICKAXE)
					&& st.getEnchantments().entrySet().stream().anyMatch(e -> e.getKey().getRegisteredName().endsWith("silk_touch"))) {
					return st.getDamageValue() == 1;
				}
			}
			return false;
		}), "T17(e) 應使用絲綢之觸鎬挖除冰磚");

		// mine 指定 fortune 挖鑽石礦：前方放石頭，驗證時運鎬只消耗在目標鑽石礦上(最多 2 點耐久)
		BlockPos stone1 = c.west(2);
		BlockPos stone2 = c.west(2).above();
		BlockPos dia1 = c.west(3);
		BlockPos dia2 = c.west(3).above();
		k.setBlock(stone1, Blocks.STONE.defaultBlockState());
		k.setBlock(stone2, Blocks.STONE.defaultBlockState());
		k.setBlock(dia1, Blocks.DIAMOND_ORE.defaultBlockState());
		k.setBlock(dia2, Blocks.DIAMOND_ORE.defaultBlockState());
		k.ctx.waitTicks(4);

		String mined = finishTask(k, k.ai.call(k.ctx, "mine",
			args("block", "diamond_ore", "count", 2, "radius", 16, "tool_match", "fortune")));
		check(mined.contains("mined=2/2"), "T17(e) mine(fortune) 應挖完 2 格鑽石礦：" + mined);
		check(k.server.computeOnServer(s -> {
			var p = k.me(s);
			boolean diamonds = p.getInventory().countItem(Items.DIAMOND) >= 2;
			int fortuneDmg = -1;
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (st.is(Items.DIAMOND_PICKAXE)
					&& st.getEnchantments().entrySet().stream().anyMatch(e -> e.getKey().getRegisteredName().endsWith("fortune"))) {
					fortuneDmg = st.getDamageValue();
				}
			}
			return diamonds && fortuneDmg > 0 && fortuneDmg <= 2;
		}), "T17(e) 時運鎬應只用在鑽石礦(耐久損耗 1–2)且取得鑽石");

		k.setBlock(stone1, Blocks.AIR.defaultBlockState());
		k.setBlock(stone2, Blocks.AIR.defaultBlockState());
		k.setBlock(dia1, Blocks.AIR.defaultBlockState());
		k.setBlock(dia2, Blocks.AIR.defaultBlockState());
		System.out.println("[ok] T17(e) break_block 與 mine 的 tool_match(silk_touch / fortune)");
	}

	/** T17(f)：smelt 同料剩餘自動選燃料(原木燒木炭)與 dig_tunnel 全場景(護欄、火把、礦脈、岩漿、保護、階梯)。 */
	private void testSmeltCharcoalFallbackAndDigTunnel(TestKit k, BlockPos c) {
		k.cmd("tp @p " + TestKit.pos(c));
		k.clearInventory();

		// 1. smelt 同料剩餘自動燃料：只帶 6 個橡木原木(無木板/煤炭)燒 2 個木炭
		BlockPos fur = c.north(2);
		k.setBlock(fur, Blocks.FURNACE.defaultBlockState());
		k.give("minecraft:oak_log", 6);
		k.ctx.waitTicks(4);
		String charcoal = finishTask(k, k.ai.call(k.ctx, "smelt", args("item", "charcoal", "count", 2)));
		check(charcoal.contains("got=2/2"), "T17(f) 只帶原木燒木炭應自動以剩餘原木為燃料：" + charcoal);
		check(k.server.computeOnServer(s -> k.me(s).getInventory().countItem(Items.CHARCOAL) == 2),
			"T17(f) 伺服器端應取得 2 個木炭");
		k.setBlock(fur, Blocks.AIR.defaultBlockState());

		// 2. dig_tunnel 火把護欄測試
		k.clearInventory();
		k.give("minecraft:diamond_pickaxe", 1);
		var noTorch = k.ai.call(k.ctx, "dig_tunnel", args("mode", "straight", "direction", "east", "length", 4, "torch_every", 8));
		k.expectError(noTorch, "MISSING_MATERIALS", "T17(f) torch_every=8 無火把應回 MISSING_MATERIALS");
		check(noTorch.text().contains("charcoal"), "T17(f) 缺火把提示應提及木炭：" + noTorch.text());

		var tooLongUnlit = k.ai.call(k.ctx, "dig_tunnel", args("mode", "straight", "direction", "east", "length", 13, "torch_every", 0));
		k.expectError(tooLongUnlit, "INVALID_ARGUMENT", "T17(f) torch_every=0 超過 12 格應回 INVALID_ARGUMENT");

		// 3. 在平坦場地上方建造封閉石頭山體(超平坦地面為 y=-60，往上抬高避免撞到基岩 y=-64)
		BlockPos base = c.offset(0, 4, -10);
		// 蓋出一座石塊量體：x in [base.x-1..base.x+14], y in [base.y-3..base.y+4], z in [base.z-2..base.z+2]
		k.onServer(s -> {
			var lvl = s.overworld();
			for (int dx = -1; dx <= 14; dx++) {
				for (int dy = -3; dy <= 4; dy++) {
					for (int dz = -2; dz <= 2; dz++) {
						lvl.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
					}
				}
			}
			// 清出起點站立室 (base, base.above)
			lvl.setBlockAndUpdate(base, Blocks.AIR.defaultBlockState());
			lvl.setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
			// 在第 2 格北牆放鐵礦(測 ores=)，在第 6 格正前方放鑽石礦(讓第 4 格挖完插火把，第 5 格前瞻遇鑽石停下)，第 8 格放岩漿，第 10 格放他人木板
			lvl.setBlockAndUpdate(base.offset(2, 0, -1), Blocks.IRON_ORE.defaultBlockState());
			lvl.setBlockAndUpdate(base.offset(6, 0, 0), Blocks.DIAMOND_ORE.defaultBlockState());
			lvl.setBlockAndUpdate(base.offset(8, 0, 0), Blocks.LAVA.defaultBlockState());
			lvl.setBlockAndUpdate(base.offset(10, 0, 0), Blocks.OAK_PLANKS.defaultBlockState());
		});
		k.cmd("tp @p " + base.getX() + " " + base.getY() + " " + base.getZ());
		k.ctx.waitTicks(6);

		// 3a. 無火把短距 3 格：應挖完 3 格並回報 unlit= 與沿途 ores=iron_ore
		String unlit = finishTask(k, k.ai.call(k.ctx, "dig_tunnel",
			args("mode", "straight", "direction", "east", "length", 3, "torch_every", 0)));
		check(unlit.contains("dug=3/3") && unlit.contains("unlit=") && unlit.contains("iron_ore"),
			"T17(f) 無火把短距 3 格應回報 dug=3/3、unlit= 與 iron_ore：" + unlit);

		// 3b. 給火把，往前挖並設 stop_on=diamond_ore：應先挖到第 4 格並插火把，接著在第 6 格鑽石礦前停下(stopped=ore)
		k.give("minecraft:torch", 8);
		k.ctx.waitTicks(4);
		String stopOre = finishTask(k, k.ai.call(k.ctx, "dig_tunnel",
			args("mode", "straight", "direction", "east", "length", 6, "torch_every", 1, "stop_on", "diamond_ore")));
		check(stopOre.contains("stopped=ore") && stopOre.contains("diamond_ore"),
			"T17(f) stop_on=diamond_ore 應在動鎬前停下：" + stopOre);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(base.offset(6, 0, 0)).is(Blocks.DIAMOND_ORE)),
			"T17(f) 鑽石礦應完整保留未被盲挖");
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(base.offset(4, 0, 0)).is(Blocks.TORCH)),
			"T17(f) 封閉暗礦道應在第 4 格自動插下火把：" + stopOre);

		// 3c. 把鑽石礦改成石頭，繼續往岩漿方向挖：應在岩漿前安全停下(stopped=hazard hazard=lava)
		k.setBlock(base.offset(6, 0, 0), Blocks.STONE.defaultBlockState());
		k.ctx.waitTicks(4);
		String stopLava = finishTask(k, k.ai.call(k.ctx, "dig_tunnel",
			args("mode", "straight", "direction", "east", "length", 6, "torch_every", 4)));
		check(stopLava.contains("stopped=hazard") && stopLava.contains("hazard=lava"),
			"T17(f) 遇岩漿應事前安全停下：" + stopLava);

		// 3d. 把岩漿改成石頭，繼續往他人橡木板方向挖：應回 RULE_PROTECTED_BLOCK 且木板保留
		k.setBlock(base.offset(8, 0, 0), Blocks.STONE.defaultBlockState());
		k.ctx.waitTicks(4);
		var hitPlanks = k.ai.call(k.ctx, "dig_tunnel",
			args("mode", "straight", "direction", "east", "length", 6, "torch_every", 4));
		String plankText = hitPlanks.text().contains("WAIT task_running") ? k.eventsUntil("task_failed:", 20) : hitPlanks.text();
		check(plankText.contains("RULE_PROTECTED_BLOCK"),
			"T17(f) 遇他人方塊應以 RULE_PROTECTED_BLOCK 停下：" + plankText);
		check(k.server.computeOnServer(s -> s.overworld().getBlockState(base.offset(10, 0, 0)).is(Blocks.OAK_PLANKS)),
			"T17(f) 他人橡木板應保持完整");

		// 4. stairs 階梯下挖 3 格(從高處乾淨起點下挖至 y-3，保留離基岩的安全距離)
		BlockPos stairStart = c.offset(-10, 6, -10);
		k.onServer(s -> {
			var lvl = s.overworld();
			for (int dx = -1; dx <= 6; dx++) {
				for (int dy = -5; dy <= 3; dy++) {
					for (int dz = -2; dz <= 2; dz++) {
						lvl.setBlockAndUpdate(stairStart.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
					}
				}
			}
			lvl.setBlockAndUpdate(stairStart, Blocks.AIR.defaultBlockState());
			lvl.setBlockAndUpdate(stairStart.above(), Blocks.AIR.defaultBlockState());
		});
		k.cmd("tp @p " + stairStart.getX() + " " + stairStart.getY() + " " + stairStart.getZ());
		k.ctx.waitTicks(6);

		int targetY = stairStart.getY() - 3;
		String stairs = finishTask(k, k.ai.call(k.ctx, "dig_tunnel",
			args("mode", "stairs", "direction", "east", "length", 10, "target_y", targetY, "torch_every", 2)));
		check(stairs.contains("dug=3/3") && stairs.contains("stopped=completed"),
			"T17(f) 階梯下挖應精準挖 3 步到達 target_y：" + stairs);
		check(k.server.computeOnServer(s -> k.me(s).blockPosition().equals(stairStart.offset(3, -3, 0))),
			"T17(f) 玩家應位於階梯終點座標");
		System.out.println("[ok] T17(f) smelt 原木燒木炭與 dig_tunnel 全場景");
	}
}
