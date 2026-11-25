package blockagent.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 讓 UseKey.cancelUse 在換快捷列格後立即送出換格封包，避免同 tick 選回原格時伺服器沒收到取消(P3-H8)。 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeInvoker {
	@Invoker("ensureHasSentCarriedItem")
	void blockagent$ensureHasSentCarriedItem();
}
