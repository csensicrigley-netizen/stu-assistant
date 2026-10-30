# Android CLI 开发与验证

本机使用 Google 官方 Android CLI。运行 `android --version` 查看版本，`android info` 查看 SDK 路径。Codex 的配套技能通过 `android init` 安装。

当前工程是 React + Capacitor，网页构建仍需先执行 Vite 与 `cap sync`；Android CLI 用于设备管理、安装运行、界面读取、截图和后续原生验证。修改网页源码后直接部署旧 APK 不会带入新代码。

## 本机环境

- Android CLI：`1.1.16717390`。
- JDK：21；`JAVA_HOME` 已设置。
- SDK：`%LOCALAPPDATA%/Android/Sdk`；`ANDROID_HOME` 已设置。
- 编译平台：Android API 36；模拟器：Android 17 / API 37，`Medium_Phone_API_37.0`。
- 模拟器使用 Windows Hypervisor Platform 加速。
- 本机 AVD 使用 4 个虚拟 CPU、`hw.gpu.mode=host`；实测使用 NVIDIA RTX 4060。默认软件渲染曾把浅色背景错误显示成青色，切换硬件渲染后恢复正常。原 AVD 配置已备份至本机 `tmp/android-setup/Medium-Phone-before-cli.ini`。
- `android` 与 `adb` 已在 PATH 中。普通终端重新打开后可直接使用。

## 开发流程

在工程根目录的 PowerShell 中运行：

```powershell
# 检查环境与现有设备
android info
android emulator list
adb devices -l

# 后台启动模拟器；不清除已有数据
android emulator start --headless --cold Medium_Phone_API_37.0

# 更新网页资源、同步 Capacitor、构建完整本机测试包并运行原生单元测试
./web/scripts/build-haptics.ps1

# 明确指定 adb devices 返回的设备，避免装到错误的手机
android run --device=emulator-5554 --apks=release/STU-assistant-1.0.31-haptics.7.apk

# 读取当前页面。优先用布局树中的文字、ID 或坐标操作
New-Item -ItemType Directory -Force tmp/android-cli-qa | Out-Null
android layout --device=emulator-5554 --pretty --output=tmp/android-cli-qa/layout.json
android screen capture --device=emulator-5554 --output=tmp/android-cli-qa/screen.png

# 收集崩溃日志。使用 -d，避免一直占住终端
adb -s emulator-5554 logcat -b crash -d

# 开发结束后停止模拟器，释放资源
android emulator stop emulator-5554
```

第一次启动需要完成 Android 系统初始化，可能较慢。若设备停留在 `offline`，先检查模拟器日志与系统启动状态；CLI 的停止命令无法识别离线实例时，可对明确的目标执行 `adb -s emulator-5554 emu kill`，再冷启动，不需要清除用户数据。

APK 文件名随 `web/android/app/build.gradle` 中的版本变化，以构建输出为准。当前本机测试包名为 `com.stuassistant.app.haptics`，与维护者发布包并存。

`android input --help` 可查看点击、滚动、文字与按键输入。操作前先读取布局树，避免从截图猜坐标；截图用于检查实际布局。首次使用设备时，CLI 可能安装其界面检查组件。

## 验证范围

模拟器可检查安装、启动、页面导航、强度滑条、状态保存、系统返回键和崩溃日志。当前默认两段按键起点最短间隔为 110 ms，强度与锐度由原生单元测试覆盖。实际马达力度、RichTap 支持和手感仍需在实体手机上确认。

2026-10-30 已在本机完成：完整 App 构建及原生测试、CLI 安装启动、预览入口、我的／课表导航、课程弹层的 Android 返回键、强度 100→50→0→100、App 与模拟器重启后保留 50。验证结束恢复为 100。本轮 App 日志没有发现崩溃或 ANR。布局 JSON、截图与日志保存在本机 `tmp/android-cli-qa/`。

首次系统初始化期间曾出现模拟器 System UI／system_server 无响应及重启，准备阶段的日志单独保留在 `emulator-startup-crashes.log`；完成系统启动并切换硬件渲染后，重新开始了上述 App 检查。不能把准备阶段的系统错误计作 App 已通过的功能，也不能据此声称模拟器覆盖了所有真机行为。

登录、教务绑定和真实数据同步需另外提供可用后端及测试账号；基础界面验证可使用 App 内预览入口。APK、设备日志、截图、SDK 和本机签名保存在本机，不进入 Git。

官方说明：[Android CLI](https://developer.android.com/tools/agents/android-cli)、[命令参考](https://developer.android.com/tools/agents/android-cli/commands)。
