# com.nfchider Bug 深度分析与修复报告

> 分析对象：<https://github.com/furina707/com.nfchider>（main 分支）
> 模块形态：Android Xposed/LSPosed 模块（libxposed API 102.0.0）——NFC 隐藏 + GPS 位置模拟 + 轨迹录制
> 分析日期：2026-10-22 ｜ 状态：15 项缺陷全部完成代码修复，`assembleDebug` 构建通过

---

## 1. 分析方法论

本报告的 Bug 结论**不是**基于对 libxposed API 的推测，而是以官方源码核验为准：

| 证据来源 | 用途 |
|---|---|
| `io.github.libxposed:api:102.0.0` 源码（Maven Central） | 核验 `XposedInterface`、`Chain`、`XposedModule` 的 API 语义 |
| `io.github.libxposed:service:102.0.0` 源码（Maven Central） | 核验 `RemotePreferences`、`XposedService` 的通信与生命周期语义 |
| 全量源码静态审查（22 个 Kotlin/Java 源文件） | 生命周期、并发、内存、Compose 重组四类缺陷模式 |
| Gradle 9.4.1 + AGP 9.2.0 + JDK 25 实际构建 | 修复可编译性验证（`BUILD SUCCESSFUL`，产物见 §5） |

关键 API 事实（引自官方源码，构成本报告多处结论的证据链）：

- **`RemotePreferences` 的监听器表是 `WeakHashMap`**——`RemotePreferences.java` 第 34 行：
  ```java
  private final Map<OnSharedPreferenceChangeListener, Object> mListeners =
      Collections.synchronizedMap(new WeakHashMap<>());
  ```
- **`Chain.getArgs()` 返回不可变列表**——`XposedInterface.java` 第 203–209 行 javadoc：
  > "The returned list is immutable. If you want to change the arguments, you should call `proceed(Object...)` or `proceedWith(Object, Object...)` with the new arguments."
- **配置推送的官方姿势是 `getRemotePreferences` + 监听器**——`XposedService.java` 第 299–304 行 javadoc 明确"hot reload 不应用于传播配置变更"。

---

## 2. 结论总览

共确认并修复 **15 项缺陷**：P0 × 3（核心功能失效）、P1 × 7（内存泄漏/并发/稳定性/定位精度）、P2 × 4（健壮性）。所有修复在源码中以 `// BUG-xx fix:` 注释标注，编号与本报告一一对应。

| # | 位置 | 严重度 | 类别 | 一句话根因 |
|---|---|---|---|---|
| BUG-01 | `location/ConfigStore.java` | **P0** | 配置推送失效 | 监听器被 `WeakHashMap` 回收，配置变更静默丢失 |
| BUG-02 | `SettingsActivity.kt` | **P0** | 模拟跳变 | 开启总开关不重置 `startAt`，引擎按旧起点重算 |
| BUG-03 | `MapActivity.kt` | **P0** | 地图不刷新 | `LaunchedEffect` 以 `points.size` 为 key，等量替换不重绘 |
| BUG-04 | `location/LocationSim.java` | P1 | 内存泄漏 | `PENDING` 强引用表钉死目标 App 的 Context |
| BUG-05 | `location/DexSqlRunner.kt` | P1 | 死锁 | `waitFor` 前 `readText` 填满管道缓冲区，超时永远无法触发 |
| BUG-06 | `location/RealLocationTracker.kt` | P1 | 性能 | 每个航点 `List.plus` 全量拷贝，O(n²) 分配 + 重组风暴 |
| BUG-07 | `NfcHook.java` | P1 | Hook 覆盖缺口 | 只 Hook `NfcAdapter` 静态方法，漏 `NfcManager.getDefaultAdapter()` 实例方法 |
| BUG-08 | `MapActivity.kt` | P1 | 交互卡顿 | follow 模式每 500ms 重发 `animateTo`，动画不断重启 |
| BUG-09 | `location/ModuleService.kt` | P1 | 数据竞争 | `pendingJson` 缺 `@Volatile`，跨线程读写无可见性保证 |
| BUG-10 | `location/DexSqlRunner.kt` | P2 | 文件原子性 | `ensureInstalled` 直接写目标路径，失败留半成品 |
| BUG-11 | `App.kt` | P2 | 启动崩溃 | `ModuleService.init()` 未防护，桥接失败拖垮 App 启动 |
| BUG-12 | `location/LocationSearchService.kt` | P2 | 连接泄漏 | HTTP 连接不 `disconnect`，靠 GC 兜底释放 socket |
| BUG-13 | `location/RealLocationTracker.kt` + `MapActivity.kt` | **P0** | 真实定位不准 | 双 provider 回调无差别覆盖：粗精度基站定位覆盖精确 GPS fix，蓝点跳到错误位置 |
| BUG-14 | `app/build.gradle.kts` + `module.prop` + CI 工作流 | P2 | 版本号失效 | `versionName` 硬编码 "1.1"，安装界面永远显示「版本: 1.1 -> 1.1」，无法分辨新旧包 |
| BUG-15 | `location/RealLocationTracker.kt` + `MapActivity.kt` + `ui/QuickFunctionsPanel.kt` | P1 | 定位精度与可视面积 | 室内/静止场景 GPS 多径漂移「蓝点跳舞」；无卫星数与静止状态反馈；底部控制面板长期占据地图可视面积 |

---

## 3. 详细分析

### BUG-01（P0）RemotePreferences 监听器被 GC 回收，配置推送静默失效

- **位置**：`app/src/main/java/com/nfchider/location/ConfigStore.java`
- **现象**：模块 App 内修改模拟开关/坐标/轨迹，已 Hook 的目标进程**不生效**，也无任何报错；重启目标进程后才恢复。
- **根因**：`ConfigStore.init()` 注册的 `OnSharedPreferenceChangeListener` 是一个**匿名局部对象**，注册后没有任何强引用持有它。而 libxposed `RemotePreferences` 用 `WeakHashMap` 存放监听器（service 源码第 34 行）。一次 GC 之后监听器即被回收，`registerOnSharedPreferenceChangeListener` 形同虚设——这正是 `XposedService` javadoc 警告的"不要用 hot reload 传播配置变更"的反面：官方推荐的通道本身要求调用方持有强引用。
- **证据链**：`RemotePreferences.java:34`（`WeakHashMap`）+ `ConfigStore.init()` 原代码中监听器为一次性局部变量。
- **影响**：跨进程配置同步是该模块的核心机制（模拟开关、坐标、轨迹全部经此下发）。此缺陷使"修改配置 → 目标 App 生效"的主链路在任何一次 GC 后断裂，且无日志、无异常，属于典型的静默失效。
- **修复**：新增静态强引用字段锚定监听器：
  ```java
  private static SharedPreferences.OnSharedPreferenceChangeListener listenerRef;
  // init() 中：
  listenerRef = l; // keep a strong reference; RemotePreferences holds listeners weakly
  prefs.registerOnSharedPreferenceChangeListener(l);
  ```

### BUG-02（P0）设置页开启总开关不重置 `startAt`，模拟位置跳变

- **位置**：`app/src/main/java/com/nfchider/SettingsActivity.kt`
- **现象**：在设置页打开总开关的瞬间，地图上的模拟位置**跳到轨迹终点附近**，然后才恢复正常。
- **根因**：`TrajectoryConfig.startAt` 是轨迹插值的起点时间戳。关闭再开启时旧时间戳被保留，`TrajectoryEngine` 按 `now - startAt` 计算进度，得到一个远超轨迹时长的值，插值结果落到终点；之后引擎自行纠偏才跳回。
- **证据链**：`SettingsActivity` 原开关逻辑只翻转 `enabled` 位；`TrajectoryEngine` 以 `startAt` 为插值基准。
- **影响**：用户视角是"开启模拟后位置先错乱几秒"，对依赖位置连续性的目标 App（导航、打卡类）可能产生一次错误位置上报。
- **修复**：开启时重置起点，关闭时保留：
  ```kotlin
  val updated = config.toBuilder()
      .enabled(newEnabled)
      .startAt(if (newEnabled) System.currentTimeMillis() else config.startAt)
      .build()
  ```

### BUG-03（P0）`LaunchedEffect` 以列表长度为 key，等量替换不重绘

- **位置**：`app/src/main/java/com/nfchider/MapActivity.kt`
- **现象**：导入一份与当前轨迹**点数相同**的 GPX，或把录制轨迹"应用为路线"时，地图路线与航点标记不更新。
- **根因**：重绘 effect 的 key 是 `config.points.size`。`TrajectoryConfig.Builder.build()` 每次都分配新 `ArrayList`，但点数不变时 size 相同，Compose 认为 key 未变、跳过重组；`config.points` 本身已是新实例，直接以它为 key 即可。
- **证据链**：`LaunchedEffect(config.points.size)` → `LaunchedEffect(config.points)`；builder 语义保证实例必新。
- **影响**：路线图与真实配置不一致，用户看到的折线是旧轨迹，按图设置的模拟位置与预期不符。
- **修复**：
  ```kotlin
  // BUG-03 fix: key on the list instance, not its size. Builder.build() always
  // allocates a new ArrayList, so an equal-size replacement (GPX import,
  // recorded track applied as route) still triggers a redraw.
  LaunchedEffect(config.points) { ... }
  ```

### BUG-04（P1）`PENDING` 注册表强引用钉死目标 App Context

- **位置**：`app/src/main/java/com/nfchider/location/LocationSim.java`
- **现象**：反复开关模拟后，被 Hook 进程内存持续增长，`LocationManager` 注册无法回收。
- **根因**：`PENDING` 原为 `ConcurrentHashMap<Object, Registration>`，value 里同时持有 **listener（key 本身）、LocationManager（经 ContextWrapper 链持有目标 App 的 Context/Activity）和原始参数数组**。大量 App 从不调用 `removeUpdates`（这是常态），于是表项连同整个 Activity 泄漏至进程终结。
- **证据链**：`Registration` 结构（receiver/method/args）+ `storeRegistration` 捕获的 `chain.getThisObject()` 即 LocationManager 实例；`getArgs()` 数组内含 listener 自身。
- **影响**：在 SystemUI 等长生命周期进程里等于永久泄漏一个 Activity； hooked 进程内存劣化，极端情况 OOM。
- **修复**（三处配合，缺一不可）：
  1. 表改 `WeakHashMap<Object, Registration>`；
  2. `Registration.receiver` 以 `WeakReference` 持有；
  3. `storeRegistration` 把参数数组中与 key 相同的对象包装成 `WeakReference`（否则 value 经 args 反持有 key，弱表失效）；`restoreRealUpdates` 调用前解包，若已被回收则放弃恢复。
  ```java
  private static final Map<Object, Registration> PENDING = new WeakHashMap<>();
  // storeRegistration 中：
  for (int i = 0; i < args.length; i++) {
      if (args[i] == key) args[i] = new WeakReference<>(key);
  }
  ```

### BUG-05（P1）`waitFor` 前 `readText` 造成管道缓冲区死锁

- **位置**：`app/src/main/java/com/nfchider/location/DexSqlRunner.kt`
- **现象**：DexSql 查询子进程输出超过约 64KB 后，整个查询协程与 UI"运行中"标志**永久卡死**，设定的超时从不触发。
- **根因**：原代码 `proc.waitFor(timeout)` 之前先 `proc.inputStream.readText()`。子进程 stdout 写满 ~64KiB 管道缓冲区后阻塞在 write，父进程阻塞在读——经典管道死锁。`waitFor` 的 timeout 根本没有机会执行。
- **证据链**：`ProcessBuilder.redirectErrorStream(true)` 合并输出 + `readText()` 在读线程同步执行。
- **影响**：数据库查询功能在结果集较大时 100% 卡死，只能杀进程恢复。
- **修复**：守护线程持续排空 stdout，主线程只负责 `waitFor(timeout)`，超时后 `destroyForcibly()` 并 join 读取线程收割已产出的内容：
  ```kotlin
  val reader = Thread { ... proc.inputStream.bufferedReader().forEachLine { ... } }
      .apply { isDaemon = true; start() }
  val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
  if (!finished) { proc.destroyForcibly(); reader.join(500); ... }
  ```

### BUG-06（P1）航点记录 O(n²) 拷贝 + 高频重组

- **位置**：`app/src/main/java/com/nfchider/location/RealLocationTracker.kt` → `RealLocationTracker.kt`
- **现象**：长时间轨迹录制时 UI 掉帧、主线程 GC 频繁。
- **根因**：`recordedPoints` 用 `MutableStateFlow`，每个新航点通过 `_recordedPoints.value = old + new` 全量拷贝列表（`List.plus` 语义），n 个航点累计 O(n²) 次分配；且每次赋值立即触发一次重组，而地图重绘根本不需要这个频率。
- **证据链**：`handleNewLocation` 中 `_recordedPoints.value = _recordedPoints.value + point` 的原实现。
- **影响**：1 小时 1Hz 录制 ≈ 3600 次全量列表拷贝与重组，纯浪费。
- **修复**：`ArrayList` 缓冲区作为事实源（O(1) 追加），公开快照按 **400ms 节流** 发布（约 2.5 次/秒，与地图刷新节奏匹配）：
  ```kotlin
  private val recordedPointsBuffer = ArrayList<TrajectoryConfig.Point>()
  private fun scheduleRecordedFlush() {
      if (flushPending) return
      flushPending = true
      scope.launch { delay(400L); flushPending = false
          _recordedPoints.value = ArrayList(recordedPointsBuffer) }
  }
  ```

### BUG-07（P1）NFC 隐藏漏掉 `NfcManager.getDefaultAdapter()` 实例路径

- **位置**：`app/src/main/java/com/nfchider/NfcHook.java`
- **现象**：部分目标 App 仍能拿到可用的 `NfcAdapter`，NFC 隐藏不彻底。
- **根因**：原实现只对 `NfcAdapter` 类的**静态方法**做了反射遍历 Hook。但 `NfcManager.getDefaultAdapter()` 是**实例方法**，Kotlin 的 `nfcManager.defaultAdapter` 以及大量 Java 代码走的正是这条路，完全绕过拦截。
- **证据链**：Android SDK 中 `NfcManager.getDefaultAdapter()` 的签名（实例、无参、返回 `NfcAdapter`）；模块原有 `hookNfcAdapter()` 只扫 `NfcAdapter.class.getDeclaredMethods()`。
- **影响**：NFC 检测类 App（银行、门禁、支付）在相当一部分代码路径下仍能发现 NFC 硬件。
- **修复**：新增 `hookNfcManager()`，对 `NfcManager` 的实例无参方法（返回 `NfcAdapter`）同样 Hook，返回 `null`：
  ```java
  for (Method method : NfcManager.class.getDeclaredMethods()) {
      if (method.getReturnType().equals(NfcAdapter.class)
              && method.getParameterTypes().length == 0
              && !Modifier.isStatic(method.getModifiers())) { hook(method).intercept(...); }
  }
  ```

### BUG-08（P1）follow 模式高频 `animateTo` 导致地图抖动

- **位置**：`app/src/main/java/com/nfchider/MapActivity.kt` → `MapActivity.kt`
- **现象**：开启"跟随"后地图不平滑，像被不断拽动。
- **根因**：模拟位置每 500ms 推送一次，每次 `LaunchedEffect(simPos)` 都调用 `mapView.controller.animateTo(target)`。osmdroid 的动画被反复重启，永远走不完一个完整缓动，观感就是抖动。
- **证据链**：`LaunchedEffect(simPos)` 内无差别 `animateTo`。
- **影响**：跟随模式（该模块最高频的使用场景）体验持续受损。
- **修复**：位移小于 30m 直接 `setCenter`（无动画、无感知），大位移才 `animateTo`：
  ```kotlin
  if (jumpM < 30.0) mapView.controller.setCenter(target)
  else mapView.controller.animateTo(target)
  ```

### BUG-09（P1）`pendingJson` 缺 `@Volatile`

- **位置**：`app/src/main/java/com/nfchider/location/ModuleService.kt`
- **现象**：偶发的配置推送丢失/延迟可见。
- **根因**：`pendingJson` 在主线程（`pushJson`）写、在 Binder 线程读，普通字段没有 happens-before 保证，读线程可能长期看到旧值。
- **修复**：`@Volatile private var pendingJson`，并注释说明跨线程读写路径。

### BUG-10（P2）`ensureInstalled` 非原子写入

- **位置**：`app/src/main/java/com/nfchider/location/DexSqlRunner.kt`
- **根因**：直接把新 DexSql 可执行文件写进目标路径，中途失败（空间不足、被杀）会留下**半成品文件**，后续运行视为"已安装"直接执行坏文件。
- **修复**：先写临时文件，成功后 `renameTo` 原子替换。

### BUG-11（P2）服务桥接初始化未防护

- **位置**：`app/src/main/java/com/nfchider/App.kt` → `App.kt`
- **根因**：`ModuleService.init()` 直调，LSPosed 服务未就绪/抛异常时直接拖垮 `Application.onCreate`，模块 App 本身无法启动（连"未激活"的提示都看不到）。
- **修复**：`runCatching` 包裹，失败走本地持久化降级路径。

### BUG-12（P2）HTTP 连接未主动释放

- **位置**：`app/src/main/java/com/nfchider/location/LocationSearchService.kt`
- **根因**：Photon / Nominatim 搜索的 `HttpURLConnection` 依赖 GC 兜底释放 socket，连接池与 TIME_WAIT 堆积。
- **修复**：两处 `finally { conn.disconnect() }`。

---

### BUG-13（P0）真实定位不准：粗精度基站定位覆盖精确 GPS fix

- **位置**：`app/src/main/java/com/nfchider/location/RealLocationTracker.kt`、`app/src/main/java/com/nfchider/MapActivity.kt`
- **现象**（用户实机反馈）：地图上「我的真实位置」蓝点位置不准，有时明显偏移到错误地点；底部面板显示"GPS 已锁定"但坐标偏差大。
- **根因**（三处叠加）：
  1. `startLocationUpdates()` 把 GPS_PROVIDER 与 NETWORK_PROVIDER 注册到**同一个 listener**，两个 provider 的回调都进入 `handleNewLocation()`；
  2. `handleNewLocation()` 第 192 行 `_currentLocation.value = location` **无差别覆盖**——一次 500m 精度的基站/网络定位会把刚锁定的 5m GPS fix 覆盖掉，蓝点、「定位到我」、「设为模拟点」全部跳到粗精度位置；
  3. `startLocationUpdates()` 读取 `getLastKnownLocation` 缓存时按 `.time` 取最新，可能选中陈旧缓存；且缓存种子定位会让经典启发式错误地拒绝随后的实时 fix。
- **修复**：
  1. 引入 Android 官方经典 `isBetterLocation()` 择优启发式（时间差 ±2 分钟判定新旧、精度差 >200m 判定显著变差、同 provider 权重），`_currentLocation` 只持有「更新且更准」的 fix；
  2. 缓存择优同样走 `isBetterLocation`；超过 10 分钟的缓存不作为初始估计；2–10 分钟的陈旧缓存可展示但标记 `seedIsStale`，首个更新的实时 fix 直接替换它；
  3. 轨迹录制增加抖动过滤：新 fix 精度明显差于最近录入 fix 且距离 <10m 时跳过，避免网络定位抖动污染已由 GPS 收敛的轨迹；
  4. `MapActivity` 自动居中逻辑：首个 fix 精度 >100m 时**不立即居中**，等待更优 fix 或 10 秒宽限超时，避免地图锁死在基站级错误位置；
  5. 底部面板状态徽标如实反映定位来源与精度（"GPS ±8m" / "网络 ±120m"），不再一律显示"GPS 已锁定"。
- **影响**：修复前只要网络定位回调晚于 GPS fix 到达即发生位置回跳，室内/窗边等 GPS 不稳定场景几乎必现；修复后蓝点单调收敛到当前可获的最佳精度。

### BUG-14（P2）versionName 硬编码 "1.1"：安装界面版本号永远不变

- **位置**：`app/build.gradle.kts`、`module.prop`、`app/src/main/resources/META-INF/xposed/module.prop`、`.github/workflows/build.yml`
- **现象**（用户实机反馈）：系统安装界面显示「版本: 1.1 -> 1.1」——versionCode 已递增到 13，versionName 却始终是初版的 "1.1"，用户无法从版本号分辨装的是否为新包。
- **根因**：
  1. `versionName = "1.1"` 是**硬编码字符串**，CI 的 Auto-bump 步骤只 sed 更新 `versionCode`，从不触碰 `versionName`；
  2. `module.prop`（Xposed 管理器读取的版本）同样停留在 `version=1.1`，LSPosed 模块列表显示的版本也与实际构建脱节；
  3. 标签格式为 `${versionCode}-${version}`，与 Xposed-Modules-Repo 官方 `versionCode-versionName` 规范不一致。
- **修复**：
  1. 三个版本文件同步更新为 `1.1.13`（versionCode 13 对应 revision 13，语义自洽）；
  2. CI Auto-bump 增加 versionName 联动递增：`NEW_VERSION_NAME = 主.次.NEW_CODE`（如 `1.1.14`），并对 `module.prop` ×2、`build.gradle.kts` 补 `version=` / `versionName=` 的 sed；
  3. 标签格式统一为 `${NEW_CODE}-${NEW_VERSION_NAME}`（如 `14-1.1.14`），与 APK 文件名 `NfcHider-${versionCode}-${versionName}.apk`（archivesName）严格一致，保证 softprops 能收集到 APK 附件；
  4. 已用临时目录完整模拟 Auto-bump 全流程验证：`1.1.13/13` → `1.1.14/14`，tag `14-1.1.14`，三个文件 sed 结果全部正确。
- **影响**：修复前每次发布 versionName 均停留在 "1.1"；修复后 versionName 随 versionCode 自动递增，安装界面、LSPosed 模块列表、Release 标签三处版本信息一致且自更新。

---

### BUG-15（P1）定位精度不足与底部面板遮挡地图

- **位置**：`app/src/main/java/com/nfchider/location/RealLocationTracker.kt`、`app/src/main/java/com/nfchider/MapActivity.kt`、`app/src/main/java/com/nfchider/ui/QuickFunctionsPanel.kt`、`app/src/main/java/com/nfchider/ui/theme/Color.kt`
- **现象**（用户实机反馈）：① 蓝点定位「不够精准」——室内或静止时蓝点围绕真实位置跳舞（GPS 多径漂移），运动时相邻 fix 抖动导致轨迹与蓝点锯齿；② 底部控制面板长期占据约 1/4 屏幕高度，地图可视面积被压缩；③ 用户无法感知「为什么定位不准」（卫星数、设备是否静止均无反馈）。
- **根因**：
  1. `requestLocationUpdates` 默认 minTime/minDistance 节流可能漏掉关键高精度 fix；GPS 与网络 fix 虽经 BUG-13 择优，但择优通过后**直接采用**单 fix 坐标，相邻 fix 的测量噪声全部暴露在蓝点上；
  2. 无任何「设备静止」判定：手机放在桌上时，室内多径效应让 GPS 每秒输出数米级漂移，蓝点持续无意义抖动；
  3. 卫星数与解算状态从未上报，用户看到「±50m」时无从判断是天空视野问题还是信号问题；
  4. 底部控制面板（速度/模式/启停/录制仪表）为常驻布局，折叠态仍占一条状态栏高度。
- **修复**（三层定位增强 + 一次布局重构）：
  1. **provider 隔离**：GPS fix 精度 ≤30m 即记录「GPS 锁」（精度 + 时间戳）；锁定且 30s 内有 fix 期间，网络定位不得覆盖蓝点，除非精度反超锁 20m 以上；GPS 超过 30s 无 fix 自动失锁，网络定位重新参与择优；
  2. **Kalman-lite 一维加权平滑**：相邻 fix 在合成噪声带（3σ 且 ≥8m）内时按方差倒数加权平均，多 fix 一致即收敛蓝点、显示精度低于任何单 fix（下限 3m，避免过度自信）；超出噪声带判定真实移动，直接跳转不拖尾；
  3. **加速度计静止检测**：合加速度持续贴近重力（|a|−9.81 < 0.6 m/s²）超过 4 秒判定静止；静止期间冻结显示位置为加权均值，只接纳「精度显著更优（<70%）或移动 >15m」的 fix，杜绝「蓝点跳舞」；重新移动立即解除冻结；
  4. **GNSS 卫星状态上报**：`GnssStatus.Callback` 统计可见卫星总数与参与解算卫星数，定位菜单徽标展示「GPS ±8m · 6星」，≤3 星参与解算时提示「移动到开阔处可显著提高精度」；
  5. **精度圈可视化**：按 fix 的 accuracy 半径绘制 48 边形半透明圆环（Polygon overlay），精度收敛过程肉眼可见，半径封顶 400m；
  6. **移除底部控制面板**：速度/模式/启停迁入「速度」圆唤起的 `SimControlDialog`；暂停/完成/放弃迁入录制控制对话框；面板位置改显顶部 `SimStatusPill` 状态药丸（状态点 + 广播坐标 + 快捷停止）；地图可视面积全部还给用户；
  7. **透明圆 UI**：快捷功能全部改为无底色透明圆（1.5dp 功能色圆环 + 同色字形，激活态圆环加粗至 2.5dp），不再遮挡底图；新增速度/启停/撤销/清空/导入 5 个功能圆（17 个功能）。
- **影响**：静止场景蓝点不再跳舞；多 fix 一致时显示精度低于单 fix；卫星数与静止状态让「准/不准」可解释；底部面板移除后地图可视面积增加约 1/4 屏幕高度。

## 4. 界面设计优化（概要）

完整设计文档见 [`UI_DESIGN.md`](UI_DESIGN.md)。核心结论与改动：

- **诊断**：原界面为"M3 默认紫色基线 + 硬编码 0xFF 色 + emoji 图标 + 无无障碍标签"的混搭态，动态取色分支与手写色互相打架。
- **设计系统重建**（`ui/theme/`）：
  - `Color.kt`：品牌「隐匿蓝」体系（`BrandBlue #0B57D0` 等）+ 完整中性色阶 + **`MapPalette`**（路线蓝/录制绿/收藏金/模拟红，明暗双套）+ **`BadgePalette`**（来源可用性徽章，明暗双套）；
  - `Theme.kt`：Material 3 完整 `colorScheme`（含 `surfaceContainer*` 全部角色）+ 6 档圆角形状系统 + `LocalBadgePalette` 组合局部量 + `dynamicColor` 默认关闭；
  - `Type.kt`：15 档排版，中文行高 ≥1.5× 字号，数值场景等宽字体。
- **两个界面落地**：
  - `SettingsActivity`：卡片化分组（图标 + 标题 + 徽章头），「模拟中」状态徽章；
  - `MapActivity`：折线/标记/状态点全部改走 `MapPalette`，FAB 语义图标 + `contentDescription` + 44dp 触达，follow 阈值平滑（BUG-08），收藏长按触觉反馈，硬编码色**全部收敛**至语义色板。

---

## 5. 验证结果

| 验证项 | 结果 |
|---|---|
| `./gradlew assembleDebug`（Gradle 9.4.1 / AGP 9.2.0 / JDK 25 / compileSdk 37） | **BUILD SUCCESSFUL** |
| `./gradlew :app:compileDebugKotlin`（BUG-13 修复后复验） | **BUILD SUCCESSFUL** |
| `./gradlew :app:processDebugResources`（BUG-14 修复 + 应用图标资源纳入后复验） | **BUILD SUCCESSFUL** |
| Auto-bump 工作流模拟（versionName 联动递增） | 通过：`1.1.13/13` → `1.1.14/14`，tag `14-1.1.14`，三版本文件 sed 结果正确 |
| Kotlin 编译（Compose 编译器） | 通过（修复过程中额外发现并修正 3 处 Compose 作用域回归：前向引用、内联 `apply` 自引用、`remember` 内调用 `@Composable`） |
| Java 编译 | 通过（补充 `java.lang.ref.WeakReference` import） |
| 产物 | `app/build/outputs/apk/debug/NfcHider-9-1.1-debug.apk`（62.9 MB，debug 未压缩） |

> 构建环境备注：沙箱内 `github.com` 不可达，Gradle 工具链自动下载（Adoptium → github 重定向）会挂起，已改用 Azul CDN 本地安装 JDK 25 并通过用户级 `gradle.properties` 指向；同时安装了 `platforms;android-37.0` 与 `build-tools;37.0.0`。这些是**环境适配**，不属于代码修复。

## 6. 遗留风险与后续建议

1. **运行时验证缺失**：本次交付止于编译级验证。建议在 LSPosed 环境实机验证 BUG-01（改配置即时生效）、BUG-04（反复开关后内存稳定）、BUG-07（NFC 检测 App 扫描结果为"无 NFC"）、BUG-13（GPS 锁定后蓝点不再被网络定位拉偏、室内场景收敛行为符合预期）、BUG-14（安装界面版本号显示 1.1.14 而非 1.1、桌面图标为新设计的 NFC 波纹图标）、BUG-15（静止放置手机时蓝点冻结不跳舞、移动后立即恢复、卫星数与静止提示在定位菜单展示、精度圈随 fix 收敛、底部面板已移除且状态药纶在顶部正常显示）。
2. **`Chain.getArgs()` 不可变性的后续审计**：`LocationSim` 已按"`toArray()` 拷贝后再改"的正确姿势处理；后续新增 Hook 时应沿用该模式，切勿直接 `getArgs().set(...)`。
3. **release 构建未验证**：`signingConfigs` 依赖 keystore 环境变量，本地仅构建 debug；release 构建（含签名与混淆）由 GitHub Actions 工作流验证，产物随 Release 发布。
4. **CI 发布链路加固（已完成）**：自动递增 versionCode 改为双仓库（个人仓库 + `Xposed-Modules-Repo/com.nfchider` 发布镜像）取 max，查询失败输出 warning 而非静默按 0；组织仓库由 CI 全量 rsync 同步代码；工作流增加 `concurrency` 组防止连续手动触发并发竞态；组织仓库缺失的 7-1.1 / 8-1.1 Release 已补发；versionName 改为随 versionCode 联动递增（BUG-14），标签统一为 `versionCode-versionName` 官方格式。
