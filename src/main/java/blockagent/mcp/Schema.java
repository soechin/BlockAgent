package blockagent.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 產生工具參數的 JSON Schema。說明是給模型看的精簡英文(docs/SPEC.md S3.0)；
 * 數值範圍、預設值和列舉寫成標準的 minimum、maximum、default、enum，讓模型在產生參數時就避開越界。
 * {@link #range}、{@link #def} 套用在最後一個加入的參數上。description 傳 null 表示名稱已經說明一切。
 */
public final class Schema {
	private final JsonObject root = new JsonObject();
	private final JsonObject props = new JsonObject();
	private final JsonArray required = new JsonArray();
	private JsonObject last;

	private Schema() {
		root.addProperty("type", "object");
		root.add("properties", props);
		root.addProperty("additionalProperties", false);
	}

	public static Schema object() {
		return new Schema();
	}

	public Schema integer(String name, String description, boolean req) {
		return prop(name, "integer", description, req);
	}

	public Schema number(String name, String description, boolean req) {
		return prop(name, "number", description, req);
	}

	public Schema string(String name, String description, boolean req) {
		return prop(name, "string", description, req);
	}

	public Schema bool(String name, String description, boolean req) {
		return prop(name, "boolean", description, req);
	}

	public Schema enumString(String name, String description, boolean req, String... values) {
		JsonObject p = base("string", description);
		JsonArray e = new JsonArray();
		for (String v : values) e.add(v);
		p.add("enum", e);
		return put(name, p, req);
	}

	public Schema object(String name, String description, boolean req, Schema fields) {
		JsonObject p = fields.build();
		if (description != null) p.addProperty("description", description);
		return put(name, p, req);
	}

	public Schema objectArray(String name, String description, boolean req, Schema items, int maxItems) {
		JsonObject p = base("array", description);
		p.add("items", items.build());
		p.addProperty("minItems", 1);
		p.addProperty("maxItems", maxItems);
		return put(name, p, req);
	}

	/** 物品 id → 數量 的物件，例如 {"oak_planks": 4}。 */
	public Schema itemCounts(String name, String description, boolean req) {
		JsonObject p = base("object", description);
		JsonObject ap = new JsonObject();
		ap.addProperty("type", "integer");
		ap.addProperty("minimum", 1);
		p.add("additionalProperties", ap);
		p.addProperty("minProperties", 1);
		return put(name, p, req);
	}

	/** 最後一個參數的 minimum 和 maximum(整數或數字)。 */
	public Schema range(int min, int max) {
		if (last == null) throw new IllegalStateException("range() 之前要先加參數");
		last.addProperty("minimum", min);
		last.addProperty("maximum", max);
		return this;
	}

	/** 最後一個參數的預設值(省略時模組用的值)。 */
	public Schema def(Object value) {
		if (last == null) throw new IllegalStateException("def() 之前要先加參數");
		switch (value) {
			case Number n -> last.addProperty("default", n);
			case Boolean b -> last.addProperty("default", b);
			case String s -> last.addProperty("default", s);
			default -> throw new IllegalArgumentException("不支援的預設值型別：" + value);
		}
		return this;
	}

	private Schema prop(String name, String type, String description, boolean req) {
		return put(name, base(type, description), req);
	}

	private static JsonObject base(String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		if (description != null) p.addProperty("description", description);
		return p;
	}

	private Schema put(String name, JsonObject p, boolean req) {
		props.add(name, p);
		last = p;
		if (req) required.add(name);
		return this;
	}

	public JsonObject build() {
		JsonObject copy = root.deepCopy();
		if (!required.isEmpty()) copy.add("required", required.deepCopy());
		return copy;
	}
}
