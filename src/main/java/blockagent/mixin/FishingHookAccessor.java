package blockagent.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 浮標有沒有被咬住：伺服器用 DATA_BITING 同步到客戶端，跟玩家看到浮標往下沉是同一件事(fish 用)。 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
	@Accessor("biting")
	boolean blockagent$isBiting();
}
