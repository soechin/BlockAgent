package blockagent.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 從 Minecraft jar 讀原版的配方與物品 tag(docs/SPEC.md S4.4：26.3 客戶端 jar 內建 2,042 個配方)。
 */
public final class VanillaData {
	private static final String RECIPE_DIR = "data/minecraft/recipe/";
	private static final String ITEM_TAG_DIR = "data/minecraft/tags/item/";

	private final Map<String, String> files = new HashMap<>();
	private final Map<String, Set<String>> tagCache = new HashMap<>();

	private VanillaData() {
	}

	/** 用類別載入器找出含有原版資料的 jar(不載入任何遊戲類別，單元測試裡也能用)。 */
	public static VanillaData fromClasspath(ClassLoader loader) throws IOException {
		java.net.URL url = loader.getResource(RECIPE_DIR + "stone_pickaxe.json");
		if (url == null) throw new IOException("類別路徑上找不到原版配方資料");
		Path jar;
		try {
			if ("jar".equals(url.getProtocol())) {
				String spec = url.getFile();
				jar = Path.of(new java.net.URI(spec.substring(0, spec.indexOf("!/"))));
			} else {
				throw new IOException("不支援的資源位置：" + url);
			}
		} catch (URISyntaxException e) {
			throw new IOException("找不到 Minecraft jar：" + url, e);
		}
		return fromJar(jar);
	}

	public static VanillaData fromJar(Path jar) throws IOException {
		VanillaData d = new VanillaData();
		try (ZipFile z = new ZipFile(jar.toFile())) {
			var en = z.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				String n = e.getName();
				if (!n.endsWith(".json")) continue;
				if (n.startsWith(RECIPE_DIR) || n.startsWith(ITEM_TAG_DIR)) {
					try (InputStream in = z.getInputStream(e)) {
						d.files.put(n, new String(in.readAllBytes(), StandardCharsets.UTF_8));
					}
				}
			}
		}
		if (d.files.keySet().stream().noneMatch(k -> k.startsWith(RECIPE_DIR))) throw new IOException("jar 裡沒有配方資料：" + jar);
		return d;
	}

	public int recipeCount() {
		return (int) files.keySet().stream().filter(k -> k.startsWith(RECIPE_DIR)).count();
	}

	/** 物品 tag(例如 minecraft:planks)展開成物品 id，會處理 tag 裡的 tag。 */
	public synchronized Collection<String> itemTag(String tag) {
		return resolve(Recipes.norm(tag), new LinkedHashSet<>());
	}

	private Set<String> resolve(String tag, Set<String> visiting) {
		Set<String> cached = tagCache.get(tag);
		if (cached != null) return cached;
		Set<String> out = new LinkedHashSet<>();
		if (!visiting.add(tag)) return out;
		String path = tag.substring(tag.indexOf(':') + 1);
		String json = files.get(ITEM_TAG_DIR + path + ".json");
		if (json != null) {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			for (JsonElement v : o.getAsJsonArray("values")) {
				String s = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
				if (s.startsWith("#")) out.addAll(resolve(Recipes.norm(s.substring(1)), visiting));
				else out.add(Recipes.norm(s));
			}
		}
		tagCache.put(tag, out);
		return out;
	}

	public Recipes recipes() {
		Recipes r = new Recipes(this::itemTag);
		List<String> keys = new ArrayList<>(files.keySet());
		keys.sort(null);
		for (String k : keys) {
			if (!k.startsWith(RECIPE_DIR)) continue;
			String id = "minecraft:" + k.substring(RECIPE_DIR.length(), k.length() - 5);
			try {
				r.addJson(id, files.get(k));
			} catch (RuntimeException e) {
				// 格式不認得的配方略過
			}
		}
		return r;
	}
}
