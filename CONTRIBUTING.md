# 参与贡献 / Contributing

感谢关注随拍。当前版本是公开预览版，尚未完成所有真机验证。欢迎提交可复现的问题与小范围修复。

## 本地构建

- JDK 17、Android SDK 36；Android Studio 近期稳定版。
- Windows: `gradlew.bat :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug`
- macOS/Linux: `bash ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug`
- iOS 壳需 macOS + Xcode；目前 **未有已验证的首次编译记录**，请勿把 Android 通过推断为 iOS 已通过。
- 不要提交 `local.properties`、`keystore.properties`、签名密钥、API Key、个人照片或构建产物。

## 报告问题

请写明：版本、Android 机型/系统、复现步骤、预期与实际行为、已遮去个人内容的日志。
涉及照片丢失/回收站/导出时，优先提供可匿名复现的测试图。

## 提交变更

1. 先看 [项目目标](GOAL.md)、[架构](docs/arch/APP-ARCH.md) 与 [验收清单](ANDROID-VERIFY.md)。
2. 尽量一次只解决一个问题，说明为什么要改及回归风险。
3. 追加相关单测；涉及系统相册、相机、权限、文件写入的，提供设备侧验证证据。
4. 提交 PR 时写明运行过哪些测试，**没运行的不写成已通过**。

## 许可证

代码按 [MIT](LICENSE) 许可证发布。第三方库和素材各自保留其原有许可证；请勿擅自将第三方品牌或用户照片提交到仓库。
