package blockagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 地獄傳送門的格子配置(docs/SPEC.md S4.2k)。純座標計算，不碰遊戲物件。
 * 框架是寬 4、高 5 的直立長方形，原點是左下角，沿 x 軸或 z 軸往正方向展開：
 * 10 格黑曜石(不含四角)、4 個角落用一般方塊墊著讓上框有地方貼(原版不在乎角落是什麼)、
 * 中間 2×3 要是空氣，點火點在左下的底框上面。
 */
public final class PortalPlanner {
	public static final int WIDTH = 4;
	public static final int HEIGHT = 5;
	public static final int OBSIDIAN = 10;

	public record Cell(int x, int y, int z) {}

	/** @param frame 黑曜石框；corners 四角；interior 門裡的 6 格；base 點火時要點的底框格(火出現在它上面) */
	public record Layout(List<Cell> frame, List<Cell> corners, List<Cell> interior, Cell base) {}

	private PortalPlanner() {
	}

	/** @param alongX true 沿 x 軸展開(門面朝南北)，false 沿 z 軸(門面朝東西) */
	public static Layout layout(int x, int y, int z, boolean alongX) {
		List<Cell> frame = new ArrayList<>();
		List<Cell> corners = new ArrayList<>();
		List<Cell> interior = new ArrayList<>();
		for (int dy = 0; dy < HEIGHT; dy++) {
			for (int w = 0; w < WIDTH; w++) {
				Cell c = alongX ? new Cell(x + w, y + dy, z) : new Cell(x, y + dy, z + w);
				boolean side = w == 0 || w == WIDTH - 1;
				boolean edge = dy == 0 || dy == HEIGHT - 1;
				if (side && edge) corners.add(c);
				else if (side || edge) frame.add(c);
				else interior.add(c);
			}
		}
		Cell base = alongX ? new Cell(x + 1, y, z) : new Cell(x, y, z + 1);
		return new Layout(List.copyOf(frame), List.copyOf(corners), List.copyOf(interior), base);
	}

	/**
	 * 另一個維度的對應座標：主世界到地獄除以 8，地獄到主世界乘以 8(原版傳送門找出口的位置)。
	 * @return {x, z}
	 */
	public static int[] linkedXZ(int x, int z, boolean toNether) {
		return toNether ? new int[]{Math.floorDiv(x, 8), Math.floorDiv(z, 8)} : new int[]{x * 8, z * 8};
	}
}
