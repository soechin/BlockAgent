package blockagent.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 世界記憶(docs/SPEC.md S4.8)：存讀、上限、存不下時不改動。 */
class MemoryTest {
	@TempDir
	Path tmp;

	@Test
	void savesAndReloadsPerWorld() throws Exception {
		Path file = tmp.resolve("worlds/sp-a/memory.json");
		Memory m = new Memory();
		assertEquals("not in a world", m.put("home", "1,64,2"), "沒進世界不能寫");
		m.load(file);
		assertNull(m.put("home", "1,64,2"));
		assertNull(m.put("goal", "蓋房子"));
		assertTrue(Files.exists(file), "寫入後要存檔(先寫暫存檔再搬過去)");
		try (var files = Files.list(file.getParent())) {
			assertEquals(1, files.count(), "不能留下暫存檔");
		}

		Memory again = new Memory();
		again.load(file);
		assertEquals(Map.of("home", "1,64,2", "goal", "蓋房子"), again.all());
		assertTrue(again.delete("home"));
		assertFalse(again.delete("home"));

		again.load(tmp.resolve("worlds/sp-b/memory.json"));
		assertEquals(0, again.size(), "換世界後是另一份記憶");
	}

	@Test
	void rejectsOversizedEntriesWithoutChangingAnything() {
		Memory m = new Memory();
		m.load(tmp.resolve("memory.json"));
		assertNotNull(m.put("", "x"));
		assertNotNull(m.put("k".repeat(Memory.MAX_KEY + 1), "x"));
		assertNotNull(m.put("k", "x".repeat(Memory.MAX_VALUE + 1)));
		assertNull(m.put("k", "舊值"));
		// 塞滿 16 KB：最後一筆放不下時回傳原因，原本的值不變
		String fail = null;
		for (int i = 0; fail == null && i < 100; i++) fail = m.put("k" + i, "x".repeat(Memory.MAX_VALUE));
		assertNotNull(fail, "應該在 16 KB 時拒絕");
		assertTrue(fail.contains("memory is full"), fail);
		assertEquals("舊值", m.all().get("k"));
		int before = m.size();
		assertNotNull(m.put("k", "x".repeat(Memory.MAX_VALUE)), "覆蓋成更長的值也會超過");
		assertEquals("舊值", m.all().get("k"), "超過時保留舊值");
		assertEquals(before, m.size());
	}

	@Test
	void brokenFileLoadsEmpty() throws Exception {
		Path file = tmp.resolve("memory.json");
		Files.writeString(file, "{not json");
		Memory m = new Memory();
		m.load(file);
		assertTrue(m.loaded());
		assertEquals(0, m.size());
	}
}
