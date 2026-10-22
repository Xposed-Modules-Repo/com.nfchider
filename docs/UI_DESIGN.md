# NFC Hider 界面设计优化方案

> 版本：v1.1 ｜ 设计系统：Stealth Azure「隐匿蓝」｜ versionCode 随 CI 自动递增
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

### 3.1 地图功能入口：Firefox 快捷方式风格「快捷功能」面板（右侧）

> 设计范式学习自 Firefox Android 新建标签页的「快捷方式」：**每个功能是一个独立的圆形**（favicon 或字母磁贴），圆下方跟一个小号标签，4 列网格排布，次要功能收入「显示全部」。原右侧纵向 FAB 列（7 个 44dp 半透明圆按钮竖向排列）存在三个问题：功能多时竖向占位过高、无标签依赖用户记忆图标、入口数量受高度限制。

| 维度 | 旧实现（纵向 FAB 列） | 新实现（快捷功能面板） |
| --- | --- | --- |
| 布局 | 右侧纵向单列，7 个按钮 ≈ 360dp 高 | 4 列网格，8 个核心功能 ≈ 170dp 高，**竖向占位减半** |
| 识别 | 仅图标，无文字 | 圆盘 + 10sp 标签（1-2 行），图标语义自解释 |
| 容量 | 受屏幕高度限制 | 「显示全部」展开 12 个功能，折叠时只露 8 个 |
| 色彩 | 半透明 Surface + 单色 icon tint | **实色圆盘 + 白色字形**（`QuickFunctionColors` 12 个独立色相），卫星图/矢量图复杂底图上均清晰 |
| 状态 | 无（跟随态仅靠 tint 变化，难以察觉） | 开关类功能（跟随/标记/录制）激活态 = 圆盘外缘 2.5dp 白色描边；禁用态 = 整体 38% 透明度 |
| 折叠 | 无 | 标题行点击整面板折叠为药丸，把地图面积还给用户；顶部告警条右侧留白随面板开合动态避让 |

核心功能（常驻 8 个）：放大 / 缩小 / 图层 / 收藏 / 搜索 / 定位 / 跟随 / 录制；
「显示全部」收纳 4 个次要功能：诊断 / 标记 / 去模拟点 / 真迹设点。

实现要点：

- `ui/QuickFunctionsPanel.kt`：`QuickFunction` 数据类（图标 + 标签 + 圆盘色 + 激活/禁用态 + 动作）→ `QuickFunctionsPanel`（Surface 20dp 圆角 + 标题行 + `chunked(4)` 网格 + AnimatedVisibility 展开动画）→ `FunctionCircle`（48dp 圆盘 + 21dp 白字形 + 标签，圆盘 `semantics` 带 `contentDescription`、标签 `clearAndSetSemantics` 避免无障碍朗读重复）；
- `ui/theme/Color.kt` 新增 `QuickFunctionColors`：每个功能一个独立色相，相邻功能色相拉开距离；成对功能（放大/缩小）共用色相表示分组；颜色不随明暗主题切换（实色圆盘 + 白字形在深浅底图上都成立）；
- 面板与「定位二级菜单」对话框**共用同一批动作 lambda**（定位/设模拟点/去模拟点/跟随/录制），两个入口行为不会漂移；
- 旧 `MapFab` 组件保留（面板之外需要单个浮动按钮时使用），地图功能入口全部迁移至面板。

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

### 3.6 应用图标（Launcher Icon）

原应用无图标资源，桌面显示系统默认机器人图标、安装界面无辨识度。本版从零建立完整图标体系：

- **设计**：深蓝底（`#184387`，与品牌「隐匿蓝」同族）+ 白色 NFC 波纹符号（两道同心弧 + 波点），语义即「NFC 隐藏」；
- **adaptive icon**（`mipmap-anydpi-v26`）：`<background>` 用 `@color/ic_launcher_background` 纯色，`<foreground>` 为 `drawable-*/ic_launcher_foreground.png`（蓝底抠透明，符号缩放 0.889 后置入 72dp 安全区，任何厂商遮罩不裁切符号）；
- **legacy 全出血 PNG**（`mipmap-mdpi~xxxhdpi`）：48/72/96/144/192px 五档，蓝底白符号满幅；`ic_launcher_round` 为同图案椭圆裁切圆变体；
- **声明**：`AndroidManifest` 的 `<application>` 增加 `android:icon` / `android:roundIcon`；
- 生成管线：AI 出图 → PIL 去水印（众数背景色填充）→ 颜色距离 alpha 抠图 → LANCZOS 缩放 → 各密度落盘，圆形遮罩预览逐档验证。

### 3.7 透明圆功能圆与底部面板移除（v1.1.15）

学习 Firefox 新建标签页的快捷方式：每个功能是一个**独立的透明圆**——无底色，功能色圆环 + 同色字形，悬浮在底图之上不遮挡内容；圆下方一个小号标签，共同构成一个功能单元。

- **透明圆**（`QuickFunctionsPanel.FunctionCircle`）：`Modifier.border(1.5dp, 功能色, CircleShape)` + `Icon(tint = 功能色)`，无 `background()`；开关类功能激活态圆环加粗至 2.5dp 并升满不透明度；禁用态圆环与字形同步降至 38% 透明度；圆环自带 `contentDescription`，标签文本从无障碍树隐藏避免朗读重复。
- **移除底部控制面板**：原常驻底部面板（速度/模式/启停/录制仪表）整体拆除，速度/模式/启停迁入 `SimControlDialog`（「速度」圆唤起），录制控制迁入 `RecordingControlDialog`（录制圆唤起）；面板折叠态的「状态摘要」升级为顶部 `SimStatusPill` 状态药丸——状态点 + 运行状态/实时广播坐标 + 模拟中快捷停止，一行展示，不占用地图底部空间。
- **定位精度圈**：按 fix 的 accuracy 半径生成 48 边形 `Polygon` overlay（半透明填充 + 细描边），先于蓝点加入 overlays 保证蓝点绘制在精度圈之上；半径封顶 400m，`accuracy < 5m` 时隐藏。
- **功能色板扩充**：`QuickFunctionColors` 新增 Speed / Undo / Clear / Import 四色，功能圆从 12 个扩至 17 个。

---

## 4. 落地清单

- [x] `ui/theme/Color.kt`：品牌色板 + 中性色阶 + `MapPalette` + `BadgePalette` + `QuickFunctionColors`
- [x] `ui/theme/Theme.kt`：完整 light/dark colorScheme + Shapes + 默认关闭动态取色
- [x] `ui/theme/Type.kt`：15 档排版 + `MonoNumeric`
- [x] `app/build.gradle.kts`：引入 `material-icons-extended`（release 有资源混淆，未引用图标被剥离）
- [x] `ui/QuickFunctionsPanel.kt`：Firefox 快捷方式风格功能面板（独立圆形 + 标签 + 4 列网格 + 显示全部）
- [x] `MapActivity.kt`：FAB 列迁移至快捷功能面板 + 地图覆盖层主题化 + 长按震动 + 真实定位择优（BUG-13）
- [x] `SettingsActivity.kt`：卡片引导图标与层级统一
- [x] 应用图标体系：adaptive icon（anydpi-v26 XML + 安全区 foreground）+ 五档 legacy PNG + roundIcon 变体 + manifest 声明
- [x] `ui/QuickFunctionsPanel.kt`：功能圆改为透明圆（功能色圆环 + 同色字形，无底色）
- [x] `MapActivity.kt`：移除底部控制面板；新增 SimStatusPill 状态药丸 / SimControlDialog / RecordingControlDialog；新增定位精度圈 overlay；GNSS 卫星数与静止状态展示
- [x] `location/RealLocationTracker.kt`：provider 隔离 / Kalman-lite 平滑 / 加速度计静止检测 / GNSS 卫星状态上报
- [x] `ui/theme/Color.kt`：QuickFunctionColors 扩充 Speed / Undo / Clear / Import
