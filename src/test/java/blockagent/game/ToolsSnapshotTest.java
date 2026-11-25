package blockagent.game;

import blockagent.Config;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具清單快照(docs/REVIEW.md M16)：CLI 測試(T8)用 tools-snapshot.json 比對兩個 CLI 實際看到的工具，
 * 所以工具的名稱、說明、參數一改就要在 ./gradlew test 抓到，不必等遊戲測試。
 * 不同時會把實際的清單寫到 tools-snapshot.actual.json，確認後覆蓋快照。
 */
class ToolsSnapshotTest {
	@TempDir
	Path tmp;

	@BeforeAll
	static void boot() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	/** 從目前目錄往上找有 settings.gradle 的專案根目錄。 */
	static Path projectRoot() {
		for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
			if (Files.exists(p.resolve("settings.gradle")) || Files.exists(p.resolve("settings.gradle.kts"))) return p;
		}
		throw new IllegalStateException("找不到專案根目錄(settings.gradle)");
	}

	@Test
	void toolListMatchesSnapshot() throws Exception {
		Agent a = new Agent(new Config(), tmp);
		String json = new GsonBuilder().setPrettyPrinting().create().toJson(a.tools.listJson()) + "\n";
		Path snap = projectRoot().resolve("src/test/resources/blockagent/tools-snapshot.json");
		assertTrue(Files.exists(snap), "找不到工具清單快照：" + snap);
		if (!Files.readString(snap).equals(json)) {
			Path actual = snap.resolveSibling("tools-snapshot.actual.json");
			Files.writeString(actual, json);
			fail("工具清單和 " + snap + " 不同(實際的寫在 " + actual.getFileName() + ")，確認後覆蓋快照");
		}
	}
}
