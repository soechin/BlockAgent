package blockagent.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.ref.WeakReference;
import java.util.List;

/** 模組允許的方塊操作指令。 */
public final class BuildCommands {
	private static final ThreadLocal<String> AUTHORIZED_FILL = new ThreadLocal<>();
	public static final int CONFIRM_TICKS = 5 * 20;
	// 原版每則指令增加 20，每 tick 扣 1。不同任務共用同一連線的頻率。
	private static final int SEND_INTERVAL = 20;
	private final Agent agent;
	private WeakReference<ClientPacketListener> lastConnection = new WeakReference<>(null);
	private long sentAt;
	/** 還沒收到回饋的 /fill 數量。連續送出時每則各有一則回饋，用計數才不會讓後面的回饋漏進聊天事件(M11)。 */
	private int awaitingFeedback;

	BuildCommands(Agent agent) {
		this.agent = agent;
	}

	public static boolean available(Minecraft mc) {
		var connection = mc.getConnection();
		return Creative.isCreative(mc) && connection != null
			&& connection.getCommands().findNode(List.of("fill", "from", "to", "block", "replace", "filter")) != null;
	}

	public static boolean isAuthorized(String command) {
		return command.equals(AUTHORIZED_FILL.get());
	}

	/**
	 * 這格能不能用指令放這種方塊(S4.6f、docs/REVIEW.md H5)：原本或要放的方塊有方塊實體(箱子、熔爐、告示牌、生怪磚…)
	 * 都走一般操作；創造物品欄裡沒有的管理員方塊(屏障、光源方塊…)也不能用指令變出來。
	 */
	public static boolean directAllowed(Minecraft mc, BlockState current, net.minecraft.world.item.BlockItem item) {
		return !current.hasBlockEntity() && !item.getBlock().defaultBlockState().hasBlockEntity() && Creative.inCreativeTabs(mc, item);
	}

	public static boolean validTarget(ClientLevel level, BlockPos pos) {
		return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
			&& AreaInspection.loaded(level, pos);
	}

	public boolean ready(Minecraft mc) {
		return lastConnection.get() != mc.getConnection() || agent.tick() - sentAt >= SEND_INTERVAL;
	}

	public boolean fill(Minecraft mc, BlockPos from, BlockPos to, BlockState previous, BlockState material) {
		if (!available(mc) || !ready(mc)) return false;
		var connection = mc.getConnection();
		String command = "fill " + coordinates(from) + " " + coordinates(to) + " " + BlockStateParser.serialize(material)
			+ " replace " + BlockStateParser.serialize(previous);
		AUTHORIZED_FILL.set(command);
		try {
			ChatGateway.withAiFlag(() -> connection.sendCommand(command));
		} finally {
			AUTHORIZED_FILL.remove();
		}
		boolean sameConnection = lastConnection.get() == connection && agent.tick() - sentAt <= CONFIRM_TICKS;
		lastConnection = new WeakReference<>(connection);
		sentAt = agent.tick();
		// 換了連線或上一批早就逾時：舊的計數不算
		if (!sameConnection) awaitingFeedback = 0;
		awaitingFeedback++;
		return true;
	}

	boolean consumeFeedback(Component message) {
		if (awaitingFeedback <= 0 || lastConnection.get() != Minecraft.getInstance().getConnection()
			|| agent.tick() - sentAt > CONFIRM_TICKS) {
			awaitingFeedback = 0;
			return false;
		}
		// 原版的指令失敗訊息會包在一個空的紅色元件下。
		if (message.getContents().equals(PlainTextContents.EMPTY) && message.getSiblings().size() == 1) {
			message = message.getSiblings().getFirst();
		}
		if (!(message.getContents() instanceof TranslatableContents contents)) return false;
		switch (contents.getKey()) {
			case "commands.fill.success", "commands.fill.failed", "commands.fill.toobig",
				"argument.pos.unloaded", "argument.pos.outofworld", "argument.pos.outofbounds" -> {
				awaitingFeedback--;
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private static String coordinates(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
}
