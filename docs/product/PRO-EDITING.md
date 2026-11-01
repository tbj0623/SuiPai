# 一瞬 · 专业编辑（曲线 / 分色 / 局部）· UI 侧接线说明

> 本文件是 `app/src/main/java/com/suipai/app/ui/yishun/pro/` 那一层的说明书：
> 三块面板的交互、性能预算、与配方 v3 的关系、以及"哪里改会炸"。
>
> **上游权威**：`docs/engine/COLOR-PRO.md` 是三组算子的唯一数学定义（插值形式、
> 复杂度、逐位一致要求）。本文**不重复数学**，只讲界面怎么把它们用起来。
> 与之平行的还有 `docs/engine/COLOR-PRO.md` §8（自定义配方的落盘与导入导出）。
>
> **需求来源**：`docs/ROADMAP.md` §2.5 —— 「一瞬里选配方/智能适配，**自定义曲线 + HSL +
> 局部径向/线性调整**，保存为自己的配方并命名」。

---

## 0. 一句话

**引擎早就能算了，缺的只是入口。** 这一层是纯接线：把 `ToneCurves` / `HslBands` /
`List<LocalMask>` 收成一个不可变值 `ProOps`，在两个地方各接一次（预览 + 导出），
在三个抽屉里各画一次（曲线 / 分色 / 蒙版），再给"存成我的配方"一条读写路径。
**所有数学、所有上限、所有降级规则都来自 `shared`，界面侧一行新数学都没有。**

```
        ui/yishun/pro/ProOps.kt          ← 三组算子的唯一持有者（不可变）
            │                    │
   ColorPipeline.preview   RecipePro.withPro
   ColorPipeline.apply     （配方 v3 / EXIF）
            │                    │
       预览 bitmap            存成我的配方
                                 │
                        data/CustomLooks（filesDir/custom_looks.json）
```

---

## 1. 入口布局：为什么顶栏只加两格

| 位置 | 格子 | 打开什么 |
|---|---|---|
| 顶栏 | `专业`（角标 = 已动几项） | `ProHubSheet`：三块面板 + 存配方 + 导入 + 全部清零 |
| 顶栏 | `存配方` | `SaveRecipeSheet`：直接命名保存当前这一组参数 |
| 筛选抽屉底部 | `我的配方` 列表 | 点一下应用，长按 → 改名 / 导出 / 删除 |
| 一瞬配方行 | 自建配方排在内置库**前面** | 点一下整条应用（含专业三组） |

**为什么不再往顶栏塞三格**：`AppBarRow` 是 `FlowRow`（`ui/common/AppBar.kt`），能换行，
但每多一格就多一份大字号下被挤扁的机会 —— 装机量到（字级 2.0，`suipai_r4`）工具条里
「筛选」只剩 15 px 宽，看不见也点不着（`AppBar.kt:180` 注释）。所以三块面板收进
**一个**入口抽屉，顶栏只多两格。

**为什么 `存配方` 独立一格**：存配方是这一屏**产出**的动作，和「保存成片」同一量级；
只藏在抽屉里等于让用户的调色成果多两层点击才存得下来。抽屉里那一份（专业 → 存配方）
保留给"刚调完顺手存"的路径，两条路走同一个 `SaveRecipeSheet`。

> 顶栏那一格「专业」的角标 = `ProOps.activeCount`：曲线算 1、分色算 1、每个蒙版算 1。
> 三条都是"中性 = 逐位不变"的（`ToneCurves.isNeutral` / `HslBands.isNeutral` / 空表），
> 所以角标不会出现"显示 3 但画面没变"。

---

## 2. 三块面板的交互

### 2.1 曲线（`pro/CurvePanel.kt` + `pro/CurvePoints.kt`）

一块 0..1 的正方形画布，**左下 = 暗部**（与直方图同向），四等分网格 + 对角线基准。

| 手势 | 行为 | 边界 |
|---|---|---|
| 点空白 | 加一个控制点 | 最多 16 个（`ToneCurve.MAX_POINTS`）；满了**不加**并保持原样 |
| 点角落附近 | 并入端点，只改端点 y | 阈值 `MIN_GAP = 0.02`；手指落不到精确 0.000 |
| 拖控制点 | 移动 | 端点**锁 x**；中间点 x 夹在左右邻点之间各留 `MIN_GAP` |
| 长按控制点 | 删掉它 | 至少留 2 点；端点不可删 |
| 通道切换 | 主 / R / G / B 四条**各存各的** | 四条曲线在引擎里是两个步骤（主曲线先、通道后） |

**为什么端点锁 x**：端点挪离 0/1 会让曲线在中暗部与高光各留一段平线，
用户拖完看到的是"两头有台阶"，而那是最常见的误操作。

**为什么通道分开**：主通道与 R/G/B 在 `ToneCurves` 里是**两步分开**的（见其类注释）。
合成一条之后，用户改主曲线会连带改掉通道曲线的斜率 —— 两条滑杆互相影响，
和"改了主曲线，红色通道怎么变了"这类 bug 报告是同一件事。

**恒等判定**：`CurvePoints.toCurve()` 对与 `IDENTITY`（两端点 0,0 / 1,1）逐点相等的列表
返回 `null`。于是"点开曲线面板但没动"不会在配方里多出 `curve` 键（保持 v2 形状）。

### 2.2 分色 HSL（`pro/HslPanel.kt` + `pro/HslModel.kt`）

8 色带 × 3 通道（色相 / 饱和 / 明度）= 24 个值，但**一屏只显示 3 根滑杆**：
先选带，再排三根。

| 项 | 取值 |
|---|---|
| 带中心角 | `HslBands.HUES` = 0 / 30 / 60 / 120 / 180 / 240 / 280 / 320 |
| 幅度 | 色相 ±30°、饱和 ±100%（相对）、明度 ±25%（引擎常量） |
| 滑杆值域 | 三根都是 `-1..1`，与引擎值域同宽 |

**版面取舍（都是踩过的）**：
- **不用横向滚动**：`PerfSourceGuardTest.noHorizontalScrollOnActionRows` 明令禁止
  `horizontalScroll(` —— 滚出视野的 Compose 节点**根本不在语义树里**，
  读屏与自动化都够不到；
- **色带选择器用 `AppChipFlow`（内部 FlowRow）**：8 格自动换行，比 8 列横排更抗大字号；
- **每根滑杆自己带 `contentDescription`**：material3 的滑杆只有写在**调用方 modifier** 上的
  语义才会落到 `sliderSemantics` 同一个节点（Slider.kt:686-695）。不写，TalkBack 到那里
  只报「滑动条」—— 全项目 9 根滑杆的这条判据由 `A11ySourceGuardTest.everySliderHasItsOwnName`
  逐字面量扫出来。

### 2.3 局部调整（`pro/MaskPanel.kt` + `pro/MaskModel.kt`）

最多 **4 个**蒙版（`LocalMaskField.MAX_MASKS`）。一行一个，点开编辑。

| 形状 | 参数 | 默认值 |
|---|---|---|
| 线性 | 角度 0..360°、起点、终点 | 90°（上→下）、0.25 / 0.75 |
| 径向 | 中心 (cx, cy)、半径 (rx, ry)、羽化、反向 | 居中、0.35、羽化 0.5 |

**新建蒙版必须带一个非中性的调整量** —— 这是本轮抓到的一个**产品级缺陷**：
`LocalMaskField` 在构造时会把 `LocalAdjust.isNeutral`（四项全 0）的蒙版**直接丢掉**
（见 `LocalMask.kt` 类注释：不产生任何像素改动，却会白算一整张权重场）。
所以如果新建出来是四项全 0，用户会看到：列表里 4 个蒙版、画面一点没变、
拖任何滑杆才有反应 —— 他只会以为"加蒙版这个按钮是坏的"。
现在的默认值是两个最常用的意图：

- 线性 → 曝光 **-0.30**（压暗天空 / 压暗背景）；
- 径向 → 曝光 **+0.30**（把主体/脸拉出来）。

判据：`ProPanelsTest.newMaskIsVisibleOnScreenImmediately`，喂一个中性壳必须红。

**形状参数全部归一化到 0..1**（相对工作缓冲，不是原图）：预览（≤1600）与成片（4096）
用的是同一套归一化场，所以局部调整在预览里看到的位置就是成片里的位置，
不需要按分辨率换算。这是 `LocalMask` 的设计前提，界面侧**不做任何分辨率换算**。

---

## 3. 接进管线：只接两次

`ProOps` 出现在两个调用点，**没有第三个**：

| 调用点 | 位置 | 传什么 |
|---|---|---|
| 预览 | `YishunScreen.recompute()` → `ColorPipeline.preview(...)` | `curves` / `hsl` / `masks` |
| 导出 | 保存按钮 → `ColorPipeline.apply(...)` | 同上，但按 `exportEdgeNow()` 全长边 |

以及两处配方读写：

| 场景 | 入口 |
|---|---|
| 应用配方 | `YishunScreen.applyRecipe()` → `ProOps.of(recipe)` |
| 存配方 | `pro.applyTo(recipeWithShifts(...))` → `RecipeExif.writeRecipe()` |

**为什么必须收成一个值**：三条线（预览 / 导出 / 配方）里只要有一条漏传，
就会出现"预览有曲线、存出去没曲线"或者"配方说没曲线、画面还带着上一条"这类
只看代码看不出来的错法。`Shifts` 当年分过家（`YishunShifts.kt` 的注释记着），
这里是同一个教训的第二次应用。

**应用配方时必须无条件 `ProOps.of(recipe)`**：v2 配方的三个字段是 `null`，
`of()` 会得到 `ProOps.NONE` —— 那正是我们要的：**上一张图残留的曲线必须被清掉**，
否则「配方说它没曲线，画面却还带着上一条的曲线」。

---

## 4. 性能预算

### 4.1 预览重算的节流

| 触发 | 节流 | 依据 |
|---|---|---|
| 强度滑杆 | `sliderTick` + `delay(160)` | 既有实现 |
| 颗粒 / 暗角滑杆 | `grainTick` + `delay(160)` | 既有实现 |
| **专业面板滑杆** | `proTick` + `delay(120)` | 本轮新增，与上面两条同一个形状 |
| 曲线拖动 | 走同一条 `proTick` | 每个拖动事件只改状态，重算被节流 |

**为什么用「tick + delay」而不是 `snapshotFlow{}.debounce()`**：这个文件里另外两条滑杆
已经用同一个形状，三处一致才不会有人只改一处。而且 `snapshotFlow` 在
`YishunScreen` 这个层级引入新的协程流，会让"拖动强度滑杆时每个指针事件都重组"
那条已经被量过的性能账变得更难推理（`YishunScreen.kt:846` 的注释记着：芯片那一列
必须 `remember`，否则每次指针事件跑一遍 5446 条）。

### 4.2 单次重算的成本

预览走 `previewEdge()` 降采样（内存档位 960 / 1280 / 1600）。按
`docs/engine/COLOR-PRO.md` §5.1 的实测口径（4096×3000 = 12.29 MPix、单线程、
预热后计时）：

| 配置 | 12.29 MPix 单线程 | 折算到 1600 长边（0.12 MPix）≈ 1/100 |
|---|---|---|
| v2 基线（无专业算子） | 1302 ms | ~13 ms |
| + 曲线（一条 S） | 1174 ms | ~12 ms |
| + 分色（2 带偏移） | 1222 ms | ~12 ms |
| + 蒙版（3 个，按行模式） | 1807 ms | ~18 ms |
| **三者全开** | **2382 ms** | **~24 ms** |

8 线程行带线性加速后，1600 长边的三者全开约 **3~4 ms** 纯调色；但整条链路还要
`getPixels`/`setPixels`（2 × 1.9 MB 拷贝）与 `Bitmap.createScaledBitmap`，
所以一帧 16 ms 内做不完 —— 这就是 120 ms 节流的来由：**~8 Hz，已经比手指快**。

> 上面的折算只是量级估算，不是本机实测（本机内存只剩 0.3 GB，起不了模拟器）。
> 真正开面板前的实测口径照 `COLOR-PRO.md` §5.1。

### 4.3 内存

| 项 | 中性时 | 全开时 |
|---|---|---|
| 曲线表 | 0 字节 | 3 × 256 × 4 = 3 KB |
| 蒙版权重场 | 0 字节 | 预览整场模式 ≤ 1.5 MB；成片按行 `4 × w × 4` = 64 KB |
| 分色加权表 | 0 字节 | 3 × 360 × 4 = 4.3 KB |

**中性时一次分支都不进**（`EngineParams` 构造期的守卫），所以"没开专业面板"的
预览与 v2 **逐位相同**、开销也相同。这条由 `shared` 的
`ProPipelineIdentityTest.neutralProOperatorsLeavePixelsBitIdentical` 钉着。

---

## 5. 保存成"我的配方"

### 5.1 数据结构

`CustomLook`（`data/CustomLooks.kt`）是 `YishunRecipe` 的**超集**：
v2 的 14 个字段 + 专业三组 + 名字 / 创建时间 / 修改时间。

```
CustomLook
├── id            "user_<时间戳>"
├── name          "我的黄昏"
├── baseLookId    "daylight"
├── intensity     0.72
├── temp/contrast/sat/shadow/fade/highlight/soft   ← 7 根微调
├── curve / hsl / masks                            ← 专业三组（v3）
├── created / modified
└── asRecipe() → YishunRecipe（v3，写 EXIF 用）
```

**落盘位置**：`filesDir/custom_looks.json`，**不进 SharedPreferences**
（理由见 `CustomLooks` 类注释：几 KB 的嵌套结构，塞 prefs 会让整文件在每次
收藏/隐藏变更时被重写；而且 prefs 要备份，配方里的蒙版参数不该默认上云）。
两个备份规则文件（`backup_rules.xml` / `data_extraction_rules.xml`）都排除了它。

### 5.2 导入导出

| 方向 | 格式 | 入口 |
|---|---|---|
| 导出 | `SPLOOK1:` + Base64Url(JSON, 无 padding) → 剪贴板 | 长按配方 → 导出 |
| 导入 | 同上，从剪贴板/手粘 | 专业抽屉 / 筛选抽屉 → 导入 |

**与 `SPRECIPE1:`（照片 EXIF 配方）刻意分开前缀**：两者语义不同 ——
配方是"怎么调这张照片"，自定义配方还带名字与时间。混用前缀会让导入方拿到
一条能解但缺字段的 payload，然后在 UI 上显示成一条空名字配方。

**导入失败一定说人话**：`CustomLooks.importText` 返回 `null` 就说明这段文本不是配方，
面板显示「这段文本里没有可识别的配方（应该是 SPLOOK1: 开头的一串字符）」，
不猜、不崩。坏输入清单（空串 / 半截 base64 / 截断 JSON / 别的模块前缀）
由 `CustomLooksTest.corruptedImportTextReturnsNullAndNeverThrows` 穷举。

**剪贴板只许一个写入口**：`ui/common/Clip.kt` 的 `clipWrite`。
`PerfSourceGuardTest.clipboardHasExactlyOneWriter` 扫全仓，绕开它会红。
导出成功与否按返回值说话（`exportRecipeText` 返回一句人话），
不出现"复制失败但界面说已复制"。

### 5.3 名字与重名

- 留空 → `defaultRecipeName()` 给「我的 + 基础配方名」（例：「我的日光」）；
- **重名不拦，但要说出来**：抽屉里提示「已经有一条同名配方；保存会新增一条，不会覆盖旧的」。
  拦下来反而会让用户以为保存失败；
- 改名走 `CustomLooks.rename()`（它负责刷新 `modified`，列表按修改时间倒序）。

---

## 6. 与既有守卫的关系（改这一层之前先读）

| 守卫 | 位置 | 对本层的约束 |
|---|---|---|
| `noHorizontalScrollOnActionRows` | `PerfSourceGuardTest` | 面板里不许出现横向滚动（用 `FlowRow` / 分页） |
| `everySliderHasItsOwnName` | `A11ySourceGuardTest` | **每一句 `Slider(` 括号内**必须有 `contentDescription`；注释里也不能出现那个调用形状（守卫不裁块注释） |
| `everyPressActionIsNamed` | `PerfSourceGuardTest` | `combinedClickable` 必须有 `onClickLabel`；有长按就必须有 `onLongClickLabel` |
| `silentCellReturnsMustBeGreyedOut` | `PerfSourceGuardTest` | `?: return@AppBarCell` 所在那次调用必须有 `enabled =` |
| `libraryScansAreRemembered` | `PerfSourceGuardTest` | `LookLibrary.all` 的遍历必须在 `remember` 里（自建配方列表已进 `chips` 的 key） |
| `yishunBitmapBagIsNotUriScoped` | `PerfSourceGuardTest` | 位图袋不许按 uri 建/收（本轮没动 `BitmapBag`） |
| `clipboardHasExactlyOneWriter` | `PerfSourceGuardTest` | 导入导出走 `clipWrite` |
| `FlagTableGuardTest.prefKeysAreGloballyUnique` | `FlagTableGuardTest` | 本轮**没有新增落盘键**（专业三组跟着配方走，不进 prefs） |

> 一条踩过的坑：「`ShapeSlider` 这个封装名里含 `Slider(` 子串」，于是守卫把 9 处调用点
> 全当成「无名 Slider」判红。改名为 `ShapeKnob` 后消失。
> **给滑杆封装起名时不要以 `Slider` 结尾。**

---

## 7. 判据清单（`app/src/test/java/com/suipai/app/ui/yishun/ProPanelsTest.kt`，33 条）

| 组 | 钉住的约定 |
|---|---|
| 曲线加点 | 排序插入、返回正确下标、满 16 不加、贴端点并入端点而非新增 |
| 曲线删点 | 至少留 2 点、端点不可删、越界不抛 |
| 曲线拖动 | 端点锁 x、中间点夹在邻点之间、邻点过近时不产生反区间 |
| 曲线命中 | 最近点 + 半径阈值（放宽必须命中） |
| 曲线通道 | 改一条不动其余三条、恒等写 null |
| HSL | 8 带顺序与引擎一致、只动一个带、越界与 NaN 都夹回、摘要点名字 |
| 蒙版 | 上限 4、新建**必须非中性**、半径/羽化/中心夹回、end > start、下标安全 |
| ProOps | 中性判定驱动管线守卫、v3 往返、v2 还原成中性、中性不写出键 |
| 自定义配方 | 全字段往返、未知 base 返回 null（不静默顶替 daylight）、空名兜底、角标如实、蒙版 4 上限穿过配方边界、两种形状各存各的 |
| 降级 | 奇数长度曲线 / 短 HSL / 未知 mask kind 都退化成中性且不抛 |

每条都配了「喂坏例子必须变红」的自检 —— 照 `COLOR-PRO.md` §9 的纪律：
先断言正常路径，再喂一个必然违反它的输入并断言这次必须失败。

---

## 8. 已知未做（下一轮）

| 项 | 为什么现在不做 |
|---|---|
| 曲线控制点的数值输入框 | 曲线是空间概念，但读屏用户只能靠"3 个控制点"那句话 —— 真要无障碍得给一组 x/y 输入，是独立一块活 |
| HSL 的 8 带渐变预览条 | 现在只画了 8 个色块 + 中心角；画成连续渐变需要一张宽度自适应的小 Canvas，收益小于成本 |
| 蒙版在预览图上的**可视化手柄** | 现在只能拖滑杆调位置。真正的"图上拖圈"要接管预览区的全部手势（与现有的按住对比 / 双击满强度 / 上下滑强度 / 左右滑换配方四条冲突），必须整体重排手势优先级 |
| 蒙版叠加顺序可视化 | 引擎按列表顺序叠加，界面只是按顺序列出来，没有拖拽排序 |
| 曲线/HSL/蒙版进 `AiSheet` 的推荐 | AI 目前只给 look + 7 偏移，不给专业三组；扩展 prompt 是另一条线 |
| iOS 侧面板 | `shared` 侧的 `YishunRecipe` v3 已就绪，Swift 侧 UI 未接 |
