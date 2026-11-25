package blockagent.mixin;

import blockagent.game.Agent;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Gui.class)
public abstract class GuiMixin {
	/**
	 * 關閉工作台等畫面時，讓交棒後釋放的游標保持自由；使用者自己抓回游標的話照原版抓回去。
	 * 用 WrapWithCondition 而不是獨佔的 Redirect，別的模組也能包同一個呼叫(L1)。
	 */
	@WrapWithCondition(method = "setScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;grabMouse()V"))
	private boolean blockagent$grabMouseWhenPlayerControls(MouseHandler mouseHandler) {
		Agent agent = Agent.get();
		return agent == null || !agent.session.isRunning() || agent.control.userGrabbed();
	}
}
