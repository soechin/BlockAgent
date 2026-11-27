package blockagent.game;

import blockagent.Config;
import blockagent.core.Event;
import blockagent.core.EventQueue;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** wait_events 的回應不附「未讀事件摘要」：task_only=true 留下的 task_progress 要留到下次(docs/REVIEW.md M4)。 */
class WaitEventsTest {
	@TempDir
	Path tmp;

	@BeforeAll
	static void boot() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void waitEventsResponseLeavesQueuedEventsAlone() throws Exception {
		Agent a = new Agent(new Config(), tmp);
		a.events.add(EventQueue.TASK_PROGRESS, Event.Level.BATCH, "task=mine mined=2/8");
		// wait_events 自己決定送哪些事件；task_only=true 逾時時留下的 task_progress 不能被回應尾巴倒出去
		assertEquals("", a.unreadSummary("wait_events"));
		assertEquals(1, a.events.size(), "task_progress 要留在佇列裡");
		// 其他工具的回應照樣附上未讀事件
		assertTrue(a.unreadSummary("get_self").contains("task_progress: task=mine mined=2/8"));
		assertEquals(0, a.events.size());
	}

	@Test
	void taskOnlyWithoutTaskReturnsPendingOnce() throws Exception {
		Agent a = new Agent(new Config(), tmp);
		a.events.add(EventQueue.TASK_PROGRESS, Event.Level.BATCH, "task=mine mined=2/8");
		JsonObject args = new JsonObject();
		args.addProperty("task_only", true);
		args.addProperty("max_wait_sec", 1);
		// 沒有任務時立即回傳待送事件(S5.1)，而且只出現一次
		String text = a.tools.call("wait_events", args).text();
		assertEquals(1, text.split("task_progress:", -1).length - 1, text);
		assertEquals(0, a.events.size());
	}

	@Test
	void handledTaskWaitDoesNotDrainQueuedProgress() {
		var reg = new blockagent.mcp.ToolRegistry();
		var events = new EventQueue(blockagent.core.Clock.SYSTEM);
		events.add(EventQueue.TASK_PROGRESS, Event.Level.BATCH, "task=mine mined=3/8");
		reg.setResponseSuffix(name -> EventQueue.render(events.drainAll(), events.takeDroppedCount()));

		// 模擬 taskTool 帶 wait_sec > 0 逾時：透過 withSuffixHandled 回傳，不應把佇列裡的 task_progress 倒出來(P3-M11)
		reg.register("mine_wait", "test", new JsonObject(),
			args -> blockagent.mcp.ToolResult.ok("OK started").withSuffixHandled("WAIT task_running task=mine waited=30s"));
		// 模擬 wait_sec == 0(一般回傳)：照常附上未讀事件
		reg.register("mine_nowait", "test", new JsonObject(),
			args -> blockagent.mcp.ToolResult.ok("OK started"));

		String waited = reg.call("mine_wait", new JsonObject()).text();
		assertFalse(waited.contains("task_progress:"), "withSuffixHandled 不可倒出 task_progress：" + waited);
		assertEquals(1, events.size(), "task_progress 仍應留在佇列裡");

		String immediate = reg.call("mine_nowait", new JsonObject()).text();
		assertTrue(immediate.contains("task_progress: task=mine mined=3/8"), "一般工具仍應附未讀事件：" + immediate);
		assertEquals(0, events.size());
	}

	@Test
	void naturalLogPathExcludesStrippedAndWoodBlocks() {
		assertTrue(TaggedBlocks.isNaturalLogPath("oak_log"));
		assertTrue(TaggedBlocks.isNaturalLogPath("spruce_log"));
		assertTrue(TaggedBlocks.isNaturalLogPath("crimson_stem"));
		assertFalse(TaggedBlocks.isNaturalLogPath("stripped_spruce_log"));
		assertFalse(TaggedBlocks.isNaturalLogPath("stripped_spruce_wood"));
		assertFalse(TaggedBlocks.isNaturalLogPath("spruce_wood"));
		assertFalse(TaggedBlocks.isNaturalLogPath("warped_hyphae"));
		assertFalse(TaggedBlocks.isNaturalLogPath("stripped_crimson_hyphae"));
	}

	@Test
	void parseBlockGroupSupportsCommonGroups() {
		assertTrue(Tools.parseBlockGroup("chests").contains(net.minecraft.world.level.block.Blocks.BARREL));
		assertTrue(Tools.parseBlockGroup("furnaces").contains(net.minecraft.world.level.block.Blocks.SMOKER));
		assertEquals(net.minecraft.tags.BlockTags.BEDS, Tools.blockGroupTag("bed"));
		assertEquals(net.minecraft.tags.BlockTags.BEDS, Tools.blockGroupTag("beds"));
		assertEquals(net.minecraft.tags.BlockTags.DOORS, Tools.blockGroupTag("door"));
		assertEquals(net.minecraft.tags.BlockTags.DOORS, Tools.blockGroupTag("doors"));
		assertEquals(net.minecraft.tags.BlockTags.WOOL, Tools.blockGroupTag("wool"));
		assertEquals(net.minecraft.tags.BlockTags.PLANKS, Tools.blockGroupTag("planks"));
		assertEquals(net.minecraft.tags.BlockTags.LEAVES, Tools.blockGroupTag("leaves"));
		assertNull(Tools.blockGroupTag("stone"));
	}

	@Test
	void waitRunningLineIncludesProgressAndUnstuckCount() {
		assertEquals("WAIT task_running task=mine block=log mined=11/12 waited=30s",
			Tools.waitRunningLine("mine", "block=log mined=11/12", 30, 0));
		assertEquals("WAIT task_running task=mine block=log mined=11/12 waited=30s unstuck=2",
			Tools.waitRunningLine("mine", "block=log mined=11/12", 30, 2));
		assertEquals("WAIT task_running task=goto",
			Tools.waitRunningLine("goto", "-", -1, 0));
	}
}
