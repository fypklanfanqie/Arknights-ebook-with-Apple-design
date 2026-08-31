# Arknights Reader

这是一个面向本地验证的 Jetpack Compose Android 阅读器起步工程，`applicationId` 为 `com.lfq06.arknightsreader`。

## 当前状态

- `app` 提供可启动的极简 Compose 占位界面。
- `reader:turn` 是不依赖 Android 的纯 Kotlin/JVM 模块，包含有限值安全的页面翻页几何内核及 JUnit 测试。
- 当前任务不包含 Room、文件导入、格式解析、EGL 渲染或液态玻璃效果。
- 尚未承诺 EPUB、PDF、CBZ 或其他格式支持；这些能力留给后续任务。

## 本地 APK 与内置内容边界

Debug APK 仅用于本地开发、设备验证和功能实验。任何内置示例内容都只用于本地验证，不构成内容分发服务，也不代表官方授权。发布 APK 前应由项目维护者确认素材的来源、授权和移除流程；本仓库不提供第三方内容下载或在线同步。

## 非 DRM 政策

本项目不实现 DRM 破解、密钥提取、绕过访问控制或规避版权保护。后续若增加本地文件能力，应仅处理用户拥有合法使用权且由用户主动提供的内容，并遵守适用法律与内容许可。

## 构建

项目使用 Gradle Version Catalog、Gradle Wrapper、JDK 17、Android API 35，并将 `local.properties`（指向本机 Android SDK）排除在 Git 之外。常用命令：

```text
./gradlew :reader:turn:test
./gradlew :app:assembleDebug
```

首次构建可能需要联网获取 Gradle、Android Gradle Plugin、Kotlin 和 AndroidX 依赖；构建环境也必须配置 JDK 17。
