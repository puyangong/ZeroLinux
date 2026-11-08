# ZeroLinux

Android 上的多发行版 Linux 容器（PRoot）+ 内置终端。

> GPL-2.0-or-later。`external/proot` 来自 [termux/proot](https://github.com/termux/proot) @ `7266fb3e`，
> 见 `external/PROVENANCE.md`。

## 当前进度：第 1 步（工程基座与原生层）

- Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.3.20 / JDK 21 / compileSdk 36 / minSdk 26
- 两个进程：`:main`（引导/设置/容器元数据）、`:terminal`（终端 + 前台服务）
- PRoot 引擎（`libproot.so` + loader + talloc + android-shmem）与自研 PTY 桥（`libpty.so`）
- 首启引导：欢迎 → 存储/通知/后台运行三项权限 → 选择发行版
- 中英双语（跟随系统 / 中文 / English），语言切换实时生效
- 环境自检页：报告 PRoot 版本、PTY 装载、六个原生库与 IPC 连接状态

## 构建

```powershell
# 工具链（便携安装，不改系统环境）
powershell -ExecutionPolicy Bypass -File ..\scripts\bootstrap-toolchain.ps1

# 构建
$env:JAVA_HOME = 'E:\zerolinux\tools\jdk21'
.\gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 目录

```
app/src/main/
├─ cpp/pty.cpp              fork/exec + posix_openpt 的 PTY 桥
├─ jniLibs/                 arm64-v8a / armeabi-v7a / x86_64
├─ assets/www/terminal/     终端页 WebView 资产
└─ java/com/zerolinux/app/
   ├─ core/                 路径、JSON、全局设置、权限
   ├─ container/           发行版目录（第 2 步扩展为真实 rootfs）
   ├─ engine/              PRoot / PTY / 原生自检
   ├─ ipc/                 IContainerService（AIDL，:main 唯一写者）
   ├─ service/             TerminalHostService（specialUse 前台服务）
   └─ ui/{main,terminal,common,theme}
```

## 设计令牌

设计令牌：`#0B0C0F` 背景、`#E6E8EB` 前景、`#7EE787` 光标/强调、`#264F78` 选区，
状态色 紫 `#A371F7` / 蓝 `#58A6FF` / 绿 `#3FB950` / 橙 `#F0883E`。
