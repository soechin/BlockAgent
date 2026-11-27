package blockagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 物品屬性篩選語法與耐久排序偏好(docs/SPEC.md S4.2d)。純邏輯、不依賴 Minecraft 類別，便於單元測試。
 * <p>
 * 支援 {@code ench=<id>[:<min>]}、{@code stored=<id>[:<min>]}、{@code potion=<id>}、{@code name=<子字串>}、
 * {@code best_dur}/{@code low_dur}，以及純值簡寫(依序比對附魔、儲存附魔、藥水、自訂名稱/書名)；多條件以 {@code ;} 分隔(AND)。
 */
public final class ItemMatcher {
	public enum DurPref {NONE, BEST, LOW}

	/** 供比對用的純資料快照。 */
	public record ItemInfo(Map<String, Integer> enchants, Map<String, Integer> storedEnchants,
						   String potion, List<String> effects, String customName, String bookTitle,
						   int remainingDurability, int maxDurability) {
		public ItemInfo {
			enchants = enchants == null ? Map.of() : Map.copyOf(enchants);
			storedEnchants = storedEnchants == null ? Map.of() : Map.copyOf(storedEnchants);
			effects = effects == null ? List.of() : List.copyOf(effects);
		}
	}

	/** {@code chest} 物品鍵的解析結果：純 id 或 {@code id[條件]}。 */
	public record Spec(String itemId, ItemMatcher matcher) {
		public boolean hasMatcher() {
			return matcher != null && !matcher.isAny();
		}
	}

	private sealed interface Rule permits EnchRule, PotionRule, NameRule, ShorthandRule {
		boolean matches(ItemInfo info);
	}

	private record EnchRule(String id, int minLevel, boolean stored) implements Rule {
		@Override
		public boolean matches(ItemInfo info) {
			Map<String, Integer> map = stored ? info.storedEnchants() : info.enchants();
			return map.getOrDefault(id, 0) >= minLevel;
		}
	}

	private record PotionRule(String target) implements Rule {
		@Override
		public boolean matches(ItemInfo info) {
			return matchesPotion(info, target);
		}
	}

	private record NameRule(String needleLower) implements Rule {
		@Override
		public boolean matches(ItemInfo info) {
			return matchesName(info, needleLower);
		}
	}

	private record ShorthandRule(String rawLower, String normId, String levelId, int minLevel) implements Rule {
		@Override
		public boolean matches(ItemInfo info) {
			if (info.enchants().getOrDefault(normId, 0) >= 1) return true;
			if (info.storedEnchants().getOrDefault(normId, 0) >= 1) return true;
			if (levelId != null) {
				if (info.enchants().getOrDefault(levelId, 0) >= minLevel) return true;
				if (info.storedEnchants().getOrDefault(levelId, 0) >= minLevel) return true;
			}
			if (matchesPotion(info, normId)) return true;
			return matchesName(info, rawLower);
		}
	}

	public static final ItemMatcher ANY = new ItemMatcher("", List.of(), DurPref.NONE);

	private final String raw;
	private final List<Rule> rules;
	private final DurPref durPref;

	private ItemMatcher(String raw, List<Rule> rules, DurPref durPref) {
		this.raw = raw;
		this.rules = List.copyOf(rules);
		this.durPref = durPref;
	}

	public static Spec parseSpec(String raw) {
		if (raw == null || raw.isBlank()) throw new IllegalArgumentException("item spec must not be empty");
		String s = raw.trim();
		int open = s.indexOf('[');
		if (open < 0) {
			if (s.indexOf(']') >= 0) throw new IllegalArgumentException("unmatched ']' in item spec: " + raw);
			return new Spec(s, ANY);
		}
		if (!s.endsWith("]") || s.indexOf('[', open + 1) >= 0 || s.indexOf(']') != s.length() - 1) {
			throw new IllegalArgumentException("invalid item spec syntax: " + raw);
		}
		String itemId = s.substring(0, open).trim();
		String cond = s.substring(open + 1, s.length() - 1).trim();
		if (itemId.isEmpty() || cond.isEmpty()) {
			throw new IllegalArgumentException("invalid item spec syntax: " + raw);
		}
		return new Spec(itemId, parse(cond));
	}

	public static ItemMatcher parse(String raw) {
		if (raw == null || raw.isBlank()) throw new IllegalArgumentException("match expression must not be empty");
		String trimmed = raw.trim();
		String[] tokens = trimmed.split(";", -1);
		List<Rule> rules = new ArrayList<>();
		DurPref pref = DurPref.NONE;
		for (String tok : tokens) {
			String t = tok.trim();
			if (t.isEmpty()) throw new IllegalArgumentException("empty condition in match: " + raw);
			if (t.equalsIgnoreCase("best_dur")) {
				if (pref == DurPref.LOW) throw new IllegalArgumentException("cannot combine best_dur and low_dur");
				pref = DurPref.BEST;
				continue;
			}
			if (t.equalsIgnoreCase("low_dur")) {
				if (pref == DurPref.BEST) throw new IllegalArgumentException("cannot combine best_dur and low_dur");
				pref = DurPref.LOW;
				continue;
			}
			int eq = t.indexOf('=');
			if (eq >= 0) {
				String key = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
				String val = t.substring(eq + 1).trim();
				if (key.isEmpty() || val.isEmpty()) throw new IllegalArgumentException("invalid condition in match: " + t);
				switch (key) {
					case "ench" -> rules.add(parseEnchRule(val, false));
					case "stored" -> rules.add(parseEnchRule(val, true));
					case "potion" -> rules.add(new PotionRule(stripMc(val.toLowerCase(Locale.ROOT))));
					case "name" -> {
						String needle = unquote(val).toLowerCase(Locale.ROOT);
						if (needle.isEmpty()) throw new IllegalArgumentException("empty name in match: " + t);
						rules.add(new NameRule(needle));
					}
					default -> throw new IllegalArgumentException("unknown match key '" + key + "' (expected ench, stored, potion, name, best_dur, low_dur)");
				}
			} else {
				String rawLower = unquote(t).toLowerCase(Locale.ROOT);
				String norm = stripMc(rawLower);
				String levelId = null;
				int minLevel = 1;
				int colon = norm.lastIndexOf(':');
				if (colon > 0 && colon < norm.length() - 1) {
					String suffix = norm.substring(colon + 1);
					int parsed = tryPositiveInt(suffix);
					if (parsed > 0) {
						levelId = norm.substring(0, colon);
						minLevel = parsed;
					}
				}
				rules.add(new ShorthandRule(rawLower, norm, levelId, minLevel));
			}
		}
		return new ItemMatcher(trimmed, rules, pref);
	}

	private static EnchRule parseEnchRule(String val, boolean stored) {
		String s = stripMc(val.toLowerCase(Locale.ROOT));
		int colon = s.lastIndexOf(':');
		if (colon < 0) {
			if (s.isEmpty()) throw new IllegalArgumentException("empty enchantment id");
			return new EnchRule(s, 1, stored);
		}
		String id = s.substring(0, colon).trim();
		String lvlStr = s.substring(colon + 1).trim();
		int lvl = tryPositiveInt(lvlStr);
		if (id.isEmpty() || lvl <= 0) {
			throw new IllegalArgumentException("invalid enchantment level in match: " + val);
		}
		return new EnchRule(id, lvl, stored);
	}

	private static int tryPositiveInt(String s) {
		try {
			int v = Integer.parseInt(s);
			return v >= 1 ? v : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String stripMc(String s) {
		return s.startsWith("minecraft:") ? s.substring(10) : s;
	}

	private static String unquote(String s) {
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			return s.substring(1, s.length() - 1);
		}
		return s;
	}

	private static boolean matchesPotion(ItemInfo info, String target) {
		if (info.potion() != null && stripMc(info.potion().toLowerCase(Locale.ROOT)).equals(target)) return true;
		for (String eff : info.effects()) {
			if (stripMc(eff.toLowerCase(Locale.ROOT)).equals(target)) return true;
		}
		return false;
	}

	private static boolean matchesName(ItemInfo info, String needleLower) {
		if (info.customName() != null && info.customName().toLowerCase(Locale.ROOT).contains(needleLower)) return true;
		return info.bookTitle() != null && info.bookTitle().toLowerCase(Locale.ROOT).contains(needleLower);
	}

	public boolean isAny() {
		return rules.isEmpty() && durPref == DurPref.NONE;
	}

	public String raw() {
		return raw;
	}

	public DurPref durPref() {
		return durPref;
	}

	public boolean matches(ItemInfo info) {
		for (Rule r : rules) if (!r.matches(info)) return false;
		return true;
	}

	/** 依耐久偏好比較兩個已命中的物品：回傳負數表示 {@code a} 較優先；無耐久物照原本順序(回傳 0)。 */
	public int comparePreference(ItemInfo a, ItemInfo b) {
		if (durPref == DurPref.NONE || a.maxDurability() <= 0 || b.maxDurability() <= 0) return 0;
		return durPref == DurPref.BEST
			? Integer.compare(b.remainingDurability(), a.remainingDurability())
			: Integer.compare(a.remainingDurability(), b.remainingDurability());
	}
}
