# 双端功能差距清单（Android ↔ iOS）

> **为什么有这份文件**：`ios/` 这个壳是在安卓已经跑完好几轮功能之后才补的，
> 两端从来就不是等价的。这份清单的唯一目的是**让差距可见** —— 避免以后有人
> （包括下一个 AI）看到 `docs/product/` 下那些详尽的设计文档，就以为 iOS 也有。
>
> **它不是一个"待办清单"，也不代表这些功能都该移植**。有些是安卓独有的形态
> （MediaStore 回收站、CameraX 专业模式），要移植得先想清楚 iOS 上等价物是什么。
>
> 统计口径：本文件写于 2026-10，对着当时的 `app/` 与 `ios/` 源码逐个数。
> **不数行数当进度**，只标「用户能不能用到」。

---

## 0. 一句话结论

**iOS 只有四块：相册、相机、互通、一瞬。** 而且这四块里每一块都比安卓薄：

| iOS 的一屏 | Swift 行数 | 大致相当于安卓的 |
| --- | --- | --- |
| `GalleryView.swift` | 244 | 没有搜索 / 没有收藏 / 没有隐藏 / 没有多选批量 / 没有年轨 |
| `CameraView.swift` | 419 | 基础拍照（无专业模式、无网格、无水印、无画幅） |
| `SyncView.swift` + `SyncBridge.swift` | 613 | 发现 + 发送 + 接收确认（没有浏览下载） |
| `YishunView.swift` | 718 | 选 Look + 强度 + 对比 + 导出（**能读**专业配方，**不能建**） |

安卓侧对应的 `app/src/main/java/com/suipai/app/ui/` 有 **22 个子包**；
iOS 侧是 **11 个 Swift 文件、约 3000 行**。

---

## 1. 完整差距表

「安卓状态」= 数据层 + 界面都写了并且接进 `AppRoute` / `AppNavHost`（也就是
用户点得到）。**只写了数据层、没接导航的，标「部分」**，因为那种状态下用户看不见。

| # | 功能 | 安卓状态 | iOS 状态 | iOS 要补的话大概动哪些文件 |
| --- | --- | --- | --- | --- |
| 1 | **时间线相册**（按日分组、网格） | ✅ 已完成 | ✅ 已完成 | — |
| 2 | 相册搜索（全库索引 / 搜索历史 / 按机型地点文件名） | ✅ 已完成（`data/SearchIndex.kt`、`ui/search/`） | ❌ 未做 | 新增 `Support` 级的 EXIF 摘要读取；新增 `SearchIndex.swift`（照 `SearchIndex.kt` 的字段表）；`GalleryView.swift` 加搜索框 |
| 3 | 查看器（缩放 / 分页 / 下滑退出 / 信息面板） | ✅ 已完成（`ui/viewer/`） | ⚠ 部分（`ViewerView.swift` 有分页+缩放，**没有信息面板/EXIF/直方图**；已核：文件里没有任何 info/EXIF 相关代码） | `ViewerView.swift` 加信息面板；EXIF 读取可复用 `RecipeReader` 的 ImageIO 路径 |
| 4 | 收藏 / 隐藏 | ✅ 已完成（`data/Favorites.kt`、`data/Hidden.kt`） | ❌ 未做 | 新增 `IdLists.swift`（UserDefaults 或 json）；`GalleryView` / `ViewerView` 加入口 |
| 5 | 自建收藏夹 | ✅ 已完成（`data/Collections.kt`、`ui/collections/`） | ❌ 未做 | 新增 `Collections.swift` + `CollectionsView.swift` |
| 6 | **回收站**（软删除 30 天） | ✅ 已完成（`data/Trash.kt`、`ui/trash/`） | ❌ 未做，**且不能照搬** | 见下方 §2 的「不能照搬」；iOS 需要 `PHAssetChangeRequest.deleteAssets` + 自己的 json 记账，且**删了就进系统「最近删除」**，两套回收站会打架 |
| 7 | 批量动作（多选 / 批量一瞬 / 批量导出分享） | ✅ 已完成 | ❌ 未做 | `GalleryView.swift` 加选择模式 + `UIActivityViewController` |
| 8 | 相机（基础拍照） | ✅ 已完成 | ✅ 已完成 | — |
| 9 | **相机专业模式**（ISO/快门/EV/WB/手动对焦/水平仪/峰值/直方图） | ✅ 已完成（`ui/camera/CameraPro*.kt` 等 4 个文件） | ❌ 未做 | `CameraView.swift` 大改：`AVCaptureDevice` 手动控制（`setExposureModeCustom` 等）+ 新的档位模型。**iOS 的能力集合与 CameraX 不同**，`CAMERA-PRO.md` 的档位表不能照抄 |
| 10 | 相机辅助（网格/快门声/前置镜像/极简取景/JPEG 画质） | ✅ 已完成 | ❌ 未做（已核：`CameraView.swift` 里 grep 不到 grid / 镜像 / 提示音 / 画幅，一个都没有） | `CameraView.swift` + 一个 `UserDefaults` 封装 |
| 11 | 一瞬（选 Look / 强度 / 前后对比 / 导出） | ✅ 已完成 | ✅ 已完成 | — |
| 12 | 一瞬 · 智能适配 | ✅ 已完成（`engine/SceneAnalyzer.kt`） | ✅ 已完成（但有缺口：EXIF 六个字段全传 `nil`，见 BUILD.md 第 7 节） | `Support.swift` 的 `EnginePipeline.analyze` 补 ExifData |
| 13 | **一瞬 · 专业面板**（曲线 / HSL / 局部蒙版） | ✅ 已完成（`ui/yishun/pro/` 9 个文件） | ⚠ 只读不建：**能自动还原**别人照片里的专业配方（打开即按配方渲染）；**用户不能新建/编辑** | 新增 `ProPanel.swift`（曲线控制点拖拽是最难的一块）+ `ProOps` 等价物；复用已有的 `ProInputs` / `makeParams(curves:hsl:masks:)` |
| 14 | 一瞬 · 自定义配方（存/改名/导入/导出） | ✅ 已完成（`data/CustomLooks.kt`、`ui/yishun/pro/MyRecipes.kt`） | ❌ 未做 | 新增 `CustomLooks.swift`：`SPLOOK1:` 编解码走 shared 还是自己写要看 `CustomLooks.kt` |
| 15 | 一瞬 · v2 微调开关（颗粒/暗角/温度/分色等 8 个滑杆） | ✅ 已完成（`YishunFlagChips.kt` / `YishunFlags.kt`） | ⚠ 只读不建：能应用配方里的 9 个微调位，但没有滑杆可调 | `YishunView.swift` 加一组滑杆；`makeParams(knobs:)` 已经接好了 |
| 16 | 一瞬 · 批量 | ✅ 已完成 | ❌ 未做 | 依赖 #7 的多选 |
| 17 | **拼图**（2/3/4/6/9 格 + 边框 + 留白 + 拖拽换位） | ✅ 已完成（`ui/collage/` 5 个文件） | ❌ 未做 | 新增 `CollageLayouts.swift`（纯几何，最值得照抄）+ `CollageRenderer.swift`（CoreGraphics） |
| 18 | 胶片边框 / 漏光 / 装裱 | ✅ 已完成 | ❌ 未做 | 同上 |
| 19 | **回忆**（那年今日聚合） | ✅ 已完成（`ui/memories/`） | ❌ 未做 | 新增 `Memories.swift`（纯聚合逻辑，`Memories.kt` 可直译）+ 两个屏 |
| 20 | 幻灯片播放 | ✅ 已完成（`ui/slideshow/`） | ❌ 未做 | 新增 `SlideshowState.swift` + 一屏 |
| 21 | 配方卡（把一条配方分享成一张图） | ✅ 已完成（`ui/play/RecipeCard*.kt`） | ❌ 未做 | 新增 `RecipeCard.swift`，CoreGraphics 画 |
| 22 | 投屏意图 | ✅ 已完成（`ui/play/CastIntent.kt`） | ❌ 未做 | iOS 用 `AVRoutePickerView` / `UIWindowScene` 等价物，语义不同 |
| 23 | **重复照片合并**（dHash 三键判据） | ✅ 已完成（`data/Duplicates.kt`、`ui/tools/`） | ❌ 未做 | 新增 `Duplicates.swift`（dHash 算法可直译）+ 一屏 |
| 24 | **存储分析**（按像素分档 / 分类饼图） | ✅ 已完成（`data/StorageInsight.kt`） | ❌ 未做 | 新增 `StorageInsight.swift`；分类判据依赖文件名/相册名，iOS 的 `PHAsset` 也有 |
| 25 | **设置中心**（7 组、约 40 项） | ✅ 已完成（`settings/` + `ui/settings/` 5 个文件） | ❌ 未做 | 新增 `SettingsView.swift` + `SettingsStore.swift`。**`SETTINGS.md` 那张表可以直接当规格用**，但落盘键名（`cfg_*`）是安卓 SharedPreferences 的，iOS 该用 `UserDefaults`，键名可以沿用 |
| 26 | **首启引导**（三屏） | ✅ 已完成（`ui/onboarding/`） | ❌ 未做 | 新增 `OnboardingView.swift`；文案照 `SETTINGS.md` §4 |
| 27 | 隐私中心（数据去向 / 权限逐项 / 导出 JSON） | ✅ 已完成（`ui/privacy/`） | ❌ 未做 | 新增 `PrivacyView.swift`；权限表要对 `Info.plist` 而不是 `AndroidManifest.xml` |
| 28 | 关于页 | ✅ 已完成（`ui/about/`） | ❌ 未做 | 小；`SETTINGS.md` §7 有签名 |
| 29 | **备份导出 / 恢复** | ✅ 已完成（`data/Backup.kt`、`ui/settings/BackupScreen.kt`） | ❌ 未做 | 见 §2；iOS 没有 `backup_rules.xml`，功能等价物是 iCloud 备份 + 自己的导出文件 |
| 30 | 视频（`data/VideoRepository.kt`） | ⚠ 部分（有数据层，界面未见） | ❌ 未做 | 低优先 |
| 31 | 局域网互通 | ✅ 已完成（`sync/`） | ⚠ 部分（发现 / 发送 / 接收确认；**没有浏览下载入口**） | `SyncView.swift` 加「浏览对端照片并下载」；`SyncHub` 侧的协议已经支持 |
| 32 | EXIF 配方往返 | ✅ 已完成（`data/RecipeExif.kt`） | ✅ 已完成（写：`export()`；读：`RecipeReader`，**本轮新增**） | — |

**统计**：32 项里 iOS 完成 6 项（#1 #8 #11 #12 #32，加部分完成的 #31）、
部分 3 项（#3 #13 #15 #31）、**未做 22 项**。

---

## 2. 三个「不能照搬安卓实现」的地方

这一节比上面的表重要 —— 直接抄会抄出 bug。

### 2.1 回收站：iOS 删照片是不可逆的另一种模型

安卓的回收站是「复制到另一个目录 + 删原行」，因为 `MediaStore` 是一个文件系统账本。

iOS 完全不同：照片在 `PHPhotoLibrary` 里，删除用
`PHAssetChangeRequest.deleteAssets`，**删掉的会进系统的「最近删除」再留 30 天**。
如果 iOS 再搞一个自己的回收站，用户会看到**两套 30 天**，而且
「随拍的回收站里恢复了、系统最近删除里已经过期」这种状态没法解释。

可能的正确形态（**需要产品决策，不是移植问题**）：
直接依赖系统的「最近删除」，随拍只做一个**入口**跳过去，不做自己的记账。
`TRASH-COLLECTIONS.md` 里那套 json 账单（`filesDir/trash.json`）在 iOS 上不该存在。

### 2.2 专业相机：CameraX 的档位表在 iOS 上对不齐

`CAMERA-PRO.md` 的 22 档 ISO、40 档快门、`clampToCapabilities` 那一族，
全部是围着 `CameraCharacteristics` 的区间写的。iOS 的
`AVCaptureDevice.activeFormat` 给的是一组离散的
`minISO`/`maxISO`/`minExposureDuration`/`maxExposureDuration`，**同样能钳制**，
但「1/3 档一节」的选择器要重新设计（`activeFormat` 本身可能就是固定档位的）。

**能照抄的只有 UI 结构**（抽屉、置灰规则、水平仪）；档位模型必须重写。

### 2.3 备份：没有 `backup_rules.xml` 这层

安卓靠 `backup_rules.xml` / `data_extraction_rules.xml` 精确控制「什么进云备份」，
`BACKUP.md` 整篇是围着它写的。iOS 的等价物是 **iCloud 备份打包整个 App 容器**，
没有 per-file 的 include/exclude —— 想排除某个文件只能自己写标记
（`URLResourceValues.isExcludedFromBackup`）。

所以 `BACKUP.md` §3 的「这里面**没有**什么（重要）」那一节，在 iOS 上要重新论证
一遍，而不是照抄结论文案。

---

## 3. iOS 现在最该补的三件事

按「投入产出比 × 缺了会有多难受」排，不是按功能表顺序。

### 第一名：设置中心（#25）+ 首启引导（#26）

**理由**：这两块是**所有平台的入场券**，不是功能。

- App Store 审核明确要求有隐私说明与权限解释（现在 iOS 只在 `project.yml` 里
  塞了四句 `INFOPLIST_KEY_*`，App 内**没有任何一处**可以看数据去向）。
- 现在的 iOS 一瞬页把强度默认值写死在代码里（`intensity = 0.55`），用户改不了
  就想不出"下次打开还是 0.55 是不是 bug"。
- 三屏引导是**纯文案 + 一屏图**，安卓侧 `onboardingPages()` 是纯函数，
  文案可以直接搬；`SETTINGS.md` 已经有完整规格。投入极小、完成度提升极大。

不需要先做数据迁移那套（iOS 没有老用户包袱），做一个 `UserDefaults` 封装即可。

### 第二名：相册搜索（#2）+ 收藏/隐藏（#4）

**理由**：这两个是「相册类 App 的地板」。现在 iOS 的相册只能按时间往下滚 ——
一旦照片超过几百张就没法用了，而**这正是相册 App 的核心场景**。

- 搜索：`SearchIndex.kt` 的字段表（`SEARCH.md` §1）是一份现成的规格，
  iOS 的 `PHAsset` 能拿到 `creationDate` / `pixelWidth` / `mediaSubtypes`，
  机型/镜头要读 EXIF（`RecipeReader` 已经在用 ImageIO 读 EXIF 了，同一套 API）。
- 收藏/隐藏：数据层是纯 id 集合，`IdLists.kt` 直译即可，界面两个按钮。

### 第三名：查看器信息面板（#3 补齐）+ 一瞬的 v2 微调滑杆（#15 补齐）

**理由**：这是「已经做了 80%、差最后一刀」的两处 —— 补起来最便宜。

- 查看器目前是个纯图片浏览器。加一个上滑信息面板（EXIF + 直方图 + 「这张带配方」），
  就同时解决了「配方还原到底有没有生效」这个用户可见的疑问。
  `RecipeReader` 已经能读 EXIF，`Histogram.rgb` 也已经有了。
- 一瞬的 `makeParams(knobs:)` **已经接好了**（本轮为读配方加的），
  只差 8 个滑杆把值喂进去。安卓侧 `YishunFlagChips.kt` 的交互可以照抄。

**为什么不把「一瞬专业面板」（#13）排进前三**：曲线控制点拖拽是这些里唯一需要
真正设计手感和命中判定的（安卓侧 `CurvePoints.kt` 有 8.7 KB 专门处理这个），
投入远大于上面三项，而受益用户是少数重度用户。

---

## 4. 这张表怎么维护

- **不要**因为「安卓有」就往 iOS 搬。第 2 节那三处就是反例。
- 每做完一项，改这张表的状态列，**并在后面写上是哪一轮做的**（日期或轮次）。
- 不确定某一项到底完没完成时，**按"未做"记**。把没做的写成做了，比漏记严重得多。
