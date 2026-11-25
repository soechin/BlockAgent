package blockagent.mixin;

import blockagent.game.Agent;
import blockagent.game.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Baritone 挖方塊時選工具(mine、clear_area、走路途中挖開)：速度一樣時它留在快捷列最前面的那格，
 * 所以斧頭排在前面就拿斧頭挖泥土(2026-11-22 試玩)。選到不划算的工具時改成空手或不會耗損的東西，
 * 快捷列沒有的話從背包換上來。
 */
@Mixin(targets = "baritone.utils.ToolSet", remap = false)
public abstract class ToolSetMixin {
	@Inject(method = "getBestSlot(Lnet/minecraft/world/level/block/Block;ZZ)I", at = @At("RETURN"), cancellable = true, remap = false)
	private void blockagent$avoidWastingTools(Block block, boolean preferSilkTouch, boolean pathingCalculation, CallbackInfoReturnable<Integer> cir) {
		// 只在 AI 執行中介入：暫停、待命時是使用者自己在玩(或自己下 Baritone 指令)，不能動他的快捷列(M6)
		Agent agent = Agent.get();
		if (agent == null || !agent.session.isRunning()) return;
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		int slot = cir.getReturnValueI();
		if (slot < 0 || slot > 8 || Inv.worthDigging(p.getInventory().getItem(slot), block.defaultBlockState())) return;
		int spare = Inv.spareHotbarSlot(p);
		if (spare >= 0) {
			cir.setReturnValue(spare);
		} else if (!pathingCalculation && Inv.pullSpareInto(Minecraft.getInstance(), slot)) {
			// 快捷列全是會耗損的東西：真的要挖時，從背包換一格空的或方塊上來(估算路線時不動背包)
			cir.setReturnValue(slot);
		}
	}
}
