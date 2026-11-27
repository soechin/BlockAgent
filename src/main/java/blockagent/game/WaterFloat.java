package blockagent.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 水面漂浮與開放水面判斷(供待機漂浮、溺水反射、水中近戰與撿物共用)。
 */
public final class WaterFloat {
	private static final int SURFACE_SCAN_LIMIT = 32;

	private WaterFloat() {
	}

	/**
	 * 從 {@code feet} 往上直到離開水體，上方有沒有無碰撞箱的空氣可以換氣(沒有冰塊、石頂、睡蓮等擋住)。
	 * 水草、海帶等無碰撞箱的水生方塊不視為阻擋。
	 */
	public static boolean hasOpenSurfaceAbove(Level level, BlockPos feet) {
		int maxY = Math.min(feet.getY() + SURFACE_SCAN_LIMIT, level.getMaxY());
		for (int y = feet.getY() + 1; y <= maxY; y++) {
			BlockPos pos = new BlockPos(feet.getX(), y, feet.getZ());
			BlockState state = level.getBlockState(pos);
			if (!state.getCollisionShape(level, pos).isEmpty()) return false;
			if (level.getFluidState(pos).isEmpty()) return true;
		}
		return false;
	}

	/** 玩家此刻在開放水域且需要按住空白鍵保持浮在水面(浮在深水裡或頭已在水下；1 格淺水踩地頭在水上回 false)。 */
	public static boolean needsSurfaceFloat(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return false;
		if (!p.isInWater() || p.isPassenger() || p.getAbilities().flying) return false;
		if (p.onGround() && !p.isEyeInFluid(FluidTags.WATER)) return false;
		return hasOpenSurfaceAbove(mc.level, p.blockPosition());
	}

	/** 實體是否正浮在水面上(在水裡或腳下緊鄰水，且上方沒有水淹過頭)。 */
	public static boolean isOnWaterSurface(Level level, Entity entity) {
		BlockPos at = entity.blockPosition();
		boolean inOrOnWater = entity.isInWater() || level.getFluidState(at.below()).is(FluidTags.WATER);
		return inOrOnWater && !level.getFluidState(at.above()).is(FluidTags.WATER);
	}
}
