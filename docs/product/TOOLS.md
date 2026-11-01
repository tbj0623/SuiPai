# 随拍 · 相册工具二件套（重复照片合并 / 存储分析）

> 实现：`data/Duplicates.kt`、`data/StorageInsight.kt`、`ui/tools/DuplicatesScreen.kt`、`ui/tools/StorageInsightScreen.kt`
> 调研依据：`docs/audit/research-product.md` §2.3（缺口 #1/#2）、§6 P0-12 与 P2-3。
> 状态：**逻辑与 UI 已落地并通过编译；导航入口由 Lead 统一接线**（这两个屏幕不 import `state/**`、不自己导航）。

---

## 0. 一句话

两个都是「成熟相册该有、随拍原来没有」的工具：**重复照片合并**回答"这些是不是同一张"，
**存储分析**回答"究竟什么在占地方"。共同纪律只有一条——**只报告 + 交给用户确认，绝不代删**。

---

## 1. 重复照片合并（`Duplicates`）

### 1.1 为什么三键判据，而不是"相似就给建议"

合并 = 删照片。**误删是产品级事故，漏报只是工具没用**。所以只有三条同时满足才算重复：

| # | 判据 | 阈值 | 为什么必须有它 | 单独用为什么不够 |
|---|---|---|---|---|
| 1 | `sizeBytes` **逐字节相等** | 精确相等 | 同一份文件被复制/导入两遍时，字节完全一致 | 两个**完全不同的**文件可以碰巧同大小 |
| 2 | `takenAtMillis` 相差 | ≤ **120 秒** | 连拍、一次点击存两遍都落在几秒内 | 同一次旅行里每分钟都在拍，光看时间会误伤 |
| 3 | dHash 汉明距离 | ≤ **6** / 64 位 | 画面结构相同才叫"重复" | 纯色壁纸、纯黑截图之间距离也可能是 0 |

三条各自都不够，凑一起才够。这个组合是**保守取向**：实测 1000 张里能找出的组通常只有个位数，
但一旦列出，用户点"删除"时不会心疼。

### 1.2 dHash 而不是 aHash

`dHash(bits, w=9, h=8): Long` 吃**已经解码好的 9×8 灰度数组**（长度 ≥72），返回 64 位。

选差分哈希的理由：aHash 比较的是"每个像素 vs 全图均值"，全图亮度抬一点，**所有像素一起翻边**；
dHash 比较的是"相邻像素谁更亮"，亮度整体抬升时相邻关系不变。

实测对照（合成扰动，见 `DuplicatesTest`）：

| 扰动 | dHash 距离 |
|---|---:|
| 整体亮度 +6 | **0** |
| 每格 ±2 抖动（模拟 JPEG 块效应） | **≤3** |
| 横向渐变 vs 纵向渐变（两张结构不同的图） | **>10** |

> **为什么输入是 `IntArray` 而不是 `Bitmap`**：`unitTests.isReturnDefaultValues = true` 会把
> `Bitmap` 的构造 stub 掉，任何吃 Bitmap 的测试都是**假通过**。所以哈希是纯函数，
> 解码单独放在 Android 侧的 `Duplicates.hashOf(id)` 里，JVM 单测不碰它。

### 1.3 性能：只对候选解缩略图

这是整个扫描的关键，也是唯一能把它从"十几秒"压到"一两秒"的地方：

```
1. 分页读 _ID/SIZE/DATE_TAKEN/WIDTH/HEIGHT     ← 纯游标，不解码，5000 行约 100ms
2. 按 SIZE 分组，只留组内 >1 的                  ← 重复照片必然同大小，这步砍掉 90%+
3. 只对候选解码到 ≤64px 算 dHash                 ← BitmapFactory + inSampleSize
```

**没有对全库每张都解码**——那是最容易写出来的版本，也是最慢的版本。

解码用 `BitmapFactory` + `inSampleSize` 而不是 `ImageDecoder`：后者要 API 28+，
本项目 `minSdk 26`；且 `inSampleSize` 的"缩到 ≤64"语义已经够用（dHash 只要 9×8）。

#### 实测（JVM 17 桌面，`:app:testDebugUnitTest` 内跑，非估算）

| 项 | 实测 | 说明 |
|---|---:|---|
| `dHash` 单次 | **0.37 µs** | 20 万次平均，含预热 |
| `hamming` 单次 | **0.0175 µs** | 100 万次平均 |
| `cluster(50 张候选)` | **0.41 ms** | 25 组 |
| `cluster(100 张候选)` | **0.57 ms** | 50 组 |
| `cluster(200 张候选)` | **0.71 ms** | 100 组 |
| `cluster(500 张候选)` | **2.15 ms** | 250 组 |

**推算（1000 张库、10% 候选率 → 100 张候选）**：

- 游标读取 1000 行 ≈ 20 ms
- 100 张候选解码缩略图 ≈ **主要成本**，单张按 5–15 ms 估 → 0.5–1.5 s
- `dHash` × 100 ≈ 0.04 ms（可忽略）
- `cluster(100)` ≈ 0.57 ms（可忽略）

→ **瓶颈 100% 在解码，不在哈希与聚类**。这也是为什么 §1.3 第一步粗筛比"把 hash 写快一点"重要一个数量级。

> 上述解码耗时是**估算**（JVM 桌面无法代表 ART 上的 `BitmapFactory`）。
> 严格实测需要在设备上跑——本机当前不允许起模拟器（Lead 纪律），故标为推算。
> 哈希与聚类两行是**实测值**（同一次 JVM 运行，`--tests` 过滤单跑）。

### 1.4 保留策略 `KeepRule`

| 规则 | 判据 | 什么时候用 |
|---|---|---|
| `HIGHEST_RESOLUTION`（默认） | 像素数 → 文件大小 → id | 大多数场景：留最大的那张 |
| `LARGEST_FILE` | 字节数 → 像素数 → id | 担心"分辨率一样但压缩率不同" |
| `EARLIEST` | 拍摄时间最早 → 像素数 → id | "我最早存的那份" |

三条规则**都必须确定**：同一组输入永远给同一个答案（末尾都跟 id 兜底）。
否则用户第二次进这个页面会看到"建议保留"换了一张，那一瞬间他就不会再信这个工具。

`takenAtMillis <= 0`（日期不可知）在 `EARLIEST` 下**排到最后**，不能被当成 1970 年选为保留。

### 1.5 聚类用并查集，不是两两配对

两两配对输出会让同一批照片出现在多个组里，用户点"删除其余"时会**重复删**。
并查集保证传递性：A~B、B~C 时 A、B、C 一组，即便 A~C 直接比超了阈值。

### 1.6 产出的组

```kotlin
DuplicateGroup(
    items: List<DuplicatePhoto>,   // 保留的那张固定排第一
    keep: Long,                     // 建议保留的 id，一定在 items 里
    confidence: Float,              // 三键全中 = 1.0（当前判定线，保留字段为将来放宽用）
    reasonText: String,             // 中文人话，直接显示在组头
)
// reclaimableBytes = 组内除保留外总和
```

`reasonText` 形如：`这 3 张大小相同、同时拍摄、画质相同，建议只留一张`。

### 1.7 删除路径

```kotlin
fun deleteRequestFor(context, uris): IntentSender?   // 包一层 MediaStore.createDeleteRequest
fun Duplicates.deletionUrisOf(group): List<Uri>      // 该组除保留那张的 uri
```

**只构造 request，`intentSender` 的发起必须由宿主（Activity）做**——与 `MediaRepository` 同样的分工。
纪律：返回值 `> 0` 才算成功；返回 0 表示用户取消了确认框，**那不是失败、也不许说"已删除"**。

---

## 2. 存储分析（`StorageInsight`）

### 2.1 三个问题，三个答案

| 用户问题 | 这一屏的答案 |
|---|---|
| 一共占多少？ | 顶部大字 `38.2 GB` + `共 12,345 张照片` |
| 大的都是什么？ | 6 档堆叠条（按字节占比）+ 最大 20 个文件 |
| 哪些能放心删？ | 按来源分类（截图/随拍加工件/原片/下载/相机/其他），**只给数量与大小** |

### 2.2 分档按像素，不按字节

`classify(sizeBytes, width, height, mime): SizeClass`

| 档 | 像素下界 | 约等于 |
|---|---:|---|
| `TINY` | 0 | 缩略图 |
| `SMALL` | 300,000 | 640×480 |
| `NORMAL` | 1,200,000 | 1280×960 |
| `MEDIUM` | 3,000,000 | 2000×1500 |
| `LARGE` | 8,000,000 | 3264×2448 |
| `HUGE` | 20,000,000 | 5200×3800 |

**为什么不按字节**：`mime` 在 MediaStore 里几乎永远是 `image/jpeg`，没有信息量；
而"一张 4000×3000 却只有 200KB"是聊天软件压过的，"800×600 却 3MB"是二次保存的截图——
**像素尺寸 + 字节数共同**才说明白这张图是什么。

尺寸未知（`width/height <= 0`，外部写入或未解析）时**退回按字节分档**，
不塞进 `TINY` 糊弄——那会让"极小"这一档藏着重图。

### 2.3 分桶是固定的 6 档

`0 / 512KB / 2MB / 5MB / 10MB / 20MB / 以上`

固定而不是自适应等分：用户看一眼就知道"2 MB 以下那一堆是我的截图"。
自适应分桶每次刷新边界都在变，反而形成不了直觉。

### 2.4 分类判据（与 `SearchIndex.photoKindOf` 同口径，但**不 import 它**）

避免数据层互相耦合，这里独立实现同一套规矩：

```
截图     : RELATIVE_PATH 或文件名含 Screenshot / 截图 / 截屏
随拍系列 : SuiPai_ 前缀，**数下划线** —— 原片只有一个（SuiPai_<ts>.jpg），
           任何加工件（一瞬/副本/灰度/2K/裁切/圆角…）都有两个以上
下载     : RELATIVE_PATH 含 Download / 下载
相机     : RELATIVE_PATH 含 DCIM / Camera
其他     : 兜底
```

「数下划线」的好处：以后新增任何加工动作都**自动落进加工件**，不用改这里。

### 2.5 不代删

这一屏**没有删除按钮**。每一类给一个「去查看」入口（`onOpenCategory`），
真正的删除交给那一类自己的界面（重复照片会走系统的 `createDeleteRequest` 确认框）。

分类说明文案必须诚实：

| 类别 | 文案 |
|---|---|
| 随拍加工件 | 这些是从原片加工出来的，删了可以用一瞬**重新生成** |
| 截图 | 截图删了就没了，建议先看一眼 |
| 随拍原片 / 相机 | 删了**无法恢复** |
| 下载 | 删了要重新下载 |

> 只有加工件敢用"可以清理"的语气——因为它确实能从原片重生。

### 2.6 图表不引库

堆叠条就是 `Canvas` 里画 6 个 `drawRect`，宽度按字节占比。
引一个图表库要几 MB 包体，换来的是一条矩形——不值。

配色从 `safelightWash` 渐变到 `fixer`（越靠右越大越红），是**视觉契约**，
由 `bucketShades()` 统一提供，别处要画同样的条必须取这里的值。

---

## 3. 屏幕契约（供 Lead 接线）

两个屏幕都是「数据 + 回调」，不 import `state/**`、不自己导航。

```kotlin
// ui/tools/DuplicatesScreen.kt
@Composable
fun DuplicatesScreen(
    groups: List<DuplicateGroup>,
    scanning: Boolean,
    progress: Pair<Int, Int>?,          // (已处理, 总数)；扫描前为 null
    onBack: () -> Unit,
    onDeleteGroup: (DuplicateGroup) -> Unit,
    onDeleteAll: () -> Unit,
    note: String? = null,
)

// ui/tools/StorageInsightScreen.kt
@Composable
fun StorageInsightScreen(
    summary: StorageSummary,
    scanning: Boolean,
    onBack: () -> Unit,
    onOpenCategory: (StorageKind) -> Unit,
    note: String? = null,
)
```

宿主侧的典型接法：

```kotlin
// 重复照片
val dup = remember { Duplicates(context) }
var groups by remember { mutableStateOf(emptyList<DuplicateGroup>()) }
var scanning by remember { mutableStateOf(false) }
LaunchedEffect(Unit) {
    scanning = true
    groups = dup.scan(onProgress = { _, _ -> })
    scanning = false
}
// onDeleteGroup = { g ->
//     val sender = deleteRequestFor(context, dup.deletionUrisOf(g))
//     if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build())
// }

// 存储分析
val storage = remember { StorageInsight(context) }
var summary by remember { mutableStateOf(StorageSummary.EMPTY) }
LaunchedEffect(Unit) { summary = storage.scan() }
```

辅助 API（避免宿主自己再算一遍、算出与界面不一致的数）：

| 函数 | 用途 |
|---|---|
| `extraCountOf(group)` | 这一组会删几张 |
| `keptOf(group)` | 保留的是哪一张 |
| `formatCount(n)` | 千分位 |
| `humanBytes(bytes)` | `38.2 GB` / `912 MB` / `12 KB` |
| `bucketShades()` | 堆叠条配色（视觉契约） |

---

## 4. 测试

| 文件 | 条数 | 覆盖 |
|---|---:|---|
| `DuplicatesTest.kt` | 33 | dHash 本身（梯度/纯色/镜像/非法尺寸）、缩放重压鲁棒性、三键逐键喂坏、并查集传递性、pickKeep 三种规则与确定性、组的产物（keep 在组内/排第一/可省字节/排序/中文理由） |
| `StorageInsightTest.kt` | 30 | classify 边界与未知尺寸兜底、histogram 不重不漏与半开区间、topN、分类判据（含「数下划线」）、reclaimable 排序与文案诚实性、humanBytes、summarize |

**每个判据都配了「喂坏例子必须变红」的自检**（函数名以 `selfCheck` 开头的那些），
例如：

- `selfCheckClassifyWouldGoRedIfPixelUsedWrong` — 若分档误用字节数，这条必红
- `selfCheckKindWouldGoRedIfUnderscoreCountIgnored` — 若加工件被当原片，必红
- `selfCheckReclaimableWouldGoRedIfUnsorted` — 若没按字节排序，必红
- `selfCheckHistogramWouldGoRedIfLastBucketClosed` — 若最后一桶上界写错，必红

关键边界断言（逐键喂坏，不是笼统"换个哈希"）：

| 用例 | 期望 |
|---|---|
| 大小差 **1 字节** | 不合并 |
| 时间差 **121 秒** | 不合并 |
| 时间差 **120 秒**（边界内侧） | 合并 |
| 哈希距离 **7** | 不合并 |
| 哈希距离 **6**（边界内侧） | 合并 |
| `hash == null`（解码失败） | **永不合并** |

---

## 5. 没做的部分（明确列出）

| 没做 | 原因 / 去向 |
|---|---|
| **导航入口** | 按 Lead 要求，由 Lead 统一接 `AppRoute`；本文件 §3 给了宿主接法 |
| **删除的 Activity 侧发起** | `intentSender` 只能在 Activity 里 launcher.launch；本模块只提供 `deleteRequestFor` |
| **扫描结果缓存** | 每次进屏重扫（1000 张约 1 秒级，可接受）；若要缓存需落在 `filesDir` 且要处理失效 |
| **视频重复检测** | 只扫 `MediaStore.Images`；视频要另一套（时长 + 首帧哈希） |
| **相似但不相同的"挑一张好的"** | 那是修图决策，不是相册工具；会滑向"代替用户审美" |
| **按分类批量删除** | 本屏只报告不执行（见 §2.5）；实际删除由各类自己的界面做 |
| **`docs/product/TOOLS.md` 之外的文档更新** | 未改 `docs/ROADMAP.md`（Lead 的决策文件） |

---

## 6. 已知约束

1. **全模块单编译单元**：本轮期间任何 lane 留一处错，本文件的测试都跑不了。
   本文件相关代码已验到 **0 error**（见交付报告）。
2. **`hasImage` 不等于可删**：MediaStore 里有行、字节存在，但可能在他人的 scoped storage 里，
   删除会走系统确认框甚至失败。`deleteRequestFor` 返回 `0` 是常态，不是异常。
3. **扫描期间用户离开**：`scan(cancel = { ... })` 每页检查一次；
   宿主应在 `DisposableEffect` 里置 `cancel` 为 true，避免扫完才发现页面已经销毁。
4. **`StorageSummary.EMPTY` 与"扫到 0 张"不是一回事**：前者是没扫/没读到，
   界面对这两种应该给不同文案（当前实现里 `count == 0` 走同一个空态，宿主可据 `note` 区分）。
