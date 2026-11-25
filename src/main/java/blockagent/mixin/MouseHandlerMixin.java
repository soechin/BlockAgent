package blockagent.mixin;

import blockagent.game.Agent;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 游標與鏡頭(docs/SPEC.md S8.2a)。 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	private boolean isLeftPressed;
	@Shadow
	private boolean isMiddlePressed;
	@Shadow
	private boolean isRightPressed;

	/**
	 * 原版只在沒開介面時更新「按著」的狀態：在遊戲畫面按下右鍵打開箱子，放開時介面開著，狀態就一直停在按著，
	 * 接手偵測(S8.5)會以為使用者一直按著，每次恢復都馬上又暫停(2026-11-22 試玩)。放開時一律清掉。
	 */
	@Inject(method = "onButton", at = @At("HEAD"))
	private void blockagent$clearOnRelease(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (action != InputConstants.RELEASE) return;
		// 只在有工作階段時介入(接手偵測才用得到)，工作階段結束後照原版(L1)
		Agent agent = Agent.get();
		if (agent == null || !agent.session.inWorldSession()) return;
		switch (info.button()) {
			case InputConstants.MOUSE_BUTTON_LEFT -> isLeftPressed = false;
			case InputConstants.MOUSE_BUTTON_MIDDLE -> isMiddlePressed = false;
			case InputConstants.MOUSE_BUTTON_RIGHT -> isRightPressed = false;
			default -> {
			}
		}
	}

	/**
	 * AI 進行中點遊戲畫面抓回游標時，這一下只抓游標：原版會接著把它當成攻擊鍵，
	 * 那樣會打到東西，也會被當成使用者接手(S8.5)。放開時的事件照原版處理。
	 */
	@Inject(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;grabMouse()V", shift = At.Shift.AFTER), cancellable = true)
	private void blockagent$grabOnlyWhileAgentPlays(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		Agent agent = Agent.get();
		if (agent == null || !agent.session.isRunning()) return;
		agent.control.onUserGrabbedMouse();
		ci.cancel();
	}

	/** 使用者用滑鼠轉視角時，讓鏡頭暫時不跟著 AI 走。用 WrapOperation 包住原本的呼叫，不獨佔(L1)。 */
	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void blockagent$noteUserTurn(LocalPlayer player, double dx, double dy, Operation<Void> original) {
		original.call(player, dx, dy);
		Agent agent = Agent.get();
		if (agent != null && (dx != 0 || dy != 0)) agent.control.onUserTurn();
	}
}
