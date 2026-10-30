# Compose 原生迁移回退基线

2026-10-30，在开始原生页面迁移之前保存全部已有 UI、GPU WebView 采样及设计文档。

- 基线提交：`09c02c1d4c20a9cf42e19a136ce7b08a9a5237f4`
- 回退标签：`rollback/pre-compose-native-20261030`
- 迁移分支：`codex/compose-native-v1`
- 基线 APK：`release/STU-assistant-1.0.31-gpu.1.apk`（8,780,577 字节）
- APK SHA-256：`204210101bcefbf76f9ed9eede21d58a10c9ffc949f10a99c2d12e9b46bda41f`
- 基线包：`com.stuassistant.app.haptics`，versionCode 32，debug 证书，非 debuggable。
- 工具链：Gradle 9.3.1、AGP 9.1.1、Kotlin/Compose compiler 2.4.21、JDK 21，Compose 1.12.0、Backdrop 2.0.1、Shapes 1.2.1。

如需检查旧源码，先保存当前工作，再从标签创建单独分支：`git switch -c codex/inspect-pre-compose rollback/pre-compose-native-20261030`。不要对有未保存改动的目录执行强制 reset。安装 APK 回退前需确认签名与版本号限制，并保留设备数据备份；该文档不代表已执行回退。

首版仅将计划与作业列表合入「事项」，导航为今天、课表、事项、校园、我的。其他页面严格按现有 JS 复现，允许按 DESIGN.md 修改圆角，以及在功能类型一致处使用固定上游源码的液态玻璃组件。
