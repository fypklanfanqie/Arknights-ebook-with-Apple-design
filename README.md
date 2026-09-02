# Arknights Reader

> **🚧 本项目仍在积极开发中（Work in Progress）。** 当前仓库包含翻页引擎与诊断界面，书架、导入、阅读器等核心功能尚未完成，界面与 API 随时可能变动。欢迎关注，但不建议作为日常阅读工具使用。

一个原生 Android 电子书阅读器，目标是将 [明日方舟小说网页版](https://github.com/fypklanfanqie) 的"真实纸张翻页"体验重构为 Kotlin 原生实现：可展圆柱卷曲几何、任意角拖拽、中途悬停、松手按进度/速度结算——而不是普通的旋转动画伪装。

## 当前状态（2026-09）

**已完成并通过四轮代码审查的部分：**

- **翻页数学内核**（`reader:turn`，纯 Kotlin/JVM，无 Android 依赖）
  - `CurlSolver`：可展圆柱卷曲求解——双铰链可达域约束、弧长守恒三段映射（平面/圆柱/背面）、零拖动安全、全输入有限值保护。与网页版参考实现数值对齐至 1e-9。
  - `TurnGesture`：纯手势状态机（idle → pressing → arming → dragging → settling）——单指针事务、slop 方向锁定、新鲜速度判定、全部中断路径确定性取消。
  - `CurlMesh`：接缝对齐网格生成器——三角形沿 d=0 与 d=πr 两条变形分界线精确裁剪，消除斜向撕裂。
- **GL 渲染层**（`reader:turngl`，Android library）
  - 与求解器数值一致的 GLSL 三段映射着色器、双面材质（半纸厚分离）、VBO 复用（拖拽零分配）、按需渲染（静止时 GPU 零工作）。
  - 纹理朝向、投影覆盖、线程契约均经独立审查验证（含 AOSP 源码取证）。
- **Page Curl Lab**（`app` 内诊断 Activity）：触摸 → reducer → 求解 → 网格 → GPU 的全链路真机验证入口。
- **131 项 JVM 单元测试**全绿，TDD 全程（RED→GREEN 证据在提交历史中）。

**尚未开始（路线图）：**

- 书架 / 文件导入（SAF）/ 目录 / 阅读位置持久化
- EPUB、TXT、PDF 等格式解析
- Room 数据库、书签、高亮批注、全文搜索
- 液态玻璃 UI（参考 Kyant0/AndroidLiquidGlass 与 Shapes）、阅读设置
- 内置内容迁移（仅限本地验证）

## 构建

```text
./gradlew :app:assembleDebug          # 构建 debug APK
./gradlew :reader:turn:test :reader:turngl:testDebugUnitTest :app:testDebugUnitTest
```

- JDK 17、Android SDK Platform 35、`minSdk 26`
- `local.properties`（指向本机 Android SDK）不入库
- 首次构建需联网下载 Gradle 8.9 Wrapper 发行版与 AndroidX/Compose 依赖

真机验证翻页效果：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lfq06.arknightsreader/.lab.CurlLabActivity
```

详细引擎管线、着色器契约与线程模型见 [docs/turn-engine.md](docs/turn-engine.md)。

## 内容与版权边界

- **非官方项目**，与《明日方舟》（Arknights / Hypergryph / Yostar）无任何关联；名称仅用于描述个人兴趣用途。
- 计划中的内置剧情内容**仅限本地验证**，来源于 PRTS Wiki 的公开整理，不构成内容分发服务；正式公开发布前需另行确认素材授权，届时可能移除。
- **非 DRM 政策**：本项目不实现、不讨论、不容忍 DRM 破解或访问控制绕过。未来的本地导入仅处理用户拥有合法使用权的无 DRM 文件。

## 许可

代码部分待定（倾向选择宽松许可；在确定前默认保留所有权利）。第三方依赖在使用前将逐一审计许可并归档 NOTICE。

## 致谢

- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 与 [Kyant0/Shapes](https://github.com/Kyant0/Shapes) —— 液态玻璃视觉参考（计划中）
- 自研网页阅读器的可展卷曲实现 —— 本项目翻页数学的行为规格来源
