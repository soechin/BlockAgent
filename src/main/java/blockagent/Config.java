package blockagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config/blockagent.json。只有使用者能改，AI 改不了(沒有對應的工具)。
 */
public final class Config {
	/** 工作階段時間上限(分鐘)，S8.6。0 表示不限時(預設)。 */
	public int sessionMinutes = 0;
	/** 發言間隔下限(秒)，S6.4。 */
	public int chatIntervalSeconds = 15;
	/** 是否允許發言，S6.5。 */
	public boolean chatEnabled = true;
	/** 閒置提醒間隔(秒)，S5.3。 */
	public int idleReminderSeconds = 60;
	/** 偵測使用者接手，S8.5。 */
	public boolean takeoverDetection = true;
	/** 單一任務的時間上限(分鐘)，S4.3。 */
	public int taskTimeoutMinutes = 10;
	/** 鎖定為開啟、AI 關不掉的反射，S9.4。 */
	public List<String> lockedReflexes = new ArrayList<>();
	/** 額外的私訊格式(正規表示式)，S3.5。 */
	public List<String> privateMessagePatterns = new ArrayList<>();
	/** MCP 伺服器的起始 port，S11.1。 */
	public int mcpPort = 47615;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Config load(Path file) {
		try {
			if (Files.exists(file)) {
				Config c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
				if (c != null) {
					c.sanitize();
					return c;
				}
			}
		} catch (IOException | RuntimeException e) {
			BlockAgentMod.LOG.warn("讀取設定檔失敗，使用預設值：{}", e.toString());
		}
		Config c = new Config();
		c.save(file);
		return c;
	}

	private void sanitize() {
		sessionMinutes = Math.max(0, sessionMinutes);
		chatIntervalSeconds = Math.max(0, chatIntervalSeconds);
		idleReminderSeconds = Math.max(10, idleReminderSeconds);
		taskTimeoutMinutes = Math.max(1, taskTimeoutMinutes);
		// 埠號超出範圍時 HttpServer 會直接拋例外(L2)
		if (mcpPort < 1 || mcpPort > 65535) mcpPort = 47615;
		if (lockedReflexes == null) lockedReflexes = new ArrayList<>();
		if (privateMessagePatterns == null) privateMessagePatterns = new ArrayList<>();
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BlockAgentMod.LOG.warn("寫入設定檔失敗：{}", e.toString());
		}
	}
}
