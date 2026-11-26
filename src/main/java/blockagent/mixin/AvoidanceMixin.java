package blockagent.mixin;

import blockagent.core.CombatPolicy;
import blockagent.game.Agent;
import blockagent.game.combat.DangerZones;
import blockagent.game.reflex.Reflexes;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 尋路繞開怪物(docs/SPEC.md S9.1b)：Baritone 原本把 8 格內所有生物(連牛羊村民)都乘 1.5 倍成本，
 * 8 格還在骷髏射程內、1.5 倍又太弱，回家常直接從怪物旁邊經過。這裡把生物那部分換成 {@link DangerZones}：
 * 只算威脅，遠程怪繞到射程外，再加上脫身時記下、現在看不到的怪物位置。怪物生成籠的部分照舊。
 * Avoidance 不在 Baritone API 裡，用反射建構與讀欄位(Mixin 類別不能宣告內部類別，所以用兩個 volatile 欄位依序安全發布)。
 */
@Mixin(targets = "baritone.utils.pathing.Avoidance", remap = false)
public abstract class AvoidanceMixin {
	private static volatile Field blockagent$coefficient;
	private static volatile Constructor<?> blockagent$ctor;
	private static volatile boolean blockagent$warned;

	@Inject(method = "create", at = @At("RETURN"), cancellable = true, remap = false)
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void blockagent$threatsOnly(baritone.api.utils.IPlayerContext ctx, CallbackInfoReturnable<List> cir) {
		Agent a = Agent.get();
		if (a == null || !a.session.isRunning()) return;
		try {
			Constructor<?> ctor = blockagent$ctor;
			Field coeff = blockagent$coefficient;
			if (ctor == null || coeff == null) {
				Class<?> c = Class.forName("baritone.utils.pathing.Avoidance");
				Field f = c.getDeclaredField("coefficient");
				f.setAccessible(true);
				Constructor<?> k = c.getConstructor(BlockPos.class, double.class, int.class);
				// 先發布已 setAccessible 的 Field，再發布 Constructor(volatile happens-before)
				blockagent$coefficient = f;
				blockagent$ctor = k;
				coeff = f;
				ctor = k;
			}
			double mobCoefficient = baritone.api.BaritoneAPI.getSettings().mobAvoidanceCoefficient.value;
			List out = new ArrayList();
			// 留下怪物生成籠(係數跟生物的不同)
			for (Object o : cir.getReturnValue()) {
				if (coeff.getDouble(o) != mobCoefficient) out.add(o);
			}
			// 正在保命(溺水／岩漿／著火／防摔)、撤退或戰鬥時，需要立刻算出短路線；
			// 套用重疊的 4× 避怪圈會讓 A* 展開百萬節點、卡住 1 秒以上挨打或淹死，此時略過避怪圈。
			Reflexes.Kind reflex = a.reflexes.active();
			if (reflex == null || reflex == Reflexes.Kind.EAT || reflex == Reflexes.Kind.UNSTUCK) {
				// 這裡在 Baritone 的背景執行緒：只讀主執行緒更新的快照；主動交戰的目標本身不避開
				int ignoreId = a.danger.ignoredEntityId();
				for (DangerZones.Zone z : a.danger.snapshot()) {
					if (ignoreId >= 0 && z.entityId() == ignoreId) continue;
					out.add(ctor.newInstance(z.center(), CombatPolicy.AVOID_COEFFICIENT, z.radius()));
				}
			}
			cir.setReturnValue(out);
		} catch (ReflectiveOperationException | RuntimeException e) {
			// 拿不到就用 Baritone 原本的避怪；記一次，免得又默默失效
			if (!blockagent$warned) {
				blockagent$warned = true;
				blockagent.BlockAgentMod.LOG.warn("尋路避怪改用 Baritone 原本的設定：{}", e.toString());
			}
		}
	}
}
