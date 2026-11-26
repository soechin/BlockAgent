package blockagent.game.tasks;

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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
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

	/**
	 * MC 26.3 的 BlockTransformer(斧/鏟/鋤)在副手拿盾牌且沒蹲下時會回 PASS 讓位給舉盾，鋤地、削皮、鏟草徑都沒反應(R11)。
	 * 同一 tick 先用正常的容器 SWAP 暫時換走副手盾牌，useOn 完 finally 立刻換回；封包照順序送，伺服器看得到同樣的順序。
	 */
	public static InteractionResult useOnWithShieldBypass(Minecraft mc, BlockPos pos, Direction face) {
		LocalPlayer p = mc.player;
		boolean needsBypass = p.getMainHandItem().has(net.minecraft.core.component.DataComponents.BLOCK_TRANSFORMER)
			&& p.getOffhandItem().has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS)
			&& !p.isSecondaryUseActive()
			&& p.containerMenu == p.inventoryMenu;
		if (!needsBypass) return useOn(mc, pos, face);
		int swapIdx = blockagent.game.Inv.findOffhandShieldSwapSlot(p);
		if (swapIdx < 0) return useOn(mc, pos, face);
		blockagent.game.Inv.swapToOffhand(mc, swapIdx);
		try {
			return useOn(mc, pos, face);
		} finally {
			blockagent.game.Inv.swapToOffhand(mc, swapIdx);
		}
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

	/**
	 * 直接掃描已載入的區塊區段(LevelChunkSection)，找出半徑內最近的 {@code max} 格目標方塊。
	 * 不經由 Baritone 的 BlockOptionalMetaLookup，避免查詢戰利品表時在 26.3 觸發 ServerLevelStub NPE(如 tall_grass)。
	 */
	public static List<BlockPos> scan(Minecraft mc, List<Block> blocks, int radius, int max, Predicate<BlockPos> filter) {
		if (blocks.isEmpty() || max <= 0 || mc.level == null || mc.player == null) return List.of();
		int cap = clampScanQuota(max, MAX_SCAN);
		BlockPos me = mc.player.blockPosition();
		int mx = me.getX(), my = me.getY(), mz = me.getZ();
		int minX = mx - radius, maxX = mx + radius;
		int minY = Math.max(mc.level.getMinY(), my - radius);
		int maxY = Math.min(mc.level.getMaxY() - 1, my + radius);
		int minZ = mz - radius, maxZ = mz + radius;
		if (minY > maxY) return List.of();
		long rSq = (long) radius * radius;
		Set<Block> wanted = Set.copyOf(blocks);
		Predicate<BlockState> hasWanted = st -> wanted.contains(st.getBlock());

		int minCx = minX >> 4, maxCx = maxX >> 4;
		int minCz = minZ >> 4, maxCz = maxZ >> 4;
		List<int[]> chunks = new ArrayList<>((maxCx - minCx + 1) * (maxCz - minCz + 1));
		for (int cx = minCx; cx <= maxCx; cx++) {
			int dx = axisDistToChunk(mx, cx);
			for (int cz = minCz; cz <= maxCz; cz++) {
				int dz = axisDistToChunk(mz, cz);
				long minChunkDistSq = (long) dx * dx + (long) dz * dz;
				if (minChunkDistSq <= rSq) chunks.add(new int[]{cx, cz, (int) Math.min(minChunkDistSq, Integer.MAX_VALUE)});
			}
		}
		chunks.sort(Comparator.comparingInt(c -> c[2]));

		PriorityQueue<BlockPos> best = new PriorityQueue<>(Comparator.<BlockPos>comparingLong(b -> distSq(b, mx, my, mz)).reversed());
		long worstDistSq = rSq;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		var chunkSource = mc.level.getChunkSource();

		for (int[] c : chunks) {
			if (best.size() >= cap && c[2] > worstDistSq) break;
			int cx = c[0], cz = c[1];
			LevelChunk chunk = chunkSource.getChunkNow(cx, cz);
			if (chunk == null) continue;
			int baseX = cx << 4, baseZ = cz << 4;
			int lx0 = Math.max(0, minX - baseX), lx1 = Math.min(15, maxX - baseX);
			int lz0 = Math.max(0, minZ - baseZ), lz1 = Math.min(15, maxZ - baseZ);
			int minSection = chunk.getMinSectionY();
			LevelChunkSection[] sections = chunk.getSections();
			int s0 = Math.max(0, (minY >> 4) - minSection);
			int s1 = Math.min(sections.length - 1, (maxY >> 4) - minSection);
			for (int si = s0; si <= s1; si++) {
				LevelChunkSection sec = sections[si];
				if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(hasWanted)) continue;
				int baseY = (minSection + si) << 4;
				int ly0 = Math.max(0, minY - baseY), ly1 = Math.min(15, maxY - baseY);
				for (int ly = ly0; ly <= ly1; ly++) {
					int wy = baseY + ly;
					long dySq = (long) (wy - my) * (wy - my);
					if (dySq > worstDistSq) continue;
					for (int lz = lz0; lz <= lz1; lz++) {
						int wz = baseZ + lz;
						long dyzSq = dySq + (long) (wz - mz) * (wz - mz);
						if (dyzSq > worstDistSq) continue;
						for (int lx = lx0; lx <= lx1; lx++) {
							int wx = baseX + lx;
							long dSq = dyzSq + (long) (wx - mx) * (wx - mx);
							if (dSq > worstDistSq) continue;
							if (!wanted.contains(sec.getBlockState(lx, ly, lz).getBlock())) continue;
							cursor.set(wx, wy, wz);
							if (filter != null && !filter.test(cursor)) continue;
							best.add(cursor.immutable());
							if (best.size() > cap) {
								best.poll();
								worstDistSq = distSq(best.peek(), mx, my, mz);
							} else if (best.size() == cap) {
								worstDistSq = distSq(best.peek(), mx, my, mz);
							}
						}
					}
				}
			}
		}

		List<BlockPos> out = new ArrayList<>(best);
		out.sort(Comparator.comparingLong(b -> distSq(b, mx, my, mz)));
		return out;
	}

	private static int axisDistToChunk(int coord, int chunkCoord) {
		int lo = chunkCoord << 4;
		int hi = lo + 15;
		return coord < lo ? lo - coord : coord > hi ? coord - hi : 0;
	}

	private static long distSq(BlockPos b, int mx, int my, int mz) {
		long dx = b.getX() - mx, dy = b.getY() - my, dz = b.getZ() - mz;
		return dx * dx + dy * dy + dz * dz;
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
