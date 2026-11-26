package blockagent.game.combat;

import blockagent.game.ActionLog;
import blockagent.mixin.AbstractArrowAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 將自動射擊與伺服器生成的箭、傷害和著地封包對上。只在客戶端主執行緒使用。 */
public final class ArcheryLog {
	enum Result { HIT, EMBEDDED, UNCONFIRMED }

	static final int SPAWN_WAIT_TICKS = 40;
	private static final int FLIGHT_WAIT_TICKS = 200;

	private record Shot(long id, long fight, ActionLog.Source source, String action, int targetId,
						Level level, int shooterId, Vec3 origin, Vec3 direction, long firedAt,
						Consumer<Result> resolved) {
	}

	private record Flight(Shot shot, AbstractArrow arrow, String launchDetail) {
	}

	private final ActionLog log;
	private final ArrayDeque<Shot> awaitingSpawn = new ArrayDeque<>();
	private final Map<Integer, Flight> flights = new HashMap<>();
	private long nextShotId;

	public ArcheryLog(ActionLog log) {
		this.log = log;
	}

	void fired(LocalPlayer player, Entity target, long now, long fight, ActionLog.Source source, String action,
			   String weapon, int drawTicks, Consumer<Result> resolved) {
		Shot shot = new Shot(++nextShotId, fight, source, action, target.getId(), player.level(), player.getId(),
			player.getEyePosition(), player.getViewVector(1.0f), now, resolved);
		awaitingSpawn.addLast(shot);
		log.log(source, action + "_shoot", Combat.id(target) + " 距離 " + Combat.fmt(target.distanceTo(player))
			+ keys(shot) + " weapon=" + weapon + " draw_ticks=" + drawTicks);
	}

	public void onEntityLoad(Entity entity, long now) {
		if (awaitingSpawn.isEmpty() || !(entity instanceof AbstractArrow arrow) || !(arrow.getOwner() instanceof LocalPlayer owner)) return;
		Shot match = null;
		for (Shot shot : awaitingSpawn) {
			if (shot.level() != arrow.level() || shot.shooterId() != owner.getId() || now - shot.firedAt() > SPAWN_WAIT_TICKS
				|| !inLaunchPath(shot, arrow)) continue;
			// 延遲或遺失生成封包時，可能有兩次發射能對上同一支箭，這時保留未確認。
			if (match != null) return;
			match = shot;
		}
		if (match != null) {
			awaitingSpawn.remove(match);
			Vec3 velocity = arrow.getDeltaMovement();
			double dot = Math.clamp(match.direction().dot(velocity.normalize()), -1, 1);
			String detail = velocity.lengthSqr() > 0 ? " launch_angle_error=" + Combat.fmt(Math.toDegrees(Math.acos(dot))) : "";
			flights.put(arrow.getId(), new Flight(match, arrow, detail));
		}
	}

	/** 生成位置可能是箭已飛過前幾 tick 的位置，弩箭一 tick 就可前進超過三格。 */
	private static boolean inLaunchPath(Shot shot, AbstractArrow arrow) {
		Vec3 direction = shot.direction();
		Vec3 displacement = arrow.position().subtract(shot.origin());
		if (displacement.lengthSqr() <= 1) return true;
		double forward = direction.dot(displacement);
		return direction.dot(arrow.getDeltaMovement().normalize()) >= 0.9 && forward >= -2 && forward <= 8
			&& displacement.subtract(direction.scale(forward)).lengthSqr() <= 4;
	}

	public void onDamage(Level level, int shooterId, int projectileId, int victimId) {
		Flight flight = flights.get(projectileId);
		if (flight == null || flight.shot().level() != level || flight.shot().shooterId() != shooterId) return;
		flights.remove(projectileId);
		Entity victim = level.getEntity(victimId);
		resolve(flight.shot(), Result.HIT, flight.launchDetail() + " victim_id=" + victimId
			+ (victim == null ? "" : " victim=" + Combat.id(victim)));
	}

	public void onEntityData(ClientLevel level, ClientboundSetEntityDataPacket packet) {
		Flight flight = flights.get(packet.id());
		if (flight == null || flight.shot().level() != level) return;
		int groundId = AbstractArrowAccessor.blockagent$inGroundData().id();
		if (packet.packedItems().stream().noneMatch(item -> item.id() == groundId && Boolean.TRUE.equals(item.value()))) return;
		flights.remove(packet.id());
		resolve(flight.shot(), Result.EMBEDDED, flight.launchDetail());
	}

	public void tick(ClientLevel level, long now) {
		awaitingSpawn.removeIf(shot -> {
			String reason = shot.level() != level ? "world_changed" : now - shot.firedAt() > SPAWN_WAIT_TICKS ? "no_projectile" : null;
			return expire(shot, reason);
		});
		flights.values().removeIf(flight -> {
			Shot shot = flight.shot();
			String reason = shot.level() != level ? "world_changed" : flight.arrow().isRemoved() ? "removed"
				: now - shot.firedAt() > FLIGHT_WAIT_TICKS ? "timeout" : null;
			if (reason == null) return false;
			resolve(shot, Result.UNCONFIRMED, " reason=" + reason + flight.launchDetail());
			return true;
		});
	}

	public void disconnect() {
		for (Shot shot : awaitingSpawn) expire(shot, "disconnected");
		for (Flight flight : flights.values()) resolve(flight.shot(), Result.UNCONFIRMED, " reason=disconnected" + flight.launchDetail());
		awaitingSpawn.clear();
		flights.clear();
	}

	private boolean expire(Shot shot, String reason) {
		if (reason == null) return false;
		resolve(shot, Result.UNCONFIRMED, " reason=" + reason);
		return true;
	}

	private void resolve(Shot shot, Result result, String detail) {
		shot.resolved().accept(result);
		log.log(shot.source(), shot.action() + "_shot_result", "result=" + result.name().toLowerCase(java.util.Locale.ROOT)
			+ keys(shot) + detail);
	}

	private static String keys(Shot shot) {
		return " shot=" + shot.id() + " fight=" + shot.fight() + " target_id=" + shot.targetId();
	}
}
