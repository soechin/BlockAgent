package blockagent.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 把 port 和 token 寫進 ~/.blockagent/runtime.json(權限 0600)，給啟動腳本讀(docs/SPEC.md S11.1)。
 */
public final class RuntimeFile {
	private RuntimeFile() {
	}

	public static Path defaultHome() {
		String override = System.getProperty("blockagent.home");
		if (override != null && !override.isBlank()) return Path.of(override);
		return Path.of(System.getProperty("user.home"), ".blockagent");
	}

	public static Path write(Path home, int port, String token) throws IOException {
		return write(home, port, token, null);
	}

	/** planToken 是規劃服務專用的 token(S11.7)，只能用在 /plan/* 端點；null 表示不開放。 */
	public static Path write(Path home, int port, String token, String planToken) throws IOException {
		Files.createDirectories(home);
		JsonObject o = new JsonObject();
		o.addProperty("port", port);
		o.addProperty("token", token);
		if (planToken != null) o.addProperty("plan_token", planToken);
		o.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
		o.addProperty("pid", ProcessHandle.current().pid());
		byte[] bytes = new GsonBuilder().setPrettyPrinting().create().toJson(o).getBytes(StandardCharsets.UTF_8);

		Path target = home.resolve("runtime.json");
		Path tmp = home.resolve("runtime.json.tmp");
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		Files.deleteIfExists(tmp);
		if (posix) {
			Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} else {
			Files.createFile(tmp);
		}
		Files.write(tmp, bytes);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	public static void delete(Path home) {
		try {
			Files.deleteIfExists(home.resolve("runtime.json"));
		} catch (IOException ignored) {
			// 結束時刪不掉也沒關係，下次啟動會覆寫
		}
	}
}
