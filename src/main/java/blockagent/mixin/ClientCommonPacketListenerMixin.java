package blockagent.mixin;

import blockagent.game.Agent;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 分辨「斷線」和「自己離開」(docs/SPEC.md S8.7)。 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void blockagent$disconnect(DisconnectionDetails details, CallbackInfo ci) {
		Agent a = Agent.get();
		if (a != null) a.setDisconnectReason(details.reason() == null ? "unknown" : details.reason().getString());
	}
}
