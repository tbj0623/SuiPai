# 随拍 v1.0 · 升级总纲（Lead 版）

> 目标来自用户 2026-10-18 的指令：把 0.6.0 升级成「完完整整、很大体量、可玩性非常高、成熟可上架」的安卓应用，
> 由 Lead 自主决策，多智能体并行推进。
>
> 本文是**这一轮的最高决策文件**：冲突时以本文的顺序与口径为准。技术细节见 `docs/audit/*`、
> `docs/design/DESIGN-SYSTEM.md`、`docs/arch/APP-ARCH.md`、`docs/product/SETTINGS.md`。

## 0. 基线（接手时的事实，已实跑确认）

| 项 | 事实 |
| --- | --- |
| 版本 | 0.6.0 / versionCode 6；debug APK 21 MB，release APK 3.3 MB |
| 代码量 | 232 个 Kotlin 文件；`shared` 引擎 128 文件 ~6 万行（其中 5446 条预设占大头） |
| 构建 | `:app:assembleDebug :shared:testDebugUnitTest :app:testDebugUnitTest` = BUILD SUCCESSFUL（3m35s，冷启动） |
| 测试 | JVM 21 个测试文件（含 12 个「源码扫描守卫」）、仪器化 15 个文件、shared 单测若干 |
| 设备 | `suipai_gapi`（API 35 google_apis x86_64 1080×2400）可用；本机 14.3 GB 内存是硬约束 |
| 已有 | 时间线/相簿/查看器/相机(CameraX)/一瞬(5446 预设)/AI 适配/局域网互通/批量动作/EXIF 配方往返 |
| 缺 | 设置中心、首启引导、回收站、搜索历史、自建收藏夹、曲线/HSL/局部调整、拼图排版、回忆、幻灯片、专业相机控制、视频 |

## 1. 产品定位（不改）

「摄影师的暗房接触印样」——相册是主体，相机是入口，一瞬是手艺。
**不改画面内容**（不是美颜、不是 P 图）；**原图不上传**；**原图永远保留**；中文优先。

## 2. 这一轮要做到什么样（验收画像）

一个从没装过的人，第一次打开到第 30 分钟，应该经历：

1. **首启**：三屏引导说清「这是相册 / 一瞬做什么 / 照片不出手机」→ 授权 → 落进时间线。
2. **翻**：时间线按日分组、双指缩放切换 日/月/年 密度、右侧年份轨快速跳、顶部搜索能按「相机型号/地点/文件名/日期」找。
3. **看**：查看器全屏、双指缩放、下滑退出、上滑信息面板（EXIF + 直方图 + 配方还原状态）、左右翻页。
4. **拍**：内置相机，点按快门、长按连拍、专业模式（ISO/快门/EV/白平衡/手动对焦）、水平仪、网格、水印、画幅。
5. **冲**：一瞬里选配方/智能适配，**自定义曲线 + HSL + 局部径向/线性调整**，保存为自己的配方并命名；
   对比原图；导出成片（配方写进 EXIF）。
6. **收**：收藏、自建收藏夹、隐藏、**回收站（30 天后才真删，可恢复）**、批量一瞬、批量导出/分享。
7. **玩**：拼图排版（2/3/4/6 格模板 + 边框 + 留白）、胶片边框/漏光模板、回忆（那年今日自动聚合）、
   幻灯片播放、把一瞬配方分享成一张「配方卡」。
8. **控**：设置中心（外观/相册/相机/一瞬/隐私/存储）、存储管理、清除数据、关于。

## 3. 执行波段（每波段结束后必须：编译过 + 单测全绿 + 装机走通主路径）

### 波段 1 · 地基（并行 3 条 lane）
| Lane | 交付 | 状态 |
| --- | --- | --- |
| writer-design | 设计系统 v1：token / 字级 / 动效 / 组件层级 / 两套主题 | 进行中 |
| writer-arch | AppState + BackStack + AppNavHost，MainActivity 瘦身，行为零变化 | 进行中 |
| writer-settings | 设置中心 + 首启引导 + 隐私中心（数据层 + 页面） | 进行中 |

### 波段 2 · 体量（并行 4 条 lane，依赖波段 1 的 AppRoute/设计 token）
| Lane | 交付 |
| --- | --- |
| writer-trash | 回收站（软删除 + 30 天清理 + 恢复）、收藏夹（用户自建相册）、存储管理 |
| writer-search | 搜索增强：搜索历史/建议、按相机型号/地点/文件名/日期、结果分组 |
| writer-yishun-pro | 一瞬专业版：自定义曲线（控制点）、HSL 分色、局部径向/线性调整、自定义配方保存/导入导出 |
| writer-camera-pro | 相机专业模式：ISO/快门/EV/WB/手动对焦、水平仪、峰值对焦、直方图 |

### 波段 3 · 可玩（并行 3 条 lane）
| Lane | 交付 |
| --- | --- |
| writer-collage | 拼图排版（多格模板 + 边框 + 留白 + 拖拽换位）、胶片边框/漏光模板 |
| writer-memories | 回忆（那年今日/自动聚合）、幻灯片播放、投屏意图 |
| writer-ship | 商店素材重拍、文案更新、发布说明、版本号、release 签名与 AAB |

### 波段 4 · 收口
- 全量回归：`:app:testDebugUnitTest` / `:app:testReleaseUnitTest` / `:shared:testDebugUnitTest` /
  `:app:compileDebugAndroidTestKotlin` / `:app:assembleRelease` / `:app:bundleRelease` / `:app:lintRelease`
- 模拟器端到端：冷启动 → 引导 → 拍照 → 一瞬 → 保存 → 回收站恢复 → 设置 → 搜索
- 设备关停（`adb -s <serial> emu kill`）+ `gradlew --stop`
- 文档：`README.md`、`docs/`、交接文档

## 4. 纪律（所有 lane 通用）

1. **构建只走 `pwsh tools/abuild.ps1 <task...>`**（文件锁串行化，避免多 lane 同时起 Gradle 打爆内存）。
   不要跑 `assembleRelease / bundleRelease / lintRelease` —— 由 Lead 在波段末统一跑。
2. **设备只走 `pwsh tools/emu.ps1 start|stop|serial`**；同一时刻只允许一台模拟器、只允许 lane 自己串行用。
   收尾必须 `pwsh tools/emu.ps1 stop`。
3. **文件所有权**：只改分给你 write_scopes 内的文件。要动别人的文件 → 在报告里写"需要谁改什么"。
4. **判据纪律**（这个项目反复栽过的）：每条新守卫都要有「故意喂坏例子，亲眼看到它变红」的自检；
   守卫的证据区间不含被检词的定义处；**量不到就说量不到，不许把"没验"写成"已修"**。
5. **中文**注释与文案，跟随既有风格（看 `IdLists.kt`、`PerfSourceGuardTest.kt`）。
6. 不新增依赖、不改 versionCode/versionName（只有 writer-ship 在最后一波改）、不 push、不上传。
7. 每次完工留证据：命令 + 退出码 + 关键输出行，写进报告。

## 5. 与既有守卫的关系

`app/src/test` 里 12 个「源码扫描守卫」用正则读源码文本，重构挪文件/改函数名会让它们变红。
**它们不是障碍，是长期约束**；变红时改断言路径、不改断言意图，并把改动记进对应 lane 的报告。
