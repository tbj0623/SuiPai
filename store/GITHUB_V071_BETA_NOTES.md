# 随拍 0.7.1 Beta 1 · Android 预览版

这是开放源码测试版本，不是 Google Play / App Store 正式版。APK 使用 Android Debug 签名，仅供愿意自行验证的测试者安装。

## 本轮修复

- Android 13+ 视频页增加按需授权入口：首次照片授权不会顺手索取视频权限。
- Android 14+ 区分全部 / 部分授权，支持补选视频；拒绝、权限未知、查询失败不再显示成「视频库为空」。
- 回收站账单迁移至 Android `AtomicFile`（完整写入后才替换）；把「记录恢复入口」移到「删除原片」之前。
- 回收站发布影响 0 行、删除失败、存在性查询未知均不再被乐观算作成功。
- 公开仓库 CI 升级官方 GitHub Actions，并把 Android lint 纳入每次推送检查；修复 Gradle 包装脚本执行位。
- 更新隐私中心、隐私政策与公开状态记录，使权限说明与清单一致。

## 验证与已知限制

- 本机 Android 和 shared JVM 测试、Debug APK 编译已通过；本机 `:app:lintDebug` 也成功。
- GitHub Actions 仍应提供独立构建证据；本版发布时请以仓库 Actions 页面为准。
- 无已连接 Android 真机，**没有完成权限、相机、回收站、局域网互传的设备级回归**。不要在只有一份的重要照片上测试删除/恢复。
- iOS 代码尚未在 Xcode 编译；正式 release keystore 缺失，本版不是商店可上传的签名构建。
- 隐私政策的正式开发者联系信息、商店账号和生产上架资料仍未齐备。

## 安装提示

文件名含 `debug`，Android 包名为 `com.suipai.app.debug`，与未来正式版分开。Android `versionCode=8`、`versionName=0.7.1-debug`，可从此前同一调试签名的 0.7.0 Beta 测试包升级。请仅从本仓库 Releases 下载、核对发布页 SHA-256。
