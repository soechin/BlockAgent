package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.BuildCommands;
import blockagent.game.BuildTarget;
import blockagent.game.Creative;
import blockagent.game.RuleGuard;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用帶有原方塊狀態篩選條件的 fill，每批等伺服器確認後才繼續。 */
public final class CommandBuildTask extends Task {
	private static final int MAX_ROW_LENGTH = 16;

	private final ClientLevel level;
	private final String what;
	private final Map<BlockPos, BuildTarget> targets;
	private final boolean blueprint;
	private final LinkedHashMap<BlockPos, BlockState> remaining;
	private final int total;
	private final Set<BlockPos> inFlight = new LinkedHashSet<>();
	private final DoorPlacer doors;
	private long sentAt;
	private Runnable onComplete;
	private String verb = "built";

	public CommandBuildTask(Agent agent, ClientLevel level, String name, String what, Block material,
						Map<BlockPos, BlockState> targets) {
		this(agent, level, name, what, BuildTarget.uniform(targets.keySet(), material), targets, false, Map.of());
	}

	/** {@code doors} 在指令填完之後用一般放置裝上(S4.8a)，門沒辦法用指令放。 */
	public CommandBuildTask(Agent agent, ClientLevel level, String name, String what, Map<BlockPos, BuildTarget> targets,
							Map<BlockPos, BlockState> original, boolean blueprint, Map<BlockPos, BuildTarget> doors) {
		super(agent, name);
		this.level = level;
		this.what = what;
		this.targets = Map.copyOf(targets);
		this.blueprint = blueprint;
		this.remaining = new LinkedHashMap<>();
		for (BlockPos pos : targets.keySet()) this.remaining.put(pos, original.get(pos));
		this.total = remaining.size();
		this.doors = new DoorPlacer(agent, doors);
	}

	/** 完成時的結果(給 AI 的 result=，例如 built、demolished)，以及全部做完時要做的事(例如刪掉藍圖紀錄)。 */
	public CommandBuildTask onComplete(String verb, Runnable action) {
		this.verb = verb;
		this.onComplete = action;
		return this;
	}

	@Override
	public boolean moves() {
		return !doors.isEmpty();
	}

	@Override
	public String describe() {
		return "蓋" + what;
	}

	@Override
	public Progress hudProgress() {
		return new Progress(total - remaining.size(), total);
	}

	@Override
	public String progress() {
		return "blocks=" + (total - remaining.size()) + "/" + total;
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mc.level != level) {
			fail(ErrorCode.INTERRUPTED, "dimension changed");
			return;
		}
		// 已經符合的格子(例如要挖空的格子本來就是空的)不用送指令
		while (!remaining.isEmpty() && inFlight.isEmpty() && targets.get(remaining.firstEntry().getKey()).matches(level.getBlockState(remaining.firstEntry().getKey()))) {
			remaining.pollFirstEntry();
			markProgress();
		}
		if (remaining.isEmpty()) {
			if (!doors.tick(mc, this::path)) return;
			String note = doors.placed() > 0 ? " doors=" + doors.placed() : "";
			if (!doors.failed().isEmpty()) note += " doors_failed=" + String.join(";", doors.failed());
			if (onComplete != null && doors.failed().isEmpty()) onComplete.run();
			done("result=" + verb + note);
			return;
		}
		if (!Creative.isCreative(mc)) {
			fail(ErrorCode.WRONG_GAME_MODE, "no longer in creative mode");
			return;
		}
		if (!BuildCommands.available(mc)) {
			fail(ErrorCode.INTERRUPTED, "server revoked permission for build commands");
			return;
		}
		long ticksSinceSend = agent.tick() - sentAt;
		if (!inFlight.isEmpty()) {
			if (ticksSinceSend >= BuildCommands.CONFIRM_TICKS) fail(ErrorCode.TIMEOUT, "no server confirmation for this batch within 5 s");
			return;
		}
		if (!agent.blockCommands.ready(mc)) return;

		var first = remaining.firstEntry();
		BlockPos start = first.getKey();
		BlockState previous = placeableState(start);
		BuildTarget target = targets.get(start);
		if (previous == null) {
			fail(ErrorCode.PATH_BLOCKED, "target changed, protected or unloaded at " + blockagent.game.Fmt.pos(start));
			return;
		}
		List<BlockPos> row = new ArrayList<>();
		row.add(start);
		Direction direction = sameRun(start.east(), previous, target) ? Direction.EAST : Direction.SOUTH;
		BlockPos next = start.relative(direction);
		while (row.size() < MAX_ROW_LENGTH && sameRun(next, previous, target)) {
			row.add(next);
			next = next.relative(direction);
		}
		if (!agent.blockCommands.fill(mc, start, row.getLast(), previous, target.state())) {
			fail(ErrorCode.INTERRUPTED, "could not continue building");
			return;
		}
		sentAt = agent.tick();
		inFlight.addAll(row);
		for (BlockPos pos : row) {
			agent.tracker.expectCommandChange(level, pos, target::matches, sentAt + BuildCommands.CONFIRM_TICKS, this::confirm);
		}
	}

	private boolean sameRun(BlockPos pos, BlockState previous, BuildTarget target) {
		return target.equals(targets.get(pos)) && placeableState(pos) == previous;
	}

	private BlockState placeableState(BlockPos pos) {
		BlockState expected = remaining.get(pos);
		if (expected == null || !BuildCommands.validTarget(level, pos)) return null;
		BlockState current = level.getBlockState(pos);
		if (blueprint && !level.isUnobstructed(targets.get(pos).state(), pos, CollisionContext.empty())) return null;
		// 前一批可能已讓相連的花草消失。空氣仍可填，其他新出現的方塊要保留。
		return (current == expected || current.isAir()) && agent.guard.canPlaceInto(level, pos).allowed() ? current : null;
	}

	private void confirm(BlockPos pos) {
		if (running() && inFlight.remove(pos)) {
			remaining.remove(pos);
			markProgress();
		}
	}

	private String remainingText() {
		return "remaining=" + remaining.size() + " at="
			+ String.join(";", remaining.keySet().stream().limit(6).map(blockagent.game.Fmt::pos).toList())
			+ (remaining.size() > 6 ? ";..." : "");
	}

	@Override
	protected void onStop(Minecraft mc) {
		// 裝門時會用 Baritone 走路(H12)
		Bari.stopAll();
		if (status == Status.CANCELLED) failReason += "; confirmed " + (total - remaining.size()) + "/" + total + "; " + remainingText();
		else if (status == Status.FAILED && !remaining.isEmpty()) failReason += "; " + remainingText();
	}
}
