package blockagent.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 系統性地下挖礦({@code dig_tunnel}，docs/SPEC.md S4.2j)的純邏輯規劃與安全前瞻：
 * 階梯/水平切片座標、鄰接檢查格、火把護欄、落差判定與礦脈摘要。不依賴 Minecraft 類別，便於單元測試。
 */
public final class TunnelPlanner {
	public static final int MAX_LENGTH = 64;
	public static final int MAX_UNLIT_LENGTH = 12;
	public static final int DEFAULT_LENGTH = 16;
	public static final int DEFAULT_TORCH_EVERY = 8;
	public static final int MAX_TORCH_EVERY = 16;
	public static final int MAX_SAFE_DROP = 2;
	public static final int TORCH_LIGHT_THRESHOLD = 7;

	public enum Mode {
		FLAT("flat"), STAIRS_DOWN("stairs_down"), STAIRS_UP("stairs_up");

		private final String wire;

		Mode(String wire) {
			this.wire = wire;
		}

		public String wireName() {
			return wire;
		}

		public static Mode parse(String raw) {
			if (raw == null) return null;
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "flat" -> FLAT;
				case "stairs_down" -> STAIRS_DOWN;
				case "stairs_up" -> STAIRS_UP;
				default -> null;
			};
		}
	}

	public enum Dir {
		NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

		public final int dx;
		public final int dz;

		Dir(int dx, int dz) {
			this.dx = dx;
			this.dz = dz;
		}

		public static Dir parse(String raw) {
			if (raw == null) return null;
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "north" -> NORTH;
				case "south" -> SOUTH;
				case "east" -> EAST;
				case "west" -> WEST;
				default -> null;
			};
		}
	}

	public record Pos(int x, int y, int z) {
		public Pos offset(int ox, int oy, int oz) {
			return new Pos(x + ox, y + oy, z + oz);
		}
	}

	private TunnelPlanner() {
	}

	/** 驗證參數合法性(含無火把模式限 12 格與 target_y 邊界)；合法回傳 null，否則回傳錯誤說明。 */
	public static String validate(Mode mode, int length, Integer targetY, int startY, int torchEvery) {
		if (mode == null) return "mode must be stairs_down, stairs_up or flat";
		if (length < 1 || length > MAX_LENGTH) return "length must be between 1 and " + MAX_LENGTH;
		if (torchEvery < 0 || torchEvery > MAX_TORCH_EVERY) {
			return "torch_every must be between 0 and " + MAX_TORCH_EVERY;
		}
		if (torchEvery == 0 && length > MAX_UNLIT_LENGTH) {
			return "torch_every=0 is limited to at most " + MAX_UNLIT_LENGTH + " steps (bring torches for deeper tunnels)";
		}
		if (targetY != null) {
			if (mode == Mode.FLAT) return "target_y is only valid for mode=stairs_down or mode=stairs_up";
			if (mode == Mode.STAIRS_DOWN && startY <= targetY) {
				return "already at or below target_y=" + targetY + " (current y=" + startY + ")";
			}
			if (mode == Mode.STAIRS_UP && startY >= targetY) {
				return "already at or above target_y=" + targetY + " (current y=" + startY + ")";
			}
		}
		return null;
	}

	/** 實際目標步數：stairs_down / stairs_up 同時給 length 與 target_y 時取先到者。 */
	public static int targetSteps(Mode mode, int length, Integer targetY, int startY) {
		if (targetY != null) {
			if (mode == Mode.STAIRS_DOWN) return Math.min(length, Math.max(0, startY - targetY));
			if (mode == Mode.STAIRS_UP) return Math.min(length, Math.max(0, targetY - startY));
		}
		return length;
	}

	/** 下一步的立足點座標。 */
	public static Pos nextStand(Pos current, Mode mode, Dir dir) {
		int dy = switch (mode) {
			case FLAT -> 0;
			case STAIRS_DOWN -> -1;
			case STAIRS_UP -> 1;
		};
		return new Pos(current.x() + dir.dx, current.y() + dy, current.z() + dir.dz);
	}

	/**
	 * 本步需要清出的方塊(由上往下排，確保視線通暢並妥善處理上方落沙)：
	 * flat 挖 1×2(頭、腳)；stairs_down 挖 1×3(高 3 格避免撞頭並保留階梯)；
	 * stairs_up 先清目前站立處上方 1 格(跳躍不撞頭)，再清上一階的頭、腳兩格，走完後同樣形成雙向可走的 1×3 階梯。
	 */
	public static List<Pos> mineSlice(Pos current, Pos nextStand, Mode mode) {
		return switch (mode) {
			case STAIRS_DOWN -> List.of(
				nextStand.offset(0, 2, 0),
				nextStand.offset(0, 1, 0),
				nextStand
			);
			case STAIRS_UP -> List.of(
				current.offset(0, 2, 0),
				nextStand.offset(0, 1, 0),
				nextStand
			);
			case FLAT -> List.of(
				nextStand.offset(0, 1, 0),
				nextStand
			);
		};
	}

	/** 供既有測試與非向上模式的簡寫。 */
	public static List<Pos> mineSlice(Pos nextStand, Mode mode) {
		return mineSlice(nextStand, nextStand, mode);
	}

	/**
	 * 本步要掃描的鄰接格(與待挖格相鄰 1 格，排除待挖格本身與玩家目前站立的頭腳兩格)，
	 * 用於檢查岩漿與露出礦脈。
	 */
	public static List<Pos> adjacentCells(List<Pos> mineCells, Pos currentStand) {
		Set<Pos> exclude = new LinkedHashSet<>(mineCells);
		exclude.add(currentStand);
		exclude.add(currentStand.offset(0, 1, 0));
		Set<Pos> out = new LinkedHashSet<>();
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (Pos c : mineCells) {
			for (int[] d : dirs) {
				Pos n = c.offset(d[0], d[1], d[2]);
				if (!exclude.contains(n)) out.add(n);
			}
		}
		return List.copyOf(out);
	}

	/**
	 * 本步要檢查水流/水源的鄰接格(頭頂、前方、左右側面，不含純下方地板下)。
	 */
	public static List<Pos> waterWatchCells(List<Pos> mineCells, Pos currentStand) {
		Set<Pos> exclude = new LinkedHashSet<>(mineCells);
		exclude.add(currentStand);
		exclude.add(currentStand.offset(0, 1, 0));
		Set<Pos> out = new LinkedHashSet<>();
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (Pos c : mineCells) {
			for (int[] d : dirs) {
				Pos n = c.offset(d[0], d[1], d[2]);
				if (!exclude.contains(n)) out.add(n);
			}
		}
		return List.copyOf(out);
	}

	/**
	 * 計算 {@code nextStand} 下方連續多少格無支撐({@code solidBelow[0]} 為 {@code y-1}、{@code [1]} 為 {@code y-2}…)。
	 * 回傳值超過 {@link #MAX_SAFE_DROP} 表示下方為深坑或空洞。
	 */
	public static int dropDepth(boolean[] solidBelow) {
		int depth = 0;
		for (boolean solid : solidBelow) {
			if (solid) break;
			depth++;
		}
		return depth;
	}

	public static boolean isUnsafeDrop(int dropDepth) {
		return dropDepth > MAX_SAFE_DROP;
	}

	/** 此節點是否需要補插火把(開啟照明、間隔達標且方塊亮度 <= 7)。 */
	public static boolean shouldPlaceTorch(int torchEvery, int stepsSinceTorch, int blockLight) {
		return torchEvery > 0 && stepsSinceTorch >= torchEvery && blockLight <= TORCH_LIGHT_THRESHOLD;
	}
}
