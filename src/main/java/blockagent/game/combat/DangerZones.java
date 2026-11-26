package blockagent.game.combat;

import blockagent.core.CombatPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 尋路要繞開的怪物範圍(docs/SPEC.md S9.1b)：看得到(載入中)的威脅，加上脫身或撤退時記下、之後看不到的怪物位置。
 * 遠程怪繞開到射程外({@link CombatPolicy#AVOID_RANGED_RADIUS})，近戰怪和苦力怕 {@link CombatPolicy#AVOID_MELEE_RADIUS}。
 * Baritone 在算路線的執行緒讀這份清單(AvoidanceMixin)，所以記憶用 synchronized。
 */
public final class DangerZones {
	public record Zone(BlockPos center, int radius, String what, long until) {
	}

	private static final double LIVE_RANGE = 64;
	private static final int MAX_REMEMBERED = 24;
	private final List<Zone> remembered = new ArrayList<>();
	/** 主執行緒每 10 tick 更新的快照：Baritone 在背景執行緒算路線，不能在那裡讀遊戲世界的生物。 */
	private volatile List<Zone> snapshot = List.of();

	/** 這種怪要繞開多遠。 */
	public static int radius(Entity e) {
		return Threats.kind(e) == Threats.Kind.RANGED ? CombatPolicy.AVOID_RANGED_RADIUS : CombatPolicy.AVOID_MELEE_RADIUS;
	}

	/** 記下 range 格內所有威脅的位置({@link CombatPolicy#DANGER_MEMORY_TICKS} 內，看不到了也繞開)。 */
	public synchronized void remember(Minecraft mc, Entity attacker, double range, long now) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(range), e -> Threats.isThreat(e, attacker))) {
			BlockPos at = e.blockPosition();
			remembered.removeIf(z -> z.center().distManhattan(at) <= 3);
			remembered.add(new Zone(at.immutable(), radius(e), Combat.id(e), now + CombatPolicy.DANGER_MEMORY_TICKS));
		}
		while (remembered.size() > MAX_REMEMBERED) remembered.removeFirst();
	}

	public synchronized void clear() {
		remembered.clear();
		snapshot = List.of();
	}

	/** 在主執行緒更新快照(Reflexes 每 10 tick 呼叫)。 */
	public void refresh(Minecraft mc, Entity attacker, long now) {
		snapshot = List.copyOf(zones(mc, attacker, now));
	}

	/** 給 Baritone 背景執行緒用：最近一次的快照。 */
	public List<Zone> snapshot() {
		return snapshot;
	}

	/** 現在要繞開的範圍：載入中的威脅(64 格內)，加上還沒過期的記憶。只能在主執行緒呼叫。 */
	public List<Zone> zones(Minecraft mc, Entity attacker, long now) {
		List<Zone> out = new ArrayList<>();
		LocalPlayer p = mc.player;
		if (p != null && mc.level != null) {
			for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(LIVE_RANGE), e -> Threats.isThreat(e, attacker))) {
				out.add(new Zone(e.blockPosition(), radius(e), Combat.id(e), now));
			}
		}
		synchronized (this) {
			remembered.removeIf(z -> z.until() < now);
			out.addAll(remembered);
		}
		return out;
	}

	/**
	 * 目的地在哪些怪物的範圍裡(goto 回報用)：例如 {@code skeleton:2,zombie:3}；沒有回傳空字串。
	 *
	 * @param y 省略 y 時是 null，只比水平距離
	 */
	public String nearDestination(Minecraft mc, Entity attacker, int x, Integer y, int z, long now) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Zone zone : zones(mc, attacker, now)) {
			double dx = zone.center().getX() - x, dz = zone.center().getZ() - z;
			double dy = y == null ? 0 : zone.center().getY() - y;
			if (dx * dx + dy * dy + dz * dz <= (double) zone.radius() * zone.radius()) counts.merge(zone.what(), 1, Integer::sum);
		}
		StringBuilder sb = new StringBuilder();
		for (var e : counts.entrySet()) sb.append(sb.isEmpty() ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
		return sb.toString();
	}
}
