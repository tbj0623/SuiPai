# 随拍 · 应用架构（P0-B）

> 这一轮的题目：把 `MainActivity.kt`（1172 行、一个 composable 里 ~40 个 `remember`、
> 9 个批量函数、手写 `when` 导航）拆成可扩展、可测试的架构，为后续十来个新界面
> （设置中心 / 回收站 / 收藏 / 回忆 / 搜索 / 幻灯片 / 拼图 / 裁剪编辑…）铺路。
>
> **这是重构，不是改功能**：所有行为（包括那些"看起来像 bug 但已装机验证过"的细节）零变化。

---

## 一、分层与目录

```
app/src/main/java/com/suipai/app/
├── MainActivity.kt          ← 只剩 7 件事（见下），313 行（重构后是 298，2026-10-29 接 SelectionState 后 +15）
├── state/                   ← 数据状态 + 业务动作（不含 Compose 布局）
│   ├── AppRoute.kt          目的地定义 + String 编码/解码（纯 Kotlin）
│   ├── BackStack.kt         返回栈（纯 Kotlin、不可变、可编码）
│   ├── NavPolicy.kt         返回键优先级 / Tab 切换 / 入栈规则（纯函数）
│   ├── NavState.kt          可保存的导航状态（Compose 可观察 + Saver）
│   ├── SelectionState.kt    选中集（可保存；`MAX_SAVED = 5000`，超限整体不存）
│   ├── RefreshGate.kt       要不要重读库（纯函数，按 generation + TTL 门控）
│   ├── BatchMachine.kt      批量动作状态机（纯函数：进度只在 Running、结论只在 Done）
│   ├── BatchSupport.kt      分享导入结论 / 授权票据（纯函数 + 可编码 data class）
│   ├── AppState.kt          照片 / 相册 / 查看器 / 选择 / 批量结论 / 加载状态
│   ├── BatchActions.kt      删除 / 收藏 / 隐藏 / 复制 / 一瞬
│   ├── DeleteFlow.kt        删除授权链（RecoverableSecurityException → 系统框 → 复查）
│   ├── ShareImport.kt       外部分享导入
│   └── ChromeState.kt       暗房/日光、关于抽屉（界面偏好）
└── nav/
    ├── AppNavHost.kt        用返回栈驱动全部屏幕（接线层）
    ├── NavRender.kt         这一帧该画哪一屏（按"数据在不在"裁层）
    └── AppChrome.kt         底栏 + 印样页眉（原 else 分支的逐行搬家）
```

**一条硬边界**：`state/` 里除 `NavState` / `AppState` / `ChromeState` / `SelectionState`
之外全是纯 Kotlin（不 import Compose、不 import Android），所以它们能被 JVM 单测直接钉住。
`SelectionState` 是第四格进 Bundle 的状态（它用 Compose 的 `Saver` 与 `mutableStateOf`），
`RefreshGate` 则是纯函数 —— 两者放在同一个包里，但前者可测的是"编码/恢复"，后者可测的是"判据"。
`nav/` 只做接线，不含业务规则。

---

## 二、MainActivity 的职责清单（瘦身结果）

改前它是一个 1172 行的 composable，什么都干。现在只留 7 件**必须**由 Activity 干的事：

| # | 职责 | 为什么不能搬走 |
| --- | --- | --- |
| 1 | 生命周期（`onCreate` / `onNewIntent`） | 系统入口 |
| 2 | `setContent` + `SuiPaiTheme` | 需要 Activity 的 window |
| 3 | `PermissionGate` | 需要 `rememberLauncherForActivityResult` 请求权限 |
| 4 | 建 `AppState` 与协作者 | 需要权限后的 `Context` 与 `rememberCoroutineScope` |
| 5 | **两个 `ActivityResultLauncher`**（删除授权 / 系统相机） | `registerForActivityResult` 必须在 `STARTED` 之前、且绑定 Activity |
| 6 | 系统栏图标明暗跟 `darkUi` | 需要 `window` / `WindowCompat` |
| 7 | intent 收集（分享 / 快捷方式 `suipai://`） | 系统入口 |

"新加一个屏幕"**不需要动这个文件**——这是本轮最实质的收益。

---

## 三、状态所有权表

`●` = 持有并写入，`○` = 只读订阅，`—` = 不涉及

| 状态 | 持有者 | 谁订阅 | 进 Bundle？ | 键 / 值形状 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 返回栈 | `NavState` | `AppNavHost` / `AppChrome` | **是** | `List<String>`（`base:<n>` + 每层 `encode()`） | 不用 Parcelable；脏数据降级回照片页 |
| 查看器当前页 | `AppState.viewerIndex` | `PhotoViewerScreen`（`rememberSaveable` 自己还有一份） | 是（查看器层本身不恢复） | `Int` | 翻页不写回路由（否则每滑一下就重建栈） |
| 查看器照片列表 | `AppState.viewerPhotos` | `PhotoViewerScreen` | **否** | `List<PhotoItem>`（带 Uri） | 重建后为 null → `NavRender` 跳过这一层（与改前一致） |
| 相册内页照片 | `AppState.albumPhotos` | `GalleryScreen` | **否** | `List<PhotoItem>` | 但**路由**恢复 → `refresh()` 重读那一册 |
| 相册内页 id / 标题 | `AppRoute.AlbumDetail` | `AppNavHost` | **是** | `String` × 2（路由里） | 与相册内页同生命周期（改前是两份独立状态，是最常见的改一漏一） |
| 时间线照片 | `AppState.photos` | `GalleryScreen`（tab 0） | 否 | `List<PhotoItem>` | `ON_RESUME` 重读 |
| 页大小 / `hasMore` / `loadingMore` | `AppState` | `GalleryScreen` | 否 | `Boolean` | 不在 Bundle：重读一次比存 5000 张便宜 |
| 相册清单 | `AppState.remoteAlbums` + `albums`（派生） | `AlbumListScreen` | 否 | `List<AlbumItem>` | `albums` 由收藏/隐藏/远端三者派生，数据变化时重算 |
| 收藏 / 隐藏 id | `AppState.favoriteIds` / `hiddenIds` | 画廊、查看器、相册 | 否 | `Set<Long>` | 真值在 `Favorites` / `Hidden`（它们自己落盘） |
| 收藏 / 隐藏照片缓存 | `AppState.favoritePhotos` / `hiddenPhotos` | 画廊、相册内页 | 否 | `List<PhotoItem>` | `loadByIds` 的结果 |
| 选中集合 | `AppState.selectedIds`（转发）→ `state/SelectionState` | 画廊 / 查看器 | **是**（`SelectionState.Saver`，`LongArray`，`MAX_SAVED = 5000`，超限**整体不存**） | `Set<Long>` | `MainActivity` 用 `rememberSaveable(saver = SelectionState.Saver)` 持有，`AppState.selectedIds` 是转发属性（8 个写入点零改动）。**代码完成，装机未验** |
| 批量状态 | `AppState.batchPhase`（`BatchMachine.Phase`） | 画廊选择条 / 页眉 | 否 | sealed | 进度位与结论位都从它派生 |
| 那句结论 `batchNote` | `AppState` | 页眉 `gallery_note` / 查看器 `statusNote` | 否 | `String?` | 4.5 秒自退（由 `AppNavHost` 的 effect 清） |
| 库问题 `libraryProblem` | `AppState` | 画廊空态 | 否 | `String?` | "没读到"≠"确实是空的" |
| 授权票据 | `AppState.consent`（`ConsentTicket`） | `DeleteFlow` | **否** ⚠️ | `List<String>` 三段 | **这里与旧版描述不符，如实标注**：`consent` 是 `AppState` 里的普通 `mutableStateOf`，而 `AppState` 整体是普通 `remember` —— 所以票据**不进 Bundle**。重建后票据丢、`DeleteFlow` 那边也没有可消费的票（"票还在、没人消费"那条反向风险因此不存在，但"重建后授权框回来时票据没了"这条**没装机验过**）。`ConsentTicket` 本身仍是可编码的 `data class`，`ConsentTicketTest` 钉住三段必须一起存 |
| 暗房/日光 | `ChromeState` | `MainActivity`（系统栏）、`AppChrome` | 否 | prefs `dark_ui` | 落盘在 `suipai` 偏好文件 |
| 关于抽屉 | `ChromeState.aboutOpen` | `AppNavHost` | 否 | `Boolean` | 纯瞬时 UI |
| 分享收件箱 | `ShareImport.inbox` | `AppNavHost`（`LaunchedEffect` 的 key） | 否 | `List<Uri>` | 每次 intent 都重新收集 |
| 快捷方式跳转 | `MainActivity.launchHop` | `MainActivity` | 否 | `String?` | 一次性信号 |

### 为什么把"数据状态"放在活动作用域、而不是 ViewModel

`AppState` 需要 `Favorites` / `Hidden` / `MediaRepository`，而这三者的构造依赖**权限之后**的
`Context`，创建点只能在 `PermissionGate` 内部；同时它的存活期必须与
`onSaveInstanceState` 对齐（视图树重建 = 重新读相册）。用
`remember` + `rememberSaveable`（导航与票据）能精确表达这件事，而 ViewModel 会引入
"重建后不重读"的第二套真相，与改前行为不一致。**未来若要跨进程恢复更重的状态，
再迁 ViewModel，届时先读这张表。**

---

## 四、导航规则（全部是纯函数，都有单测）

### 4.1 返回栈的结构

```
base: 一个 Tab（照片 / 相册 / 互通）        ← 永远有且只有一个
layers: [ … … ]                          ← 上层，最后一个 = 当前屏
current = layers.lastOrNull() ?: base
```

### 4.2 入栈的去重策略

| 情况 | 规则 | 为什么 |
| --- | --- | --- |
| 栈顶与要压的 **同 family** | 替换栈顶，不长高 | 一瞬里按"下一张"、查看器里换一张再进一瞬：改前都是一个单值状态，换值不叠层 |
| **不同 family** | 照常入栈 | 大图 → 一瞬 = 两层（返回先回大图） |
| 要压的是 `Tab` | **忽略** | 栈里出现第二个 Tab，返回键会在两颗 tab 之间来回跳 |

family 取值：`tab` / `album` / `viewer` / `camera` / `yishun`。新增界面时
`AppRoute.family` 决定它是否与已有层"同类只留一份"。

### 4.3 Tab 切换

| 动作 | 规则 | 备注 |
| --- | --- | --- |
| 点底栏 / 矮屏页眉的 tab | **换底座 + 清上层** | 改前切 tab 时上层必然不可见（底栏只在上层为空时画），所以这是显式化不是新规则 |
| 快捷方式「互通」 | **只换底座，保留上层** | 改前是 `tab = 2`，而渲染顺序里上层都在 tab 之上 → 屏幕不变（行为保持） |
| 快捷方式「拍照」 | 插到栈顶**下面** | 改前 `showCamera = true` 是个布尔，一瞬的渲染分支在前 → 相机在一瞬下面 |
| 快捷方式「一瞬」 | 摘掉相机与查看器层，再压一瞬 | 改前是三个赋值（`showCamera=false` / `viewerPhotos=null` / `yishunUri=…`） |

### 4.4 返回键优先级

`NavPolicy.resolveBack()` 的 `when` 顺序**就是行为**，不许重排：

| 优先级 | 条件 | 动作 | 改前出处 |
| --- | --- | --- | --- |
| 1 | 批量任务在跑 | 取消任务（**不清选择**） | `batchJob?.isActive == true -> { cancel(); batchHint = null }` |
| 2 | 有选中 | 清选择 | `selectedIds.isNotEmpty() -> selectedIds = emptySet()` |
| 3 | 一瞬 / 相机 / 查看器 / 相册内页在栈里 | 退一层（并清掉那一层的数据） | 四个并列分支 |
| 4 | 以上都无 | 交给系统（Activity 结束） | `enabled = false` |

第 3 档在改前是四个并列分支，因为那四层互斥地覆盖同一块屏幕；现在合成为"退栈顶一层"，
**语义不变、扩展性更好**（新界面只要进栈就自动获得返回键行为）。

### 4.5 这一帧该画哪一屏

`NavRender.effective()`：从栈顶往下找第一个"数据在"的层。
`viewerPhotos == null` 时跳过查看器层、露下下面那一屏——与改前 `when` 按数据挑分支一致。
**注意**：只跳过、不改栈；栈是用户意图，数据是当前事实。

---

## 五、批量动作的状态机（文字版）

三个动作（删除 / 副本 / 一瞬）共用一个状态机 `BatchMachine`，只有动词不同：

```
                    ┌─────────────────────────────────────────┐
                    │                                         │
   Idle ──start──► Running(done, total, verb) ──finish──► Done(note, cancelled)
     ▲              │        ▲                            │
     │              │        │                            │
     │              │      advance(done)                  │
     │              │      ← 只接受"真的成了几张"          │
     │              │                                     │
     └──────────────┴─────────────────────────────────────┘
        cancel()（用户按停止 / 返回键第 1 档）
```

三条不变量（各有单测，也各有源码守卫）：

1. **进度位只显示 Running**：`hint(Idle) == null`、`hint(Done) == null`。
   改前 `batchYishun` 把失败结论写进进度位，于是那句结论**永远不会被清掉**。
2. **进度按真实成功数报**：`advance` 收的是计数不是下标。
   改前写 `i + 1`，23 张一张没删掉也能把进度条走到 23/23
   （`PerfSourceGuardTest.attemptCountProgress` 钉住）。
3. **结论只在 Done、且必须有话说**：结论词来自 `ui/common/BatchNote.kt` 的纯函数
   （每个输入都有一句非空、带张数的话）。取消时不报张数——中途停下时手上没有可信的成功数。

收尾一律走 `NonCancellable`：用户按"停止"时选择要清、结论要说，否则屏幕上留下一条走到一半的进度条。

### 删除的两段式（唯一一个跨协程边界的动作）

```
batch.delete(from)
  ├─ 逐张 deleteOne(uri)
  │    DELETED       → deleted += id          ┐ 每张都刷新进度：已删 N/M
  │    NEEDS_CONSENT → consent += (id, uri)   │ （N = 真实成功数）
  │    FAILED        → refused++              ┘
  ├─ 有 consent？ ── 否 ─► batchNote = deleteNote(deleted, refused, total)  ← 结束
  └─ 是 ─► 写票据(ids=consent, direct=deleted, fromViewer=false)
           ├─ deleteRequestFor() == null ─► batchNote = "…要授权但这台机器没给入口"（结束）
           └─ 非 null ─► ActivityResultLauncher.launch(req)  ← 任务挂起（awaiting=true）
                         │
                         └─ 系统框回来 ─► DeleteFlow.onConsentResult()
                                ├─ 票为 empty ─► 结束（"用户根本没点"这条腿）
                                ├─ stillPresent(ids) == null ─► "没查到这几张还在不在"（补：不许说成成功）
                                └─ 有值 ─► gone = ids filterNot in still
                                        ├─ pruneForDeleted(gone)（收藏/隐藏/选择三处一起摘）
                                        ├─ 从 viewer/photos/albumPhotos 里剔掉
                                        └─ batchNote = consentNote(gone, asked, already)
```

三个数字必须分开说：**真没了的 / 被扣下的 / 弹框之前就直删掉的**
（`consentNote(gone, asked, already)`，`BatchNoteTest` 穷举）。

---

## 六、新屏幕接入的 5 步模板

以「设置中心 `Settings`」为例（假设它从页眉进入、不是 tab）：

**第 1 步 · 加一个路由**（`state/AppRoute.kt`）

```kotlin
data object Settings : AppRoute {
    override val family: String get() = "settings"
    override fun encode(): String = "settings"
}
```
并在 `AppRoute.decode` 里补一行 `encoded == "settings" -> Settings`。
> **漏了 decode 的症状**：进程被杀 / 转屏重建之后回到首页，而且不会有任何报错。
> `AppRouteTest` 里补一条往返用例就能抓住。

**第 2 步 · 加导航分支**（`nav/AppNavHost.kt` 的 `when (shown)`）

```kotlin
AppRoute.Settings -> SettingsScreen(onClose = { backOneLayer(state, nav) })
```

**第 3 步 · 需要拦返回键吗？**
- 已经在栈里 → 自动获得（`resolveBack` 第 3 档就是"退一层"），**不用改任何东西**；
- 如果它有"层内的临时状态"（比如批量进行中、抽屉开着）→ 在 `NavPolicy.Intercepts` 加一个
  Booleans 字段，并在 `resolveBack` 的**合适优先级**上插一档（顺序就是行为，改前六条都不许重排）。

**第 4 步 · 需要进 Bundle 的状态吗？**
- 进：`rememberSaveable`（String / Int / Boolean / `List<String>`）+ 在
  `NavState.Saver` 或 `AppState` 里给出编码/恢复；
- 不进：普通 `remember`。
- **判据**：这份状态丢了之后，用户会不会"被静默扔回照片网格"？会 → 必须进 Bundle。

**第 5 步 · 加测试**
- `AppRouteTest`：编码往返（含特殊字符）；
- `BackStackTest`：新 family 的入栈/去重/返回行为；
- 如果它是**新的一类**目的地，补一条"同 family 替换栈顶"的用例。
- 跑 `pwsh tools/abuild.ps1 :app:testDebugUnitTest`，用例数只许涨。

---

## 七、测试清单（`app/src/test/java/com/suipai/app/state/`）

| 文件 | 用例数 | 钉的是什么 |
| --- | --- | --- |
| `state/BackStackTest.kt` | 34 | 入栈 / 出栈 / 同路由去重 / 空栈兜底 / Tab 清栈 / 保存恢复 / 脏数据降级 / 快捷方式的两条特殊规则 / `showViewerAt` 幂等 |
| `state/AppRouteTest.kt` | 12 | 编码往返（含 `:`/`%`/换行/中文）、越界页号、未知编码、Tab 下标对齐 |
| （同文件内 `NavPolicyTest`） | 10 | 返回键优先级、Tab 切换、`Intercepts` 与 `resolveBack` 的分工、系统相机落点 |
| `state/BatchMachineTest.kt` | 12 | 进度按成功数报、结论不进进度位、取消时清进度、动词逐字保留、越界钳制 |
| （同文件内 `ShareImportOutcomeTest` / `ConsentTicketTest`） | 8 | 分享导入三档结论；授权票据三段一起保存、`direct` 清零规则 |
| `nav/NavRenderTest.kt` | 8 | 这一帧该画哪一屏；"数据没了但路由还在"时降级到下面那一屏 |

**合计 78 条**（`OK (78 tests)`）。

### 这些测试抓出的两个真缺陷（都是我第一版写的，值得记下来）

1. **`NavRender` 多了一个 `yishunReady` 参数。**
   一瞬要显示的 uri 就是路由自己，不存在"路由在、数据没了"这一格 —— 那个参数会让
   "关不掉一瞬"成为可能（`yishunIsAlwaysReady` 当场判红）。已删掉该参数。
2. **`jumpUri` 那条路只设数据、不压路由。**
   改前 `refresh()` 里"一瞬保存完跳回查看器"只有一句 `viewerPhotos = photos` ——
   因为查看器是 `when` 里按**数据在不在**挑分支的，设数据就等于打开查看器。
   重构后查看器还需要一个路由层：只设数据不压路由，屏幕上**不会有任何反应且不报错**
   （一瞬保存的那张再也看不到）。补了 `NavState.showViewerAt()`（幂等：栈里已有查看器
   就退回去而不叠层）+ 3 条单测。
3. 另有一个**搬漏**：`batchNote` 的 4.5 秒自动消失（改前
   `LaunchedEffect(batchNote) { delay(4500); batchNote = null }`）初版没搬。
   少了它，那句结论会永远留在页眉 `gallery_note` 与查看器 `statusNote` 上，
   并被下一次操作的进度顶掉 —— 用户看到的结论张冠李戴。已补在 `AppNavHost` 顶部。

**每条测试的注释都写了"它防的是什么 bug"**——因为这一层的错的共同点是
编译器不报、跑起来不崩，只表现为"返回错地方 / 重建回首页 / 删错照片"。

---

## 八、迁移账（改了哪些守卫断言、为什么）

守卫测试扫的是**源码文本**，所以挪代码必然要跟着改扫描路径。原则：**只改路径/位置，不改意图**。

| 守卫 | 改了什么 | 为什么 | 有没有削弱 |
| --- | --- | --- | --- |
| `NavTabSemanticsGuardTest` | `mainActivity` 的 `File("…/MainActivity.kt")` → `File("…/nav/AppChrome.kt")` | 底栏与页眉整体搬到 `AppChrome.kt`（逐行搬、testTag 与语义一字未改） | **没有**：判据、检查项、自检坏样本全部原样。条数仍 = 1 处 `nav_tab_` 标签（三颗 tab 共用一个 `forEachIndexed`，与改前相同） |
| `CompactChromeGuardTest.bottomBarIsGoneOnShortViewports` | 同上（改扫 `AppChrome.kt`） | `if (!shortChrome)` 那道闸门跟着底栏一起搬进 `AppChrome.kt` | **没有**：`ungatedBottomBar` 判据原样；`NavigationBar(` 在本文件里仍然只出现一次且有闸 |
| `PerfSourceGuardTest.everyBatchActionEndsWithSpokenConclusion` | `File("…/MainActivity.kt")` → `File("…/state/BatchActions.kt")` | 五个 `batch*` 函数搬到 `BatchActions.kt` | **没有**：函数名、`batchNote = xxxNote(` 判据、`consentNote(` 那一条全部原样 |
| `PerfSourceGuardTest.attemptCountProgress` | 无（扫全 `src/main/java/com/suipai/app`，新文件自动被覆盖） | — | 覆盖上限 `checked >= 3` 仍满足（`BatchActions` 里有两个 `forEach`、`BatchYishun` 一个） |
| 其余守卫 | 无 | `horizontalScroll` / `@Immutable` / `preferenceKeys` / `AppBarRow` 等扫的是全目录或 `ui/**`，`state/` 与 `nav/` 不在新增违规之列 | — |

> **口径**（scout-tests §B.3）：守卫红先分两类——
> **读法失效**（正则找不到目标 → 改路径/写法，让判据重新看得见）与
> **真违规**（新代码确实破坏了不变量 → 改代码，不许改判据）。
> 本轮 4 处全部是第一类。

---

## 九、已知行为差异（**零变化声明之外，必须交代的**）

严格说下面几条不是"新引入的"，而是改前就**不一致**的两处，重构时**照抄了改前行为**，
并把差异记在这里，留给下一轮拍板：

1. **分屏重建时的选择——已修，但装机未验。** 改前 `selectedIds` 是 `rememberSaveable`
   （带 `LongSetSaver`），而 `photos` 是普通 `remember`：重建后选择集合恢复了、照片列表是空的 →
   用户看到"选中了 N 张但屏幕是空的"。上一轮 `AppState` 整体是 `remember`，于是重建后**两者都空**
   （内部一致，但"重建丢选择"还在）。
   **本轮改了**：新增 `state/SelectionState.kt`，`MainActivity` 用
   `rememberSaveable(saver = SelectionState.Saver) { SelectionState() }` 持有它，
   `AppState.selectedIds` 变成转发属性（8 个写入点零改动）。于是重建后选择集合会回来、
   `photos` 仍要靠已有的 `ON_RESUME → refresh()` 重读 —— 所以**恢复后那一帧会闪
   「已选 N 张 · 0 B」**，这是已知且接受的取舍（Bundle 里不存照片）。
   `MAX_SAVED = 5000`，**超过就整体不存**（截断会让"已选 5000 张"变成一个错误的真值）。
   证据是 JVM 的 `SelectionStateTest`（6 条），**分屏 / 进程重建的装机验证没做**。
2. **`batchNote` 的 4.5 秒自动消失**（改前是 `LaunchedEffect(batchNote) { delay(4500) }`）
   保持原样：`AppNavHost` 里同一个 effect。重构没有把它挪进 `AppState`（那样会在
   非 Composition 上下文里起协程）。

---

## 十、TODO（下一轮）

- [x] ~~`AppState` 的 `selectedIds` 进 Saver（见 §九.1），并补一条"重建后选择还在"的单测。~~
      **已完成（代码 + JVM 单测 `SelectionStateTest`；装机未验）**，见 §九.1。
- [x] ~~`AppState.refresh()` 目前每次 `ON_RESUME` 全量重读四份清单；考虑按 `albumId` 只读一份。~~
      **已完成（代码 + JVM 单测 `RefreshGateTest`；装机未验）**：`state/RefreshGate.kt` 纯函数 +
      `MediaRepository.generation()` + `loadByIds` 实例复用，`refresh(force = Boolean = false)`。
      **刻意没做「按 `albumId` 只读一份」** —— `photos` 是搜索 / 收藏夹详情 / 回忆 / 幻灯片 /
      页眉「最近一张」的共同数据源，只读当前屏会让退页后数据陈旧。
- [ ] `ChromeState` 与 `AppRoute` 的"关于抽屉"应该变成路由（现在是 `aboutOpen` 布尔）——
      它为 0 层，所以没有返回键问题；将来若被更多入口复用，改成路由。
- [ ] 新增界面的路由预留位：`Settings` / `Trash` / `Favorites` / `Memories` / `Search` /
      `Slideshow` / `Collage` / `Editor` —— **本轮不加**（加空路由等于给未验证能力开口子），
      按 §六 的 5 步模板逐个接。
- [ ] `NavPolicy.resolveBack` 的第 3 档把"一瞬/相机/查看器/相册内页"合成了"退一层"；
      将来若有"同一层内两个可退阶段"（如查看器的折叠态），需要在 `Intercepts` 里加一档。

---

## 十一、第二轮：9 个新屏幕接入导航（本轮）

上一轮把架构做成了"加一屏只动一个分支"。这一轮就是**验证那句话**：9 个已经写好、
已测绿、但一条路由都没接的屏幕，全部接进来（0.6.0 之后用户第一次能在 App 里看到它们）。

### 11.1 新增的 14 条路由

| 路由（编码） | 屏幕 | 数据来源 | 备注 |
| --- | --- | --- | --- |
| `settings` | `SettingsScreen` | `SettingsStore.load()` + 只读值映射 | 总目录 + 搜索 |
| `settings.appearance` | `AppearanceScreen` | 同上 | 四项档位 |
| `settings.storage` | `StorageScreen` | `storageNumbers()` + 清除结果 | 清缓存只删 `cacheDir` |
| `settings.about` | `AboutScreen` | 只读值 + `OnboardingGate.statusText` | 可重看引导 |
| `privacy` | `PrivacyScreen` | `SettingsSnapshot.forExport()` | 导出走剪贴板唯一出口 |
| `onboarding` | `OnboardingScreen` | `OnboardingGate` | 首启自动进 |
| `trash` | `TrashScreen` | `Trash.list()` | 30 天候车室 |
| `collections` | `CollectionsScreen` | `Collections.list()` + 封面 | 封面按第一张取 |
| `collection:<id>:<名称>` | `GalleryScreen`（复用） | `Collections.byId` + `state.photos` | 与相册内页同构 |
| `search` / `search:<query>` | `SearchScreen` | `SearchIndex` + `SearchHistory` | 200 ms 防抖 |
| `memories` | `MemoriesScreen` | `Memories.build(photos, now)` | 纯函数聚合 |
| `memory:<id>` | `MemoryDetailScreen` | 同上（按 id 找） | 找不到直接退 |
| `collage[:ids]` / `collage:~<count>` | `CollageScreen` | 选中集 / 当前可见集合 | 超 24 个只存 count |
| `slideshow:<startIndex>` | `SlideshowScreen` | 当前可见集合 | 列表不进路由 |

**编码规则与既有那一族完全一致**：`:` 分隔、字段用 `RouteCodec` 转义、
无参数目的地用 `data object`、有参数用 `data class` + 自带 `decode`。
`AppRouteTest` 里每条都有往返用例（含中文/冒号/百分号/换行）。

### 11.2 入口：页眉的「更多」抽屉（不新增工具条行）

`AppChrome.AppHeader` 在 `theme_toggle` **之后**加一颗「更多」，打开一个 `AppSheet`，
分三组：

- **看照片**：搜索 / 回忆 / 收藏夹
- **整理**：回收站 / 拼图 / 幻灯片
- **设置**：设置 / 隐私与数据

三条设计约束：

1. **不横向滚动**（`PerfSourceGuardTest.noHorizontalScrollOnActionRows` 禁止）——
   用 `AppChipFlow`（`ui/common/AppBar.kt` 的 `FlowRow` 包装）。
2. **不新增工具条行**：工具条只许一行，8 个入口全部收进抽屉。
3. **抽屉开合是页眉的局部状态**（`var moreOpen by remember`），**不进 `ChromeState`**：
   它不跨屏、不落盘、也不需要重建后恢复（打开 app 第一眼不该是盖着照片的抽屉）。
   `ChromeState` 只放真正要跨屏/落盘的东西（暗房/日光、关于抽屉）。

### 11.3 删除接管回收站（行为变化，必须记账）

这是本轮**唯一一处用户可见的行为变化**，也是产品要求的：

| 路径 | 改前 | 改后 |
| --- | --- | --- |
| 批量删除 · 本 app 照片 | `deleteOne` 直接销毁 | **先 `Trash.moveToTrash`**（30 天可恢复）；搬不动才退回真删 |
| 批量删除 · 外来照片 | 系统授权框（`NEEDS_CONSENT`） | **不变** |
| 查看器单删 · 本 app 照片 | `deleteOne` 直接销毁 | 同上先搬回收站 |
| 查看器单删 · 外来照片 | 系统授权框 | **不变** |

三条既有语义**一个字都没动**（守卫 `PerfSourceGuardTest:932` / `attemptCountProgress` 仍绿）：

1. **进度按真实成功数报**：搬进回收站成功也算"删掉了"（系统相册里确实看不见了），
   计入 `deleted`；搬失败退回真删，仍失败才 `refused++`。
2. **结论走 `batchNote`**（`deleteNote` / `consentNote`），不留在进度位。
3. **`NonCancellable` 收尾 + `awaiting` 授权框那段逻辑**原样保留。

判据 `AppState.isOwnPhoto()`：文件名前缀 `SuiPai_`。为什么不用目录：读回来的
`bucketName` 是系统给的显示名（国产 ROM 上不一定是 "SuiPai"），而前缀是我们写进去的。
**判错的代价不对称**：误判成"自己的"→ 搬失败后退回真删（只丢可恢复性）；
误判成"外来的"→ 多一次授权确认（不丢东西）。所以宁可宽。

### 11.4 启动时的两件事（`MainActivity` 的 `LaunchedEffect(Unit)`）

1. `Trash(context).purgeExpired(now)`：30 天过期清理。**失败静默但 `Log.w`**——
   它只是顺手清，失败不该弹窗；但没日志就等于没跑。
2. `OnboardingGate.shouldShow(store.load())` 为真 → `nav.push(AppRoute.Onboarding)`。
   判定是纯函数（`onboarded && onboardingVersion >= CONTENT_VERSION`），
   所以"连读三次同一个答案"在 `OnboardingTest` 里已钉死。

### 11.5 返回键：为什么新页面不用改 `NavPolicy`

加这 14 条路由**只改了一个地方**：`Intercepts` 增加一个 `hasLayer: Boolean` 字段。

原因：`resolveBack` 早就是"先批量、再清选择、再退一层、最后交系统"，
**退一层用的是 `stackDepth`，它不认识路由类型**。缺的只是 `intercepts()`（决定
`BackHandler(enabled = …)`）—— 它原来只认六个显式布尔，新页面没有布尔，
于是 `enabled=false`，按返回会把 Activity 结束掉。

补 `hasLayer = stack.depth > 0` 之后：任何压在 tab 上的路由都自动被拦。
**这是"新屏幕接入 5 步模板"第 3 步的正确答案**：优先只靠 `hasLayer`；
只有当这一屏**内部还有一个可退阶段**（查看器删到空、批量进行中）才需要新字段。
`AppRouteTest.secondaryPagesCanBePoppedByBack` 逐条钉住 14 个页面。

### 11.6 仓库实例（不在每次重组里 new）

`SecondaryScreenHost` 里 `remember(context)` 各建一次：
`SettingsStore.of(context)` / `Trash(context)` / `Collections(context)` /
`SearchIndex(context)` / `SearchHistory(context)`。

`AppState` 自己也懒建一个 `Trash`（删除路径用）——**必须是同一个实例**：
`Trash` 的 `lock` 保护的是同一份 `trash.json`，每次 new 一个就各锁各的。

### 11.7 本轮还没做的（诚实清单）

- **`SearchIndex` 的冷启动预热**：现在是在搜索页第一次打开时 `load()`，没有就 `rebuild()`。
  `SEARCH.md §7` 建议挪到冷启动后台 —— 那要动 `MainActivity` 的启动序列，
  而本轮 `MainActivity` 已经被限定只改两处，所以留着。
- **`Collections` 的"加入收藏夹"入口**：~~`CollectionsScreen` 能建/改名/删/看，
  但"把当前选中加进某个夹"还没有 UI 入口（`Collections.addPhotos` 已就绪）。~~
  **2026-10-29 已接**：`CollectionsScreen(... selectedCount, onAddPhotos, note)` +
  `EditTarget.AddingToExisting` 第四态；那颗格子是 `collections_add_selected`
  （`selectedCount == 0 || collections.isEmpty()` 时灰着）。`AppNavHost` 里
  `collections.addPhotos(id, picked)` 之后会写一句结论到 `collectionsNote`，
  这一项现在**有读者**了。
  本轮之前 `onCreate` 用的是"当前选中集"，所以**选中照片 → 更多 → 收藏夹 → 新建**那条路一直是通的。
- **`Collage` 的选中集来源**：从页眉进时用 `state.selectedIds`；未选中时进的是空拼图
  （屏幕自己会说"至少选一张"）。
- **幻灯片 / 拼图的重建**：照片列表不进路由，重建后为空就退回下层 —— 与查看器同一条规则。

---

## 十二、第三轮：最后三个屏幕接入（收口轮）

上一轮接了 14 条路由，这一轮补齐最后 3 个"有页面、无调用点"的屏幕。
**2026-10-29 收口**：`RecipeCard` 的入口也接上了（本轮之前它是这一节里唯一还挂着的那个），
所以这一节标题里的「最后三个」现在**全部接通**。另外两个入口（投屏 / 收藏夹加照片）
不在这一节的四张路由表里 —— 它们不新增路由，只是把已有能力接到已有屏幕上。

### 12.1 新增 4 条路由

| 路由（编码） | 屏幕 | 入口 | 备注 |
| --- | --- | --- | --- |
| `duplicates` | `DuplicatesScreen` | 页眉「更多 › 整理」 | 扫全库、按组给"留哪张"建议 |
| `storage.insight` | `StorageInsightScreen` | 页眉「更多 › 整理」 | 尺寸档 / 直方图 / 最大若干张 / 可回收建议 |
| `recipeCard:<uri>:<lookId>:<intensity>` | `RecipeCardScreen` | **已接**：一瞬 ›「更多」› 导出 ›「配方卡」（`yishun_recipe_card`） | 三个参数全部进路由；抽屉里那颗在 `onRecipeCard == null` 或没有预览时是灰的 |
| （`settings.storage` 已在上一轮） | — | — | 本轮**没有**新增，`storage.insight` 与它是两个页面 |

**`storage.insight` 的 family 特意不叫 `settings.storage`**：后者已经是「设置 › 存储」
（清缓存那一屏）。family 撞了 `push` 会互相顶掉 —— `AppRouteTest` 里有一条专门钉这个。

**`RecipeCard.intensity` 是 `Float`，而路由编码只处理 `String`**：
编 `toString()`、解 `toFloatOrNull()`，解不出来 / `NaN` / `Infinity` 一律返回 null
（与 `collage:abc` 同一个口径：宁可丢掉这一层，不猜一个默认强度画一张假卡）。
越界值**不**作废，夹回 `0..1` —— 强度是连续量，不是身份标识。

### 12.2 一条路由格式的边界（踩过一次，写清）

`RecipeCard` 用 `:` 分段，而 uri 里天然带 `://`。所以：

- **只有 `encode()` 转义过的形式能被 `decode()` 解**（`:` → `%3A`）；
- 裸串（`"recipeCard:content://a:look:0.5"`）会多切两段 → 返回 null。

这是**设计决定，不是缺陷**：不为"让裸串也能解"去猜哪个冒号是分隔符 ——
那个歧义解不干净（`a:b:c` 是 1 段还是 2 段没有答案）。
`AppRouteTest.recipeCardRejectsUnescapedRawString` 把这条口径钉住，
`decode` 的 KDoc 也写明了。第一版测试里用裸串断言论，红了，就是这件事。

### 12.3 重复照片：删除走一个**本地**的 `ActivityResultLauncher`

`MainActivity` 的 launcher 属于"删除授权链"（`DeleteFlow` 的票据 + `consentNote`），
它回来说的是"删了几张 / 没允许"；而重复照片这里只关心"这一组删掉了没有"，
语义不同。更要紧的是「7 职责」那条：再加第 3 个 launcher 会让 `MainActivity`
重新变成什么都有的地方。所以 launcher 就地建在 `AppNavHost` 的 `DuplicatesSection` 里。

**判据必须是 `result.resultCode > 0`**：

| 返回值 | 含义 | 处理 |
| --- | --- | --- |
| `> 0`（`RESULT_OK = -1`... 实际是 `Activity.RESULT_OK`） | 真的删了 | 说话 + 重扫 + `refresh()` |
| `0` / `RESULT_CANCELED` | 没删成（**Android 11+ 对外来行静默返回 0**） | 说"取消了，没删" |

把 0 当成功就等于谎报一次删除 —— 与 `PerfSourceGuardTest.attemptCountProgress`
抓的"进度条走到 N/N 而一张都没动"是同一类错。

`onDeleteAll` 把**所有组的待删 uri 合成一次请求**（一次系统框删完），
而不是逐组弹 N 次确认 —— N 次确认对用户是灾难。

### 12.4 存储分析：`onOpenCategory` 不跳转

`StorageInsightScreen` 的 `onOpenCategory: (StorageKind) -> Unit` 原本应该"点这一档看有哪些照片"，
但我们这一轮**不新增筛选入口**（画廊的筛选链是 `GalleryScreen` 的私有状态）。
跳到一个没有筛选状态的地方，等于把用户带进一个看不出区别的页面。

所以改成：**在 note 行把这一档的张数说出来**（`"大图：12 张"`）。
这是"点了有反应、而且反应是真话"的最小实现。不假装有筛选。

**顺带修掉一个真的数据模型缺口**（`data/StorageInsight.kt`）：
`StorageSummary.byClass` 是按 `SizeClass`（尺寸档）切的，而 `onOpenCategory` 要的是
`StorageKind`（来源档：截图 / 相机 / 下载 / 一瞬成片…）。两个枚举不是一回事，
宿主拿 `byClass` 去对 `StorageKind` 编译器直接报 `Incompatible enums`，
界面上表现为"点了没反应"。

修法是在 `StorageSummary` 里**加一个并列字段** `byKind: List<Pair<StorageKind, Int>>`
（与 `byClass` 互相独立、都用 `entries` 全量），`summarize()` 里用
`storageKindOf`（与 `reclaimable()` 同一个判据函数，不另写一份）统计。
`StorageInsightTest.byKindAndByClassBothSumToCount` 钉住
`byClass 之和 == byKind 之和 == count` 且两边都是枚举全量 —— 防以后加第三个维度时漂移。

### 12.5 本轮测试增量

- `AppRouteTest` +4：分析工具往返与家族不撞、配方卡三参数往返、坏强度判废（不猜值）、裸串判废。
- `StorageInsightTest` +2：`byKind`/`byClass` 之和与 `count` 三相等 + 覆盖枚举全量；空库安全。
- `secondaryRoutesAreNotTabs` / `secondaryPagesCanBePoppedByBack` 两个清单各加 3 条。

### 12.6 还没接的（全项目收口清单）

| 缺口 | 为什么 | 谁接 |
| --- | --- | --- |
| ~~**配方卡入口**~~ | ✅ **已接（2026-10-29）**：`YishunScreen` 的「更多」›「导出」段第一颗「配方卡」（`yishun_recipe_card`）→ `onRecipeCard` 回调 → `AppNavHost` 的 `nav.push(AppRoute.RecipeCard(...))` | 装机验证仍缺（见 `ANDROID-VERIFY.md` §45.2 C1） |
| `SearchIndex` 冷启动预热 | 要动 MainActivity 启动序列 | 下一轮 |
| ~~`Collections` 的「加入现有夹」~~ | ✅ **已接（2026-10-29）**：`CollectionsScreen` 加 `selectedCount` / `onAddPhotos` / `note` 三参 + `EditTarget.AddingToExisting` 第四态，那颗格子 `collections_add_selected`；`AppNavHost` 接 `collections.addPhotos`。**原本写了没人读的 `collectionsNote` 现在真的渲染了** | 装机验证仍缺（见 `ANDROID-VERIFY.md` §45.2 C3） |
| 存储分析的"按档位筛选" | 需要画廊支持外部筛选状态 | 与 `GalleryScreen` 的筛选链一起做 |
