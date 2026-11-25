package blockagent.testkit.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 模擬視窗失焦(T6)：直接設定 Window 的 focused 欄位，也就是 Minecraft.pauseIfInactive() 讀的值。
 * (Fabric 的 client gametest 會擋掉 Window#onFocus，讓測試不受真正的視窗焦點影響，所以不能用它。)
 */
@Mixin(Window.class)
public interface WindowInvoker {
	@Accessor("focused")
	void blockagent$setFocused(boolean focused);
}
