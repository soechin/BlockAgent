package blockagent.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 用原版自己的放置邏輯預測「這樣點、這樣看」會放出什麼方塊狀態(place_block 的 facing，A6)。只讀，不放東西。 */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
	@Invoker("getPlacementState")
	BlockState blockagent$placementState(BlockPlaceContext context);
}
