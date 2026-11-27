package blockagent.game.tasks;

import blockagent.core.ItemMatcher;
import blockagent.game.Agent;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.game.UseKey;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;

import java.util.LinkedHashMap;
import java.util.Map;

/** 進食或飲用消耗品(docs/SPEC.md S4.2c)：拿到手上、按住使用鍵直到消耗完成並回報狀態效果變化。 */
public final class EatTask extends Task {
	/** 消耗後多等 2 tick 讓伺服器同步狀態效果封包(ClientboundUpdateMobEffect / RemoveMobEffect)。 */
	private static final int EFFECT_SYNC_TICKS = 2;

	private final Item item;
	private final ItemMatcher matcher;
	private final boolean drink;
	private int startCount;
	private int startFood;
	private Map<String, int[]> startEffects = Map.of();
	private int consumedTicks = -1;

	public EatTask(Agent agent, Item item) {
		this(agent, item, ItemMatcher.ANY, item.getDefaultInstance().get(DataComponents.CONSUMABLE));
	}

	public EatTask(Agent agent, Item item, ItemMatcher matcher, Consumable consumable) {
		super(agent, "eat");
		this.item = item;
		this.matcher = matcher == null ? ItemMatcher.ANY : matcher;
		this.drink = consumable != null && consumable.animation() == ItemUseAnimation.DRINK;
		int consumeTicks = consumable != null ? consumable.consumeTicks() : 32;
		this.timeoutTicks = Math.max(20 * 5, consumeTicks + 60);
	}

	@Override
	public String describe() {
		return (drink ? "喝 " : "吃 ") + name(item);
	}

	@Override
	public String progress() {
		return "item=" + Inv.id(item);
	}

	@Override
	protected void start(Minecraft mc) {
		startCount = Inv.countWithOffhand(mc.player, item, matcher);
		startFood = mc.player.getFoodData().getFoodLevel();
		startEffects = snapshotEffects(mc.player.getActiveEffects());
		if (!Inv.selectBestInHand(mc, item, matcher)) {
			fail(ErrorCode.MISSING_MATERIALS, "no " + Inv.id(item) + " in inventory");
			return;
		}
		UseKey.startEating(mc);
	}

	@Override
	protected void tick(Minecraft mc) {
		if (consumedTicks >= 0) {
			UseKey.release(mc);
			if (++consumedTicks >= EFFECT_SYNC_TICKS) finishDone(mc);
			return;
		}
		UseKey.holdOff(mc);
		if (Inv.countWithOffhand(mc.player, item, matcher) < startCount) {
			UseKey.release(mc);
			consumedTicks = 0;
		} else if (!mc.player.isUsingItem() && elapsed() > 5) {
			fail(ErrorCode.INVALID_ARGUMENT, "could not consume (already full, or not consumable)");
		}
	}

	private void finishDone(Minecraft mc) {
		Map<String, int[]> afterEffects = snapshotEffects(mc.player.getActiveEffects());
		Kv kv = Kv.of().put(drink ? "drank" : "ate", Inv.id(item)).put("food_before", startFood);
		if (effectsChanged(startEffects, afterEffects)) {
			kv.put("effects", Fmt.effects(mc.player.getActiveEffects()));
		}
		done(kv.toString());
	}

	static Map<String, int[]> snapshotEffects(java.util.Collection<MobEffectInstance> effects) {
		Map<String, int[]> map = new LinkedHashMap<>();
		for (MobEffectInstance e : effects) {
			String name = e.getEffect().getRegisteredName();
			int dur = e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration();
			map.put(name, new int[]{e.getAmplifier(), dur});
		}
		return map;
	}

	/** 狀態效果有沒有實際改變(新增、移除、等級變化或持續時間延長，排除自然倒數)。 */
	static boolean effectsChanged(Map<String, int[]> before, Map<String, int[]> after) {
		if (!before.keySet().equals(after.keySet())) return true;
		for (var en : after.entrySet()) {
			int[] b = before.get(en.getKey());
			int[] a = en.getValue();
			if (b == null || a[0] != b[0] || a[1] > b[1]) return true;
		}
		return false;
	}

	@Override
	protected void onStop(Minecraft mc) {
		UseKey.release(mc);
	}

	/** 反射接手時鬆開了使用鍵：重新拿起物品繼續消耗(H7)。 */
	@Override
	protected void onResume(Minecraft mc) {
		if (Inv.countWithOffhand(mc.player, item, matcher) < startCount) {
			finishDone(mc);
			return;
		}
		if (!Inv.selectBestInHand(mc, item, matcher)) {
			fail(ErrorCode.MISSING_MATERIALS, "no " + Inv.id(item) + " in inventory");
			return;
		}
		startTick = agent.tick();
		UseKey.startEating(mc);
	}
}
