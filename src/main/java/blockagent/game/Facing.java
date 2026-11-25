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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/**
 * place_block 的 facing(A6，docs/SPEC.md S4.2b)：放好後方塊狀態的朝向。
 * 看方塊有哪一種朝向屬性：FACING、HORIZONTAL_FACING、漏斗的 FACING_HOPPER 直接比方向；原木這類的 AXIS 比軸；
 * 告示牌、旗幟、頭顱的 ROTATION_16 比四個正方向。每種方塊「朝玩家」還是「背對玩家」、看點哪一面還是看視線各不相同，
 * 所以不逐一手寫規則，而是用原版自己的放置邏輯模擬「點哪一面、怎麼看」，挑出會放成指定朝向的組合。
 */
public final class Facing {
	private Facing() {
	}

	/** 點擊與視角的組合，以及照這樣放會得到的狀態。 */
	public record Plan(BlockHitResult hit, float yaw, float pitch, BlockState predicted) {
	}

	private static final float[] PITCHES = {0f, 80f, -80f};

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
		if (s.hasProperty(BlockStateProperties.AXIS)) return s.getValue(BlockStateProperties.AXIS) == want.getAxis();
		return want.getSerializedName().equals(of(s));
	}

	/** 把朝向直接寫進狀態(創造模式用指令放時)；方塊沒有朝向屬性或不接受這個方向時回傳 null。 */
	public static BlockState apply(BlockState s, Direction want) {
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
	public sealed interface Result permits Found, NoFacing, NoWay {
	}

	public record Found(Plan plan) implements Result {
	}

	/** 照任何方式放，這種方塊都沒有朝向屬性。 */
	public record NoFacing() implements Result {
	}

	/** 方塊有朝向，但從目前旁邊有的支撐面放不出這個方向；tried 是試得出的其他方向。 */
	public record NoWay(String possible) implements Result {
	}

	/**
	 * 這格能不能拿來貼著放(不蹲下直接右鍵)：要是實心方塊，而且不是會打開介面或切換狀態的方塊
	 * (箱子、工作台、門、按鈕…，P2-1)，也不點有方塊實體的(告示牌這類右鍵可能有自己的反應)。
	 */
	public static boolean canPlaceAgainst(Level level, BlockState n, BlockPos at) {
		return !n.isAir() && !n.canBeReplaced() && n.getFluidState().isEmpty() && !n.hasBlockEntity()
			&& !blockagent.game.RuleGuard.isInteractive(level, n, at);
	}

	/**
	 * 試每個可以貼著放的面({@link #canPlaceAgainst})和每個視角(四個水平方向 × 平視、往下、往上)，
	 * 用原版的放置邏輯預測狀態。會暫時改玩家的視角，算完就還原。
	 */
	public static Result solve(Level level, LocalPlayer p, BlockPos pos, BlockItem item, Direction want) {
		float oldYaw = p.getYRot(), oldPitch = p.getXRot();
		boolean anyFacing = false;
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
						if (!canPlaceAgainst(level, n, against)) continue;
						BlockHitResult hit = blockagent.game.tasks.Moves.hit(against, side.getOpposite());
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
						if (f == null) continue;
						anyFacing = true;
						if (matches(st, want)) return new Found(new Plan(hit, yaw, pitch, st));
						possible.add(f);
					}
				}
			}
		} finally {
			p.setYRot(oldYaw);
			p.setXRot(oldPitch);
		}
		if (!anyFacing) {
			// 旁邊沒有支撐面時預測不出來：看預設狀態有沒有朝向屬性，分辨「沒有朝向」和「放不出這個方向」
			boolean wallVariant = item instanceof net.minecraft.world.item.StandingAndWallBlockItem; // 火把貼牆才有朝向
			return of(item.getBlock().defaultBlockState()) == null && !wallVariant ? new NoFacing() : new NoWay("");
		}
		return new NoWay(String.join(",", possible));
	}
}
