# NFC Hider
LSPosed模块：对目标应用隐藏 NFC 硬件
- TG公众号:https://t.me/MCCRLAB
- Telegram：https://t.me/mccrlab707
- Issue：https://github.com/furina707/com.nfchider
- 仓库：https://github.com/Xposed-Modules-Repo/com.nfchider

## 功能

- **NFC 隐藏**：拦截 `PackageManager`（`hasSystemFeature` / `getSystemAvailableFeatures`）、`NfcAdapter` 与 `NfcManager.getDefaultAdapter()`，目标应用无法探测 NFC 硬件。安装即默认开启。
- **按应用生效**：LSPosed 勾选目标 App，重启生效；App 内开关实时启停。

## 使用

1. 安装并启用 LSPosed，勾选 **NFC Hider**。
2. 作用域勾选目标 App，重启该 App。
3. 打开 NFC Hider，可查看/切换 **NFC 隐藏**开关与 **Hook 总开关**。
   - **NFC 隐藏**默认开启，装完即可生效；只有关闭它或关闭「Hook 总开关」才会停止隐藏。
   - **Hook 总开关**是总闸，关闭后 NFC 隐藏停止。

## 环境

- Android 10+（API 29+）
- LSPosed / EdXposed（libxposed API 101+，Target 102）

## 编译

需 JDK 17+、Compile SDK 37：

```bash
git clone https://github.com/Xposed-Modules-Repo/com.nfchider.git
cd com.nfchider
./gradlew assembleRelease
```
