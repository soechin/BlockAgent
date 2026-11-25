# BlockAgent 開發與審查護欄

> 整理自 `docs/REVIEW.md` 三輪複查的實戰教訓。凡是修改 `src/main`、`src/gametest` 或 `bin/`，開發者與 AI 助理皆須遵守以下規則，避免修舊問題時引入同類回歸。

---

## 1. 修改前必查呼叫端與實作(杜絕局部視野盲點)

- **變更回傳條件或前置假設必 grep 所有呼叫端**：例如在 `Combat.strike()` 加 `!hasLineOfSight` 時，必須逐一檢查 `melee`/`creeper`/`ranged` 的 `if (inReach) return;` 是否因此跳過 `approach()`；變更 `BuildPreflight` 的材料計數規則時，必須同步檢查 `Tools.build` 的錯誤回報公式。
- **不靠函式名稱猜語意**：呼叫任何既有輔助方法(例如 `Inv.worthDigging`、`RuleGuard.isInteractive`、`TaskRunner.claim`)前，必須先讀其實作確認邊界條件。
- **注意語句順序與短路**：在 `if/else if` 鏈、早期 `return`(例如 `if (state == null) return TIMEOUT` 之後的容器補救)或狀態轉換前，檢查新分支是否會被前置條件吞掉成為死碼。
- **Baritone/全域設定在 `start()` 擷取、在 `onStop()` `try/finally` 還原**：不可在建構子(`new XxxTask()`)擷取全域設定，因為建構子執行時機早於 `TaskRunner.start()` 取消舊任務。

---

## 2. Minecraft 26.3 原版行為必查位元碼，不可靠記憶猜測

凡涉及方塊互動、物品繼承、容器封包、實體狀態或 Mixin 目標簽章，實作前必須先用 `javap` 核對本機快取之反編譯 jar(`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/minecraft-merged-*-26.3.jar`)：

- **確認繼承階層**：例如 `SolidBucketItem extends BlockItem`、`StrawBedBlock extends AbstractBedBlock`、`Items.STRAW_BED`/`Items.PITCHER_PLANT` 使用普通 `BlockItem`。
- **確認互動落點與執行順序**：例如 `FlintAndSteelItem.useOn`/`FireChargeItem.useOn` 一律在 `clickedPos.relative(clickedFace)` 生火(不看 `canBeReplaced()`)；`MultiPlayerGameMode.performUseItemOn` 先跑方塊 `useItemOn` 再跑 `Item.useOn`。
- **確認封包與同步時序**：
  - `setSelectedSlot()` 不會立刻送出 `ServerboundSetCarriedItemPacket`，須由 `ensureHasSentCarriedItem()` 刷出。
  - `ClientboundOpenScreenPacket` 到達早於 `ClientboundContainerSetContentPacket`；所有開啟/恢復容器的階段(`OPEN` → `SYNC`)都必須等內容封包同步後才讀槽位或點擊，且每個容器操作階段開頭都要驗證畫面仍是預期容器。

---

## 3. 跨執行緒邊界顯式化

本專案存在三種執行緒：
1. **Minecraft client thread**：玩家操作、世界方塊、容器、`TaskRunner`、`Reflexes`。
2. **MCP HTTP thread(`blockagent-mcp-*`)**：工具參數解析與等待；操作遊戲狀態一律經 `ClientThread.call(...)`。
3. **Baritone pathing background thread**：呼叫 `CalculationContextMixin`、`RuleGuard`、`ToolSetMixin`、`BuildTask.protects/reserves/forbidsScaffold`。

**規則**：凡是背景執行緒會讀取的欄位，禁止直接讀普通 `HashMap`/`LinkedHashMap`/`ArrayList`；必須改用 `volatile` 不可變快照、`ConcurrentHashMap`(或 `newKeySet()`)、或 `synchronized`，並在欄位註解標明哪條執行緒會讀取。

---

## 4. 可測試性與分批驗證

- **把高風險狀態決策抽成純邏輯**：涉及冷卻時間、戰術切換條件、掃描上限、體積溢位、停滯判斷、時間切點等不依賴 `Minecraft.getInstance()` 的邏輯，優先放在 `blockagent.core`(如 `DayClock`、`CombatPolicy`、`OwnershipLedger`)或 package-private 純靜態方法，並於 `src/test` 補毫秒級 JUnit 測試。
- **跨線改動必對齊共用入口**：像 `TaskRunner.claim()`、`Blueprints.precheckStart()`、`RuleGuard.blockMark()`/`blockedAfter()` 這類跨模組護欄，新增條件時必須同步檢查所有平行入口。
- **小步驗證**：每個子系統改完立刻執行 `./gradlew test`；涉及安全防線、容器恢復或 Mixin 時，補齊對應 `RulesGameTest`/`SessionGameTest` 回歸案例並執行 `./gradlew build`(驗證 `verifyReleaseJar`)與 `./gradlew runClientGameTest`。

---

## 5. 中文排版與符號慣例

凡撰寫文件、提示詞、註解或介面文字，一律遵循以下符號慣例：

- **標準中文標點保留全形**：`，。、：；「」…—？`。
- **括號與運算/分隔符號一律半形(不額外加空白)**：`()`、`/`、`=`、`+`(例：`設定檔(時間上限)`、`開啟/恢復`、`0=不限時`、`單人+專用伺服器`)；程式碼內的運算子仍照語言慣例留白。
- **數值與編號範圍用 en-dash**：`–`(例：`S5.1–S5.4`、`範圍 3–8`)，不用全形波浪號。
- **遊戲內 HUD 用顏色和空白分段，不加分隔符**(例：`● 挖 鐵礦 4/10 …… 剩 42 分`)；長期和短期目標之間用 `›`(例：`目標 建立基地 › 勘察空地`)，不用 `/`，免得跟計數 `4/10` 混淆。
- **文件圖表用 Mermaid**：架構圖、流程圖、狀態機一律使用 `mermaid` 區塊(`flowchart` / `stateDiagram-v2`)，不用 ASCII 框線繪圖。
