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

---

## 2026-11-26 戰鬥重製後續檢查(弓箭與自然地形)

> 來源：2026-11-26 戰鬥重製審查(`f13c7ef`、`6cc37dc`)與 `NaturalCombatGameTest`(`seed=wild-158210`)實測日誌。
> 2026-11-27 回歸結果：`CombatGameTest` 的 29 個固定情境分批通過。`NaturalCombatGameTest` 在 `wild-158210` 與 `blockagent-combat-1` 各跑 4 個情境，合計無死亡或溺水搶佔。

- [x] **R15. `CombatPolicy.rangedStance` 有弓時直接跳過勝算與近戰貼身檢查**
  - **位置**：`src/main/java/blockagent/core/CombatPolicy.java:110-118`、`src/main/java/blockagent/game/combat/Combat.java:579-592`
  - **問題**：`if (me.bow() && los && (distance > 8 || stalled)) return RangedStance.SHOOT;` 排在 `worthFighting(...)` 之前；當角色殘血無甲被多隻射手圍攻，或身旁 4 格內已有近戰怪貼身時，仍會直接進入 `SHOOT` 原地 `halt()` 站樁拉弓，不會轉 `COVER` 脫身。
  - **方向**：`rangedStance` 應先判勝算(或至少檢查 `meleeNear == 0` 與血量／多方向曝露風險)，打不贏時優先 `COVER` 脫身而非站樁對射。
  - **處理(2026-11-27)**：對射先檢查安全彈道、近戰威脅和預估掉血，對射勝算不足時評估能否近戰，否則脫身。單元測試覆蓋只有弓的戰力、貼身怪和低血量被多位射手瞄準。

- [x] **R16. 拉弓途中目標跨進距離門檻即直接 `cancelDraw`，白費蓄力**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:345,465,545,579,960-993`
  - **證據**：`NaturalCombatGameTest` 實測日誌多次出現拉弓 0.5–0.8 秒後，目標走進 8 格(`bow_duel`)或 6 格(`bow_first`)門檻即收弓切換近戰，一箭未發。
  - **問題**：滿弓需拉 20 tick，距離判定沒有留遲滯(hysteresis)，也未在已經快拉滿時把箭放出去再換戰術，導致站樁近 1 秒卻整弓作廢。
  - **方向**：若已在拉弓中(`drawingBow(p)`)且距離仍在安全容許範圍(例如近戰怪 > 3.5 格、遠程怪 > 5 格)或已蓄力 ≥ 14 tick，應讓這箭放完(或立刻半蓄力放箭)再切近戰，避免空轉。
  - **處理(2026-11-27)**：起弓與收弓採不同距離門檻，距離仍安全時完成當前射擊。遊戲測試在蓄力途中讓目標跨進六格，驗證確實消耗一支箭。

- [x] **R17. `ShieldGuard` 與拉弓互搶，對射常永遠拉不滿 20 tick**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:305-318`、`src/main/java/blockagent/game/combat/ShieldGuard.java:130-156`
  - **證據**：`NaturalCombatGameTest` 密林實測中，`bow_duel` 剛拉 0.5 秒就被 `shield_vs_bow` 呼叫 `UseKey.cancelUse()` 歸零，等擋完箭從 0 重拉又遇下一輪射擊。
  - **問題**：舉盾無條件取消主手拉弓，未考量拉弓進度與來箭剩餘時間。
  - **方向**：若已拉弓接近滿弓(例如 ≥ 15 tick)且 `!guard.arrowImminent()`，允許先放完這箭再舉盾；或與 `ShieldGuard` 協同在剛擋完箭的空檔立即起拉。
  - **處理(2026-11-27)**：接近滿弓且來箭仍留有盾牌生效時間，可以先放箭再立即舉盾。遊戲測試確認同一場雙向對射既能出箭也能成功格擋。

- [x] **R18. 終界使者未避用弓、`expectedLoss` 未計入弓箭戰力，且尚未支援弩**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:465`、`src/main/java/blockagent/core/CombatPolicy.java:74-88`、`src/main/java/blockagent/game/combat/Bow.java:27-35`
  - **問題**：`melee()` 的 `bow_first` 只看 `target instanceof Enemy`，會對免疫箭矢的終界使者拉弓；`expectedLoss()` 只算近戰武器傷害，只帶弓箭沒好劍時會被誤判成空手戰力；`Bow` 目前也僅支援 `BowItem` 未含 `CrossbowItem`。
  - **方向**：`shoot` 前排除 `Enderman`；`expectedLoss` 在遠距有弓時納入弓箭 DPS；後續評估補上已裝填弩的支援。
  - **處理(2026-11-27)**：依弓箭輸出及拉弓期間暴露給射手的風險評估勝算，終界使者用近戰。支援發射已裝單支箭的弩，並沿用受保護實體的彈道檢查。

- [x] **R19. `fight_summary` 缺少射箭計數，且 `CombatGameTest` 尚無專測弓箭的情境**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:441,989`、`src/gametest/java/blockagent/testkit/CombatGameTest.java`
  - **證據**：自然地形實測用弓射死骷髏時，`fight_summary` 僅顯示 `swings=0 crits=0 result=killed`，看不出實際射了幾箭；`CombatGameTest` 也沒有驗證 `bow_duel`、`bow_creeper`、`bow_first` 或屋頂／樹葉下切近戰的專用情境。
  - **方向**：`Combat` 補記 `shots` 寫入 `fight_summary`，並在 `CombatGameTest` 新增弓箭對射、苦力怕遠距射殺與低天花板切近戰情境。
  - **處理(2026-11-27)**：戰鬥摘要記錄 `shots` 與同場累計 `kills`。固定遊戲測試涵蓋對射、遠射苦力怕、距離門檻變動，以及低簷遮住彈道時的近戰移動。

- [x] **R20. 自然地形深水坑追擊目標時易反覆觸發 `drowning` 搶佔**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:258-268,579-610`
  - **證據**：`NaturalCombatGameTest`(`seed=wild-158210`)密林情境中，最後一隻骷髏落入深水坑(`y=56`)，角色在 75 秒內反覆下水追擊並被 `drowning` 搶佔 3 次(雖 0 死亡且剩 12.5 HP，但大幅拉長交戰時間)。
  - **方向**：目標位於深水中時，有弓優先在岸上射擊；無弓且自身需要潛水才能追上時，縮短追擊時間或改為脫離，避免反覆下水與 `drowning` 循環。
  - **處理(2026-11-27)**：深水目標有射線時先從岸上射擊，無法有效射擊時自衛脫身、狩獵回報深水阻擋。一格深的水域仍可近戰。近距離遇苦力怕引爆時優先避開或舉盾，包含正在脫身的情況。
  - **驗證(2026-11-27)**：固定場景確認無弓留岸、有弓岸射與一格深水近戰。密林 `wild-158210` 在 `afa27ee` 對照跑觸發 2 次溺水，修正後觸發 0 次，以 13.7 HP 脫身；`blockagent-combat-1` 的雪地針葉林複測也無死亡或溺水。

---

## 2026-11-26 正式環境試玩審查(Prism Launcher)

> 日期：2026-11-26
> 來源：Prism Launcher `26.3` 實機試玩紀錄(`sp-Live_2026_11_25-94c75847`，`2026-11-26 19:42–20:50`，`blockagent 2026.11.26-1742` + `codex-mcp-client 0.172.0` + 規劃模型 `gpt-6.2-sol`，區網與 `Anne201624` 雙人生存)及對應 Codex 對話紀錄(`~/.blockagent/codex-home/sessions/2026/11/26/`)。
> 局況摘要：總計 67.8 分鐘(`RUNNING` 66.8 分、`PAUSED` 0.6 分)、遊戲端 698 筆紀錄、Codex 端 585 次 MCP 呼叫(14 次錯誤，2.4%)、背景任務完成率 137/155(88.4%，不含 AI 主動取消為 137/150=91.3%)、死亡 0 次、無遊戲內 Java 例外或崩潰、0 次 `RULE_PROTECTED_*` 誤擋、0 次 `path_break`；自衛戰 5/5 擊殺(對骷髏成功格擋 4 箭、暴擊 11/22)；規劃服務 11 次全數成功(平均 13.04 秒)。

### High(功能失效與嚴重體驗卡關)

- [x] **R21. `equip(slot="armor")` 無法替換身上已穿的護甲**
  - **位置**：`src/main/java/blockagent/game/Inv.java:400-405`、`src/main/java/blockagent/game/Tools.java:1317-1356`
  - **證據**：`20:25:28` AI 合成全套鑽石護甲後連續呼叫 4 次 `equip(item="diamond_*", slot="armor")`，全數回報 `ERR INVALID_ARGUMENT ... now=iron_* | item was not equipped`；清完背包於 `20:25:39` 重試頭盔依舊失敗，最後 AI 改用 `use_item("diamond_*")` 右鍵才成功換裝(`20:47:28` 穿金靴時亦直接改用 `use_item`)。
  - **問題**：`Inv.wear()` 使用 `ContainerInput.QUICK_MOVE`(Shift+左鍵)，Minecraft 原版僅在對應護甲欄為空時才會穿上；身上已穿舊護甲時只會在快捷列與主背包間移動物品，完全不會替換。
  - **方向**：當目標護甲欄非空時，改以正常容器點擊對調(`PICKUP` 拿起新護甲 → `PICKUP` 點目標護甲槽 5/6/7/8 對調 → `PICKUP` 將換下的舊護甲放回原格)。
  - **處理(2026-11-26)**：護甲欄有舊裝備時，透過容器點擊換穿新裝，舊裝回原格。空欄沿用快速移動。

- [x] **R22. `get_players` 缺少睡眠狀態且無隊友入睡事件，導致夜間多次卡關**
  - **位置**：`src/main/java/blockagent/game/Observation.java:475-520,539-600`
  - **證據**：在「等 `Anne201624` 先睡再睡」的約定下，本局 3 個夜晚全數卡住：`19:57:21` 與 `20:08:04` `get_players` 皆僅回報 `activity=idle`，最後兩度由使用者手動接手(`19:57:27`、`20:10:19`)去睡；`20:42:23` 仍只回 `activity=idle`，AI 邀睡後原地乾等 75 秒，直到 `20:44:22` 使用者提醒「他睡了啊」才上床。
  - **問題**：`Observation.activity()` 未檢查 `o.isSleeping()`，且 `Observation.tick()` 未在偵測到其他玩家入睡時發送喚醒事件。
  - **方向**：在 `get_players` / `activity()` 加入 `sleeping` 狀態(`o.isSleeping()`)，並於夜間偵測到追蹤範圍內玩家入睡時送出 `PROMPT` 級事件(如 `player_sleeping`)喚醒 `wait_events`。
  - **處理(2026-11-26)**：`get_players` 直接回報客戶端同步到的 `activity=sleeping`，每位追蹤玩家入睡時發送一次即時級 `player_sleeping`。

- [x] **R23. 切換維度未喚醒 `wait_events`，且地獄門冷卻處理易空等 60 秒**
  - **位置**：`src/main/java/blockagent/game/Observation.java:160-200,539-600`
  - **證據**：本局 4 次穿越地獄門有 3 次白等 30–60 秒：`20:28:54` 返抵主世界後 `wait_events` 未喚醒，空等 60 秒至 `20:29:52` `idle_reminder`；`20:47:47` 進入地獄後同樣未喚醒，直到 31 秒後因朋友離線系統訊息才醒；`20:48:30` AI 只走出門框 1 格`(30,49,20)` 並在 0.1 秒後立刻折返`(29,49,20)`，未清掉傳送門冷卻便原地空等 60 秒，重試於 `20:49:48` 傳回主世界後又空等 31 秒直至使用者中斷。
  - **問題**：`Observation.tick()` 未追蹤 `mc.level.dimension()` 變更並推送事件；`get_self` 亦未揭露傳送門冷卻狀態。
  - **方向**：維度切換時於 `Observation.tick()` 立刻發送 `PROMPT` 級 `dimension_changed` 事件(附新維度與座標)；評估於 `get_self` 標示 `portal_cooldown`，避免剛出門框立刻折返無效。
  - **處理(2026-11-26)**：維度變更推送即時級 `dimension_changed`，包含新舊維度、座標和冷卻。`get_self` 回報 `portal_cooldown` 剩餘 tick，冷卻期間提示留在門外。

- [x] **R24. `use_block` 對門等可切換方塊未回報開關狀態，導致誤將大門重開**
  - **位置**：`src/main/java/blockagent/game/Tools.java:1135-1146`
  - **證據**：`20:24:52` 返家時新屋東門`(102,64,74)` 原本已開啟，`20:24:56` 呼叫 `use_block` 僅回 `OK used block=oak_door now=oak_door`(未顯示 `open=true/false`)，AI 誤關大門後於 `20:25:03` 進屋想關門又按一次反而把門重新打開，在門外有豬布林的情況下大門敞開達 1 分半(至 `20:26:37` `inspect_area` 才發現 `open=true`)。
  - **問題**：`Tools.useBlock()` 僅以 `RuleGuard.shortId(...)` 回報方塊名稱，未附上開關類方塊的狀態屬性。
  - **方向**：對具 `BlockStateProperties.OPEN` / `POWERED` / `LIT` 等互動狀態的方塊，在 `use_block` 回傳結果中附上操作後狀態(如 `open=false`)。
  - **處理(2026-11-26)**：`use_block` 回報適用的 `open`、`powered`、`lit`。尚待伺服器更新的按鈕或拉桿先等狀態更新，逾時標記 `state_pending=true`。

- [ ] **R25. 屋內積水/站於床格上導致 `goto` 陷入 `PathNode map size: 1` 鎖死且缺診斷資訊**
  - **位置**：`src/main/java/blockagent/game/tasks/GotoTask.java:89-105`、`src/main/java/blockagent/mixin/CalculationContextMixin.java:19-28`
  - **證據**：新屋內`(101,65,73)` 出現水源積水(AI 本身水桶全程未倒出，旁有 `owner=other` 牆火把 `(101,66,72)`)，AI 在 `20:24:59`(`98,64,76` 水流中)、`20:26:24` 與 `20:26:31`(`97,64,76` 棕色床頭格、旁有 `water[level=5]` 與自有木板牆)連續 3 次 `goto` 均於 `latest.log:471-498` 印出 `22 movements considered / Open set size: 0 / PathNode map size: 1`，在 5×5 屋內完全無法移動，最後由使用者於 `20:26:35` 暫停清掉水源。
  - **問題**：在自有不可拆方塊包圍的室內站於床格/淺水流中時，Baritone 無法產生任何起步節點；且 `GotoTask` 失敗僅回傳通用訊息 `no path to ... (blocked, too far, or chunks not loaded)`，缺乏如 R8 `MineTask` 的周圍阻擋物診斷。
  - **方向**：釐清 Baritone 在床格/室內水流搭配 `canPathBreak` 產生 `PathNode map size: 1` 的成因並修正起步限制；`GotoTask` 失敗時補上當前座標與腳下/周圍阻擋方塊摘要。
  - **決定(2026-11-26)**：屋內積水與床格案例依使用者指定略過。
  - **室外驗證與處理(2026-11-26)**：角色站在露天的一格深水流，或站在四周皆為水流的單一水源，均可重現 `PathNode map size: 1`。`goto` 正常尋路失敗時，從仍可踩地的室外淺水找最多 8 步的上岸路線，落地後重新尋路到原目標。遊戲測試驗證同高度的岸、高一格的岸、中止後放開移動鍵，以及有屋頂時沿用原本行為。

- [x] **R33. 豬布林(`Piglin`)一律歸為 `NEUTRAL` 且受限 6 格距離，遠程開弩或無金甲遇襲皆無自衛反應**
  - **位置**：`src/main/java/blockagent/game/combat/Threats.java:71-76`、`src/main/java/blockagent/game/combat/Combat.java:167-180`、`src/main/java/blockagent/game/combat/ShieldGuard.java:137-148`
  - **證據**：`20:24:52` 角色身穿全套鐵甲(無金甲)，門口 `piglin id=10929` 距離 7 格；`20:24:56` 遭該豬布林擊中(`[URGENT] attacked: by=piglin id=10929 lost=2 hp=18`)，全程未觸發 `self_defense`，也未舉盾或反擊。
  - **問題**：`Threats.kind()` 將 `AbstractPiglin` 排在 `if (ranged(e))` 之前並一律歸類為 `Kind.NEUTRAL`，而 `Combat.pickThreat()` 對 `NEUTRAL` 限制 `d <= 6`，導致 7–16 格開弩射擊或追打的豬布林即使已打傷玩家(`hurtMe=true`)仍不觸發自衛；就算進入 6 格內也走 `melee()` 而非 `ranged()`，`ShieldGuard.canSafelyStop()` 亦未將其視為遠程威脅，且未依玩家是否穿戴金甲區分敵意。
  - **方向**：依玩家是否穿戴金甲區分一般豬布林是否中立；敵對或已攻擊玩家的拿弩豬布林應視為 `RANGED`(16 格內觸發、舉盾與 `rangedStance` 應對)，近戰豬布林在 `hurtMe` 時比照 `MELEE` 套用 `MELEE_RANGE`(8 格)。
  - **處理(2026-11-27)**：依金甲與敵意區分普通豬布林。敵對弩手在遠程範圍內觸發自衛及舉盾，近戰型依近戰距離處理。遊戲測試驗證金甲中立、換下金甲後在六格外觸發自衛與舉盾。

- [x] **R34. 地獄/終界仍推送主世界日夜與 `can_sleep` 事件，且 `sleep` 未封鎖會引爆床的維度**
  - **位置**：`src/main/java/blockagent/game/Observation.java:122-124,143,572-601`、`src/main/java/blockagent/game/Tools.java:1375-1402`
  - **證據**：`20:28:36`、`20:48:21`、`20:49:40` 位於地獄(`dim=the_nether`)時，`get_surroundings` 與 `get_self` 仍顯示 `time=night day_in=83s` / `time=day dusk_in=6min weather=clear`。
  - **問題**：`Observation.tick()` 未檢查維度是否為主世界，在地獄或終界待到主世界黃昏會照常推送 `can_sleep`(提示「可以上床睡覺」)與 `night_falling`/`daybreak`；`Tools.sleep()` 亦未擋下非主世界維度，一旦於地獄/終界嘗試 `sleep` 會引爆床炸死角色。
  - **方向**：僅在主世界(或 `bedWorks()` 為 true 的維度)發送日夜/可睡事件與顯示主世界天色；`Tools.sleep()` 在不可睡床的維度直接回傳錯誤並說明原因。
  - **處理(2026-11-26)**：非主世界省略主世界日夜與可睡事件。`sleep` 及實際互動前依原版床頭位置的床規則檢查，拒用會爆炸或不可睡的床。

- [x] **R35. Baritone 預設 `allowWaterBucketFall=true` 主動跳深坑並與 `FALL` 反射互打，且 `FALL` 未排除地獄蒸發**
  - **位置**：`src/main/java/blockagent/game/Bari.java:16-40`、`src/main/java/blockagent/game/reflex/Reflexes.java:59,296-300,534-556`
  - **證據**：`20:37:41` `goto` 下樓跨落 4 格、`20:45:13` `mine(gold_ore)` 途中 Baritone 從 `y=34` 直接跳下 `y=24`(落差 10 格)的全黑洞穴(`light=0`，隨即觸發 `danger_rising: dark,hostiles:3`)；兩次 `FALL` 反射各只花 0.26 秒與 0.60 秒便安全水桶落地並舀回水，卻因 `FALL("survival", true)` 設為 `cancelsTask=true` 直接強制取消任務(`INTERRUPTED`)。
  - **問題**：`Bari.java` 未關閉 Baritone 預設的 `allowWaterBucketFall=true`，導致身上帶水桶時尋路主動規劃跳深洞，起跳後又立刻被 `FALL` 反射接管並中止任務；另 `Reflexes.FALL` 未檢查維度是否禁水(地獄倒水會瞬間蒸發並卡等 2 秒嘗試舀回)。
  - **方向**：評估於 `Bari.applySettings()` 關閉 `allowWaterBucketFall`(避免尋路主動規劃高落差跳躍)；`Reflexes.FALL` 在不可放水的維度(地獄)不觸發倒水，並評估安全落地後改為暫停恢復而非一律取消任務。
  - **處理(2026-11-26)**：關閉 Baritone 主動規劃水桶跳落。在水會蒸發的位置不執行水桶反射。正常落地後恢復任務，確認不了落地才回報中斷。

### Medium(規劃快取、採礦與跟隨體驗)

- [ ] **R26. Planner(`gpt-6.2-sol`)Prompt Cache 命中率仍為 0%(R9 修正未生效)，且過時計畫會覆蓋新進度**
  - **位置**：`bin/blockagent-planner:158-203`、`src/main/java/blockagent/game/Agent.java:394-440`
  - **證據**：本局 11 次規劃總計 62,632 input tokens(其中 4 組相鄰間隔僅 81–103 秒)，但 `blockagent-planner-usage.jsonl` 顯示 11 次 `cached_input_tokens` 全數為 `0`(對比主遊戲 Codex 對話串快取命中率達 97.6%)。另 `20:28:31` 與 `20:37:50` 兩次 `plan_ready` 到達時 AI 已在 11–13 秒等待期間切換維度或移動到岸邊，新計畫第 1 步已過時卻仍覆蓋目前步驟。
  - **問題**：雖已固定 `planner-workdir` 與輸入順序，但每次仍啟動全新 `codex exec --ephemeral`，每次產生新的 session/thread UUID(作為 `prompt_cache_key`)，導致無法跨次命中快取；且 `acceptPlan` 未比對快照與當前狀態差異。
  - **方向**：評估讓規劃服務重用長駐 session/固定 `prompt_cache_key`(或確認 Codex CLI 支援參數)以實際啟用 Prompt Cache；檢查非同步規劃抵達時若維度或情境已變，標註快照時間差或提醒 AI 跳過已完成步驟。
  - **部分處理(2026-11-26)**：跨維度、離開快照位置超過 8 格或已有計畫進展時，送出 `plan_stale` 建議並保留現在的計畫。不同世界的結果直接拒絕。另用本機假模型驗證 Codex CLI 0.172.0 兩次相同規劃請求送出不同 `prompt_cache_key`。快取命中率及跨請求重用仍待驗證。

- [x] **R27. `mine` 遇埋藏岩層內礦脈被 Baritone 回報 `No locations known` 時，誤報為他人方塊擋路**
  - **位置**：`src/main/java/blockagent/game/tasks/MineTask.java:168-179`
  - **證據**：`20:18:14`、`20:18:31` 在地下 `y=-12` 想採 4 格外的 4 塊 `deepslate_diamond_ore`(周圍全為天然深板岩與礫石，`air=0`)，Baritone 1 秒內印出 `No locations for diamond_ore known, cancelling`(`latest.log:426-427`)，`MineTask` 卻回報 `PATH_BLOCKED ... (terrain or others' blocks in the way; the rules forbid breaking those)`；AI 改以 `break_block` 挖露出一格後，`20:19:01` `mine` 隨即成功。
  - **問題**：`MineTask` 在 `!isActive()` 時只要 `Moves.scan` 找得到目標方塊，就一律套用暗示「他人方塊擋路」的錯誤文字，未區分實際是否有受保護方塊阻擋或僅為 Baritone 未掃描/黑名單。
  - **方向**：區分「周圍存在不可破壞方塊」與「目標埋在岩層中/未被 Baritone 掃描到」，回報準確原因與下一步建議(如先 `break_block` 挖露或改用 `goto` 靠近)。
  - **處理(2026-11-26)**：分別回報目標受規則保護、找不到目標或有礦但路徑不可達。不可達時附上礦脈座標、露出狀態、受保護面數和周圍方塊，依現況提示靠近或挖露。

- [x] **R28. `till_and_plant` 與 `harvest` 不支援西瓜/南瓜，與規劃步驟脫節**
  - **位置**：`src/main/java/blockagent/game/tasks/FarmTask.java:32-35`、`src/main/java/blockagent/game/Tools.java:933-967`
  - **證據**：`19:58:19` Planner 明確指示用 `till_and_plant` 種植西瓜種子，但 `FarmTask.CROP_OF` 僅列小麥/胡蘿蔔/馬鈴薯/甜菜根，AI 於 `19:58:43` 自行發現工具不支援，改以手動 `use_item` 鋤地、播種並清落葉。
  - **方向**：評估讓 `till_and_plant` 支援 `melon_seeds`/`pumpkin_seeds`(或於工具說明/規劃提示詞明確標示支援作物清單)，並補上對應果實採收說明。
  - **處理(2026-11-26)**：播種支援西瓜與南瓜。採收只收與自有藤蔓相連的果實，藤蔓掛果及恢復時保留所有權。

- [x] **R29. `follow` 目標在無法抵達樓層時持續重算尋路並連觸發 `unstuck`，耗滿逾時仍報完成**
  - **位置**：`src/main/java/blockagent/game/tasks/FollowTask.java:54-80`
  - **證據**：`20:36:37–20:37:11` 呼叫 `follow(Anne201624, distance=2, max_sec=30)` 欲送魚給樓上(`y=73`)的朋友，角色卡在樓下(`y=70`)無法上樓；`FollowTask` 每 10 tick 不斷重送同一目標，使 Baritone 連續 10 次跑完約 660 萬 movements 的高耗時搜索(`latest.log:585-620`)並觸發 2 次 `unstuck`，拖滿 30 秒後仍回 `task_done`(`followed=30/30s dist=3`)。
  - **問題**：`FollowTask` 未偵測對同一無法抵達座標連續尋路失敗或反覆觸發 `unstuck`，導致白耗 CPU 與等待時間。
  - **方向**：對未移動的目標連續尋路無路可走或連續觸發 `unstuck` 達上限時，提早以 `PATH_BLOCKED` 結束並回報雙方座標與高度差。
  - **處理(2026-11-26)**：到達判斷納入高度差。同一帶反覆尋路失敗或原地無進展時，提早以 `PATH_BLOCKED` 回報雙方座標和高度差。

- [x] **R36. `craft` 只挑直線最近工作台，夜間帶 AI 走出安全屋去用屋外別人的工作台**
  - **位置**：`src/main/java/blockagent/game/Tools.java:812-813`
  - **證據**：`19:43:14` 夜間屋外 `danger=high`(3 隻敵怪)，AI 原本待在照明良好的舊屋內`(87,67,226)` 且背包剛做好一張 `crafting_table`，接著呼叫 `craft(chest)` 與 `craft(furnace)` 時，`Tools.craft` 掃到屋外 6 格別人放的工作台`(91,66,230 owner=other)`，直接開門走進黑夜怪堆合成(`19:43:22` AI 回報「合成時走到了屋外，我先回到舊屋避開附近怪物」)。
  - **問題**：不同於 `SmeltTask` 會優先挑自有設施，`Tools.craft` 僅以 `Moves.scan(..., 16, 1, null)` 取直線最近的一張，不區分自有/他人、室內/室外或夜間高危險環境。
  - **方向**：比照 `SmeltTask` 優先選自有且不需離開安全區域的近處工作台，或在夜間/高危險且背包已有工作台時優先就近放置使用。
  - **處理(2026-11-26)**：合成優先選有清楚視線且伸手可及的工作台，低或中危險時再考慮附近自有或他人工作台，高危險時優先在原地放置。

- [x] **R37. `find_blocks` 缺少高度範圍(`min_y`/`max_y`)篩選，迫使 AI 連刷 27 次 `inspect_area` 切片掃地表**
  - **位置**：`src/main/java/blockagent/game/Tools.java:395-401,1112-1133`
  - **證據**：`20:06–20:09` 尋找地表熔岩池時，`find_blocks(type="lava", radius=96, max=20)` 結果全被地底 `y=17–27` 佔滿；因無法限定高度，AI 在 3 分鐘內連續呼叫 27 次 `inspect_area` 手動切 `64×8×64` 方盒掃描(全場 70 次居冠，且撞上 `area is larger than 32768 blocks` 與 `max must be between 1 and 50`)，加速對話 context 膨脹(67 分鐘內觸發 3 次 compaction)。
  - **方向**：於 `find_blocks` 新增可選的 `min_y`/`max_y` 高度篩選參數(必要時支援 `source_only` 過濾流體來源)，一次即可精準定位地表或目標礦層。
  - **處理(2026-11-26)**：`find_blocks` 支援 `min_y`、`max_y` 與 `source_only`，先篩選再套用每種方塊的 `max`。

### Low(日誌與細節修正)

- [x] **R30. `Tools.smelt` 啟動回傳之 `furnace=` 與 `SmeltTask` 實際優先選用的自有熔爐不一致**
  - **位置**：`src/main/java/blockagent/game/Tools.java:1452-1458`、`src/main/java/blockagent/game/tasks/SmeltTask.java:89-91`
  - **證據**：`20:36:10` 在岸邊`(122,63,73)` 呼叫 `smelt(cod, count=2)`，`Tools.smelt` 以 `Moves.scan(..., 32, 1, null)` 抓直線最近者而回報 `furnace=110,74,74`(朋友家樓上)，但 `SmeltTask` 內部會優先挑選自有熔爐(`isOwned`)，實際走回自家熔爐`(99,64,72)` 完成。
  - **方向**：抽出共用的熔爐挑選邏輯(優先自有熔爐)，讓預檢回傳與實際執行一致。
  - **處理(2026-11-26)**：預檢與任務共用熔爐選擇，啟動回應列出的自有熔爐就是實際優先嘗試的位置。

- [x] **R31. `CraftTask` 於 `task_start` 日誌將合成前既有庫存算進 `made=`**
  - **位置**：`src/main/java/blockagent/game/tasks/TaskRunner.java:89-91`、`src/main/java/blockagent/game/tasks/CraftTask.java:79-96`
  - **證據**：`20261126-194237.jsonl` 多次於 `task_start` 印出 `craft item=torch made=14/8 step=0/1`、`made=17/16`、`made=8/4`。
  - **問題**：`TaskRunner.start()` 在呼叫 `t.start(mc)` 之前即先記錄 `t.progress()`，此時 `CraftTask.startTargetCount` 尚未初始化(為 `0`)，導致 `Inv.count - startTargetCount` 誤計為既有數量。
  - **方向**：改於 `t.start(mc)` 完成初始化後(或在建構子/進入前先初始化基準值)再寫入 `task_start` 進度。
  - **處理(2026-11-26)**：`task_start` 在任務初始化後記錄，合成進度以起始庫存為基準。

- [x] **R32. 混戰收尾 13ms 後立刻重啟 `self_defense`，且對非強制工具方塊缺工具提醒**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:256-275`、`src/main/java/blockagent/game/tasks/MineTask.java:120-130`
  - **證據**：`19:54:26.632` 混戰擊殺第一隻骷髏與蜘蛛後結束自衛反射，僅 13ms 後(`19:54:26.645`)即對 5.8 格外的第二隻骷髏再次觸發 `self_defense_start`；另 `19:45:27` AI 在背包無斧頭時直接徒手 `mine(jungle_log)`，直至 `19:46:06` 使用者提醒「採集工具呢」才中止改做石斧。
  - **方向**：`Combat.finished()` 在目標陣亡時檢查交戰範圍內是否已有其他立即威脅，若有則直接轉目標避免反覆進出反射；`MineTask` 開始採集原木/泥土等有加速工具的方塊時，若無對應工具可於回傳附上 `warn="no axe/shovel in inventory"` 提醒 AI。

  - **部分處理(2026-11-26)**：缺斧或鏟時在 `mine` 啟動回應附提醒。
  - **處理(2026-11-27)**：擊殺後若已有立即威脅，在同一次自衛中更換目標。固定混戰只觸發一次自衛，摘要累計兩次擊殺。
