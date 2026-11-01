# 随拍长期目标

面向安卓与 iPhone 用户的相册应用，内置高像素相机，用「一瞬」把普通直出做成更高级、更有质感的照片。不是美颜，不是 P 图，不改画面内容。

未接到停止指令前持续推进，逐步完善，不把某一阶段当成终点。

## 产品

- 主体是相册：时间线、文件夹、大图浏览。
- 内置相机：CameraX/AVCapture 最大静帧、JPEG 95–100、原字节落盘；能开就开厂商 HDR/夜景 Extensions。
- 系统相机作为备选入口，拍完回到随拍做一瞬。
- 一瞬两条入口并用：
  - AI 智能适配：分析 EXIF、直方图、主体，选预设当底并微调。
  - 预设库：日光、人像、暖室、夜色、银盐、胶片等 5400+ 方案。
- 原图保留，可对比、换方案、调强度、保存、还原。
- **双端互通**：同一 WiFi 下 Android ↔ iOS 直传照片与一瞬配方（协议见 SYNC.md），不经云端。
- **配方随照片走**：导出照片的 EXIF 带调色参数，任何一端打开都能一键还原。
- 原图不上传。不使用徕卡商标、机型名、官方 Look 名。

## 技术

- Kotlin Multiplatform：调色引擎、预设、场景识别、互通协议双端共享一份代码。
- Android：Jetpack Compose + CameraX。iOS：SwiftUI + AVFoundation，引用 SuiPaiKit 共享框架。
- 调色走端侧色彩科学管线（影调曲线、分离色调、动态颗粒、HSL 分色、局部蒙版），
  不上生成式大模型，不接免费修图 API。
- 互通走局域网直传（UDP 发现 + TCP 帧协议），无服务端依赖。

---

## 当前状态（0.7.0 · 2026-10-18；文档与代码对齐复核于 2026-10-29）

### 版本

`versionCode 7` / `versionName "0.7.0"`（由主控在发版时改 `app/build.gradle.kts`）。
**2026-10-29 那一轮没有改版本号、没有打包、也没有装机。**

> **一句话现状**：代码上又还清了三笔旧账（设置键统一 / 回收站拍摄时间 / 选择集与刷新门控），
> 接上了三处"有页面无入口"（配方卡 / 投屏 / 收藏夹加照片），
> 修掉了相机专业模式三处"界面在说谎"，并给互通加了浏览翻页。
> **这些全部是「代码完成 + JVM 单测」，一条都没装机验证** ——
> 逐项状态看 `docs/feature-matrix.md`，装机作业单看 `ANDROID-VERIFY.md` §45.2。

### 已完成的三波

**波段 1 · 地基**（设计系统 + 架构 + 设置）

| Lane | 交付 | 状态 |
| --- | --- | --- |
| 设计系统 v1 | `ui/design/`：Spacing / SuiPaiType / SuiPaiTokens / Motion / Surfaces / Components；`docs/design/DESIGN-SYSTEM.md` | ✅ 代码完成 |
| 架构重构 | `state/`（AppRoute / BackStack / NavPolicy / NavState / AppState / SelectionState / RefreshGate / BatchActions / DeleteFlow / ShareImport）+ `nav/`（AppNavHost / NavRender / AppChrome）；`MainActivity` 1172 → 298 行（2026-10-29 接上 `SelectionState` 后 313 行，仍是那 7 件事） | ✅ 代码完成，行为零变化 |
| 设置中心 | `settings/`（Keys / Store / Model / Bridge / Signal / OnboardingGate）+ `ui/settings/`（主屏 / 外观 / 存储 / 关于）+ `ui/onboarding/` + `ui/privacy/` | ✅ 代码完成，模拟器上走到过主屏。**2026-10-29：`cfg_` 变真源、老键降级为兼容写，绝大多数项已 `LIVE`** |

**波段 2 · 体量**（回收站 / 搜索 / 专业调色 / 专业相机）

| Lane | 交付 | 状态 |
| --- | --- | --- |
| 回收站与收藏夹 | `data/Trash.kt` + `ui/trash/`（30 天软删除、恢复、到期清理）；`data/Collections.kt` + `ui/collections/` | ✅ 代码完成。**2026-10-29：回收站保留原始 `DATE_TAKEN` + `Trash.of` 单例；收藏夹补上「加入选中」入口** |
| 搜索增强 | `data/SearchIndex.kt`（全库索引落盘）+ `data/SearchHistory.kt` + `ui/search/`；按日期/相册/收藏/EXIF/文件名/文字 | ✅ 代码完成 |
| 一瞬专业版 | shared 引擎 `ToneCurve` / `HslBands` / `LocalMask`；`data/CustomLooks.kt`（自定义配方存取导入导出） | ✅ 代码完成 |
| 相机专业模式 | 见 `ui/camera/` 现有表驱动实现 | ⚠️ 部分完成，详见 `docs/feature-matrix.md` 第 17 / 18 项（2026-10-29 修掉三处"界面在说谎"，**AVD 与真机都没跑过**） |

**波段 3 · 可玩**（拼图 / 回忆 / 幻灯片）

| Lane | 交付 | 状态 |
| --- | --- | --- |
| 拼图 | `ui/collage/`：12 套模板、拖拽换位、边框/边距/圆角/底色/画幅/导出尺寸 | ✅ 代码完成 |
| 回忆 | `ui/memories/`：六种聚合卡片，纯函数 + 注入时钟 | ✅ 代码完成 |
| 幻灯片 | `ui/slideshow/`：2/3/5/10 秒四档、循环、随机 | ✅ 代码完成 |

### 测试（本机实测）

> **条数是个移动靶。** 下面的数都带来源与时间，别把任何一个当成"永远当前"。
> 量不到就说量不到 —— 换一台 `build/` 被清过的机器，这些数都得重跑。

- **919 条 = 我这一轮实测的当前产物**（2026-10-29 用脚本逐个读
  `app/build/test-results/testDebugUnitTest/*.xml` 与 `shared/build/...` 的 `tests=` 属性）：
  **77 个 result XML / 0 失败 / 0 跳过**，拆开是 app 侧 **61 个 XML / 784 条**、
  shared 侧 **16 个 XML / 135 条**。XML 的落盘时间是 app 16:19、shared 15:33。
- **881 条 = 「本轮开工基线」**（73 个 result XML / 0 失败 / 0 跳过）。
  **这个数我复测不到** —— 开工时的那批 XML 已经被这一轮的重跑覆盖在同两个目录里了。
  919 − 881 = **38 条**，就是这一轮各 lane 新加的用例。
  本轮新增的几族：`SettingsBridgeTest`（29）、`RefreshGateTest`（10）、`SelectionStateTest`（6）、
  `SyncRemotePagingTest`（9，shared）、`RouteBranchGuardTest`（3）、`CameraProStateTest` 长大到 35。
- 历史快照（仅供对照，不要再引用成当前值）：2026-10-18 14:47 那次是 725 条 / 57 个类。
- 15 个测试类是**源码扫描守卫**（`*GuardTest`），钉住那些"编译得过、跑得起来、
  只在设备上才暴露"的形状：横向滚动、无名控件、无活区、键名撞车、能力夸大、权限表与 manifest 不一致、
  "标了 LIVE 就必须真有读者"。每条守卫都带「喂坏例子必须变红」的自检。
- 仪器化测试 **69 个 `@Test`**（`app/src/androidTest` 14 个文件，脚本逐个 `grep -c "@Test"` 数出来的）
  —— **本轮没有跑过仪器化，只验证过 `compileDebugAndroidTestKotlin` 能过**。
  （历史数字写的是 68；多出来的那一条是 `SyncPeerInteropTest.unGrantedClientIsRefusedAndToldWhy`。）
- release APK **3.48 MB**（3,645,239 B）、release AAB **6.93 MB**（7,262,827 B）；
  `:app:lintRelease` **0 error**（留存的报告里有 5 条 warning，其中 3 条所指向的源码已修，报告未重跑）。
  **这三个产物数字属于 0.7.0 那次构建，本轮没有重新打包。**
  ⚠️ 2026-10-29 复核：磁盘上那两个 release 产物（mtime 2026-10-18 15:59）实际是
  **3,722,008 B / 7,416,908 B**，与上面记的对不上 —— 两个数都留着，见 `docs/feature-matrix.md` 第 7b 条。

### 文档

`docs/arch/APP-ARCH.md`、`docs/design/DESIGN-SYSTEM.md`、`docs/engine/COLOR-PRO.md`、
`docs/product/{SETTINGS,SEARCH,PLAY,TRASH-COLLECTIONS,PRO-EDITING,CAMERA-PRO,TOOLS,BACKUP,VIDEO}.md`、
`docs/arch/{RELEASE,DEVICE-CHECK}.md`、`docs/handoff.md`、`docs/press-kit.md`、
`docs/feature-matrix.md`、`docs/ROADMAP.md`、`ANDROID-VERIFY.md`、
`store/{listing-zh,release-notes-zh,privacy-policy-zh}.md`。

---

## 阶段

0. 工程骨架与工具链 ✓
1. 相册 ✓
2. 内置相机 ✓
3. 调色引擎与预设库 ✓
4. 智能适配 ✓
5. 对比、撤销、导出、批量、低端机与稳定性 ✓（持续打磨）
6. 双端化：KMP 共享层 + iOS 壳 + 局域网互通 ✓（**iOS 壳仍待 Mac 首次编译验证**）
7. 成熟度：设置中心 / 引导 / 隐私中心 / 回收站 / 收藏夹 / 全库搜索 / 拼图 / 回忆 / 幻灯片 / 专业调色 ✓（代码完成，**多数待装机验证**；2026-10-29 又补齐了设置键统一与三处入口，同样**未装机**）
8. 之后持续：互通增强（**浏览对端相册翻页已接**，多选收发仍缺）、更多风格、场景模型、性能、iOS 细节对齐

---

## 剩余待办

### 上架阻塞项（必须做）

- [ ] **真机验证闭环**：把所有标「已实现（待装机验证）」的功能在真机上走一遍，
      结果写进 `ANDROID-VERIFY.md`。涉及设置中心、引导、隐私中心、回收站、收藏夹、
      搜索、拼图、回忆、幻灯片、专业调色。
      **2026-10-29 又欠了一笔**：那一轮的设置键统一 / 三处入口 / 三条技术债 / 相机专业模式 /
      互通翻页**一次装机都没做**，作业单已写好（`ANDROID-VERIFY.md` §45.2 A–F），
      照着走就能把这一笔补上。
- [ ] **商店截图整组重拍**：现有 8 张是 0.6.0 之前的模拟器合成场景，新屏都没进组。
- [ ] **隐私政策 URL**：内容已备好（`store/privacy-policy.html` +
      `store/privacy-policy-zh.md`），只差挂到可访问地址；两处联系方式占位符要替换。
- [ ] **Android 16 实测**：预测式返回（未接线）、edge-to-edge（只在部分界面）、
      分屏与折叠屏换外屏。
- [ ] **Play Console 三份问卷**：内容分级、数据安全、目标受众。
- [ ] **iOS 首次编译**：`shared` 的 iOS target 与 SwiftUI 壳的边界至今没有编译证据。

### 技术债（可排在后面）

- [x] 旧设置键统一。`settings/SettingsBridge.kt` 是 `cfg_` 新键与老键之间**唯一**的一座桥，
      **`cfg_` 是真源、老键降级为兼容写**（只写不读，供降级安装用）；`SettingsStore.commit()`
      单点落盘（一次 edit 写 `cfg_` + 老键），`syncFromLegacyBool/Int` 收界面自己拨老键那一路；
      读侧是三个迁移感知扩展 `settingsBool` / `settingsInt` / `storedChoiceName`
      （**存过就按 cfg 读，没存过才看老键**）。`SettingsModel` 里绝大多数项已标 `LIVE`，
      **仍剩三项 PENDING**（年份索引 / 桌面拍照快捷方式 / 缓存过大提醒）。
      （代码完成 + JVM 单测；**装机未验**）
- [x] 回收站恢复时保留原始 `DATE_TAKEN`。`TrashItem.dateTaken` + 账单字段 + 搬家/恢复
      两头都写；老 `trash.json` 缺键解成 0（不写 `DATE_TAKEN`，与改前一致）。
      顺手加了 `Trash.of(context)` 进程级单例，修掉三个实例各持一把锁的账单覆盖。
      （代码完成 + JVM 单测；**装机未验**）
- [x] `AppState.selectedIds` 进 Saver（进程被杀后不丢选择）。拆出 `state/SelectionState.kt`
      （`MAX_SAVED = 5000`，超限**整体不存**而不是截断），由 `MainActivity` 的
      `rememberSaveable` 持有，`AppState.selectedIds` 转发。
      （代码完成 + JVM 单测；**装机未验**）
- [x] `AppState.refresh()` 不再每次 `ON_RESUME` 全量重读四份清单。
      两步：`loadByIds` 实例复用（同 id 集合 + 内容相同 → 同一个 List）+ `state/RefreshGate.kt`
      按 MediaStore generation + 60 s TTL 门控（另有 `jumpUri` / `libraryDirty` 两个内部必读信号）。
      `refresh(force: Boolean = false)`；下拉刷新 / 备份恢复 / 分享导入 / 同步接收 /
      重复照片清理 / 拼图落盘 / 提帧保存都走 `force = true`。
      **没有**改成"按 `albumId` 只读一份"
      （`photos` 是搜索/收藏/回忆/幻灯片/页眉的共同数据源，只读当前屏会让退页后陈旧）。
      （代码完成 + JVM 单测；**装机未验** —— 内存与帧时间的收益也没量）
- [ ] 相机专业模式补齐（见 `docs/feature-matrix.md` 第 17 / 18 项）。三处调用点断链已在代码上修好，
      但 AVD 与真机都还没跑过。
- [ ] **`MediaRepository.saveJpeg` 加 `dateTaken` 参数**（`sync/SyncSupport.kt` 的 `AndroidSyncSink`
      已经把拍摄时间收下了却传不进去）。现状是**同步收到的照片时间线顺序会乱**：
      按「收到的先后」落 `DATE_TAKEN`，一次收十张是同一个毫秒。
      记在 `docs/feature-matrix.md` 的「表外 / 已知缺口」与 `docs/handoff.md` §7。
- [ ] 互通增强：多选收发（翻页已接：`SyncScreen.BrowsePane` 的「加载下一页」+ 末页置灰）。

### 长期

- [ ] 更多风格与场景模型
- [ ] iOS 细节对齐
- [ ] 性能与低端机继续打磨

---

## 完成标准（本目标本身不设「做完」）

每一轮都要留下可编译、可安装的进展。相册、拍照、一瞬三条主路径要能在真机或模拟器上走通，再在这条路上加深质感与稳定性。

**并且**：量不到就说量不到，不把"没验证"写成"已完成" —— 这是这个仓库最硬的一条纪律。
