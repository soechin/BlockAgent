package blockagent.game;

import blockagent.mcp.Args;
import blockagent.mcp.ToolResult;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AreaInspection {
	static final int MAX_VOLUME = 32_768;
	static final int PAGE_SIZE = 32;
	/** 單頁最多可要求幾筆(預設仍是 PAGE_SIZE)。 */
	static final int MAX_LIMIT = 128;
	private static final int SUMMARY_TYPES = 12;

	record Region(BlockPos min, BlockPos max) {
		static Region between(BlockPos a, BlockPos b) {
			return new Region(new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
				new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
		}

		int volume() {
			long x = (long) max.getX() - min.getX() + 1;
			long y = (long) max.getY() - min.getY() + 1;
			long z = (long) max.getZ() - min.getZ() + 1;
			if (x <= 0 || y <= 0 || z <= 0 || x > MAX_VOLUME || y > MAX_VOLUME || z > MAX_VOLUME || x * y * z > MAX_VOLUME) {
				throw new Args.BadArgument("area is larger than " + MAX_VOLUME + " blocks; use a smaller box");
			}
			return (int) (x * y * z);
		}

		Iterable<BlockPos> positions() {
			return BlockPos.betweenClosed(min, max);
		}
	}

	private AreaInspection() {
	}

	static boolean loaded(ClientLevel level, BlockPos pos) {
		return level.getChunkSource().getChunk(SectionPos.blockToSectionCoord(pos.getX()),
			SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false) != null;
	}

	static ToolResult inspect(ClientLevel level, Observation observation, Region region, boolean details, Block filter, int offset, int limit) {
		int unloaded = 0, outside = 0, matched = 0;
		Map<String, Integer> counts = new HashMap<>();
		List<String> page = new ArrayList<>();
		for (BlockPos pos : region.positions()) {
			String unavailable = null;
			if (!level.isInWorldBounds(pos)) {
				outside++;
				unavailable = "outside_world";
			} else if (!loaded(level, pos)) {
				unloaded++;
				unavailable = "unloaded";
			}
			if (unavailable != null) {
				if (details && filter == null) {
					if (matched >= offset && page.size() < limit) page.add("- " + Fmt.pos(pos) + " " + unavailable);
					matched++;
				}
				continue;
			}
			var state = level.getBlockState(pos);
			if (filter != null && !state.is(filter)) continue;
			if (!details) counts.merge(RuleGuard.shortId(state), 1, Integer::sum);
			if (details && matched >= offset && page.size() < limit) {
				String st = BlockStateParser.serialize(state);
				StringBuilder line = new StringBuilder("- ").append(Fmt.pos(pos)).append(' ')
					.append(st.startsWith("minecraft:") ? st.substring(10) : st);
				if (!state.isAir()) line.append(" owner=").append(observation.ownerLabel(pos, state));
				String[] sign = Observation.signTexts(level, pos);
				if (sign != null) {
					if (!sign[0].isEmpty()) line.append(" front=").append(blockagent.mcp.Kv.quote(sign[0]));
					if (!sign[1].isEmpty()) line.append(" back=").append(blockagent.mcp.Kv.quote(sign[1]));
				}
				page.add(line.toString());
			}
			matched++;
		}
		int total = region.volume();
		String dim = level.dimension().identifier().getPath();
		var head = blockagent.mcp.Kv.of().put("from", Fmt.pos(region.min())).put("to", Fmt.pos(region.max())).put("dim", dim)
			.put("volume", total).put("loaded", total - outside - unloaded).putIf(unloaded > 0, "unloaded", unloaded)
			.putIf(outside > 0, "outside", outside).putIf(filter != null, "matched", matched);
		StringBuilder text = new StringBuilder(head.toString());
		if (details) {
			if (page.isEmpty()) text.append("\nblocks=none");
			else text.append("\n").append(String.join("\n", page));
			if (offset + page.size() < matched) text.append("\nnext_offset=").append(offset + page.size());
		} else {
			var entries = counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
				.thenComparing(Map.Entry.comparingByKey())).toList();
			int shown = Math.min(entries.size(), SUMMARY_TYPES);
			Map<String, Integer> top = new java.util.LinkedHashMap<>();
			for (var entry : entries.subList(0, shown)) top.put(entry.getKey(), entry.getValue());
			text.append("\ncounts=").append(blockagent.mcp.Kv.counts(top));
			if (entries.size() > shown) {
				int other = entries.subList(shown, entries.size()).stream().mapToInt(Map.Entry::getValue).sum();
				text.append(" other_types=").append(entries.size() - shown).append(" other_blocks=").append(other);
			}
		}
		return ToolResult.ok(text.toString());
	}
}
