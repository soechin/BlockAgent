package blockagent.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/** 讀取工具參數，型別不對時丟出 {@link BadArgument}(會變成 INVALID_ARGUMENT)。 */
public final class Args {
	public static final class BadArgument extends RuntimeException {
		public BadArgument(String message) {
			super(message);
		}
	}

	private final JsonObject o;

	public Args(JsonObject o) {
		this.o = o == null ? new JsonObject() : o;
	}

	/** 所有參數的複本(例如把這次的參數跟存下來的參數合併)。 */
	public JsonObject json() {
		return o.deepCopy();
	}

	public boolean has(String k) {
		return o.has(k) && !o.get(k).isJsonNull();
	}

	public String str(String k) {
		JsonElement e = need(k);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new BadArgument(k + " must be a string");
		return e.getAsString();
	}

	public String str(String k, String def) {
		return has(k) ? str(k) : def;
	}

	/** 接受字串或整數(例如生物 target 可以傳 "sheep"、"1538" 或整數 1538)。 */
	public String strOrInt(String k) {
		JsonElement e = need(k);
		if (e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			if (p.isString()) return p.getAsString();
			if (p.isNumber()) {
				double d = p.getAsDouble();
				if (d == Math.rint(d) && Math.abs(d) <= 1e9) return Integer.toString((int) d);
			}
		}
		throw new BadArgument(k + " must be a string or an integer");
	}

	public int integer(String k) {
		JsonElement e = need(k);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new BadArgument(k + " must be an integer");
		double d = e.getAsDouble();
		if (d != Math.rint(d) || Math.abs(d) > 1e9) throw new BadArgument(k + " must be an integer");
		return (int) d;
	}

	public int integer(String k, int def) {
		return has(k) ? integer(k) : def;
	}

	public int integer(String k, int def, int min, int max) {
		int v = integer(k, def);
		if (v < min || v > max) throw new BadArgument(k + " must be between " + min + " and " + max);
		return v;
	}

	public double number(String k, double def) {
		if (!has(k)) return def;
		JsonElement e = o.get(k);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new BadArgument(k + " must be a number");
		return e.getAsDouble();
	}

	public boolean bool(String k, boolean def) {
		if (!has(k)) return def;
		JsonElement e = o.get(k);
		if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) throw new BadArgument(k + " must be true or false");
		return p.getAsBoolean();
	}

	public Args object(String k) {
		JsonElement e = need(k);
		if (!e.isJsonObject()) throw new BadArgument(k + " must be an object");
		return new Args(e.getAsJsonObject());
	}

	public JsonArray objectArray(String k, int max) {
		JsonElement e = need(k);
		if (!e.isJsonArray() || e.getAsJsonArray().isEmpty() || e.getAsJsonArray().size() > max) {
			throw new BadArgument(k + " must be an array of 1 to " + max + " objects");
		}
		for (JsonElement entry : e.getAsJsonArray()) {
			if (!entry.isJsonObject()) throw new BadArgument(k + " must contain only objects");
		}
		return e.getAsJsonArray();
	}

	public Map<String, Integer> itemCounts(String k) {
		JsonElement e = need(k);
		if (!e.isJsonObject()) throw new BadArgument(k + " must be an object {item_id: count}");
		Map<String, Integer> m = new LinkedHashMap<>();
		for (var en : e.getAsJsonObject().entrySet()) {
			JsonElement v = en.getValue();
			if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || v.getAsInt() < 1) {
				throw new BadArgument(k + "." + en.getKey() + " must be a positive integer");
			}
			m.put(en.getKey(), v.getAsInt());
		}
		if (m.isEmpty()) throw new BadArgument(k + " must not be empty");
		return m;
	}

	private JsonElement need(String k) {
		if (!has(k)) throw new BadArgument("missing required argument: " + k);
		return o.get(k);
	}
}
