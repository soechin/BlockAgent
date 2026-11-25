package blockagent.testkit.mixin;

import blockagent.testkit.TestKit;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * T3、T5：數實際送給伺服器的操作(開始挖、對方塊使用、攻擊、容器點擊)，用來證明「沒有做任何事」。
 * 被規矩擋下的動作不會送出封包，所以不算。放在測試套件裡，正式 jar 不含(docs/REVIEW.md M8)。
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ActionCounter {
	@Inject(method = "send", at = @At("HEAD"))
	private void blockagent$count(Packet<?> packet, CallbackInfo ci) {
		boolean action = packet instanceof ServerboundPlayerActionPacket p && p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
			|| packet instanceof ServerboundUseItemOnPacket
			|| packet instanceof ServerboundAttackPacket
			|| packet instanceof ServerboundContainerClickPacket;
		if (action) TestKit.ACTIONS.incrementAndGet();
	}
}
