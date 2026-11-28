package blockagent.mixin;

import blockagent.core.ItemMatcher;
import blockagent.game.Agent;
import blockagent.game.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Baritone 挖方塊時選工具(mine、clear_area、走路途中挖開)：
 * 1) mine 指定 {@code tool_match} 時，目標方塊強制選符合條件的快捷列工具(S4.2i)；
 * 2) 一般開路／未指定 {@code tool_match} 時，依預設保護規則優先選無附魔普通工具，避免拿附魔鑽石鎬清地獄岩或石頭；
 * 3) 沒有合適工具卻選到會耗損耐久的東西(如拿斧頭挖泥土、木鎬挖鐵礦)時，改成空手或非工具。
 */
@Mixin(targets = "baritone.utils.ToolSet", remap = false)
public abstract class ToolSetMixin {
	@Inject(method = "getBestSlot(Lnet/minecraft/world/level/block/Block;ZZ)I", at = @At("RETURN"), cancellable = true, remap = false)
	private void blockagent$avoidWastingTools(Block block, boolean preferSilkTouch, boolean pathingCalculation, CallbackInfoReturnable<Integer> cir) {
		// 只在 AI 執行中介入：暫停、待命時是使用者自己在玩(或自己下 Baritone 指令)，不能動他的快捷列(M6)
		Agent agent = Agent.get();
		if (agent == null || !agent.session.isRunning()) return;
		Minecraft mc = Minecraft.getInstance();
		var p = mc.player;
		if (p == null) return;
		BlockState st = block.defaultBlockState();
		ItemMatcher activeMatcher = null;
		// S4.2i：mine 指定 tool_match 時，僅對目標方塊強制選符合條件的快捷列工具；開路石材仍沿用預設保護規則
		if (agent.tasks.current() instanceof blockagent.game.tasks.MineTask mineTask) {
			var matcher = mineTask.toolMatcher();
			if (matcher != null && !matcher.isAny()) {
				activeMatcher = matcher;
				if (mineTask.isMineTarget(st)) {
					int matched = Inv.findHotbarMiningTool(p, st, matcher);
					if (matched >= 0) {
						cir.setReturnValue(matched);
						return;
					}
				}
			}
		}
		int bestHotbar = Inv.findDefaultMiningTool(p, st, 9);
		if (bestHotbar >= 0) {
			if (!pathingCalculation && mc.isSameThread()) {
				int pulled = Inv.pullBetterDefaultHotbarTool(mc, st, bestHotbar, activeMatcher);
				if (pulled >= 0) {
					cir.setReturnValue(pulled);
					return;
				}
			}
			cir.setReturnValue(bestHotbar);
			return;
		}
		int slot = cir.getReturnValueI();
		if (slot < 0 || slot > 8 || Inv.worthDigging(p.getInventory().getItem(slot), st)) return;
		int spare = Inv.spareHotbarSlot(p);
		if (spare >= 0) {
			cir.setReturnValue(spare);
		} else if (!pathingCalculation && mc.isSameThread() && Inv.pullSpareInto(mc, slot)) {
			// 快捷列全是會耗損的東西：真的要挖時，從背包換一格空的或方塊上來(估算路線時不動背包)
			cir.setReturnValue(slot);
		}
	}
}
