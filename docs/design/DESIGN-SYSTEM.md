# 随拍 · 设计系统 v1（暗房接触印样）

> 世界契约见 `.impeccable/surfaces/app-src-main-java-com-suipai-app-mainactivity-kt.md`。
> 本文是**实现层**的手册：每个界面换皮、加界面、加组件之前先读这一份。
> 代码入口：`app/src/main/java/com/suipai/app/ui/design/`。

---

## 0. 一句话

**整个界面是摄影师在暗房里翻看一页接触印样**：照片是相纸，界面是印样页的框线与编号。
深灰绿暗房底、相纸白字、琥珀 safelight 是**唯一**强调色、近直角、等宽编号。

这一版要解决的**核心问题**不是"不好看"，是**层级扁平**：

> 改之前，工具条格子、抽屉格子、预设芯片、空态按钮**全是同一种 2dp 方框**。
> 后果：照片不再是主角（屏上最有力的形状是四十个一样的框）；用户点哪儿都一样
> （主操作没有比次操作更显眼）；危险操作和普通操作长得一样，只能靠读字分辨。

所以 v1 的第一性原则是：**每个语义角色有自己的形态语言，但它们都还在同一个世界里**。

---

## 1. 层级规则（最重要的一节）

**先定角色，再选形态。** 不许"先在手上找一个能点的组件，再想它是什么角色"。

| 角色 | 形态 | 组件 | 一屏允许几个 | 例子 |
|---|---|---|---|---|
| **主操作** | 琥珀**实心**块，近直角 2dp | `PrimaryButton` | **至多 1 个** | 保存、完成、开始冲印 |
| **次操作** | **无底色**、琥珀字 + 一道极淡下划线 | `TextButtonTonal` | 2–4 个 | 还原、随机、复制信息 |
| **第三级动作** | 1dp 描边格子（38dp 视觉 / 48dp 命中） | `AppBarCell`（旧，保留） | 工具条里若干 | 按日、最新、适应、旋转 |
| **筛选** | **胶囊**、选中 = 琥珀洗色 + 琥珀描边 | `PillChip` | 一组 | 日期、时段、比例、只看 |
| **危险** | 定影红**实心**（白字）或红字 | `PrimaryButton(danger = true)` / `fixerInk` 文字 | 1 个，且必须二次确认 | 删除、清空 |
| **导航** | 底栏三 tab / 页首一行 | 不在设计包内（`MainActivity`） | 全局 1 处 | 照片 / 相册 / 互通 |
| **空态** | 一条漏光 + 等宽编号 + 一句话 | `EmptyState` | 整屏 1 个 | 相册空了 |

### 为什么主操作必须是实心

**全屏只允许一个实心块。** 这句话就是层级的全部秘密：
其它一切（格子、芯片、次操作、导航）都只有描边，于是"琥珀实心"自动变成
"这一步该按的那一颗"。反过来说，只要出现第二个实心块，这条规则立刻失效 ——
两个实心并排，用户就得读字来决定，那和不分层是一样的。

### 为什么筛选芯片不同于工具条格子

它们**长得像**（都是小控件），但语义完全不同：

- `AppBarCell` = 「一个动作」。点了就发生，有一个开/关状态。
- `PillChip` = 「一个筛选条件」。可以叠加，是**一组**里的一个。

旧界面把两者写成同一个东西，于是筛选看起来像"又一组按钮"，用户不知道
这一排是可以叠加的。胶囊 + 洗色底 + 可以多选，是这个区别的视觉表达。

### 危险操作为什么用"定影红"而不许用琥珀

定影红 `fixer` 不是第二支强调色，它是**危险语义**，只出现在：
删除 / 清空 / 覆盖原图 / 断开连接。
判据（`ContrastGuardTest.semanticRolesStayInTheirLane` 钉住）：
`fixer != safelight`，任何主题下都不许相等 —— 否则"删除"看起来像"保存"。

---

## 2. 颜色 token

> 两套主题**成对**（键集合逐字相同，顺序也相同），由 `TokenPairGuardTest` 钉住。
> 加一支颜色必须两边都加，否则守卫立刻红。

### 面（surface）

| token | 暗房（深色） | 日光印样（浅色） | 语义 |
|---|---|---|---|
| `canvas` | `#101413` | `#F2EFE8` | 页面底 |
| `canvasSunken` | `#0A0D0C` | `#E7E3D9` | 最深一层（查看器外沿、区块底） |
| `panel` | `#161B1A` | `#FAF8F3` | 弹层 / 抽屉 |
| `panelRaised` | `#1E2422` | `#FFFFFF` | 浮起面（芯片底、卡面） |
| `panelOverlay` | `#202624` | `#EFEBE2` | 叠在面板上的又一层 |
| `selectionSurface` | `#2B291E` | `#EFE8DD` | 被油笔圈住的那一格底 |

### 字（ink）

| token | 暗房 | 日光 | 语义 | 对比度（对 canvas） |
|---|---|---|---|---|
| `ink` | `#EDEBE4` | `#1A1E1C` | 主文字 | 15.56 : 1 / 14.67 : 1 |
| `inkSecondary` | `#9AA39E` | `#5C625E` | 次级（说明、副标题） | 7.16 : 1 / 5.44 : 1 |
| `inkTertiary` | `#929B96` | `#5B615B` | 三级（编号、元数据、脚注） | 5.58 : 1 / 5.21 : 1 |
| `inkInverse` | `#101413` | `#F2EFE8` | 反相面上的字 | — |

### 线（line）

| token | 暗房 | 日光 | 语义 | 约束 |
|---|---|---|---|---|
| `hairline` | `#2A312E` | `#D8D4C9` | **装饰**发丝线 | 不承担可点性指示，无需 3:1 |
| `outline` | `#6B7570` | `#8A857A` | **交互**描边 | 对 canvas/panel/panelRaised 全部 ≥3:1 |

**这两个不许混用。** 格子的框承担"这里能点"，必须 ≥3:1（WCAG 1.4.11）；
装饰分隔线可以更淡。把 `hairline` 拿去做格子框 = 那颗按钮看起来不可点。

### 琥珀 safelight（唯一强调色）

| token | 暗房 | 日光 | 语义 |
|---|---|---|---|
| `safelight` | `#E8A33D` | `#855416` | 主强调：描边、图标、强调文字、主按钮底 |
| `safelightPressed` | `#D08F2E` | `#6E4512` | 按下的琥珀 |
| `safelightWash` | `#3B2A0E` | `#EBD9B8` | 琥珀洗色容器（徽标底、芯片选中底） |
| `inkOnSafelight` | `#101413` | `#FAF8F3` | 画在琥珀实心上的字（8.61 : 1 / 6.04 : 1） |
| `inkOnSafelightWash` | `#E8A33D` | `#4A3413` | 画在琥珀洗色上的字（6.39 : 1 / 8.46 : 1） |
| `grease` | `#E8A33D` | `#855416` | 油笔圈选。**必须等于 `safelight`** |

> 浅色主题的琥珀是 `#855416` 而不是原色：`#E8A33D` 在纸白上只有 **1.9:1**，
> 任何尺寸都不合格。这是"浅色不是反色"的最直接证据。

### 定影红 fixer（危险，不是强调）

| token | 暗房 | 日光 | 语义 | 约束 |
|---|---|---|---|---|
| `fixer` | `#C4453B` | `#A83028` | **仅装饰**（危险区的边、图标底） | 3.77 : 1，**不许当文字** |
| `fixerInk` | `#E5887E` | `#9E2E26` | 危险**文字**/图标 | 7.21 : 1 / 6.37 : 1 |
| `fixerWash` | `#3A1512` | `#F6DAD7` | 危险洗色容器 | — |
| `inkOnFixerWash` | `#E58D83` | `#8A231C` | 洗色上的字 | 6.54 : 1 / 5.54 : 1 |
| `dangerFill` | `#B33A31` | `#A83028` | 危险实心按钮 | — |
| `inkOnDangerFill` | `#EDEBE4` | `#FAF8F3` | 实心上的字 | 4.94 : 1 / 6.34 : 1 |

> **`fixer` 与 `fixerInk` 是两件事**：本体是给色块用的（3.77:1 画小字读不清），
> 提亮过的 `fixerInk` 才是给文字用的。混用是这套系统里最容易犯的错。

### 渐变 / 遮罩

| token | 暗房 | 日光 | 语义 |
|---|---|---|---|
| `leakWarm` | `#2EE8A33D` | `#1FA96F16` | 胶片漏光起点（琥珀 + alpha） |
| `leakEdge` | `#00E8A33D` | `#00A96F16` | 漏光终点（全透明） |
| `scrim` | `#B3000000` | `#66000000` | 弹层背后的压暗 |

**漏光是这个世界唯一允许的渐变**，且只用在**没有照片的地方**（空态、加载占位、进度底）。
照片后面永远不用 —— 那会让画面变成一张加了滤镜的假照片。

---

## 3. 字级

两族声音：**正文声**（系统黑体）与**编号声**（等宽）。

### 正文声

行高倍数：正文族 **1.6**、标题族 **1.45–1.5**、大字族 **1.32**。全部 ≥ 1.32。

| style | 字号 | 行高 | 字重 | 用途 |
|---|---|---|---|---|
| `displayLarge` | 48sp | 63.4sp | Black | 首启页主标题、相机倒计时 |
| `displayMedium` | 36sp | 47.5sp | Black | 空态主标题 |
| `displaySmall` | 30sp | 39.6sp | Black | 整屏只有一个的数 |
| `headlineLarge` | 26sp | 34.3sp | Black | 页面主标题 |
| `headlineMedium` | 22sp | 30.8sp | Black | 页首（旧 `pageHead`） |
| `headlineSmall` | 19sp | 26.6sp | Bold | 分区大标题 |
| `titleLarge` | 20sp | 29sp | Bold | 弹层标题 |
| `titleMedium` | 17sp | 25.5sp | Bold | 卡片标题 |
| `titleSmall` | 15sp | 22.5sp | Bold | 小节标题 |
| `bodyLarge` | 16sp | 25.6sp | Normal | 正文（长段落） |
| `bodyMedium` | 14sp | 22.4sp | Normal | 正文（默认） |
| `bodySmall` | 12sp | 19.2sp | Normal | 说明、注释 |
| `labelLarge` | 14sp | 20.3sp | Bold | 按钮文字 |
| `labelMedium` | 12sp | 17.4sp | Medium | 芯片、角标 |
| `labelSmall` | 11sp | 16sp | Medium | 极小标签 |

### 编号声（等宽，**绝不作正文**）

| style | 字号 | 字距 | 用途 |
|---|---|---|---|
| `frameNo` | 11sp | 0.6sp | 片框编号：`41 / 90`、`1/3`、`1920×1440` |
| `stamp` | 12sp | 0.5sp | 日期时间：`2026.09.21 20:54` |
| `parameter` | 13sp | 0.3sp | 参数值：`48%`、`f/1.8`、`ISO 400` |
| `counter` | 28sp | 0 | 大字计数（`ValueWall`）：`5446`、`90` |
| `marker` | 11sp | 1.2sp | 等宽小标记：`DARKROOM`、`NO FRAMES` |

### 层级矩阵（守卫钉住的真规则）

整张表**不是**单调的（Material 的 title 与 body 在尺寸上本来就交叉），
真正的承诺是**每一族内部逐档递减，且同一档位下逐族递减**：

```
         Large  Medium  Small
display    48     36     30
headline   26     22     19
title      20     17     15
body       16     14     12
label      14     12     11
```

每列严格递减 → "这个字比那个字重要"在任何系统字级下都成立。

---

## 4. 间距 / 圆角 / 触摸目标

### 间距（8dp 基准，2dp 相纸缝为例外）

| token | 值 | 用途 |
|---|---|---|
| `Spacing.gutter` | 2dp | **只给**时间线网格的相纸缝 |
| `Spacing.hair` | 1dp | 发丝线宽 |
| `Spacing.xxs` | 4dp | 图标与文字之间、格子内边距 |
| `Spacing.xs` | 8dp | 同组控件之间 |
| `Spacing.sm` | 12dp | 元素之间 |
| `Spacing.md` | 16dp | 默认页面留边 |
| `Spacing.lg` | 20dp | 弹层内容留边 |
| `Spacing.xl` | 24dp | 分块之间 |
| `Spacing.xxl` | 32dp | 大分块 |
| `Spacing.xxxl` | 48dp | 空态四周留白 |

取用顺序：元素内部 → `xxs/xs/sm`；元素之间 → `sm/md`；分块之间 → `lg/xl/xxl`。

### 圆角（只有四档）

| token | 值 | 用途 |
|---|---|---|
| `Radius.none` | 0dp | 照片本身、印样网格格子 |
| `Radius.frame` | **2dp** | **本世界签名圆角**：格子、按钮、芯片描边、输入框 |
| `Radius.panel` | 8dp | 弹层顶角（只圆上面两个角） |
| `Radius.pill` | 50dp | **仅**筛选芯片 |

**只有这四档。** 4dp / 12dp / 16dp 都不存在 —— 中间档是"随便挑一个差不多"的温床，
而"到处都差一点点"正是这套界面之前失去质感的原因。

### 触摸目标

| token | 值 | 说明 |
|---|---|---|
| `Touch.min` | 48dp | 无障碍最小可点边长（**任何**可点东西的命中区） |
| `Touch.comfortable` | 56dp | 拇指舒适区（右下主操作、快门） |
| `Touch.toolRow` | 52dp | 顶部工具条一行上限（契约 ≤64dp） |
| `Touch.button` | 48dp | 主按钮视觉高度 |
| `Touch.chip` | 32dp | 筛选芯片视觉高度（命中区仍须补到 48dp） |

---

## 5. 动效

刻度表（`MotionScale.durations`，严格递增）：**90 / 120 / 160 / 220 / 260 / 420**

| 常量 | 值 | 语义 |
|---|---|---|
| `Press` | 90ms | 按压：手指**还按着**的时候就要看得见 |
| `Exit` | 120ms | 退场：同屏里让位的一方（比进场快） |
| `State` | 120ms | 状态换色不换形 |
| `Enter` | 160ms | 进场：横带、页眉、遮罩、结论句 |
| `Replace` | 160ms | **只**替换图片（文字走这条会出鬼影） |
| `Circle` | 220ms | 油笔圈选落下 |
| `Screen` | 260ms | 整屏替换（今天没有调用点） |
| `Leak` | 420ms | 漏光/空态呼吸（**不许**挂在可交互控件上） |

缓动：`Standard`（进场/状态）、`Accelerate`（退场）、`PressEasing`（按压）、
`CircleEasing`（圈选，带轻微过冲）。

> `Exit = State = 120`、`Enter = Replace = 160` 是**同一刻度两种语义**，不是重复档。
> 守卫按 `ledger` 核对：每条具名常量都必须落在刻度表上（不许悄悄加一个 137ms）。

---

## 6. 可复用 Modifier（`ui/design/Surfaces.kt`）

| Modifier | 作用 | 用法注意 |
|---|---|---|
| `paperEdge()` | 相纸边缘高光（上沿 + 左沿 1dp 渐隐） | 自己会 clip；**不要**叠第二个 clip |
| `greaseSelection(ticked)` | 油笔圈选（2dp 琥珀 + 重描） | 选中态唯一语言；**不要**再另加 border |
| `pressable(onClick,…)` | 按压缩放 1.5% + 压暗 12% | **替代** `clickable`，不要叠两个 |
| `hairline(inset)` | 横发丝线 | 装饰用，不指示可点性 |
| `hairlineVertical()` | 竖发丝线 | 工具条分组 |
| `filmGrain()` | 极淡噪点（让暗房底不塑料） | **禁止**盖在照片上（一瞬已有颗粒） |
| `paperInset()` | 相纸内边缘厚度暗示 | 与 `paperEdge` 成对 |

---

## 7. 组件（`ui/design/Components.kt`）

| 组件 | 用途 | 关键约束 |
|---|---|---|
| `SheetHeader` | 弹层/抽屉页眉（标题 + 收起） | 收起按钮带 `role=Button` 与 `onClickLabel` |
| `SectionHeader` | 弹层分组标题 | `accent=true` 一屏至多一处 |
| `PillChip` | 筛选芯片 | **不是** `AppBarCell` 的替代品 |
| `PrimaryButton` | 主操作 | 全屏至多 1 个；`danger=true` 用于删除 |
| `TextButtonTonal` | 次操作 | 无底色，靠下划线表达"这是按钮" |
| `EmptyState` | 空态 | 不放假插画、不放假按钮 |
| `ValueWall` | 参数墙（大数字 + 等宽标签） | 大数字**必须**配标签，否则是 hero-metric 模板 |
| `Divider` | 发丝线分隔 | 不是 `HorizontalDivider` |
| `ValueRow` | 键 — 值信息条 | 值用等宽 |
| `ChipFlow` | 芯片自动换行容器 | 不横向滚动 |

---

## 8. 迁移指南：`AppBar.kt` 的老组件什么时候用

| 老组件 | 保留 | 什么时候改用新组件 |
|---|---|---|
| `AppBarCell` | ✅ 工具条上的**动作格子** | 如果是"筛选条件" → 用 `PillChip` |
| `AppBarPrimary` | ⚠️ 仅工具条内的主操作 | 弹层/正文里的主操作 → 用 `PrimaryButton` |
| `AppBarRow` | ✅ 工具条容器（`FlowRow`） | 不用改 |
| `AppBarText` | ✅ 工具条内的文字 | 不用改 |
| `AppBarSheet` / `AppSheet` | ✅ 底部弹层 | 弹层内部改用 `SheetHeader` + `SectionHeader` |
| `AppSheetSection` | ⚠️ 仍可用 | 新弹层用 `SectionHeader`（有一致的 heading 语义） |
| `AppChipFlow` | ⚠️ 仍可用 | 新代码用 `ChipFlow` |
| `AppInlineSearch` | ✅ 保持 | 不用改 |

**不用改的理由**：`AppBarCell`/`AppBarPrimary` 的语义树结构是**装机量过的**
（外层管命中 + `clearAndSetSemantics`，内层只管画），四个源码守卫按它的形状钉着。
重写它等于重开一次无障碍回归。所以工具条那一条继续用它，**新形态只用于新场景**。

---

## 9. 禁止用法

1. **不许新增第二套强调色。** `safelight` 是唯一强调色；`grease` 必须与它同值。
   想要"第二种强调"时，正确的解法是**改层级**（实心 / 描边 / 无底），不是加颜色。
2. **不许用 `fixer` 当文字色。** 它是 3.77:1 的装饰红；文字一律 `fixerInk`。
3. **不许把 `hairline` 当交互描边。** 它不承担可点性指示；用 `outline`。
4. **一屏不许出现第二个琥珀实心块。** 出现第二个，主操作的地位就没了。
5. **不许为某个页面单独现编间距/圆角/字号。** 阶梯里没有的数就是错的。
   确实需要新档位 → 加进阶梯表 + 加进守卫，不要在调用点写 `RoundedCornerShape(6.dp)`。
6. **不许给 `Radius` 加中间档**（4 / 12 / 16）。这个世界的圆角只有 0 / 2 / 8 / pill。
7. **等宽字体不许作正文。** 只用于编号 / 计数 / 日期 / 参数。
8. **漏光渐变不许盖在照片上**，也不许用于任何承载信息的地方。
9. **`filmGrain()` 不许盖在照片上**（一瞬的颗粒是管线一部分，叠两层会让原图对比失真）。
10. **不许绕过 `pressable` 自写 `clickable` + 缩放**。三处各写 0.97/0.96/0.95 就会分叉。
11. **不许用 Material `Button`**：装机量到它的可点节点 `content-desc` 是空的，
    TalkBack 只说「按钮，双击激活」。设计包里的按钮都是"外层管命中、内层管画"的结构。
12. **`*Fixed` 系列 ColorScheme 槽位填不了**：本项目 material3 1.3.1 没有这组参数，
    `darkColorScheme(...)` 不接受。这一版只填 Material 实际提供的槽位。
13. **KDoc 里不许写带 `*` 的路径字面量**（例如 `ui` 加上 `/**`）—— `**/` 会提前闭合
    块注释，编译器在文件末尾报错，而 `object` 整个被吃掉，下游报一串"未解析"。

---

## 10. 守卫测试（改 token/阶梯/字级后必须跑）

```powershell
pwsh tools/abuild.ps1 :app:testDebugUnitTest --tests "com.suipai.app.ui.design.*"
```

| 文件 | 钉住什么 |
|---|---|
| `ContrastGuardTest` | 每一对「字/底」WCAG AA ≥4.5:1；交互描边 ≥3:1；禁用态 ≥2:1 但 <4.5；`grease=safelight` |
| `TokenPairGuardTest` | 两套 token 键集合与**顺序**完全一致；无重复键；`asMap()` 与注册表逐键对齐；两套确实是不同主题；语义角色不越界 |
| `ScaleGuardTest` | 间距/圆角阶梯严格递增；间距落 4dp 网格；触摸目标 ≥48dp；动效刻度有序且常量必须落表 |
| `TypeScaleGuardTest` | 每个 TextStyle 行高 ≥ 阈值（中文）；5 族 × 3 档矩阵每列严格递减；等宽不作正文 |

**每个守卫都有"喂坏例子必须变红"的自检** —— 没见过自己变红的守卫不算守卫。

---

## 11. 换皮顺序（建议）

1. **先改层级最扁的地方**：一瞬的抽屉（预设芯片 → `PillChip`）与查看器底栏。
2. **再改空态**：相册空 / 相册列表空 / 互通无设备（用 `EmptyState`）。
3. **最后改工具条**：这一步收益最小、回归风险最大（有四个源码守卫钉着），放最后。
4. 任何一步都要跑 `:app:compileDebugKotlin` + `:app:testDebugUnitTest`，
   并确认 `PerfSourceGuardTest` / `A11ySourceGuardTest` / `NavTabSemanticsGuardTest` 仍绿。
