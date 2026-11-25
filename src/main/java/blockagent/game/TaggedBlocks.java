package blockagent.game;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 某個方塊 tag 裡有哪些方塊(docs/REVIEW.md M15)：每次掃整個方塊註冊表太慢，算過就記下來。
 * tag 重新載入、換世界時由 {@link RuleGuard#clearCache()} 清掉。
 */
public final class TaggedBlocks {
	private static final Map<TagKey<Block>, List<Block>> CACHE = new ConcurrentHashMap<>();
	/** clear() 時遞增，避免並發的 HTTP 執行緒把重載前算出的空清單寫回新快取(Low)。 */
	private static final AtomicLong GENERATION = new AtomicLong();

	private TaggedBlocks() {
	}

	public static List<Block> of(TagKey<Block> tag) {
		List<Block> cached = CACHE.get(tag);
		if (cached != null) return cached;
		long gen = GENERATION.get();
		List<Block> out = new ArrayList<>();
		for (Block b : BuiltInRegistries.BLOCK) if (b.defaultBlockState().is(tag)) out.add(b);
		List<Block> copy = List.copyOf(out);
		if (!copy.isEmpty() && GENERATION.get() == gen) CACHE.putIfAbsent(tag, copy);
		return copy;
	}

	/** 天然樹幹/菌柄(排除村莊與建築常用的削皮原木、木塊與菌絲塊，對齊 natural_blocks.json)。 */
	public static List<Block> naturalLogs() {
		return of(net.minecraft.tags.BlockTags.LOGS).stream()
			.filter(b -> isNaturalLogPath(BuiltInRegistries.BLOCK.getKey(b).getPath()))
			.toList();
	}

	public static boolean isNaturalLogPath(String path) {
		return !path.startsWith("stripped_") && !path.endsWith("_wood") && !path.endsWith("_hyphae");
	}

	static void clear() {
		GENERATION.incrementAndGet();
		CACHE.clear();
	}
}
