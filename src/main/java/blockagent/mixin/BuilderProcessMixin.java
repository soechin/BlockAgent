package blockagent.mixin;

import blockagent.game.Agent;
import blockagent.game.tasks.BuildTask;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/** 規劃路線時只按物料的方塊種類判斷。嘗試放置時仍由 Baritone 比對實際方塊狀態。 */
@Mixin(targets = "baritone.process.BuilderProcess", remap = false)
public abstract class BuilderProcessMixin {
	@Inject(method = "containsBlockState", at = @At("HEAD"), cancellable = true, remap = false)
	private static void blockagent$matchBlueprintMaterial(Collection<BlockState> available, BlockState desired,
														CallbackInfoReturnable<Boolean> cir) {
		Agent agent = Agent.get();
		if (agent != null && agent.tasks.build() instanceof BuildTask build && build.isBlueprint()) {
			cir.setReturnValue(available.stream().anyMatch(state -> state.is(desired.getBlock())));
		}
	}
}
