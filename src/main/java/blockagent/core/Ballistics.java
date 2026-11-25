package blockagent.core;

/**
 * 箭的彈道(docs/SPEC.md S9.1c)：跟原版 AbstractArrow 每 tick 的順序一樣，先依速度移動，再乘阻力 0.99、扣重力 0.05。
 * 不依賴 Minecraft，方便單元測試。
 */
public final class Ballistics {
	public static final double ARROW_GRAVITY = 0.05;
	public static final double ARROW_DRAG = 0.99;
	/** 滿弓(蓄力 20 tick)的初速，格/tick。 */
	public static final double FULL_BOW_SPEED = 3.0;
	private static final int MAX_TICKS = 120;

	private Ballistics() {
	}

	/** 解出的仰角(度，往上為正，跟原版 xRot 相反)和飛行時間(tick)。 */
	public record Solution(double pitchDeg, int ticks) {
	}

	/**
	 * 水平距離 {@code dx}、高低差 {@code dy}(目標減去出發點)時，要用幾度仰角才打得到。
	 * 只取低弧線(-60° 到 45°)。打不到(太遠)回傳 null。
	 */
	public static Solution solve(double dx, double dy, double speed) {
		if (dx < 0.01) return new Solution(dy >= 0 ? 90 : -90, 1);
		double lo = -60, hi = 45;
		// 45° 時是最高的；那樣都打不到就是太遠
		if (heightAt(dx, hi, speed) < dy) return null;
		for (int i = 0; i < 40; i++) {
			double mid = (lo + hi) / 2;
			if (heightAt(dx, mid, speed) < dy) lo = mid;
			else hi = mid;
		}
		double pitch = (lo + hi) / 2;
		return new Solution(pitch, ticksTo(dx, pitch, speed));
	}

	/** 用這個仰角射出，水平走到 dx 時的高度(相對出發點)。走不到回傳負無限大。 */
	public static double heightAt(double dx, double pitchDeg, double speed) {
		double rad = Math.toRadians(pitchDeg);
		double vx = Math.cos(rad) * speed, vy = Math.sin(rad) * speed;
		double x = 0, y = 0;
		for (int t = 0; t < MAX_TICKS; t++) {
			double nx = x + vx, ny = y + vy;
			if (nx >= dx) return y + (ny - y) * (dx - x) / (nx - x);
			x = nx;
			y = ny;
			vx *= ARROW_DRAG;
			vy = vy * ARROW_DRAG - ARROW_GRAVITY;
		}
		return Double.NEGATIVE_INFINITY;
	}

	static int ticksTo(double dx, double pitchDeg, double speed) {
		double rad = Math.toRadians(pitchDeg);
		double vx = Math.cos(rad) * speed, x = 0;
		for (int t = 1; t <= MAX_TICKS; t++) {
			x += vx;
			if (x >= dx) return t;
			vx *= ARROW_DRAG;
		}
		return MAX_TICKS;
	}
}
