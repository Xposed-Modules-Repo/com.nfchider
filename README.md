# NFC Hider

一款基于 Xposed / LSPosed 的 Android 模块：对目标应用隐藏 NFC 硬件，并支持 GPS 位置模拟与轨迹回放。

**当前版本：8**

- Telegram：https://t.me/+eIEZ13o6HPU5NzBk
- 中国群：921440494
- Issue：https://github.com/furina707/com.nfchider
- 仓库：https://github.com/Xposed-Modules-Repo/com.nfchider

[![构建状态](https://github.com/furina707/com.nfchider/actions/workflows/build.yml/badge.svg)](https://github.com/furina707/com.nfchider/actions/workflows/build.yml)

---

## 功能

- **NFC 隐藏**：拦截 `PackageManager` / `NfcAdapter`，目标应用无法探测 NFC 硬件。
- **定位模拟**：拦截 `LocationManager` / `FusedLocationProviderClient`，剥除 `isMock()` 标识，识别为真实定位。
- **按应用生效**：LSPosed 勾选目标 App，重启生效；App 内总开关实时启停。
- **多源地图**：高德（国内直连）、Google 卫星混合、OSM，异常自动诊断。
- **地点搜索**：Photon + OSM Nominatim，支持地名/经纬度，一键设为模拟点或加入路线。
- **轨迹与收藏**：GPX 导入导出、实地录制、循环/往返/单次、1~300 km/h；长按收藏地点。
- **快捷功能圆**：Firefox 快捷方式风格的透明圆形按钮（速度/启停/撤销/清空/导入等 17 个），点按直达，不遮挡地图。
- **定位增强**：GPS 锁定隔离网络定位、Kalman 平滑、静止冻结蓝点，精度圈与卫星数实时可见。

## 使用

1. 安装并启用 LSPosed，勾选 **NFC Hider**。
2. 作用域勾选目标 App，重启该 App。
3. 打开 NFC Hider **直接进入卫星地图**：切换图层、搜索地点、模拟定位或录制轨迹。
4. 点右侧「快捷功能」面板标题行的**齿轮图标**进入设置：Hook 总开关、模块状态、拦截分类等都在设置页。

## 环境

- Android 10+（API 29+）
- LSPosed / EdXposed（libxposed API 101+，Target 102）

## 编译

需 JDK 17+、Compile SDK 37：

```bash
git clone https://github.com/Xposed-Modules-Repo/com.nfchider.git
cd com.nfchider
./gradlew assembleRelease
