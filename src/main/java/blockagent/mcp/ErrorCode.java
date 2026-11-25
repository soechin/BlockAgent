package blockagent.mcp;

/**
 * 錯誤碼清單(docs/SPEC.md S3.6)。{@link #hint} 是這個錯誤常用的下一步(S3.0d)，工具有更具體的建議時會覆蓋。
 */
public enum ErrorCode {
	MISSING_MATERIALS("gather or craft the missing items first"),
	MISSING_TOOL("craft or equip a suitable tool first"),
	NO_STATION(null),
	NOT_FOUND(null),
	PATH_BLOCKED("goto closer or pick another spot"),
	RULE_PROTECTED_BLOCK("pick another block or approach; do not work around the rule"),
	RULE_PROTECTED_ENTITY("pick another target; do not work around the rule"),
	BUSY("stop, or pass replace=true"),
	PAUSED("call wait_events until user_resumed"),
	SESSION_ENDED(null),
	CHAT_BLOCKED(null),
	RATE_LIMITED("act now and say it later"),
	CHAT_DISABLED("act without chatting"),
	INVALID_ARGUMENT(null),
	INTERRUPTED(null),
	TIMEOUT(null),
	/** 這個動作在目前的遊戲模式不能用(例如 creative_take 只能在創造模式)。 */
	WRONG_GAME_MODE(null),
	/** 模組內部錯誤(不是 AI 的問題)。 */
	INTERNAL("try another approach");

	private final String hint;

	ErrorCode(String hint) {
		this.hint = hint;
	}

	/** 預設的下一步，沒有就是 null。 */
	public String hint() {
		return hint;
	}
}
