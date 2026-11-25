package blockagent.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 讀寫原版的右鍵冷卻(吃東西時用，見 blockagent.game.UseKey)。 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("rightClickDelay")
	int blockagent$getRightClickDelay();

	@Accessor("rightClickDelay")
	void blockagent$setRightClickDelay(int ticks);
}
