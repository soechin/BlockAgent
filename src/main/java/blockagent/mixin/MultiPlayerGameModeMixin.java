package blockagent.mixin;

import blockagent.game.Agent;
import blockagent.game.RuleGuard;
import blockagent.game.tasks.ClearAreaTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 守門員的最後防線(docs/SPEC.md S7.2)與所有權追蹤(S7.4)。
 * 所有破壞、放置、攻擊、互動、容器點擊都先經過 RuleGuard。
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Unique
	private static final ThreadLocal<BlockState> blockagent$breaking = new ThreadLocal<>();
	@Unique
	private static final ThreadLocal<Object[]> blockagent$using = new ThreadLocal<>();

	@Unique
	private static boolean blockagent$denyBreak(BlockPos pos, String action) {
		Agent a = Agent.get();
		Minecraft mc = Minecraft.getInstance();
		if (a == null || !a.guard.active() || mc.level == null) return false;
		if (a.tasks.current() instanceof ClearAreaTask clear && !clear.authorizesBreak(pos)) return true;
		RuleGuard.Verdict v = a.guard.canBreak(mc.level, pos);
		if (v.allowed()) return false;
		a.guard.reportBlocked(action, v);
		return true;
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void blockagent$startDestroy(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
		if (blockagent$denyBreak(pos, "break_block")) cir.setReturnValue(false);
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void blockagent$continueDestroy(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
		if (blockagent$denyBreak(pos, "break_block")) {
			((MultiPlayerGameMode) (Object) this).stopDestroyBlock();
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
	private void blockagent$destroyHead(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (blockagent$denyBreak(pos, "break_block")) {
			cir.setReturnValue(false);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		blockagent$breaking.set(mc.level == null ? null : mc.level.getBlockState(pos));
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void blockagent$destroyReturn(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		BlockState before = blockagent$breaking.get();
		blockagent$breaking.remove();
		Agent a = Agent.get();
		if (a != null && before != null && cir.getReturnValueZ()) {
			a.tracker.onSelfBroke(pos.immutable(), before);
			// 尋路順手拆掉自己的建築(R10)：不是 break_block、施工、清空區域、收成或主動開採目標造成的就記一筆，方便事後追查
			var t = a.tasks.current();
			if (a.guard.active() && !before.isAir() && !a.guard.isNatural(before)
				&& !(t instanceof blockagent.game.tasks.BreakTask) && !(t instanceof blockagent.game.tasks.BuildTask)
				&& !(t instanceof ClearAreaTask) && !(t instanceof blockagent.game.tasks.FarmTask)
				&& !(t instanceof blockagent.game.tasks.DigTunnelTask)
				&& !(t instanceof blockagent.game.tasks.MineTask mine && mine.isMineTarget(before))) {
				a.log.log(blockagent.game.ActionLog.Source.system, "path_break", blockagent.game.Fmt.pos(pos) + " " + RuleGuard.shortId(before));
			}
		}
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void blockagent$useItemOnHead(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Agent a = Agent.get();
		Minecraft mc = Minecraft.getInstance();
		blockagent$using.remove();
		if (a == null || mc.level == null) return;
		ItemStack stack = player.getItemInHand(hand).copy();
		BlockPos target = hit.getBlockPos();
		BlockState targetState = mc.level.getBlockState(target);
		BlockPos placeAt = null;
		if (stack.getItem() instanceof BlockItem bi) {
			BlockPlaceContext ctx = bi.updatePlacementContext(new BlockPlaceContext(player, hand, stack, hit));
			if (ctx != null) placeAt = ctx.getClickedPos().immutable();
		}
		if (a.guard.active()) {
			boolean sneaking = player.isSecondaryUseActive();
			// 沒蹲下且用主手點門、箱子、床這類方塊，原版先執行 useWithoutItem，手上的方塊或工具不會作用；
			// 副手(OFF_HAND)原版會跳過 useWithoutItem 直接執行 stack.useOn，所以 handActs 必為 true(Fail-closed)
			boolean handActs = sneaking || hand != InteractionHand.MAIN_HAND || !blockagent$isInteractive(targetState, target);
			boolean placing = placeAt != null && handActs;
			if (placing && a.tasks.current() instanceof ClearAreaTask) {
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}
			RuleGuard.Verdict v = placing ? a.guard.canPlaceForBuild(mc.level, placeAt, stack.getItem()) : RuleGuard.Verdict.ALLOW;
			if (!v.allowed()) {
				a.guard.reportBuildBlocked("use_item_on_block", v);
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}
			// 先檢查工具/物品對「被點擊格本身」的改動(S7.3c)：不可放進 if (placing) 的 else 分支，
			// 否則繼承 BlockItem 的 SolidBucketItem(細雪桶)點別人煉藥鍋會繞過此檢查(P3-H1)。
			// 非 BLOCK_TRANSFORMER(例如打火石點蠟燭蛋糕)即使對方塊是互動方塊也會在 useItemOn 生效，不受 handActs 限制；
			// BLOCK_TRANSFORMER(斧/鏟/鋤)拿著開別人的門/箱子時 !sneaking 不會改方塊，仍要看 handActs。
			boolean changesTarget = RuleGuard.toolWouldChange(stack, targetState)
				&& (handActs || !stack.has(DataComponents.BLOCK_TRANSFORMER));
			if (changesTarget && !a.guard.isNatural(targetState) && !a.guard.isOwned(mc.level, target)) {
				v = new RuleGuard.Verdict(false, blockagent.mcp.ErrorCode.RULE_PROTECTED_BLOCK,
					"cannot change someone else's block with a tool (" + RuleGuard.shortId(targetState) + ")");
			} else if (placing) {
				// 蹲下放也一樣要檢查(docs/REVIEW.md C1)；門、床、高植物連另一格一起檢查(H3)
				v = a.guard.canPlaceInto(mc.level, placeAt);
				BlockPos second = v.allowed() ? blockagent$companion(player, hand, stack, hit, placeAt) : null;
				if (second != null) v = a.guard.canPlaceInto(mc.level, second);
			} else if (handActs && (stack.is(net.minecraft.world.item.Items.FLINT_AND_STEEL) || stack.is(net.minecraft.world.item.Items.FIRE_CHARGE))
				&& !changesTarget) {
				// 原版打火石/火焰彈一律在 target.relative(hit.getDirection()) 生火，不看 targetState.canBeReplaced()(P3-H2)。
				// 點燃營火、蠟燭、TNT 是改變那一格本身，上面已由 changesTarget 處理
				v = a.guard.canStartFire(mc.level, target.relative(hit.getDirection()));
			}
			if (!v.allowed()) {
				a.guard.reportBlocked("use_item_on_block", v);
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}
		}
		blockagent$using.set(new Object[]{stack, placeAt, target, targetState, placeAt == null ? null : mc.level.getBlockState(placeAt)});
	}

	/** 雙格方塊會連帶占用的另一格(門的上半、床頭、高植物的上半)；不是雙格方塊或放不下時回傳 null。 */
	@Unique
	private static BlockPos blockagent$companion(LocalPlayer player, InteractionHand hand, ItemStack stack, BlockHitResult hit, BlockPos placeAt) {
		if (!(stack.getItem() instanceof BlockItem bi)) return null;
		BlockState planned = bi.getBlock().getStateForPlacement(new BlockPlaceContext(player, hand, stack, hit));
		return planned == null ? null : RuleGuard.companionCell(planned, placeAt);
	}

	@Unique
	private static boolean blockagent$isInteractive(BlockState s, BlockPos pos) {
		// 安全防線只能對「原版保證吃掉右鍵、絕不落到 stack.useOn」的方塊略過手部檢查(Fail-closed)
		return RuleGuard.alwaysConsumesUse(Minecraft.getInstance().level, s, pos);
	}

	/**
	 * 直接使用手上的物品(不是對方塊按右鍵)：桶子倒出液體的位置比照放方塊，撈起液體比照破壞(S7.3b)。
	 * 落點用跟原版一樣的視線計算。
	 */
	@Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
	private void blockagent$useItemHead(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		Agent a = Agent.get();
		Minecraft mc = Minecraft.getInstance();
		if (a == null || mc.level == null || !a.guard.active()) return;
		ItemStack stack = player.getItemInHand(hand);
		double fire = blockagent.game.combat.Bow.fireSpeed(stack);
		if (fire > 0) {
			Entity friend = RuleGuard.protectedOnProjectilePath(player, fire);
			if (friend != null) {
				a.guard.reportBlocked("crossbow", new RuleGuard.Verdict(false, blockagent.mcp.ErrorCode.RULE_PROTECTED_ENTITY,
					"protected " + RuleGuard.describe(friend) + " is in the bolt's path"));
				cir.setReturnValue(InteractionResult.FAIL);
			}
			return;
		}
		if (!(stack.getItem() instanceof BucketItem bucket)) return;
		Fluid content = bucket.getContent();
		boolean empty = content == Fluids.EMPTY;
		// 跟原版 BucketItem.use 一樣的視線：空桶只會撈到水源，裝滿的桶不看液體(H1)
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1f).scale(player.blockInteractionRange()));
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
			empty ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
		BlockPos at = hit.getBlockPos();
		RuleGuard.Verdict v;
		if (empty) {
			v = a.guard.canBreak(mc.level, at);
		} else {
			// 水倒進樓梯、半磚、箱子這類可含水的方塊時，進的是點到的那一格本身；其他倒在點的那一面旁邊。
			// 點到的那格裝不下時原版會改倒在旁邊那格，所以兩格都要檢查
			BlockPos beside = at.relative(hit.getDirection());
			boolean intoClicked = mc.level.getBlockState(at).getBlock() instanceof LiquidBlockContainer && content == Fluids.WATER;
			v = a.guard.canPlaceInto(mc.level, beside);
			if (v.allowed() && intoClicked) v = a.guard.canPlaceInto(mc.level, at);
			// 岩漿跟火一樣，附近不能有別人的可燃方塊(H2)
			if (v.allowed() && content.isSame(Fluids.LAVA)) v = a.guard.canStartFire(mc.level, intoClicked ? at : beside);
		}
		if (!v.allowed()) {
			a.guard.reportBlocked("bucket", v);
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}

	/**
	 * 放開拉滿的弓、丟三叉戟前做拋物線檢查(S7.7)：路線上有玩家、寵物或村民就不射，
	 * 取消拉弓(不會射出去，UseKey.cancelUse)。
	 */
	@Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
	private void blockagent$release(Player player, CallbackInfo ci) {
		Agent a = Agent.get();
		Minecraft mc = Minecraft.getInstance();
		if (a == null || mc.level == null || !a.guard.active()) return;
		double speed = blockagent.game.combat.Bow.releaseSpeed(player);
		if (speed < 0.3) return;
		Entity friend = RuleGuard.protectedOnProjectilePath(player, speed);
		if (friend == null) return;
		a.guard.reportBlocked("bow", new RuleGuard.Verdict(false, blockagent.mcp.ErrorCode.RULE_PROTECTED_ENTITY,
			"protected " + RuleGuard.describe(friend) + " is in the arrow's path"));
		// 收弓不射：主手換格讓伺服器停止拉弓，客戶端也停下，免得原版下一 tick 又來放開(H8)
		blockagent.game.UseKey.cancelUse(mc);
		ci.cancel();
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void blockagent$useItemOnReturn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Object[] u = blockagent$using.get();
		blockagent$using.remove();
		Agent a = Agent.get();
		Minecraft mc = Minecraft.getInstance();
		if (u == null || a == null || mc.level == null || !cir.getReturnValue().consumesAction()) return;
		ItemStack stack = (ItemStack) u[0];
		BlockPos placeAt = (BlockPos) u[1];
		BlockPos target = (BlockPos) u[2];
		BlockState before = (BlockState) u[3];
		BlockState after = mc.level.getBlockState(target);
		if (before.getBlock() instanceof FlowerPotBlock && after.getBlock() instanceof FlowerPotBlock
			&& before.getBlock() != after.getBlock()) {
			a.tracker.expectOwnedChange(target, after);
		} else if (placeAt != null && !mc.level.getBlockState(placeAt).isAir() && mc.level.getBlockState(placeAt) != u[4]) {
			a.tracker.onPlaceAttempt(placeAt, mc.level.getBlockState(placeAt));
		} else if (RuleGuard.toolWouldChange(stack, before) && after.getBlock() != before.getBlock()
			&& (a.guard.isNatural(before) || a.guard.isOwned(mc.level, target))) {
			// 工具轉換(鋤地、鏟草徑、削樹皮、銅塊上蠟、煉藥鍋裝倒、剪刀刻南瓜…)：
			// 只有原本是天然或自己的方塊，變完才算自己的，並更新所有權避免被伺服器更新洗掉(H2、P3-M4)
			a.tracker.onPlaceAttempt(target, after);
		}
	}

	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void blockagent$attack(Player player, Entity target, CallbackInfo ci) {
		Agent a = Agent.get();
		if (a == null) return;
		if (a.guard.active()) {
			RuleGuard.Verdict v = a.guard.canAttack(target);
			if (!v.allowed()) {
				a.guard.reportBlocked("attack", v);
				ci.cancel();
				return;
			}
		}
	}

	@Inject(method = "piercingAttack", at = @At("HEAD"), cancellable = true)
	private void blockagent$piercing(SwingAnimation swing, PiercingWeapon weapon, CallbackInfo ci) {
		Agent a = Agent.get();
		Minecraft mc = Minecraft.getInstance();
		if (a == null || !a.guard.active() || mc.player == null || mc.level == null) return;
		Vec3 from = mc.player.getEyePosition();
		Vec3 to = from.add(mc.player.getLookAngle().scale(7));
		for (Entity e : mc.level.getEntities(mc.player, new AABB(from, to).inflate(1.5), RuleGuard::isProtectedEntity)) {
			if (e.getBoundingBox().inflate(0.6).clip(from, to).isPresent()) {
				a.guard.reportBlocked("spear_thrust", new RuleGuard.Verdict(false, blockagent.mcp.ErrorCode.RULE_PROTECTED_ENTITY,
					"protected " + RuleGuard.describe(e) + " is in the thrust range"));
				ci.cancel();
				return;
			}
		}
	}

	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void blockagent$interact(Player player, Entity target, net.minecraft.world.phys.EntityHitResult hit, InteractionHand hand,
									 CallbackInfoReturnable<InteractionResult> cir) {
		Agent a = Agent.get();
		if (a == null || !a.guard.active()) return;
		RuleGuard.Verdict v = a.guard.canInteract(target);
		if (!v.allowed()) {
			a.guard.reportBlocked("interact_entity", v);
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}

	@Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
	private void blockagent$containerHead(int containerId, int slot, int button, ContainerInput type, Player player, CallbackInfo ci) {
		Agent a = Agent.get();
		if (a == null) return;
		if (a.guard.active()) {
			RuleGuard.Verdict v = a.guard.canContainerInput(type);
			if (!v.allowed()) {
				a.guard.reportBlocked("container", v);
				ci.cancel();
				return;
			}
		}
	}

	@Inject(method = "handleCreativeModeItemAdd", at = @At("HEAD"), cancellable = true)
	private void blockagent$creativeAdd(ItemStack stack, int slot, CallbackInfo ci) {
		Agent a = Agent.get();
		// 唯一的例外是創造模式下的 creative_take(S4.6c)
		if (a != null && a.guard.active() && !(blockagent.game.Creative.takingNow() && blockagent.game.Creative.isCreative(Minecraft.getInstance()))) ci.cancel();
	}

	@Inject(method = "handleCreativeModeItemDrop", at = @At("HEAD"), cancellable = true)
	private void blockagent$creativeDrop(ItemStack stack, CallbackInfo ci) {
		Agent a = Agent.get();
		if (a != null && a.guard.active()) ci.cancel();
	}
}
