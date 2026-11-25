package blockagent.game;

import blockagent.BlockAgentMod;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 每個工作階段一個 JSONL 紀錄檔(docs/SPEC.md S9.6)。source 分成 ai/reflex/guard/user/system。
 */
public final class ActionLog {
	public enum Source {ai, reflex, guard, user, system}

	private static final Gson GSON = new Gson();
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

	private BufferedWriter out;
	private Path file;

	public synchronized void open(Path dir) {
		close();
		try {
			Files.createDirectories(dir);
			file = dir.resolve(FILE_TIME.format(Instant.now()) + ".jsonl");
			out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			BlockAgentMod.LOG.warn("無法建立紀錄檔：{}", e.toString());
			out = null;
		}
	}

	public synchronized Path file() {
		return file;
	}

	public void log(Source source, String action, JsonElement args, String result) {
		JsonObject o = new JsonObject();
		o.addProperty("t", Instant.now().toString());
		o.addProperty("source", source.name());
		o.addProperty("action", action);
		if (args != null) o.add("args", args);
		if (result != null) o.addProperty("result", result);
		String line = GSON.toJson(o);
		synchronized (this) {
			if (out == null) return;
			try {
				out.write(line);
				out.newLine();
				out.flush();
			} catch (IOException e) {
				BlockAgentMod.LOG.warn("寫入紀錄失敗：{}", e.toString());
			}
		}
	}

	public void log(Source source, String action, String result) {
		log(source, action, null, result);
	}

	public synchronized void close() {
		if (out != null) {
			try {
				out.close();
			} catch (IOException ignored) {
				// 關閉失敗不影響遊戲
			}
			out = null;
		}
	}
}
