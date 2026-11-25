package blockagent.testkit.mixin;

import blockagent.testkit.ServerAndCreativeGameTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * T12(e)：數伺服器把玩家「拉回去」的次數。伺服器判定移動太快或不合理時，會送 ClientboundPlayerPositionPacket 把位置改回來。
 * (這個方法會先在網路執行緒被呼叫一次、再轉到主執行緒，只數主執行緒那次。)
 */
@Mixin(ClientPacketListener.class)
public abstract class PositionCorrectionCounter {
	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void blockagent$count(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread()) ServerAndCreativeGameTest.CORRECTIONS.incrementAndGet();
	}
}
