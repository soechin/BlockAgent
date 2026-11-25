package blockagent.game;

import blockagent.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 規劃結果送回遊戲、plan 工具和事件上的步驟提醒(docs/SPEC.md S5.6、S11.7)。 */
class PlanFlowTest {
	@TempDir
	Path tmp;

	@BeforeAll
	static void boot() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	private static JsonObject result(String goal, String... steps) {
		JsonObject o = new JsonObject();
		o.addProperty("goal", goal);
		o.addProperty("recommendation", "先求安全");
		JsonArray arr = new JsonArray();
		for (String s : steps) arr.add(s);
		o.add("steps", arr);
		return o;
	}

	private static JsonObject args(String action) {
		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		return o;
	}

	@Test
	void plannerResultBecomesThePlanAndToolAdvancesIt() throws Exception {
		Agent a = new Agent(new Config(), tmp);
		assertEquals("not in a world", a.acceptPlan(result("過夜", "砍樹")), "不在世界裡不接受");
		a.memory.load(tmp.resolve("memory.json"));

		JsonObject noSteps = new JsonObject();
		noSteps.addProperty("goal", "x");
		assertEquals("steps must be an array", a.acceptPlan(noSteps));
		JsonObject badGoal = result("x", "砍樹");
		badGoal.addProperty("goal", 123);
		assertEquals("goal is required", a.acceptPlan(badGoal), "非字串 goal 不可轉成字串");
		JsonObject badRec = result("x", "砍樹");
		badRec.addProperty("recommendation", true);
		assertEquals("recommendation must be a string", a.acceptPlan(badRec));
		assertNull(a.acceptPlan(result("過第一晚", "砍 16 塊原木", "蓋 5x5 的房子")));
		String events = a.unreadSummary("get_self");
		assertTrue(events.contains("plan_ready:") && events.contains("1. 砍 16 塊原木 2. 蓋 5x5 的房子")
			&& events.contains("plan(action=done)"), events);
		assertEquals("砍 16 塊原木", a.hudStatus().shortGoal());
		assertEquals("過第一晚", a.hudStatus().longGoal());
		assertEquals(" | plan_step=1/2 \"砍 16 塊原木\"", a.planSuffix());

		String done = a.tools.call("plan", args("done")).text();
		assertTrue(done.startsWith("OK step_done step=1") && done.contains("plan_step=2/2"), done);
		assertEquals("蓋 5x5 的房子", a.hudStatus().shortGoal());
		assertTrue(a.tools.call("plan", args("done")).text().startsWith("OK plan_finished"));
		assertFalse(a.tools.call("plan", args("done")).isOk(), "做完之後不能再往下");

		JsonObject set = args("set");
		set.addProperty("goal", "蓋農田");
		set.addProperty("steps", "找水源\n\n犁 9x9 的田\n種小麥");
		String r = a.tools.call("plan", set).text();
		assertTrue(r.contains("plan_step=1/3 \"找水源\""), r);
		assertTrue(a.tools.call("plan", args("show")).text().contains("source=ai"));
		assertEquals("OK plan_cleared", a.tools.call("plan", args("clear")).text());
		assertEquals("", a.planSuffix());
	}

	@Test
	void plannerFailureOnlyNotifies() throws Exception {
		Agent a = new Agent(new Config(), tmp);
		a.memory.load(tmp.resolve("memory.json"));
		assertNull(a.acceptPlan(result("過夜", "砍樹")));
		a.unreadSummary("get_self");
		JsonObject fail = new JsonObject();
		fail.addProperty("goal", "找村莊");
		JsonObject err = new JsonObject();
		err.addProperty("code", "TIMEOUT");
		err.addProperty("message", "規劃超過 120 秒");
		fail.add("error", err);
		assertNull(a.acceptPlan(fail));
		String events = a.unreadSummary("get_self");
		assertTrue(events.contains("plan_failed:") && events.contains("code=TIMEOUT"), events);
		assertEquals("過夜", a.plan.goal(), "失敗時目前計畫不變");

		JsonObject failNoMsg = new JsonObject();
		failNoMsg.addProperty("goal", "找村莊");
		JsonObject errNoMsg = new JsonObject();
		errNoMsg.addProperty("code", "CLI_FAILED");
		failNoMsg.add("error", errNoMsg);
		assertNull(a.acceptPlan(failNoMsg));
		String noMsgEvents = a.unreadSummary("get_self");
		assertTrue(noMsgEvents.contains("code=CLI_FAILED | hint="), "空白訊息不應出現空區段：" + noMsgEvents);
	}

	@Test
	void syncPlanHudRestoresLoadedPlanAndKeepsTextAndThought() throws Exception {
		Agent a = new Agent(new Config(), tmp);
		a.memory.load(tmp.resolve("memory.json"));
		a.loadPlan(tmp.resolve("plan.json"));
		assertNull(a.acceptPlan(result("蓋農田", "找水源", "犁田")));

		Agent reloaded = new Agent(new Config(), tmp);
		reloaded.loadPlan(tmp.resolve("plan.json"));
		reloaded.setHudStatus("觀察中", "先看環境", "", "");
		reloaded.syncPlanHud();
		assertEquals("觀察中", reloaded.hudStatus().text());
		assertEquals("先看環境", reloaded.hudStatus().thought());
		assertEquals("找水源", reloaded.hudStatus().shortGoal());
		assertEquals("蓋農田", reloaded.hudStatus().longGoal());
	}

	@Test
	void snapshotHasEverySection() {
		String s = Agent.planContext("hp=20 food=18", "RUNNING", "plan=none", Map.of("home", "1,64,2"),
			List.of("10:00:00 task_failed mine NOT_FOUND"), List.of("goto", "mine"));
		for (String part : List.of("[self]\nhp=20 food=18", "session=RUNNING", "[current_plan]\nplan=none",
			"- home=\"1,64,2\"", "[recent_problems]\n- 10:00:00 task_failed mine NOT_FOUND", "[player_tools]\ngoto, mine")) {
			assertTrue(s.contains(part), part + "\n" + s);
		}
		String big = Agent.planContext("", "READY", "", Map.of("a", "x".repeat(450), "b", "y".repeat(450), "c", "z".repeat(450),
			"d", "w".repeat(450), "e", "v".repeat(450), "f", "u".repeat(450), "g", "t".repeat(450), "h", "s".repeat(450),
			"i", "r".repeat(450)), List.of(), List.of());
		assertTrue(big.contains("(more entries omitted)") && big.contains("[recent_problems]\nnone"), "記憶太多時截斷");
	}

	@Test
	void problemsKeepOnlyTheLatest() throws Exception {
		Agent a = new Agent(new Config(), tmp);
		for (int i = 0; i < Agent.RECENT_PROBLEMS + 3; i++) a.noteProblem("p" + i);
		var list = a.recentProblems();
		assertEquals(Agent.RECENT_PROBLEMS, list.size());
		assertTrue(list.getLast().endsWith("p" + (Agent.RECENT_PROBLEMS + 2)));
	}
}
