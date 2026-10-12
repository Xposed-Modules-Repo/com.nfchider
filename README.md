# NFC Hider
https://t.me/+eIEZ13o6HPU5NzBk
  中国群921440494
[![构建状态](https://github.com/Xposed-Modules-Repo/com.nfchider/actions/workflows/build.yml/badge.svg)](https://github.com/Xposed-Modules-Repo/com.nfchider/actions/workflows/build.yml)

一款基于现代 Xposed / LSPosed 框架的 Android 模块，支持对目标应用隐藏设备 NFC 硬件，并提供高自由度的地图轨迹 GPS 位置模拟与回放。

**当前版本：v1.2**

---

## 🌟 核心特性

### 1. 全局系统 Hook 模式（无需逐个选 App，App 内实时启闭）
- **系统级深度拦截**：支持在系统框架层（`system_server` / `android`）拦截系统定位服务（`LocationManagerService`）与硬件分发（`LocationProviderManager`），实现**系统全局 Hook**。
- **免选单独应用**：只需在 LSPosed 作用域中勾选「系统框架 (android)」，即可对全手机所有应用全局生效。
- **App 内实时开关**：在 NFC Hider 应用内关闭开关即可立即停止 Hook，开启开关即可重新生效，无需重启设备。

### 2. 高精度 GPS 位置模拟、卫星图层与地点收藏
- **Google 卫星高清图层**：默认采用 Google Maps 卫星混合图层（高精卫星实景 + 道路/地标标注），并支持在卫星图与标准矢量图之间一键切换；
- **地点收藏夹（新特性）**：支持在地图上长按任意地点快速添加收藏，也可一键收藏地图中心或当前真实定位；在收藏夹中可随时查看、重命名、删除、快速前往或将收藏点一键加入模拟路线；
- **即时获取手机真实定位**：自动申请定位权限，启动即自动搜星定位，右上角一键居中到当前位置，地图常驻“我的位置”光晕蓝点；
- **户外真实轨迹录制**：支持带着手机实际走动/骑行/驾车，一键开启「录制轨迹」，内置智能防抖滤波采样，实时统计航点、里程与速度，录制完成后可一键「应用为模拟轨迹」或「导出为 GPX 文件」；
- **自由路线绘制与 GPX 导入**：支持在地图上随心添加/编辑导航路线点，或直接导入 `.gpx` 航迹文件；
- **丰富的播放模式**：支持**循环播放**、**往返折返**、**单次运行**，速度支持 **1 km/h 至 300 km/h** 无级微调；
- **免检测机制**：系统底层拦截并剥除 `isMock()` / `isFromMockProvider()` 标识，各应用均识别为真实硬件定位。

### 3. NFC 硬件深度隐藏
- 在系统底层拦截 NFC 软件特性查询（`PackageManager`）与硬件接口（`NfcAdapter`），隐藏设备 NFC 硬件，防止目标应用探测。

---

## 📖 使用指南

1. 在手机上安装并打开 **LSPosed**；
2. 在模块列表中找到 **NFC Hider** 并启用；
3. **配置作用域**：
   - 勾选**「系统框架 (android)」**即可实现全局生效（无需再逐个勾选单独 App）；
   - 重启手机一次使系统框架 Hook 生效；
4. **控制与使用**：
   - 打开 **NFC Hider** 主界面，即可看到「全局系统 Hook」总开关；
   - **在 App 内关闭开关**：即刻停止所有 Hook 拦截与位置模拟，各应用恢复真实系统状态；
   - **在 App 内开启开关**：即刻重新启用全局拦截与位置模拟；
5. **卫星地图与地点收藏**：
   - 点击「打开卫星地图轨迹模拟与地点收藏」进入地图；
   - 默认展示 Google 高清卫星混合图，点击右侧「🛰️」按钮可切换图层；
   - 点击右侧「⭐」按钮打开地点收藏夹，可快速定位已收藏地点或将其加入路线；在地图任意位置**长按**即可直接添加收藏点！

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
- `app/build/outputs/apk/release/NfcHider-5-1.2.apk`

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 许可证开源。
