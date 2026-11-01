# 可玩功能 · 拼图 / 回忆 / 幻灯片

> 本文件是 `app/src/main/java/com/suipai/app/ui/collage/`、`ui/memories/`、`ui/slideshow/`
> 三个包的说明书：交互、命名、与 `SuiPaiName` 体系的关系、性能预算。
> 与 `docs/product/SETTINGS.md`（设置项）、`docs/arch/APP-ARCH.md`（导航）平级。
>
> 三个功能都遵守同一条结构约定：**纯逻辑在 `*State.kt` / `*Layouts.kt` / `Memories.kt` 里，
> 不认识 Compose、不认识 `MediaRepository`，全部能在 JVM 单测里跑**；界面只负责画和转发回调。

---

## 0. 一句话总览

| 功能 | 入口形态 | 参数 | 落盘 |
| --- | --- | --- | --- |
| 拼图 | `CollageScreen(photos, onBack, onExport, note)` | 已选 2..9 张 | 调用方落盘（`onExport(bitmap)`） |
| 配方卡 | `RecipeCardScreen(look, intensity, onBack)` | 一条配方 + 强度 | 屏内「存到相册」/「分享」（§5.2） |
| 回忆 | `MemoriesScreen` / `MemoryDetailScreen` | 整库 + 注入时钟 | 不落盘（只读） |
| 幻灯片 | `SlideshowScreen(photos, startIndex, onExit)` | 一串照片 | 不落盘 |

三个屏都**不碰导航**：进哪、退到哪由调用方决定。这样它们能同时被"相册多选"和
"查看器"两条路复用，而 `MainActivity` / `nav/` 那一层不需要认识它们的内部。

---

## 1. 拼图

### 1.1 版面

```
┌ 工具条 ─────────────────────────────────────────────┐
│ 返回   6 张 · 九格·九宫      长边 2K   方形   [导出] │
├ 模板横条（LazyRow，12 张）──────────────────────────┤
│ ▢ ▢ ▢ ▢ ▢ ▢ →                                        │
├ 预览区（长按一格拖到另一格 = 交换）──────────────────┤
│                                                      │
├ 参数行 ─────────────────────────────────────────────┤
│ 边框 6   边距 12   圆角 0   黑底                     │
└──────────────────────────────────────────────────────┘
```

- **模板横条用 `LazyRow`，不是 `horizontalScroll`。** 这是项目守卫
  `PerfSourceGuardTest.noHorizontalScrollOnActionRows` 硬性禁止的写法：滚出视野的
  Compose 节点根本不在语义树里，读屏与自动化都够不着。
- 模板条的每一格都画出**该模板的微缩版式**（拿同一份 `slots` 画），所以"这张模板长什么样"
  不用点进去才知道。
- 当前照片数放不下的模板**灰掉而不是藏起来**（`CollageLayout.holds`）：灰着至少还能听见
  "为什么这颗点不动"。

### 1.2 拖拽换位

- 起手是**长按**（`detectDragGesturesAfterLongPress`，`detectDragGestures` 那一族）。
  不用裸 `detectDragGestures`：那样一点就开会和"选中某格"抢同一个手势。
- 命中测试用归一化坐标 `CollageLayout.hit(x, y)`，**没命中返回 -1**，不是 0 ——
  返回 0 会让"在画布外松手"变成"换到第一格"。
- 换位改的是**格位表**（`order: List<Int>`，第 i 格放第几张），不是 `photos` 本身：
  换完之后导出走的也是这张表，所以"屏幕上看到的就是导出的"。

### 1.3 参数

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| 边框 | 0 / 3 / 6 / 12 / 24 dp | 格与格之间的**缝**；相邻两格各收一半。与 §1.6 的「胶片边框」不是一回事 |
| 外边距 | 0 / 8 / 12 / 24 / 48 dp | 画布四周留白 |
| 圆角 | 0 / 6 / 12 / 24 dp | 每格四角；`clipPath` 裁剪 |
| 底色 | 黑 / 白 / 灰 / 棕 | 缝、外边距、解码失败留下的洞都是这个色 |
| 画幅 | 方 / 竖 / 横 | `aspect = 1 / 0.8 / 2` |
| 长边 | 2048 / 4096 px | 默认 2048 |
| **胶片边框** | 无 / 35mm / 120 / 拍立得 | 装裱，不改源图（§1.6） |
| **漏光** | 0 / 20 / 40 / 70 / 100 + 四角 | 强度 0 时逐像素不变（§1.7） |

越界值一律**夹回合法区间**（`CollageStyle.coerced()`），不是画出一张负宽高的图。
外边距大到吃光画布时，`cellRects` 返回空矩形，渲染器跳过这几格而不是抛
`IllegalArgumentException: width and height must be > 0`。

### 1.4 导出命名

```
SuiPai_拼图_<时间戳>.jpg
```

例：`SuiPai_拼图_1790023855356.jpg`。

规则来自 `CollageRenderer.kt` 的 `collageExportName(timestamp)`，落盘走
`MediaRepository.saveBitmap(bitmap, name, quality = 95, png = false)`
（与"一瞬导出""副本""裁切"同一处落盘口径，`Pictures/SuiPai`）。

### 1.5 与 `SuiPaiName` 体系的关系

项目的文件名判据只有一条（`GalleryScreen.kt:2021`）：

```kotlin
internal fun isSuiPaiOriginalName(name: String): Boolean =
    name.startsWith("SuiPai_") && !name.substring("SuiPai_".length).contains("_")
```

即：**`SuiPai_` 之后还有一个下划线 ⇒ 加工件，不是原片**。

- `SuiPai_1790023855356.jpg` —— 一个下划线，**原片**（相机存的）；
- `SuiPai_拼图_1790023855356.jpg` —— 两个下划线，**加工件**。

所以拼图导出天然不会被"仅随拍原片"那一档收进去，也不需要往任何排除表里加分支。
`SuiPaiNameTest` 的加工件名单里已经登记了 `SuiPai_拼图_1790023855356.jpg` 这一行，
`CollageLayoutsTest.exportNameLooksLikeADerivative` 从另一头钉住同一个事实
（"导出的名字确实有两个下划线、动作段确实是 `拼图`"）。两边一起红，才说明命名改了。

### 1.6 胶片边框（装裱，不是修改）

四种样式，进 `CollageStyle.frame`，默认 `NONE`：

| 样式 | 画什么 | 为什么这么画 |
| --- | --- | --- |
| `NONE` | 什么都不加 | 默认值。**加这个特性之前的所有调用点改都不用改，且像素逐字节不变** |
| `FILM_35MM` | 上下两条片基 + 等距齿孔，左右各一道窄片基 | 齿孔是 35mm 最好认的特征；孔数由"孔宽 + 孔距"推出而不是写死，免得不横幅上拉成邮票 |
| `FILM_120` | 四边深色宽边 + 框内一道转记线 + 四角刻线 | 120 **没有齿孔**，所以辨识度靠"边更宽 + 那道亮线 + 角上的机械刻线" |
| `POLAROID` | 三边窄白卡 + **下边宽白卡**，下方就是落款位 | 下边 = 窄边的 2.9 倍，这是拍立得最好认的那个比例 |

**口径（重要）：边框是"装裱"，不是"修改画面内容"。**

- `Pictures/SuiPai` 里的源照片**一个字节都不动**（`CollageRenderer.renderPhotos` 只在新画出的位图上动像素）；
- 边框只压在成片边缘，画幅内部的像素除了漏光之外不被改写；
- 这符合产品原则「原图永远保留」——拼图成片本来就是一份新文件
  （`SuiPai_拼图_<时间戳>.jpg`，两个下划线 ⇒ 加工件），装裱改的是这份新文件。

几何全部在 `CollagePixels.frameMarks(width, height, style)` 里按 `min(width, height)` 的比例算，
所以**预览与导出是等比的**：`CollageScreen` 的 `AdornmentOverlay` 拿同一份记号按
`previewW / outputW` 缩小再画。预览好看、导出难看这类分叉在这个结构下不会发生。

### 1.7 漏光

`CollageStyle.leak: Float`（0..1）+ `leakCorner: Int`（0 左上 / 1 右上 / 2 左下 / 3 右下）。

- **衰减是 `(1 - d/r)^2`**：一次方太均匀（像蒙了一层橙纱），三次方太快收
  （半径内一半的地方就看不见了）。平方落在中间，像光线从缝里漏进来。
- **半径** = `min(width, height) * 0.62`：0.62 刚好让对角中点还有一点点余光。
- **拉满也不糊**：`leakMaxAlpha = 0.62`，它只是"漏"，不是滤镜。
- **顺序是承重的**：先漏光、后边框。片基与卡面是不透明的实物，
  漏光只漏在画面上，不会把白卡染成橙的。

**强度 0 时逐像素不变**，而且这条在**实现层**保证而不只是数学巧合：
`CollageStyle.hasAdornments == false` 时 `adorn()` 直接返回，连一次 `getPixels` 都不发生。
`CollagePixelsTest.zeroStrengthLeavesEveryPixelUntouched` 对三种写法（默认值 / 显式 NONE /
负强度）都逐字节比对了一次。

### 1.8 装裱的像素层为什么是 `IntArray` 而不是 `Canvas`

本工程 `unitTests.isReturnDefaultValues = true`，JVM 单测里 `Bitmap.createBitmap` 直接回
null、`getPixels` 什么都不做 —— **任何"在 Bitmap 上断言像素"的测试都会绿着空跑**。

所以像素层（`ui/collage/CollagePixels.kt`）只认 `IntArray`：
生产路径用 `Bitmap.getPixels/setPixels` **按条带**（256 行）把像素取出来交给它，
测试路径直接喂自己造的数组。两条路径调的是同一批函数，不存在"测的和跑的不是一份逻辑"。
条带还有一个内存理由：2048² 的整幅 `IntArray` 是 16 MB，条带只有 2 MB —— 4K 输出时
这个差别是"会不会 OOM"。

---

## 2. 回忆

### 2.1 聚合规则（`ui/memories/Memories.kt`）

`Memories.buildFrom(shots, nowMillis, zone, maxPhotos)` 是纯函数：
**注入时钟 + 注入时区**，一行 `System.currentTimeMillis()` 都没有。

| 卡片 | 口径 | 说明 |
| --- | --- | --- |
| 某年今天 | 历年同月同日（含今年的今天） | 多张合并成一张卡，副标题列年份 |
| 一年前的今天 | `today.minusYears(1)` 那一个日历日 | **与"某年今天"同一天时被合并掉**，不重复出卡 |
| 最近一周 | `today-6 .. today`（含今天） | 按**日历日**比，不按毫秒差 |
| 这个月 | 当月 1 日 .. 月末 | 月末由 `YearMonth.atEndOfMonth()` 给 |
| 去年夏天 | 去年 6 月 1 日 .. 8 月 31 日 | `SUMMER_MONTHS = {6,7,8}` |
| 第一次拍的那天 | 整库最早的日历日 | 那天所有照片都带上 |

出卡顺序固定：`ON_THIS_DAY → YEAR_AGO → LAST_WEEK → THIS_MONTH → LAST_SUMMER → FIRST_DAY`。
顺序固定，才不会因为照片集合的 hash 顺序变化而抖动。

**闰日**：2 月 29 日那天，"一年前"是 `2027-02-28`（`LocalDate.minusYears` 自己处理，不抛）。
于是 2024-02-29 拍的照片在 2025-02-28 那天**不算**"一年前的今天"（那天是 2024-02-28），
也不在 2026-02-28 那天算"某年今天"（2-29 ≠ 2-28）—— 它只出现在"第一次拍的那天"里。
反方向也一样：今天是 2 月 29 日时，`minusYears(1)` 给 2 月 28 日，不会凭空造出一张卡。

**`dateTaken <= 0` 的照片不参与任何卡片**：MediaStore 的 `DATE_TAKEN` 缺列时会被退回 0，
那不是"1970 年 1 月 1 日"，是"日期不可知"。塞进"某年今天"就是造一条假回忆。

### 2.2 卡片流

- 大封面 4:3（`ContentScale.Crop`）+ 右上角张数 + 标题 + 编号（`ON THIS DAY` 等）+ 副标题。
- 列表 `key = Memory.id`，id 由 `kind + 覆盖范围` 生成，**同一批照片算两遍得到同一份 id**
  （否则每进一次回忆页整屏卡片重建一次）。
- 空库 / 全部照片都没有可信日期 → 露出 `EmptyState`（"还没有能拼成回忆的照片"），
  不是一张 0 张照片的空卡。

### 2.3 详情

`MemoryDetailScreen(memory, photos, onBack, onAllMoments, startIndex)`：

- `HorizontalPager` 横滑大图，点一下收/放信息层（与查看器同一个手势语言）；
- 底部：组名、副标题、当前那张的日期 + 文件名、`全部一瞬` 主按钮；
- `全部一瞬` 只回调 `onAllMoments()`，**自己不导航**：那是调用方的事。

---

## 3. 幻灯片

### 3.1 状态机（`ui/slideshow/SlideshowState.kt`）

`SlideshowState(size, index, loop, shuffle, bag, played, exhausted)`，推进全在四个纯函数里：

| 函数 | 语义 |
| --- | --- |
| `advance(stepSeed)` | 下一张。顺序 / 随机 / 空片单三种分支，**唯一一处**"下一张是谁"的判断 |
| `back()` | 上一张（手动左滑 / 音量减）；随机模式下**不动袋子** |
| `jump(target)` | 跳到任意一张；随机模式下把目标从袋里摘掉 |
| `withShuffle(on)` / `withLoop(on)` | 开关；都不跳片 |

**随机不重复的口径是洗牌袋（shuffle bag），不是"reroll 直到不同"。**
后者在三张以上时没有"取尽一轮"的保证，而需求要的正是"随机不重复**直到取尽**"。
袋是 `0 until size` 的一个排列，洗完逐个出，出完再洗一轮（新一轮的第一个不接在刚才那张后面，
否则接缝处会连播同一张，看着像卡住了）。

`SlideshowStateTest.rerollRandomReallyFailsTheExhaustionGuarantee` 用 24 个种子量过：
reroll 那一版有若干个种子在一轮内漏掉某张，洗牌袋 0 个。

### 3.2 交互

| 手势 / 按键 | 动作 |
| --- | --- |
| 点一下画面 | 暂停 / 继续 |
| 左右滑 | 手动翻页（`HorizontalPager`），翻完同步回状态机 |
| 下滑 | 退出（阈值 140px，和查看器同一个数） |
| 音量 + / − | 下一张 / 上一张（`view.setOnKeyListener`；只有一张时把音量键还给系统） |
| 工具条「暂停 / 继续」 | 同点一下 |
| 工具条「2 秒 / 3 秒 / 5 秒 / 10 秒」 | 循环切档 |
| 工具条「循环」/「随机」 | 开关 |

计时器的 `LaunchedEffect` 依赖里带着 `state.index`：手动滑一下会**重新计时**，
而不是"刚滑过去就立刻被定时器顶走"。

进度条是自绘的 `Canvas`，不是 `Slider` —— 它不是可调控件，不该被读屏当成滑杆。

---

## 4. 性能预算

### 4.1 拼图导出：9 张 4000px 图的内存峰值

这是这一组功能里唯一会真的把机器压出 OOM 的路径，所以按实测口径把账列出来。

**关键设计：源图一张一张解、画完立刻 `recycle()`，不并行、不预解。**

| 项 | 尺寸 | 内存 |
| --- | --- | --- |
| 输出画布（长边 2048，方图） | 2048 × 2048 × 4 B | **16.8 MB** |
| 输出画布（长边 4096，方图） | 4096 × 4096 × 4 B | **67.1 MB** |
| 单张源图（按格子解码，9 宫格 = 683px 的一格 × 1.2） | 820 × 820 × 4 B | **2.7 MB** |
| 一张 4000px 原图若**整张**解码 | 4000 × 3000 × 4 B | **48 MB** |

于是：

- **现在的峰值** ≈ 16.8 MB（2048 输出）+ 2.7 MB（当前那一张源图）+ 压缩用的 `ByteArrayOutputStream`
  ≈ **21 MB** 量级。9 张 4000px 原图**从来不会同时展开**。
- 如果按"把 9 张原图全解出来再画"的老写法：9 × 48 MB = **432 MB** 只是位图本身，
  加上输出画布与压缩缓冲，在 `-Xmx` 受限的进程里就是必 OOM 的那一档。
- 4096 长边那一档的峰值约 **70 MB**：这是把"长边 4K"做成显式选项而不是默认值的原因。

`decodeEdgeFor(cell)` 是这份预算的闸门：目标格子的较长边 × 1.2，上限
`MAX_SOURCE_EDGE = 2400`。改这个函数就是在改上面的表，别的地方没有第二个决定点。

### 4.2 其它

| 项 | 预算 |
| --- | --- |
| 模板表 | 12 张 × 最多 9 个 `CollageSlot`（4 个 Float），常量级，类加载时校验一次 |
| 回忆聚合 | 整库一遍 `filter` + `sortedByDescending` + 6 次 `filter`，O(n log n)。5000 张量到 10ms 量级以下，且只在进回忆页时跑一次 |
| 回忆卡片 | 每张卡最多 `MAX_PHOTOS_PER_MEMORY = 240` 张照片的 id，纯 `Long` |
| 幻灯片缩略图 | 每张按 2048 请求 Coil（`FastThumb(2048)`），同一时刻只有当前页 + 预取的一页驻留 |
| 预览区 | 每格按"格子像素数"请求缩略图（不是原图），9 格合计不到 1 屏 |

### 4.3 一条纪律

三个包**都不新增偏好键**：今天的参数只存在内存里，退出即回到默认值。
要落盘的话走 `settings/` 的 `cfg_` 前缀（见 `docs/product/SETTINGS.md` §0），
不要直接往 `suipai.xml` 里塞裸键 —— `FlagTableGuardTest.prefKeysAreGloballyUnique` 会拦。

---

## 5. 播放与分享（`ui/play/`）

三件"可玩"的收尾：配方卡、投屏意图。都**不新增依赖**、**不 import `state` 包**、
**不读照片**。

### 5.1 配方卡

把一条配方抄到一张印样卡上送人。**卡上只有配方，没有照片** —— 所以它不需要相册权限，
也不会把任何画面带进分享。

入口签名只有三个参数：

```kotlin
@Composable
fun RecipeCardScreen(look: Look, intensity: Float, onBack: () -> Unit, onSave: ... = ...)
```

卡被切成三层，前两层可测、第三层没有分支：

| 层 | 文件 | 是什么 | 怎么测 |
| --- | --- | --- | --- |
| 模型 | `RecipeCard.kt` `recipeCardModel()` | 卡上该出现的每一句话、每一个数 | 逐字段断言；两次调用必须相等 |
| 几何 + 材料 | `recipeCardLayout()` / `RecipeCardPixels.paint()` | 所有矩形 + 纸底/框线/横线/强调竖条 | 逐像素断言（矩形不越界、颜色落在对的坐标） |
| 字体 | `renderRecipeCard()` | `Canvas.drawText` | 只有这一层依赖设备字体 |

**确定性是硬要求**：卡要发出去，"同一组参数两次生成不一样"就是缺陷。所以模型里
**没有时间、没有随机、没有设备信息** —— `RecipeCardTest.cardCarriesNoTimeOrDeviceInformation`
用正则拦年份与 `hh:mm`，并且配了"塞一个年份必须被抓住"的自检。

三档尺寸：1080×1350（4:5）/ 1080×1080（1:1）/ 1080×1920（9:16）。长边一律 1080。

**强调色由配方自己的色温决定**，不跟界面主题走：暖调（温度 > 0.02）走琥珀
`#E8A33D`、冷调（< -0.02）走青蓝 `#6F97A8`、中性走相纸灰 `#C9C3B4`、单色一律
`#9AA39E`（黑白配方刷暖色会和"黑白"打架）。

### 5.2 配方卡的落盘与分享

| 出口 | 走哪条路 | 失败时说 |
| --- | --- | --- |
| 存到相册 | `MediaRepository.saveBitmap(bmp, name, png = true)` —— 与拼图/一瞬/导出**同一条口径**（`Pictures/SuiPai` + `IS_PENDING` 二段式） | 「配方卡没存上」 |
| 分享 | 优先用刚存好的 **MediaStore uri**（`content://media/...`，天然可读，不需要 FileProvider） | 「分享没发出去：<原因>」 |
| 分享兜底 | 落盘失败时写进 `cacheDir/camera/` + `FileProvider` —— 那条路径在 `res/xml/file_paths.xml` **已声明**，所以不需要改 manifest | 「配方卡没能写到临时目录，分享不了」 |

文件名：`SuiPai_配方卡_<lookId>_<时间戳>.png`（两个下划线 ⇒ 加工件）。
`lookId` 进文件名前会清洗：`last:xxx` 这种带冒号的前缀在某些文件系统上非法。

### 5.3 投屏意图

```kotlin
fun castIntent(context: Context, uri: Uri, mime: String): Intent?
```

**探测不到就返回 null**，界面显示「未找到投屏设备」——不崩、也不假装成功。
不引 Media3 / Cast SDK，只做"系统能接就交给系统"。

探测顺序是承重的：

1. **先精确**：`knownCasters` 里的包名逐个 `getPackageInfo` 直查（属包可见性规则里
   "本应用主动点名的包"那一档，不依赖 `<queries>`），命中之后再问一次
   `resolveActivity` —— **装了 ≠ 接得住 `ACTION_VIEW`**，只判"装没装"会让用户看到
   系统那句「没有应用可执行此操作」。
2. **再宽松**：有任何一个应用能接 image 通配的 `ACTION_VIEW`，就交给系统去选
   （会弹选择框，这是可接受的降级）。
3. **都没有 → null**。

判定被抽成纯函数 `CastIntent.choose(uri, mime, isInstalled, resolves)`：
两个谓词进，四种出口全部能在 JVM 单测里真的走到（本工程 JVM 单测里 `Context` 是桩，
直接对着 `PackageManager` 断言只会绿着空跑）。

`CastIntent.noteFor(intent)` 三种结果各有各的话，**"点了没反应"是这个功能最容易变成的样子**。
---

## 6. 测试

| 文件 | 钉住什么 |
| --- | --- |
| `ui/collage/CollageLayoutsTest.kt` | 每个模板无重叠 / 无缝隙 / 面积 = 1.0 / 格数正确 / 坐标在 [0,1]；`cellRects` 的边距与缝；导出命名 |
| `ui/memories/MemoriesTest.kt` | 注入固定时钟的 8+ 用例：空库、只有一张、跨年、闰年、闰日当天、同月同日合并、`covers` 与卡片互校 |
| `ui/slideshow/SlideshowStateTest.kt` | 顺序 / 循环 / 随机取尽 / 0 张 / 1 张 / 手动 back 与 jump / 间隔表 |
| `ui/collage/CollagePixelsTest.kt` | **强度 0 逐像素不变**（三种写法）/ 四种边框的预期像素（35mm 齿孔亮、片基暗、120 无齿孔且边更宽、拍立得下边 ≥ 上边 ×2）/ 漏光只落在选定角、对角为 0 / 条带处理与整幅处理逐字节相同 / 记号不越界 / 预览与像素层同一支橙 |
| `ui/play/RecipeCardTest.kt` | 同参数两次渲染完全一致（模型 + 材料像素）/ 卡上无年份、无 `hh:mm`、无设备信息 / 强度夹取与四舍五入 / 强调色随色温（含阈值边界）/ 三档尺寸的矩形不越界且竖排不重叠 / 文件名清洗 / 摘要截断带省略号 |
| `ui/play/CastIntentTest.kt` | 四种出口（精确 / 装了但接不住 / 宽松 / null）/ mime 空白退回 image 通配 / 空 uri 早退且不碰 PackageManager / 第一个命中就停 / `noteFor` 三种话互不相同 |

每一篇都配了**自检**：同一个判定函数喂一份"坏例子"，必须变红；
并且都钉了"核对到的样本数"下限，防止正则失效之后测试绿着空跑。
