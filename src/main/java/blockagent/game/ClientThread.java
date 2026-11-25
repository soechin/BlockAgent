package blockagent.game;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 把工具呼叫交給 client thread 執行(docs/SPEC.md S2.1)。 */
public final class ClientThread {
	public static final long TIMEOUT_MS = 5_000;

	private ClientThread() {
	}

	public static <T> T call(Supplier<T> task) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) return task.get();
		CompletableFuture<T> f = mc.submit(task);
		try {
			return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			Throwable c = e.getCause();
			if (c instanceof Exception ex) throw ex;
			throw e;
		} catch (TimeoutException e) {
			f.cancel(false);
			throw new TimeoutException("game did not respond (client thread busy)");
		}
	}
}
