package blockagent.game;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 指定的方塊狀態。藍圖只比對明確指定的屬性，其他屬性可隨鄰接方塊更新。 */
public record BuildTarget(BlockState state, Set<Property<?>> properties) {
	public BuildTarget {
		properties = Set.copyOf(properties);
	}

	public boolean matches(BlockState actual) {
		if (actual == null) return false;
		if (state.isAir()) return actual.isAir();
		return actual.is(state.getBlock()) && properties.stream().allMatch(property -> actual.getValue(property).equals(state.getValue(property)));
	}

	public static Map<BlockPos, BuildTarget> uniform(Set<BlockPos> positions, Block block) {
		BuildTarget target = new BuildTarget(block.defaultBlockState(), Set.of());
		Map<BlockPos, BuildTarget> targets = new LinkedHashMap<>();
		for (BlockPos pos : positions) targets.put(pos, target);
		return targets;
	}
}
