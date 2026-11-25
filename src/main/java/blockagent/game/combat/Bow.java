package blockagent.game.combat;

import blockagent.core.Ballistics;
import blockagent.game.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 弓(docs/SPEC.md S9.1c)：有沒有弓和箭、怎麼瞄準、各種投射物的初速。 */
public final class Bow {
	/** 蓄力滿的 tick 數。 */
	public static final int FULL_DRAW = 20;

	private Bow() {
	}

	public static boolean isBow(ItemStack s) {
		return s.getItem() instanceof BowItem;
	}

	/** 背包有弓也有箭(創造模式不用箭)。 */
	public static boolean ready(Player p) {
		if (Inv.count(p, Bow::isBow) == 0) return false;
		return p.getAbilities().instabuild || Inv.count(p, s -> s.is(ItemTags.ARROWS)) > 0 || p.getOffhandItem().is(ItemTags.ARROWS);
	}

	/**
	 * 轉頭瞄準：算拋物線的仰角，並依目標最近一 tick 的移動量加上前置量。
	 * 打不到(太遠)回傳 false，不轉頭。
	 */
	public static boolean aim(LocalPlayer p, Entity target) {
		Vec3 from = p.getEyePosition().subtract(0, 0.1, 0);
		Vec3 aimAt = target.getBoundingBox().getCenter();
		Vec3 vel = target.position().subtract(target.xo, target.yo, target.zo);
		Vec3 predicted = aimAt;
		Ballistics.Solution sol = null;
		for (int i = 0; i < 3; i++) {
			double dx = predicted.x - from.x, dz = predicted.z - from.z;
			sol = Ballistics.solve(Math.sqrt(dx * dx + dz * dz), predicted.y - from.y, Ballistics.FULL_BOW_SPEED);
			if (sol == null) return false;
			// 只預測水平移動；垂直方向(跳、掉)太難猜
			predicted = aimAt.add(vel.x * sol.ticks(), 0, vel.z * sol.ticks());
		}
		double dx = predicted.x - from.x, dz = predicted.z - from.z;
		float yaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
		float pitch = (float) -sol.pitchDeg();
		p.setYRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90f, 90f));
		p.yRotO = yaw;
		p.xRotO = pitch;
		return true;
	}

	/**
	 * 照滿弓的彈道朝目標中心射，箭會不會先撞到方塊(例如頭上的屋頂、中間的牆)。
	 * 打不到(太遠)或會先撞到方塊回傳 false(docs/REVIEW.md M14)。
	 */
	public static boolean hasClearShot(LocalPlayer p, Entity target) {
		Vec3 from = p.getEyePosition().subtract(0, 0.1, 0);
		Vec3 at = target.getBoundingBox().getCenter();
		double dx = at.x - from.x, dz = at.z - from.z, horiz = Math.sqrt(dx * dx + dz * dz);
		Ballistics.Solution sol = Ballistics.solve(horiz, at.y - from.y, Ballistics.FULL_BOW_SPEED);
		if (sol == null) return false;
		double rad = Math.toRadians(sol.pitchDeg());
		Vec3 dir = horiz < 0.01 ? new Vec3(0, Math.signum(at.y - from.y), 0) : new Vec3(dx / horiz * Math.cos(rad), Math.sin(rad), dz / horiz * Math.cos(rad));
		Vec3 v = dir.scale(Ballistics.FULL_BOW_SPEED), pos = from;
		var box = target.getBoundingBox().inflate(0.3);
		for (int t = 0; t <= sol.ticks() + 2; t++) {
			Vec3 next = pos.add(v);
			// 先測方塊阻擋再測目標 AABB(並將目標測試段縮短到撞牆點)，免得 3 格/tick 步長內略過目標前方的牆/半磚(Low)
			var hit = p.level().clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
			if (box.contains(pos) || box.clip(pos, end).isPresent()) return true;
			if (hit.getType() != HitResult.Type.MISS) return false;
			pos = next;
			v = v.scale(Ballistics.ARROW_DRAG).subtract(0, Ballistics.ARROW_GRAVITY, 0);
		}
		return false;
	}

	/** 放開使用鍵時射出去的初速；不是會射出東西的物品回傳 0。 */
	public static double releaseSpeed(Player p) {
		ItemStack s = p.getUseItem();
		if (s.getItem() instanceof BowItem) return BowItem.getPowerForTime(p.getTicksUsingItem()) * Ballistics.FULL_BOW_SPEED;
		if (s.getItem() instanceof TridentItem && p.getTicksUsingItem() >= 10) return 2.5;
		return 0;
	}

	/** 按右鍵就會發射：已經上好箭的弩。 */
	public static double fireSpeed(ItemStack s) {
		if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) return s.is(Items.CROSSBOW) ? 3.15 : 0;
		return 0;
	}
}
