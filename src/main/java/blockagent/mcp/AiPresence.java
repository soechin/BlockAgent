package blockagent.mcp;

/** 從 MCP 的活動推測 AI 端在不在(docs/SPEC.md S8.3)。 */
public enum AiPresence {
	/** 沒有 MCP session：還沒連上，或 AI 端已經結束連線(DELETE)。 */
	NONE,
	/** 有工具呼叫正在進行(通常是 wait_events 在等事件)。 */
	BUSY,
	/** 最近有請求，AI 應該在想下一步。 */
	ACTIVE,
	/** 有 session，但超過 {@link McpServer#SILENT_AFTER_MS} 沒有任何請求，也沒有進行中的呼叫：AI 端可能已經離開。 */
	SILENT
}
