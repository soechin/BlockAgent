package blockagent.game;

import blockagent.core.ItemMatcher;
import blockagent.core.PortalPlanner;
import blockagent.game.tasks.BuildTask;
import blockagent.game.tasks.CommandBuildTask;
import blockagent.game.tasks.NetherPortalTask;
import blockagent.game.tasks.Task;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import blockagent.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** nether_portal 工具(docs/SPEC.md S4.2k)：事前檢查，通過後交給 {@link NetherPortalTask}。 */
final class NetherPortals {
	/** enter 沒給座標時找傳送門的範圍。 */
	static final int SEARCH_RADIUS = 16;
	/** 四個角落墊腳用的方塊，依序挑背包裡有的(原版不在乎角落是什麼)。 */
	private static final List<Item> CORNER_FILLERS = List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK,
		Items.BLACKSTONE, Items.STONE, Items.DEEPSLATE, Items.DIRT, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS);
	private static final BuildTarget OBSIDIAN = new BuildTarget(Blocks.OBSIDIAN.defaultBlockState(), Set.of());

	private NetherPortals() {
	}

	/**
	 * @param origin 框架左下角(底框那一層)；null 時放在面前 3 格、正對自己
	 * @param axis   "x"、"z" 或 null(依面向)
	 */
	static ToolResult build(Agent a, Minecraft mc, BlockPos origin, String axis, boolean replace) {
		LocalPlayer p = mc.player;
		if (mc.level.dimension().equals(Level.END)) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("dim", "the_end"), "nether portals do not light in the End");
		}
		Direction facing = p.getDirection();
		boolean alongX = axis == null ? facing.getAxis() == Direction.Axis.Z : axis.equals("x");
		if (origin == null) {
			BlockPos front = p.blockPosition().relative(facing, 3);
			origin = alongX ? front.offset(-1, 0, 0) : front.offset(0, 0, -1);
		}
		var layout = PortalPlanner.layout(origin.getX(), origin.getY(), origin.getZ(), alongX);
		String axisName = alongX ? "x" : "z";
		BlockPos base = pos(layout.base());
		Kv at = Kv.of().put("origin", Fmt.pos(origin)).put("axis", axisName);
		Item igniter = igniter(p);
		if (igniter == null) {
			return ToolResult.error(ErrorCode.MISSING_TOOL, at.put("need", "flint_and_steel"), "no flint_and_steel or fire_charge",
				"craft flint_and_steel (iron_ingot + flint)");
		}
		if (mc.level.getBlockState(base.above()).is(Blocks.NETHER_PORTAL)) {
			return ToolResult.ok(Kv.of("OK done").put("task", "nether_portal").put("portal", Fmt.pos(base.above()))
				.put("axis", axisName).put("note", "already lit"));
		}
		Map<BlockPos, BuildTarget> cells = new LinkedHashMap<>();
		for (var c : layout.frame()) cells.put(pos(c), OBSIDIAN);
		for (var c : layout.interior()) cells.put(pos(c), BuildPreflight.AIR);
		// 角落已經是實心方塊就留著；空著的才補，讓上框和側框有地方貼
		List<BlockPos> openCorners = new ArrayList<>();
		for (var c : layout.corners()) {
			BlockState st = mc.level.getBlockState(pos(c));
			if (st.isAir() || st.canBeReplaced()) openCorners.add(pos(c));
		}
		if (!openCorners.isEmpty()) {
			int obsidianNeeded = 0;
			for (var c : layout.frame()) if (!mc.level.getBlockState(pos(c)).is(Blocks.OBSIDIAN)) obsidianNeeded++;
			Item filler = cornerFiller(mc, openCorners.size(), obsidianNeeded);
			if (filler == null) {
				return ToolResult.error(ErrorCode.MISSING_MATERIALS, at.put("missing", "cobblestone:" + openCorners.size()),
					"the frame corners need " + openCorners.size() + " plain blocks to build against",
					"carry cobblestone, netherrack, dirt or similar (or 4 extra obsidian)");
			}
			BuildTarget corner = new BuildTarget(((net.minecraft.world.item.BlockItem) filler).getBlock().defaultBlockState(), Set.of());
			for (BlockPos c : openCorners) cells.put(c, corner);
		}
		BlockPos min = pos(layout.corners().get(0)), max = pos(layout.corners().get(3));
		var design = new BuildPreflight.Design(cells, AreaInspection.Region.between(min, max), null, Set.of());
		var check = BuildPreflight.inspect(a, mc, design);
		at.put("matching", check.existing() + "/" + cells.size());
		if (!check.blockers().isEmpty()) {
			return ToolResult.error(ErrorCode.PATH_BLOCKED, at.raw(check.skipped(6)), "some portal cells are blocked or unsupported",
				"pick another spot with x,y,z on flat ground, or clear those cells");
		}
		if (!check.missing().isEmpty()) {
			return ToolResult.error(ErrorCode.MISSING_MATERIALS, at.put("missing", check.missingList()), "not enough materials",
				"a portal needs " + PortalPlanner.OBSIDIAN + " obsidian (mine with a diamond pickaxe, or pour water on lava sources)");
		}
		Task frame = null;
		if (!check.nothingToDo()) {
			frame = check.commands()
				? new CommandBuildTask(a, mc.level, "nether_portal", "傳送門框", check.targets(), check.previous(), true, check.doors())
				: new BuildTask(a, "nether_portal", "傳送門框", min, max, check.targets(), check.clearing(), check.demolish(),
					check.doors(), cells.keySet());
		}
		String scaffolds = frame instanceof BuildTask ? BuildTask.scaffoldPlan(mc) : null;
		Set<BlockPos> keep = new java.util.HashSet<>();
		for (var c : layout.frame()) keep.add(pos(c));
		for (var c : layout.corners()) keep.add(pos(c));
		return a.tasks.start(NetherPortalTask.build(a, frame, base, axisName, keep, ignites(layout, alongX)), replace,
			at.raw(check.work()).put("igniter", Inv.id(igniter)).raw(scaffolds).toString());
	}

	/** @param target 指定的傳送門格；null 時找 {@link #SEARCH_RADIUS} 格內最近的 */
	static ToolResult enter(Agent a, Minecraft mc, BlockPos target, boolean replace) {
		LocalPlayer p = mc.player;
		if (p.isOnPortalCooldown()) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("portal_cooldown", p.getPortalCooldown()),
				"portal cooldown is active; the portal will not send you yet", "stay outside the portal until portal_cooldown in get_self is 0");
		}
		BlockPos portal = target != null ? floorOf(mc.level, target) : nearest(mc);
		if (portal == null || !mc.level.getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
			return ToolResult.error(ErrorCode.NOT_FOUND, Kv.of().put("radius", SEARCH_RADIUS)
					.put("target", target == null ? null : Fmt.pos(target)),
				target == null ? "no lit nether portal within " + SEARCH_RADIUS + " blocks" : "no lit nether portal at " + Fmt.pos(target),
				"nether_portal action=build first, or goto a known portal");
		}
		return a.tasks.start(NetherPortalTask.enter(a, portal), replace, Kv.of().put("portal", Fmt.pos(portal)).toString());
	}

	/** 每格框塊朝門裡的那一面：底框朝上、頂框朝下、側框朝內。底框排前面(站在地上最好點)。 */
	private static List<NetherPortalTask.Ignite> ignites(PortalPlanner.Layout layout, boolean alongX) {
		Set<BlockPos> interior = new java.util.HashSet<>();
		for (var c : layout.interior()) interior.add(pos(c));
		List<NetherPortalTask.Ignite> out = new ArrayList<>();
		Direction[] faces = alongX
			? new Direction[]{Direction.UP, Direction.EAST, Direction.WEST, Direction.DOWN}
			: new Direction[]{Direction.UP, Direction.SOUTH, Direction.NORTH, Direction.DOWN};
		for (Direction face : faces) {
			for (var c : layout.frame()) {
				BlockPos p = pos(c);
				if (interior.contains(p.relative(face))) out.add(new NetherPortalTask.Ignite(p, face));
			}
		}
		return out;
	}

	private static BlockPos pos(PortalPlanner.Cell c) {
		return new BlockPos(c.x(), c.y(), c.z());
	}

	static Item igniter(LocalPlayer p) {
		if (Inv.countWithOffhand(p, Items.FLINT_AND_STEEL, ItemMatcher.ANY) > 0) return Items.FLINT_AND_STEEL;
		if (Inv.countWithOffhand(p, Items.FIRE_CHARGE, ItemMatcher.ANY) > 0) return Items.FIRE_CHARGE;
		return null;
	}

	/** 背包裡夠補角落的第一種方塊；都沒有時，多出來的黑曜石夠也行。 */
	private static Item cornerFiller(Minecraft mc, int corners, int obsidianNeeded) {
		boolean creative = Creative.isCreative(mc);
		for (Item item : CORNER_FILLERS) {
			int have = Inv.count(mc.player, item);
			if (have >= corners || (creative && have > 0)) return item;
		}
		int obsidian = Inv.count(mc.player, Items.OBSIDIAN);
		return obsidian >= obsidianNeeded + corners || (creative && obsidian > 0) ? Items.OBSIDIAN : null;
	}

	/** 最近一格傳送門，取那一欄最底下站得進去的格子。 */
	private static BlockPos nearest(Minecraft mc) {
		BlockPos me = mc.player.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(me.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
			me.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
			if (!mc.level.getBlockState(q).is(Blocks.NETHER_PORTAL) || mc.level.getBlockState(q.below()).is(Blocks.NETHER_PORTAL)) continue;
			double d = q.distSqr(me);
			if (d < bestDist) {
				bestDist = d;
				best = q.immutable();
			}
		}
		return best;
	}

	/** 指定的格子往下找到傳送門那一欄的最底格(站得進去的那格)。 */
	private static BlockPos floorOf(Level level, BlockPos target) {
		BlockPos q = target;
		while (level.getBlockState(q.below()).is(Blocks.NETHER_PORTAL)) q = q.below();
		return q;
	}
}
