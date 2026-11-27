package blockagent.game.tasks;

import blockagent.core.BookReader;
import blockagent.game.Agent;
import blockagent.game.Fmt;
import blockagent.mcp.ErrorCode;
import blockagent.mcp.Kv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 讀取書台上的書(docs/SPEC.md S3.2a)：開啟 {@link LecternMenu} 等同步後讀出內容並立刻關閉。
 */
public final class LecternReadTask extends ContainerTask {
	private final BlockPos pos;
	private String bookResult;

	public LecternReadTask(Agent agent, BlockPos pos) {
		super(agent, "use_block");
		this.pos = pos.immutable();
		this.timeoutTicks = 20 * 10;
	}

	@Override
	public String describe() {
		return "閱讀書台";
	}

	@Override
	public String progress() {
		return "block=lectern pos=" + Fmt.pos(pos);
	}

	@Override
	protected void start(Minecraft mc) {
		beginContainer(mc, pos);
	}

	@Override
	protected boolean isExpectedMenu(AbstractContainerMenu menu) {
		return menu instanceof LecternMenu;
	}

	@Override
	protected boolean isMenuDataReady(AbstractContainerMenu menu) {
		return menu instanceof LecternMenu lm && !lm.getBook().isEmpty();
	}

	@Override
	protected String openTimeoutReason() {
		return "could not open the lectern at " + Fmt.pos(pos);
	}

	@Override
	protected void work(Minecraft mc) {
		LecternMenu lm = (LecternMenu) mc.player.containerMenu;
		ItemStack book = lm.getBook();
		if (book.isEmpty()) {
			MenuOps.close(mc);
			markOpenedContainer(false);
			fail(ErrorCode.NOT_FOUND, "lectern has no book at " + Fmt.pos(pos));
			return;
		}
		BookReader.BookView view = Fmt.readBook(book, 1);
		bookResult = Kv.of().put("item", blockagent.game.Inv.id(book)).add(Fmt.bookKv(view)).toString();
		finishContainer();
	}

	@Override
	protected void onClosed(Minecraft mc) {
		done(bookResult == null ? "" : bookResult);
	}
}
