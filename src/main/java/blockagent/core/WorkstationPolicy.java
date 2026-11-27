package blockagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作站任務(附魔台、鐵砧、砂輪與熔爐燃料)的純邏輯判斷(docs/SPEC.md S4.2e–S4.2h)，
 * 不依賴 Minecraft 客戶端類別，便於單元測試。
 */
public final class WorkstationPolicy {
	/** 原版鐵砧經驗等級花費上限(>=40 在生存模式為 Too Expensive)。 */
	public static final int ANVIL_MAX_COST = 39;

	private WorkstationPolicy() {
	}

	/** 附魔台單格預覽項目。 */
	public record EnchantOffer(int option, int level, int lapis, String clue, int clueLevel) {
		public String format() {
			StringBuilder sb = new StringBuilder();
			sb.append(option).append(":lv").append(level).append("(lapis=").append(lapis);
			if (clue != null && !clue.isEmpty()) {
				sb.append(",clue=").append(clue);
				if (clueLevel > 0) sb.append(':').append(clueLevel);
			}
			return sb.append(')').toString();
		}
	}

	/** 附魔台預覽結果摘要。 */
	public record EnchantPreview(List<EnchantOffer> offers, int maxLevel, String summary) {
		public boolean hasAny() {
			return !offers.isEmpty();
		}
	}

	/** 依附魔台同步的三格資料組出預覽摘要與最高可用等級(便於驗證 15 書架達 30 級)。 */
	public static EnchantPreview buildEnchantPreview(int[] costs, String[] clues, int[] clueLevels) {
		List<EnchantOffer> offers = new ArrayList<>(3);
		int maxLevel = 0;
		for (int i = 0; i < 3; i++) {
			int lv = costs != null && i < costs.length ? costs[i] : 0;
			if (lv <= 0) continue;
			if (lv > maxLevel) maxLevel = lv;
			String clue = clues != null && i < clues.length ? clues[i] : null;
			int clueLv = clueLevels != null && i < clueLevels.length ? clueLevels[i] : 0;
			offers.add(new EnchantOffer(i + 1, lv, i + 1, clue, clueLv));
		}
		List<String> parts = new ArrayList<>(offers.size());
		for (EnchantOffer o : offers) parts.add(o.format());
		return new EnchantPreview(List.copyOf(offers), maxLevel, String.join(";", parts));
	}

	/** 附魔台 3 格資料是否已同步就緒(至少 1 格有等級需求，且對應 clue 已非預設 -1)。 */
	public static boolean enchantOffersReady(int[] costs, int[] clueIds) {
		if (costs == null || costs.length < 3) return false;
		boolean any = false;
		for (int i = 0; i < 3; i++) {
			if (costs[i] > 0) {
				any = true;
				if (clueIds != null && i < clueIds.length && clueIds[i] < 0) return false;
			}
		}
		return any;
	}

	/** 原版鐵砧在非創造模式下 cost >= 40 不可執行(Too Expensive)。 */
	public static boolean anvilTooExpensive(int cost, boolean creative) {
		return !creative && cost > ANVIL_MAX_COST;
	}

	/** 檢查兩格輸入(鐵砧、砂輪)是否有兩個獨立可取用的物品(避免兩項都匹配到同一格單件物品)。 */
	public static boolean hasDistinctInputs(int firstCount, int secondCount, int unionCount) {
		return firstCount >= 1 && secondCount >= 1 && unionCount >= 2;
	}

	/**
	 * 熔爐自動選燃料(S4.2e)：優先選與原料不同種的燃料；若沒有其他燃料，
	 * 原料本身可燃且扣除要燒的 {@code smeltCount} 後剩餘量足以支撐 {@code fuelNeeded} 時，允許以剩餘原料當燃料。
	 */
	public static boolean canUseInputAsFallbackFuel(boolean inputIsFuel, int haveInput, int smeltCount, int fuelNeeded) {
		return inputIsFuel && fuelNeeded > 0 && haveInput - smeltCount >= fuelNeeded;
	}
}
