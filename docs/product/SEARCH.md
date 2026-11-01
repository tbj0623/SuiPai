# 搜索 · 索引字段、查询语义与接入建议

> 本文件是 `app/src/main/java/com/suipai/app/data/SearchIndex.kt`、`data/SearchHistory.kt`
> 与 `app/src/main/java/com/suipai/app/ui/search/` 那一层的说明书。
> 与 `docs/product/SETTINGS.md`（设置）、`docs/audit/scout-gallery.md` §C（现状审计）平行。
>
> **需求来源**：`docs/audit/scout-gallery.md` §C.2/§C.3/§C.4。那三节写清了缺陷：
> 搜索只在**已加载的 400 张**里翻字（`MediaRepository.PAGE`），没有历史、没有建议、
> 没有 EXIF 型号/地点维度，而且界面上**不说**自己只搜了一部分。

---

## 0. 一句话

**搜索单开一本账。** 那个 400 是首屏渲染的预算（一页刚够铺满一屏加一屏预取），
不是搜索的边界；索引器用自己的分页把**全库**扫成一份可落盘的 JSON，
查询在内存里对全库打分并给出**命中原因**。

```
MediaStore（全库，分页 500/页）
        │  rebuild() / refreshIncremental()
        ▼
IndexedPhoto  ← EXIF 摘要（只读文件头 64 KB，不解码）
        │  save() → filesDir/search_index.json（带版本号）
        ▼
SearchIndex.query / match / suggestions   ← 纯内存、纯函数
        ▼
ui/search/SearchScreen（数据 + 回调，不含仓库）
```

---

## 1. 索引字段表

`data class IndexedPhoto`。**全部是值类型**（没有 `Uri`），所以 JVM 单测造得出来 ——
`PhotoItem` 因为带 `android.net.Uri` 在 JVM 里造不出来，那正是 `matchesPhotoQuery` 一直测不到的原因。

| 字段 | 类型 | 来源 | 用途 | 读不到时 |
|---|---|---|---|---|
| `photoId` | `Long` | `MediaStore._ID` | 打开/选中照片；增量去重的键 | **必填**，缺了这行整条丢弃 |
| `displayName` | `String` | `DISPLAY_NAME` | 文件名子串匹配；分类判据 | `"IMG_<id>.jpg"` |
| `bucketName` | `String` | `BUCKET_DISPLAY_NAME` | 相册名子串匹配；建议 | `"相册"` |
| `dateTaken` | `Long` | `DATE_TAKEN`，为 0 时退回 `DATE_ADDED * 1000` | 日期模式；同分排序 | `0`（1970，不参与「今年」） |
| `width` / `height` | `Int` | `WIDTH` / `HEIGHT` | 留给后续画幅筛选 | `0` |
| `sizeBytes` | `Long` | `SIZE` | 留给后续体积筛选 | `0` |
| `make` | `String` | `EXIF TAG_MAKE` | 相机维度 | `""` |
| `model` | `String` | `EXIF TAG_MODEL` | 相机维度；**建议**的主要来源 | `""` |
| `lens` | `String` | `EXIF TAG_LENS_MODEL` | 镜头维度 | `""` |
| `iso` | `Int` | `TAG_PHOTOGRAPHIC_SENSITIVITY` | 参数展示 | `0` |
| `aperture` | `String` | `TAG_F_NUMBER` → `"f/2.8"` | 参数展示 | `""` |
| `shutter` | `String` | `TAG_EXPOSURE_TIME` → 归一成 `"1/125"` | 参数展示 | `""` |
| `focal` | `Int` | `TAG_FOCAL_LENGTH_IN_35MM_FILM` | 参数展示 | `0` |
| `hasGps` | `Boolean` | `ExifInterface.latLong != null` | 地点维度的**前提**（见 §7 未做项） | `false` |
| `lat` / `lon` | `Double` | 同上 | 同上 | `0.0` |
| `kind` | `PhotoKind` | 由 `displayName` + `bucketName` 推出 | 类型维度；分组 | 推不出就是 `OTHER` |

### 1.1 `PhotoKind` 的判据

| 值 | 标签 | 判据 |
|---|---|---|
| `ORIGINAL` | 原片 | `SuiPai_` 开头且**只有一个下划线**（`SuiPai_<时间戳>.jpg`） |
| `YISHUN` | 一瞬成片 | `SuiPai_一瞬_` 开头 |
| `COPY` | 副本 | 其余所有 `SuiPai_` 加工件（灰度 / 2K / 裁切 / 旋转 / 圆角…） |
| `SCREENSHOT` | 截图 | 文件名或相册名含 `Screenshot` / `截屏` / `截图` |
| `DOWNLOAD` | 下载 | 相册名含 `download` / `下载` |
| `OTHER` | 其他 | 以上都不成立 |

「原片」这条判据与 `GalleryScreen.isSuiPaiOriginalName` **同源**：数下划线，不抄排除表。
这样以后新增任何加工动作（`SuiPai_灰阶_…`、`SuiPai_4K_…`）都自动落进「副本」，
不需要回来改这张表。**注意**：两份实现分别在 `data/` 与 `ui/gallery/`（后者是 `private fun`，
本轮不许动它）—— 这是一处**已知的重复**，见 §7。

### 1.2 EXIF 读取的三条硬约束

1. **只开流、不解码**：`openInputStream(uri)` 读前 **64 KB** 交给 `ExifInterface(InputStream)`。
   JPEG 的 APP1 与 HEIC 的头部盒都在这 64 KB 里。整图解码一次 5~30 ms，
   5000 张就是半分钟到两分钟，并且会把内存顶穿 —— 这是本机（14.3 GB，多会话并行）最不能接受的做法。
2. **读不到就跳过该字段，绝不抛**：`readExif()` 返回 `ExifBits.EMPTY`；
   外面还有一层 `readExifSafe()` 收 `Throwable`（`ExifInterface` 在个别 ROM 上对畸形文件抛
   `RuntimeException`，不是 `IOException`）。索引是**一条 5000 次的循环**，
   中间炸一次的表现是「索引永远停在 37%」。
3. **`CancellationException` 必须重抛**：取消是用户离开页面发出来的，不是"这张读失败"。

---

## 2. 查询语义

`internal object SearchQuery` 是**唯一实现**，纯函数、不碰 Android。

### 2.1 分词规则

| 输入形态 | 切法 | 例子 |
|---|---|---|
| 连续汉字 | **按字**切成单字，**字序必须一致**（子序列） | `上海` → `上`、`海` |
| 英文 / 数字 | 按**整词**（`isLetterOrDigit` 连续段），小写化 | `IMG_0042` → `img`、`0042` |
| 分隔符 | 空格、全角空格、`\t`、`\n`、`,`、`，`、`、`、`;`、`；`、`\|` | `上海、外滩` → 两段 |
| 其余标点 | 当断词点丢弃 | `a-b` → `a`、`b` |

**为什么中文按字**：中文相册文件名没有词边界（`上海外滩.jpg`、`魔都上海夜景.jpg`），
按字才能覆盖"词在中间"。代价是"整串不连续也会中"（`上…海` 命中 `上_海.jpg`），
所以用**连续命中加权**把最相关的顶到前面（见 §2.4）。

**为什么保序**：不保序的话 `上海` 会命中 `海上日出.jpg` —— 那是**另一个意思**。
`SearchQueryTest.chineseIsSplitPerCharacterAndOrderIsKept` 钉住了这一条，
并且配了"不保序会把日出也带出来"的自检。

**大小写**：`lowercase(Locale.ROOT)`，`contains` 判定。`IMG` / `img` / `ImG` 等价。

### 2.2 多词 AND

空格分开的**每一段都必须命中**，缺一段就不进结果。
`上海 外滩` 只留同时满足两段的；`上海 故宫` 是空集。
单段内部的英文词同样 AND（`IMG_0042` = `img` AND `0042`）。

### 2.3 日期模式

| 模式 | 正则 | 例子 | 语义 |
|---|---|---|---|
| 四位年份 | `\d{4}`（且 1900..2999） | `2024` | 那一年 |
| 年月 | `(\d{4})[-/](\d{1,2})`（月 1..12） | `2024-09`、`2024/9` | 那个月 |
| 月日 | `(\d{1,2})月(\d{1,2})日?`（月 1..12、日 1..31） | `9月22日`、`9月22` | **每年**的那一天 |
| 月 | `(\d{1,2})月`（月 1..12） | `9月` | **每年**的那个月 |
| 今天 / 昨天 / 今年 | 字面量 | `今天` | 相对 `LocalDate.now(zone)` |

三条细节：

- **整串是日期时只当日期查**，不再当文件名搜。否则搜「今天」会命中 `今天的云.jpg`
  —— 用户输入的是一个**时间概念**，不是四个汉字。
- **多词查询里的日期段仍然生效**：`上海 2024-09` = 名字含上海 **且** 2024 年 9 月。
- **不合法的值不是日期**：`13月`、`9月32日` 退回普通的文本匹配（`13月` 会去搜文件名）。

### 2.4 打分与排序

每个维度一个权重，**叠加**：

| 维度 | 权重 | 为什么是这个量级 |
|---|---:|---|
| 日期命中 | 1000 | 用户输入日期时他要的是**那一天**，不是名字里带那个数字的 |
| 文件名 | 100 | 「这张照片是什么」 |
| 相册名 | 60 | 「它被放在哪」——比文件名软 |
| 相机 make/model | 50 | 弱线索 |
| 镜头 | 40 | 更弱 |
| 类型标签 | 30 | 最软（「截图」命中一片是合理的） |
| 连续命中奖励 | +120（文件名）/ +60（相册） | 多段查询时，整串连着出现的那张更相关 |

**排序键**：得分 → `dateTaken` 新 → `photoId` 大。
同分时"新的先看到"，与时间线的口径一致。

**命中原因**：`HitReason`，同一条结果里取**权重最高**的那个维度。
`indexOf` 顺序即优先级：`FILE_NAME` > `ALBUM` > `DATE` > `CAMERA` > `LENS` > `TYPE`
（注意 `DATE` 的 1000 让它实际上永远压过别的）。

### 2.5 空查询

**空串 / 只有空白 → 空结果，不是"全部照片"。**
界面上的「没输入」是另一种状态（历史 + 建议），把它渲染成"5000 张结果"是错的。
`今天` 与 `\u3000` 都算空。

---

## 3. 命中原因设计

| 枚举 | 界面标签 | 什么时候出现 |
|---|---|---|
| `FILE_NAME` | 文件名 | 关键词出现在 `displayName` |
| `ALBUM` | 相册 | 出现在 `bucketName` |
| `DATE` | 日期 | 命中 §2.3 的任一模式 |
| `CAMERA` | 相机 | 出现在 `make` 或 `model` |
| `LENS` | 镜头 | 出现在 `lens` |
| `TYPE` | 类型 | 出现在 `PhotoKind.label`（原片/一瞬成片/副本/截图/下载/其他） |

**为什么要它**：搜「上海」跳出一张名字里没有「上海」的照片，如果没有角标就是玄学
（它可能命中的是 `EXIF` 里的机型，或者相册名）。角标让"为什么它在结果里"**看得见**，
同时它是 `SearchScreen` 分组的键。

界面上它以 `SearchPhoto.reason` 传给缩略图那一格，画在右下角（`SuiPaiType.frameNo`，
暗底 82% 不透明）。翻人话的函数是 `HitReason.label()`。

---

## 4. 落盘格式

`filesDir/search_index.json`（**不是** `SharedPreferences`，也**不是** cache 目录）。

```json
{"version":1,"builtAt":1790000000000,"count":2,"photos":[
  {"id":12,"name":"上海外滩.jpg","album":"旅行","taken":1789000000000,
   "w":4000,"h":3000,"size":4200000,
   "make":"SONY","model":"ILCE-7M4","lens":"FE 24-70mm F2.8 GM II",
   "iso":400,"aperture":"f/2.8","shutter":"1/125","focal":35,
   "gps":true,"lat":31.2304,"lon":121.4737,"kind":"OTHER"}
]}
```

四条规矩（各有单测）：

1. **版本号**：`SearchJson.VERSION`。对不上 → 当空索引**并删掉文件**（不做迁移：
   索引是**可重建的缓存**，删掉只让它下次重建，不丢任何用户数据）。
2. **损坏兜底**：解析失败 / 被截断 / 根本不是 JSON → 同上。**单行坏掉只丢那一行**
   （缺 `id`），其余照进 —— 5000 行里一条畸形不该让整本账作废。
3. **增量不重复**：`refreshIncremental()` 按「id 不在索引里」挑新照片，
   回来走 `mergeIndexed()`（`LinkedHashMap`，**后到的覆盖先到的**）。同一张扫两遍只有一行。
4. **写盘是原子的**：写 `search_index.json.tmp` 再 `renameTo`。进程在写一半时被杀，
   旧文件仍然完整。空索引**不写文件**（不留 `{"photos":[]}` 的空壳）。

### 4.1 为什么手写 JSON 而不用 `org.json` / kotlinx-serialization

- `org.json` 在 `android.jar` 里是**桩**，`unitTests.isReturnDefaultValues = true` 之后每个方法
  返回默认值（`JSONObject()` 是个空壳）。用它写"JSON 往返"守卫会**绿着空跑**。
- kotlinx-serialization 这个工程没引，而索引是可重建的缓存，为它加一个带 R8 keep 规则的
  序列化框架不划算。

`SearchJson` 只支持本项目需要的子集：对象 / 数组 / 字符串 / 数字 / 布尔 / null。
整数走 `Long`（照片 id 与毫秒时间戳要逐位精确，走 `Double` 在 2^53 以上会丢位）。

---

## 5. 性能预算

本机实测口径（桌面 JVM warm，5000 张量级；ART 上按 2~5 倍算）。

| 动作 | 预算 | 依据 |
|---|---|---|
| 全库扫描（纯 MediaStore） | **~0.4–0.8 s** | 一次游标遍历 5000 行 × 8 列；与 `MediaRepository.query` 同一条路径 |
| EXIF（每张 64 KB） | **~2–6 ms/张 → 10–30 s** | 主要开销是 `openInputStream` + 系统 IO。这是**索引一次**的成本，不是查询成本 |
| 落盘 / 载入 JSON | **~120–400 ms** | 5000 行 × ~220 字符 ≈ 1.1 MB；`writeText` 与解析都在 IO 线程 |
| 一次查询（5000 张） | **~2–6 ms** | 每条查询 3~6 个 `contains`；`Ranked` 排序 O(n log n) |
| 一次建议（5000 张） | **~3–8 ms** | 三次 `groupingBy{}.eachCount()` + 排序 |

**结论**：查询用的是"每键重算"的预算，所以建议与结果都该在**输入防抖（150~250 ms）之后**
才调 —— 否则每次按键 5 ms × 5000 张的遍历会跟输入法抢帧。

**EXIF 那 10~30 秒必须给进度**：`rebuild(onProgress)` 每张回调一次
（`(已索引, 总数)`），界面拿它说「已索引 N / M 张」。
总数数不出来（`countRows` 抛了）时给 **0**，界面据此显示"不定进度" —— 不编分母。
编出来的百分比会在 37% 停住，看起来像卡死。

**协程取消**：`rebuild` 在每个页边界与每张之间 `ensureActive()`。
取消时**已扫到的部分不落盘** —— 半本账比没有更糟，它会让人以为索引是全的。

---

## 6. UI 的四种空态

`ui/search/SearchScreen.kt`。参数全部是**数据 + 回调**，这一层不持有仓库、不开协程、不落盘 ——
四种空态、四种分组、两种索引状态都能单独摆出来看。

判据顺序就是代码里的 `when` 分支顺序，**别调**：

| # | 状态 | 判据 | 显示什么 |
|---|---|---|---|
| 1 | **没输入** | `query.isBlank()` | 最近搜过（可清空）+ 可以试试的建议；索引在跑时最上面多一行进度。两者都空 → "还没有可搜的东西" |
| 2 | **正在索引** | `results` 空 **且** `indexing` | 漏光那道线 + 「已索引 N / M 张」+ 一句"索引建完前只搜到已清点的那部分" |
| 3 | **有输入无结果** | `results` 空 | 「没有匹配「X」的照片」+ **"这次只搜了已索引的 N 张"**（有进度时）+ 换个词的建议 |
| 4 | **索引失败** | `note != null`（由调用方塞进来的原因） | 顶部那条 note 卡在所有状态之上；第 3 态里的说明也换成 `note` |
| — | 有结果 | `total > 0` | 分组（见下）+ 顶部「找到 N 张 / 全选 / 取消选择」 |

**第 3 态那一句是本轮的核心**：`scout-gallery.md` §C.2 记着"`hasMore=true` 时搜索结果是残缺的，
而且**界面不说明**"。现在它必须说。索引跑完过（`indexProgress` 非空）就退化成普通的一句"换个词"。

### 6.1 分组

`List<SearchResultGroup>`，四类（`SearchGroupKind`）：照片 / 相册 / 相机型号 / 年份。
每组渲染两块：**标题行**（标题 + 「N 张」+ 「搜这一组」）与一条 **`LazyRow` 横向预览**。

- 组标题自己可点 = 用这个标题再搜一次（"相机型号 / 年份"这两组的自然动作）。
- 横向预览用 `LazyRow`。**没有 `horizontalScroll(`** —— 源码守卫第 1 条禁的是后者
  （滚出视野的节点不在语义树里），`LazyRow` 不在它的判据里，而且这里的横向滚动是
  "一条图片带"，不是"把动作按钮藏在屏幕外"。
- 缩略图 96dp 见方、`FastThumb(320)` 走系统缩略图缓存，`ImageRequest` 用
  `remember(photo.photoId)` 包住（守卫第 4 条）。

### 6.2 源码守卫自查

| 守卫 | 本层怎么满足 |
|---|---|
| `ui/` 下不许 `horizontalScroll(` | 全层零出现；横向预览用 `LazyRow` |
| `combinedClickable(` 必须带 `onClickLabel` | 全层不用 `combinedClickable`，统一 `Modifier.pressable(onClickLabel = …)` |
| Lazy 模型类要 `@Immutable` | `SearchResultGroup` / `SearchPhoto` 都标了 |
| `ImageRequest.Builder(` 必须被 `remember(` 包住（往上 4 行内） | `ResultThumb` 里 `val request = remember(photo.photoId) { ImageRequest.Builder(…) … }` |
| `OutlinedTextField(` + `placeholder` + `singleLine` 必须有 `maxLines` | **不用** `OutlinedTextField`：`SearchField` 是 `BasicTextField`，提示语是自己画的一行 `Text(maxLines = 1)` |

最后一条不是绕过守卫，是**按 `GalleryScreen` 上量到的教训做的**：`singleLine` 只管用户输入、
不管提示语，字级 2.0 + 720×1600 时提示语自己折行把搜索框撑到 528 px，整片网格被顶出屏幕。

---

## 7. 接入现有 `GalleryScreen` 的建议（**本轮不改**）

搜索这一层已经就位，但**还没接进导航**。接线要动的地方如下（行号按本轮读到的文件，
接线时以当时为准）：

| 要改哪 | 现在 | 改成 |
|---|---|---|
| `ui/gallery/GalleryScreen.kt:902-933` | 内联的 `OutlinedTextField` 搜索框 | 换成 `ui/search/SearchField`，或点它进独立搜索页 |
| `GalleryScreen.kt:1975-2008` `matchesPhotoQuery` | `private fun`，只在已加载的 400 张里翻字 | 删掉本地实现，改调 `SearchIndex.query(q)`；`PhotoItem` 里没有的维度（EXIF/类型）只能从索引取 |
| `GalleryScreen.kt:1673-1674` 筛选链末端 | `query` 是第 13 段 `filter` | **搜索必须是独立结果集**，不能继续挂在 12 段筛选之后 —— 筛选链只覆盖已加载页 |
| `GalleryScreen.kt:1576-1580` `topEmptyHint` | 只处理"没读到"与"搜不到" | 加第三条：索引没建完时把「只搜了已索引的 N 张」说出来（`SearchScreen` 第 3 态的那句话） |
| `GalleryScreen.kt:2078-2081` 四个日期正则 | 本地常量 | 删掉，语义收进 `SearchQuery`（含"今天/昨天/今年"与整串判定） |
| `nav/` 与 `MainActivity.kt` | 没有搜索目的地 | 加一个目的地；`SearchIndex(context)` / `SearchHistory(context)` 在进程级各一份（照 `MediaRepository` / `Favorites` 的 `remember` 写法） |
| 索引触发点 | 无 | 冷启动后 IO 上跑一次 `load()`；`builtAt` 超过 N 天或首次安装时 `rebuild()`；`ON_RESUME` 可以调 `refreshIncremental()`（它只读 id 清单，很快） |
| 查询防抖 | 无（每键重算 400 张） | 输入 **150~250 ms** 防抖后再调 `query()`（见 §5：5000 张一次 2~6 ms） |

接线时**注意三件事**：

1. **别在 composition 里建索引器**。`SearchIndex(context)` 只做对象构造（IO 在读/写里），
   但 `load()` 会解析 1 MB JSON —— 它必须挂在 `LaunchedEffect` + `Dispatchers.IO` 上。
2. **`rebuild()` 的进度要真的画出来**。10~30 秒没有进度条的"正在搜索"和卡死没有区别；
   而且它应该在**用户看不到的地方**跑（冷启动后台），不要在搜索页里现扫。
3. **索引里的照片可能已经被删了**。`refreshIncremental()` 有意**不处理删除**
   （见 §8）；打开一张已删的照片要能优雅失败，不能崩在 `ContentResolver`。

### 7.1 仍未做（诚实清单）

| 缺口 | 为什么没做 | 大概怎么做 |
|---|---|---|
| **GPS 反向地理编码** | 需要 `Geocoder`，国内 ROM 常要联网；离线可用性依 ROM 而异。而"原图不上传"是本 App 的承诺 —— 加一条会走网络的搜索维度必须先把这件事说清 | 坐标已经在索引里（`hasGps`/`lat`/`lon`），接线时再决定"用系统 Geocoder + 明确告知"还是"内置城市边界框" |
| **类型判据与 `GalleryScreen` 重复一份** | `GalleryScreen` 的判据是 `private fun` 且吃 `PhotoItem`，本轮不许改那个文件 | 接线时把 `isSuiPaiOriginalName` / `isScreenshot` 挪到 `data/`，两处共用 |
| **色彩 / 人像标签** | 要一次全库后台任务，代价最大、优先级最低（`scout-gallery.md` §C.4 也把它排在最后） | 单独一批，先算主色再谈筛选 |
| **索引里的已删照片** | 增量不删是有意取舍（宁快勿全） | 搜索页打开照片失败时提示一句"这张可能已经删了"，或定期跑一次全量 `rebuild()` |

---

## 8. 索引的语义边界（写给下一个改这里的人）

- **索引是可重建的缓存**，不是数据源。删掉 `search_index.json` 只损失"下次要重扫"。
- **绝不因为"读不到"就清空索引**。`rebuild()` 一张都没扫到时**保留旧索引**
  （媒体提供方抖一下、撤权限、partial access 都会走到这里）。
  把"没读到"当成"库里没有"这个项目反复踩过 —— `MediaRepository` 里 `null` 与空表
  的分工就是为它写的。
- **增量不处理删除**。`refreshIncremental()` 只补新照片。要清掉已删的，跑全量 `rebuild()`。
- **索引器不写任何用户数据**：它只读 `MediaStore`、只写 `filesDir` 下自己那个文件。
