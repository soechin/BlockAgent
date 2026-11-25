package blockagent.game.tasks;

import blockagent.game.Agent;
import blockagent.game.Inv;
import blockagent.game.UseKey;
import blockagent.mcp.ErrorCode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

/** 吃東西：拿到手上、按住使用鍵直到吃完。 */
public final class EatTask extends Task {
	private final Item food;
	private int startCount;
	private int startFood;

	public EatTask(Agent agent, Item food) {
		super(agent, "eat");
		this.food = food;
		this.timeoutTicks = 20 * 5;
	}

	@Override
	public String describe() {
		return "吃 " + name(food);
	}

	@Override
	public String progress() {
		return "food=" + Inv.id(food);
	}

	@Override
	protected void start(Minecraft mc) {
		startCount = Inv.count(mc.player, food);
		startFood = mc.player.getFoodData().getFoodLevel();
		if (!Inv.selectInHand(mc, s -> s.getItem() == food)) {
			fail(ErrorCode.MISSING_MATERIALS, "no " + Inv.id(food) + " in inventory");
			return;
		}
		UseKey.startEating(mc);
	}

	@Override
	protected void tick(Minecraft mc) {
		UseKey.holdOff(mc);
		if (Inv.count(mc.player, food) < startCount) {
			done("ate=" + Inv.id(food) + " food_before=" + startFood);
		} else if (!mc.player.isUsingItem() && elapsed() > 5) {
			fail(ErrorCode.INVALID_ARGUMENT, "could not eat (already full, or not food)");
		}
	}

	@Override
	protected void onStop(Minecraft mc) {
		UseKey.release(mc);
	}

	/** 反射接手時鬆開了使用鍵：重新拿起食物開始吃(H7)。 */
	@Override
	protected void onResume(Minecraft mc) {
		if (Inv.count(mc.player, food) < startCount) {
			done("ate=" + Inv.id(food) + " food_before=" + startFood);
			return;
		}
		if (!Inv.selectInHand(mc, s -> s.getItem() == food)) {
			fail(ErrorCode.MISSING_MATERIALS, "no " + Inv.id(food) + " in inventory");
			return;
		}
		startTick = agent.tick();
		UseKey.startEating(mc);
	}
}
