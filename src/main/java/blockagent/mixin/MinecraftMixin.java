package blockagent.mixin;

import blockagent.game.Agent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 視窗失焦時不跳出 ESC 選單(docs/SPEC.md S8.4)。
 * 26.3 的 Minecraft.pauseIfInactive() 在失焦超過 500 ms 且 options.pauseOnLostFocus 時呼叫 pauseGame(false)。
 * 工作階段期間直接跳過這個檢查；不改 options，所以不會寫進使用者的 options.txt。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	private long lastActiveTime;

	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void blockagent$keepRunningWhenUnfocused(CallbackInfo ci) {
		Agent a = Agent.get();
		if (a != null && a.session.inWorldSession()) {
			// 跳過時照樣更新「最後活動時間」：工作階段一結束，原版還是先等滿 500 ms 才暫停(L1)
			lastActiveTime = Util.getMillis();
			ci.cancel();
		}
	}
}
