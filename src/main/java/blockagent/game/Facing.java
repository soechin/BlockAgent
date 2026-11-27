package blockagent.game;

import blockagent.mixin.BlockItemInvoker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * place_block 的 facing、half 與 sneak(A6、docs/SPEC.md S4.2b)：放好後方塊狀態的朝向與上下半。
 * 用原版自己的放置邏輯模擬「可貼放鄰格面 × 點擊高度 × 視角」，挑出同時符合 facing 與 half 的組合。
 */
public final class Facing {
	private Facing() {
	}

	/** 點擊與視角的組合，以及照這樣放會得到的狀態。 */
	public record Plan(BlockHitResult hit, float yaw, float pitch, BlockState predicted) {
	}

	private static final float[] PITCHES = {0f, 80f, -80f};
	private static final double[] SIDE_HEIGHTS = {0.5, 0.25, 0.75};
	private static final double[] VERTICAL_HEIGHTS = {0.5};

	/** 方塊目前的朝向，寫成 KV 的值(例如 north、axis_y、rotation_3)；沒有朝向屬性時是 null。 */
	public static String of(BlockState s) {
		if (s.hasProperty(BlockStateProperties.FACING)) return s.getValue(BlockStateProperties.FACING).getSerializedName();
		if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getSerializedName();
		if (s.hasProperty(BlockStateProperties.FACING_HOPPER)) return s.getValue(BlockStateProperties.FACING_HOPPER).getSerializedName();
		if (s.hasProperty(BlockStateProperties.AXIS)) return "axis_" + s.getValue(BlockStateProperties.AXIS).getSerializedName();
		if (s.hasProperty(BlockStateProperties.ROTATION_16)) {
			int r = s.getValue(BlockStateProperties.ROTATION_16);
			return switch (r) {
				case 0 -> "south";
				case 4 -> "west";
				case 8 -> "north";
				case 12 -> "east";
				default -> "rotation_" + r;
			};
		}
		return null;
	}

	/** 這個狀態的朝向是不是 want(AXIS 只比軸：north 和 south 都算 z)。 */
	public static boolean matches(BlockState s, Direction want) {
		if (want == null) return true;
		if (s.hasProperty(BlockStateProperties.AXIS)) return s.getValue(BlockStateProperties.AXIS) == want.getAxis();
		return want.getSerializedName().equals(of(s));
	}

	/** 方塊有沒有上下半屬性(半磚 SlabBlock.TYPE 或樓梯/活板門 BlockStateProperties.HALF，S4.2b)。 */
	public static boolean hasHalf(BlockState s) {
		return s.hasProperty(SlabBlock.TYPE) || s.hasProperty(BlockStateProperties.HALF);
	}

	/** 方塊目前的上下半狀態(bottom/top/double)；沒有該屬性回傳 null。 */
	public static String halfOf(BlockState s) {
		if (s.hasProperty(SlabBlock.TYPE)) return s.getValue(SlabBlock.TYPE).getSerializedName();
		if (s.hasProperty(BlockStateProperties.HALF)) return s.getValue(BlockStateProperties.HALF).getSerializedName();
		return null;
	}

	public static boolean matchesHalf(BlockState s, String wantHalf) {
		return wantHalf == null || wantHalf.equals(halfOf(s));
	}

	/** 把上下半寫進狀態(創造模式用指令放時)；不支援回傳 null。 */
	public static BlockState applyHalf(BlockState s, String wantHalf) {
		if (wantHalf == null) return s;
		if (s.hasProperty(SlabBlock.TYPE)) {
			return switch (wantHalf) {
				case "bottom" -> s.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
				case "top" -> s.setValue(SlabBlock.TYPE, SlabType.TOP);
				default -> null;
			};
		}
		if (s.hasProperty(BlockStateProperties.HALF)) {
			return switch (wantHalf) {
				case "bottom" -> s.setValue(BlockStateProperties.HALF, Half.BOTTOM);
				case "top" -> s.setValue(BlockStateProperties.HALF, Half.TOP);
				default -> null;
			};
		}
		return null;
	}

	/** 把朝向直接寫進狀態(創造模式用指令放時)；方塊沒有朝向屬性或不接受這個方向時回傳 null。 */
	public static BlockState apply(BlockState s, Direction want) {
		if (want == null) return s;
		if (s.hasProperty(BlockStateProperties.FACING)) return s.setValue(BlockStateProperties.FACING, want);
		if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return want.getAxis().isHorizontal() ? s.setValue(BlockStateProperties.HORIZONTAL_FACING, want) : null;
		}
		if (s.hasProperty(BlockStateProperties.FACING_HOPPER)) {
			return want != Direction.UP ? s.setValue(BlockStateProperties.FACING_HOPPER, want) : null;
		}
		if (s.hasProperty(BlockStateProperties.AXIS)) return s.setValue(BlockStateProperties.AXIS, want.getAxis());
		if (s.hasProperty(BlockStateProperties.ROTATION_16) && want.getAxis().isHorizontal()) {
			return s.setValue(BlockStateProperties.ROTATION_16, switch (want) {
				case SOUTH -> 0;
				case WEST -> 4;
				case NORTH -> 8;
				default -> 12;
			});
		}
		return null;
	}

	/** 直接寫進狀態時({@link #apply})這種方塊能有的朝向，逗號分隔，例如 north,south,east,west。 */
	public static String possible(BlockState def) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (Direction d : Direction.values()) {
			BlockState st = apply(def, d);
			if (st != null) out.add(of(st));
		}
		return String.join(",", out);
	}

	/** 結果：找到的組合，或為什麼找不到。 */
	public sealed interface Result permits Found, NoFacing, NoHalf, NoWay {
	}

	public record Found(Plan plan) implements Result {
	}

	/** 照任何方式放，這種方塊都沒有朝向屬性。 */
	public record NoFacing() implements Result {
	}

	/** 這種方塊沒有上下半屬性。 */
	public record NoHalf() implements Result {
	}

	/** 方塊有該屬性，但從目前旁邊有的支撐面放不出這個組合；possible 是試得出的其他值。 */
	public record NoWay(String possible) implements Result {
	}

	/**
	 * 這格能不能拿來貼著放(不蹲下直接右鍵)：要是實心方塊，而且不是會打開介面或切換狀態的方塊
	 * (箱子、工作台、門、按鈕…，P2-1)，也不點有方塊實體的(告示牌這類右鍵可能有自己的反應)。
	 */
	public static boolean canPlaceAgainst(Level level, BlockState n, BlockPos at) {
		return canPlaceAgainst(level, n, at, false);
	}

	/** {@code sneak=true} 時允許以有方塊實體或可互動的鄰格為支撐面貼著放(S4.2b)。 */
	public static boolean canPlaceAgainst(Level level, BlockState n, BlockPos at, boolean sneak) {
		if (n.isAir() || n.canBeReplaced() || !n.getFluidState().isEmpty()) return false;
		return sneak || (!n.hasBlockEntity() && !RuleGuard.isInteractive(level, n, at));
	}

	private static BlockHitResult hitAt(BlockPos against, Direction clickFace, double yFrac) {
		double x = against.getX() + 0.5 + clickFace.getStepX() * 0.5;
		double y = clickFace.getAxis().isHorizontal()
			? against.getY() + yFrac
			: against.getY() + 0.5 + clickFace.getStepY() * 0.5;
		double z = against.getZ() + 0.5 + clickFace.getStepZ() * 0.5;
		return new BlockHitResult(new Vec3(x, y, z), clickFace, against, false);
	}

	/**
	 * 試每個可以貼著放的面({@link #canPlaceAgainst})、點擊高度(側面含 0.5/0.25/0.75)和視角，
	 * 用原版放置邏輯預測狀態，挑出同時符合 {@code wantFacing}(可 null)與 {@code wantHalf}(可 null)的組合。
	 */
	public static Result solve(Level level, LocalPlayer p, BlockPos pos, BlockItem item, Direction wantFacing, String wantHalf, boolean sneak) {
		float oldYaw = p.getYRot(), oldPitch = p.getXRot();
		boolean anyFacing = false;
		boolean anyHalf = false;
		java.util.Set<String> possible = new java.util.TreeSet<>();
		ItemStack stack = new ItemStack(item);
		try {
			for (float pitch : PITCHES) {
				for (Direction look : Direction.Plane.HORIZONTAL) {
					float yaw = look.toYRot();
					p.setYRot(yaw);
					p.setXRot(pitch);
					for (Direction side : Direction.values()) {
						BlockPos against = pos.relative(side);
						BlockState n = level.getBlockState(against);
						if (!canPlaceAgainst(level, n, against, sneak)) continue;
						Direction clickFace = side.getOpposite();
						double[] heights = clickFace.getAxis().isHorizontal() ? SIDE_HEIGHTS : VERTICAL_HEIGHTS;
						for (double yFrac : heights) {
							BlockHitResult hit = hitAt(against, clickFace, yFrac);
							BlockPlaceContext ctx = item.updatePlacementContext(new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit));
							if (ctx == null || !ctx.getClickedPos().equals(pos)) continue;
							BlockState st;
							try {
								st = ((BlockItemInvoker) item).blockagent$placementState(ctx);
							} catch (RuntimeException e) {
								continue;
							}
							if (st == null) continue;
							String f = of(st);
							String h = halfOf(st);
							if (f != null) anyFacing = true;
							if (h != null) anyHalf = true;
							if (wantFacing != null && f == null) continue;
							if (wantHalf != null && h == null) continue;
							if (matches(st, wantFacing) && matchesHalf(st, wantHalf)) {
								return new Found(new Plan(hit, yaw, pitch, st));
							}
							if (wantFacing != null && matchesHalf(st, wantHalf)) possible.add(f);
							else if (wantHalf != null && matches(st, wantFacing)) possible.add(h);
							else if (f != null && h != null) possible.add(f + "/" + h);
						}
					}
				}
			}
		} finally {
			p.setYRot(oldYaw);
			p.setXRot(oldPitch);
		}
		BlockState def = item.getBlock().defaultBlockState();
		if (wantHalf != null && !anyHalf && !hasHalf(def)) return new NoHalf();
		if (wantFacing != null && !anyFacing) {
			boolean wallVariant = item instanceof net.minecraft.world.item.StandingAndWallBlockItem;
			return of(def) == null && !wallVariant ? new NoFacing() : new NoWay("");
		}
		return new NoWay(String.join(",", possible));
	}
}
