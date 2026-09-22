# NFC Hider
https://t.me/+eIEZ13o6HPU5NzBk
921440494
[![构建状态](https://github.com/Xposed-Modules-Repo/com.nfchider/actions/workflows/build.yml/badge.svg)](https://github.com/Xposed-Modules-Repo/com.nfchider/actions/workflows/build.yml)

一款基于现代 Xposed / LSPosed 框架的 Android 模块，支持对目标应用隐藏设备 NFC 硬件，并提供高自由度的地图轨迹 GPS 位置模拟与回放。

**当前版本：v1.1**

---

## 🌟 核心特性

### 1. NFC 硬件深度隐藏
- 在系统框架底层拦截 NFC 相关 Android 系统 API，让目标应用认为设备完全不具备 NFC 硬件：
  - **包管理器 (`PackageManager`)**：拦截 NFC 软件包列表与系统 NFC 特性查询；
  - **硬件适配器 (`NfcAdapter` / `NfcManager`)**：拦截硬件适配器实例获取与状态查询；
  - **设备文件系统 (`Filesystem`)**：拦截 `/dev/nfc*` 设备节点与硬件驱动文件读取；
  - **底层进程与 Shell**：拦截针对底层 NFC 服务的 Shell 命令行探测；
  - **系统设置 (`Settings`)**：拦截 NFC 相关的系统全局设置项读取。

### 2. 高精度 GPS 位置模拟与轨迹回放
- **自由路线绘制与 GPX 导入**：集成 OpenStreetMap（基于 osmdroid，无需额外申请 API Key），支持在地图上随心添加/编辑导航路线点，或直接导入 `.gpx` 航迹文件；
- **丰富的播放模式**：支持**循环播放**、**往返折返**、**单次运行**，速度支持 **1 km/h 至 300 km/h** 无级微调；
- **全方位定位拦截（免检测）**：
  - 系统底层 `LocationManager` 全重载拦截（覆盖 `getLastKnownLocation`、`getCurrentLocation`、`requestLocationUpdates` 监听器/PendingIntent/Executor 等所有变体）；
  - Google Play services / FusedLocation 联合定位拦截；
  - 返回的 `Location` 对象不包含模拟位置标识（`isMock()` / `isFromMockProvider()` 严格返回 `false`），有效防范模拟定位检测；
- **直接查看实时模拟状态**：模块作为控制器**无需勾选自身**，界面常驻状态条直接展示当前向目标应用广播的实时经纬度坐标与移动速度。

### 3. 包体极致轻量化
- 深度开启 R8 代码优化、混淆压缩与冗余资源过滤，剔除 80+ 种非必需多语言包；
- 构建产物 Release APK 体积仅 **2.4 MB**（相比原版 16.6 MB 缩减逾 85%）。

---

## 📖 使用指南

1. 在手机上安装并打开 **LSPosed**（或兼容的 Xposed 框架管理器）；
2. 在模块列表中找到 **NFC Hider** 并启用；
3. **配置作用域（关键步骤）**：
   - 在模块的作用域设置中，勾选需要生效的**目标应用**（例如需要隐藏 NFC 或模拟定位的应用）；
   - 💡 **特别说明**：本模块本身作为控制端，**无需也不能在 LSPosed 作用域中勾选自身**；
4. **启动位置模拟**：
   - 打开 **NFC Hider** → 点击「打开地图轨迹模拟」；
   - 开启右上角「标记」，在地图上点击添加轨迹路线点（或点击「导入 GPX」）；
   - 点击下方「开始模拟」按钮，状态条将变为绿色并开始实时广播模拟经纬度；
5. **在目标应用中生效**：
   - 首次配置或修改模拟路线后，在手机系统设置中对目标应用执行一次**「强行停止」**并重新打开；
   - 目标应用启动获取定位时，将自动获取此处广播的模拟坐标，且模块退出后台仍会持续按计划轨迹移动。

---

## 📱 兼容性与运行环境

- **系统要求**：Android 10+（API 29+）
- **框架支持**：LSPosed / EdXposed（libxposed API 101+，Target 102）

---

## 🛠️ 本地编译与构建

项目采用 Jetpack Compose 构建现代化 Android 界面，本地编译环境要求：
- **JDK**：JDK 17+
- **Android SDK**：Compile SDK 37

编译命令：
```bash
git clone https://github.com/Xposed-Modules-Repo/com.nfchider.git
cd com.nfchider

# 编译 Release 版本 APK
./gradlew assembleRelease
```

编译输出目录：
- `app/build/outputs/apk/release/NfcHider-3-1.1.apk`

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 许可证开源。
