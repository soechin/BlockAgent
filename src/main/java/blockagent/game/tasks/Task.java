package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 背景任務(docs/SPEC.md S4.3)。所有方法都在 client thread 執行。
 * 任務中途被擋下或失敗時直接結束，並說明做到哪裡、為什麼停(S4.5)，不會自己換方法。
 */
public abstract class Task {
	public enum Status {RUNNING, DONE, FAILED, CANCELLED}

	protected final Agent agent;
	protected final String name;
	long startTick;
	/** 最後一次有進展(放下、挖掉、收成一格…)的 tick；脫困反射用它分辨「站著做事」和「真的卡住」。 */
	long lastProgressTick;
	/** 任務實際推進的次數(僅 markProgress 遞增，不受暫停平移影響)；脫困反射用來判斷是否陷入原地死循環。 */
	private int progressCount;
	long timeoutTicks;
	Status status = Status.RUNNING;
	String resultText;
	ErrorCode failCode;
	String failReason;
	/** 失敗時建議的下一步；null 用錯誤碼的預設(S3.0d)。 */
	String failHint;
	boolean suspended;
	/** 被反射暫停的 tick(恢復時把暫停的時間從逾時裡扣掉)。 */
	long suspendedAt;
	boolean openedContainer;
	private baritone.api.pathing.goals.Goal lastGoal;

	protected Task(Agent agent, String name) {
		this.agent = agent;
		this.name = name;
		this.timeoutTicks = agent.config.taskTimeoutMinutes * 60L * 20L;
	}

	public String name() {
		return name;
	}

	/** 只給 HUD 用(給人看，繁體中文)：寫動作和遊戲裡的中文名稱，例如「挖 鐵礦」；不帶進度和座標。 */
	public abstract String describe();

	/** 做到哪裡(給 AI 看的 KV，S3.0)，例如 {@code mined=4/10}。 */
	public abstract String progress();

	/** HUD 在正文後面顯示的 done/total(S8.3)。 */
	public record Progress(int done, int total) {}

	/** 只給 HUD 用；有計數的任務覆寫，數字跟 {@link #progress()} 一致。沒有計數回 null。 */
	public Progress hudProgress() {
		return null;
	}

	/** 物品在遊戲語言裡的名稱(給 HUD)。 */
	protected static String name(net.minecraft.world.item.Item item) {
		return new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
	}

	/** 方塊在遊戲語言裡的名稱(給 HUD)。 */
	protected static String name(net.minecraft.world.level.block.Block block) {
		return block.getName().getString();
	}

	/** 開始執行(已通過事前檢查)。 */
	protected void start(Minecraft mc) {
	}

	protected abstract void tick(Minecraft mc);

	/** 被取消或被反射打斷時：停下 Baritone、關掉容器等。 */
	protected void onStop(Minecraft mc) {
	}

	/** 反射暫時接手時(自衛、進食)；結束後 onResume 接著做。 */
	protected void onSuspend(Minecraft mc) {
		onStop(mc);
	}

	/** 恢復時：如果任務還在走路，重新設定剛才的目標(反射會清掉 Baritone 的目標)。 */
	protected void onResume(Minecraft mc) {
		if (lastGoal != null && moves()) blockagent.game.Bari.b().getCustomGoalProcess().setGoalAndPath(lastGoal);
	}

	/** 用 Baritone 走到某處，並記住目標(恢復時會用到)。 */
	protected final void path(baritone.api.pathing.goals.Goal goal) {
		lastGoal = goal;
		blockagent.game.Bari.b().getCustomGoalProcess().setGoalAndPath(goal);
	}

	/** 有移動的任務才需要脫困反射(S9.1 unstuck)。 */
	public boolean moves() {
		return false;
	}

	/**
	 * 任務全程需要用腳走動、不能坐在載具上開始(預設跟 {@link #moves()} 相同；
	 * {@link ClearAreaTask} 在建構當下 {@code walking == false}，需獨立宣告，P3-M13)。
	 */
	public boolean requiresFoot() {
		return moves();
	}

	/** 被反射暫停後恢復時，將任務內部以 {@code agent.tick()} 記住的倒數時間戳一併平移(P3-M13)。 */
	protected void onShiftTimers(long pausedTicks) {
	}

	/**
	 * 任務執行期間要的 Baritone 設定(Bari.applySettings 每次重設後都會呼叫)，例如清空區域時不准挖和放。
	 * 預設不改。
	 */
	public void adjustBaritone(baritone.api.Settings s) {
	}

	public void onSelfBroke(BlockPos pos, BlockState before) {
	}

	/** 自己放下一格方塊(客戶端預測成功，不管是任務、Baritone 還是反射放的)。 */
	public void onSelfPlaced(BlockPos pos, BlockState state) {
	}

	public void onSystemMessage(String text) {
	}

	/** 完成；text 是給 AI 看的結果 KV，例如 {@code got=8 block=iron_ore}。 */
	protected final void done(String text) {
		if (status != Status.RUNNING) return;
		status = Status.DONE;
		resultText = text;
	}

	/** 失敗並附上比錯誤碼預設更具體的下一步。 */
	protected final void fail(ErrorCode code, String reason, String hint) {
		fail(code, reason);
		if (status == Status.FAILED && failCode == code && reason.equals(failReason)) failHint = hint;
	}

	/** 失敗；reason 是一句短英文(S3.0d)。 */
	protected final void fail(ErrorCode code, String reason) {
		if (status != Status.RUNNING) return;
		status = Status.FAILED;
		failCode = code;
		failReason = reason;
	}

	/**
	 * 打不開工作台、熔爐、箱子時：最近 3 秒內有動作被規矩擋下，就照實回報那個原因，
	 * 不要讓 AI 誤以為是方塊本身的問題(2026-11-22 試玩：被擋下卻回報 NO_STATION「打不開」)。
	 */
	protected final void failToOpen(String reason) {
		var v = agent.guard.blockedSince(agent.tick() - 60);
		if (v != null) fail(v.code(), reason + "; blocked by a rule: " + v.reason());
		else fail(ErrorCode.NO_STATION, reason);
	}

	/** 任務有進展時呼叫。 */
	protected final void markProgress() {
		lastProgressTick = agent.tick();
		progressCount++;
	}

	public final int progressCount() {
		return progressCount;
	}

	public final long ticksSinceProgress() {
		return agent.tick() - lastProgressTick;
	}

	protected final long elapsed() {
		return agent.tick() - startTick;
	}

	public boolean running() {
		return status == Status.RUNNING;
	}

	protected final void markOpenedContainer(boolean v) {
		openedContainer = v;
	}
}
