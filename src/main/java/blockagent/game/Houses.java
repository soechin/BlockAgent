package blockagent.game;

import blockagent.mcp.Args;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * build_house(S4.8b)：依現場地形算出一間房子的藍圖構件。座標都相對於原點(外牆西北角、地板那一層)。
 * from/to 的 y 是屋內站立高度(地板在 y-1)；預設保留自然地面、只補低窪凹洞，指定 floor 才整層鋪地板。
 * 只產生構件，不碰世界；施工、續建和修改都走藍圖(S4.8c)。
 */
final class Houses {
	/** 每一柱的地面要在地板上下幾格內。 */
	static final int MAX_SLOPE = 3;
	static final int MIN_SIZE = 5, MAX_SIZE = 15;
	/** 新版 source 記下的 y 語意標記；舊版藍圖沒有此欄，表示 from/to 記的是地板方塊層。 */
	static final String Y_MODE_STANDING = "standing";

	record House(BlockPos origin, JsonArray parts, JsonObject source, String note) {
	}

	record FillSpan(int fromY, int toY) {
	}

	private Houses() {
	}

	/** from/to 的 y 轉成地板方塊所在的高度：現行語意是站立高度(地板在 y-1)；舊版存檔記的是地板層本身。 */
	static int floorLayerY(int fromY, int toY, boolean legacyFloorY) {
		int y = Math.min(fromY, toY);
		return legacyFloorY ? y : y - 1;
	}

	/** 省略 floor 時單柱需要補齊的地基/地面範圍；原本就是平整實心天然地面時回傳 null。 */
	static FillSpan naturalFloorFill(int groundY, int floorY, boolean baseSturdy) {
		if (groundY < floorY) return new FillSpan(groundY + 1, floorY);
		return baseSturdy ? null : new FillSpan(floorY, floorY);
	}

	static House generate(Agent agent, Minecraft mc, Args args) {
		// 舊版藍圖記下的參數叫 corner1/corner2(2026-11-23 統一成 from/to)，這次有給 from/to 時優先
		BlockPos c1 = Tools.corner(args, args.has("from") || !args.has("corner1") ? "from" : "corner1");
		BlockPos c2 = Tools.corner(args, args.has("to") || !args.has("corner2") ? "to" : "corner2");
		int height = args.integer("height", 4, 3, 8);
		String wall = blockId(args.str("material"), "material");
		String floorBlock = args.has("floor") ? blockId(args.str("floor"), "floor") : null;
		String foundation = floorBlock != null ? floorBlock : wall;
		String roof = args.has("roof") ? blockId(args.str("roof"), "roof") : wall;
		String window = args.has("window") ? blockId(args.str("window"), "window") : null;
		Direction side = side(args.str("door_side", "north"));
		int x0 = Math.min(c1.getX(), c2.getX()), x1 = Math.max(c1.getX(), c2.getX());
		int z0 = Math.min(c1.getZ(), c2.getZ()), z1 = Math.max(c1.getZ(), c2.getZ());
		int standY = Math.min(c1.getY(), c2.getY());
		int fy = floorLayerY(c1.getY(), c2.getY(), false);
		int w = x1 - x0 + 1, d = z1 - z0 + 1;
		if (w < MIN_SIZE || d < MIN_SIZE) throw new Args.BadArgument("outer walls must be at least " + MIN_SIZE + "x" + MIN_SIZE + " (3x3 inside)");
		if (w > MAX_SIZE || d > MAX_SIZE) throw new Args.BadArgument("outer walls must be at most " + MAX_SIZE + "x" + MAX_SIZE);
		if (!mc.level.isInWorldBounds(new BlockPos(x0, fy - MAX_SLOPE, z0)) || !mc.level.isInWorldBounds(new BlockPos(x0, fy + height + 1, z0))) {
			throw new Args.BadArgument("house exceeds the world height range");
		}

		// 門在那面牆的中間，門口外面那一格也要量地面
		int mx = Math.floorDiv(x0 + x1, 2), mz = Math.floorDiv(z0 + z1, 2);
		BlockPos door = switch (side) {
			case NORTH -> new BlockPos(mx, fy + 1, z0);
			case SOUTH -> new BlockPos(mx, fy + 1, z1);
			case WEST -> new BlockPos(x0, fy + 1, mz);
			default -> new BlockPos(x1, fy + 1, mz);
		};
		BlockPos step = door.relative(side).atY(fy);

		Map<Long, Integer> ground = new HashMap<>();
		int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
		int top = fy + height + 1;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				int g = ground(agent, mc, x, z, top, fy);
				ground.put(key(x, z), g);
				lowest = Math.min(lowest, g);
				highest = Math.max(highest, g);
			}
		}
		int stepGround = ground(agent, mc, step.getX(), step.getZ(), top, fy);
		lowest = Math.min(lowest, stepGround);
		highest = Math.max(highest, stepGround);
		if (lowest < fy - MAX_SLOPE || highest > fy + MAX_SLOPE) {
			// lowest 低於下限時只代表「往下 3 格都沒找到地面」，不是真的高度
			String low = lowest < fy - MAX_SLOPE ? "ground is more than " + MAX_SLOPE + " below the floor in places" : null;
			String high = highest > fy + MAX_SLOPE ? "ground is more than " + MAX_SLOPE + " above the floor in places (highest y=" + highest + ")" : null;
			String what = low != null && high != null ? low + "; " + high : low != null ? low : high;
			throw new Args.BadArgument("terrain too uneven: " + what + ". Every column's ground must be within " + MAX_SLOPE
				+ " of floor y=" + fy + " (standing y=" + standY + "); pick flatter land or set from/to y to your standing level");
		}

		String doorItem = null, note = null;
		if (args.has("door") && args.str("door").equals("none")) {
			doorItem = null;
		} else if (args.has("door")) {
			doorItem = doorId(args.str("door"));
		} else {
			// 門口已經裝了木門(例如重算時)就沿用那扇，不然背包裡沒有門時會把裝好的門拆掉
			BlockState installed = mc.level.getBlockState(door);
			int slot = Inv.find(mc.player, st -> st.is(ItemTags.WOODEN_DOORS));
			if (installed.getBlock() instanceof DoorBlock && installed.getBlock().asItem().getDefaultInstance().is(ItemTags.WOODEN_DOORS)) {
				doorItem = BuiltInRegistries.ITEM.getKey(installed.getBlock().asItem()).toString();
			} else if (slot >= 0) {
				doorItem = Inv.id(mc.player.getInventory().getItem(slot).getItem());
			} else {
				note = "note=\"no wooden door in inventory; left an opening. Craft a door, then call build_house with the same id\"";
			}
		}

		var parts = new Parts(x0, fy, z0);
		// 1. 地基與地板：指定 floor 時整層鋪滿並補下方空隙；省略時保留自然實心地面，只補低窪凹洞或非實心表層
		if (floorBlock != null) {
			for (int z = z0; z <= z1; z++) {
				int x = x0;
				while (x <= x1) {
					int g = ground.get(key(x, z));
					int end = x;
					while (end + 1 <= x1 && ground.get(key(end + 1, z)) == g) end++;
					if (g < fy - 1) parts.add(x, g + 1, z, end, fy - 1, z, floorBlock);
					x = end + 1;
				}
			}
			parts.add(x0, fy, z0, x1, fy, z1, floorBlock);
		} else {
			for (int z = z0; z <= z1; z++) {
				int x = x0;
				while (x <= x1) {
					FillSpan span = naturalFillAt(mc, x, z, ground.get(key(x, z)), fy);
					int end = x;
					while (end + 1 <= x1 && java.util.Objects.equals(naturalFillAt(mc, end + 1, z, ground.get(key(end + 1, z)), fy), span)) {
						end++;
					}
					if (span != null) parts.add(x, span.fromY(), z, end, span.toY(), z, foundation);
					x = end + 1;
				}
			}
		}
		// 2. 室內留空 3. 牆
		parts.add(x0 + 1, fy + 1, z0 + 1, x1 - 1, fy + height, z1 - 1, "air");
		parts.add(x0, fy + 1, z0, x1, fy + height, z0, wall);
		parts.add(x0, fy + 1, z1, x1, fy + height, z1, wall);
		parts.add(x0, fy + 1, z0 + 1, x0, fy + height, z1 - 1, wall);
		parts.add(x1, fy + 1, z0 + 1, x1, fy + height, z1 - 1, wall);
		// 4. 窗戶：門以外的三面牆，牆的中間、地板上 2 格
		if (window != null) {
			for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
				if (dir == side) continue;
				int wx = switch (dir) {
					case WEST -> x0;
					case EAST -> x1;
					default -> mx;
				};
				int wz = switch (dir) {
					case NORTH -> z0;
					case SOUTH -> z1;
					default -> mz;
				};
				parts.add(wx, fy + 2, wz, wx, fy + 2, wz, window);
			}
		}
		// 5. 屋頂
		parts.add(x0, top, z0, x1, top, z1, roof);
		// 6. 門口(上半格先留空，門的上半格會自己長出來)，門朝外
		parts.add(door.getX(), fy + 1, door.getZ(), door.getX(), fy + 2, door.getZ(), "air");
		if (doorItem != null) {
			parts.add(door.getX(), fy + 1, door.getZ(), door.getX(), fy + 1, door.getZ(),
				doorItem + "[facing=" + side.getSerializedName() + "]");
		}
		// 門口外面那一格：比地板低就補到地板那層；比地板高就挖出兩格高的出口
		if (stepGround < fy) parts.add(step.getX(), stepGround + 1, step.getZ(), step.getX(), fy, step.getZ(), foundation);
		else if (stepGround > fy) parts.add(step.getX(), fy + 1, step.getZ(), step.getX(), fy + 2, step.getZ(), "air");

		JsonObject source = new JsonObject();
		source.addProperty("from", x0 + "," + standY + "," + z0);
		source.addProperty("to", x1 + "," + standY + "," + z1);
		source.addProperty("y_mode", Y_MODE_STANDING);
		source.addProperty("height", height);
		source.addProperty("material", wall);
		// 沒指定的地板、屋頂不存：省略 floor 代表保留自然地面；省略 roof 在換 material 時跟著牆換(M12)
		if (floorBlock != null) source.addProperty("floor", floorBlock);
		if (args.has("roof")) source.addProperty("roof", roof);
		source.addProperty("door_side", side.getSerializedName());
		if (args.has("door")) source.addProperty("door", args.str("door"));
		if (window != null) source.addProperty("window", window);
		return new House(new BlockPos(x0, fy, z0), parts.json, source, note);
	}

	private static FillSpan naturalFillAt(Minecraft mc, int x, int z, int groundY, int floorY) {
		if (groundY < floorY) return naturalFloorFill(groundY, floorY, false);
		BlockPos base = new BlockPos(x, floorY, z);
		BlockState st = mc.level.getBlockState(base);
		boolean sturdy = !st.isAir() && !st.canBeReplaced() && st.isFaceSturdy(mc.level, base, Direction.UP);
		return naturalFloorFill(groundY, floorY, sturdy);
	}

	/**
	 * 一柱的地面：從屋頂那層往下找第一個天然的實心方塊。原木、樹葉、花草不算，人造的方塊也不算
	 * (重算時舊房子還在，屋裡也可能有別人放的東西)。
	 * 找到地板下 MAX_SLOPE 格都沒有就回傳再低一格，讓呼叫端報「高低差太大」。
	 */
	private static int ground(Agent agent, Minecraft mc, int x, int z, int top, int fy) {
		var level = mc.level;
		for (int y = top; y >= fy - MAX_SLOPE; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			if (!AreaInspection.loaded(level, pos)) throw new Args.BadArgument("chunks around " + x + ",~," + z + " are not loaded; goto closer first");
			BlockState st = level.getBlockState(pos);
			if (!st.getFluidState().isEmpty()) {
				throw new Args.BadArgument(blockagent.game.Fmt.pos(pos) + " has water or lava; a house cannot stand on liquid, pick another spot");
			}
			if (st.isAir() || st.canBeReplaced() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)
				|| st.getDestroySpeed(level, pos) == 0f || agent.guard.isOwned(pos) || !agent.guard.isNatural(st)) continue;
			return y;
		}
		return fy - MAX_SLOPE - 1;
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	private static Direction side(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "north" -> Direction.NORTH;
			case "south" -> Direction.SOUTH;
			case "east" -> Direction.EAST;
			case "west" -> Direction.WEST;
			default -> throw new Args.BadArgument("door_side must be north, south, east or west");
		};
	}

	private static String blockId(String raw, String name) {
		Item item = Tools.parseItem(raw);
		if (!(item instanceof BlockItem bi)) throw new Args.BadArgument(name + ": " + Inv.id(item) + " is not a block");
		// 門、床、高草這類一次佔兩格的方塊不能拿來鋪牆、地板、屋頂(M12)
		var def = bi.getBlock().defaultBlockState();
		if (def.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
			|| def.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)) {
			throw new Args.BadArgument(name + ": " + Inv.id(item) + " takes two blocks; use a single-block material");
		}
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private static String doorId(String raw) {
		Item item = Tools.parseItem(raw);
		if (!(item instanceof BlockItem bi) || !(bi.getBlock() instanceof DoorBlock) || !item.getDefaultInstance().is(ItemTags.WOODEN_DOORS)) {
			throw new Args.BadArgument("door must be a wooden door (e.g. oak_door) or none for an opening; iron doors need redstone");
		}
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/** 依序收集構件(後面的蓋過前面的)，座標換成相對原點。 */
	private static final class Parts {
		final JsonArray json = new JsonArray();
		final int ox, oy, oz;

		Parts(int ox, int oy, int oz) {
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
		}

		void add(int xa, int ya, int za, int xb, int yb, int zb, String block) {
			if (xa > xb || ya > yb || za > zb) return; // 空的範圍(例如室內只有一格寬時)
			JsonObject part = new JsonObject();
			part.addProperty("from", (xa - ox) + "," + (ya - oy) + "," + (za - oz));
			if (xa != xb || ya != yb || za != zb) part.addProperty("to", (xb - ox) + "," + (yb - oy) + "," + (zb - oz));
			part.addProperty("block", block);
			json.add(part);
		}
	}
}
