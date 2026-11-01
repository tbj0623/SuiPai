# 随拍公开预览版 · 验证状态

更新：2026-11-02。这里区分「代码完成」「编译通过」「真机实际验证」三个概念。

| 项目 | 当前证据 | 尚未完成 |
| --- | --- | --- |
| Android / shared JVM 单测 | 本机执行 `:app:testDebugUnitTest :shared:testDebugUnitTest` 通过；媒体写入事务新增单测单独通过 | 测试不替代真实 MediaStore |
| Android 主代码 | `assembleRelease`、`bundleRelease`、`lintRelease` 本机成功；但正式 keystore 缺失，仅生成未签名 release APK | 正式签名包、不同系统版本及真机回归 |
| 图片保存失败回滚 | 成功、流失败、发布失败、异常路径的纯 Kotlin 回归测试已通过 | 真机模拟磁盘满/提供方失败 |
| 视频读取授权 | 0.7.1 代码补齐 Android 13+ 视频单独授权入口、Android 14+ 部分选择；纯 Kotlin 判据测试通过 | Android 13/14/15 真机授权弹窗、拒绝与补选流程 |
| 回收站完整性 | 0.7.1 改为 `AtomicFile` 账单、先记账再删原图、发布影响 0 行视为失败、删除结果未知时保留账单；JVM 回归通过 | 在真机断电/杀进程及 MediaStore 提供方故障下实测 |
| 相册、相机、一瞬、回收站、搜索 | 已有代码与自动化测试 | 完整手动装机验收未闭环 |
| 局域网互通 | 共享协议有测试 | 两台真实 Android/iPhone 同 Wi-Fi 互传 |
| iOS | SwiftUI 壳与 KMP 接口源码存在 | 必须在 Mac / Xcode 首次编译与运行 |
| 商店上架 | 有商店素材与政策草稿 | 开发者联系方式、真机截图、审核问卷、正式隐私政策 URL |

**不发布为 Google Play / App Store 正式版**，直到上述发布阻塞项完成。GitHub 可提供带明确风险说明的预览代码或测试 APK。

若你要贡献设备验证，欢迎在 Issue 中提供机型、Android 版本、可复现步骤及脱敏日志；
不要附带私人照片、GPS EXIF 或 API Key。
