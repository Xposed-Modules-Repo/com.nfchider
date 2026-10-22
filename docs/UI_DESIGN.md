# NFC Hider 界面设计优化方案

> 版本：v1.1 (versionCode 9) ｜ 设计系统：Stealth Azure「隐匿蓝」
> 本文档记录界面层的问题诊断、设计系统重建与关键界面改造方案。

---

## 1. 现状诊断

### 1.1 设计系统缺失

| 问题 | 位置 | 影响 |
| --- | --- | --- |
| 主题只有 `primary/secondary/tertiary` 三个角色，其余回落 M3 默认**紫色基线** | `ui/theme/Color.kt`、`Theme.kt` | 非动态取色设备（Android 11 及以下 / 用户关闭取色）出现「紫色卡片 + 蓝色强调」混搭 |
| 动态取色默认开启且优先级最高 | `Theme.kt` `dynamicColor = true` | 品牌识别被系统壁纸色覆盖，工具类 App 的专业感不稳定 |
| 字体只定义了 3 个角色，其余用 M3 默认 | `Type.kt` | 中文行高偏挤（默认 bodyLarge 24sp/16sp = 1.5 尚可，但标题层级不全） |
| 无形状系统 | — | 圆角值在代码里散落（8/10/12/14/16 dp 混用） |
| 地图覆盖色**硬编码**且不区分明暗 | `MapActivity.kt` 多处 | 卫星图夜间瓦片上亮蓝色路线看不清；与主题无联动 |

### 1.2 地图页（MapActivity）交互问题

1. **图标语义不一致**：缩放按钮一个用 `Add` 图标、一个用文本 `"-"`；图层按钮用 emoji `🛰️/🗺️` 文本。emoji 在不同 ROM 字体下渲染差异大，且无法适配主题色。
2. **无障碍缺失**：所有 `MapFab` 的 `contentDescription = null`，读屏用户无法区分按钮。
3. **状态色语义弱**：录制中/暂停/运行中状态点散落使用 `Color(0xFFE53935)`、`Color(0xFF9E9E9E)` 等裸值。
4. **信息密度**：底部折叠态已有良好状态摘要（保留并增强）；展开态速度、模式、编辑、录制、导入全挤在一列，缺少分组与图标引导。
5. **缺少触觉反馈**：长按收藏地点无震动确认。

---

## 2. 设计系统重建

### 2.1 品牌定位

**「隐匿蓝 / Stealth Azure」**——工具型模块，专业、克制、高信息密度：

- **Primary 体系蓝 `#0B57D0`**：主操作、运行状态、强调
- **Tertiary 定位青 `#00696E`**：录制、轨迹相关次级强调
- **Secondary 石板灰 `#565E71`**：中性信息、辅助操作
- **地图语义色板 `MapPalette`**：路线蓝 / 录制绿 / 收藏金 / 模拟红 / 真实位置蓝，明暗双套

### 2.2 完整色彩角色（Material 3）

`Theme.kt` 现提供 light/dark **完整** colorScheme（含 `surfaceContainer*` 系列新角色），不再回落到默认紫色：

```
Light:  primary #0B57D0   tertiary #00696E   surface #FDFCFF   outline #757780
Dark:   primary #B0C6FF   tertiary #82D3D8   surface #111318   outline #8F9099
```

### 2.3 地图语义调色板

```kotlin
MapPalette.Light  // 亮底瓦片：路线 #1B6FE3 / 录制 #00C853 / 收藏 #FFB300 / 模拟 #E53935
MapPalette.Dark   // 暗底瓦片：路线 #7FB0FF / 录制 #5EFC82 / 收藏 #FFD54F / 模拟 #FF8A80
```

暗色套刻意提高明度（Lightness +20%），保证 Google 卫星图夜间瓦片与深色 OSM 上的可读性。

### 2.4 字体与形状

- **Typography**：15 档完整层级；中文场景正文行高 ≥ 1.5×字号；标题用 SemiBold/Medium 而非 Black；新增 `MonoNumeric` 等宽字体用于坐标/速度读数，避免数字刷新跳字。
- **Shapes**：6/10/14/20/28 dp 五档，比 M 默认柔和一档，统一原先散落的圆角值。

---

## 3. 关键界面改造

### 3.1 地图浮动按钮组（右侧）

| 按钮 | 旧实现 | 新实现 |
| --- | --- | --- |
| 放大 | `Icons.Filled.Add` | `Icons.Filled.ZoomIn`，描述「放大」 |
| 缩小 | 文本 `"-"` | `Icons.Filled.ZoomOut`，描述「缩小」 |
| 图层 | emoji `🛰️/🗺️` | `Icons.Filled.Layers`，描述「切换地图图层」 |
| 收藏 | `Icons.Filled.Star` | 保留，描述「地点收藏」 |
| 搜索 | `Icons.Filled.Search` | 保留，描述「搜索地点」 |
| 定位菜单 | `Icons.Filled.LocationOn` | `Icons.Filled.MyLocation`（十字准星更贴合「定位到我的位置」），描述「定位与目标位置」 |

同时：FAB 增加触觉反馈（长按收藏）、统一 44dp 尺寸与半透明Surface底色。

### 3.2 地图覆盖层

- 路线折线、录制航迹、收藏标记、真实位置蓝点：全部从 `MapPalette.forDark(isMapDark())` 取色，`remember(mapPalette)` 重建。
- 航点 Marker 增加序号气泡样式（`waypointLabel` 色）。
- 录制状态点、录制按钮红点统一走 `statusRecording / statusPaused`。

### 3.3 底部控制面板

保留现有「折叠/展开」结构（这是 v1.1 的亮点），增强：

- **状态条**：折叠态状态点 + 标题 + 实时广播坐标（等宽字体）。
- **速度区**：Slider + 等宽读数 + 预设 Chip（步行/骑行/驾车/高铁），新增 `Icons.Filled.Speed` 引导图标。
- **模式区**：分段按钮（循环/往返/单次）保留。
- **操作区**：标记/撤销/清空/搜点/录制/导入 GPX 按功能分两组，间距分组 8dp。
- **主操作**：开始/停止模拟使用填充按钮（primary / error），次级操作描边。

### 3.4 诊断对话框

信息架构：状态总卡 → 地图诊断卡（异常时 errorContainer）→ 定位指标列表 → LSPosed 说明卡 → 真实 GPS 对比。卡片化 + `DiagnosticItem` 行式布局保留，色彩全部走主题角色。

### 3.5 设置页（SettingsActivity）

- 每张卡片增加**引导图标**（盾牌=总开关、芯片=模块状态、地图=位置模拟、分类=拦截分类、应用=作用域、信息=关于）。
- 标题层级：卡片标题 `titleMedium` + 描述 `bodySmall`，统一 16dp 内边距。
- 总开关卡片：开启时 primaryContainer + 状态徽章；关闭时 surfaceVariant。

---

## 4. 落地清单

- [x] `ui/theme/Color.kt`：品牌色板 + 中性色阶 + `MapPalette`
- [x] `ui/theme/Theme.kt`：完整 light/dark colorScheme + Shapes + 默认关闭动态取色
- [x] `ui/theme/Type.kt`：15 档排版 + `MonoNumeric`
- [x] `app/build.gradle.kts`：引入 `material-icons-extended`（release 有资源混淆，未引用图标被剥离）
- [x] `MapActivity.kt`：FAB 语义图标 + 无障碍描述 + 地图覆盖层主题化 + 长按震动
- [x] `SettingsActivity.kt`：卡片引导图标与层级统一
