package blockagent.mixin;

import blockagent.game.ChatGateway;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;

/** 使用者在聊天畫面打字送出的聊天和指令，最後防線放行(docs/SPEC.md S6.3、docs/REVIEW.md H6)。 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@WrapMethod(method = "handleChatInput")
	private void blockagent$asUser(String message, boolean addToRecentChat, Operation<Void> original) {
		ChatGateway.withUserFlag(() -> original.call(message, addToRecentChat));
	}
}
