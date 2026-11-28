package blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 天然方塊白名單(docs/SPEC.md S7.3a)：清單裡的才算天然，其他(包括不認得的)一律算人造。
 * 清單可以是方塊 id(minecraft:stone)或 tag(#minecraft:logs)。
 * 另有只在某個維度才算天然的方塊 id(例如地獄裡的地獄疙瘩)。
 */
public final class BlockClassifier {
	private final Set<String> ids = new HashSet<>();
	private final Set<String> tags = new HashSet<>();
	private final Map<String, Set<String>> dimensionIds = new HashMap<>();

	public BlockClassifier(Collection<String> entries) {
		this(entries, Map.of());
	}

	/** @param byDimension 維度 id → 只在那個維度算天然的方塊 id */
	public BlockClassifier(Collection<String> entries, Map<String, ? extends Collection<String>> byDimension) {
		for (String e : entries) {
			if (e.startsWith("#")) tags.add(normalize(e.substring(1)));
			else ids.add(normalize(e));
		}
		byDimension.forEach((dim, list) -> {
			Set<String> set = dimensionIds.computeIfAbsent(normalize(dim), k -> new HashSet<>());
			for (String id : list) set.add(normalize(id));
		});
	}

	public static BlockClassifier fromJson(InputStream in) throws IOException {
		Objects.requireNonNull(in, "找不到 natural_blocks.json");
		try (var r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
			JsonArray arr = o.getAsJsonArray("natural");
			Set<String> entries = new HashSet<>();
			arr.forEach(el -> entries.add(el.getAsString()));
			Map<String, Set<String>> byDimension = new HashMap<>();
			JsonObject dims = o.getAsJsonObject("natural_in_dimension");
			if (dims != null) {
				for (var e : dims.entrySet()) {
					Set<String> set = byDimension.computeIfAbsent(e.getKey(), k -> new HashSet<>());
					e.getValue().getAsJsonArray().forEach(el -> set.add(el.getAsString()));
				}
			}
			return new BlockClassifier(entries, byDimension);
		}
	}

	private static String normalize(String id) {
		return id.contains(":") ? id : "minecraft:" + id;
	}

	/**
	 * @param blockId   例如 minecraft:oak_log
	 * @param blockTags 這個方塊的所有 tag(不含 #)，例如 minecraft:logs
	 */
	public boolean isNatural(String blockId, Collection<String> blockTags) {
		if (ids.contains(normalize(blockId))) return true;
		for (String t : blockTags) if (tags.contains(normalize(t))) return true;
		return false;
	}

	/** 同上，再加上只在 {@code dimension}(例如 minecraft:the_nether)才算天然的方塊。 */
	public boolean isNatural(String blockId, Collection<String> blockTags, String dimension) {
		if (isNatural(blockId, blockTags)) return true;
		Set<String> extra = dimension == null ? null : dimensionIds.get(normalize(dimension));
		return extra != null && extra.contains(normalize(blockId));
	}
}
