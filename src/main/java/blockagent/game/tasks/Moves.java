package blockagent.game.tasks;

import baritone.api.BaritoneAPI;
import baritone.api.utils.BlockOptionalMetaLookup;
import blockagent.game.Bari;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** 任務共用的小動作：轉頭、對方塊按右鍵、找附近的方塊、找放東西的位置。 */
public final class Moves {
	private Moves() {
	}

	public static void lookAt(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
		float pitch = (float) -(Mth.atan2(dy, h) * (180 / Math.PI));
		p.setYRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90f, 90f));
		p.yRotO = yaw;
		p.xRotO = pitch;
	}

	public static boolean canReach(LocalPlayer p, BlockPos pos) {
		return p.isWithinBlockInteractionRange(pos, 0.0);
	}

	/** 朝向玩家的那一面。 */
	public static Direction faceToward(LocalPlayer p, BlockPos pos) {
		Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(d.x, d.y, d.z);
	}

	/**
	 * 朝向玩家、而且外面沒被擋住的那一面(例如地板只露出上面)。直接使用物品(水桶)時伺服器是照視線找落點，
	 * 對準被鄰格蓋住的側面會落到隔壁格(2026-11-22 測試：倒水倒歪一格)。沒有露出來的面就退回 faceToward。
	 */
	public static Direction exposedFaceToward(Minecraft mc, BlockPos pos) {
		Vec3 d = mc.player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		Direction best = null;
		double bestDot = 0;
		for (Direction dir : Direction.values()) {
			double dot = d.x * dir.getStepX() + d.y * dir.getStepY() + d.z * dir.getStepZ();
			if (dot <= bestDot) continue;
			BlockPos n = pos.relative(dir);
			if (!mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()) continue;
			best = dir;
			bestDot = dot;
		}
		return best != null ? best : faceToward(mc.player, pos);
	}

	public static BlockHitResult hit(BlockPos pos, Direction face) {
		Vec3 c = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		return new BlockHitResult(c, face, pos, false);
	}

	/** 對方塊按右鍵(使用方塊或放置手上的方塊)。 */
	public static InteractionResult useOn(Minecraft mc, BlockPos pos, Direction face) {
		LocalPlayer p = mc.player;
		BlockHitResult hr = hit(pos, face);
		lookAt(p, hr.getLocation());
		InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hr);
		if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		return r;
	}

	/** 一次掃描最多拿幾格(Moves.scan 加大上限的天花板)。 */
	static final int MAX_SCAN = 32_768;

	/** 計算本次要交給 Baritone 的掃描上限：絕不超過 {@code maxScan}(P3-M9)。 */
	static int clampScanQuota(int quota, int maxScan) {
		return Math.min(Math.max(quota, 1), maxScan);
	}

	/** 計算下一輪放大後的掃描上限(×8，封頂 {@code maxScan})。 */
	static int nextScanQuota(int current, int maxScan) {
		long next = (long) current * 8L;
		return (int) Math.min(next, maxScan);
	}

	/** 用 Baritone 的掃描器在已載入的區塊裡找方塊，依距離排序。 */
	public static List<BlockPos> scan(Minecraft mc, List<Block> blocks, int radius, int max, Predicate<BlockPos> filter) {
		if (blocks.isEmpty()) return List.of();
		var ctx = Bari.b().getPlayerContext();
		int chunkRadius = Math.max(2, (radius + 15) / 16 + 1); // Baritone 的範圍迴圈是 i < radius，要多加 1
		var lookup = new BlockOptionalMetaLookup(blocks);
		BlockPos me = mc.player.blockPosition();
		List<BlockPos> out = new ArrayList<>();
		// Baritone 先依數量上限截斷，才輪到這裡濾半徑和條件：上限被不合格的格子(例如深水裡的水下格)占滿時，
		// 加大上限再掃一次，免得誤報找不到(H12)，同時夾限不超過 MAX_SCAN(P3-M9)
		for (int quota = clampScanQuota(Math.max(max * 4, 64), MAX_SCAN); ; quota = nextScanQuota(quota, MAX_SCAN)) {
			List<BlockPos> found = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(ctx, lookup, quota, -1, chunkRadius);
			out.clear();
			for (BlockPos b : found) {
				if (b.distManhattan(me) > radius * 2 || Math.sqrt(b.distSqr(me)) > radius) continue;
				if (filter == null || filter.test(b)) out.add(b.immutable());
			}
			if (out.size() >= max || found.size() < quota || quota >= MAX_SCAN) break;
		}
		out.sort(Comparator.comparingDouble(b -> b.distSqr(me)));
		return out.size() > max ? out.subList(0, max) : out;
	}

	/**
	 * 找一個可以放下工作台/熔爐的位置：玩家旁邊、空氣、下面是實心方塊、不擋住自己。
	 * 回傳要放的位置；放的時候對它下面那格的上方按右鍵。
	 */
	public static BlockPos findPlaceSpot(Minecraft mc, Predicate<BlockPos> allowed) {
		LocalPlayer p = mc.player;
		BlockPos feet = p.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					if (Math.abs(dx) <= 0 && Math.abs(dz) <= 0) continue;
					BlockPos pos = feet.offset(dx, dy, dz);
					if (pos.equals(feet) || pos.equals(feet.above())) continue;
					BlockState st = mc.level.getBlockState(pos);
					BlockState below = mc.level.getBlockState(pos.below());
					if (!(st.isAir() || st.canBeReplaced()) || !below.isFaceSturdy(mc.level, pos.below(), Direction.UP)) continue;
					// 底下是箱子、工作台這類方塊時，按右鍵會打開它而不是放東西；有生物(包括自己)擋著也放不下(L6)
					if (blockagent.game.RuleGuard.isInteractive(mc.level, below, pos.below())) continue;
					if (!mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new net.minecraft.world.phys.AABB(pos),
						e -> e.isAlive() && e.blocksBuilding).isEmpty()) continue;
					if (!canReach(p, pos.below()) || (allowed != null && !allowed.test(pos))) continue;
					double d = pos.distSqr(feet);
					if (d < bestD) {
						bestD = d;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	public static boolean nearBlock(LocalPlayer p, BlockPos pos) {
		return canReach(p, pos);
	}
}
