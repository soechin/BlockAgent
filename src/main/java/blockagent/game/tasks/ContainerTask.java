package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalGetToBlock;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Fmt;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 容器工作站任務的共用基底(docs/SPEC.md S4.2e)：統一 {@code WALK → OPEN → SYNC → work() → CLOSE}
 * 與反射暫停/恢復({@link #onSuspend}/{@link #onResume})，確保開啟/恢復後必等伺服器同步內容再操作(DEV_RULES §2)。
 */
public abstract class ContainerTask extends Task {
	protected enum ContainerPhase {CUSTOM, WALK, OPEN, SYNC, WORK, CLOSE}

	protected BlockPos containerPos;
	protected ContainerPhase containerPhase = ContainerPhase.CUSTOM;
	protected int containerWaitTicks;
	protected long menuOpenedAt = -1;

	protected ContainerTask(Agent agent, String name) {
		super(agent, name);
	}

	/** 目前開啟的畫面是否為此任務預期的容器。 */
	protected abstract boolean isExpectedMenu(AbstractContainerMenu menu);

	/** 容器同步完成後每 tick 執行；呼叫前基底已保證 {@link #isExpectedMenu} 成立。 */
	protected abstract void work(Minecraft mc);

	/** {@link ContainerPhase#CUSTOM} 階段的專屬邏輯(如找/放熔爐或睡覺)。 */
	protected void tickCustom(Minecraft mc) {
	}

	/** SYNC 剛完成、進入 WORK 當下呼叫一次。 */
	protected void onMenuSynced(Minecraft mc) {
	}

	/** 除 {@code stateId != 0} 與 3 tick 下限外，子類可額外要求內容已就緒(例如書台書本非空)。 */
	protected boolean isMenuDataReady(AbstractContainerMenu menu) {
		return true;
	}

	/** 走到容器後對它按右鍵開啟介面。 */
	protected void openContainerBlock(Minecraft mc) {
		Moves.useOn(mc, containerPos, Moves.faceToward(mc.player, containerPos));
	}

	/** OPEN 逾時無法開啟時的錯誤說明。 */
	protected String openTimeoutReason() {
		return "could not open " + Fmt.pos(containerPos);
	}

	/** WALK 逾時到不了容器時的處理；預設直接失敗，子類(如 SmeltTask)可覆寫換下一個候選。 */
	protected void onWalkFailed(Minecraft mc) {
		fail(ErrorCode.PATH_BLOCKED, "cannot reach " + Fmt.pos(containerPos));
	}

	/** SYNC 或 WORK 途中容器畫面意外被關掉時的處理。 */
	protected void onMenuClosedUnexpectedly(Minecraft mc) {
		fail(ErrorCode.INTERRUPTED, "container screen was closed at " + Fmt.pos(containerPos));
	}

	/** CLOSE 階段關完容器後的收尾(通常呼叫 {@link #done} 或 {@link #fail})。 */
	protected abstract void onClosed(Minecraft mc);

	/** 開始走向或直接開啟 {@code pos} 的容器。 */
	protected final void beginContainer(Minecraft mc, BlockPos pos) {
		this.containerPos = pos.immutable();
		this.containerWaitTicks = 0;
		this.menuOpenedAt = -1;
		if (Moves.canReach(mc.player, containerPos)) {
			containerPhase = ContainerPhase.OPEN;
		} else {
			containerPhase = ContainerPhase.WALK;
			path(new GoalGetToBlock(containerPos));
		}
	}

	/** 準備關閉容器並收尾。 */
	protected final void finishContainer() {
		containerPhase = ContainerPhase.CLOSE;
		containerWaitTicks = 0;
	}

	/** 關閉容器並回到 {@link ContainerPhase#CUSTOM}(例如換下一座熔爐)。 */
	protected final void closeAndCustom(Minecraft mc) {
		MenuOps.close(mc);
		markOpenedContainer(false);
		menuOpenedAt = -1;
		containerWaitTicks = 0;
		containerPhase = ContainerPhase.CUSTOM;
	}

	@Override
	public boolean moves() {
		return containerPhase == ContainerPhase.WALK;
	}

	@Override
	protected final void tick(Minecraft mc) {
		switch (containerPhase) {
			case CUSTOM -> tickCustom(mc);
			case WALK -> {
				if (Moves.canReach(mc.player, containerPos)) {
					Bari.stopAll();
					containerPhase = ContainerPhase.OPEN;
					containerWaitTicks = 0;
				} else if (!Bari.busy() && containerWaitTicks++ > 40) {
					onWalkFailed(mc);
				}
			}
			case OPEN -> {
				if (isExpectedMenu(mc.player.containerMenu)) {
					markOpenedContainer(true);
					menuOpenedAt = agent.tick();
					containerPhase = ContainerPhase.SYNC;
					containerWaitTicks = 0;
				} else if (containerWaitTicks++ % 20 == 0) {
					if (containerWaitTicks > 60) {
						failToOpen(openTimeoutReason());
						return;
					}
					openContainerBlock(mc);
				}
			}
			case SYNC -> {
				AbstractContainerMenu m = mc.player.containerMenu;
				if (!isExpectedMenu(m)) {
					onMenuClosedUnexpectedly(mc);
					return;
				}
				long since = agent.tick() - menuOpenedAt;
				if (since < 3 || ((m.getStateId() == 0 || !isMenuDataReady(m)) && since < 40)) return;
				containerPhase = ContainerPhase.WORK;
				containerWaitTicks = 0;
				onMenuSynced(mc);
			}
			case WORK -> {
				if (!isExpectedMenu(mc.player.containerMenu)) {
					onMenuClosedUnexpectedly(mc);
					return;
				}
				work(mc);
			}
			case CLOSE -> {
				MenuOps.close(mc);
				markOpenedContainer(false);
				onClosed(mc);
			}
		}
	}

	@Override
	protected void onStop(Minecraft mc) {
		Bari.stopAll();
		if (openedContainer) MenuOps.close(mc);
	}

	@Override
	protected void onShiftTimers(long pausedTicks) {
		if (menuOpenedAt >= 0) menuOpenedAt += pausedTicks;
	}

	@Override
	protected void onResume(Minecraft mc) {
		markOpenedContainer(false);
		menuOpenedAt = -1;
		containerWaitTicks = 0;
		switch (containerPhase) {
			case OPEN, SYNC, WORK -> {
				if (containerPos != null) beginContainer(mc, containerPos);
			}
			case WALK -> super.onResume(mc);
			default -> {
			}
		}
	}
}
