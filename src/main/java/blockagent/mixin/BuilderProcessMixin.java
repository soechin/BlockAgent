package blockagent.mixin;

import blockagent.game.Agent;
import blockagent.game.tasks.BuildTask;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/** 規劃路線時只按物料的方塊種類判斷；比對目標方塊狀態時只檢核設計明確指定的屬性，容許柵欄/玻璃片等鄰接連線屬性(R38)。 */
@Mixin(targets = "baritone.process.BuilderProcess", remap = false)
public abstract class BuilderProcessMixin {
	@Inject(method = "containsBlockState", at = @At("HEAD"), cancellable = true, remap = false)
	private static void blockagent$matchBuildMaterial(Collection<BlockState> available, BlockState desired,
													  CallbackInfoReturnable<Boolean> cir) {
		Agent agent = Agent.get();
		if (agent != null && agent.tasks.build() != null) {
			cir.setReturnValue(available.stream().anyMatch(state -> state.is(desired.getBlock())));
		}
	}

	@Inject(method = "sameBlockstate", at = @At("HEAD"), cancellable = true, remap = false)
	private static void blockagent$matchBuildTargetState(BlockState first, BlockState second,
														 CallbackInfoReturnable<Boolean> cir) {
		Agent agent = Agent.get();
		if (agent != null && agent.tasks.build() instanceof BuildTask build) {
			Boolean matched = build.sameTargetState(first, second);
			if (matched != null) cir.setReturnValue(matched);
		}
	}
}
