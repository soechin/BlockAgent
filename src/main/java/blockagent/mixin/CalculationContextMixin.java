package blockagent.mixin;

import blockagent.game.Agent;
import blockagent.game.tasks.BuildTask;
import blockagent.game.tasks.MineTask;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 讓 Baritone 的路徑規劃一開始就避開不能拆的方塊(docs/SPEC.md S1.5)。
 * 真正擋下動作的是 MultiPlayerGameModeMixin；這裡只是「先避開」。
 * 蓋建時 Baritone 用的是 BuilderCalculationContext，它覆寫了 breakCostMultiplierAt、不會呼叫父類別，所以兩個都要掛。
 */
@Mixin(targets = {"baritone.pathing.movement.CalculationContext", "baritone.process.BuilderProcess$BuilderCalculationContext"}, remap = false)
public abstract class CalculationContextMixin {
	@Inject(method = "breakCostMultiplierAt", at = @At("HEAD"), cancellable = true, remap = false)
	private void blockagent$avoidProtected(int x, int y, int z, BlockState current, CallbackInfoReturnable<Double> cir) {
		Agent a = Agent.get();
		if (a != null && a.session.isRunning()) {
			if (!a.guard.canPathBreak(x, y, z, current)
				|| (a.tasks.current() instanceof MineTask mine && mine.rejectsMineTarget(x, y, z, current))) {
				cir.setReturnValue(baritone.api.pathing.movement.ActionCosts.COST_INF);
			}
		}
	}

	@Inject(method = "costOfPlacingAt", at = @At("HEAD"), cancellable = true, remap = false)
	private void blockagent$preserveDoorway(int x, int y, int z, BlockState current, CallbackInfoReturnable<Double> cir) {
		Agent a = Agent.get();
		// 門口和藍圖設計裡的格子都不准拿來墊腳(S4.8c；M14：原本只擋門口)
		if (a != null && a.tasks.current() instanceof BuildTask build && build.forbidsScaffold(x, y, z)) {
			cir.setReturnValue(baritone.api.pathing.movement.ActionCosts.COST_INF);
		}
	}
}
