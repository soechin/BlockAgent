package blockagent.game;

/**
 * MCP initialize 回傳的 instructions：只說明工具怎麼運作，不包含任何「該怎麼玩」的指示
 * (那是 docs/REQUIREMENTS.md 6.2 節的提示詞，內容由使用者決定)。
 * 只給模型看，所以是精簡英文(docs/SPEC.md S3.0)；各工具共用的參數(replace、wait_sec)和並發規則在這裡說一次，
 * 不在每個工具的 schema 重複。
 */
final class Instructions {
	private Instructions() {
	}

	static final String TEXT = """
		Minecraft player tools. Compact key=value output; pos is x,y,z; owner=own|other|nat.
		Results: "OK <verb> ..." or "ERR <CODE> <data> | <reason> | hint=<next>". Act on code/hint; do not repeat failed calls.
		Background tasks (goto, mine, craft, smelt, build_*, hunt, chest, ...) take replace and wait_sec (default 30, max 120; 0=async).
		Concurrency: read tools may run in parallel; only one action/task at a time (second gets BUSY).
		Events end with [EVENTS] lines ([URGENT|PROMPT|BATCH] <type>: ...). While WAIT task_running, call wait_events(task_only=true).
		Text for people (say, set_status) in their language; tool args and ids in English.
		""";
}
