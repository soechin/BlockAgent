package blockagent.game.tasks;

import blockagent.core.Event;
import blockagent.core.EventQueue;
import blockagent.game.ActionLog;
import blockagent.game.Agent;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import blockagent.mcp.ToolResult;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 同一時間只有一個背景任務(docs/SPEC.md S4.3)。
 */
public final class TaskRunner {
	private static final int PROGRESS_INTERVAL_TICKS = 15 * 20;

	private final Agent agent;
	private volatile Task current;
	// Give waiters a stable identity until the completion event has been queued.
	private volatile Task pendingResult;

	public TaskRunner(Agent agent) {
		this.agent = agent;
	}

	public boolean hasActiveTask() {
		return current != null;
	}

	public Task current() {
		return current;
	}

	public Task pendingResult() {
		return pendingResult;
	}

	public String describeCurrent() {
		Task t = current;
		return t == null ? null : t.describe();
	}

	public boolean openedContainer() {
		Task t = current;
		return t != null && t.openedContainer;
	}

	/**
	 * 要做事前先讓出位置(client thread)：沒有任務時回 null；有任務時 replace=false 回 BUSY，
	 * replace=true 取消舊任務再回 null。不是背景任務、但會搶快捷列和視角的動作也要先呼叫(P2-5)。
	 */
	public ToolResult claim(String name, boolean replace) {
		// 反射正在接手(保命、撤退、自衛、進食)時不開始新的事，免得跟反射搶 Baritone 和按鍵(H7)
		var reflex = agent.reflexes.active();
		if (reflex != null && reflex != blockagent.game.reflex.Reflexes.Kind.UNSTUCK) {
			return ToolResult.error(ErrorCode.BUSY, Kv.of().put("reflex", reflex.id()), "the " + reflex.id() + " reflex is running",
				"wait_events a few seconds until the reflex event, then try again");
		}
		if (current == null) return null;
		if (!replace) {
			return ToolResult.error(ErrorCode.BUSY, Kv.of().put("task", current.name()).raw(current.progress()),
				"another task is running");
		}
		stop(Minecraft.getInstance(), Task.Status.CANCELLED, "replaced by " + name);
		agent.reflexes.dropUnstuck(Minecraft.getInstance(), "task_replaced");
		return null;
	}

	/** 開始一個任務(client thread)。preflight 已經在呼叫前做完。 */
	public ToolResult start(Task t, boolean replace, String preflightText) {
		Minecraft mc = Minecraft.getInstance();
		// 先檢查會不會被拒絕，再取消舊任務(M14：坐在載具上時 replace=true 不該白白取消舊任務；P3-M13 用 requiresFoot)
		if (t.requiresFoot() && mc.player != null && mc.player.getVehicle() != null) {
			return ToolResult.error(ErrorCode.INVALID_ARGUMENT, Kv.of().put("riding", blockagent.game.Fmt.entityId(mc.player.getVehicle())),
				"cannot start a walking task while riding", "dismount first");
		}
		ToolResult busy = claim(t.name(), replace);
		if (busy != null) return busy;
		// 舊任務的脫困反射跟著結束，不要繼續跳、挖
		agent.reflexes.dropUnstuck(mc, "task_replaced");
		t.startTick = agent.tick();
		t.lastProgressTick = t.startTick;
		current = t;
		pendingResult = t;
		agent.log.log(ActionLog.Source.ai, "task_start", t.name() + " " + t.progress());
		try {
			t.start(mc);
		} catch (RuntimeException e) {
			t.fail(ErrorCode.INTERNAL, "error while starting: " + e);
		}
		agent.idle.touch();
		JsonObject data = new JsonObject();
		data.addProperty("task", t.name());
		if (!t.running()) {
			// 一開始就結束了(例如創造模式一碰就碎、那格本來就是空的)：直接回報結果，不再另外送事件
			finish(mc, false);
			if (t.status == Task.Status.DONE) {
				data.addProperty("status", "done");
				return ToolResult.ok(Kv.of("OK done").add(doneKv(mc, t)).toString(), data);
			}
			return ToolResult.error(t.failCode == null ? ErrorCode.INTERRUPTED : t.failCode,
				Kv.of().put("task", t.name()).raw(t.progress()), t.failReason, t.failHint);
		}
		data.addProperty("status", "running");
		return ToolResult.ok(Kv.of("OK started").put("task", t.name()).raw(preflightText).toString(), data);
	}

	public void tick(Minecraft mc) {
		Task t = current;
		if (t == null || t.suspended) return;
		if (t.elapsed() > t.timeoutTicks) {
			t.fail(ErrorCode.TIMEOUT, "task exceeded " + agent.config.taskTimeoutMinutes + " min");
		} else {
			try {
				t.tick(mc);
			} catch (RuntimeException e) {
				t.fail(ErrorCode.INTERNAL, "error while running: " + e);
			}
		}
		if (!t.running()) {
			finish(mc, true);
		} else if (t.elapsed() > 0 && t.elapsed() % PROGRESS_INTERVAL_TICKS == 0) {
			agent.events.add(EventQueue.TASK_PROGRESS, Event.Level.BATCH, Kv.of().put("task", t.name()).raw(t.progress()).toString());
		}
	}

	private void finish(Minecraft mc, boolean notify) {
		try {
			finishCurrent(mc, notify);
		} finally {
			pendingResult = null;
		}
	}

	private void finishCurrent(Minecraft mc, boolean notify) {
		Task t = current;
		current = null;
		try {
			t.onStop(mc);
		} catch (RuntimeException ignored) {
			// 收尾失敗不影響結果回報
		}
		agent.idle.touch();
		// 結果事件已附上最後的進度，還沒送出的進度事件都過時了
		agent.events.removeType(EventQueue.TASK_PROGRESS);
		switch (t.status) {
			case DONE -> {
				if (notify) agent.events.add("task_done", Event.Level.PROMPT, doneKv(mc, t) + agent.planSuffix());
				agent.log.log(ActionLog.Source.ai, "task_done", t.name() + " " + t.progress());
			}
			case FAILED -> {
				if (notify) agent.events.add("task_failed", Event.Level.PROMPT, endLine(mc, t, t.failCode) + agent.planSuffix());
				agent.noteProblem("task_failed " + t.name() + " " + t.failCode + " " + t.failReason);
				agent.log.log(ActionLog.Source.ai, "task_failed", t.name() + " " + t.failCode + " " + t.failReason + " " + t.progress());
			}
			case CANCELLED -> {
				if (notify) agent.events.add("task_cancelled", Event.Level.PROMPT, endLine(mc, t, null) + agent.planSuffix());
				agent.log.log(ActionLog.Source.ai, "task_cancelled", t.name() + " " + t.failReason + " " + t.progress());
			}
			default -> {
			}
		}
	}

	/**
	 * 完成的結果(S4.3)：{@code task=<name> <進度> <結果> hp=.. food=.. pos=..}。
	 * 附上自己的狀況，AI 不必為了看血量、位置再呼叫 get_self。
	 */
	static Kv doneKv(Minecraft mc, Task t) {
		return Kv.of().put("task", t.name()).raw(t.progress()).raw(t.resultText)
			.add(blockagent.game.Fmt.status(mc == null ? null : mc.player));
	}

	/** 失敗或停止的事件：{@code task=<name> code=<CODE> <進度> hp=.. food=.. pos=.. | <原因> | hint=..}(S3.0d)。 */
	static String endLine(Minecraft mc, Task t, ErrorCode code) {
		Kv kv = Kv.of().put("task", t.name()).put("code", code).raw(t.progress())
			.add(blockagent.game.Fmt.status(mc == null ? null : mc.player));
		String reason = t.failReason == null || t.failReason.isBlank() ? "stopped" : t.failReason.strip().replace('\n', ' ');
		// 跟 ToolResult.error 一樣：null 用錯誤碼的預設，空字串表示不給
		String hint = t.failHint != null ? t.failHint : code == null ? null : code.hint();
		return kv + " | " + reason + (hint == null || hint.isBlank() ? "" : " | hint=" + hint);
	}

	private void stop(Minecraft mc, Task.Status status, String reason) {
		Task t = current;
		if (t == null) return;
		t.status = status;
		t.failReason = reason;
		if (status == Task.Status.FAILED) t.failCode = ErrorCode.INTERRUPTED;
		finish(mc, true);
	}

	/** 取消目前任務(暫停、接手、stop 工具、結束工作階段)。 */
	public void cancel(String reason) {
		Minecraft mc = Minecraft.getInstance();
		agent.reflexes.dropUnstuck(mc, "task_cancelled");
		if (current == null) return;
		stop(mc, Task.Status.CANCELLED, reason);
	}

	/** 被反射打斷而結束(保命、撤退類)。 */
	public void interrupt(String reflex) {
		Task t = current;
		if (t == null) return;
		t.status = Task.Status.FAILED;
		t.failCode = ErrorCode.INTERRUPTED;
		t.failReason = "interrupted by the " + reflex + " reflex";
		finish(Minecraft.getInstance(), true);
	}

	/** 反射暫時接手(自衛、進食)，結束後 resume。 */
	public boolean suspend() {
		Task t = current;
		if (t == null || t.suspended) return false;
		t.suspended = true;
		t.suspendedAt = agent.tick();
		try {
			t.onSuspend(Minecraft.getInstance());
		} catch (RuntimeException e) {
			t.fail(ErrorCode.INTERNAL, "error while pausing for a reflex: " + e);
		}
		return true;
	}

	/** 反射結束：暫停的時間不算進逾時和「多久沒進展」(H7)，再讓任務接著做(重新開容器、拿東西…)。 */
	public void resume() {
		Task t = current;
		if (t == null || !t.suspended) return;
		t.suspended = false;
		long paused = Math.max(0, agent.tick() - t.suspendedAt);
		t.startTick += paused;
		t.lastProgressTick += paused;
		t.onShiftTimers(paused);
		Minecraft mc = Minecraft.getInstance();
		if (t.running()) {
			try {
				t.onResume(mc);
			} catch (RuntimeException e) {
				t.fail(ErrorCode.INTERNAL, "error while resuming after a reflex: " + e);
			}
		}
		if (!t.running()) finish(mc, true);
	}

	public void onSelfBroke(BlockPos pos, BlockState before) {
		if (current != null) current.onSelfBroke(pos, before);
	}

	public void onSelfPlaced(BlockPos pos, BlockState state) {
		if (current != null) current.onSelfPlaced(pos, state);
	}

	public void onSystemMessage(String text) {
		if (current != null) current.onSystemMessage(text);
	}
}
