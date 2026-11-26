package blockagent.game.combat;

import blockagent.core.CombatPolicy;
import blockagent.game.UseKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 盾牌守備(docs/SPEC.md S9.1b、docs/REVIEW.md R1)：每 tick 看有沒有遠程怪在拉弓、有沒有箭或火球正飛過來，
 * 決定這 tick 要不要舉盾、該面向哪裡。
 * <ul>
 *   <li>16 格內看得到自己的遠程怪拉弓 6 tick 以上就開始擋(骷髏 20 tick 才放箭，盾要舉 5 tick 才生效)。</li>
 *   <li>放箭之後盾牌照樣舉著，直到箭飛到({@link CombatPolicy#shieldHoldTicks})；不會在箭還在空中時收盾。</li>
 *   <li>24 格內有不是自己射的箭、三叉戟或火球，預測 10 tick 內會進到自己的碰撞箱，也要擋。</li>
 * </ul>
 * 只做判斷和舉盾；停下移動交給呼叫的人(Baritone 走路時會轉視角，盾就沒對著射手了)。
 */
public final class ShieldGuard {
	private static final double DRAW_RANGE = 16;
	private static final double PROJECTILE_RANGE = 24;
	private static final int DRAW_TICKS = 6;
	private static final int PREDICT_TICKS = 10;

	private long holdUntil = Long.MIN_VALUE;
	private Vec3 faceAt;
	/** 最快會到的那支箭還要幾 tick；沒有來箭是 Integer.MAX_VALUE。 */
	private int arrowEta = Integer.MAX_VALUE;
	private String source = "";

	/** 這 tick 要不要擋。沒有盾牌時一律 false。 */
	public boolean update(Minecraft mc, long now) {
		LocalPlayer p = mc.player;
		arrowEta = Integer.MAX_VALUE;
		if (!hasShield(p)) {
			holdUntil = Long.MIN_VALUE;
			return false;
		}
		double bestDraw = Double.MAX_VALUE;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(DRAW_RANGE), ShieldGuard::drawingShooter)) {
			LivingEntity le = (LivingEntity) e;
			double d = e.distanceTo(p);
			if (d > DRAW_RANGE || le.getTicksUsingItem() < DRAW_TICKS || !p.hasLineOfSight(e)) continue;
			holdUntil = Math.max(holdUntil, now + CombatPolicy.shieldHoldTicks(d));
			if (d < bestDraw) {
				bestDraw = d;
				faceAt = le.getEyePosition();
				source = Combat.id(e) + "_drawing";
			}
		}
		AABB me = p.getBoundingBox().inflate(0.5);
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(PROJECTILE_RANGE), ShieldGuard::blockable)) {
			if (((Projectile) e).getOwner() == p) continue;
			int eta = eta(e, me);
			if (eta < 0) continue;
			holdUntil = Math.max(holdUntil, now + eta + 3);
			if (eta < arrowEta) {
				arrowEta = eta;
				// 面向箭飛來的方向(反著速度往回看)，比面向射手更準：射手可能已經換位置了
				faceAt = p.getEyePosition().subtract(e.getDeltaMovement().normalize().scale(4));
				source = Combat.id(e) + "_incoming";
			}
		}
		return now < holdUntil;
	}

	/** 目前要對準的位置(射手的眼睛或箭的來向)。 */
	public Vec3 faceAt() {
		return faceAt;
	}

	/** 最快的那支箭 3 tick 內就到：不能放盾去砍。 */
	public boolean arrowImminent() {
		return arrowEta <= 3;
	}

	/** 給紀錄看的原因，例如 {@code skeleton_drawing}、{@code arrow_incoming}。 */
	public String source() {
		return source;
	}

	public void reset() {
		holdUntil = Long.MIN_VALUE;
		faceAt = null;
		arrowEta = Integer.MAX_VALUE;
	}

	/** 正在拉弓、拉弩或舉三叉戟的敵對遠程生物(女巫的「使用中」是在喝藥水，不算)。 */
	private static boolean drawingShooter(Entity e) {
		return e instanceof LivingEntity le && e instanceof Enemy && e.isAlive() && !(e instanceof Witch)
			&& le.isUsingItem() && Threats.ranged(e);
	}

	/** 盾牌擋得住的投射物：箭、三叉戟、火球、風彈。 */
	private static boolean blockable(Entity e) {
		return e.isAlive() && (e instanceof AbstractArrow || e instanceof AbstractHurtingProjectile)
			&& e.getDeltaMovement().lengthSqr() > 0.09;
	}

	/** 預測幾 tick 後會碰到自己；不會碰到回傳 -1。箭有重力和阻力，火球直線飛。 */
	private static int eta(Entity e, AABB me) {
		Vec3 pos = e.position();
		Vec3 v = e.getDeltaMovement();
		boolean gravity = e instanceof AbstractArrow;
		for (int t = 1; t <= PREDICT_TICKS; t++) {
			Vec3 next = pos.add(v);
			if (me.contains(pos) || me.clip(pos, next).isPresent()) return t;
			pos = next;
			if (gravity) v = v.scale(0.99).subtract(0, 0.05, 0);
		}
		return -1;
	}

	static boolean hasShield(LocalPlayer p) {
		return p.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS);
	}

	/** 舉盾：按住使用鍵對副手使用；正在拉弓時先收弓，不然舉不起來(H8)。 */
	public static void raise(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!hasShield(p)) return;
		if (p.isUsingItem() && p.getUseItem().getItem() instanceof BowItem) UseKey.cancelUse(mc);
		if (p.isUsingItem()) {
			if (p.getUsedItemHand() == InteractionHand.OFF_HAND) UseKey.holdOff(mc);
			return;
		}
		UseKey.startUsing(mc, InteractionHand.OFF_HAND);
	}

	/** 放下盾牌(沒在舉就只放開使用鍵)。 */
	public static void lower(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND) {
			mc.options.keyUse.setDown(false);
			mc.gameMode.releaseUsingItem(p);
		} else if (!p.isUsingItem() && mc.options.keyUse.isDown()) {
			mc.options.keyUse.setDown(false);
		}
	}
}
