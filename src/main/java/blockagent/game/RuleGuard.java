package blockagent.game;

import blockagent.core.Event;
import blockagent.core.OwnershipLedger;
import blockagent.game.tasks.BuildTask;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SignApplicator;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 守門員：判斷規矩的唯一地方(docs/SPEC.md S7)。只看遊戲狀態和所有權帳本，不看聊天內容或 AI 給的理由。
 */
public final class RuleGuard {
	/** @param hint 比錯誤碼預設更具體的下一步(S3.0d)；null 用預設。 */
	public record Verdict(boolean allowed, ErrorCode code, String reason, String hint) {
		public static final Verdict ALLOW = new Verdict(true, null, null);

		public Verdict(boolean allowed, ErrorCode code, String reason) {
			this(allowed, code, reason, null);
		}

		static Verdict deny(ErrorCode code, String reason) {
			return new Verdict(false, code, reason);
		}

		static Verdict deny(ErrorCode code, String reason, String hint) {
			return new Verdict(false, code, reason, hint);
		}
	}

	private final Agent agent;

	RuleGuard(Agent agent) {
		this.agent = agent;
	}

	/** 只有 AI 在控制時(RUNNING)才攔截；暫停時是使用者自己在玩(S7.2)。 */
	public boolean active() {
		return agent.session.isRunning();
	}

	// ---- 方塊(S7.3) ----

	public static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	public static Set<String> blockTags(BlockState state) {
		return state.tags().map(t -> t.location().toString()).collect(Collectors.toSet());
	}

	/** 維度 id(例如 minecraft:overworld)，所有權帳本的鍵之一(docs/REVIEW.md C2)。 */
	public static String dim(Level level) {
		return level == null ? OwnershipLedger.OVERWORLD : level.dimension().identifier().toString();
	}

	/** 目前所在的維度。Baritone 的尋路執行緒也會呼叫，只讀欄位。 */
	public static String currentDim() {
		return dim(Minecraft.getInstance().level);
	}

	/** (維度 →) 方塊 → 是否天然。Baritone 尋路每格都會問，所以快取起來；tag 重新載入或換世界時清掉(M15)。 */
	private final Map<String, Map<Block, Boolean>> naturalCache = new ConcurrentHashMap<>();

	public void clearCache() {
		naturalCache.clear();
		TaggedBlocks.clear();
	}

	/** 目前所在維度裡，這種方塊算不算天然(地獄疙瘩只在地獄算，S7.3a)。 */
	public boolean isNatural(BlockState state) {
		String dim = currentDim();
		return naturalCache.computeIfAbsent(dim, d -> new ConcurrentHashMap<>())
			.computeIfAbsent(state.getBlock(), b -> agent.classifier.isNatural(blockId(state), blockTags(state), dim));
	}

	/**
	 * 傳送門的黑曜石框(S7.3a)：六面任一面貼著 nether_portal 的黑曜石。不是自己放的不能拆，
	 * 尋路和脫困連自己的也不拆，免得回程的門被拆掉。Baritone 尋路執行緒會呼叫，只讀世界。
	 */
	public static boolean isPortalFrame(Level level, int x, int y, int z, BlockState state) {
		if (level == null || !state.is(Blocks.OBSIDIAN)) return false;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			n.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
			if (level.getBlockState(n).is(Blocks.NETHER_PORTAL)) return true;
		}
		return false;
	}

	/** 目前維度的這格是不是自己的。 */
	public boolean isOwned(BlockPos pos) {
		return agent.ledger.isOwned(currentDim(), pos.asLong());
	}

	public boolean isOwned(Level level, BlockPos pos) {
		return agent.ledger.isOwned(dim(level), pos.asLong());
	}

	/** 這格可以破壞嗎？Baritone 的路徑規劃執行緒也會呼叫(目前所在的維度)，所以只讀不改。 */
	public Verdict canBreak(int x, int y, int z, BlockState state) {
		return canBreak(currentDim(), x, y, z, state);
	}

	public Verdict canBreak(Level level, BlockPos pos) {
		return canBreak(dim(level), pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos));
	}

	private Verdict canBreak(String dim, int x, int y, int z, BlockState state) {
		if (state.isAir()) return Verdict.ALLOW;
		if (agent.tasks.build() instanceof BuildTask build && build.protects(x, y, z)) {
			return Verdict.deny(ErrorCode.RULE_PROTECTED_BLOCK, "kept by the current build (doorway or existing block)");
		}
		if (agent.tasks.current() instanceof blockagent.game.tasks.NetherPortalTask portal && portal.keeps(x, y, z)) {
			return Verdict.deny(ErrorCode.RULE_PROTECTED_BLOCK, "kept by the portal being lit");
		}
		if (agent.ledger.isOwned(dim, OwnershipLedger.pack(x, y, z))) return Verdict.ALLOW;
		if (isPortalFrame(Minecraft.getInstance().level, x, y, z, state)) {
			return Verdict.deny(ErrorCode.RULE_PROTECTED_BLOCK, "obsidian at " + x + "," + y + "," + z
				+ " is part of a nether portal frame; cannot break", "keep portal frames intact so the way back stays open");
		}
		if (isNatural(state)) return Verdict.ALLOW;
		return Verdict.deny(ErrorCode.RULE_PROTECTED_BLOCK,
			shortId(state) + " at " + x + "," + y + "," + z + " was placed by someone else (owner=other); cannot break");
	}

	/**
	 * Baritone 尋路時這格能不能順手拆掉(R5)：canBreak 必須放行，而且非天然的自有建築方塊
	 * (木板、圓石、門、床、箱子、熔爐等)一律不准尋路拆毀，除非是進行中 BuildTask 正要清除的格子。
	 * 主動拆除(break_block、demolish、clear_area)不走尋路，不受影響。Baritone 尋路執行緒會呼叫，只讀執行緒安全的資料。
	 */
	public boolean canPathBreak(int x, int y, int z, BlockState state) {
		if (state.isAir()) return true;
		if (!canBreak(x, y, z, state).allowed()) return false;
		if (isPortalFrame(Minecraft.getInstance().level, x, y, z, state)) return false;
		var t = agent.tasks.current();
		if (agent.tasks.build() instanceof BuildTask build && build.isClearing(x, y, z)) return true;
		if (t instanceof blockagent.game.tasks.MineTask mine && mine.isMineTarget(state)) return true;
		return isNatural(state);
	}

	/** S7.3b：放置的目標格是空氣或可替換方塊才行；會覆蓋其他方塊的，比照破壞判斷。 */
	public Verdict canPlaceInto(Level level, BlockPos pos) {
		BlockState cur = level.getBlockState(pos);
		if (cur.isAir() || cur.canBeReplaced()) {
			if (cur.isAir() || isNatural(cur) || isOwned(level, pos)) return Verdict.ALLOW;
		}
		// Baritone 墊腳連續點擊：上一格剛放的方塊還沒等到伺服器確認進帳本，不能當成別人的(R4)
		if (agent.ledger.isPendingSelf(dim(level), pos.asLong(), blockId(cur), agent.tick())) return Verdict.ALLOW;
		// 放置會覆蓋那格，規則比照破壞；但被別人的方塊擋住時，訊息要說「放不下去」而不是「不能拆」
		Verdict v = canBreak(level, pos);
		boolean othersBlock = !v.allowed() && !isOwned(level, pos) && !isNatural(cur)
			&& !(agent.tasks.build() instanceof BuildTask build && build.protects(pos.getX(), pos.getY(), pos.getZ()));
		if (!othersBlock) return v;
		return Verdict.deny(ErrorCode.RULE_PROTECTED_BLOCK, pos.getX() + "," + pos.getY() + "," + pos.getZ() + " is occupied by someone else's " + shortId(cur) + "; cannot place");
	}

	/** 點火、倒岩漿時，火附近幾格內不能有別人的可燃方塊(使用者決定，2026-11-23；docs/REVIEW.md H2)。 */
	public static final int FIRE_RADIUS = 2;

	/**
	 * 火(或岩漿)出現在 {@code at} 時，周圍 5×5×5 範圍內有沒有別人的可燃方塊(木頭、羊毛這類會被岩漿點燃的方塊)。
	 * 原版的火會往旁邊和上方蔓延，所以不只看那一格。
	 */
	public Verdict canStartFire(Level level, BlockPos at) {
		Verdict v = canPlaceInto(level, at);
		if (!v.allowed()) return v;
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-FIRE_RADIUS, -FIRE_RADIUS, -FIRE_RADIUS), at.offset(FIRE_RADIUS, FIRE_RADIUS, FIRE_RADIUS))) {
			BlockState st = level.getBlockState(p);
			if (st.isAir() || !st.ignitedByLava() || isNatural(st) || isOwned(level, p)) continue;
			return Verdict.deny(ErrorCode.RULE_PROTECTED_BLOCK, "someone else's flammable " + shortId(st) + " at " + Fmt.pos(p)
				+ " is within " + FIRE_RADIUS + " blocks of the fire", "start fires at least " + (FIRE_RADIUS + 1) + " blocks away from others' builds");
		}
		return Verdict.ALLOW;
	}

	/**
	 * 雙格方塊的另一格(docs/REVIEW.md H3)：門和高植物的上半格、床頭。放下時遊戲自己長出來，
	 * 所以放置時要一起檢查、一起記進帳本。不是雙格方塊回傳 null。
	 */
	public static BlockPos companionCell(BlockState placed, BlockPos pos) {
		if (placed.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			return placed.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
		}
		if (placed.hasProperty(BlockStateProperties.BED_PART) && placed.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			Direction toHead = placed.getValue(BlockStateProperties.HORIZONTAL_FACING);
			return placed.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? pos.relative(toHead) : pos.relative(toHead.getOpposite());
		}
		return null;
	}

	public Verdict canPlaceForBuild(Level level, BlockPos pos, Item item) {
		if (agent.tasks.build() instanceof BuildTask build && !build.permitsPlacement(level, pos, item)) {
			return Verdict.deny(ErrorCode.RULE_PROTECTED_BLOCK, "kept by the current build (doorway, empty cell, existing block or planned material)");
		}
		return Verdict.ALLOW;
	}

	/**
	 * 沒蹲下時對這格按右鍵「可能會」被方塊吃掉(打開介面、切換狀態、播音符、吃蛋糕…)。
	 * 給 {@code Facing.canPlaceAgainst} 與 {@code Moves.findPlaceSpot} 用：寧可多避開，不要貼著點下去開了介面或白費動作(P2-1)。
	 */
	public static boolean isInteractive(Level level, BlockState s, BlockPos pos) {
		Block b = s.getBlock();
		return alwaysConsumesUse(level, s, pos)
			|| s.is(net.minecraft.tags.BlockTags.DOORS) || s.is(net.minecraft.tags.BlockTags.TRAPDOORS)
			|| b instanceof net.minecraft.world.level.block.NoteBlock
			|| b instanceof net.minecraft.world.level.block.RepeaterBlock
			|| b instanceof net.minecraft.world.level.block.ComposterBlock
			|| b instanceof net.minecraft.world.level.block.RespawnAnchorBlock
			|| b instanceof net.minecraft.world.level.block.CakeBlock
			|| b instanceof net.minecraft.world.level.block.CandleCakeBlock;
	}

	/**
	 * 沒蹲下時對這格按右鍵「原版保證消耗動作(絕不會回傳 PASS 落到 stack.useOn)」的嚴格子集。
	 * 僅供 {@code MultiPlayerGameModeMixin} 判斷手上的方塊/工具會不會生效(安全層必須 Fail-closed)：
	 * 例如未滿的堆肥桶、零充能的重生錨、滿飽食度時的蛋糕/蠟燭蛋糕、鐵門/鐵活板門在原版會回傳 PASS
	 * 而落到打火石生火或放方塊，絕不可列入這裡。
	 */
	public static boolean alwaysConsumesUse(Level level, BlockState s, BlockPos pos) {
		Block b = s.getBlock();
		if (s.getMenuProvider(level, pos) != null) return true;
		if (b instanceof net.minecraft.world.level.block.DoorBlock d && d.type().canOpenByHand()) return true;
		if (s.is(net.minecraft.tags.BlockTags.TRAPDOORS) && !s.is(net.minecraft.world.level.block.Blocks.IRON_TRAPDOOR)) return true;
		return s.is(net.minecraft.tags.BlockTags.FENCE_GATES)
			|| s.is(net.minecraft.tags.BlockTags.BEDS)
			|| s.is(net.minecraft.tags.BlockTags.BUTTONS)
			|| s.is(net.minecraft.world.level.block.Blocks.LEVER)
			|| b instanceof net.minecraft.world.level.block.FlowerPotBlock
			|| b instanceof net.minecraft.world.level.block.DragonEggBlock;
	}

	public static String shortId(BlockState state) {
		String id = blockId(state);
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	// ---- 生物(S7.7) ----

	public static boolean isProtectedEntity(Entity e) {
		if (e instanceof Player) return true;
		if (e instanceof OwnableEntity o && o.getOwnerReference() != null) return true;
		if (e instanceof AbstractHorse h && h.isTamed()) return true;
		return e instanceof AbstractVillager;
	}

	public static String describe(Entity e) {
		String name = e.getName().getString();
		if (e instanceof Player) return "player " + name;
		if (e instanceof AbstractVillager) return "villager " + name;
		return "pet " + name;
	}

	public Verdict canAttack(Entity target) {
		if (isProtectedEntity(target)) {
			return Verdict.deny(ErrorCode.RULE_PROTECTED_ENTITY, "players, pets and villagers are protected (" + describe(target) + ")");
		}
		Entity blocker = protectedEntityOnLine(target);
		if (blocker != null) {
			return Verdict.deny(ErrorCode.RULE_PROTECTED_ENTITY, "protected " + describe(blocker) + " is in the line of attack; holding fire");
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS)) {
			Entity near = protectedNearTarget(mc.player, target);
			if (near != null) {
				return Verdict.deny(ErrorCode.RULE_PROTECTED_ENTITY, "protected " + describe(near) + " is next to the target; a sword sweep would hit it",
					"use an axe or another item");
			}
		}
		return Verdict.ALLOW;
	}

	/** 劍的橫掃範圍(原版 Player.doSweepAttack：目標碰撞箱往旁邊放大 1 格、上下 0.25 格)內的受保護生物(M14)。 */
	public static Entity protectedNearTarget(Player attacker, Entity target) {
		var area = target.getBoundingBox().inflate(1.0, 0.25, 1.0);
		for (Entity e : attacker.level().getEntities(target, area, e -> e != attacker && isProtectedEntity(e))) return e;
		return null;
	}

	/** 攻擊前的射線檢查(S7.7 最後一點)：從眼睛到目標的線段附近有受保護的生物就不攻擊。 */
	public Entity protectedEntityOnLine(Entity target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return null;
		Vec3 from = mc.player.getEyePosition();
		Vec3 to = target.getBoundingBox().getCenter();
		AABB area = new AABB(from, to).inflate(1.5);
		List<Entity> list = mc.level.getEntities(mc.player, area, e -> e != target && isProtectedEntity(e));
		for (Entity e : list) {
			AABB box = e.getBoundingBox().inflate(0.6);
			if (box.contains(from) || box.clip(from, to).isPresent()) return e;
		}
		return null;
	}

	/**
	 * 射箭、丟三叉戟前的拋物線檢查(S7.7)：照目前的視角和初速，一 tick 一 tick 模擬箭的路線
	 * (阻力 0.99、重力 0.05)，直到撞到方塊或第一隻生物。路線上先碰到玩家、寵物或村民就回傳牠
	 * (受保護生物的碰撞箱多放寬 0.6 格)；先碰到別的生物或方塊就回傳 null。
	 */
	public static Entity protectedOnProjectilePath(Player p, double speed) {
		Level level = p.level();
		Vec3 pos = p.getEyePosition().subtract(0, 0.1, 0);
		Vec3 v = p.getLookAngle().scale(speed);
		for (int t = 0; t < 100; t++) {
			Vec3 next = pos.add(v);
			var blockHit = level.clip(new net.minecraft.world.level.ClipContext(pos, next,
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
			boolean hitBlock = blockHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
			if (hitBlock) next = blockHit.getLocation();
			Entity first = null;
			double best = Double.MAX_VALUE;
			for (Entity e : level.getEntities(p, new AABB(pos, next).inflate(1.5),
				e -> e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive() && !e.isSpectator())) {
				var hit = e.getBoundingBox().inflate(isProtectedEntity(e) ? 0.6 : 0.3).clip(pos, next);
				if (hit.isPresent() && hit.get().distanceToSqr(pos) < best) {
					best = hit.get().distanceToSqr(pos);
					first = e;
				}
			}
			if (first != null) return isProtectedEntity(first) ? first : null;
			if (hitBlock) return null;
			pos = next;
			v = v.scale(0.99).subtract(0, 0.05, 0);
		}
		return null;
	}

	/**
	 * 改變受保護生物狀態的互動(拴繩、剪毛、上鞍、餵食…)都擋。
	 * 村民和流浪商人(AbstractVillager)一律不能互動，空手也一樣：使用者決定不做交易功能(2026-11-23)，
	 * 空手右鍵會打開交易畫面，而且畫面不會自己關，之後點格子的動作(craft…)可能點進交易畫面。
	 */
	public Verdict canInteract(Entity target) {
		if (!isProtectedEntity(target)) return Verdict.ALLOW;
		if (target instanceof AbstractVillager) {
			return Verdict.deny(ErrorCode.RULE_PROTECTED_ENTITY, "villagers and wandering traders cannot be interacted with ("
				+ describe(target) + ")", "trading is not supported; leave villagers and wandering traders alone");
		}
		return Verdict.deny(ErrorCode.RULE_PROTECTED_ENTITY, "cannot change the state of protected " + describe(target));
	}

	/**
	 * 手上的工具對這格按右鍵，原版會不會改變方塊(S7.3c)：斧頭、鏟子、鋤頭(BLOCK_TRANSFORMER)，
	 * 打火石點燃營火、蠟燭、TNT，骨粉催熟，蜂巢上蠟，染料/墨囊/蜂巢塗告示牌，剪刀和玻璃瓶採蜂箱，
	 * 桶子和瓶子裝倒煉藥鍋。天然方塊和自己的方塊由呼叫端放行。
	 */
	public static boolean toolWouldChange(ItemStack tool, BlockState st) {
		Block block = st.getBlock();
		// 斧頭、鏟子、鋤頭(26.3 改成 BLOCK_TRANSFORMER 元件)：削皮、去氧化、去蠟、鏟草徑、熄營火、鋤地。
		// 不逐一判斷這格會不會被轉換，一律當成會改(別人的草徑也會被鋤成耕地，H2)；轉換不了的格子原版本來就沒反應。
		if (tool.has(DataComponents.BLOCK_TRANSFORMER)) return true;
		// 點燃營火、蠟燭、TNT，或用骨粉催熟，也是改變那格方塊(use_item)
		if (tool.is(Items.FLINT_AND_STEEL) || tool.is(Items.FIRE_CHARGE)) {
			return CampfireBlock.canLight(st) || net.minecraft.world.level.block.CandleBlock.canLight(st)
				|| net.minecraft.world.level.block.CandleCakeBlock.canLight(st) || st.is(Blocks.TNT);
		}
		if (tool.is(Items.BONE_MEAL)) return block instanceof net.minecraft.world.level.block.BonemealableBlock;
		// 告示牌：染料、墨囊、螢光墨囊改顏色，蜂巢上蠟鎖定
		if (tool.getItem() instanceof SignApplicator && block instanceof SignBlock) return true;
		if (tool.is(Items.HONEYCOMB)) return HoneycombItem.WAXABLES.get().containsKey(block);
		// 蜂箱：剪刀採蜂巢、玻璃瓶裝蜂蜜(滿了才會動)
		if ((tool.is(Items.SHEARS) || tool.is(Items.GLASS_BOTTLE)) && block instanceof BeehiveBlock) {
			return st.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS;
		}
		if (tool.is(Items.SHEARS)) return st.is(Blocks.PUMPKIN);
		// 煉藥鍋：裝水、倒水、裝岩漿、裝細雪、瓶子裝取
		if (block instanceof AbstractCauldronBlock) {
			return tool.getItem() instanceof BucketItem || tool.getItem() instanceof SolidBucketItem
				|| tool.is(Items.GLASS_BOTTLE) || tool.is(Items.POTION);
		}
		return false;
	}

	// ---- 容器(S7.5) ----

	/** handleContainerInput 的檢查：所有容器都可以拿也可以放，只擋創造模式的複製。 */
	public Verdict canContainerInput(ContainerInput type) {
		if (type == ContainerInput.CLONE) {
			return Verdict.deny(ErrorCode.INVALID_ARGUMENT, "creative clone is not allowed");
		}
		return Verdict.ALLOW;
	}

	// ---- 擋下時 ----

	private volatile Verdict lastBlocked;
	private volatile long lastBlockedTick = Long.MIN_VALUE;
	/** 被擋下的累計次數：同一個 tick 裡判斷「這個動作有沒有被擋」用，不會把上一個 tick 的紀錄算進來(H12)。 */
	private final java.util.concurrent.atomic.AtomicLong blockedCount = new java.util.concurrent.atomic.AtomicLong();

	/** 動作前記下目前的累計次數，動作後用 {@link #blockedAfter} 看這段期間有沒有被擋。 */
	public long blockMark() {
		return blockedCount.get();
	}

	/** 從 mark 之後被擋下的判定；沒有就回傳 null。 */
	public Verdict blockedAfter(long mark) {
		return blockedCount.get() > mark ? lastBlocked : null;
	}

	/** 從 sinceTick 之後最近一次被擋下的判定；沒有就回傳 null。讓任務失敗時照實回報被擋的原因。 */
	public Verdict blockedSince(long sinceTick) {
		Verdict v = lastBlocked;
		return v != null && lastBlockedTick >= sinceTick ? v : null;
	}

	private volatile long lastBuildBlockedLogTick = -1;

	public static boolean shouldLogBuildBlocked(long now, long lastTick) {
		return lastTick < 0 || now - lastTick >= 100;
	}

	/**
	 * 施工中 canPlaceForBuild 擋下 Baritone 重複點擊或非法墊腳(R6)：照樣更新計數供 DoorPlacer 的 blockedAfter 讀取，
	 * 但不送 PROMPT 事件、不記進 recentProblems，日誌每 100 tick 最多一筆，免得洗版嚇停 AI。
	 */
	public void reportBuildBlocked(String action, Verdict v) {
		lastBlocked = v;
		long now = agent.tick();
		lastBlockedTick = now;
		blockedCount.incrementAndGet();
		if (shouldLogBuildBlocked(now, lastBuildBlockedLogTick)) {
			lastBuildBlockedLogTick = now;
			agent.log.log(ActionLog.Source.guard, action, v.code() + " " + v.reason());
		}
	}

	public void reportBlocked(String action, Verdict v) {
		lastBlocked = v;
		lastBlockedTick = agent.tick();
		blockedCount.incrementAndGet();
		agent.events.add("rule_blocked", Event.Level.PROMPT, blockagent.mcp.Kv.of().put("action", action).put("code", v.code()) + " | " + v.reason()
			+ (v.hint() == null ? "" : " | hint=" + v.hint()));
		agent.log.log(ActionLog.Source.guard, action, v.code() + " " + v.reason());
		agent.noteProblem("rule_blocked " + action + " " + v.code() + " " + v.reason());
	}
}
