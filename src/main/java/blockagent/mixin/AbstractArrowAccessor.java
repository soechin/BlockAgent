package blockagent.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 確認箭插進方塊用的伺服器同步欄位。 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
	@Accessor("IN_GROUND")
	static EntityDataAccessor<Boolean> blockagent$inGroundData() {
		throw new AssertionError();
	}
}
