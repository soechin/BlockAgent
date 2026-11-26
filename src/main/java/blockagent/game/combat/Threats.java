package blockagent.game.combat;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;

/**
 * 怪物分類(docs/SPEC.md S9.1b)。只看生物的種類和客戶端看得到的狀態(手上拿什麼、在不在拉弓、生氣沒有)。
 */
public final class Threats {
	public enum Kind {
		/** 苦力怕：打一下就退，膨脹就跑。 */
		EXPLODER("exploder"),
		/** 會從遠處攻擊：衝過去近戰、拉弓時舉盾，走不近就對射或躲起來。 */
		RANGED("ranged"),
		/** 走過來咬人的：走近、冷卻滿才打。 */
		MELEE("melee"),
		/** 不惹牠就不會攻擊：牠沒生氣、也沒打過自己就不動手。 */
		NEUTRAL("neutral"),
		/** 打不過或不該打：往遠處跑。 */
		AVOID("avoid");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	private Threats() {
	}

	public static boolean isHostileType(Entity e) {
		return e instanceof Enemy;
	}

	/** 沒有弓的時候用的分類。有弓時，ghast 改當遠程對射(見 {@link #kind(Entity, boolean, Player, Entity)})。 */
	public static Kind kind(Entity e) {
		return kind(e, false, null, null);
	}

	public static Kind kind(Entity e, boolean haveBow, Player player, Entity attacker) {
		if (e instanceof Creeper) return Kind.EXPLODER;
		// 鐵傀儡不是敵對生物，但被惹毛時打不過，跟凋零、伏守者一樣避開(M14)
		if (e instanceof Warden || e instanceof WitherBoss || e instanceof EnderDragon || e instanceof IronGolem) return Kind.AVOID;
		if (e instanceof Ghast) return haveBow ? Kind.RANGED : Kind.AVOID;
		if (e instanceof Piglin piglin && (e == attacker || angry(e)
			|| player != null && !piglin.isBaby() && !hasPiglinSafeArmor(player))) {
			return ranged(e) ? Kind.RANGED : Kind.MELEE;
		}
		if (e instanceof Enderman || e instanceof ZombifiedPiglin || e instanceof Spider
			|| (e instanceof AbstractPiglin && !(e instanceof PiglinBrute))) {
			return Kind.NEUTRAL;
		}
		if (ranged(e)) return Kind.RANGED;
		return Kind.MELEE;
	}

	private static boolean hasPiglinSafeArmor(Player player) {
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (player.getItemBySlot(slot).is(ItemTags.PIGLIN_SAFE_ARMOR)) return true;
		}
		return false;
	}

	/** 會從遠處打人的生物：拿弓或弩、拿三叉戟的溺屍，以及女巫、烈焰使者、旋風人、界伏蚌、深海守衛。 */
	public static boolean ranged(Entity e) {
		if (e instanceof Witch || e instanceof Blaze || e instanceof Breeze || e instanceof Shulker || e instanceof Guardian || e instanceof Ghast) {
			return true;
		}
		if (e instanceof Mob m) {
			ItemStack hand = m.getMainHandItem();
			if (hand.getItem() instanceof BowItem || hand.getItem() instanceof CrossbowItem) return true;
			return m instanceof Drowned && hand.getItem() instanceof TridentItem;
		}
		return false;
	}

	/** 這隻遠程怪的攻擊盾牌擋得住、而且 ShieldGuard 會偵測(女巫藥水、深海守衛雷射、界伏蚌飛彈除外)。 */
	public static boolean shieldBlocks(Entity e) {
		return !(e instanceof Witch || e instanceof Guardian || e instanceof Shulker);
	}

	/** 會飛、走路追不到的：只在打得到時出手。 */
	public static boolean flying(Entity e) {
		return e instanceof Phantom || e instanceof Vex || e instanceof Ghast || e instanceof Blaze;
	}

	/** 牠是不是已經衝著人來了：手臂舉起({@code isAggressive})、在拉弓、終界使者在尖叫、苦力怕在膨脹。 */
	public static boolean angry(Entity e) {
		if (e instanceof Enderman en && en.isCreepy()) return true;
		if (e instanceof Creeper c && c.getSwellDir() > 0) return true;
		if (e instanceof Mob m && m.isAggressive()) return true;
		return e instanceof LivingEntity le && le.isUsingItem();
	}

	/**
	 * 算不算威脅(撤退、進食、hostile_near、尋路避怪都用這個)：敵對生物都算；中立生物要生氣了或剛打過自己才算；
	 * 剛打過自己的非敵對生物(鐵傀儡、狼、蜜蜂等，受保護生物除外)也算，免得被打殘不會撤退(P3-M3)。
	 *
	 * @param attacker 最近打過自己的生物，可以是 null
	 */
	public static boolean isThreat(Entity e, Player player, Entity attacker) {
		if (!e.isAlive()) return false;
		if (!(e instanceof Enemy)) {
			return e == attacker && e instanceof LivingEntity && !blockagent.game.RuleGuard.isProtectedEntity(e);
		}
		if (kind(e, false, player, attacker) != Kind.NEUTRAL) return true;
		return e == attacker || angry(e);
	}

	/** 給 AI 看的狀態，例如「正在膨脹」；沒有特別的狀態回傳空字串。 */
	public static String state(Entity e) {
		if (e instanceof Creeper c && c.getSwellDir() > 0) return "swelling";
		if (e instanceof Enderman en && en.isCreepy()) return "angry";
		if (e instanceof LivingEntity le && le.isUsingItem() && ranged(e)) return "drawing_bow";
		if (kind(e) == Kind.NEUTRAL && angry(e)) return "angry";
		return "";
	}
}
