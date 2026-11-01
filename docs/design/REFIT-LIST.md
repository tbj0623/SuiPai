# 换皮清单（设计系统 v1 落地施工图）

> **第二轮更新：A/C/D 组的 10 条已在 `ui/gallery/**`、`ui/viewer/**`、`ui/sync/**` 上真实落地。**
> 每条都标了状态：✅ 已改并编译通过 / ⏭️ 跳过（含原因）/ ⬜ 待办（不在本轮写作用域）。
>
> 排序原则：**先改层级最扁的地方**（收益最大、回归风险最小），工具条放最后
> （`PerfSourceGuardTest` / `A11ySourceGuardTest` / `NavTabSemanticsGuardTest` 三处钉着它的形状）。
>
> 每条的格式：`文件:行号 → 建议组件 → 理由`。

---

## 本轮实际改动（写作用域：gallery×2 / viewer / sync）

### ✅ #10 `GalleryScreen.kt:946-947` 多选选中态 → `greaseSelection`

**改前**：`border(3.dp, MaterialTheme.colorScheme.secondary)`。
`secondary` 在本项目里是**相纸白/墨色**，不是琥珀 —— 多选时照片被一条 3dp **白框**框住，
看起来像"被框住的异常"，而视觉契约要的是"印样上被油笔圈中的那张"。

**改后**：照片之上一层 `Box(Modifier.fillMaxSize().greaseSelection(ticked = true))`。
两个实现要点（都写进代码注释了）：
1. `greaseSelection` 是 `drawBehind`，画在内容**背后**；照片是不透明子节点会盖住它。
   所以在照片**之后**再叠一层空 Box 承载圈选。
2. 未选中 = 完全不画（`if (!ticked) this`，零开销），外层不再有 border。

### ✅ #11 角标语义化（`GalleryScreen.kt` 五处角标）

| 角标 | 改前 | 改后 |
|---|---|---|
| 「藏」 | `secondary` 实底 + `onSecondary` 字（暗房里是一块**白方块**，比照片还亮） | `safelightWash` 底 + `inkOnSafelightWash`（6.39:1） |
| 「选」 | `secondary` 实底（同上，整屏最刺眼的东西） | `safelightWash` 底 + `inkOnSafelightWash` |
| 「隐」 | `surface.copy(alpha = 0.82f)` 半透明 | `panelOverlay`（不透明） + `inkSecondary` |
| 「瞬」 | `surface.copy(alpha = 0.82f)` 半透明 | `panelOverlay`（不透明） + `safelight` 字 |
| 日期/尺寸/分辨率 | `surface.copy(alpha = 0.78f)` 半透明 + `labelSmall` | `panelOverlay` + `SuiPaiType.frameNo`（等宽，印样语义） |

### ✅ #16 圆角规范化 `GalleryScreen.kt:943`

`RoundedCornerShape(10.dp)` → `SuiPaiShapes.frame`（2dp）。
10dp 是"社交 App 头像"的圆角，开了「圆角」开关后照片像贴纸，与印样世界冲突。

### ✅ #17 位置横带不透明化 `GalleryScreen.kt:1787`

`background(MaterialTheme.colorScheme.surface)` → `background(panelOverlay)` + 下沿 `hairline()`。
底色本来就该不透明（注释里已有实测记录："被青图染成青灰"），这次补上**发丝线收边**：
不透明块与照片之间有一道边，它才从"糊了一层"变成"印样页上压的一条标签"。

### ✅ #20 浮层不透明化（`GalleryScreen.kt` 两处）

| 位置 | 改前 | 改后 |
|---|---|---|
| peek 遮罩 | `background.copy(alpha = 0.94f)` | `canvasSunken`（不透明，比页面底深一档） |
| 日期角标 | `surface.copy(alpha = 0.78f)` | `panelOverlay` |

半透明压在照片上 = 给照片加一层假滤镜，而且透出来的颜色随滚动变化。

### ✅ #8 `GalleryScreen` 两处空态 → `EmptyState`

| 场景 | kicker | 主文案 | 动作 |
|---|---|---|---|
| 首屏 `photos.isEmpty()`（正常空） | `00 / 00 FRAMES` | 「还没有照片。点快门拍一张。」 | 无 |
| 首屏（部分授权） | `PARTIAL ACCESS` | `partialNotice` 那句 | `AppBarCell("补授权")` + `TextButtonTonal("重试")` |
| 首屏（读失败） | `READ FAILED` | 「读取相册失败…」 | `TextButtonTonal("重试")` |
| 筛完没命中 | `NO MATCH` | `topEmptyHint` 那张 when 表的结论 | 无 |

**主文案优先级一个字都没改** —— `partialNotice` 压过 `emptyHint` 是判据
（四行真值表钉在 `PartialAccessTest.noticeNeverBlamesTheUsersLibrary`）。

### ✅ #6/#7 `AlbumListScreen` 两处空态 → `EmptyState`

| 场景 | kicker | 主文案 | 备注 |
|---|---|---|---|
| 一个相册都没有 | `NO ALBUMS` | 「还没有相册」 | note：「拍一张照片，系统会把它们收进相册」 |
| 有相册但筛完没命中 | `NO MATCH` | `emptyHint`（保留那张 7 分支 when 表） | note 报还有几处条件开着 |

「清掉筛选」从 `AppBarCell` → `TextButtonTonal`（它不是主操作），
放在空态下方而不是嵌在文字中间。

### ✅ #18 `AlbumListScreen.kt:424` 可点卡片描边 `hairline` → `outline`

空相册占位卡是**可点的**（整张卡 `combinedClickable`），WCAG 1.4.11 要求
"能点的边界"对背景 ≥3:1。`hairline` 只有 1.3:1，用它 = 这张卡看起来不可点。
顺带：占位底 `surfaceVariant` → `panelRaised`，图标 `onSurfaceVariant` → `inkTertiary`。

### ✅ #19 `SyncScreen.kt:315` / `:523` 可点卡片描边 `hairline` → `outline`

- `PeerCard`（整行可点）：`hairline` → `outline`，底 `surface` → `panel`。
- 对端相册缩略格子（可点）：`hairline` → `outline`，底 `surfaceVariant` → `panelRaised`。

### ✅ `SyncScreen` 空态 → `EmptyState`

`NO PEERS` + 「还没发现别的设备」+ note + `TextButtonTonal("再找一次")`。
改前是"大字 + 小字 + 一颗方框格子"，层级只有两档，和别的界面不是一个世界。

### ✅ #13 `PhotoViewerScreen.kt:515-543` 底栏分主次

**改前**：五颗完全同构的 `AppBarCell` 方框（收藏/隐藏/删除/更多/一瞬），
**「删除」和「收藏」长得一模一样**，而删除是不可逆的。

**改后**按语义角色分三层：

| 位置 | 组件 | 形态 |
|---|---|---|
| 收藏 / 隐藏 | `TextButtonTonal(tonal = 未激活)` | 无底色；激活时琥珀字，未激活时次级字 |
| 删除（非确认态） | `AppBarText` + `pressable` + `fixerInk` | **定影红字**（7.21:1），不再是琥珀 |
| 删除（确认态） | `PrimaryButton(danger = true)` | 实心 `dangerFill` + 纸白字（4.94:1） |
| 取消（确认态旁） | `TextButtonTonal(tonal = true)` | 次级字 |
| 更多 | `AppBarCell` | 保持 1dp 描边格子（工具条动作） |
| 一瞬 | `AppBarPrimary` | **全屏唯一实心主操作** |

`TextButtonTonal` 没有危险档，所以删除的非确认态用 `AppBarText`（不是裸 `Text` ——
`AppBarRow` 是 `FlowRow`，裸 `Text` 会被顶到行的上沿，`PerfSourceGuardTest` 钉着）。
**二次确认行为一个字都没改**：第一次点只把 `confirmDelete` 置真，第二次才 `onDeleted`。

---

## 跳过 / 不适用

### ⏭️ #15 相机倍率选中态
`ui/camera/**` **不在本轮写作用域**。建议后续 lane：`CameraScreen.kt` 里 `1×..5×` 那一排
把选中态换成 `greaseSelection`（现在已经是琥珀描边，只差"油笔重描"的质感）。
**注意**：相机浮层固定用暗房配色（`CameraOverlay`），不要引入日光印样的 token。

### ⏭️ A 组 #1/#2/#3/#4（`ui/yishun/**`）
不在本轮写作用域（scout-core 在改）。清单原文保留，见下方 A 组。

### ⏭️ `PhotoAccessCell` 换成 `PrimaryButton`
**主动回退**：`PartialAccessTest.settingsCellGreysOutWhenNoExitResolves` 的判据是
「每一处 `openPhotoAccessSettings(` 所属的那一次 `AppBarCell(` 里必须有 `enabled =`」，
换控件会报「读不到所属格子」。那条守卫要保的是**"做不了就自己灰"**，与外形无关。
已在代码里写注释说明为什么保持 `AppBarCell`。

---

## 待办（不在本轮写作用域，留给后续）

| # | 位置 | 建议 | 理由 |
|---|---|---|---|
| 1 | `ui/yishun/YishunScreen.kt:1114-1123` | `AppBarCell` → `PillChip` | 「参数特征」那一排是**筛选条件**（`LookFilter.entries` 可叠加），却用了动作格子。 |
| 2 | `ui/yishun/YishunScreen.kt:1095-1108` | `AppBarCell` → `PillChip` | 「冷暖」是循环档位筛选，不是动作。 |
| 3 | `ui/yishun/YishunScreen.kt:1197-1250` | `AppBarCell` → `PillChip` | 「对比原图」整组是显示选项，不是动作。 |
| 4 | `ui/yishun/YishunScreen.kt:1397-1404` | `AppBarCell` → `TextButtonTonal` | 「上一张/下一张」是导航，与"钉住/清钉"同形是错的。 |
| 5 | `ui/yishun/YishunScreen.kt:1093/1184/1467` | `AppSheetSection` → `SectionHeader` | 新分组标题带 `heading()` 语义（读屏可跳读）。 |
| 12 | `ui/yishun/YishunScreen.kt:649-655` / `945-976` | 预设选中 → `PillChip` + `greaseSelection` | 让"哪个配方是当前的"与其他界面的选中态说同一句话。 |
| 14 | `ui/yishun/YishunScreen.kt:454`/`456` | 只留一个琥珀实心 | 现在两颗实心并排（「完成」+ 保存），违反"一屏至多一个实心"。 |
| 15 | `ui/camera/CameraScreen.kt` 倍率排 | 选中态 → `greaseSelection` | 见上。 |
| 21 | `AppSheet` 的 `shape` | `RoundedCornerShape(2.dp)` → `SuiPaiShapes.sheet`(8dp 顶角) | 表达"掀起来的一页纸"。**要先装机看一眼**，2→8dp 是可见变化。 |

---

## 不建议动的地方（会踩守卫）

- **`AppBarCell` / `AppBarPrimary` / `AppBarRow` 的本体**：语义树结构是装机量过的
  （外层管命中 + `clearAndSetSemantics`，内层只管画），
  `PerfSourceGuardTest.toolbarCellsSpeakOnTheClickableNode` 与 `toolbarRowMustWrapNotStarve`
  按形状钉着。改它 = 重开一次无障碍回归。
- **`AppSheet` 的 `verticalScroll` + `weight(1f, fill = false)`**：改成别的一瞬「更多」的
  导出组会重新变成点不到。
- **`AppBarRow` 的 `FlowRow`**：改回 `Row` 会在系统字级 2.0 下把「筛选」挤成 15px。

---

## 验证记录（本轮）

```
pwsh tools/abuild.ps1 :app:compileDebugKotlin
→ BUILD SUCCESSFUL（我的 4 个文件 0 error）

pwsh tools/abuild.ps1 :app:testDebugUnitTest --tests "com.suipai.app.ui.*"
→ 299 tests, 16 failed
→ 逐条核对：**16 条全部来自他人文件**（MaskPanel / ui.play.CastIntent / Collage / ProPanels / CameraProState）
   ，我的 4 个文件相关守卫（PerfSourceGuardTest / A11ySourceGuardTest / NavTabSemanticsGuardTest /
   PartialAccessTest / ListingCountsGuardTest）全绿。
```

**修过的一次回归（记录在案）**：第一版把危险态的「删除」写成了裸 `Text`，
`PerfSourceGuardTest.toolbarTextsMustUseAppBarText` 当场变红（`PhotoViewerScreen.kt:589`）。
改成 `AppBarText` 后绿 —— 这条守卫正是"工具条里不许出现裸 Text"的那一条，
而 `AppBarRow` 就是工具条。
