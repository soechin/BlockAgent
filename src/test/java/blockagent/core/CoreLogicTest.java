package blockagent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 不依賴 Minecraft 的核心邏輯(docs/SPEC.md S5、S6、S7、S8.1)。 */
class CoreLogicTest {
	static final class FakeClock implements Clock {
		final AtomicLong now = new AtomicLong(1_000_000);

		@Override
		public long millis() {
			return now.get();
		}

		void advance(long ms) {
			now.addAndGet(ms);
		}
	}

	// ---- Session(S8.1) ----

	@Test
	void sessionLifecycle() {
		Session s = new Session();
		assertEquals(Session.State.NO_WORLD, s.state());
		s.enterWorld();
		assertEquals(Session.State.READY, s.state());
		assertTrue(s.inWorldSession());
		assertEquals(Session.State.RUNNING, s.toggle("key"));
		assertEquals(Session.State.PAUSED, s.toggle("key"));
		assertEquals(Session.State.RUNNING, s.toggle("key"));
		assertTrue(s.pause("takeover"));
		assertFalse(s.pause("takeover"));
		s.end(Session.EndReason.DISCONNECTED);
		assertEquals(Session.State.ENDED, s.state());
		assertEquals(Session.EndReason.DISCONNECTED, s.endReason());
		assertEquals(Session.State.ENDED, s.toggle("key"), "結束後按鍵不能重新開始");
		s.enterWorld();
		assertEquals(Session.State.READY, s.state());
		assertNull(s.endReason());
	}

	// ---- EventQueue(S5.2、S5.4) ----

	@Test
	void urgentIsReadyImmediately() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add("chat", Event.Level.BATCH, "Ming：好餓");
		assertFalse(q.ready());
		q.add("attacked", Event.Level.URGENT, "被殭屍攻擊");
		assertTrue(q.ready());
	}

	@Test
	void promptWaitsTwoSeconds() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add("task_done", Event.Level.PROMPT, "mine 完成");
		assertFalse(q.ready());
		c.advance(EventQueue.PROMPT_DELAY_MS);
		assertTrue(q.ready());
	}

	@Test
	void batchDeliveredAtMostEvery15Seconds() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add("chat", Event.Level.BATCH, "a");
		assertFalse(q.ready());
		c.advance(EventQueue.BATCH_INTERVAL_MS);
		assertTrue(q.ready());
		assertEquals(1, q.drainAll().size());
		q.add("chat", Event.Level.BATCH, "b");
		c.advance(EventQueue.BATCH_INTERVAL_MS - 1);
		assertFalse(q.ready());
		c.advance(1);
		assertTrue(q.ready());
	}

	@Test
	void taskWaitIgnoresProgressButNotOtherBatch() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add(EventQueue.TASK_PROGRESS, Event.Level.BATCH, "smelt 進度：拿到 2/8 個");
		q.add("chat", Event.Level.BATCH, "system=\"Player 已完成進度 [石器時代]\"");
		q.add("hostile_near", Event.Level.BATCH, "zombie id=1 dist=16");
		q.add("reflex", Event.Level.BATCH, "reflex=unstuck result=done");
		c.advance(EventQueue.BATCH_INTERVAL_MS);
		assertTrue(q.ready());
		assertFalse(q.ready(EventQueue::wakesTaskWait), "進度、系統廣播、遠怪察覺與非緊急反射不應打斷進行中的任務");
		q.add("damaged", Event.Level.BATCH, "受傷 -3 血");
		assertTrue(q.ready(EventQueue::wakesTaskWait));
		// 叫醒時佇列裡的事件一併送出
		assertEquals(5, q.drainAll().size());
		// 玩家聊天與緊急反射照常叫醒
		q.add("chat", Event.Level.BATCH, "Ming=\"過來一下\"");
		c.advance(EventQueue.BATCH_INTERVAL_MS);
		assertTrue(q.ready(EventQueue::wakesTaskWait), "玩家聊天仍應叫醒");
		q.drainAll();
		q.add("reflex", Event.Level.URGENT, "reflex=retreat result=done");
		assertTrue(q.ready(EventQueue::wakesTaskWait), "緊急反射仍應叫醒");
		q.drainAll();
		q.add(EventQueue.TASK_PROGRESS, Event.Level.BATCH, "smelt 進度：拿到 4/8 個");
		q.add("task_done", Event.Level.PROMPT, "smelt 完成");
		c.advance(EventQueue.PROMPT_DELAY_MS);
		assertTrue(q.ready(EventQueue::wakesTaskWait));
	}

	@Test
	void awaitWithFilterTimesOutOnProgressOnly() throws Exception {
		EventQueue q = new EventQueue(Clock.SYSTEM);
		q.add(EventQueue.TASK_PROGRESS, Event.Level.URGENT, "不會叫醒");
		long t0 = System.currentTimeMillis();
		List<Event> got = q.await(200, () -> true, EventQueue::wakesTaskWait);
		assertTrue(System.currentTimeMillis() - t0 >= 190);
		assertTrue(got.isEmpty());
		assertEquals(1, q.size());
	}

	@Test
	void removeTypeDropsStaleProgress() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add(EventQueue.TASK_PROGRESS, Event.Level.BATCH, "mine 進度：mined 4/8");
		q.add("chat", Event.Level.BATCH, "Ming「嗨」");
		q.removeType(EventQueue.TASK_PROGRESS);
		List<Event> left = q.drainAll();
		assertEquals(1, left.size());
		assertEquals("chat", left.getFirst().type());
	}

	@Test
	void renderMergesSameGroup() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add("chat", Event.Level.BATCH, "Ming=\"好餓\"");
		q.add("chat", Event.Level.BATCH, "Amy=\"哈哈\"");
		q.add("user_paused", Event.Level.URGENT, "all actions stopped");
		String text = EventQueue.render(q.drainAll(), 0);
		assertEquals("[EVENTS]\n[URGENT] user_paused: all actions stopped\n[BATCH] chat(2): Ming=\"好餓\"; Amy=\"哈哈\"", text);
		assertEquals("", EventQueue.render(List.of(), 0));
	}

	/** A15：每行都帶出等級和事件型別，AI 才能依型別分支(S3.0e)。 */
	@Test
	void renderShowsLevelAndType() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add("task_done", Event.Level.PROMPT, "task=mine mined=8/8");
		q.add("task_failed", Event.Level.PROMPT, "task=goto code=PATH_BLOCKED | no path\nsecond line");
		q.add("idle_reminder", Event.Level.PROMPT, "");
		q.add("damaged", Event.Level.BATCH, "lost=2 hp=15");
		q.add(new Event("hostile_near", Event.Level.BATCH, "zombie id=1", null, 0));
		q.add(new Event("hostile_near", Event.Level.BATCH, "skeleton id=2", null, 0));
		String text = EventQueue.render(q.drainAll(), 3);
		List<String> lines = text.lines().toList();
		assertEquals(List.of(
			"[EVENTS]",
			"[WARN] events_dropped: n=3",
			"[PROMPT] task_done: task=mine mined=8/8",
			"[PROMPT] task_failed: task=goto code=PATH_BLOCKED | no path second line",
			"[PROMPT] idle_reminder:",
			"[BATCH] damaged: lost=2 hp=15",
			"[BATCH] hostile_near(2): zombie id=1; skeleton id=2"), lines);
		assertEquals("[EVENTS]\n[WARN] events_dropped: n=1", EventQueue.render(List.of(), 1));
	}

	@Test
	void capacityDropsOldestBatchFirst() {
		FakeClock c = new FakeClock();
		EventQueue q = new EventQueue(c);
		q.add("user_paused", Event.Level.URGENT, "keep");
		for (int i = 0; i < EventQueue.CAPACITY + 5; i++) q.add("chat", Event.Level.BATCH, "m" + i);
		assertEquals(EventQueue.CAPACITY, q.size());
		assertEquals(6, q.takeDroppedCount());
		assertTrue(q.drainAll().stream().anyMatch(e -> e.text().equals("keep")));
	}

	@Test
	void awaitReturnsOnUrgentAndTimesOut() throws Exception {
		EventQueue q = new EventQueue(Clock.SYSTEM);
		long t0 = System.currentTimeMillis();
		assertTrue(q.await(200).isEmpty());
		assertTrue(System.currentTimeMillis() - t0 >= 190);
		Thread th = new Thread(() -> {
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {
			}
			q.add("health_low", Event.Level.URGENT, "血量 4");
		});
		th.start();
		t0 = System.currentTimeMillis();
		List<Event> got = q.await(5000);
		assertEquals(1, got.size());
		assertTrue(System.currentTimeMillis() - t0 < 1000);
	}

	@Test
	void idleReminder() {
		FakeClock c = new FakeClock();
		IdleTracker t = new IdleTracker(c, 60_000);
		c.advance(59_999);
		assertFalse(t.poll(false));
		c.advance(1);
		assertTrue(t.poll(false));
		assertFalse(t.poll(false), "提醒後重新計時");
		c.advance(120_000);
		assertFalse(t.poll(true), "有任務時不提醒");
	}

	// ---- ChatPolicy(S6) ----

	@Test
	void chatAddsPrefixAndBlocksCommands() {
		FakeClock c = new FakeClock();
		ChatPolicy p = new ChatPolicy(c, true, 120_000);
		assertEquals(new ChatPolicy.Ok("[AI] 你好"), p.prepare("你好"));
		c.advance(120_000);
		for (String cmd : List.of("/give @s diamond", "  /op me", "​/kill", "§a/kill", "\n/tp 0 0 0")) {
			var r = p.prepare(cmd);
			assertInstanceOf(ChatPolicy.Rejected.class, r, cmd);
			assertEquals("CHAT_BLOCKED", ((ChatPolicy.Rejected) r).code(), cmd);
		}
	}

	@Test
	void chatRateLimit() {
		FakeClock c = new FakeClock();
		ChatPolicy p = new ChatPolicy(c, true, 120_000);
		assertInstanceOf(ChatPolicy.Ok.class, p.prepare("第一句"));
		c.advance(40_000);
		var r = p.prepare("第二句");
		assertEquals("RATE_LIMITED", ((ChatPolicy.Rejected) r).code());
		assertTrue(((ChatPolicy.Rejected) r).reason().contains("40s since last message"), ((ChatPolicy.Rejected) r).reason());
		assertTrue(((ChatPolicy.Rejected) r).reason().contains("wait 80s"), ((ChatPolicy.Rejected) r).reason());
		c.advance(80_000);
		assertInstanceOf(ChatPolicy.Ok.class, p.prepare("第三句"));
	}

	@Test
	void blockedMessagesDoNotConsumeRateLimit() {
		FakeClock c = new FakeClock();
		ChatPolicy p = new ChatPolicy(c, true, 120_000);
		p.prepare("/x");
		assertInstanceOf(ChatPolicy.Ok.class, p.prepare("正常"));
	}

	@Test
	void chatDisabledAndTruncationKeepsPrefix() {
		FakeClock c = new FakeClock();
		ChatPolicy p = new ChatPolicy(c, false, 0);
		assertEquals("CHAT_DISABLED", ((ChatPolicy.Rejected) p.prepare("x")).code());
		p.setEnabled(true);
		String msg = ((ChatPolicy.Ok) p.prepare("字".repeat(400))).message();
		assertEquals(ChatPolicy.MAX_LENGTH, msg.length());
		assertTrue(msg.startsWith(ChatPolicy.PREFIX));
		assertTrue(ChatPolicy.isAllowedOutgoing(msg));
		assertFalse(ChatPolicy.isAllowedOutgoing("沒有前綴"));
	}

	@Test
	void privateMessagePatterns() {
		PrivateMessageFilter f = new PrivateMessageFilter(List.of("^\\{私\\}.*"));
		assertTrue(f.isPrivate("Ming whispers to you: 秘密"));
		assertTrue(f.isPrivate("[Ming -> me] 秘密"));
		assertTrue(f.isPrivate("{私} 自訂格式"));
		assertFalse(f.isPrivate("<Ming> 好餓"));
		assertFalse(f.isPrivate("Ming joined the game"));
		assertTrue(f.isPrivate("Ming whispers to you: 第一行\n第二行"), "多行私訊也要比對得到");
	}

	@Test
	void privateMessagePatternsSkipInvalidRegex() {
		PrivateMessageFilter f = new PrivateMessageFilter(java.util.Arrays.asList("([壞掉", null, "^\\{私\\}.*"));
		assertEquals(List.of("([壞掉"), f.invalidPatterns());
		assertTrue(f.isPrivate("{私} 還是有效"));
	}

	// ---- BlockClassifier(S7.3a) ----

	@Test
	void naturalWhitelist() throws Exception {
		BlockClassifier c = BlockClassifier.fromJson(getClass().getResourceAsStream("/blockagent/natural_blocks.json"));
		assertTrue(c.isNatural("minecraft:stone", Set.of("minecraft:base_stone_overworld")));
		assertTrue(c.isNatural("minecraft:oak_log", Set.of("minecraft:logs", "minecraft:oak_logs")));
		// M7：削皮原木、木塊、染色陶土是人造的；惡地會生成的陶土顏色是天然的
		assertFalse(c.isNatural("minecraft:stripped_oak_log", Set.of("minecraft:logs", "minecraft:oak_logs")));
		assertFalse(c.isNatural("minecraft:oak_wood", Set.of("minecraft:logs", "minecraft:oak_logs")));
		assertFalse(c.isNatural("minecraft:blue_terracotta", Set.of("minecraft:terracotta")));
		assertTrue(c.isNatural("minecraft:red_terracotta", Set.of("minecraft:terracotta", "minecraft:badlands_terracotta")));
		assertTrue(c.isNatural("minecraft:crimson_stem", Set.of("minecraft:logs", "minecraft:crimson_stems")));
		assertTrue(c.isNatural("minecraft:gravel", Set.of()));
		assertFalse(c.isNatural("minecraft:oak_planks", Set.of("minecraft:planks")));
		assertFalse(c.isNatural("minecraft:cobblestone", Set.of()));
		assertFalse(c.isNatural("minecraft:wheat", Set.of("minecraft:crops")));
		assertFalse(c.isNatural("minecraft:farmland", Set.of()));
		assertFalse(c.isNatural("somemod:unknown", Set.of()), "不認得的方塊算人造");
		// 地獄疙瘩只在地獄算天然(堡壘生成)；主世界的是別人種的
		assertTrue(c.isNatural("minecraft:nether_wart", Set.of(), "minecraft:the_nether"));
		assertFalse(c.isNatural("minecraft:nether_wart", Set.of(), "minecraft:overworld"));
		assertFalse(c.isNatural("minecraft:nether_wart", Set.of()));
		assertFalse(c.isNatural("minecraft:nether_bricks", Set.of(), "minecraft:the_nether"), "堡壘的地獄磚仍是人造");
		assertTrue(c.isNatural("minecraft:netherrack", Set.of("minecraft:base_stone_nether"), "minecraft:overworld"));
	}

	// ---- OwnershipLedger(S7.4、S7.6) ----

	@Test
	void packRoundTrip() {
		for (int[] p : new int[][]{{0, 0, 0}, {-1, -64, -1}, {30_000_000 - 1, 319, -30_000_000}, {123, -5, 456}}) {
			long v = OwnershipLedger.pack(p[0], p[1], p[2]);
			assertEquals(p[0], OwnershipLedger.unpackX(v));
			assertEquals(p[1], OwnershipLedger.unpackY(v));
			assertEquals(p[2], OwnershipLedger.unpackZ(v));
		}
	}

	private static final String OW = OwnershipLedger.OVERWORLD, NETHER = "minecraft:the_nether";

	@Test
	void placementNeedsServerConfirmation() {
		OwnershipLedger l = new OwnershipLedger();
		long a = OwnershipLedger.pack(1, 64, 1);
		l.expectPlacement(OW, a, "minecraft:oak_planks", 100);
		l.onBlockUpdate(OW, a, "minecraft:dirt", false, 102, false);
		assertFalse(l.isOwned(OW, a), "伺服器回傳的方塊不同，不算");
		long b = OwnershipLedger.pack(2, 64, 1);
		l.expectPlacement(OW, b, "minecraft:oak_planks", 100);
		l.onBlockUpdate(OW, b, "minecraft:oak_planks", false, 100 + OwnershipLedger.CONFIRM_TICKS + 1, false);
		assertFalse(l.isOwned(OW, b), "太晚確認，不算");
		long c = OwnershipLedger.pack(3, 64, 1);
		l.expectPlacement(OW, c, "minecraft:chest", 100);
		l.onBlockUpdate(OW, c, "minecraft:chest", false, 101, true);
		assertTrue(l.isOwned(OW, c));
		l.onBlockUpdate(OW, c, "minecraft:chest", false, 200, false);
		assertTrue(l.isOwned(OW, c), "同一種方塊的狀態變化不影響");
		l.onBlockUpdate(OW, c, "minecraft:stone", false, 300, false);
		assertFalse(l.isOwned(OW, c), "被別人換掉");
	}

	/** C2：同座標在不同維度互不影響。 */
	@Test
	void ledgerKeepsDimensionsApart() {
		OwnershipLedger l = new OwnershipLedger();
		long p = OwnershipLedger.pack(10, 64, 10);
		l.expectPlacement(OW, p, "minecraft:oak_planks", 1);
		l.onBlockUpdate(OW, p, "minecraft:oak_planks", false, 2, true);
		assertTrue(l.isOwned(OW, p));
		assertFalse(l.isOwned(NETHER, p), "主世界的方塊不能讓地獄同座標變成自己的");
		// 地獄同座標的方塊更新、自己挖掉，都不能動到主世界的所有權
		l.onBlockUpdate(NETHER, p, "minecraft:netherrack", false, 50, false);
		l.onBlockUpdate(NETHER, p, "minecraft:air", true, 60, true);
		l.onSelfBroke(NETHER, p);
		assertTrue(l.isOwned(OW, p), "地獄的變化刪掉了主世界的所有權");
		// 放置確認也分維度
		l.expectPlacement(NETHER, p, "minecraft:cobblestone", 100);
		l.onBlockUpdate(OW, p, "minecraft:cobblestone", false, 101, true);
		assertFalse(l.isOwned(NETHER, p), "主世界的更新不能確認地獄的放置");
	}

	/** R4：剛送出放置、尚未收到伺服器確認的同種方塊可視為自己剛放的，避免墊腳競態誤報。 */
	@Test
	void pendingSelfMatchesWithinWindowAndDimension() {
		OwnershipLedger l = new OwnershipLedger();
		long p = OwnershipLedger.pack(81, 60, 205);
		l.expectPlacement(OW, p, "minecraft:cobblestone", 100);
		assertTrue(l.isPendingSelf(OW, p, "minecraft:cobblestone", 101));
		assertTrue(l.isPendingSelf(OW, p, "minecraft:cobblestone", 100 + OwnershipLedger.CONFIRM_TICKS));
		assertFalse(l.isPendingSelf(OW, p, "minecraft:dirt", 101), "方塊種類不同不能算");
		assertFalse(l.isPendingSelf(OW, p, "minecraft:cobblestone", 100 + OwnershipLedger.CONFIRM_TICKS + 1), "逾期不能算");
		assertFalse(l.isPendingSelf(NETHER, p, "minecraft:cobblestone", 101), "不同維度不能算");
		l.onBlockUpdate(OW, p, "minecraft:cobblestone", false, 102, true);
		assertTrue(l.isOwned(OW, p));
		assertFalse(l.isPendingSelf(OW, p, "minecraft:cobblestone", 102), "確認進帳本後移除 pending");
	}

	@Test
	void ledgerPersistence() {
		OwnershipLedger l = new OwnershipLedger();
		long a = OwnershipLedger.pack(-5, 70, 9);
		l.expectPlacement(OW, a, "minecraft:furnace", 1);
		l.onBlockUpdate(OW, a, "minecraft:furnace", false, 2, true);
		l.expectPlacement(NETHER, a, "minecraft:chest", 1);
		l.onBlockUpdate(NETHER, a, "minecraft:chest", false, 2, true);
		OwnershipLedger r = new OwnershipLedger();
		r.loadJson(l.toJson());
		assertTrue(r.isOwned(OW, a));
		assertEquals("minecraft:furnace", r.ownedBlockId(OW, a));
		assertEquals("minecraft:chest", r.ownedBlockId(NETHER, a));
		assertEquals(2, r.ownedBlockCount());
	}

	/** 舊版 ownership.json(沒有分維度)一律算主世界。 */
	@Test
	void legacyLedgerIsOverworld() {
		var legacy = com.google.gson.JsonParser.parseString("{\"blocks\":[[1,64,-2,\"minecraft:oak_planks\"]]}").getAsJsonObject();
		OwnershipLedger l = new OwnershipLedger();
		l.loadJson(legacy);
		long p = OwnershipLedger.pack(1, 64, -2);
		assertTrue(l.isOwned(OW, p));
		assertFalse(l.isOwned(NETHER, p));
		assertTrue(l.toJson().has("dims"), "存回去是新格式");
	}

	// ---- 世界鍵(S4.8) ----

	@Test
	void worldKeyKeepsNonAsciiWorldsApart() {
		assertEquals("sp-My_World-1.2", blockagent.game.WorldKey.folderKey("My_World-1.2"));
		String a = blockagent.game.WorldKey.folderKey("新的世界"), b = blockagent.game.WorldKey.folderKey("我的世界");
		assertTrue(a.matches("sp-____-[0-9a-f]{8}"), a);
		assertNotEquals(a, b);
		assertEquals(a, blockagent.game.WorldKey.folderKey("新的世界"));
	}

	@Test
	void serverAddressDropsDefaultPort() {
		assertEquals("play.example.com", blockagent.game.WorldKey.serverAddress(" Play.Example.com:25565 "));
		assertEquals("play.example.com", blockagent.game.WorldKey.serverAddress("play.example.com."));
		assertEquals("play.example.com:25566", blockagent.game.WorldKey.serverAddress("play.example.com:25566"));
		assertEquals("[::1]", blockagent.game.WorldKey.serverAddress("[::1]:25565"));
		assertEquals("[::1]:1234", blockagent.game.WorldKey.serverAddress("[::1]:1234"));
		assertEquals("", blockagent.game.WorldKey.serverAddress(null));
	}

	// ---- 時間(S3.2、M17) ----

	@Test
	void dayClockPhasesMatchVanilla() {
		assertEquals("day", DayClock.phase(0));
		assertEquals("day", DayClock.phase(11999));
		assertEquals("dusk", DayClock.phase(12000));
		assertEquals("dusk", DayClock.phase(13187));
		assertEquals("night", DayClock.phase(13188));
		assertEquals("night", DayClock.phase(22799));
		assertEquals("dawn", DayClock.phase(22800));
		assertEquals("dawn", DayClock.phase(23459));
		assertEquals("day", DayClock.phase(23460));
		assertEquals("day", DayClock.phase(24000 * 5 + 100), "多天累積的時鐘也要取餘數");
		assertFalse(DayClock.isDark(23460));
		assertTrue(DayClock.isDark(12000));
	}

	@Test
	void dayClockCountdownRoundsAndUsesSeconds() {
		// 舊版 t=10101 就印 dusk_in=1min；離黃昏還有 1899 tick(約 95 秒)
		assertEquals("time=day dusk_in=95s", DayClock.describe(10101));
		assertEquals("time=day dusk_in=10min", DayClock.describe(0));
		assertEquals("time=day dusk_in=2min", DayClock.describe(12000 - 2400));
		assertEquals("time=dusk night_in=59s", DayClock.describe(12000));
		assertEquals("time=night day_in=9min", DayClock.describe(13188));
		assertEquals("time=dawn day_in=33s", DayClock.describe(22800));
		assertEquals("time=day dusk_in=10min", DayClock.describe(23460), "23460 之後倒數到隔天黃昏");
		assertEquals("1s", DayClock.duration(3));
		assertEquals("time=day", DayClock.brief(1000));
		assertEquals("time=day dusk_in=90s", DayClock.brief(12000 - 1800));
	}

	@Test
	void dayClockSleepWindowAndCountdown() {
		assertFalse(DayClock.canSleep(12000, false));
		assertFalse(DayClock.canSleep(12541, false));
		assertTrue(DayClock.canSleep(12542, false));
		assertTrue(DayClock.canSleep(23459, false));
		assertFalse(DayClock.canSleep(23460, false));
		assertTrue(DayClock.canSleep(1000, true), "雷雨隨時可睡");
		assertEquals(542, DayClock.ticksUntilSleep(12000));
		assertEquals("sleep_in=27s", DayClock.sleepCountdown(12000));
		assertEquals(0, DayClock.ticksUntilSleep(12542));
		assertNull(DayClock.sleepCountdown(12542));
		assertEquals(24000 - 23460 + 12542, DayClock.ticksUntilSleep(23460));
	}
}
