package blockagent.mixin;

import blockagent.game.ChatGateway;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 使用者在伺服器對話框(dialog)按下會執行指令的按鈕，最後防線放行(docs/SPEC.md S6.3、docs/REVIEW.md H6)。
 * 目標是 ClientPacketListener 裡實作 CommonDialogAccess 的匿名類別。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener$2")
public abstract class DialogAccessMixin {
	@WrapMethod(method = "runCommand")
	private void blockagent$asUser(String command, Screen screen, Operation<Void> original) {
		ChatGateway.withUserFlag(() -> original.call(command, screen));
	}
}
