package blockagent.mixin;

import blockagent.game.ChatGateway;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** 使用者點聊天或書本裡的指令連結，最後防線放行(docs/SPEC.md S6.3、docs/REVIEW.md H6)。 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	@WrapMethod(method = "clickCommandAction")
	private static void blockagent$asUser(LocalPlayer player, String command, Screen screen, Operation<Void> original) {
		ChatGateway.withUserFlag(() -> original.call(player, command, screen));
	}
}
