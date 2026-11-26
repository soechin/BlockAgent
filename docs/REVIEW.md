# BlockAgent 專案審查紀錄

> 日期：2026-11-25
> 來源：Prism Launcher `26.3` 實機試玩紀錄(`sp-Live_2026_11_25-94c75847`，`2026-11-25 19:58–21:32`，`blockagent 2026.11.25-1957` + `codex-mcp-client 0.172.0` + 規劃模型 `gpt-6.2-sol`，區網與 `Anne201624` 雙人生存)及對應 Codex 對話紀錄(`~/.blockagent/codex-home/sessions/2026/11/25/`)。
> 局況摘要：總計 90.8 分鐘(`RUNNING` 86.4 分、`PAUSED` 4.3 分)、遊戲端 1,616 筆紀錄、Codex 端 1,143 次 MCP 呼叫(46 次錯誤)、背景任務完成率 313/336(93.2%)、死亡 3 次(皆為骷髏射殺)、無 Java 例外或崩潰；規劃服務 17 次全數成功(平均 12.84 秒)。
> 註：先前審查(`2026-11-22`–`2026-11-24` 之 C1–C7、H1–H13、M1–M17、L1–L6、A1–A15、Phase 2/3 複查)項目已全數修畢並移除，歷史紀錄留存於 git。

---

## Critical(最優先)

- [x] **R1. 對遠程怪舉盾過早放盾且移動搶視角，盾牌實戰幾乎零格擋**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:330-338`
  - **證據**：`21:19:42–21:20:20` 身穿全身鐵甲+盾牌觸發 4 次 `shield_vs_bow`(每次僅持續 0.54–0.65 秒)，但格擋箭矢進度 `[抱歉，今天不行]` 一次都沒觸發；直到 `21:20:24` 使用者手動接手後 9 秒(`21:20:33`)才解鎖，AI 在接手前已被打到 `hp=5` 並於 `21:20:44` 陣亡。
  - **問題**：
    - 骷髏放箭的那一 tick，`target.isUsingItem()` 立刻變為 `false`，`else` 分支馬上呼叫 `lowerShield(mc)`；在 8–12 格距離，箭矢仍需飛行 5–8 ticks(0.25–0.4 秒)才會命中，等同在箭到臉前主動放盾。
    - 第 338 行在 `shield_vs_bow` 期間仍呼叫 `approach(mc)`，Baritone 尋路每 tick 覆寫玩家視角朝向移動路線，蓋掉第 332 行的 `Moves.lookAt`，盾牌沒正對骷髏導致箭矢從側面穿過。
  - **方向**：偵測到遠程怪拉弓時設定舉盾維持時間窗(例如 `shieldHoldUntil = tick + 12`，覆蓋 5-tick 舉盾預熱與箭矢飛行時間)；舉盾期間暫停 Baritone 轉向(`halt()` 或保持面向目標)，避免視角被搶。
  - **處理(2026-11-26)**：新增 `combat/ShieldGuard`：遠程怪拉弓 ≥ 6 tick 或預測 10 tick 內有箭/三叉戟/火球命中時舉盾，放箭後維持 `CombatPolicy.shieldHoldTicks(距離)` 直到箭到；`Combat.tick()` 一開始先跑守備，擋的時候停下 Baritone 並每 tick 對準射手或箭的來向。撤退反射也共用。

- [x] **R2. `Combat.noCloserTicks` 命中擊退後未重置，砍一刀即誤判走不近並收盾逃跑**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:213-218,317-320,355-368,399-430`
  - **證據**：`21:19:45.839` 貼近骷髏(距離 3.3)砍出第 1 刀後，精準經過 2.994 秒(60 ticks)即於 `21:19:48.833` 突然放棄追擊，切換成 `back_off` / `hide_from_skeleton`，放走殘血骷髏並在後續遭遇苦力怕、殭屍與第二隻骷髏的車輪戰。
  - **問題**：近戰命中擊退目標後 `closest` 卡死在 `3.3`，且 `strike()` 成功出刀並未重置 `noCloserTicks` 與 `closest`；重新接近時 `d < closest - 0.5` 不成立，滿 60 ticks 就跳進 `takeCover(mc)`，而第 356 行第一件事便是無條件 `lowerShield(mc)`，讓角色收盾背對骷髏吃箭。此外當 `hp <= 8`(`COVER_HEALTH`)且無弓時會鎖死在 `takeCover`，完全喪失舉盾與反擊能力。
  - **方向**：`strike()` 成功出刀時重置 `noCloserTicks = 0` 與 `closest = d`；`takeCover()` 找掩體途中若遠程怪正在拉弓，應先舉盾擋完箭而非無條件 `lowerShield`。
  - **處理(2026-11-26)**：`strike()` 出刀後重設 `closest`/`noCloserTicks`，擋箭的 tick 不累加；擋箭由 `ShieldGuard` 在戰術之前處理，`takeCover()` 只在沒箭要擋時才收盾移動，途中目標進入攻擊距離照樣砍(`hit_from_cover`)。原本的 `COVER_HEALTH` 改由勝算(`CombatPolicy.rangedStance`)決定要不要躲。

- [x] **R3. `RETREAT` 反射陷入震盪死循環，打斷進食與回屋並迫使 AI 關掉撤退**
  - **位置**：`src/main/java/blockagent/game/reflex/Reflexes.java:58,257-259,379-385,418`
  - **證據**：`21:23:04–21:23:52` 47 秒內連續觸發 5 次 `retreat_start(hp=4 hostiles_near)`，依序打斷 `goto 109,74,74`、兩次 `goto 87,67,227`(回屋)，甚至在 `21:23:47` 直接打斷 **`eat melon_slice`**；AI 被迫於 `21:23:52` 呼叫 `set_reflex retreat=false` 硬衝回屋，隨即被 `self_defense` 拖住並於 10 秒後(`21:24:02`)在 `76,67,205` 被骷髏射殺。
  - **問題**：`RETREAT` 設為 `cancelsTask=true`，會無差別取消 `EatTask` 與回庇護所的 `GotoTask`；且撤退僅用 `GoalRunAway(20)` 往反方向跑到 16 格外就結束(`finished`)，角色仍停在 `hp=4` 未回血，AI 一轉頭回家又立刻踏入 12 格觸發圈。
  - **方向**：`RETREAT` 不應打斷進行中的 `EatTask`(或在安全距離內優先進食回血再結束撤退)；考慮給撤退結束後短暫冷卻或優先往床/庇護所方向撤離，避免與回家路線互相拉扯。
  - **處理(2026-11-26)**：撤退改成暫停任務並分兩段——跑開(有箭要擋時停下舉盾)→ 16 格內沒有看得到自己的威脅時停下吃東西回血；血量 > 10 才結束並接著做任務。同一個任務第二次撤退(`retreated_twice`)或 10 秒回不了血(`cannot_heal`)才取消任務交給 AI，回不了血後 20 秒內不再觸發(除非又被打)。正在吃東西而且 3 格內沒怪時不觸發；撤退時限改為 60 秒。

- [x] **R4. `OwnershipLedger` pending 競態導致 Baritone 墊腳圓石被誤判為他人方塊並污染規劃**
  - **位置**：`src/main/java/blockagent/core/OwnershipLedger.java:54-67`、`src/main/java/blockagent/game/RuleGuard.java:140-151,403-411`、`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:132-147`
  - **證據**：`goto` 期間出現 4 群共 28 筆 `guard use_item_on_block -> RULE_PROTECTED_BLOCK <x,y,z> is occupied by someone else's cobblestone; cannot place`(`81,59–63,205`、`105,48–60,231`、`83,66,228`–`84,68,228`、`71,57–63,219`)；對照 `ownership.json`，這些圓石柱全部在 AI 自己的所有權帳本內。
  - **問題**：`expectPlacement()` 僅寫入 `pendingPlacements`，但 `isOwned()` 只查已獲伺服器確認的 `ownedBlocks`。Baritone 垂直墊腳長按右鍵時，第 N tick 客戶端預測放完圓石進入 `pending`，第 N+1 tick 伺服器更新尚未回傳就再次觸發 `useItemOn`，`canPlaceInto()` 見該格已是圓石且 `isOwned()==false`，誤報為他人方塊並寫進 `agent.noteProblem(...)`；結果後續 `gpt-6.2-sol` 規劃多次出現幻覺(「不重試在他人圓石柱旁施工」「不碰受保護的圓石」)。
  - **方向**：`RuleGuard.canPlaceInto()`(或 `OwnershipLedger`)對尚未逾期的同種 `pendingPlacements` 視為自己剛放置的方塊，避免誤觸守門員與污染 `recentProblems`。
  - **處理(2026-11-26)**：於 `OwnershipLedger` 新增 `isPendingSelf(...)`，並由 `RuleGuard.canPlaceInto()` 放行尚未逾期的同種 pending 放置(`isOwned` 維持嚴格已確認才算)。

- [x] **R5. Baritone 尋路任意拆毀自家完工房屋的屋頂與外牆(從屋頂下來偷拆、抄近路穿牆)**
  - **位置**：`src/main/java/blockagent/game/Bari.java:29`、`src/main/java/blockagent/game/RuleGuard.java:128-137`、`src/main/java/blockagent/mixin/CalculationContextMixin.java:19-28`、`src/main/java/blockagent/game/tasks/GotoTask.java`
  - **證據**：整份日誌沒有任何對應 `ai break_block`，但自家建物 3 處先後被尋路挖穿：
    - 第一間木屋東牆 `89,67–68,227`(`20:09:50` 從東南 `103,65,242` 呼叫 `goto 87,67,226` 回家，直接敲破東牆 2 格抄近路，而非繞至北門 `87,67,224`)。
    - 第一間木屋屋頂 `88,70,227`(`20:15:46` 補完屋頂後站在 `y=71` 呼叫 `goto 87,67,226` 進屋，直接往下挖穿屋頂跳進屋內，直到 `21:01:21` 巡檢才補回)。
    - 東側擴建南牆 `93,67–68,229`(`20:55:56` 完工後在進出期間被敲出 2 格人高破洞，於 `21:01:21` 補回)。
  - **問題**：`BuildTask.protects()` 僅在施工進行中生效；完工(或中止)後建築方塊只剩 `isOwned()==true`，而全域 `allowBreak=true` 且 `CalculationContextMixin` 只對 `!canBreak().allowed()` 給 `COST_INF`，Baritone 尋路將自家木板視為低成本可拆方塊，且尋路拆塊不寫進 `ActionLog`，AI 本身無從察覺。
  - **方向(待定案)**：分離「所有權」與「尋路可破壞性」——在 `CalculationContextMixin` 中，非主動拆除任務(`break_block` / `demolish` / `clear_area`)期間，對已存檔藍圖範圍內的方塊，或對所有非天然的自有建築方塊(木板、門、床、箱子、熔爐等)給予 `COST_INF` 或極高破壞懲罰(野外墊腳圓石可視情境保留高成本而非完全禁拆)。
  - **處理(2026-11-26)**：採方案 A，於 `RuleGuard.canPathBreak()`、`BuildTask.isClearing()` 與 `CalculationContextMixin` 對所有非天然自有方塊在尋路時一律回傳 `COST_INF`(進行中 `BuildTask` 正要清除的格子除外)；`break_block`/`demolish`/`clear_area` 不受影響。

---

## High(重要功能與穩定性問題)

- [x] **R6. `BuildTask` 施工期間 `permitsPlacement` 守門員洗版，導致提前中止並跳過裝門**
  - **位置**：`src/main/java/blockagent/game/tasks/BuildTask.java:298-306`、`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:117-122`、`src/main/java/blockagent/game/RuleGuard.java:403-411`
  - **證據**：第一次 `build_house`(`20:03:49–20:04:27`)在 24 秒內連噴 **101 筆** `guard use_item_on_block -> RULE_PROTECTED_BLOCK kept by the current build`(約每秒 4.2 次)，大量 `PROMPT` 級 `rule_blocked` 事件迫使 AI 在 `blocks=95/97` 主動呼叫 `stop`，留下 2 格屋頂缺口且完全跳過 `DoorPlacer` 裝門(最終北門與東牆破洞的門皆於暫停期間由人工補裝)。
  - **問題**：Baritone `BuilderProcess` 重複點擊已放好的方塊或嘗試在保留格墊腳遭 `canPlaceForBuild` 擋下時，每 tick 都呼叫 `reportBlocked()` 寫入 `PROMPT` 事件與 `noteProblem`。
  - **方向**：對 `BuildTask` 內部擋下 Baritone 施工重疊/非法墊腳的情況改為靜默拒絕或節流去重，避免洗版事件誤導 AI 中止建造。
  - **處理(2026-11-26)**：於 `RuleGuard` 新增 `reportBuildBlocked()`，維持 `blockedCount`/`lastBlocked` 供 `DoorPlacer.blockedAfter` 使用，但省略 `PROMPT` 事件與 `noteProblem`，且 `ActionLog` 每 100 tick 最多記 1 筆；`MultiPlayerGameModeMixin` 的 `canPlaceForBuild` 分支改呼此方法。

- [x] **R7. 重生無甲空手時 `SELF_DEFENSE` 仍對骷髏硬衝(`charge`)，並空手連揮 23–25 下**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:314-339,399-430`、`src/main/java/blockagent/game/Inv.java:54-61`
  - **證據**：`21:21:11`(剛重生無甲、正要合成石劍時)被 12 格外骷髏觸發 `self_defense`，選用 `skeleton: charge` 空手連揮 23 下後遭撤退搶佔，於 `21:21:32` 在 `69,67,218` 被射殺；`21:22:58` 再次對骷髏空手連打 24 下；`21:25:33` 對殭屍 7 秒內空手連揮 25 下。
  - **問題**：`Combat.ranged()` 僅依 `hp <= 8` 決定是否躲避，未評估自身是否無武器、無盾、無護甲；另 `Inv.selectInHand(mc, s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES))` 只依背包索引挑第一把劍或斧(不比傷害)，無武器時直接空手纏鬥(每拳 1 傷害)。
  - **方向**：無劍/斧/弓且無盾(或無護甲)時，面對遠程怪應直接採 `takeCover` 或撤離，不主動 `charge`；近戰選武器應依傷害挑最佳武器而非只看欄位順序。
  - **處理(2026-11-26)**：新增勝算規則 `CombatPolicy.worthFighting`(預估打死要幾秒 × 每秒掉血，打完仍在撤退線以上才打)：遠程怪依 `rangedStance` 選對射/舉盾推進/直衝/躲；近戰怪在自衛時勝算不夠就 `disengage_outmatched`。`Inv.bestWeapon` 依每秒傷害挑劍或斧。另加戰前評估(`danger`/`gear`/`missing`、`danger_rising`)、威脅出現時自動穿甲換盾、跳劈與衝刺擊退、站位檢查和 `fight_summary` 紀錄(SPEC S9.1b、S9.1c)。

- [x] **R8. `mine` / `goto` 陷入 `PathNode map size: 1` 鎖死，連續黑名單周圍全部目標**
  - **位置**：`src/main/java/blockagent/game/tasks/MineTask.java`、`src/main/java/blockagent/game/tasks/GotoTask.java`、`src/main/java/blockagent/game/reflex/Reflexes.java:489-533`
  - **證據**：`21:28:47` 掉落 5 格(`fall_start`)後，於 `21:29:13` 啟動 `mine oak_log count=43 radius=64 search=true`；`21:29:26`、`21:29:38` 兩次 `unstuck` 皆未脫困，隨後 `latest.log` 在 `21:30:36–21:30:38` 連續 10 次印出 `22 movements considered / Open set size: 0 / PathNode map size: 1` 並持續將最近原木加入黑名單。另全場 `mine` 成功率僅 24/30(80.0%)，其中 5 次為 `PATH_BLOCKED`。
  - **問題**：`PathNode map size: 1` 表示起點周圍 22 個鄰近移動全被禁止(例如落入他人建物或受保護方塊圍住處)，而 `UNSTUCK` 僅挖天然方塊，無法脫離時仍反覆觸發並讓 Baritone 不斷黑名單合法資源。
  - **方向**：偵測到連續無法起步(`PATH_BLOCKED` / `Open set size: 0`)時提早失敗回報，並附上當前座標與周圍不可拆方塊提示，讓 AI 改由其他手段脫困或求助。
  - **處理(2026-11-26)**：於 `MineTask` 新增 `TRAPPED_TICKS`(160 ticks)，採礦啟動中若持續無路徑、未離開原地且未挖到新方塊達 8 秒即提早以 `PATH_BLOCKED` 失敗並附上座標與周圍阻擋方塊摘要。

- [x] **R11. MC 26.3 副手拿盾牌導致 `till_and_plant` 與 `use_item`(鋤/斧/鏟)全面失效**
  - **位置**：`src/main/java/blockagent/game/tasks/FarmTask.java:114-120`、`src/main/java/blockagent/game/Tools.java:1244-1269`、MC 26.3 `net/minecraft/core/component/BlockTransformer.class`
  - **證據**：`20:45:19–20:45:31` 副手裝備盾牌(`offhand="shield"`)時，對上方為空氣的天然 `grass_block`(`85,65,231`、`85,65,232`、`86,65,231`)呼叫 `till_and_plant` 全報 `skipped=...:untillable`，單獨呼叫 `use_item(item="stone_hoe", x=85, y=65, z=232)` 也報 `ERR INVALID_ARGUMENT item=stone_hoe pos=85,65,232 block=grass_block | using the item had no effect`；使用者於 `20:45:37` 暫停後以同一把石鋤手動操作才成功鋤地。
  - **問題**：MC 26.3 將斧、鏟、鋤統一進 `BlockTransformer` 元件，且內含 `getOffhandItem()` 具 `BLOCKS_ATTACKS` 且 `!isSecondaryUseActive()` 時直接回傳 `InteractionResult.PASS`(讓位給舉盾)的判斷。生存模式 AI 幾乎全程持盾，`FarmTask` 與 `Tools.useItem` 未蹲下或暫卸盾牌，導致鋤地、削樹皮、鏟草徑等動作失效。
  - **方向**：使用具 `DataComponents.BLOCK_TRANSFORMER` 的工具且副手具 `BLOCKS_ATTACKS` 時，於互動前暫時蹲下(`isSecondaryUseActive`)或暫將副手盾牌移回背包後再還原。
  - **處理(2026-11-26)**：於 `Inv` 新增 `findOffhandShieldSwapSlot()` 並在 `Moves` 新增 `useOnWithShieldBypass()`，使用 `BLOCK_TRANSFORMER` 且副手具 `BLOCKS_ATTACKS` 時同 tick 暫換副手盾牌、互動後立即於 `finally` 換回；已接入 `FarmTask` 與 `Tools.useItem`。

- [x] **R12. 副手物品對 `craft`、`equip` 與 `Inv` 查詢完全隱形，引發連續假缺料**
  - **位置**：`src/main/java/blockagent/game/Inv.java:21,39-61,210-225`、`src/main/java/blockagent/game/Tools.java:1272-1295`
  - **證據**：`20:00:50–20:01:12` 開局剛砍完的 16 塊 `jungle_log` 位於副手(`hand=cocoa_beans:2 offhand=jungle_log:16`)，AI 連續 9 次呼叫 `craft` 全報 `ERR MISSING_MATERIALS missing="jungle_log|#jungle_logs:8"`，連呼叫 `equip(item="jungle_log", slot="mainhand")` 都回報 `ERR MISSING_MATERIALS item=jungle_log have=0 need=1`；最後 AI 靠 `equip(item="cocoa_beans", slot="offhand")` 把副手硬頂回主背包才解除卡關。
  - **問題**：`Inv.MAIN_SIZE=36` 僅掃描 0–35 格，`Inv.find()`、`Inv.count()`、`Inv.summary()`、`Inv.selectInHand()` 全部忽略副手槽(slot 40)。
  - **方向**：`equip(slot="mainhand")` 在主背包找不到時檢查副手並直接對調；`craft` 預檢/取料或 `Inv` 工具方法納入副手槽(或自動移回主背包再合成)。
  - **處理(2026-11-26)**：於 `Inv` 新增 `countWithOffhand()`、`summaryWithOffhand()` 與 `pullOffhandToMain()`；`Tools.craft` 改用 `summaryWithOffhand` 預檢，若材料在副手則開工前先換回主背包；`Tools.equip` 在主背包找不到時補查副手並對調。

---

## Medium(規劃服務與觀測紀錄)

- [x] **R9. Planner(`gpt-6.2-sol`)Prompt Cache 命中率為 0，且短間隔重複規劃互蓋**
  - **位置**：`bin/blockagent-planner`、`src/main/java/blockagent/game/Tools.java`(`request_plan`)
  - **證據**：`blockagent-planner-usage.jsonl` 紀錄本局 17 次規劃全數成功(0 timeout，平均 12.84 秒，9.78–15.81 秒)，總計 83,387 input tokens + 5,922 output tokens(平均 4,905 in / 348 out)，但全部 31 筆歷史紀錄的 `cached_input_tokens` 皆為 `0`。另出現兩組極短間隔呼叫：`20:57:04 → 20:57:51 → 20:58:10`(間隔 47 秒、19 秒，AI 1 秒連勾 4 步馬上再請規劃，隨後又因使用者改目標重請)與 `21:28:48 → 21:29:05`(間隔 17 秒，舊採礦計畫剛到就被新建屋計畫覆蓋)。
  - **方向**：檢查 `blockagent-planner` 送出的 prompt 前綴結構為何無法命中快取(確認固定指示在最前、動態狀態在後)；新 `request_plan` 進來時若已有進行中請求，取消舊請求避免浪費與覆蓋。
  - **處理(2026-11-26)**：先修正 Prompt Cache 問題(`BUSY` 單飛機制維持不動)：`bin/blockagent-planner` 改用固定的 `CODEX_HOME/planner-workdir` 以避免每次換隨機 `cwd` 切斷前綴，stdin JSON 改把 `game_snapshot` 放最前面；`Agent.planContext()` 改由靜態到動態排序(`[player_tools]` → `[memory]` → `[current_plan]` → `[recent_problems]` → `[self]`)。

- [x] **R10. Baritone 尋路破壞自有方塊未留日誌，難追查誤拆行為**
  - **位置**：`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:85-91`
  - **問題**：`ActionLog` 僅記錄 MCP 工具呼叫(`break_block`)，Baritone 尋路經 `destroyBlock` 拆掉自有非天然方塊時無任何紀錄，導致屋頂(`88,70,227`)、東牆(`89,67–68,227`)與南牆(`93,67–68,229`)被拆卻查無直接動作紀錄。
  - **方向**：除 R5 從尋路層根本防止外，可在 `RUNNING` 狀態下於 `destroyBlock` 拆掉非天然自有方塊且當前任務非 `BreakTask`/`BuildTask`/`ClearAreaTask` 時補記一筆 `ActionLog`，方便事後診斷。
  - **處理(2026-11-26)**：於 `MultiPlayerGameModeMixin` 的 `destroyBlock` 回傳成功處，若在運作中拆掉非天然方塊且非 `BreakTask`/`BuildTask`/`ClearAreaTask`，補記 `path_break` 至 `ActionLog`。

- [x] **R13. `find_blocks` 群組別名不一致與 `unknown block id` 缺引導，加上參數上限與錯誤訊息易誤導**
  - **位置**：`src/main/java/blockagent/game/Tools.java:200-258`(`parseItem`/`parseBlock`/`blockGroupTag`/`parseBlockGroup`)、`src/main/java/blockagent/game/Tools.java`(`inspect_area`/`mine`/`get_surroundings`/`useItem`)、`src/main/java/blockagent/core/Recipes.java`
  - **證據(Codex 對話紀錄)**：
    - `find_blocks(type="ladder,door,stairs")` → `ERR INVALID_ARGUMENT | unknown block id: stairs`(`blockGroupTag` 支援 `door`/`bed`/`plank`/`wool`/`leaves` 與 `log`/`chests`/`furnaces`/`*_ore`，卻不含 `stairs`/`slabs`/`fences`/`trapdoors`)。
    - `find_blocks(type="wheat_seeds,short_grass")` → `ERR INVALID_ARGUMENT | unknown block id: wheat_seeds`(把物品 ID 誤傳成方塊 ID，錯誤訊息未提示它是物品而非方塊)。
    - `inspect_area` 單局撞牆 **7 次** `ERR INVALID_ARGUMENT | limit must be between 1 and 32`(檢查 5×5 屋頂或整個房間時反覆傳 `limit=40–250`，另 1 次誤傳 `center`/`radius` 報 `missing required argument: from`)；另出現 `mine(radius=6)` 報 `radius must be between 8 and 96`、`get_surroundings(radius=64)` 報 `radius must be between 4 and 48`。
    - `craft(wooden_pickaxe)` 缺料輸出 `missing="oak_planks|#wooden_tool_materials:3,stick:2,oak_planks|#planks:4"`，同一個 `oak_planks` 出現兩次(未標示第二項是隱含子步驟工作台所需)。
    - `use_item(item="bucket", x=56, y=62, z=225)` 撈流動水(`water[level>0]`)只回 `block=water | using the item had no effect`，未顯示 `level` 或提示必須撈水源(`level=0`)。
  - **方向**：`blockGroupTag` 補齊常見建築/農作 tag(`stairs`/`slabs`/`fences`/`trapdoors`/`crops` 等)，`parseBlock` 發現傳入的是合法物品 ID 時於 hint 說明；評估放寬或自動夾限 `inspect_area.limit`(裁切並標記 `truncated` 而非直接失敗重呼)；`craft` 缺料合併或標註子步驟；空桶對流動水失敗時附註 `level` 與水源提示。
  - **處理(2026-11-26)**：`blockGroupTag` 補齊 `stairs`/`slabs`/`fences`/`trapdoors`/`crops`/`saplings`；`parseBlock` 對合法物品 ID 附註 `(this is an item id, not a block id)`；`inspect_area` 的 `limit` 上限放寬至 `128`(`AreaInspection.MAX_LIMIT`)；`Recipes.planWith()` 將工作台缺料標註 `(for_crafting_table)`；`Tools.useItem()` 空桶撈流動水失敗時附註 `level` 與水源提示。

- [x] **R14. `ActionLog` 漏記 `Args.BadArgument` 錯誤，遊戲端日誌看不到 ID 與參數失敗**
  - **位置**：`src/main/java/blockagent/mcp/ToolRegistry.java:102-109`
  - **證據**：上述 `unknown block id`、`limit must be between 1 and 32`、`radius must be between 8 and 96`、`missing required argument: from` 等錯誤全未記錄於 `blockagent/logs/*.jsonl`，只能從 Codex 端 rollout 紀錄查出。
  - **問題**：參數解析(`parseItem`/`parseBlock`/`Args`)在進入 `act(...)` 前拋出 `Args.BadArgument`，由 `ToolRegistry.call()` 直接轉成 `ERR INVALID_ARGUMENT` 回傳，未寫入 `ActionLog`。
  - **方向**：在 `ToolRegistry` 捕獲 `Args.BadArgument` 與 `INTERNAL` 例外時透過回呼補記一筆 `ActionLog`，讓遊戲端日誌完整保存所有工具呼叫失敗紀錄。
  - **處理(2026-11-26)**：於 `ToolRegistry` 新增 `setOnError` 回呼並在 `Args.BadArgument` 與 `INTERNAL` 例外時通知；`Agent` 建構子將錯誤寫入 `ActionLog`。
