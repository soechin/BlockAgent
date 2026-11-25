package blockagent.mixin;

import blockagent.core.ChatPolicy;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.game.BuildCommands;
import blockagent.game.ChatGateway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天的最後防線(docs/SPEC.md S6.3)與撿起物品的偵測(S7.6)。
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	/**
	 * S6.3：AI 送的一律檢查；AI 在控制時，除了使用者自己從畫面送出的，其他來源也檢查(H6，ChatGateway.mustCheckOutgoing)。
	 * 聊天要有 [AI] 前綴，指令只放行模組核准的 /fill。
	 */
	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
	private void blockagent$sendChat(String message, CallbackInfo ci) {
		if (ChatGateway.mustCheckOutgoing() && !ChatPolicy.isAllowedOutgoing(message)) {
			Agent a = Agent.get();
			if (a != null) a.log.log(ActionLog.Source.guard, "chat_dropped", "沒有前綴的發言被丟棄");
			ci.cancel();
		}
	}

	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void blockagent$sendCommand(String command, CallbackInfo ci) {
		if (blockagent$dropCommand(command)) ci.cancel();
	}

	/** 點聊天、書本裡的連結或伺服器對話框送出的指令(MC 26.3 新增的路徑)。 */
	@Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
	private void blockagent$sendUnattendedCommand(String command, Screen screen, CallbackInfo ci) {
		if (blockagent$dropCommand(command)) ci.cancel();
	}

	@Unique
	private static boolean blockagent$dropCommand(String command) {
		if (!ChatGateway.mustCheckOutgoing() || BuildCommands.isAuthorized(command)) return false;
		Agent a = Agent.get();
		if (a != null) a.log.log(ActionLog.Source.guard, "command_dropped", "AI 不能送出指令");
		return true;
	}

	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
	private void blockagent$takeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		// 封包會先在網路執行緒進來一次，再轉到 client thread；只在 client thread 處理
		if (!Minecraft.getInstance().isSameThread()) return;
		Agent a = Agent.get();
		if (a != null) a.tracker.onTakeItem(packet.getItemId(), packet.getPlayerId(), packet.getAmount());
	}

	@Inject(method = "handleBlockDestruction", at = @At("HEAD"))
	private void blockagent$blockDestruction(ClientboundBlockDestructionPacket packet, CallbackInfo ci) {
		if (!Minecraft.getInstance().isSameThread()) return;
		Agent a = Agent.get();
		if (a != null) a.observation.onBlockDestruction(packet.getId(), packet.getPos());
	}

	/** 自己的死亡訊息(died 事件附上死因)。 */
	@Inject(method = "handlePlayerCombatKill", at = @At("HEAD"))
	private void blockagent$combatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
		if (!Minecraft.getInstance().isSameThread()) return;
		Agent a = Agent.get();
		var mc = Minecraft.getInstance();
		if (a != null && mc.player != null && packet.playerId() == mc.player.getId()) a.tracker.onDeathMessage(packet.message().getString());
	}

	/** 客戶端的 getLastHurtByMob() 不會被設定，攻擊者要從伺服器的傷害事件取得(自衛反射、attacked 事件用)。 */
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void blockagent$damage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		if (!Minecraft.getInstance().isSameThread()) return;
		Agent a = Agent.get();
		var mc = Minecraft.getInstance();
		if (a != null && mc.player != null && packet.entityId() == mc.player.getId()) {
			a.observation.onSelfDamaged(packet.sourceCauseId());
		}
	}
}
