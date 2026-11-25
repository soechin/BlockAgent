package blockagent.mixin;

import blockagent.game.Agent;
import blockagent.game.tasks.ClearAreaTask;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 伺服器確認的方塊狀態，用來確認放置、偵測被別人換掉(docs/SPEC.md S7.4)。 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void blockagent$serverBlock(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		Agent a = Agent.get();
		if (a == null) return;
		ClientLevel level = (ClientLevel) (Object) this;
		a.tracker.onServerBlockState(level, pos.immutable(), state);
		if (a.tasks.current() instanceof ClearAreaTask clear) clear.onServerBlockState(level, pos, state);
	}
}
