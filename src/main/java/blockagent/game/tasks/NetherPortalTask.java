package blockagent.game.tasks;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import blockagent.core.ItemMatcher;
import blockagent.core.PortalPlanner;
import blockagent.game.Agent;
import blockagent.game.Bari;
import blockagent.game.Fmt;
import blockagent.game.Inv;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地獄傳送門(docs/SPEC.md S4.2k)。
 * <ul>
 *   <li>build：先用內含的施工任務蓋黑曜石框(和藍圖同一套施工、鷹架與規矩)，蓋好後走近用打火石點底框，等門亮起來。</li>
 *   <li>enter：走進傳送門那一格，放開按鍵站著，等換到另一個維度。</li>
 * </ul>
 * 施工階段規矩和 Baritone mixin 透過 {@link TaskRunner#build()} 看到內含的施工任務。
 */
public final class NetherPortalTask extends Task {
	private static final int LIGHT_WAIT_TICKS = 40;
	private static final int LIGHT_TRIES = 3;
	/** 生存模式要在門裡站 4 秒(80 tick)；伺服器慢或剛好卡到怪也多等一點。 */
	private static final int STAND_TICKS = 20 * 15;
	private static final int IDLE_TICKS = 40;

	/** 下框架時暫時放寬的墜落高度(框高 5，站在框頂要能跳下來)。 */
	private static final int DESCEND_FALL = PortalPlanner.HEIGHT + 1;

	private enum Phase {BUILD, LIGHT, DESCEND, WALK_IN, STAND}

	/** 點火的位置：點 {@code block} 朝門裡的那一面，火出現在門裡(原版門框裡任何一格起火都會亮)。 */
	public record Ignite(BlockPos block, Direction face) {}

	private final boolean entering;
	private final Task inner;
	private final BlockPos base;
	private final BlockPos inside;
	private final String axis;
	/** 框架和墊角的格子：點火、下框架期間尋路和脫困不能拆。 */
	private final java.util.Set<BlockPos> keep;
	private final java.util.List<Ignite> ignites;
	private Integer savedFall;
	// Baritone 尋路執行緒會透過 keeps()、inner() 讀(DEV_RULES §3)
	private volatile Phase phase;
	private int innerProgress;
	private int tries;
	private long litTick = -1;
	private long standSince = -1;
	private int idleTicks;
	private ResourceKey<Level> fromDim;
	private BlockPos fromPos;

	private NetherPortalTask(Agent agent, boolean entering, Task inner, BlockPos base, BlockPos inside, String axis,
							 java.util.Set<BlockPos> keep, java.util.List<Ignite> ignites) {
		super(agent, "nether_portal");
		this.entering = entering;
		this.inner = inner;
		this.base = base;
		this.inside = inside;
		this.axis = axis;
		this.keep = java.util.Set.copyOf(keep);
		this.ignites = java.util.List.copyOf(ignites);
	}

	/**
	 * 蓋框並點火。{@code build} 是蓋框的施工任務；框已經蓋好時傳 null，直接點火。
	 * @param base    底框左邊那格(門裡最下面那格在它上面)
	 * @param keep    框架和墊角
	 * @param ignites 可以點火的框塊和朝門裡的面，依偏好排序
	 */
	public static NetherPortalTask build(Agent agent, Task build, BlockPos base, String axis, java.util.Set<BlockPos> keep,
										 java.util.List<Ignite> ignites) {
		return new NetherPortalTask(agent, false, build, base, base.above(), axis, keep, ignites);
	}

	/** 走進 {@code portal} 這一格傳送門。 */
	public static NetherPortalTask enter(Agent agent, BlockPos portal) {
		return new NetherPortalTask(agent, true, null, null, portal, null, java.util.Set.of(), java.util.List.of());
	}

	/** 點火、下框架期間，這格是框架或墊角(規矩不准尋路和脫困拆掉，S4.2k)。 */
	public boolean keeps(int x, int y, int z) {
		return !entering && phase != Phase.BUILD && keep.contains(new BlockPos(x, y, z));
	}

	/** 施工階段內含的施工任務；其他階段回 null。 */
	Task inner() {
		return phase == Phase.BUILD ? inner : null;
	}

	@Override
	public boolean crossesDimensions() {
		return entering;
	}

	@Override
	public boolean moves() {
		return true;
	}

	@Override
	public String describe() {
		if (entering) return "走進地獄傳送門";
		return phase == Phase.BUILD && inner != null ? "蓋地獄傳送門：" + inner.describe() : "點燃地獄傳送門";
	}

	@Override
	public Progress hudProgress() {
		return phase == Phase.BUILD && inner != null ? inner.hudProgress() : null;
	}

	@Override
	public String progress() {
		String state = switch (phase == null ? (entering ? Phase.WALK_IN : Phase.BUILD) : phase) {
			case BUILD -> "building";
			case LIGHT -> "lighting";
			case DESCEND -> "climbing_down";
			case WALK_IN -> "walking";
			case STAND -> "in_portal";
		};
		StringBuilder sb = new StringBuilder("state=").append(state);
		if (phase == Phase.BUILD && inner != null) sb.append(' ').append(inner.progress());
		if (!entering) sb.append(" portal=").append(Fmt.pos(inside)).append(" axis=").append(axis);
		else sb.append(" portal=").append(Fmt.pos(inside));
		return sb.toString();
	}

	@Override
	protected void start(Minecraft mc) {
		fromDim = mc.level.dimension();
		fromPos = mc.player.blockPosition();
		if (entering) {
			phase = Phase.WALK_IN;
			// 進門任務才准尋路穿過傳送門方塊(Bari.applySettings 依 crossesDimensions 決定)
			Bari.applySettings();
			path(new GoalBlock(inside));
			return;
		}
		if (inner == null) {
			phase = Phase.LIGHT;
			return;
		}
		phase = Phase.BUILD;
		inner.startTick = agent.tick();
		inner.lastProgressTick = inner.startTick;
		inner.startDim = fromDim;
		inner.start(mc);
		afterInner(mc);
	}

	@Override
	protected void tick(Minecraft mc) {
		switch (phase) {
			case BUILD -> {
				inner.tick(mc);
				afterInner(mc);
			}
			case LIGHT -> light(mc);
			case DESCEND -> descend(mc);
			case WALK_IN, STAND -> walkIn(mc);
			default -> {
			}
		}
	}

	/** 內含施工任務的進展算到自己頭上；它結束時接著點火，失敗就照它的原因結束。 */
	private void afterInner(Minecraft mc) {
		if (inner.progressCount() != innerProgress) {
			innerProgress = inner.progressCount();
			markProgress();
		}
		if (inner.running()) return;
		if (inner.status != Status.DONE) {
			fail(inner.failCode == null ? ErrorCode.INTERRUPTED : inner.failCode,
				"frame not finished: " + (inner.failReason == null ? "stopped" : inner.failReason), inner.failHint);
			return;
		}
		try {
			inner.onStop(mc);
		} catch (RuntimeException ignored) {
			// 收尾失敗不影響接著點火
		}
		phase = Phase.LIGHT;
		markProgress();
		// 施工的設定(不准挖放、鷹架)換回一般設定
		Bari.applySettings();
	}

	private void light(Minecraft mc) {
		BlockState here = mc.level.getBlockState(inside);
		if (here.is(Blocks.NETHER_PORTAL)) {
			Bari.stopAll();
			if (mc.player.getY() > base.getY() + 1.5) {
				// 蓋上框時站到了框頂：放寬墜落高度走下來，免得困在 5 格高的框上
				phase = Phase.DESCEND;
				idleTicks = 0;
				markProgress();
				setFall(true);
				path(new GoalNear(base, 3));
				return;
			}
			finishLit("");
			return;
		}
		if (litTick >= 0) {
			if (agent.tick() - litTick < LIGHT_WAIT_TICKS) return;
			if (++tries >= LIGHT_TRIES) {
				fail(ErrorCode.INVALID_ARGUMENT, "the frame did not light up (blocked interior or incomplete frame)",
					"inspect_area around " + Fmt.pos(base) + "; the 2x3 inside must be empty and all 10 obsidian in place");
				return;
			}
			litTick = -1;
		}
		var p = mc.player;
		Ignite at = null;
		for (Ignite i : ignites) {
			if (Moves.canReach(p, i.block())) {
				at = i;
				break;
			}
		}
		if (at == null) {
			var b = Bari.b();
			if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing()) {
				if (++idleTicks > IDLE_TICKS) {
					fail(ErrorCode.PATH_BLOCKED, "cannot get within reach of the portal frame at " + Fmt.pos(base));
					return;
				}
				path(new GoalNear(base, 2));
			} else {
				idleTicks = 0;
			}
			return;
		}
		Bari.stopAll();
		Item igniter = Inv.countWithOffhand(p, Items.FLINT_AND_STEEL, ItemMatcher.ANY) > 0 ? Items.FLINT_AND_STEEL : Items.FIRE_CHARGE;
		if (Inv.countWithOffhand(p, igniter, ItemMatcher.ANY) == 0) {
			fail(ErrorCode.MISSING_TOOL, "no flint_and_steel or fire_charge to light the portal", "craft flint_and_steel (iron_ingot + flint)");
			return;
		}
		if (p.containerMenu != p.inventoryMenu) p.closeContainer();
		if (!Inv.selectBestInHand(mc, igniter, ItemMatcher.ANY)) {
			fail(ErrorCode.INVALID_ARGUMENT, "could not take " + Inv.id(igniter) + " into the hand");
			return;
		}
		long mark = agent.guard.blockMark();
		var r = Moves.useOnWithShieldBypass(mc, at.block(), at.face());
		var blocked = agent.guard.blockedAfter(mark);
		if (blocked != null) {
			fail(blocked.code(), blocked.reason(), blocked.hint());
			return;
		}
		if (!r.consumesAction()) {
			fail(ErrorCode.INVALID_ARGUMENT, "using " + Inv.id(igniter) + " on the frame had no effect");
			return;
		}
		litTick = agent.tick();
		markProgress();
	}

	private void descend(Minecraft mc) {
		var p = mc.player;
		if (p.onGround() && p.getY() <= base.getY() + 1.5) {
			setFall(false);
			finishLit("");
			return;
		}
		var b = Bari.b();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing()) {
			if (++idleTicks > IDLE_TICKS) {
				setFall(false);
				finishLit(" note=\"still on top of the frame; goto the ground nearby\"");
				return;
			}
			path(new GoalNear(base, 3));
		} else {
			idleTicks = 0;
		}
	}

	private void finishLit(String note) {
		done("portal=" + Fmt.pos(inside) + " axis=" + axis + " lit=true" + note);
	}

	/** 下框架時暫時放寬 Baritone 不帶水的墜落高度，結束時還原。 */
	private void setFall(boolean on) {
		var s = baritone.api.BaritoneAPI.getSettings();
		if (on && savedFall == null) {
			savedFall = s.maxFallHeightNoWater.value;
			s.maxFallHeightNoWater.value = Math.max(savedFall, DESCEND_FALL);
		} else if (!on && savedFall != null) {
			s.maxFallHeightNoWater.value = savedFall;
			savedFall = null;
		}
	}

	private void walkIn(Minecraft mc) {
		if (!mc.level.dimension().equals(fromDim)) {
			Bari.stopAll();
			BlockPos now = mc.player.blockPosition();
			// 抵達點在原本維度的對應座標(地獄乘 8、主世界除以 8)，方便記下兩邊的門
			int[] linked = PortalPlanner.linkedXZ(now.getX(), now.getZ(), !mc.level.dimension().equals(Level.NETHER));
			done("from=" + fromDim.identifier().getPath() + " dim=" + mc.level.dimension().identifier().getPath()
				+ " arrived=" + Fmt.pos(now) + " left=" + Fmt.pos(fromPos) + " linked_xz=" + linked[0] + "," + linked[1]
				+ " hint=\"memory_write both portals with their dim; step out of the portal first\"");
			return;
		}
		if (mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.NETHER_PORTAL)) {
			if (phase != Phase.STAND) {
				phase = Phase.STAND;
				standSince = agent.tick();
				Bari.stopAll();
				markProgress();
			}
			if (agent.tick() - standSince > STAND_TICKS) {
				fail(ErrorCode.TIMEOUT, "stood in the portal for " + STAND_TICKS / 20 + " s without changing dimension",
					"the server may disable the Nether; check portal_cooldown in get_self");
			}
			return;
		}
		if (phase == Phase.STAND) {
			// 被推出門外(怪物、朋友)：重新走回去
			phase = Phase.WALK_IN;
			path(new GoalBlock(inside));
			return;
		}
		if (!mc.level.getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
			fail(ErrorCode.NOT_FOUND, "the portal at " + Fmt.pos(inside) + " is gone");
			return;
		}
		var b = Bari.b();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing()) {
			if (++idleTicks > IDLE_TICKS) fail(ErrorCode.PATH_BLOCKED, "no path into the portal at " + Fmt.pos(inside));
		} else {
			idleTicks = 0;
		}
	}

	@Override
	protected void onStop(Minecraft mc) {
		setFall(false);
		if (phase == Phase.BUILD && inner != null) inner.onStop(mc);
		else Bari.stopAll();
	}

	@Override
	protected void onSuspend(Minecraft mc) {
		if (phase == Phase.BUILD && inner != null) {
			inner.suspended = true;
			inner.suspendedAt = agent.tick();
			inner.onSuspend(mc);
		} else {
			setFall(false);
			Bari.stopAll();
		}
	}

	@Override
	protected void onResume(Minecraft mc) {
		if (phase == Phase.BUILD && inner != null) {
			inner.suspended = false;
			inner.onResume(mc);
			return;
		}
		if (phase == Phase.STAND) phase = Phase.WALK_IN;
		if (phase == Phase.DESCEND) setFall(true);
		super.onResume(mc);
	}

	@Override
	protected void onShiftTimers(long pausedTicks) {
		if (litTick >= 0) litTick += pausedTicks;
		if (standSince >= 0) standSince += pausedTicks;
		if (inner != null) {
			inner.startTick += pausedTicks;
			inner.lastProgressTick += pausedTicks;
			inner.onShiftTimers(pausedTicks);
		}
	}

	@Override
	public void adjustBaritone(baritone.api.Settings s) {
		if (phase == Phase.BUILD && inner != null) inner.adjustBaritone(s);
	}

	@Override
	public void onSelfBroke(BlockPos pos, BlockState before) {
		if (phase == Phase.BUILD && inner != null) inner.onSelfBroke(pos, before);
	}

	@Override
	public void onSelfPlaced(BlockPos pos, BlockState state) {
		if (phase == Phase.BUILD && inner != null) inner.onSelfPlaced(pos, state);
	}
}
