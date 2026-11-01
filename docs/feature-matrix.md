# 功能矩阵（内部用，不是给用户看的）

> **这张表只抄不编。** 每一行的「代码位置」与「测试覆盖」都来自 `docs/product/` 下的文档
> （`SETTINGS` / `SEARCH` / `PLAY` / `TRASH-COLLECTIONS` / `PRO-EDITING` / `CAMERA-PRO` /
> `TOOLS` / `BACKUP` / `VIDEO`，共 9 份 —— 见本表末尾第 11 条）
> 以及 `docs/arch/APP-ARCH.md`、`docs/engine/COLOR-PRO.md`。
> 文档没写的一律标「文档未记」；没验证的一律写「未验证」。
>
> **最近一次修订：2026-10-29。** 这一轮改的是「状态是否还诚实」，不是功能本身：
> 第 6 / 7 / 17 / 18 / 23 / 24 / 27 行的备注按代码当前状态重写，
> 新增「表外 / 已知缺口」一节（同步收照片会丢拍摄时间）。
> **本轮没有任何一条被提升为「已完成」** —— 本轮一行都没装机。
>
> 状态三档，严格按仓库纪律：
>
> | 状态 | 含义 |
> | --- | --- |
> | **已完成** | 代码 + JVM 单测 + **在模拟器或真机上实际走通过**（有出图 / 有截图 / 有 dump 作证） |
> | **部分** | 代码与单测都在，但**没有在设备上走完整条用户路径**；或上架链路里缺一环 |
> | **未做** | 明确不做的（不是遗漏），或文档里列为「仍缺」的 |
>
> 本表的行序按六组排：看 / 拍 / 冲 / 收 / 玩 / 控。

| # | 功能 | 状态 | 代码位置 | 测试覆盖 | 诚实备注 |
| :-: | --- | :-: | --- | --- | --- |
| 1 | 时间线：按日 / 周 / 月 / 年分组，滚动时顶部留住当前组，双指缩放切密度，右侧年份轨 | 已完成 | `app/src/main/java/com/suipai/app/ui/gallery/GalleryScreen.kt`（2472 行，2026-10-29 数） | `ui/gallery/GalleryPositionTest`（4）、`CellLabelTest`（11） | 分组与坐标在模拟器上走到过；**双指缩放切密度与年份轨未单独量过** |
| 2 | 多路筛选：日期与时段 / 方向 / 画幅 / 像素 / 体积 / 文件名 | 已完成 | 同上（筛选链在 `GalleryScreen.kt` 内，`ui/gallery/`） | `OnlyKindTableTest`（7，扫 31×28 组合）、`RatioFilterTest`（5）、`RecencyRuleTest`（6）、`HourBucketTest`（4） | 「只看 X」31 档的恒空死胡同由设备侧 `GalleryOnlyKindTest` 扫；**那一支是 androidTest，没跑过** |
| 3 | 大图查看器：双指缩放、旋转、16 种裁切比例、15 档导出尺寸、设为壁纸 | 已完成 | `ui/viewer/PhotoViewerScreen.kt`、`ui/viewer/ViewerActions.kt` | `ListingCountsGuardTest`（4，钉住 16 / 15 两个数） | 旋转 / 裁切 / 导出的**像素结果**只在 `ANDROID-VERIFY.md` 里有人眼记录，JVM 侧只钉住表的大小 |
| 4 | 全库搜索：日期 / 相册 / 收藏 / EXIF 机型 / 文件名 / 文字 + 命中原因 + 分组 | 部分 | `data/SearchIndex.kt`、`ui/search/SearchScreen.kt`、`ui/search/SearchField.kt` | `SearchIndexTest`（25）、`SearchQueryTest`（27） | **未装机**。索引落盘 / 损坏兜底 / 增量合并都有单测，但「冷启动预热」还没接（`APP-ARCH.md` §11.7） |
| 5 | 搜索历史（10 条、去重、倒序）与建议 | 部分 | `data/SearchHistory.kt` | `SearchHistoryTest`（20） | **未装机**。落 `filesDir/search_history.json`，不参与备份 |
| 6 | 回收站：30 天软删除 / 恢复 / 彻底删除 / 到期清理 | 部分 | `data/Trash.kt`、`ui/trash/TrashScreen.kt` | `TrashLogicTest`（16） | **只在模拟器上看到过空态**（`store/screens/phone2/06-trash.png`）。**「恢复后保留原始 `DATE_TAKEN`」代码完成，装机未验**：`TrashItem.dateTaken`（默认 0 = 不知道）+ 账单键 `dateTaken`，搬家与恢复两头都写 MediaStore 列；老 `trash.json` 缺键解成 0 → 不写该列 → 行为与改前一致。`ContentResolver` 在 JVM 上是桩，搬家成不成功测不到；`Trash.of(context)` 进程级单例也只由 JVM 侧断言过一致。只对随拍自己写入的行生效 |
| 7 | 收藏夹：用户自建相册（200 个 / 每册 5000 张） | 部分 | `data/Collections.kt`、`ui/collections/CollectionsScreen.kt` | `CollectionsLogicTest`（10） | **未装机**。能建 / 改名 / 删 / 看；**「把选中加进已有夹」现在有 UI 入口**（`CollectionsScreen` 的 `collections_add_selected` → `EditTarget.AddingToExisting` → `AppNavHost` 的 `collections.addPhotos`，成功后会清空选择），原先**写了没人读**的 `collectionsNote` 也真的渲染出来了（`note =` 参数）。**代码完成，装机未验** |
| 8 | 批量动作：删除 / 副本 / 一瞬（进度按真实成功数报，结论只在结束时说） | 已完成 | `state/BatchActions.kt`、`state/BatchMachine.kt`、`state/DeleteFlow.kt`、`ui/common/BatchNote.kt` | `BatchMachineTest`（10）、`BatchNoteTest`（7）、`ConsentTicketTest`（5）、`ShareImportOutcomeTest`（3） | 装机验证过。删除已接管回收站（本 app 照片先搬候车室），外来照片仍走系统授权框 |
| 9 | 一瞬 · 强度与 7 条影调滑杆 + 颗粒 / 暗角倍率 | 已完成 | `ui/yishun/YishunScreen.kt`、`engine/YishunShifts.kt`（`enum Shift` 7 项） | `ListingCountsGuardTest`（钉住 7） | 装机验证过。**「7 条」不含颗粒与暗角**，那两档是倍率不是滑杆 |
| 10 | 一瞬 · 曲线（2–16 控制点，Fritsch–Carlson 单调三次 Hermite） | 部分 | `shared/src/commonMain/.../engine/ToneCurve.kt`、`ui/yishun/pro/CurvePanel.kt`、`CurvePoints.kt` | `shared` 的 `ToneCurveTest`、`ProPipelineIdentityTest`；`ProPanelsTest`（33，含曲线上下限与端点锁 x） | **未装机**。数学与 LUT 单调性有 1000 组随机点断言；「拖两下顺不顺手」只有人眼能判 |
| 11 | 一瞬 · HSL 8 色相带（色相 ±30° / 饱和 ±100% / 明度 ±25%） | 部分 | `engine/HslBands.kt`、`ui/yishun/pro/HslPanel.kt`、`HslModel.kt` | `HslBandsTest`（权重和恒为 1、灰阶不染色、往返 ≤1 码）、`ProPanelsTest` | **未装机**。8 带顺序与引擎一致由断言钉住；**单带只影响该色**只有人眼能判 |
| 12 | 一瞬 · 局部蒙版（径向 / 线性，最多 4 个，可调羽化与强度） | 部分 | `engine/LocalMask.kt`、`ui/yishun/pro/MaskPanel.kt`、`MaskModel.kt` | `LocalMaskProbeTest`（中心 1 / 边缘 0 / 羽化单调 / 整场 vs 按行逐位一致）、`ProPanelsTest` | **未装机**。新建蒙版必须非中性（线性 −0.30 EV、径向 +0.30 EV）；**边界有没有硬切**只有人眼能判 |
| 13 | 一瞬 · 自定义配方：保存 / 改名 / 删除 / `SPLOOK1:` 导入导出 | 部分 | `data/CustomLooks.kt`、`ui/yishun/pro/MyRecipes.kt` | `CustomLooksTest`（19） | **未装机**，且**必须在真 release 包上验一次**：`CustomLook` 走 kotlinx-serialization，R8 削掉属性名会让键名变成 a/b/c（`docs/arch/RELEASE.md` §3） |
| 14 | 配方随照片走：调色参数写进 EXIF（v3），可读回重放 | 已完成 | `data/RecipeExif.kt`、`engine/RecipeCodec.kt` | `RecipeCodecTest`、`RecipeV3Test`、`RecipeExifRoundTripTest`（androidTest，钉磁盘字节） | JVM 往返全绿；**磁盘字节那一支是仪器化测试，没有跑过** |
| 15 | 智能配方（可选，总开关默认关闭，自带 Key，只发 256 px 缩略 JPEG） | 已完成 | `data/ai/AiAdvisor.kt`、`shared/.../ai/`、`ui/ai/AiSheet.kt` | `AiLayerTest`、`OpenAiCompatibleTest`、`AiGuardTest`（7）、`TelemetryLineTest`（3） | 装机验证过「关着时点不动」。`AiGuardTest` 源码级钉住出网只许一处、政策文案里的 256 与常量一致 |
| 16 | 相机基础：最大静帧 / JPEG 95–100 / 24 种辅助线 / 10 档画幅 / 水平仪 / 延时 / 连拍 / 水印 | 已完成 | `ui/camera/CameraScreen.kt`、`CameraUiState.kt` | `CamGridGuardTest`（7）、`CamRatioGuardTest`（3）、`CameraStatusTest`（3）、`CameraFocusFeedbackTest`（6）、`CameraTablesTest`（androidTest） | 装机出过图。**水平仪在模拟器上量不出来**（传感器是假的），标「待真机」 |
| 17 | 相机 · 专业模式：ISO 22 档 / 快门 40 档 / EV ±3 / WB 9 档 / 手动对焦 | 部分 | `ui/camera/CameraProState.kt`、`CameraProPanel.kt`、`CameraProBindings.kt`、`CameraScreen.kt`（`applyProNow`） | `CameraProStateTest`（35，含 `histogramNoteFor` 四态与 `exposureMismatchNote`）、`PerfSourceGuardTest.everyProToggleAppliesItsPlan`（源码守卫） | **未在真机上量过，AVD 上也没跑过**。档位表、钳制、降级矩阵、CaptureRequest 计划都有断言。**三处调用点断链已修**（P0-1）：`pro.toggle()` 的两个入口加「这一档」入口现在共用唯一出口 `applyProNow()`，源码守卫钉住「每个 `pro.toggle()` 之后必须 `applyProNow`」；下发失败只 `degradeInMemory()` 且 `readCapsOrNull` 返回 null 表示"这次没读到"（P0-3），不再把用户存盘的 ISO/快门/WB/对焦写成本地墓碑（P0-2 的直方图那句话也抽成 `histogramNoteFor` 纯函数）；拍完回读 EXIF 的 ISO/快门，不一致时状态行说「请求 ISO 400 · 实际 800」（P1-6）。**以上全部是代码 + JVM 单测，装机未验**。抽屉五颗格新加 `cam_pro_slot_*` testTag 供 AVD 取证（P1-9）。**模拟器大概率一整排都置灰**，得用真机判 |
| 18 | 相机 · 亮度直方图（第三路 `ImageAnalysis`，64 桶，step 8，只读 Y 平面） | 部分 | `ui/camera/CameraProBindings.kt`、`CameraProState.kt`（`lumaHistogram` / `histogramNoteFor`） | `CameraProStateTest`（直方图 4 条：计数守恒 / 尊重 stride / 越界安全 / 归一化；另加 `histogramNoteFor` 四态） | **未装机**。三路流在低端机上排不下时降级成两路，那条降级**没在真机上走到过**。**那句话现在是三态纯函数**：`executorShutdown` / `!analysisBuilt` / `!threeStreamBound` 各一句人话，只有三路都绑成才返回空串（改前是"降级原因写一句、下一行又按 `frameSource == null` 重算并覆盖成空串"）。**装机未验** |
| 19 | 拼图：12 套模板 + 边框 / 外边距 / 圆角 / 底色 / 画幅 / 导出 2048 或 4096 + 拖拽换位 | 部分 | `ui/collage/CollageLayouts.kt`、`CollageRenderer.kt`、`CollageScreen.kt` | `CollageLayoutsTest`（11）、`CollagePixelsTest`（17） | **未装机**。模板几何（无重叠 / 无缝隙 / 面积 = 1）逐套断言；拖拽换位与 4K 导出的内存峰值只有人眼 / 真机能判 |
| 20 | 胶片边框（35mm / 120 / 拍立得）+ 漏光（4 角 / 5 档） | 部分 | `ui/collage/CollagePixels.kt`、`CollageLayouts.kt`（`CollageStyle.frame` / `leak`） | `CollagePixelsTest`（强度 0 逐像素不变、四种边框的预期像素、漏光只落在选定角、条带与整幅逐字节相同） | **未装机**。像素层是纯 `IntArray`，所以断言是真的（不是 Bitmap 桩）；**「好不好看」只有人眼能判** |
| 21 | 回忆：六种聚合卡片（纯函数 + 注入时钟） | 部分 | `ui/memories/Memories.kt`、`MemoriesScreen.kt`、`MemoryDetailScreen.kt` | `MemoriesTest`（18，含闰年 / 闰日 / 同月同日合并） | **未装机**。`dateTaken <= 0` 的照片不参与任何卡片，这条有断言 |
| 22 | 幻灯片：2/3/5/10 秒四档、循环、随机（洗牌袋） | 部分 | `ui/slideshow/SlideshowState.kt`、`SlideshowScreen.kt` | `SlideshowStateTest`（15，含 24 个种子量「reroll 会漏片」） | **未装机**。随机不重复的保证是洗牌袋，不是 reroll |
| 23 | 配方卡：把一条配方画成一张可分享的图 | 部分 | `ui/play/RecipeCard.kt`、`RecipeCardScreen.kt`、`ui/yishun/YishunScreen.kt`（入口） | `RecipeCardTest`（11） | **入口已接**：一瞬 ›「更多」› 导出 › 第一颗「配方卡」（`yishun_recipe_card`）→ `AppNavHost` push `AppRoute.RecipeCard`。**未装机**：出图里的配方名/参数、以及 `onRecipeCard == null` 时那颗灰着的行为都只有人眼能判 |
| 24 | 投屏意图（系统能接就交给系统，探测不到返回 null） | 部分 | `ui/play/CastIntent.kt`、`ui/viewer/PhotoViewerScreen.kt`（入口） | `CastIntentTest`（10，四种出口全在 JVM 上走到） | **入口已接**：查看器 ›「更多」› 外部打开 ›「投屏」（`viewer_cast`），先 `CastIntent.decide` 再 `intentFor`，三种结局（精确 / 交给系统 / 未找到）各有自己的话，落在 `AppSheetSection(note = castNote)` 那一行。**未装机**：真机上有没有能接的投屏器、以及系统拒了这一发时的回话都测不到 |
| 25 | 重复照片合并（大小逐字节 + 时间 ≤120s + dHash ≤6，并查集聚类） | 部分 | `data/Duplicates.kt`、`ui/tools/DuplicatesScreen.kt` | `DuplicatesTest`（32） | **未装机**。删除走本地 `ActivityResultLauncher`，**判据 `resultCode > 0` 没在设备上验过**；扫描耗时的「一两秒」是推算不是实测 |
| 26 | 存储分析：6 档堆叠条 / 来源分类 / 最大 20 个文件 | 部分 | `data/StorageInsight.kt`、`ui/tools/StorageInsightScreen.kt` | `StorageInsightTest`（37） | **未装机**。这一屏**没有删除按钮**（只报告）；`onOpenCategory` 不跳转，只在 note 行说出张数 |
| 27 | 设置中心：8 组 + 顶部搜索（37 项） | 已完成 | `settings/SettingsKeys.kt`、`SettingsModel.kt`、`SettingsStore.kt`、`SettingsBridge.kt`、`SettingsSignal.kt`、`ui/settings/SettingsScreen.kt` | `SettingsModelTest`（12）、`SettingsStoreTest`（14）、`SettingsBridgeTest`（29）、`SettingsUiGuardTest`（9） | 模拟器上走进去了（`store/screens/phone2/04-settings.png`，标题栏 37 项）。**读数点现在接完了，只剩三项 PENDING**（年份索引 / 桌面拍照快捷方式 / 缓存过大提醒），只有那三行写「（等接线）」并灰着。`cfg_` 是真源、老键降级为兼容写（`SettingsBridge`）；`SettingsBridgeTest.everyLiveWritableItemReallyHasAReader` 源码级钉住"标了 LIVE 就必须真有读者"。**「当场换主题/字号」这条链路（`SettingsSignal`）未装机验** |
| 28 | 首启引导：3 屏、可滑可跳过、末屏接权限、设置里可重看 | 已完成 | `settings/OnboardingGate.kt`、`ui/onboarding/OnboardingScreen.kt` | `OnboardingTest`（7） | 模拟器上逐屏拍到（`01` / `01b` / `01c` 三张）。完成态是两个条件的与，**幂等**有断言 |
| 29 | 隐私与数据中心：数据去向 / 权限逐项 / 导出 JSON / 清除设置 | 部分 | `ui/privacy/PrivacyScreen.kt` | `SettingsUiGuardTest`（权限表双向核对 + 导出 JSON 格式） | **未在设备上走完整条**。「导出本机数据说明」与「清除设置」的实际落盘只在 JVM 侧验过逻辑 |
| 30 | 导出与恢复：`.json` 备份（7 类内容），REPLACE / UNION 两种合并 | 部分 | `data/Backup.kt`、`ui/settings/BackupScreen.kt` | `BackupTest`（40）、`BackupUiTextTest`（2） | **SAF 路径完全没装机验过**。`"wt"` 模式在真实 `DocumentsProvider` 上的行为未知；恢复后的界面刷新要靠宿主自己重读 |
| 31 | 视频：网格 / 播放 / 分享 / 提帧 | 部分 | `data/VideoItem.kt`、`VideoRepository.kt`、`ui/video/VideoGrid.kt`、`VideoPlayerScreen.kt` | `VideoDurationTest`（20） | 0.7.1 已补 Android 13+ `READ_MEDIA_VIDEO` 按需授权和 Android 14+ 部分视频选择；JVM 判据测试通过，**真机弹窗流程仍未验** |
| 32 | 桌面小组件「最近的照片」+ 3 条静态快捷方式（拍 / 传 / 改）+ 2 条动态快捷方式（最近一张 / 回收站） | 部分 | `widget/WidgetLogic.kt`、`WidgetShortcuts.kt`、`RecentWidgetProvider.kt`、`DynamicShortcuts.kt` | `WidgetLogicTest`（33） | **未装机**。`exported="true"` 与 `shortcuts.xml` 的形状都有源码级断言；组件的实际绘制没在 launcher 上看过。0.8 Beta 已给动态「最近一张」与「回收站」接上 `suipai://recent` / `suipai://trash` 真正跳转，JVM 白名单一致性测试覆盖；**装机未验** |

---

## 表外：明确「未做」的（不是遗漏）

抄自各文档的「没做的部分」章节，集中在这里，免得审核或下一个人以为漏了：

| 项 | 出处 | 为什么不做 |
| --- | --- | --- |
| 峰值对焦 / 手动对焦刻度叠加 | `CAMERA-PRO.md` §6 | 需要在预览上做边缘检测并染色，属「预览替换」级改动 |
| RAW / DNG | 同上 | 需要新的输出格式与存盘路径，超出范围 |
| HDR 包围曝光 | 同上 | 连拍 + 对齐去鬼影，是引擎级工作 |
| 白平衡色彩矩阵校正 | 同上 | 需要 `COLOR_CORRECTION_TRANSFORM` 与双光源处理 |
| 测光模式（点测 / 中央重点） | 同上 | 现有对焦动作已带测光区域，但没有独立选择 |
| 直方图 RGB 分通道 / 波形图 / 斑马纹 | 同上 | 要先做 YUV→RGB，成本 ×3，96dp 宽也看不清 |
| 曲线控制点的数值输入框 | `PRO-EDITING.md` §8 | 无障碍要单独一块活 |
| 蒙版在预览图上的可视化手柄 | 同上 | 要接管预览区全部手势，与现有四条冲突 |
| GPS 反向地理编码 | `SEARCH.md` §7.1 | 国内 ROM 常要联网，与「原图不上传」的承诺冲突 |
| 色彩 / 人像标签 | 同上 | 要一次全库后台任务，代价最大 |
| 视频剪辑 / 转码 / 画中画 / 后台播放 | `VIDEO.md` §5.1 | 产品口径是「只看」，不是「编辑视频」 |
| 备份文件加密 | `BACKUP.md` §10 | 要引 KDF 与分组密码，本轮禁止新增依赖 |
| 增量 / 自动备份 | 同上 | 没有后台任务 |
| 换机后照片 id 对齐 | 同上 | 需要扫全库猜，猜错会把不该收藏的照片收藏起来 |
| 视频重复检测 | `TOOLS.md` §5 | 要另一套（时长 + 首帧哈希） |
| 按分类批量删除（存储分析） | 同上 | 本屏只报告不执行 |
| iOS 侧的专业面板 / 回收站 / 专业相机 / 备份 | `docs/handoff.md` §11.5 | 平台模型不同，属**决议**不是没对齐 |

### 表外 / 已知缺口（有代码，但这张表没有一行装得下）

| 项 | 缺口是什么 | 后果 | 出处 |
| --- | --- | --- | --- |
| **同步拍摄时间（代码已修，真机未验）** | `saveJpeg(bytes, displayName, dateTaken)` 已接收发送端的拍摄时间；`AndroidSyncSink.saveIncoming` 传入同一字段，非法时间回退到当前时刻 | 能避免按接收顺序代替拍摄时间排序；两机互传与 Android MediaStore 行为仍需真机实测 | `data/MediaRepository.kt`、`sync/SyncSupport.kt`、`MediaRepositoryDateTakenTest` |

---

## 文档之间的矛盾（发现就记，别自己圆过去）

下面几条是抄写过程中撞到的。**代码一行没动**；能在本轮写权限内的文档里修掉的已修，
剩下的留在这里等主控裁决。

| # | 矛盾 | 位置 | 本轮处置 |
| :-: | --- | --- | :-: |
| 1 | 单测条数写 **441**（`app/src/test` 43 个文件），而本机实测结果是 **725 条 / 57 个类 / 53 个 .kt** | `GOAL.md` §测试、`CHANGELOG.md` 0.7.0 §测试、旧 `README.md` 徽标 | ✅ 已改成 725 / 57 / 53（带实测日期） |
| 2 | `MainActivity` 行数三处不一致：**251 行**（`GOAL.md` §波段 1、旧 `listing-zh.md`）、**约 330 行**（旧 `README.md`、`APP-ARCH.md` §一），重构后实际 **298 行**；2026-10-29 接上 `SelectionState` 之后是 **313 行** | `GOAL.md` §波段 1、`docs/arch/APP-ARCH.md` §一 | ✅ `GOAL.md` 与 `README.md` 都改成「298（重构后）/ 313（2026-10-29）」；`APP-ARCH.md` §一 也一并订正成 313 |
| 3 | 隐私页权限行被描述成「7 条，与 manifest 逐条对应」，而 manifest 里是 **9 条**（其中 2 行是合并显示） | `CHANGELOG.md` §隐私中心、`store/privacy-policy-zh.md` §三 | ✅ `CHANGELOG.md` 已改成「7 行覆盖 9 条」并说明合并在哪两行；`privacy-policy-zh.md` 的表格本来就是 7 行，口径正确 |
| 4 | `gradle.properties` 的 `-Xmx` 写 **4096m**（`AGENTS.md`），实际是 **2048m** | `AGENTS.md` §Gradle 守护进程 | ❌ 未改（不在写权限内） |
| 5 | `PrivacyScreen.kt` 的 KDoc 说 `PrivacyPermissionTest` 会核对 manifest，**这个测试类不存在**；实际核对它的是 `SettingsUiGuardTest.privacyPermissionTableMatchesTheManifest` | `ui/privacy/PrivacyScreen.kt:284` | ❌ 未改（`.kt` 禁改） |
| 6 | 配方卡 / 重复照片 / 存储分析在 `DEVICE-CHECK.md` 里标「有页面、没有入口」，但 `APP-ARCH.md` §12.1 与 `AppChrome.kt` 已经把后两个接进「更多 › 整理」；**只有配方卡仍然没有入口** | `docs/arch/DEVICE-CHECK.md:222` vs `nav/AppChrome.kt:317-321` | ✅ **本轮已修**：配方卡入口接了（一瞬 › 更多 › 导出 › 配方卡）；`DEVICE-CHECK.md` 那一行也订正了（见本表第 14 条） |
| 7 | `RELEASE.md` 记 0.6.0 基线 APK **3.20 MB** / AAB **6.15 MB**，本次实测 **3.48 MB / 6.93 MB**（0.7.0）。两者不矛盾，但那两个数容易被误读成当前值 | `docs/arch/RELEASE.md:117` | ❌ 未改（不在写权限内）；本表与 `README.md` / `listing-zh.md` 写的都是 0.7.0 的实测值 |
| 7b | **产物体积有两个互相矛盾的"0.7.0"**：文档记 APK **3,645,239 B** / AAB **7,262,827 B**；而磁盘上 `app/build/outputs/{apk,bundle}/release/` 那两个文件（mtime 都是 **2026-10-18 15:59**，`output-metadata.json` 写着 `versionCode 7` / `versionName "0.7.0"`）实际是 **3,722,008 B / 7,416,908 B** | `README.md` §当前构建与产物、`CHANGELOG.md` §产物、`store/listing-zh.md:392` | ⚠ **两个数都留着，不猜哪个对**：本轮没有重新打包，也没有 `apksigner` / `md5` 归属证据能判定磁盘上那份是不是发出去的那份。`README.md` 已经把两个数并列写清来源与 mtime |
| 8 | lint 留存报告是 **0 error / 5 warning**，其中 `DefaultLocale` 2 条所指向的源码**已经改成 `Locale.ROOT`**，`StaticFieldLeak` 1 条指向的是 `Collections.of()` 里那个**刻意**的单例（`applicationContext`，不是 Activity）——报告没重跑 | `app/build/reports/lint-results-release.txt` | ⚠ 报告是产物，**不能手改**；重跑 `:app:lintRelease` 后 warning 数应降到 3 条左右 |
| 9 | `ListingCountsGuardTest` 的 KDoc 说「`store/screens/phone/` 那批图全部是 1080×2400 共 9 张」，实际目录里是 **8 张**（那句话描述的是修好之前的状态） | `app/src/test/.../ListingCountsGuardTest.kt:121` | ❌ 未改（`.kt` 禁改） |
| 10 | `VIDEO.md` §7 说「加 `READ_MEDIA_VIDEO` 要同步改 `PrivacyScreen`，否则 `SettingsUiGuardTest` 会红」——这条是对的，但**该权限从未加过**，所以现状是"视频页能用但读不到别人的视频"；`README` 与 `listing` 里必须这么写，不能写成"视频功能完整" | `docs/product/VIDEO.md` §7 | ✅ 三份文档都按「部分 / 系统没放行视频」写 |
| 11 | `docs/product/` 只有 **9 份**文档，任务书里说 10 份；缺的那一份是 `WIDGET.md` —— 代码注释与 `widget_recent_info.xml` 都在引用它（「细节见 docs/product/WIDGET.md」），但文件不存在 | `app/src/main/res/xml/widget_recent_info.xml`、`widget/*.kt` | ❌ 本轮不新建（不在交付物清单里）；小组件那一行的依据取自 `WidgetLogic.kt` 与 `WidgetLogicTest` 的注释 |
| 12 | 测试条数是个**移动靶**。历史快照：725 / 57（2026-10-18 14:47）。本轮开工基线是 **73 个 result XML / 881 条 / 0 失败 / 0 跳过**（app + shared 合计；这个数是另一个统计口径报的，**开工时那批 XML 已被重跑覆盖，我复测不到**）；我这一轮从产物目录实测到 **77 个 XML / 919 条**（app 61 XML / 784 条 + shared 16 XML / 135 条，2026-10-29，0 失败 / 0 跳过）。919 − 881 = 38 条是本轮新加的用例 | `app/build/test-results/`、`shared/build/test-results/` | ⚠ 本轮所有文档都写成「开工基线 881 + 收工实测 919」，没有把任何一个数说成"永远当前" |
| 13 | `AppNavHost` 从 557 行起一直在写 `collectionsNote`，但 `CollectionsScreen` 没有参数接收它 —— **写了没人读**，用户看不到「建好了 / 改名了 / 删掉了」 | `nav/AppNavHost.kt` vs `ui/collections/CollectionsScreen.kt` | ✅ **本轮已修**：`CollectionsScreen` 加了 `note` 参数并真的渲染出来；同时补了 `selectedCount` / `onAddPhotos` 两参，收藏夹页现在有「加入选中」（`collections_add_selected`） |
| 14 | `DEVICE-CHECK.md` 有两处陈旧：① §4 把「专业模式（关键）」写成抽屉里出现「这一趟取景」分区 —— 那其实是**对焦/手电那一组**的标题，真正的 PRO 段叫「专业模式」；② 222 行说三个页面「全仓只在自己文件里出现一次、也不在 `AppRoute` 里」 | `docs/arch/DEVICE-CHECK.md:99`、`:222` | ✅ **本轮已订正**：99 行改成「专业模式」分区 + 五颗 `cam_pro_slot_*` testTag；222 行改成三个页面的真实状态（重复照片/存储分析从「更多 › 整理」进，配方卡本轮接上） |
