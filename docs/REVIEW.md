# BlockAgent 專案審查紀錄

> 日期：2026-11-22
> 審查基準：`main` @ `0ae82a0`
> 範圍：全專案原始碼(`src/main`、`src/test`、`src/gametest`、`bin/`、`prompts/`、`build.gradle`)，並對照 `docs/REQUIREMENTS.md`、`docs/SPEC.md` 及本機快取之 `minecraft-merged-deobf-26.3.jar`、`baritone-unoptimized-fabric-1.20.0.jar` 與 `build/libs/blockagent-*.jar` 位元碼。
> 執行驗證：Python 規劃服務單元測試(Homebrew Python 3.15，4/4 通過)；未重跑 Gradle Java 測試與實機 Minecraft GameTest。

---

## 審查角度

1. **安全邊界與最後防線**：`RuleGuard`、`MultiPlayerGameModeMixin`、`ClientPacketListenerMixin`、私訊過濾與雙 CLI 沙箱是否可被繞過。
2. **執行緒模型與生命週期**：MCP HTTP 執行緒、Minecraft client thread 與 Baritone 尋路背景執行緒的邊界；進入世界、暫停/接手、死亡重生、切維度、斷線/重連與關閉流程。
3. **狀態機與邊界條件**：任務暫停/恢復、反射優先順序與震盪、Baritone 全域設定還原、原版方塊/物品/容器機制假設。
4. **規格與實作一致性**：對照 `REQUIREMENTS.md`、`SPEC.md` 與 MC 26.3/Baritone 1.20.0 實際位元碼。
5. **效能、穩定性與容錯**：client thread 掃描上限、熱路徑配置、封包暴衝、檔案原子寫入、設定檔容錯。
6. **測試品質與打包衛生**：測試覆蓋缺口、測試本身盲點、正式 jar 測試程式碼隔離。

---

## Critical(最優先)

- [x] **C1. 潛行放方塊繞過 `RuleGuard.canPlaceInto`**
  - **位置**：`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:105-117`(`blockagent$useItemOnHead`)
  - **問題**：第 105 行在 `player.isSecondaryUseActive()`(潛行)時設 `placing = true`，但第 116 行寫成 `if (placing && !player.isSecondaryUseActive())`，導致潛行放置方塊時完全跳過 `canPlaceInto`。
  - **情境**：潛行拿半磚、蠟燭、海鞘、海龜蛋等對別人的可疊加/可替換人造方塊按右鍵時，守門員不攔截，可直接改寫或覆蓋。
  - **方向**：移除第 116 行的 `&& !player.isSecondaryUseActive()`。
  - **處理(2026-11-23，線 1)**：移除潛行例外，蹲下放置一樣經過 `canPlaceInto`。RulesGameTest 驗證蹲下拿半磚點朋友的半磚不會疊成雙層。

- [x] **C2. `OwnershipLedger` 未分維度：跨維度破壞繞過與主世界所有權遺失**
  - **位置**：`src/main/java/blockagent/core/OwnershipLedger.java:20-26`、`src/main/java/blockagent/game/RuleGuard.java:70-81`、`src/main/java/blockagent/game/OwnershipTracker.java:32-121`、`src/main/java/blockagent/game/Agent.java:47,175`
  - **問題**：帳本與 `selfCaused` 僅用打包後的 `(x, y, z)` 當鍵，沒有維度鍵(`minecraft:overworld` / `minecraft:the_nether` / `minecraft:the_end`)。
  - **情境**：在主世界 `(10, 64, 10)` 蓋過方塊，到地獄同座標遇到別人蓋的建築時 `canBreak` 會誤放行；反過來在地獄同座標挖天然地獄岩或收到方塊更新時，會刪掉主世界同座標的所有權。
  - **方向**：`OwnershipLedger` 以 `(dimension, pos)` 或每個維度一張表儲存(並相容舊版 `ownership.json` 視為 `minecraft:overworld`)。
  - **處理(2026-11-23，線 1)**：`OwnershipLedger` 改成每個維度一張表(鍵是 `minecraft:overworld` 這類維度 id)，`OwnershipTracker.selfCaused` 也帶維度；`RuleGuard` 依呼叫端的 `Level`(Baritone 尋路用目前所在維度)查帳本。新檔格式是 `{"dims": {...}}`，舊版 `{"blocks": [...]}` 讀進來一律算主世界。CoreLogicTest 補分維度、存讀檔、舊檔相容。

- [x] **C3. `set_reflex` 在 MCP HTTP 執行緒直接操作 Minecraft 與 Baritone**
  - **位置**：`src/main/java/blockagent/game/Tools.java:414-427`、`src/main/java/blockagent/game/reflex/Reflexes.java:114-119`
  - **問題**：`set_reflex` 未透過 `ClientThread.call(...)` 派回 client thread。關閉正在執行的反射時，`Reflexes.set()` 直接在 `blockagent-mcp-*` 執行緒呼叫 `end()`，操作 `mc.options.keyJump`、`UseKey.release`(`mc.gameMode.releaseUsingItem`)、`Bari.stopAll()` 與 `agent.tasks.resume()`。
  - **方向**：將 `set_reflex` 改為在 `ClientThread.call(...)` 中執行(並視規格決定暫停/結束狀態下的行為)。
  - **處理(2026-11-23，線 2a)**：`set_reflex` 改在 `ClientThread.call` 裡呼叫 `Reflexes.set`，暫停時也照樣能開關。

- [x] **C4. `ON_FIRE` 優先度高於 `LAVA_AVOID`，掉進岩漿時 `LAVA_AVOID` 無法執行**
  - **位置**：`src/main/java/blockagent/game/reflex/Reflexes.java:51-53,152-166,210-216,285-293`
  - **問題**：`Kind.ON_FIRE`(ordinal 1)排在 `Kind.LAVA_AVOID`(ordinal 2)前面。碰到岩漿立刻 `isOnFire() == true`，因此 `ON_FIRE` 永遠優先(甚至會搶佔執行中的 `LAVA_AVOID`)。附近 8 格沒水時 `ON_FIRE` 只跑一次 `GoalRunAway(5, 腳下單點)` 且 `act()` 無動作，角色會留在岩漿裡燒死。
  - **方向**：調整順序讓 `LAVA_AVOID` 優先於 `ON_FIRE`(或 `ON_FIRE` 在 `p.isInLava()` 時不觸發/不搶佔)。
  - **處理(2026-11-23，線 2a)**：`LAVA_AVOID` 排到 `ON_FIRE` 前面，`ON_FIRE` 在岩漿裡也不觸發。`set_reflex` 的列舉順序跟著變，快照已更新。

- [x] **C5. `EAT` 反射在背包有高營養壞食物時陷入每 2 tick 無限震盪**
  - **位置**：`src/main/java/blockagent/game/reflex/Reflexes.java:232-235,248-262,299-305,359-363`
  - **問題**：`begin()` 呼叫 `bestFood(p, foodLevel)`(`food > 6` 時跳過 `BAD_FOOD`)記錄 `eatStartCount`，但 `finished()` 第 360 行呼叫 `bestFood(p, 0)`(不跳過 `BAD_FOOD`)。
  - **情境**：飢餓 7–14、身上有 1 個腐肉(營養 4)+ 10 根胡蘿蔔(營養 3)，或 1 碗謎之燉菜(6)+ 5 塊麵包(5)：第 1 tick 吃胡蘿蔔，下 1 tick `finished()` 查腐肉數量 `1 < 10` 誤判已吃完並中止，下一 tick 再重觸發，每秒反覆暫停/恢復任務約 10 次且吃不到東西。
  - **方向**：在 `begin()` 記下實際選中的 `Item eatingFood`，`finished()` 只比對 `eatingFood` 的數量。
  - **處理(2026-11-23，線 2a)**：`begin()` 記下實際在吃的 `eatingFood`，`finished()` 只看它的數量。SessionGameTest 驗證腐肉加胡蘿蔔、飢餓度 10 時吃胡蘿蔔，不會反覆開始又中止。

- [x] **C6. `SmeltTask` 斷料偵測看錯槽位(檢查原料格而非燃料格)**
  - **位置**：`src/main/java/blockagent/game/tasks/SmeltTask.java:185-187`
  - **問題**：`if (m.getSlot(0).getItem().isEmpty() && out.isEmpty() && !m.isLit() && waitTicks++ > 40)` 檢查的是 slot `0`(原料格)，但燃料燒完時未燒完的原料仍留在 slot `0`，條件永遠為 `false`，導致任務卡到 10 分鐘逾時。
  - **方向**：條件應檢查燃料耗盡(`m.getSlot(1).getItem().isEmpty() && !m.isLit() && out.isEmpty()`，或原料格已空且未點燃且產物格已空)。
  - **處理(2026-11-23，線 2b)**：熄火、產物格空了，而且燃料格空了或原料格空了才算停下，條件不成立時計數歸零。SessionGameTest 驗證。

- [x] **C7. `MenuOps.placeInto()` 未檢查目標格實際增加量，導致非 64 堆疊合成與超量燃料熔煉靜默出錯**
  - **位置**：`src/main/java/blockagent/game/tasks/MenuOps.java:57-84`、`src/main/java/blockagent/game/tasks/CraftTask.java:167-169,205,231-236`、`src/main/java/blockagent/game/tasks/SmeltTask.java:135,158-162`
  - **問題**：`placeInto()` 假設每次右鍵都成功而直接 `placed += k`。
  - **情境**：
    - `CraftTask` 的 `batch = Math.min(craftsLeft, 64)` 未受材料 `maxStackSize` 限制；合成多個發射器(需弓，堆疊上限 1)或 20 個雪塊(需雪球，上限 16)時，多餘點擊彈回卻仍扣完 `craftsLeft` 回報成功。`QUICK_MOVE` 背包滿時同理少做卻回報完成。
    - `SmeltTask` 燒 40 鐵礦用木棒(需 80 > 64)或木鏟(上限 1)時，空燃料格會跳過 `CHECK` 堆疊檢查，實際只放進上限數量卻回傳放滿，後續搭配 C6 卡死。
  - **方向**：`placeInto()` 依目標格實際變化量計算 `placed`；`CraftTask` 的 `batch` 限制為所有材料 `maxStackSize` 與剩餘背包容量的最小值；`SmeltTask` 支援分批補燃料或於預檢限制單組上限。
  - **處理(2026-11-23，線 2b)**：`placeInto` 照目標格實際增加的數量算，事先用 `Slot.getMaxStackSize`/`mayPlace` 算放得下多少；`CraftTask` 每批受材料堆疊上限限制，拿產物時照背包實際多出來的數量算，背包滿了就照實失敗；`SmeltTask` 燃料分批補，`CHECK` 只要求第一批放得下。SessionGameTest 驗證雪塊 20 個(雪球一疊 16)、木鏟兩次補燃料。

---

## High(重要安全、功能與穩定性問題)

### 安全防線與所有權

- [x] **H1. 水桶倒水可含水/澆熄別人方塊，撈水射線與原版不一致**
  - **位置**：`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:167-182`
  - **問題**：倒水只檢查 `st.canBeReplaced() ? at : at.relative(hit.getDirection())`，忽略 `LiquidBlockContainer`(樓梯、半磚、箱子、鐵欄杆、點燃營火等會把水倒進 `at` 本身)；撈水使用 `player.pick(..., true)`(`Fluid.ANY`)，與原版 `BucketItem` 的 `ClipContext.Fluid.SOURCE_ONLY` 不一致。
  - **處理(2026-11-23，線 1)**：落點照原版 `BucketItem.use`：空桶用 `SOURCE_ONLY` 視線；水倒進 `LiquidBlockContainer` 時被點的那格和旁邊那格都檢查。

- [x] **H2. `RuleGuard.toolWouldChange` 保護缺口(含鋤頭劫持別人草徑所有權)**
  - **位置**：`src/main/java/blockagent/game/RuleGuard.java:207-222`、`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:118-127,223-225`
  - **問題**：`dirt_path` 不在 `natural_blocks.json` 中(屬人造方塊)，但 `toolWouldChange` 排除鋤頭，導致對別人 `dirt_path` 用鋤頭能轉成耕地，且 `useItemOnReturn` 會將該格標記為自己所有(之後連拆都能拆)。另缺：`HONEYCOMB`(上蠟/鎖定告示牌)、`SHEARS`(蜂箱)、染料/墨囊(告示牌)、打火石點燃鄰近別人木頭/羊毛方塊的保護。
  - **處理(2026-11-23，線 1)**：MC 26.3 的斧頭、鏟子、鋤頭改成 `BLOCK_TRANSFORMER` 元件，`toolWouldChange` 改成有這個元件就當成會改方塊(別人的草徑不能鋤)；補上蜂巢上蠟、染料/墨囊/蜂巢塗告示牌、剪刀和玻璃瓶採蜂箱、剪刀刻南瓜、桶子和瓶子裝倒煉藥鍋。工具轉換後的方塊只有原本是天然或自己的才記成自己的。生火(使用者決定)：打火石、火焰彈生火和倒岩漿時，火那格周圍 2 格內有別人的可燃方塊(`ignitedByLava()`)就擋(SPEC S7.3d)。RulesGameTest 驗證鋤頭和點火。

- [x] **H3. 雙格方塊(門、床、高植物)未將第二格寫入所有權帳本**
  - **位置**：`src/main/java/blockagent/game/OwnershipTracker.java:52-57`、`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:205-226`、`src/main/java/blockagent/game/Blueprints.java:336-345`、`src/main/java/blockagent/game/BuildPreflight.java:179-184`
  - **問題**：放置門或床時只登記 `placeAt`，門上半部(`placeAt.above()`)與床頭(`placeAt.relative(facing)`)未登記。後續 `break_block` 門上半部或 `build_blueprint update/demolish` 會誤判為別人方塊而擋下。
  - **處理(2026-11-23，線 1)**：`RuleGuard.companionCell`(門和高植物的上半格、床頭)：放置時另一格也要能放，`OwnershipTracker.onPlaceAttempt` 兩格一起等伺服器確認。RulesGameTest 驗證門的兩格都是自己的、能單獨拆上半格。

- [x] **H4. `RuleGuard.canInteract` 忽略左右手(副手繞過 + `use_on_entity` 預檢誤擋)**
  - **位置**：`src/main/java/blockagent/game/RuleGuard.java:205-212`、`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:260-270`、`src/main/java/blockagent/game/Tools.java:860-893`
  - **問題**：`canInteract(target)` 只看 `mc.player.getMainHandItem().isEmpty()`。主手空、副手拿命名牌可改動村民；反之主手拿工具時呼叫 `use_on_entity(target="villager")`(省略 `item` 想空手交易)會在預檢被誤拒，而主手空時呼叫 `use_on_entity(target="villager", item="name_tag")` 反而會通過預檢。
  - **處理(2026-11-23，`b3ab945`)**：移除 `canInteract` 的空手放行，所有受保護生物(含村民與流浪商人)不論左右手一律擋下。註：`isProtectedEntity` 未區分寵物主人是否為自己，詳見下方複查紀錄 `P2-6`。

- [x] **H5. 創造模式 `/fill` 繞過管理員方塊限制與方塊實體排除**
  - **位置**：`src/main/java/blockagent/game/Tools.java:938,941`、`src/main/java/blockagent/game/Blueprints.java:366-369`、`src/main/java/blockagent/game/BuildPreflight.java:210-300`
  - **問題**：未檢查 `Creative.inCreativeTabs(mc, item)`，可用 `/fill` 放置 `barrier`、`light`、`spawner` 等管理員方塊；`BuildPreflight.inspect` 未排除 `hasBlockEntity()`，違反 SPEC S4.6f。
  - **處理(2026-11-23，線 1)**：`BuildCommands.directAllowed`：原本或要放的方塊有方塊實體、或不在創造物品欄(管理員方塊)就不用指令；`BuildPreflight.inspect` 設計裡有這類方塊、或指令會蓋掉有方塊實體的格子時，整個改用一般施工。

- [x] **H6. 聊天/指令最後防線採 opt-in，且漏攔 `sendUnattendedCommand`**
  - **位置**：`src/main/java/blockagent/mixin/ClientPacketListenerMixin.java:24-40`、`src/main/java/blockagent/game/ChatGateway.java:28,37-49`
  - **問題**：只攔 `ChatGateway.aiSending() == true` 的呼叫，未來任何未包 `withAiFlag` 的程式碼或 Baritone 呼叫都能直接繞過；且 MC 26.3 的 `ClientPacketListener.sendUnattendedCommand(String, Screen)` 未掛勾。
  - **處理(2026-11-23，線 1)**：`ChatGateway.mustCheckOutgoing`：AI 送的一律檢查；AI 在控制時(使用者決定：只在 RUNNING)，只有使用者自己從畫面送出的放行(`ChatScreen.handleChatInput`、`Screen.clickCommandAction`、伺服器對話框，以 MixinExtras `@WrapMethod` 標記)。補掛 `sendUnattendedCommand`。ChatGameTest 驗證沒標記的聊天、指令、點連結的指令被丟棄，聊天畫面打的放行。

### 任務、戰鬥、反射與生命週期

- [x] **H7. 多個任務缺少 `onResume()`，被反射暫停後恢復會崩潰或亂點裝備欄；暫停時間未扣除**
  - **位置**：`SmeltTask.java:130-188`、`CraftTask.java:144-177,222-257`、`StationTask.java:124-163`、`BreakTask.java:95-113`、`EatTask.java:44-56`、`FarmTask.java:101-168`、`FishTask.java:294-297`、`TaskRunner.java:52-90,95,183-196`
  - **問題**：
    - `Task.onSuspend()` 呼叫 `onStop()` 關閉容器/停止挖掘/鬆開進食，恢復時 `SmeltTask`(`CHECK` 強轉 `AbstractFurnaceMenu` 拋 `ClassCastException`、`PUT_*` 誤點 2×2 合成格)與 `CraftTask`(3×3 `FILL` 誤點玩家 `InventoryMenu` 盔甲欄 slot 5–8)會出錯；`BreakTask`/`EatTask`/`StationTask`/`FishTask` 恢復也會立刻失敗或原地亂拋竿。
    - `TaskRunner.suspend()/resume()` 未扣除暫停時間，導致 `BuildTask`(`STALL_TICKS=600`)或短逾時任務在自衛 30 秒後立刻超時失敗，且 `onSuspend`/`onResume` 未包 `try/catch`。
    - `TaskRunner.start()` 未協調進行中反射，新任務啟動會與反射同時搶 Baritone 與按鍵。
  - **處理(2026-11-23，線 2b)**：`SmeltTask`(重新打開熔爐並回到原本階段)、`CraftTask`(重新打開工作台、這一批重來)、`StationTask`、`BreakTask`、`EatTask`、`FarmTask`、`FishTask`(站不到岸邊先走回去)補上 `onResume()`；`TaskRunner.resume()` 把暫停時間從 `startTick`/`lastProgressTick` 扣掉；`onSuspend`/`onResume` 包 try/catch，出錯以 `INTERNAL` 結束；`claim()` 在反射(脫困以外)接手時回 `BUSY reflex=`，換任務時結束舊任務的脫困反射。SessionGameTest 用 `tasks.suspend()`/`resume()` 驗證熔煉中途暫停後會重新打開熔爐做完。

- [x] **H8. `SELF_DEFENSE` 隔牆 3 秒無限震盪 + 拉弓切戰術未收弓 + 副手拉弓攔截無限迴圈**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:110-123,169-181,244-313,402-462`、`src/main/java/blockagent/mixin/MultiPlayerGameModeMixin.java:189-203`
  - **問題**：
    - `pickThreat()` 對苦力怕/近戰/中立怪未檢查 `hasLineOfSight`，`finished()` 失去視線 60 tick 結束且無冷卻，牆外怪物會每 3 秒反覆打斷任務。反射 30 秒超時結束後亦無冷卻會立刻重觸發。
    - `Combat.shoot()` 拉弓後若不再進入 `shoot()` 或 `Bow.aim()` 失敗，主手拉弓與 `keyUse` 不會取消；`raiseShield()` 見 `p.isUsingItem()` 直接返回，導致苦力怕膨脹時舉不起盾。
    - `MultiPlayerGameModeMixin.blockagent$release` 用換快捷列取消拉弓對副手弓/三叉戟(或相鄰同物品)無效，且取消後未清 `isUsingItem()` 會每 tick 無限觸發。
  - **處理(2026-11-23，線 2a)**：`pickThreat` 除了要避開的都要看得到；自衛反射收手時目標還活著，那一隻 10 秒內不再觸發(冷卻中又打了自己的除外，30 秒逾時也算)。`Combat.tick` 這個 tick 沒在拉弓或射箭就收弓，`raiseShield` 先收弓；瞄不到時回 false 交給別的戰術。新增 `UseKey.cancelUse`：主手換格讓伺服器停止使用，客戶端也 `stopUsingItem()`，`blockagent$release` 改用它，不再每 tick 重複攔截。

- [x] **H9. 斷線時 `Combat.release()` 與 `FishTask.reelIn()` 拋出 NPE**
  - **位置**：`src/main/java/blockagent/game/combat/Combat.java:227-238,454-462`、`src/main/java/blockagent/game/tasks/FishTask.java:274-277,289-292`
  - **問題**：`mc.player == null` 時 `Combat.release()` 落入 `else` 呼叫 `lowerShield(mc)` 直接 NPE，中斷 `Reflexes.stopAll()` 清理；`FishTask.reelIn()` 未檢查 `mc.player`/`mc.gameMode == null`，斷線時 NPE 跳過 `Bari.stopAll()`。
  - **部分處理(2026-11-23，線 2a)**：`Combat.release` 沒有玩家或 `gameMode` 時只放開按鍵，`halt()` 包 try/catch。`FishTask.reelIn` 留給線 2b。
  - **處理(2026-11-23，線 2b)**：`FishTask.reelIn` 沒有玩家或 `gameMode` 時直接返回，`onStop` 用 try/finally 保證 `Bari.stopAll()`。

- [x] **H10. Baritone 設定還原漏洞與混淆版靜默失效**
  - **位置**：`MineTask.java:55-60,91-100`、`BuildTask.java:333-340,492-509,641-649`、`Bari.java:17-50,84-91`、`src/main/resources/fabric.mod.json:18`
  - **問題**：
    - `MineTask.onResume()` 未重新套用 `exploreForBlocks = search`；`onStop()` 無 `try/finally`。
    - `BuildTask.onStop()` 未還原 `layerOrder`、無 `try/finally` 保護 `noPlace(false)`(且 `static savedFall` 可能跨階段殘留)；拆鷹架期間若觸發 `Bari.enforceSettings()` -> `applySettings()`，會把 `allowBreak`/`allowPlace` 改回 `true`；`enforceSettings()` 漏檢 `chatDebug`、`desktopNotifications`、`useSwordToMine`。
    - 若使用者誤裝 `baritone-standalone-fabric`(混淆版)，3 個 Baritone Mixin 因目標類別未載入而靜默略過不報錯，建議於啟動時主動檢查關鍵類別並提示。
  - **處理(2026-11-23，線 2b)**：`MineTask.onResume` 重新套用 `exploreForBlocks`、`onStop` 用 try/finally；`BuildTask.onStop` 用 try/finally 還原 `buildInLayers`、`layerOrder`、`noPlace`；新增 `Task.adjustBaritone`，`Bari.applySettings` 重設後交給目前任務調整(清空區域、拆鷹架時不准挖和放)，不再在拆鷹架途中被改回 `true`；`enforceSettings` 補檢 `chatDebug`、`desktopNotifications`、`useSwordToMine`；啟動時 `Bari.mixinTargetsPresent()` 檢查 Baritone 內部類別，找不到就記錄錯誤並在進入世界時用紅字提醒。

- [x] **H11. `Control.stopEverything()` 關容器後備為死碼 + `Respawn` 跨世界狀態外洩**
  - **位置**：`src/main/java/blockagent/game/Control.java:109-124,149-174`、`src/main/java/blockagent/game/Respawn.java:20-63`、`src/main/java/blockagent/game/Agent.java:255-266,287`
  - **問題**：
    - `Control.stopEverything()` 先 `agent.tasks.cancel(reason)` 把 `current` 設為 `null`，才檢查 `agent.tasks.openedContainer()`(恆為 `false`)。另 `onToggle`/`takeover` 在 `stopEverything()` 前先觸發 `restoreMouse()`，若有容器開啟就抓不回滑鼠。
    - `Respawn` 死亡時未呼叫 `agent.reflexes.stopAll()`，且切換世界未重置 `hardcoreReported`/`lastRequest`/`deathPos`/`deadSince`。
  - **部分處理(2026-11-23，線 2a)**：`Respawn` 死掉時呼叫 `reflexes.stopAll()`，新增 `reset()` 在進入世界時清掉死亡狀態。`Control` 的部分留給線 2b。
  - **處理(2026-11-23，線 2b)**：`Control.stopEverything` 取消任務前先記下有沒有開容器；`onToggle`/`takeover` 在 `stopEverything` 關掉容器後再 `restoreMouse()`。

- [x] **H12. 工具、掃描與建造的重要缺陷**
  - **位置與問題**：
    - ~~`Tools.toolHint`(`Tools.java:962-973`)：鎬子等級錯一階(`NEEDS_DIAMOND_TOOL` 回報「鐵鎬以上」，`NEEDS_IRON_TOOL` 回報「石鎬以上」)。~~(已於 2026-11-23 `a6dddec` 修正)
    - `Tools.harvest` / `Tools.tillAndPlant`(`Tools.java:775-780,1171-1199`)：無區域體積上限檢查，大座標會卡死 client thread；`harvest` 在 `c1.y > c2.y` 時漏掃樓層。
    - `Tools.useItem`(`Tools.java:997-1006`)：檢查 `blockedSince(since - 1)`，會把上一 tick 的擋下紀錄誤當本次成功動作失敗。
    - `Blueprints.write`(`Blueprints.java:543-547`)：fallback `Files.move(temp, target)` 漏 `StandardCopyOption.REPLACE_EXISTING`；且 `create`/`update`/`house` 在檢查 `BUSY` 前就先寫檔、`delete` 走 `observe`，執行中的任務完成後會復活已刪除藍圖。
    - `Moves.scan`(`Moves.java:86-100`)：先傳上限給 Baritone 再濾半徑與條件，深水區 `FishTask`(`FishTask.java:91`)易被水下格填滿而誤報找不到水面。
    - `DoorPlacer`(`DoorPlacer.java:118-122`)：站位條件 `ahead >= 0.8 && side <= 1.0` 在 `ahead < side` 時玩家朝向會偏 90°，把門裝歪。
    - `ClearAreaTask` 未覆寫 `moves()`(預設 `false`)；`CommandBuildTask.onStop()` 漏 `Bari.stopAll()`；`UseEntityTask.collect()` 每 tick 重尋路且取消時漏停 Baritone。
    - `Reflexes` 的 `UNSTUCK`(`Reflexes.java:418-440`)：tick 30 後乾等 28.5 秒超時、不換工具、未呼 `stopDestroyBlock()`、`unstuckAttempts` 跨任務未重置。
    - `Observation.surroundings`(`Observation.java:183-288`)：每次在 client thread 掃完整方塊註冊表 11 次 + `Moves.scan` 16 次，易造成頓挫或逾時。
  - **部分處理(2026-11-23，線 2a)**：`UNSTUCK` 改成跳 0.5 秒、換上合適工具挖 2 秒、第 50 tick `stopDestroyBlock()`、第 60 tick 還沒移動就算這次失敗；結束時也 `stopDestroyBlock()`；`unstuckAttempts` 換任務時歸零。
  - **部分處理(2026-11-23，線 2b)**：`Moves.scan` 結果不夠時加大 Baritone 的掃描上限重掃(最多 32,768 格)；`DoorPlacer` 加上 `side < ahead`；`ClearAreaTask.moves()` 回傳是否在走路；`CommandBuildTask.onStop` 補 `Bari.stopAll()`；`UseEntityTask` 撿東西只在掉落物換位置時重新尋路，取消時停下 Baritone。剩下 `Tools`/`Blueprints`/`Observation` 的部分。
  - **部分處理(2026-11-23，線 3)**：`Observation.surroundings` 的方塊組改用快取的 tag 清單(`TaggedBlocks`)，固體方塊組合成一次 `Moves.scan` 再依方塊分組，只有水和岩漿各自掃(16 次 → 3 次)。剩下 `Tools`/`Blueprints` 的部分(線 5)。
  - **處理(2026-11-23，線 5)**：`harvest` 改用 `from`/`to` 正規化後的長方體(下 1 格、上 2 格)，`harvest`、`till_and_plant` 都套 32,768 格上限；`RuleGuard` 加上被擋次數的 `blockMark()`/`blockedAfter()`，`use_item`、`use_block`、`place_block` 用它判斷這次有沒有被擋；`Blueprints.write` 改用 `AtomicFiles`，`create`/`update`/`build_house` 先檢查 `BUSY` 再存檔，施工完成只更新還存在的紀錄。

- [x] **H13. `CliPermissionTest.codex()` 內建工具白名單解析漏檢盲點**
  - **位置**：`src/test/java/blockagent/cli/CliPermissionTest.java:301-313`
  - **問題**：`int at = keysLine.lastIndexOf("Output:")` 未被使用，實際從 `indexOf("apply_patch")` 開始擷取；任何字母順序排在 `apply_patch` 前面的新增 Codex 內建工具都會被靜默忽略。
  - **處理(2026-11-23，線 4)**：新增 `toolKeys()`：把 exec 結果(字串或 `input_text` 陣列)還原成文字，取最後一行「整行都是逗號分隔名稱」的那行整行解析；找不到清單就失敗，不再從 `apply_patch` 開始切。

---

## Medium(腳本、細節、相容性、資源與打包)

- [x] **M1. `bin/codex-tools-check:11` 與 `bin/codex-config:8` 預設 planner 判斷不一致**
  - `codex-config` 未設 `BLOCKAGENT_PLANNER_MODEL` 時預設 `gpt-6.2-sol`，`codex-tools-check` 用 `${BLOCKAGENT_PLANNER_MODEL:-}`(未設為假)，應改為 `${BLOCKAGENT_PLANNER_MODEL-gpt-6.2-sol}`。另 `codex-tools-check:8` 寫入固定路徑 `/tmp/codex-prompt-input.json` 且 POSIX `sh` 管線無 `pipefail`。
  - **處理(2026-11-23，線 4)**：`codex-tools-check` 的規劃判斷改成 `${BLOCKAGENT_PLANNER_MODEL-gpt-6.2-sol}`；`codex debug prompt-input` 先寫進暫存目錄、檢查結束碼再解析；只有設了 `BLOCKAGENT_DEBUG_DIR` 才另外存一份。

- [x] **M2. `bin/codex-config:64` 固定使用 `python3`(macOS 系統版 3.9 缺 `tomllib`)**
  - 需檢查或解析 Python ≥ 3.11 執行檔，避免 `blockagent-planner` 在系統 `/usr/bin/python3` 下啟動失敗。
  - **處理(2026-11-23，線 4)**：新增 `bin/python311`(可用 `BLOCKAGENT_PYTHON` 指定，依序找 `python3.15`…`python3.11`、`python3`)，`codex-config` 把找到的絕對路徑寫進規劃服務的 `command`；找不到就報錯不啟動。

- [x] **M3. `PrivateMessageFilter` 缺少設定檔 regex 容錯與多行支援**
  - **位置**：`src/main/java/blockagent/core/PrivateMessageFilter.java:29-39`
  - 使用者設定檔填錯 regex 會拋 `PatternSyntaxException` 導致整個模組初始化中止；`matches()` 缺 `Pattern.DOTALL` 會讓多行私訊漏過。
  - **處理(2026-11-23，線 4)**：所有樣式加 `DOTALL`；設定檔寫錯的 regex 略過、記在 `invalidPatterns()`，`Agent` 啟動時在日誌警告。CoreLogicTest 補多行私訊與錯誤 regex。

- [x] **M4. `Agent.unreadSummary` 未排除 `wait_events`**
  - **位置**：`src/main/java/blockagent/game/Agent.java:314-318`
  - `wait_events(task_only=true)` 逾時回傳時保留在佇列中的 `task_progress`，仍會被 `ToolRegistry.call` 的 `responseSuffix` 呼叫 `drainAll()` 立刻倒出。
  - **處理(2026-11-23，補做)**：`unreadSummary` 對 `wait_events` 也回空字串，事件由 `wait_events` 自己決定送哪些。`WaitEventsTest` 驗證佇列裡的 `task_progress` 留著、其他工具照樣附上。

- [x] **M5. `OwnershipTracker` 未跨世界重置 + `saveLedger`/`Memory.save` 非原子寫入**
  - **位置**：`OwnershipTracker.java:32-39,144-163`、`Agent.java:169-237,285,296`、`Memory.java:31-38,84-92`
  - 死亡 10 tick 內換世界會把舊世界 `died` 事件帶到新世界；`saveLedger`/`Memory.save` 直接 `Files.writeString` 有崩潰毀檔風險；暫停/待命期間手動放的方塊不會定期存檔；`finishDisconnect` 未清空舊世界 `session_ended` 事件與 `ledgerFile`。
  - **處理(2026-11-23，線 1)**：進入世界時清掉上個世界沒送出的事件、`OwnershipTracker.reset()`；`finishDisconnect` 清 `ledgerFile`；帳本存檔移到暫停檢查之前；`saveLedger`、`Memory.save` 改用 `core.AtomicFiles`(暫存檔+原子搬移)。

- [x] **M6. `ToolSetMixin` 在 AI 暫停/待命時仍運作並改動玩家快捷列**
  - **位置**：`src/main/java/blockagent/mixin/ToolSetMixin.java:18-31`
  - 未檢查 `session.isRunning()`，可能於非 AI 控制期間呼叫 `Inv.pullSpareInto()` 動到玩家背包。
  - **處理(2026-11-23，補做)**：`ToolSetMixin` 開頭檢查 `session.isRunning()`，不在執行中就照 Baritone 原本的選擇。

- [x] **M7. `natural_blocks.json` 包含人造方塊**
  - **位置**：`src/main/resources/blockagent/natural_blocks.json:13,17`
  - `#minecraft:logs` 包含所有 `stripped_*_log` 與 `*_wood`/`*_hyphae`；`#minecraft:terracotta` 包含全部 16 色染色陶土(應只留 `#minecraft:badlands_terracotta` + `minecraft:terracotta`)。
  - **處理(2026-11-23，線 1)**：原木改成逐一列出各樹種的 `*_log`/`*_stem`(含 26.3 的 `poplar_log`)與紅樹林根；陶土只留 `#minecraft:badlands_terracotta`。CoreLogicTest 補削皮原木、木塊、染色陶土。

- [x] **M8. 正式 jar 仍包含 `TestHooks.class`**
  - **位置**：`src/main/java/blockagent/game/TestHooks.java`、`build.gradle:122-140`
  - 建議移入 `src/gametest/` 的 testkit mixin，讓正式版產物完全不含測試輔助碼(M8.2 / S10.2)。
  - **處理(2026-11-23，線 5)**：刪掉 `TestHooks`。操作次數改由 testkit 的 `ActionCounter` Mixin 數送出的封包(開始挖、對方塊使用、攻擊、容器點擊)；T6 對照組改在 T7 結束工作階段後做(Mixin 不介入時失焦會跳出暫停選單)，`main` 不再有測試開關；`verifyReleaseJar` 也檢查 `TestHooks`。

- [x] **M9. `BuildPreflight` 細節瑕疵**
  - **位置**：`src/main/java/blockagent/game/BuildPreflight.java:120,255-275,329`
  - 生存模式門口遇到水/岩漿會放進 `plants` 導致 `BuildTask` 嘗試挖掘液體；創造無指令模式下 `build_wall` 要求整份材料數量而非每種 1 個；純挖掘/拆除格在預覽顯示 `0 格可施工`。
  - **處理(2026-11-23，線 5)**：門口只有真的花草才排進先清的 `plants`，水、雪片這類可替換方塊讓放門時直接蓋掉；創造模式不用指令時，牆、房子、藍圖都是每種材料 1 個就夠；預覽開頭的 `buildable=` 改成會動到的格子數(放、挖、拆、裝門，同一格只算一次)。

- [x] **M10. `Tools` 介面細節與一致性**
  - ~~`Tools.use_block`(`Tools.java:1003-1014`)未檢查 `blockedSince`，把規矩擋下包成 `INVALID_ARGUMENT`。~~(已於 2026-11-23 `a6dddec` 修正)
  - `Tools.placeBlock`(`Tools.java:1016-1082`)貼到互動方塊(箱子/門)會開介面並乾等 5 秒 `TIMEOUT`。
  - `Tools.resolveEntity`(`Tools.java:244-261`)傳入數字 ID 時繞過 `filter` 與 `range`；~~`attack`/`use_on_entity`/`mount` 用 `str` 而非 `strOrInt`~~(`strOrInt` 已於 2026-11-23 `a6dddec` 修正)。
  - `Tools.smelt`(`Tools.java:1228-1278`)未驗證手動指定的 `fuel` 具 `COOKING_FUEL`；指定產物時未優先選數量足夠的原料。
  - **處理(2026-11-23，線 5)**：`place_block` 貼互動方塊已在 P2-1(`e45d9d3`)改成只貼 `Facing.canPlaceAgainst` 允許的面；`resolveEntity` 用數字 id 時也檢查存活、範圍和 `filter`；`smelt` 指定的 `fuel` 不是燃料回 `INVALID_ARGUMENT`，給產物時優先選數量夠的原料，都不夠就選最多的。

- [x] **M11. `ChatGateway` 重複記錄自己發言與私訊白名單、以及 `BuildCommands` 回饋吞噬**
  - `ChatGateway.say:122` 與 `onPlayerChat:72` 會把 AI 每句發言寫入 `recent` 兩次；`onPlayerChat:53-62` 採 4 種私訊黑名單而非 SPEC S3.5 白名單。
  - `BuildCommands.awaitingFeedback` 為單一布林，連續 `/fill` 時回饋重疊會漏進聊天事件。
  - **處理(2026-11-23，線 1)**：`say` 不再自己記一筆，以伺服器轉回來的為準，自己的發言記成 `me`；私訊判斷改成白名單(只轉交 `CHAT`、`SAY_COMMAND`、`EMOTE_COMMAND`)；`BuildCommands` 回饋改成計數。

- [x] **M12. `Houses.generate` 覆寫預設地板/屋頂材質與非單格材料檢查**
  - **位置**：`src/main/java/blockagent/game/Houses.java:157-159,201-205`
  - 存檔時將預設 `floor`/`roof` 寫進 `source`，導致 `build_house(id=..., material=...)` 只換牆不換地板與屋頂；`blockId()` 未限制必須是單格方塊。
  - **處理(2026-11-23，線 5)**：沒指定的 `floor`/`roof` 不存進 `source`；重算時舊存檔的地板或屋頂跟舊牆材質相同、這次只換 `material` 就一起換；`blockId()` 拒絕有 `DOUBLE_BLOCK_HALF` 或 `BED_PART` 屬性的方塊。

- [x] **M13. `Inv`、`Creative`、`UseKey` 邊界風險**
  - `Inv.dropItem:177-179` 從 64 疊丟 1 個會在一 tick 送 65 封包(改用 `ContainerInput.THROW`)；`Inv.selectInHand`/`swapToOffhand` 未檢查非背包容器開啟。
  - `Creative.take:56` 在 `mc.gameMode == null` 時 NPE；`UseKey.release:43` 未檢查 `mc.gameMode == null`。
  - **部分處理(2026-11-23，線 2a)**：`UseKey.release` 檢查 `mc.gameMode == null`。`Inv`、`Creative` 留給線 2b。
  - **處理(2026-11-23，線 2b)**：`Inv.dropItem` 丟的比留的少時用 `THROW` 一個一個丟；`selectInHand`/`swapToOffhand` 開著別的容器時不點背包畫面；`Creative.take` 沒有 `gameMode` 時不再 NPE。

- [x] **M14. 任務與戰鬥細節**
  - `BuildTask`: `occupiedDoorways` 非 `volatile`(跨執行緒讀取)；`clearTick` 在 `continueDestroyBlock` 回 `false` 時乾等 30 秒；`stageMaterials` 略過非空氣可替換格；`cleanTick` 每次 `continueDestroyBlock` 設 `cleanWait=2` 變慢 3 倍；`end()` 在拆鷹架後掉格仍執行 `onComplete`。
  - `StationTask`: `WORK` 未從開啟容器後起算同步等待(亦影響新增的 `chest(action=list)`，多人延遲 ≥ 150 ms 時可能讀成空箱)，`take()` 切疊忽略同種未滿疊；`StationTask.item()` 直接呼叫 `Identifier.parse` 未正規化大小寫/空白(`Tools` 驗證會放行大寫 key 如 `"OAK_LOG"`，走到箱子前才拋例外)。
  - `FishTask`: 釣竿在最後一尾斷掉時 `Inv.total` 淨變 0 會漏算該次。
  - `ClearAreaTask`: `onSuspend()` 未暫時恢復 `allowBreak`/`allowPlace`；`oldAllowBreak`/`oldAllowPlace` 預設 `false`。
  - `Combat`: 近戰未檢查視線、揮劍掃擊未檢查周圍受保護生物、射箭未檢查上方屋頂碰撞、僅看 `Enemy` 忽略主動攻擊的非 `Enemy` 生物(鐵傀儡、狼、蜜蜂等)。
  - `Reflexes`: `FALL` 帶 2 桶水或掉落 > 40 tick 跳過舀回；`RETREAT` 不會依怪物追近重規劃；`DROWNING` 露出水面瞬間就結束易反覆觸發。
  - `TaskRunner.start`: `replace=true` 在檢查是否坐載具前就先取消舊任務。
  - `CalculationContextMixin.costOfPlacingAt`: 僅擋 `occupiedDoorways`，未對 `BuildTask.protectedCells` 回 `COST_INF`。
  - **部分處理(2026-11-23，線 2a)**：`Combat`：近戰要看得到；目標旁有受保護生物時不用劍(`RuleGuard.canAttack` 也擋拿劍的攻擊，`protectedNearTarget`)；射箭前用 `Bow.hasClearShot` 模擬彈道，會先撞到方塊就不射；剛打過自己的非敵對生物(狼、蜜蜂…)當近戰處理、鐵傀儡避開。`Reflexes`：`FALL` 等水桶數回到放水前或落地 2 秒；`RETREAT` 每 2 秒(怪物在 4 格內時半秒)重新規劃；`DROWNING` 等氧氣回滿。任務部分留給線 2b。
  - **處理(2026-11-23，線 2b)**：`BuildTask`：`occupiedDoorways` 改 `volatile`、`clearTick` 的 `continueDestroyBlock` 回 false 就換下一格、`stageMaterials` 也準備可替換格要的材料、拆鷹架每 tick 都繼續挖(只在拆掉一格後等 2 tick)、`end()` 少了格子就不執行 `onComplete`。`StationTask`：從箱子畫面出現起等 `stateId` 同步、`take()` 先補同種未滿的疊、`item()` 正規化。`FishTask` 計數不看釣竿。`ClearAreaTask`：`old*` 預設 `true`，暫停時恢復原本設定。`TaskRunner.start` 先檢查載具再取消舊任務。`CalculationContextMixin.costOfPlacingAt` 也擋藍圖設計裡要空著的格子(`BuildTask.forbidsScaffold`)。

- [x] **M15. 效能熱點**
  - `RuleGuard.blockTags`(`RuleGuard.java:62-64`)在 Baritone 尋路熱路徑每格建一個 `HashSet`，可快取 `Block -> isNatural`。
  - `Observation.tagged` 與 `Tools.blocksInTag` 重複做無快取全註冊表線性掃描。
  - **部分處理(2026-11-23，線 1)**：`RuleGuard.isNatural` 快取「方塊 → 是否天然」，換世界和 tag 重新載入(Fabric `TAGS_LOADED`)時清掉。`Observation.tagged` 留給線 3。
  - **處理(2026-11-23，線 3)**：新增 `TaggedBlocks`，`Observation.tagged` 和 `Tools.blocksInTag` 共用「tag → 方塊清單」快取，跟 `RuleGuard.clearCache()` 一起在換世界和 tag 重新載入時清掉。

- [x] **M16. 測試可靠性與覆蓋缺口**
  - `SmokeGameTest:33` 以寫死相對路徑找 `tools-snapshot.json`(路徑不對會靜默新建檔)，且 snapshot 檢查未在 `./gradlew test` 執行；Python planner 測試未掛進 Gradle。
  - `SessionGameTest.t7Disconnected` 未用 `try-with-resources` 管理專用伺服器，`wait.join()` 與 `McpTestClient.call()` 逾時後會卡住 gametest 執行緒；`ToolsGameTest:36` 仍用舊版 `doDaylightCycle`。
  - 缺少測試：`ObservationTest`、`MemoryTest`、`LauncherTest`(`--dry-run`)、M6.7 鍵盤接手、M6.2/6.5 HUD 狀態與收合、M7.8 `ActionLog.source`、M7.9 反射開關與鎖定、M6.10 極限模式重生。
  - **處理(2026-11-23，線 4)**：新增 `ToolsSnapshotTest`(單元測試裡 bootstrap Minecraft、建 `Agent` 比對 `tools-snapshot.json`，不同時寫出 `tools-snapshot.actual.json`)；`SmokeGameTest` 從專案根目錄找快照，找不到就失敗、不再自己建；Python 規劃服務測試掛成 `pythonTest`，`./gradlew test` 會先跑。`t7Disconnected` 已在 `bc57f41` 併進 `ServerAndCreativeGameTest.disconnectedByKick`(伺服器用 try-with-resources，`join()` 前先確認已完成)；`ToolsGameTest` 已刪除，沒有 `doDaylightCycle` 要改。新增測試依計畫只補 `ObservationTest`(`truncate` 格式)和 `MemoryTest`，其他缺口保留。

- [x] **M17. AI 時間感偏差(天未黑就急著回家、缺少天亮事件)**(2026-11-23 補充)
  - **位置**：`src/main/java/blockagent/game/Observation.java:91-97,454-460`、`src/main/java/blockagent/game/Fmt.java:71-76`
  - **問題**：
    - `timeOfDay` 在 `t=11000` 就標 `dusk`，且 `(12500 - t) / 1200` 整數捨去導致 `t=10101`(離地表生怪 `13188` 還有 2.5 分鐘)就印 `dusk_in=1min`；`night_falling` 在 `t=11300`(離生怪 ~94 秒、離能睡 `12542` 還有 1 分鐘)就發 `hint=head to shelter or a bed`，讓 AI 提早回家乾等。
    - `SPEC` S5.1 與 `Instructions.TEXT` 教 AI 用 `wait_events` 等天亮，但 `Observation.tick` 沒有發天亮事件；且 `23000–23999` 只印 `time=dawn`，AI 不知道 `23460` 不死系已開始自燃，會一路等到 `t=0`(`time=day`)才出門。
    - `Fmt.status` 附在任務結果尾巴的資訊只含 `hp`/`food`/`pos`，不含時間，連續做任務時感覺不到時間流逝。
  - **方向**：
    - 階段切點對齊原版：`day`(`0–11999` 與 `23460–23999`)、`dusk`(`12000–13187`)、`night`(`13188–22799`)、`dawn`(`22800–23459`)；倒數改四捨五入，低於 2 分鐘印秒數(如 `dusk_in=50s`)。
    - `night_falling` 延後至 `12000`；新增 `daybreak`(`PROMPT` 等級，僅在剛經歷過夜晚並跨過 `23460` 時發送一次，附晴天/雨天狀態)讓 `wait_events` 等天亮可被叫醒。
    - `Fmt.status` 順手附精簡時間(如 `time=day` 或近入夜時 `dusk_in=90s`)，同步更新 `docs/SPEC.md`。
  - **處理(2026-11-23，線 3)**：切點和倒數寫成純函式 `core.DayClock`(有單元測試)；白天倒數 `dusk_in`、黃昏 `night_in`、晚上和黎明 `day_in`，2 分鐘內寫秒數。`night_falling` 在 12000 發；觀察到天黑後跨進白天發 `daybreak`(PROMPT，附天氣與雨天提醒)。任務結果的 `Fmt.status` 附 `time=`，2 分鐘內換階段才加倒數。SPEC S3.2/S4.3/S5.2、`Instructions`、`how-to-play.md` 一併更新。

---

## Low(清理與相容性)

- [x] **L1.** `GuiMixin`(`:13-17`)與 `MouseHandlerMixin`(`:54-59`)改用 MixinExtras `@WrapWithCondition` / `@Inject` 取代獨佔 `@Redirect`；`MouseHandlerMixin.blockagent$clearOnRelease` 僅在 AI 啟用時介入；`MinecraftMixin` 維持 `lastActiveTime` 避免結束瞬間無寬限期暫停。
  - **處理(2026-11-23，線 4)**：`GuiMixin` 改用 `@WrapWithCondition`，`turnPlayer` 改用 `@WrapOperation`；`clearOnRelease` 只在有工作階段時介入；`MinecraftMixin` 跳過檢查時更新 `lastActiveTime`。
- [x] **L2.** `McpServer` 對非字串 `method`/`protocolVersion`/`clientInfo` 回 400 而非 500，且 500 例外需記錄日誌；`Config.sanitize()` 夾限 `mcpPort` 於 `1..65535`。
  - **處理(2026-11-23，線 4)**：型別不對時回 400(`method` 用 `-32600`，`initialize` 的參數用 `-32602`)，500 時把例外寫進日誌；`mcpPort` 不在範圍內就改回預設 47615(夾到 1 會用到特權埠，反而開不起來)。McpServerTest 補型別錯誤的案例。
- [x] **L3.** `bin/blockagent-claude:43` 對 `$BA_BIN/stop-hook` 做好 JSON 跳脫與空白路徑引號；`bin/blockagent-codex:42` 補上 `--no-daemon`；`bin/codex-config:22` 比照 `planner_model` 驗證 `BLOCKAGENT_CODEX_MODEL`。
  - **處理(2026-11-23，線 4)**：`settings.json` 改由 Python 產生(`shlex.quote` 再 `json.dumps`)；`--test-exec` 非 JSON 模式也加 `--no-daemon`；`BLOCKAGENT_CODEX_MODEL` 做跟規劃模型一樣的格式和長度檢查。
- [x] **L4.** 補上 `src/main/resources/assets/blockagent/lang/zh_tw.json` 與 `en_us.json`(按鍵翻譯)；`build.gradle` 一併展開 `processGametestResources` 的 `${version}`。
  - **處理(2026-11-23，線 4)**：補上兩個語言檔(四個按鍵和分類 `key.category.blockagent.main`)；`processGametestResources` 也展開 `${version}`。SmokeGameTest 檢查按鍵有翻譯。
- [x] **L5.** `Observation.tick` 在暫停後恢復會對暫停期間扣血補發假 `damaged`；`get_surroundings` 缺火源危險(S3.2)；~~`truncate()` 對 `get_self`/`get_players` 仍印「用 detail=true」~~(已於 2026-11-23 `a6dddec` 修正)；`WorldKey` 未正規化 `:25565`。
  - **處理(2026-11-23，線 3)**：`Observation.tick` 隔了不只 1 tick 才再執行(暫停、待命)時不拿舊血量比；`get_surroundings` 新增 `fire` 組(火、靈魂火)；`WorldKey.serverAddress` 去空白、轉小寫、去結尾的點和 `:25565`，舊資料夾已存在而新的沒有時沿用舊的。
- [x] **L6.** `MineTask` 挖最後一格即 `done()` 沒等撿掉落物；`HuntTask` 遇合法 0 掉落物仍判 `NOT_FOUND`；`FarmTask` 延遲超過 1 tick 會跳過收成撿取；`Moves.findPlaceSpot` 未避開底下互動方塊與實體阻擋。
  - **處理(2026-11-23，線 2b)**：`MineTask` 挖到數量後停下挖掘，最多花 3 秒撿最後那格附近的掉落物；`HuntTask` 打倒後沒看到掉落物就算完成(`drops=none`)；`FarmTask` 從作物消失才開始走過去撿(最多 1 秒)；`Moves.findPlaceSpot` 避開底下是互動方塊和有生物擋著的位置。


---

## MCP 與 AI Agent 使用視角(Agent 體驗、能力盲區與 Schema 設計)

> 從「AI Agent 直接連接這個 MCP Server 來玩遊戲」的角度檢視 `Instructions.TEXT`、47 個工具的 JSON Schema(`tools-snapshot.json`，壓縮後約 23.7 KB)、觀察回傳內容與 MCP 協定設計。

### 觀察盲區與半成品工具

- [x] **A1. `get_surroundings` 看不到載具(船/礦車)與地上掉落物**
  - **位置**：`src/main/java/blockagent/game/Observation.java:295`
  - **問題**：實體掃描只過濾 `e instanceof LivingEntity`，導致 `AbstractBoat`、`AbstractMinecart` 與 `ItemEntity` 完全不會出現。Agent 有 `mount` 工具且 `respawned` 事件要它回去撿東西，卻看不見船、礦車和掉落物。
  - **處理(2026-11-23)**：`get_surroundings` 新增 `vehicle` 行(`VehicleEntity`：船、礦車，附 id、位置、乘客)與 `drop` 行(`ItemEntity` 依物品 id 合併計數)。

- [x] **A2. 缺少查看箱子內容的工具(`chest_list` / `inspect_chest`)**
  - **位置**：`src/main/java/blockagent/game/Tools.java:887-915`
  - **問題**：有 `chest_put`、`chest_take`、`use_block`，卻沒有查看箱子內容的工具；`chest_take` 必須先指定 `{物品id: 數量}`，對村莊/地牢/朋友的箱子或壓縮脈絡後的自家箱子只能盲猜。
  - **處理(2026-11-23)**：新增 `chest(action=list|put|take)`，結果一律附上關上前的箱子內容(`contents=`、`free=`、`special=`)。

- [x] **A3. 村民交易為半成品死路**
  - **位置**：`src/main/java/blockagent/game/Tools.java:833`、`src/main/java/blockagent/game/RuleGuard.java:194-198`
  - **問題**：`use_on_entity` 說明寫「`item 省略就空手(例如開村民的交易畫面)`」，但沒有任何查看交易清單或執行交易的工具。應補齊交易工具，或先從說明移除以免誘導 Agent 空點。
  - **處理(2026-11-23)**：決定不做交易工具，並禁止與村民互動：`RuleGuard.canInteract` 對村民和流浪商人一律回 `RULE_PROTECTED_ENTITY`(空手也一樣，hint 說明不支援交易)，工具預檢與 `MultiPlayerGameModeMixin` 最後防線都會擋；`use_on_entity`、`mount` 說明與 SPEC S7.2 同步，RulesGameTest 驗證。

- [x] **A4. `get_players` 未回傳座標，與提示詞的 `goto` 流程脫節**
  - **位置**：`src/main/java/blockagent/game/Observation.java:363`、`prompts/how-to-play.md:31`
  - **問題**：提示詞教 AI「先用 `get_players` 查位置，再用 `goto` 走到他附近幾格」，但 `get_players` 只印相對方向(如「東北 12 格」)，沒有印出 `x, y, z` 座標。
  - **處理(2026-11-23)**：`get_players` 每行附 `pos=x,y,z`。

- [x] **A5. `get_self` 缺少狀態效果、裝備附魔/藥水內容與面朝方向**
  - **位置**：`src/main/java/blockagent/game/Observation.java:101-172`
  - **問題**：`Observation.item()` 未顯示附魔、藥水種類或書本內容；`self()` 未顯示 `MobEffects`(中毒、防火、虛弱、飢餓等)與面向方位(`get_surroundings` 會說「前方有落差」，但 Agent 不知自己面朝哪邊)。
  - **處理(2026-11-23)**：`get_self` 新增 `face=`、`effects=`；手持、盔甲與背包特殊物品顯示耐久、附魔、儲存附魔、藥水、書名、自訂名稱(`Fmt.item`)。

- [x] **A6. `place_block` 無法指定方塊朝向**
  - **位置**：`src/main/java/blockagent/game/Tools.java:935-985`
  - **問題**：放置樓梯、熔爐、箱子、漏斗、活板門或貼牆火把時無法指定 `facing`，只能由系統取第一個相鄰實心方塊貼放。
  - **處理(2026-11-23)**：`place_block` 新增 `facing`：用原版放置邏輯模擬找出點擊面與視角，先轉向再點，伺服器確認後驗證方塊狀態；沒有朝向屬性一律回 `INVALID_ARGUMENT`(SPEC S4.2b，RulesGameTest 驗證樓梯、熔爐、原木)。

### Schema 一致性與 Token 效率

- [x] **A7. 工具總數(47 個)與重複 Schema 膨脹**
  - **位置**：`src/test/resources/blockagent/tools-snapshot.json`、`src/main/java/blockagent/game/Tools.java`
  - **問題**：`wait_sec` 的 64 字重複說明在 23 個工具各出現一次；`blueprintPart` 物件在 `build_blueprint` 與 `preview_build` 展開 4 次；`preview_build.spec` 把 `wall`/`house`/`blueprint` 欄位全部重寫一遍。在 Codex 60k auto-compact 門檻下，精簡重複說明、評估以 `dry_run` 取代或瘦身 `preview_build`、或合併低頻 CRUD 工具可省下大量每輪固定 token。
  - **處理(2026-11-23)**：47 → 45 個工具：`preview_build` 併入 `build_*` 的 `dry_run`，`chest_put`/`chest_take` 併成 `chest`；`wait_sec`/`replace` 說明移到 `instructions`。`memory_*` 與反射開關依決定維持獨立。壓縮後 schema 30,619 → 26,163 位元組(含新增的 annotations 約 3.6 KB)。

- [x] **A8. 跨工具參數格式與寬容度不統一**
  - **位置**：`src/main/java/blockagent/game/Tools.java`
  - **問題**：
    - 座標混用 `x, y, z`(整數)與 `"x,y,z"`(字串)，區域端點混用 `from`/`to` 與 `corner1`/`corner2`。
    - `target` schema 宣告 `string`，但觀察輸出為 `id=1538`；後端僅 `hunt` 用 `strOrInt`，`attack`/`use_on_entity`/`mount` 傳整數會失敗。
    - `break_block`、`place_block`、`eat`、`hunt`、`dismount` 缺少 `replace` 參數，加上 `additionalProperties: false` 易觸發參數錯誤。
  - **處理(2026-11-23)**：區域端點統一 `from`/`to`(`build_house`、`till_and_plant`、`harvest`；舊藍圖的 `corner1`/`corner2` 相容)；`attack`、`use_on_entity`、`mount` 改用 `strOrInt`；所有背景任務自動帶 `replace`。

- [x] **A9. JSON Schema 缺少數值約束與列舉(只寫在中文 description)**
  - **位置**：`src/main/java/blockagent/mcp/Schema.java`、`src/main/java/blockagent/game/Tools.java`
  - **問題**：`Schema.java` 未輸出 `minimum`、`maximum`、`default`；`till_and_plant.seed` 固定 4 種卻未設 `enum`；`preview_build.spec.door_side` 漏設 `enum`。補齊標準 JSON Schema 約束可讓模型在生成層直接避開越界。
  - **處理(2026-11-23)**：`Schema` 支援 `range()`、`def()`，各工具補上 `minimum`/`maximum`/`default`；`till_and_plant.seed` 改 `enum`；`door_side` 隨 `preview_build` 移除。

- [x] **A10. 短背景任務 `wait_sec` 預設為 `0` 導致多餘回合**
  - **位置**：`src/main/java/blockagent/game/Tools.java:129-138`、`prompts/how-to-play.md:91`
  - **問題**：像 `break_block`、`eat`、`dismount` 這類 1–3 秒完成的短任務，模型漏帶 `wait_sec` 就會白白多花一整輪推論呼叫 `wait_events`；而 `place_block` 在生存模式原本就是同步等待確認，掛 `wait_sec` 反而令人困惑。
  - **處理(2026-11-23)**：所有背景任務 `wait_sec` 預設 30(0 立刻回傳)。

### MCP 協定標準與通用客戶端相容性

- [x] **A11. 缺少 MCP Tool Annotations**
  - **位置**：`src/main/java/blockagent/mcp/ToolRegistry.java:49-59`
  - **問題**：協定已支援 `2025-11-25`，但 `tools/list` 未提供 `annotations`(`readOnlyHint`、`destructiveHint`、`idempotentHint`、`openWorldHint`)，不利於標準 MCP client 區分唯讀觀察工具與寫入動作。
  - **處理(2026-11-23)**：`ToolRegistry.Hints` 輸出 `readOnlyHint`、`destructiveHint`、`idempotentHint`、`openWorldHint`。

- [x] **A12. `Instructions.TEXT` 未說明平行工具呼叫限制**
  - **位置**：`src/main/java/blockagent/game/Instructions.java:11-18`
  - **問題**：未明確告知 Agent「觀察工具可平行，但背景任務一次只能執行一個，請勿同時平行呼叫多個動作工具」，現代模型在單輪平行發出多動時容易撞出 `BUSY`。
  - **處理(2026-11-23)**：`Instructions.TEXT` 改英文並寫明並發規則。

- [x] **A13. 通用 MCP Client 無法取得 `how-to-play.md`(未暴露 MCP Prompts)**
  - **位置**：`src/main/java/blockagent/mcp/McpServer.java`、`prompts/how-to-play.md`
  - **問題**：目前僅透過專用 CLI 腳本外部注入提示詞，標準 MCP client 直接連 `http://127.0.0.1:<port>/mcp` 時拿不到玩法指南；可考慮透過 `prompts/list` + `prompts/get` 提供可選取的 prompt。
  - **處理(2026-11-23)**：`McpServer` 實作 `prompts/list`、`prompts/get`，有 prompt 時宣告 `capabilities.prompts`；`how_to_play` 內容是打包進 jar 的 `prompts/how-to-play.md`(SPEC S11.2a，McpServerTest 驗證)。

- [x] **A14. 統一錯誤回饋的可行動性(Actionable Errors)**
  - **位置**：`Tools.java`(如 `use_block:521`、`toolHint:923-929`)
  - **問題**：部分錯誤已提供具體下一步提示(如 `smelt` 提示 `craft furnace`、`mine` 提示 `search=true`)，但 `use_block` 隱藏規矩拒絕原因、`toolHint` 報錯鎬子等級等仍會誤導 Agent 陷入錯誤重試。
  - **處理(2026-11-23)**：錯誤統一為 `ERR <CODE> <kv> \| <reason> \| hint=`(`ErrorCode` 提供預設 hint)；`toolHint` 鎬子等級修正；`use_block`/`place_block` 回報規矩擋下的真正原因。

- [x] **A15. `EventQueue.render` 未輸出 `Event.type()`**
  - **位置**：`src/main/java/blockagent/core/EventQueue.java:139-163`
  - **問題**：`Instructions.TEXT` 與 `prompts/how-to-play.md` 要求 AI 依事件型別(`task_done`、`task_failed`、`task_cancelled`、`idle_reminder`、`user_paused`、`user_resumed`、`time_warning`、`respawned`、`session_ended`)分支，但 `EventQueue.render` 只輸出 `e.text()` 而未印出 `e.type()`。
  - **處理(2026-11-23)**：`EventQueue.render` 每行輸出 `[LEVEL] type:`，合併類寫成 `type(n):`；`CoreLogicTest.renderShowsLevelAndType` 驗證。

---

## 建議處理方案與落地路線(已定案方向)

### 核心設計原則：人機介面雙語分層
- **人類介面維持繁體中文**：HUD 面板、遊戲內 `[AI]` 聊天與終端機思考摘要(由 `prompts/how-to-play.md` 要求輸出繁體中文)、CLI 啟動提示、遊戲內朋友聊天保留**原文**。
- **MCP 通訊層改為 LLM-first(精簡英文 Compact KV 純文字)**：只給 LLM 讀的 `Instructions`、`tools/list` schema、`tools/call` 回傳、錯誤與事件改為高密度英文 KV/條列格式(預估省 45%–60% token，並直接與 Minecraft 英文 ID 對齊)。

### MCP 層具體定案
1. **回應與錯誤格式(Compact KV)**：
   - 觀察輸出：`pos=10,64,-3 dim=overworld biome=plains face=N ...`(座標無空白可直接貼回參數，所有權固定枚舉 `own|other|nat`)。
   - 動作結果：`OK started task=...` / `OK done task=... | hp=20/20 food=18/20 pos=...`。
   - 錯誤結果：`ERR <CODE> <key=val ...> | <short reason> | hint=<next action>`(消除中文原因與 `詳細：{...}` 的重複)。
   - 事件輸出：`[EVENTS]` 明確帶出 `level` 與 `e.type()`(如 `URGENT user_paused: ...`、`BATCH chat(2): Ming="好餓"`)。
2. **參數與座標一致化**：
   - 單點工具維持 `x, y, z` 整數(保留 `goto` 可省略 `y`、`sleep`/`use_item`/`drop` 選填座標的清晰度)。
   - 區域工具端點統一命名為 `from` / `to`(`"x,y,z"`，淘汰 `corner1`/`corner2` 混用)。
   - 所有實體工具(`attack`、`hunt`、`use_on_entity`、`mount`)統一用 `strOrInt` 支援數字或字串 ID。
3. **工具適度整併(47 → ~39 個)與 Schema 強化**：
   - 高頻核心工具保持獨立(`goto`, `mine`, `craft`, `smelt`, `hunt`, `build_house`, `get_self`, `get_surroundings` 等)。
   - 只合併 4 組高重複/低頻工具：
     - `preview_build` → 併為 `build_wall`/`build_house`/`build_blueprint` 的 `dry_run: boolean`。
     - `chest_put` + `chest_take` + 新增查看 → `chest(action="list"|"put"|"take", ...)`。
     - `memory_read`/`write`/`delete` → `memory(action="read"|"write"|"delete", ...)`。
     - `list_reflexes`/`set_reflex` → `reflex(action="list"|"set", ...)`。
   - 補齊 JSON Schema 的 `minimum`/`maximum`/`default`/`enum` 與 MCP `annotations`(`readOnlyHint` 等)；精簡 `wait_sec` 重複說明；短任務(`break_block`, `eat`, `dismount`)改為預設等完(如 `wait_sec` 預設 10)；`Instructions.TEXT` 明寫並發規則(觀察可平行、背景任務一次一個)。
4. **補齊 4 個 Agent 觀察盲區**：
   - `get_surroundings` 加入載具(船/礦車)與地上掉落物。
   - `get_players` 附上 `pos=x,y,z`。
   - `get_self` 補上 `face`(面向)、`effects`(狀態效果)、附魔與藥水內容。
   - 移除 `use_on_entity` 裡「開村民交易畫面」的半成品誘導說明(直到補齊交易工具)。

### 三階段執行順序
- [x] **Phase 1 — 底層止血(不動 MCP 文字協定，沿用現有測試驗證)**(2026-11-23 完成：線 1、2a、2b)
  - 修 `C1–C7`(潛行繞過、跨維度帳本、`set_reflex` 執行緒、`ON_FIRE`/`EAT`/`SmeltTask`/`MenuOps`)與核心安全 `H1–H4`(水桶、鋤頭與工具改塊、雙格方塊所有權、副手互動)。
- [x] **Phase 2 — MCP 層「LLM-first」一次性升級**(2026-11-23 完成；`memory_*`/反射開關依決定不合併)
  - 一次完成英文 Compact KV 格式、補 `Event.type()`(`A15`)、4 個觀察盲區(`A1, A2, A4, A5`)、4 組工具整併、`Schema` 約束與 `annotations`、短任務預設等待、移除村民交易死路(`A3`)。
  - 同步更新 `docs/SPEC.md`、`tools-snapshot.json`、`prompts/how-to-play.md`，並將測試斷言由中文句子改為 `ErrorCode` 與固定 KV 鍵。
- [x] **Phase 3 — 工程護欄與其餘修正**(2026-11-23 完成：線 3、5、4 與 M4、M6 補做；`./gradlew test`、`cliTest` 與全部 5 個遊戲測試套件通過)
  - 修 `CliPermissionTest.codex()`、`TestHooks` 移出正式 jar、將 Python planner 測試與 `tools-snapshot.json` 檢查接入 `./gradlew test`，並逐項清理其餘 `H`/`M`/`L` 項目與下方 Phase 2 複查發現(`P2-1`–`P2-6`)。

---

## 複查紀錄(2026-11-23，Phase 2：`a6dddec`–`b3ab945`)

> 審查範圍：`41341cf..b3ab945`(`a6dddec`、`4084f93`、`b3ab945`)，共 73 個變更檔案；靜態審查與 `tools-snapshot.json` 結構檢查(未重跑 Gradle Java 測試與 GameTest)。
> 結論：Phase 2(`A1–A15`)雙語分層乾淨(MCP 回應無殘留中文)、`Fmt.facing`/`compass` 方位換算正確、MCP `annotations` 與 `prompts/list`/`prompts/get` 符合規格、45 個工具快照一致；同時順手解決了 `H4`、`H12`(`toolHint`)、`M10`(`use_block`、`strOrInt`)、`L5`(`truncate` 提示)。本次改動新發現以下待修項目：

- [x] **P2-1. `Facing.solve()` 漏過濾無方塊實體的互動方塊，會開介面並乾等逾時**
  - **位置**：`src/main/java/blockagent/game/Facing.java:108-112`、`src/main/java/blockagent/game/tasks/PlaceTask.java:89-106`
  - **問題**：`Facing.solve()` 只排除 `n.hasBlockEntity()`，但工作台、切石機、織布機、門、活板門、柵欄門、按鈕、拉桿等沒有 `BlockEntity`；`solve()` 直接呼叫 `getPlacementState` 不模擬方塊互動，挑中這些面後 `PlaceTask` 未潛行呼叫 `useItemOn` 會打開介面或切換方塊(`consumesAction() == true`)，接著等 5 秒 `TIMEOUT` 且介面未關閉。
  - **方向**：`Facing.solve()` 比照 `MultiPlayerGameModeMixin.blockagent$isInteractive` 排除互動方塊；`PlaceTask` 若偵測 `containerMenu` 改變應立即關閉並回錯。
  - **處理(2026-11-23)**：`MultiPlayerGameModeMixin.blockagent$isInteractive` 搬成 `RuleGuard.isInteractive(level, state, pos)` 共用；`Facing.canPlaceAgainst()`(實心、無方塊實體、非互動方塊)同時給 `Facing.solve()` 和不帶 `facing` 的 `Tools.placeBlock` 挑支撐面(順手解決 `M10` 貼到箱子、工作台會開介面)。`PlaceTask` 確認階段若 `containerMenu` 變了就關掉並回 `INVALID_ARGUMENT`；`placeBlock` 確認後發現開了介面也會關掉。`RulesGameTest.placeFacing` 補「漏斗朝西只能點工作台 → 回 `possible=down`、沒開介面」。

- [x] **P2-2. `PlaceTask` 缺少 `onResume()`(被反射暫停後拿錯物品點擊)且完成時重複輸出 `state=`**
  - **位置**：`src/main/java/blockagent/game/tasks/PlaceTask.java:54-118`、`src/main/java/blockagent/game/tasks/TaskRunner.java:153-156`
  - **問題**：
    - `progress()` 輸出 `state=confirming`，完成時 `done("state=oak_stairs[...]")` 與 `progress()` 拼接成 `state=confirming state=oak_stairs[...]`(且 `pos=` 與 `Fmt.status` 的 `pos=` 重複)。
    - 只在 `ticks == 1` 選取物品；若在 `TURN` 階段被 `EAT`/`SELF_DEFENSE` 暫停並切換手持物或移動位置，恢復後不會重選物品、也不重檢 `Moves.canReach`，直接拿反射留下的物品右鍵。
  - **方向**：`progress()` 在 `DONE` 時省略階段欄位(或改鍵名為 `phase=`)；在 `onResume()` 重置 `TURN` 計數或在 `useItemOn` 前重選物品並重檢距離。
  - **處理(2026-11-23)**：`progress()` 進行中寫 `pos= item= facing= phase=turning|confirming`，結束後只留 `item= facing=`(結果尾巴已有自己的 `pos=`，`done` 給 `state=`)。`onResume()` 在 `TURN` 階段把計數歸零，重新轉向、關容器、重選物品；點擊前再檢查距離(`PATH_BLOCKED`)和手上物品。註：`BreakTask`/`CommandBlockTask` 結束時仍是 SPEC S4.3 記載的「目標 `pos=` +自己 `pos=`」寫法，這次沒動。

- [x] **P2-3. 創造模式 `place_block(facing=...)` 對不支援的方向誤報無朝向屬性**
  - **位置**：`src/main/java/blockagent/game/Tools.java:1099-1106`、`src/main/java/blockagent/game/Facing.java:58-76`
  - **問題**：創造指令模式(`direct == true`)下，對具朝向但僅接受部分方向的方塊傳不支援方向(如樓梯/熔爐/箱子設 `facing=up`，或漏斗設 `facing=up`)，`Facing.apply` 回 `null`，第 1103 行卻呼叫 `noFacing(...)` 回報 `this block has no facing property | hint=omit facing`(與生存模式回報 `possible=...` 矛盾)。
  - **方向**：區分「無朝向屬性」與「該方向不適用」，後者回 `INVALID_ARGUMENT` 並列出 `possible=` 允許方向清單。
  - **處理(2026-11-23)**：新增 `Facing.possible(state)`(用 `Facing.apply` 試六個方向)；創造指令模式下方向不適用時回 `ERR INVALID_ARGUMENT ... block= possible= | this block cannot face <dir> | hint=pick one of possible`，「沒有朝向屬性」仍在最前面就回 `noFacing`。

- [x] **P2-4. `McpTestClient.parseError()` 的 regex 遇到 `data` 內含 `|` 會解析失敗**
  - **位置**：`src/gametest/java/blockagent/testkit/McpTestClient.java:105-116`、`src/main/java/blockagent/core/Recipes.java:128`
  - **問題**：正則 `^ERR ([A-Z_]+)( [^|]*)? \| (.*?)(?: \| hint=(.*))?$` 中的 `[^|]*` 無法匹配引號內的 `|`。`craft` 缺料輸出 `missing="cobblestone|#stone_tool_materials:3"` 或藍圖名稱含 `|` 時，`m.matches()` 回 `false`，導致 `code()`/`reason()`/`hint()` 全為 `null`。
  - **方向**：改以 `" | "` 分隔(例如 `( .*?)? \| `)或正確跳過雙引號字串內的 `|`。
  - **處理(2026-11-23)**：改成手寫掃描：鍵值段跳過雙引號字串(含 `\"` 跳脫)找第一個 ` | `，其後是原因，hint 取最後一個 ` | hint=` 之後。用獨立小程式驗過含 `|` 的 `missing="..."`、無 hint、非錯誤行。

- [x] **P2-5. 生存模式不帶 `facing` 的 `place_block` 繞過 `TaskRunner`(忽略 `BUSY` 與 `replace`)**
  - **位置**：`src/main/java/blockagent/game/Tools.java:541-555,1016-1082`
  - **問題**：`place_block` 已透過 `taskTool` 宣告 `replace` 與 `wait_sec`，但只有帶 `facing`(或創造指令模式)走 `a.tasks.start(...)`；生存模式不帶 `facing` 會直接執行 `placeBlock(...)`，若有其他背景任務進行中，即使 `replace=false` 也不會回 `BUSY`，直接搶快捷列與視角。
  - **方向**：在 `placeBlock` 前檢查進行中任務並尊重 `replace`(或統一走任務機制)。
  - **處理(2026-11-23)**：新增 `TaskRunner.claim(name, replace)`(`start()` 也改用它)：有任務時 `replace=false` 回 `BUSY`、`replace=true` 先取消舊任務。`placeBlock` 在事前檢查(支撐面、背包)都過了、動快捷列之前呼叫，失敗的呼叫不會白白取消任務。沒有整個改走任務機制：一般放置一個 tick 就點完，改成任務會多出轉向等待且改變回應格式，收益不大。

- [x] **P2-6. Phase 2 複查次要項目**
  - `RuleGuard.isProtectedEntity`(`RuleGuard.java:127-132`)未區分寵物主人是否為自己，移除空手放行後連 AI 自己馴服的馬/狼也會被 `canInteract` 擋下(與工具說明 `"others' pets"`/`"Others' horses"` 略有出入)；`RulesGameTest.t1` 建議在最後防線區段補一筆直接呼叫 `mc.gameMode.interact(...)` 擋村民的驗證。
  - `TaskRunner.endLine`(`TaskRunner.java:163-164`)對空字串 `failHint` 會輸出空的 `| hint=`，應比照 `ToolResult.error` 檢查 `!isBlank()`。
  - `PlaceTask.java:92` 與 `Tools.java` 其餘呼叫 `RuleGuard` 的位置未轉發 `blocked.hint()`(目前僅 `canInteract` 有自訂 hint，統一轉發可避免後續遺漏)。
  - `Observation.surroundings`(`Observation.java:280-282`)處理 `ItemEntity` 建議多檢查 `!it.getItem().isEmpty()`。
  - `docs/SPEC.md:854` 的 T2 測試表仍殘留舊工具名 `chest_take(...)`，應改為 `chest(action=take, ...)`。
  - **處理(2026-11-23)**：
    - 寵物：使用者決定**維持現行行為**(寵物和馴服的馬不分主人一律受保護，規矩簡單好解釋；這不是 `b3ab945` 造成的)。`use_on_entity`/`mount` 工具說明改成 "tamed pets (anyone's, yours too)"/"Tamed horses (anyone's, yours too)"，SPEC 表格與 S7.7 `interact` 同步，快照已更新。`RulesGameTest.t1` 最後防線補直接呼叫 `mc.gameMode.interact(...)` 對村民：回傳不是成功、沒打開交易畫面。
    - `TaskRunner.endLine` 的 hint 比照 `ToolResult.error`：`null` 用錯誤碼預設，空字串不輸出 `| hint=`。
    - `PlaceTask`、`CommandBlockTask`、`HuntTask`，以及 `Tools` 的 `break_block`、`hunt`、`use_block`、`use_item`、`attack`、`place_block`(含 facing)都轉發 `Verdict.hint()`(`null` 仍用預設)。`Task.failToOpen` 是把規矩原因併進句子，維持原樣。
    - `Observation.surroundings` 略過空物品的 `ItemEntity`。
    - `SPEC.md` T2 測試表改成 `chest(action=take, 朋友的箱子, items={bread: 3})`。

---

## 複查紀錄(2026-11-24，Phase 1/3 複查：`b3ab945..a0a8fd1`)

> 審查範圍：`b3ab945..a0a8fd1`(96 個變更檔案)，靜態程式碼審查並對照 MC 26.3 位元碼。
> 結論：原 `REVIEW.md` 全部 Critical(C1–C7)與絕大多數 High/Medium/Low 皆已正確落實；本次複查挖掘出 9 個 High、13 個 Medium 與 7 個 Low(多為新邏輯回歸、邊界未收乾淨或既有跨執行緒/原版互動盲點)，並於 2026-11-24 分 Phase A–E 全部修畢，同時抽出 `CombatPolicy` 純邏輯測試與建立 `docs/DEV_RULES.md` 開發護欄。

### High(已修畢)

- [x] **P3-H1(H2 安全繞過)**：`SolidBucketItem`(細雪桶)繼承 `BlockItem`，點別人煉藥鍋走進 `if (placing)` 繞過 `else if (... toolWouldChange ...)`。→ `MultiPlayerGameModeMixin` 改為先獨立檢查 `RuleGuard.toolWouldChange`；`RulesGameTest` 補細雪桶煉藥鍋回歸測試。
- [x] **P3-H2(H2 安全繞過)**：點擊可替換方塊生火時 `canStartFire` 傳了 `targetState.canBeReplaced() ? target : relative`，但原版 `FlintAndSteelItem`/`FireChargeItem` 一律點在 `relative`，少算 1 格。→ 改為一律傳 `target.relative(hit.getDirection())`；`RulesGameTest` 補短草東面點火回歸測試。
- [x] **P3-H3(M14 執行緒安全)**：`BuildTask.forbidsScaffold()` 在 Baritone 尋路執行緒讀取非執行緒安全的 `LinkedHashMap targets`。→ 改以 `ConcurrentHashMap.newKeySet()` 維護 `forbiddenScaffolds`，`nextClear()` 同步更新。
- [x] **P3-H4(M14 回歸)**：`Combat.strike()` 加入 `!hasLineOfSight` 後，`melee`/`creeper`/`ranged` 的 `if (inReach(mc)) return;` 沒檢查 `los`，導致隔牆近戰卡死(`HuntTask` 卡到逾時)。→ 抽出 `CombatPolicy.shouldWaitInReach(inReach, los)` 並在三處套用，`CombatPolicyTest` 驗證。
- [x] **P3-H5(M14 回歸)**：`Combat.shoot()` 在 `Bow.hasClearShot()` 之前先 `lowerShield(mc)`，屋頂下對骷髏每 tick 重置舉盾。→ 調整為先通過 `CombatPolicy.canPrepareShot` 與 `Bow.aim` 才放盾拉弓，`CombatPolicyTest` 驗證。
- [x] **P3-H6(H7 / C7)**：`SmeltTask` 恢復進入 `WAIT`/`PUT_*` 跳過容器內容同步等待，且 `CHECK`/`PUT_*` 缺 `menuOpen(mc)` 保護。→ 新增 `Phase.SYNC` 等封包同步，並於 `SYNC`/`CHECK`/`PUT_INPUT`/`PUT_FUEL` 補 `menuOpen(mc)` 檢查。
- [x] **P3-H7(H7)**：`CraftTask` 在 `FILL`/`TAKE`/`CLEAR` 缺 `CraftingMenu` 保護(工作台被拆會誤點盔甲欄)，`PLACE_TABLE`/`SmeltTask.PLACE` 恢復漏檢距離，`CLEAR` 暫停前若已同步未先結算。→ 補 `checkTableMenu()`、`onSuspend()` 預結算與 `PLACE_TABLE`/`PLACE` 距離檢查。
- [x] **P3-H8(H8)**：`UseKey.cancelUse()` 對副手未通知伺服器，主手換格未即時送封包。→ 主手新增 `MultiPlayerGameModeInvoker.blockagent$ensureHasSentCarriedItem()` 立刻刷出換格封包；副手以 `javap` 驗證 MC 26.3 `handlePlayerAction` 後發送一對 `SWAP_ITEM_WITH_OFFHAND` 取消使用且保持雙手不變。
- [x] **P3-H9(H10)**：`BuildTask.savedLayerOrder` 在建構子擷取而非 `start()`，`replace=true` 連續建造會漏還原。→ 改為預設 `true`，於 `start(mc)` 擷取。

### Medium(已修畢)

- [x] **P3-M1(H12 `UNSTUCK`)**：改用 `BreakTask.equipBestTool`、排除流體與不可破壞方塊(`getDestroySpeed < 0`)、挖通後 `stopDestroyBlock()` 並走進清出空間。
- [x] **P3-M2(H8 冷卻)**：抽出 `CombatPolicy.DefenseCooldown`，紀錄收手時間點(只允許收手*之後*的新攻擊解除冷卻)，被更高優先級反射搶佔時不上冷卻；`CombatPolicyTest` 驗證。
- [x] **P3-M3(M14)**：`Threats.isThreat` 納入剛打過自己的非 `Enemy` 生物(排除受保護生物)，讓鐵傀儡/狼/蜜蜂也能觸發 `RETREAT`。
- [x] **P3-M4(H2)**：`MultiPlayerGameModeMixin.useItemOnReturn` 將工具所有權追蹤擴充至 `RuleGuard.toolWouldChange`(含銅塊上蠟、煉藥鍋裝倒、剪刀刻南瓜)；`RulesGameTest` 補銅塊上蠟保所有權驗證。
- [x] **P3-M5(H3 / H5)**：`Tools.placeBlock`/`placeFacing` 創造 `/fill` 改以 `RuleGuard.companionCell(...) == null` 判斷，一併涵蓋 MC 26.3 的 `straw_bed` 與 `pitcher_plant`。
- [x] **P3-M6(P2-1)**：`Tools.placeBlock` 將意外開容器的檢查移至 `state == null`(`TIMEOUT`)之前，確保即時關閉並回 `INVALID_ARGUMENT`。
- [x] **P3-M7(M9 回歸)**：`Tools.build` 缺料訊息的 `have=` 改用 `Inv.count(mc.player, design.material())`。
- [x] **P3-M8(H12)**：`Blueprints.precheckStart` 同步納入反射中、任務中與坐載具檢查，避免先寫藍圖檔再報錯。
- [x] **P3-M9(H12)**：`Moves.scan` 抽出 `clampScanQuota`/`nextScanQuota` 封頂於 `MAX_SCAN`(32,768)；`TaskPolicyTest` 驗證。
- [x] **P3-M10(H12)**：`AreaInspection.Region.volume()` 補 `x <= 0 || y <= 0 || z <= 0` 與 `Tools.harvest` Y 溢位檢查；`AreaInspectionTest` 驗證。
- [x] **P3-M11(M4)**：`ToolResult.withSuffixHandled` 讓 `taskTool` 帶 `wait_sec > 0` 走完 `awaitTask` 後跳過 `ToolRegistry` 尾端 `responseSuffix`，不把保留中的 `task_progress` 倒出；`WaitEventsTest` 驗證。
- [x] **P3-M12(M2)**：`bin/python311` 引號保護 `BLOCKAGENT_PYTHON` 並以 `os.path.abspath` 輸出絕對路徑(保留 venv symlink)。
- [x] **P3-M13(H7 / M14)**：`StationTask.onResume` 清舊 `failMessage` 並消除雙重 `path()`；`FarmTask.onResume` 補 `REPLANT` 與超距 `WAIT` 走回；`FishTask` 最後一尾入包後立即完成；`ClearAreaTask` 新增 `Task.requiresFoot()` 與 `moves()` 解耦；`TaskRunner.claim`/`cancel` 補 `dropUnstuck` 且 `CombatPolicy.shouldReportUnstuckFailure` 排除 `task_replaced`/`task_cancelled`；`Task.onShiftTimers` 平移 `BuildTask.cleanStart`、`MineTask.finishing`、`UseEntityTask.usedTick`。

### Low(已順手修畢)

- [x] `Bow.hasClearShot` 先測方塊阻擋再測目標 AABB；`SmeltTask` `noFuel` 納入非燃料物品(空桶)並補 `TaskPolicyTest`；`Observation.tick` 跨暫停重設 `sawDark`；`DayClock.duration` 消除 `120s` 邊界並補 `TaskPolicyTest`；`TaggedBlocks` 加 `AtomicLong` 世代防並發重載競態；`McpServer.start` 埠號掃描上限 `<= 65535`；`Reflexes.stopAll()` 補 `stopDestroyBlock()`；`RuleGuard.isInteractive` 補無 `BlockEntity` 互動方塊(並確保非 `BLOCK_TRANSFORMER` 工具不受 `handActs` 略過)；`MultiPlayerGameModeMixin` 支援 `ScaffoldingBlockItem.updatePlacementContext`；`DoorPlacer`/`MountTask`/`UseEntityTask`/`PlaceTask` 統一改用 `blockMark()`/`blockedAfter()`。
- [x] 新增 `docs/DEV_RULES.md` 開發與審查護欄。
- [x] **二輪對抗自審收斂(2026-11-24)**：
  - `RuleGuard` 拆出 `alwaysConsumesUse()`(嚴格子集給 `MultiPlayerGameModeMixin`)與 `isInteractive()`(寬鬆超集給 `Facing.canPlaceAgainst`/`Moves.findPlaceSpot`)，並於 `MultiPlayerGameModeMixin` 補 `hand != InteractionHand.MAIN_HAND` 杜絕副手繞過與未滿堆肥桶/重生錨/蛋糕/鐵門回傳 `PASS` 繞過生火或放置檢查，`RulesGameTest` 補堆肥桶點火驗證。
  - `Reflexes.UNSTUCK` 鎖定 `unstuckStart`/`unstuckFront`，挖通後改 `GoalRunAway(3, unstuckStart)`(`> 2` 格門檻，第 70 tick 逾時)。
  - `Control.stopEverything` 先停 `reflexes.stopAll()` 再取消任務；`dropUnstuck(mc, how)` 區分 `"task_replaced"` 與 `"task_cancelled"`，`Reflexes.end` 補 `mc.options != null` 與 `Bari.stopAll()` `try/catch`。
  - `CraftTask` 以 `batchMenu` 綁定每批畫面，不靠 `needsTable()` 猜測，杜絕多步驟 2×2 配方在工作台被拆時誤點盔甲欄。
  - `Blueprints.precheckRiding` 移至 `parse` 之後、`write` 之前，坐載具時直接以 `BuildPreflight.inspect` 與 `start()` 單一來源對齊，避免設計含門、方塊實體或非創造欄方塊時先寫檔再失敗；順手清理 `FarmTask.onResume` 重複 `path()`/`afterWalk` 與 `SmeltTask.SYNC` 錯誤文案。
