# Talky Android 客户端

**聊天、群组，以及对话里的个人 AI 助手 Jarvis。**

[官网](https://talkytalky.im/zh/?utm_source=github&utm_medium=readme&utm_campaign=talky_android) · [下载](https://talkytalky.im/zh/download.html?utm_source=github&utm_medium=readme&utm_campaign=talky_android) · [构建说明](docs/BUILDING.md) · [iOS 源码](https://github.com/mailes/talky-ios) · [English](README.md)

这是 Talky 安卓客户端源码，基于 Telegram Android 和 OwpenGram 开发。支持消息、群组、媒体分享、语音视频通话、主题、贴纸及 Jarvis 助手入口。AI 功能的具体可用性取决于服务配置和订阅。

官网当前版本为 **13.2.2（O9），versionCode 73049**。请通过官网下载正式 APK。本仓库只公开安卓客户端，不包含后端部署凭据、签名私钥或 AI 模型。

## 从源码构建

需要 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 27.2.12479018 和 CMake 3.22.1。递归获取子模块后，复制 `private-build.properties.example` 为被 Git 忽略的 `private-build.properties`，填写自己的 API 及可选服务配置，执行 `scripts/build-android.sh debug`。

调试包使用 Android 标准调试签名，包名增加 `.debug`，可与官网正式版共存。签名发布包需要自己的密钥和本地签名口令，详见 [构建说明](docs/BUILDING.md)。

首个公开快照以 O9 源码为基础，另做了凭据外置、调试签名分离和公开文档调整，不声称与官网 APK 逐字节一致。原私有 Git 历史、Google/Huawei 项目配置及发布签名材料不进入公开仓库。

欢迎贡献可复现的问题、文档和聚焦的改进。不要在 Issue 或提交中放入私人聊天、密钥或验证码。沿用上游 GPL v2 或更新版本许可，并保留第三方署名，见 [LICENSE](LICENSE)、[NOTICE.md](NOTICE.md) 和 [SECURITY.md](SECURITY.md)。
