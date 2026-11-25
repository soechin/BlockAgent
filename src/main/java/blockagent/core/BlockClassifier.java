package blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 天然方塊白名單(docs/SPEC.md S7.3a)：清單裡的才算天然，其他(包括不認得的)一律算人造。
 * 清單可以是方塊 id(minecraft:stone)或 tag(#minecraft:logs)。
 */
public final class BlockClassifier {
	private final Set<String> ids = new HashSet<>();
	private final Set<String> tags = new HashSet<>();

	public BlockClassifier(Collection<String> entries) {
		for (String e : entries) {
			if (e.startsWith("#")) tags.add(normalize(e.substring(1)));
			else ids.add(normalize(e));
		}
	}

	public static BlockClassifier fromJson(InputStream in) throws IOException {
		Objects.requireNonNull(in, "找不到 natural_blocks.json");
		try (var r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
			JsonArray arr = o.getAsJsonArray("natural");
			Set<String> entries = new HashSet<>();
			arr.forEach(el -> entries.add(el.getAsString()));
			return new BlockClassifier(entries);
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
}
