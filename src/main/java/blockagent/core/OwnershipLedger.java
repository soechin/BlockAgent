package blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 所有權帳本(docs/SPEC.md S7.4)：只記錄自己放的方塊。以(維度, 位置)為鍵，維度是
 * {@code minecraft:overworld} 這種 id，位置用 BlockPos.asLong 相同的打包方式(docs/REVIEW.md C2)。
 * 不依賴 Minecraft，方便單元測試。
 */
public final class OwnershipLedger {
	/** 放置後要在幾個 tick 內收到伺服器的方塊更新才算數。 */
	public static final int CONFIRM_TICKS = 10;
	/** 舊版帳本(沒有分維度)的方塊都算在主世界。 */
	public static final String OVERWORLD = "minecraft:overworld";

	private record Pending(String blockId, long tick) {
	}

	private final Map<String, Map<Long, String>> ownedBlocks = new HashMap<>();
	private final Map<String, Map<Long, Pending>> pendingPlacements = new HashMap<>();
	private boolean dirty;

	public static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	public static int unpackX(long p) {
		return (int) (p >> 38);
	}

	public static int unpackY(long p) {
		return (int) (p << 52 >> 52);
	}

	public static int unpackZ(long p) {
		return (int) (p << 26 >> 38);
	}

	private Map<Long, String> owned(String dim) {
		return ownedBlocks.computeIfAbsent(dim, d -> new HashMap<>());
	}

	private Map<Long, Pending> pending(String dim) {
		return pendingPlacements.computeIfAbsent(dim, d -> new HashMap<>());
	}

	// ---- 方塊 ----

	public synchronized boolean isOwned(String dim, long pos) {
		Map<Long, String> m = ownedBlocks.get(dim);
		return m != null && m.containsKey(pos);
	}

	public synchronized String ownedBlockId(String dim, long pos) {
		Map<Long, String> m = ownedBlocks.get(dim);
		return m == null ? null : m.get(pos);
	}

	/** 本客戶端送出放置動作時呼叫。 */
	public synchronized void expectPlacement(String dim, long pos, String blockId, long tick) {
		pending(dim).put(pos, new Pending(blockId, tick));
	}

	/**
	 * 收到伺服器的方塊更新時呼叫。
	 *
	 * @param causedBySelf 這次變化是不是自己造成的(自己挖掉、自己放置)
	 */
	public synchronized void onBlockUpdate(String dim, long pos, String newBlockId, boolean isAir, long tick, boolean causedBySelf) {
		Map<Long, Pending> pend = pending(dim);
		Map<Long, String> own = owned(dim);
		Pending p = pend.get(pos);
		if (p != null) {
			if (tick - p.tick() <= CONFIRM_TICKS && p.blockId().equals(newBlockId)) {
				own.put(pos, newBlockId);
				pend.remove(pos);
				dirty = true;
				return;
			}
			if (tick - p.tick() > CONFIRM_TICKS) pend.remove(pos);
		}
		String owned = own.get(pos);
		if (owned == null || owned.equals(newBlockId)) {
			// 作物長大、熔爐點火、門開關：方塊 id 不變，保留
			return;
		}
		if (isAir || !causedBySelf) {
			// 被挖掉或被別人換掉：不再是自己的
			own.remove(pos);
		} else {
			// 自己把自己的方塊變成別的(例如剝掉樹皮)：仍是自己的
			own.put(pos, newBlockId);
		}
		dirty = true;
	}

	/** 自己挖掉自己的方塊。 */
	public synchronized void onSelfBroke(String dim, long pos) {
		Map<Long, String> m = ownedBlocks.get(dim);
		if (m != null && m.remove(pos) != null) dirty = true;
	}

	public synchronized void expireTick(long tick) {
		for (var m : pendingPlacements.values()) m.values().removeIf(p -> tick - p.tick() > CONFIRM_TICKS);
	}

	/** 換世界時清掉只在這次連線有效的資料。 */
	public synchronized void clearTransient() {
		pendingPlacements.clear();
	}

	public synchronized int ownedBlockCount() {
		int n = 0;
		for (var m : ownedBlocks.values()) n += m.size();
		return n;
	}

	// ---- 存檔 ----

	public synchronized boolean takeDirty() {
		boolean d = dirty;
		dirty = false;
		return d;
	}

	/** {@code {"dims": {"minecraft:overworld": [[x, y, z, "minecraft:oak_planks"], ...], ...}}} */
	public synchronized JsonObject toJson() {
		JsonObject o = new JsonObject();
		JsonObject dims = new JsonObject();
		for (var d : ownedBlocks.entrySet()) {
			if (d.getValue().isEmpty()) continue;
			JsonArray blocks = new JsonArray();
			for (var e : d.getValue().entrySet()) {
				JsonArray a = new JsonArray();
				a.add(unpackX(e.getKey()));
				a.add(unpackY(e.getKey()));
				a.add(unpackZ(e.getKey()));
				a.add(e.getValue());
				blocks.add(a);
			}
			dims.add(d.getKey(), blocks);
		}
		o.add("dims", dims);
		return o;
	}

	/** 也讀舊版的 {@code {"blocks": [...]}}：當時沒有分維度，一律算主世界。 */
	public synchronized void loadJson(JsonObject o) {
		ownedBlocks.clear();
		if (o == null) return;
		if (o.has("blocks")) readBlocks(OVERWORLD, o.getAsJsonArray("blocks"));
		if (o.has("dims")) {
			for (var d : o.getAsJsonObject("dims").entrySet()) readBlocks(d.getKey(), d.getValue().getAsJsonArray());
		}
	}

	private void readBlocks(String dim, JsonArray blocks) {
		Map<Long, String> m = owned(dim);
		for (JsonElement el : blocks) {
			JsonArray a = el.getAsJsonArray();
			m.put(pack(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()), a.get(3).getAsString());
		}
	}
}
