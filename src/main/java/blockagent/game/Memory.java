package blockagent.game;

import blockagent.BlockAgentMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 依世界分開的記憶(docs/SPEC.md S4.8)。key-value 文字，單個值最多 500 字，整份最多 16 KB。
 */
public final class Memory {
	public static final int MAX_VALUE = 500;
	public static final int MAX_KEY = 64;
	public static final int MAX_TOTAL_BYTES = 16 * 1024;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Map<String, String> entries = new LinkedHashMap<>();
	private Path file;

	public synchronized void load(Path file) {
		this.file = file;
		entries.clear();
		try {
			if (Files.exists(file)) {
				JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : o.entrySet()) entries.put(e.getKey(), e.getValue().getAsString());
			}
		} catch (IOException | RuntimeException e) {
			BlockAgentMod.LOG.warn("讀取記憶失敗：{}", e.toString());
		}
	}

	public synchronized void unload() {
		entries.clear();
		file = null;
	}

	public synchronized boolean loaded() {
		return file != null;
	}

	public synchronized Map<String, String> all() {
		return new LinkedHashMap<>(entries);
	}

	public synchronized int size() {
		return entries.size();
	}

	/** 回傳 null 表示成功，否則是錯誤原因。 */
	public synchronized String put(String key, String value) {
		if (file == null) return "not in a world";
		if (key == null || key.isBlank() || key.length() > MAX_KEY) return "key must be 1 to " + MAX_KEY + " characters";
		if (value == null) return "value must not be empty";
		if (value.length() > MAX_VALUE) return "value is " + value.length() + " characters; max " + MAX_VALUE;
		String old = entries.put(key, value);
		if (bytes() > MAX_TOTAL_BYTES) {
			if (old == null) entries.remove(key);
			else entries.put(key, old);
			return "memory is full (16 KB); memory_delete unneeded entries first";
		}
		save();
		return null;
	}

	public synchronized boolean delete(String key) {
		if (entries.remove(key) == null) return false;
		save();
		return true;
	}

	private int bytes() {
		return GSON.toJson(entries).getBytes(StandardCharsets.UTF_8).length;
	}

	private void save() {
		if (file == null) return;
		try {
			blockagent.core.AtomicFiles.writeString(file, GSON.toJson(entries));
		} catch (IOException e) {
			BlockAgentMod.LOG.warn("寫入記憶失敗：{}", e.toString());
		}
	}
}
