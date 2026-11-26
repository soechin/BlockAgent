package blockagent.game;

import blockagent.core.Event;
import blockagent.game.tasks.HuntTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 把自己放的方塊記進所有權帳本(docs/SPEC.md S7.4)。由 Mixin 和事件呼叫。
 * 掉落物撿到就是自己的(S7.6)，這裡只把物品事件轉給狩獵任務。
 */
public final class OwnershipTracker {
	private record CommandChange(ClientLevel level, Predicate<BlockState> matches, long deadline, Consumer<BlockPos> confirmed) {
	}

	private record Placement(ClientLevel level, CompletableFuture<BlockState> result) {
	}

	/** 帳本的鍵：維度加位置(C2)。 */
	private record Key(String dim, long pos) {
	}

	private final Agent agent;
	private final Map<Key, Long> selfCaused = new HashMap<>();
	private final Map<BlockPos, Placement> placements = new HashMap<>();
	private final Map<BlockPos, CommandChange> commandChanges = new HashMap<>();
	private boolean wasDead;
	/** 死掉的那一 tick；等死亡訊息最多 10 tick 再送 died 事件。 */
	private long deathTick = -1;
	private BlockPos deathPos;
	private volatile String deathMessage;

	OwnershipTracker(Agent agent) {
		this.agent = agent;
	}

	private long now() {
		return agent.tick();
	}

	// ---- 方塊 ----

	/** useItemOn 成功放置方塊之後(不管是誰按的，都是同一個玩家)。門、床、高植物的另一格一起記(H3)。 */
	public void onPlaceAttempt(BlockPos pos, BlockState expected) {
		String dim = RuleGuard.currentDim();
		expect(dim, pos, expected);
		BlockPos second = RuleGuard.companionCell(expected, pos);
		if (second != null) expect(dim, second, expected);
		agent.tasks.onSelfPlaced(pos.immutable(), expected);
	}

	private void expect(String dim, BlockPos pos, BlockState expected) {
		agent.ledger.expectPlacement(dim, pos.asLong(), RuleGuard.blockId(expected), now());
		selfCaused.put(new Key(dim, pos.asLong()), now());
	}

	public boolean placementPending(BlockPos pos) {
		Placement pending = placements.get(pos);
		return pending != null && !pending.result().isDone();
	}

	/** 一般放置同樣等伺服器回報目標格，再將實際結果送回工具呼叫。 */
	public CompletableFuture<BlockState> confirmPlacement(ClientLevel level, BlockPos pos) {
		var result = new CompletableFuture<BlockState>();
		placements.put(pos, new Placement(level, result));
		return result.completeOnTimeout(null, BuildCommands.CONFIRM_TICKS * 50L, TimeUnit.MILLISECONDS);
	}

	/** 記住自己對既有方塊的轉換，只接受伺服器回傳的預期種類。 */
	public void expectOwnedChange(BlockPos pos, BlockState state) {
		String dim = RuleGuard.currentDim();
		if (agent.ledger.isOwned(dim, pos.asLong())) agent.ledger.expectPlacement(dim, pos.asLong(), RuleGuard.blockId(state), now());
	}

	/** 已送出指令的格子也等伺服器更新才變更所有權。 */
	public void expectCommandChange(ClientLevel level, BlockPos pos, Block material, long deadline, Consumer<BlockPos> confirmed) {
		expectCommandChange(level, pos, state -> state.is(material), deadline, confirmed);
	}

	public void expectCommandChange(ClientLevel level, BlockPos pos, Predicate<BlockState> matches, long deadline, Consumer<BlockPos> confirmed) {
		commandChanges.put(pos, new CommandChange(level, matches, deadline, confirmed));
	}

	/** 收到伺服器確認的方塊狀態。 */
	public void onServerBlockState(ClientLevel level, BlockPos pos, BlockState state) {
		if (level != Minecraft.getInstance().level) return;
		String dim = RuleGuard.dim(level);
		CommandChange command = commandChanges.get(pos);
		if (command != null && command.level() == level && now() <= command.deadline() && command.matches().test(state)) {
			commandChanges.remove(pos);
			if (state.isAir()) agent.ledger.onSelfBroke(dim, pos.asLong());
			else markOwned(pos, state);
			command.confirmed().accept(pos);
			return;
		}
		long p = pos.asLong();
		Long t = selfCaused.get(new Key(dim, p));
		boolean self = t != null && now() - t <= 40;
		String blockId = RuleGuard.blockId(state);
		boolean stemGrew = (state.getBlock() instanceof net.minecraft.world.level.block.StemBlock
			|| state.getBlock() instanceof net.minecraft.world.level.block.AttachedStemBlock)
			&& blockagent.game.tasks.FarmTask.sameStem(agent.ledger.ownedBlockId(dim, p), blockId);
		agent.ledger.onBlockUpdate(dim, p, blockId, state.isAir(), now(), self || stemGrew);
		if (!self && !stemGrew && !state.isAir()) agent.observation.onOtherPlaced(pos);
		Placement placement = placements.get(pos);
		if (placement != null && placement.level() == level) {
			placements.remove(pos);
			placement.result().complete(state);
		}
	}

	/** 自己挖掉一格(客戶端預測成功)。 */
	public void onSelfBroke(BlockPos pos, BlockState before) {
		String dim = RuleGuard.currentDim();
		long p = pos.asLong();
		selfCaused.put(new Key(dim, p), now());
		agent.ledger.onSelfBroke(dim, p);
		agent.tasks.onSelfBroke(pos, before);
	}

	/** 自己標記為擁有(例如自己把天然泥土鋤成耕地)。 */
	public void markOwned(BlockPos pos, BlockState state) {
		String dim = RuleGuard.currentDim();
		long p = pos.asLong();
		agent.ledger.expectPlacement(dim, p, RuleGuard.blockId(state), now());
		agent.ledger.onBlockUpdate(dim, p, RuleGuard.blockId(state), state.isAir(), now(), true);
	}

	// ---- 掉落物(S7.6：撿到就是自己的) ----

	public void onEntityLoad(Entity e) {
		if (e instanceof ItemEntity item && agent.tasks.current() instanceof HuntTask hunt) hunt.onItemSpawn(item);
	}

	/** 伺服器告訴我們有人撿起了物品(ClientboundTakeItemEntityPacket，實體還沒被移除)。 */
	public void onTakeItem(int itemEntityId, int playerId, int amount) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || playerId != mc.player.getId()) return;
		if (!(mc.level.getEntity(itemEntityId) instanceof ItemEntity ie) || ie.getItem().isEmpty()) return; // 經驗球等
		if (agent.tasks.current() instanceof HuntTask hunt) hunt.onItemPickedUp(ie, Math.max(1, amount));
	}

	/** ClientboundPlayerCombatKillPacket：自己的死亡訊息，例如「Steve was shot by Skeleton」。 */
	public void onDeathMessage(String message) {
		deathMessage = message;
	}

	// ---- tick ----

	/** 換世界時清掉上個世界留下的暫存狀態，免得把舊世界的 died 事件帶過來(M5)。 */
	void reset() {
		selfCaused.clear();
		placements.values().forEach(p -> p.result().complete(null));
		placements.clear();
		commandChanges.clear();
		wasDead = false;
		deathTick = -1;
		deathPos = null;
		deathMessage = null;
	}

	void tick(Minecraft mc) {
		long t = now();
		agent.ledger.expireTick(t);
		selfCaused.values().removeIf(v -> t - v > 40);
		placements.values().removeIf(p -> p.level() != mc.level || p.result().isDone());
		commandChanges.values().removeIf(p -> p.level() != mc.level || t > p.deadline());

		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead) {
			deathTick = t;
			deathPos = mc.player.blockPosition();
		}
		wasDead = dead;
		if (deathTick >= 0 && (deathMessage != null || t - deathTick >= 10)) {
			var kv = blockagent.mcp.Kv.of().put("pos", Fmt.pos(deathPos));
			if (deathMessage != null && !deathMessage.isBlank()) kv.quoted("cause", deathMessage);
			agent.events.add("died", Event.Level.URGENT, kv + " | items were dropped there");
			deathTick = -1;
			deathMessage = null;
		}
	}
}
