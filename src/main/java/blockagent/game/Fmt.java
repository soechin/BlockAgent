package blockagent.game;

import blockagent.mcp.Kv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 遊戲資料的 Compact KV 寫法(docs/SPEC.md S3.0b)：座標、方向、物品、所有權、自己的狀態。
 * 只給 MCP 回應用；HUD 和聊天是給人看的，不用這裡。
 */
public final class Fmt {
	private Fmt() {
	}

	public static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

	public static String pos(BlockPos p) {
		return p.getX() + "," + p.getY() + "," + p.getZ();
	}

	public static String pos(Vec3 v) {
		return pos(BlockPos.containing(v));
	}

	/** 水平方向的八方位，1 格內是 here。 */
	public static String compass(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dz = to.z - from.z;
		if (dx * dx + dz * dz < 1) return "here";
		double ang = Math.toDegrees(Math.atan2(dx, -dz));
		if (ang < 0) ang += 360;
		return COMPASS[(int) Math.round(ang / 45) % 8];
	}

	/** 依 yaw 算面朝的八方位(Minecraft 的 yaw 0 是南、90 是西)。 */
	public static String facing(float yaw) {
		double ang = Math.floorMod(Math.round(yaw) + 180, 360); // 轉成 0 = 北、90 = 東
		return COMPASS[(int) Math.round(ang / 45) % 8];
	}

	/** 加上 dir=、dist=，高低差超過 2 格再加 dy=。 */
	public static Kv rel(Kv kv, Vec3 from, Vec3 to) {
		double dy = to.y - from.y;
		kv.put("dir", compass(from, to)).put("dist", Math.round(from.distanceTo(to)));
		if (Math.abs(dy) > 2) kv.put("dy", (dy > 0 ? "+" : "") + Math.round(dy));
		return kv;
	}

	public static String entityId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	/** 所有權的固定三個值：own、other、nat(S3.0b)。 */
	public static String owner(Agent agent, BlockPos pos, net.minecraft.world.level.block.state.BlockState st) {
		if (agent.guard.isOwned(pos)) return "own";
		return agent.guard.isNatural(st) ? "nat" : "other";
	}

	/**
	 * 自己的血量、飢餓度、位置和精簡時間(任務結果、事件附上，AI 不必再呼叫 get_self，S4.3)。
	 * 時間只在 2 分鐘內要換階段時才附倒數，例如 {@code time=day dusk_in=90s}(M17)。
	 */
	public static Kv status(LocalPlayer p) {
		Kv kv = Kv.of();
		if (p == null) return kv;
		kv.put("hp", Math.round(p.getHealth()) + "/" + Math.round(p.getMaxHealth()))
			.put("food", p.getFoodData().getFoodLevel() + "/20").put("pos", pos(p.blockPosition()));
		if (p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
			kv.raw(blockagent.core.DayClock.brief(p.level().getOverworldClockTime()));
		}
		return kv;
	}

	/**
	 * 一件物品：id，數量大於 1 加 :數量，再加上耐久、附魔、藥水、書名、自訂名稱(S3.0b)。
	 * 例如 {@code iron_pickaxe[dur=200/250;ench=efficiency:3+unbreaking:2]}。空的寫 none。
	 */
	public static String item(ItemStack s) {
		if (s == null || s.isEmpty()) return "none";
		StringBuilder sb = new StringBuilder(Inv.id(s));
		if (s.getCount() > 1) sb.append(':').append(s.getCount());
		List<String> props = props(s);
		if (!props.isEmpty()) sb.append('[').append(String.join(";", props)).append(']');
		return sb.toString();
	}

	/** 耐久、附魔、藥水等額外資訊；普通物品是空的。 */
	public static List<String> props(ItemStack s) {
		List<String> props = new ArrayList<>();
		if (s.getMaxDamage() > 0) props.add("dur=" + (s.getMaxDamage() - s.getDamageValue()) + "/" + s.getMaxDamage());
		String ench = enchantments(s.getEnchantments());
		if (ench != null) props.add("ench=" + ench);
		ItemEnchantments stored = s.get(DataComponents.STORED_ENCHANTMENTS);
		String st = stored == null ? null : enchantments(stored);
		if (st != null) props.add("stored=" + st);
		var potion = s.get(DataComponents.POTION_CONTENTS);
		if (potion != null) {
			potion.potion().ifPresent(h -> props.add("potion=" + shortName(h)));
			List<String> custom = new ArrayList<>();
			for (MobEffectInstance e : potion.customEffects()) custom.add(effect(e));
			if (!custom.isEmpty()) props.add("effects=" + String.join("+", custom));
		}
		var book = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (book != null) props.add("title=" + Kv.quote(book.title().raw()));
		var name = s.get(DataComponents.CUSTOM_NAME);
		if (name != null) props.add("name=" + Kv.quote(name.getString()));
		return props;
	}

	private static String enchantments(ItemEnchantments e) {
		if (e == null || e.isEmpty()) return null;
		List<String> parts = new ArrayList<>();
		for (var en : e.entrySet()) parts.add(shortName(en.getKey()) + ":" + en.getIntValue());
		return String.join("+", parts);
	}

	/** 狀態效果：{@code poison:1:12s}(等級從 1 起算、剩餘秒數，無限寫 inf)。 */
	public static String effect(MobEffectInstance e) {
		String left = e.isInfiniteDuration() ? "inf" : Math.max(0, e.getDuration() / 20) + "s";
		return shortName(e.getEffect()) + ":" + (e.getAmplifier() + 1) + ":" + left;
	}

	public static String effects(Collection<MobEffectInstance> effects) {
		if (effects.isEmpty()) return "none";
		List<String> parts = new ArrayList<>();
		for (MobEffectInstance e : effects) parts.add(effect(e));
		return String.join(",", parts);
	}

	private static String shortName(Holder<?> h) {
		String n = h.getRegisteredName();
		return n.startsWith("minecraft:") ? n.substring(10) : n;
	}
}
