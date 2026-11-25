package blockagent.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 依世界分開存資料的鍵(docs/SPEC.md S4.8)。
 * 單人：sp-<存檔資料夾名>(有非英數字元時加上雜湊)；直接連線：mp-<位址雜湊>；LAN：lan-<MOTD 雜湊>。
 */
public final class WorldKey {
	private WorldKey() {
	}

	/**
	 * 目前世界的鍵。直接連線時「host」和「host:25565」是同一台伺服器，用正規化後的位址(L5)；
	 * 舊版用原字串算的資料夾已經存在、新的還沒有時沿用舊的，免得記憶和所有權帳本不見。
	 */
	public static String current(Minecraft mc, Path worldsDir) {
		ServerData sd = mc.getSingleplayerServer() == null ? mc.getCurrentServer() : null;
		String key = current(mc);
		if (sd != null && !sd.isLan() && worldsDir != null) {
			String legacy = "mp-" + hash((sd.ip == null ? "" : sd.ip).toLowerCase(Locale.ROOT));
			if (!legacy.equals(key) && !Files.isDirectory(worldsDir.resolve(key)) && Files.isDirectory(worldsDir.resolve(legacy))) {
				return legacy;
			}
		}
		return key;
	}

	public static String current(Minecraft mc) {
		IntegratedServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			Path root = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = root.getFileName();
			return folderKey(name == null ? "world" : name.toString());
		}
		ServerData sd = mc.getCurrentServer();
		if (sd != null) {
			if (sd.isLan()) {
				String motd = sd.motd != null ? sd.motd.getString() : sd.name;
				return "lan-" + hash(motd == null ? "" : motd);
			}
			return "mp-" + hash(serverAddress(sd.ip));
		}
		return "unknown";
	}

	/** 正規化伺服器位址：去空白、轉小寫、去掉結尾的點和預設連接埠 25565。 */
	public static String serverAddress(String ip) {
		String s = ip == null ? "" : ip.strip().toLowerCase(Locale.ROOT);
		String host = s, port = null;
		if (s.startsWith("[")) {
			int end = s.indexOf(']');
			if (end > 0) {
				host = s.substring(0, end + 1);
				if (s.startsWith(":", end + 1)) port = s.substring(end + 2);
			}
		} else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
			host = s.substring(0, s.indexOf(':'));
			port = s.substring(s.indexOf(':') + 1);
		}
		while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		if (port == null || port.isEmpty() || port.equals("25565")) return host;
		return host + ":" + port;
	}

	/** 單人世界的鍵。資料夾名有非英數字元(例如「新的世界」)時加上雜湊，避免不同世界撞成同一個鍵。 */
	public static String folderKey(String folder) {
		String safe = safe(folder);
		return "sp-" + (safe.equals(folder) ? safe : safe + "-" + hash(folder).substring(0, 8));
	}

	static String safe(String s) {
		String r = s.replaceAll("[^A-Za-z0-9._-]", "_");
		return r.isEmpty() ? "world" : r.length() > 64 ? r.substring(0, 64) : r;
	}

	static String hash(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
