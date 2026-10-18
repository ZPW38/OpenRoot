# 第三方代码来源与许可

本项目为 GPL-3.0（见 `LICENSE`）。以下第三方代码被直接并入源码树（vendored），
版权归原作者所有，许可与来源见下。

## ADB 配对协议栈

| 来源 | 路径 | 版本 | 许可 |
| --- | --- | --- | --- |
| [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android) | `app/src/main/java/com/hexo141/liquidglass/adb/**` | 3.1.1 | `GPL-3.0-or-later OR Apache-2.0`（本项目按 Apache-2.0 使用） |
| [MuntashirAkon/spake2-java](https://github.com/MuntashirAkon/spake2-java)（`java` 纯 Java 模块） | `app/src/main/java/com/hexo141/liquidglass/crypto/**` | 2.2.0 | LGPL-3.0 |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | 参考实现：`AdbPairingService`、`AdbPairDialogFragment` 的配对流程与通知栏交互 | master | Apache-2.0 |

被并入的具体文件：

- `com/hexo141/liquidglass/adb/`：`PairingConnectionCtx`、`PairingAuthCtx`、`KeyPair`、`SslUtils`、
  `AndroidPubkey`、`StringCompat`、`ByteArrayNoThrowOutputStream`、`AdbMdns`（原 `…adb.android` 包，改到
  `…adb.mdns`）、`AndroidUtils`，以及同目录下未被本功能直接使用但对编译/后续连接 ADB 有用的
  `AdbConnection`、`AdbProtocol`、`AdbStream`、`LocalServices`、`PRNGFixes`、异常类等。
- `com/hexo141/liquidglass/crypto/`：纯 Java 的 SPAKE2 实现（`spake2/Spake2Context`、`Spake2Role`）及其
  依赖的 Ed25519 / X25519 运算（`ed25519/*`、`x25519/*`）。

本地改动：仅将包名 `io.github.muntashirakon.{adb,crypto}` 重写为 `com.hexo141.liquidglass.{adb,crypto}`，
`socket` 相关的 Java 实现保持不变。没有使用 native 代码，因此不需要 NDK。

## Liquid Glass 按钮效果

| 来源 | 方式 | 版本 | 许可 |
| --- | --- | --- | --- |
| [Kyant0/Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`） | Maven 依赖（编译期库本体） | 2.0.0-alpha03 | Apache-2.0 |
| 同仓库目录示例 `components/LiquidButton.kt` | 参考实现：`app/src/main/java/com/hexo141/liquidglass/ui/LiquidGlassButton.kt` 去掉拖拽高光后的胶囊玻璃按钮 | master | Apache-2.0 |

该库是 Compose 实现，因此 `MainActivity` 用 `ComposeView` 承载主界面，Compose 编译器
插件版本与 AGP 9.3 内置 Kotlin 一致（`org.jetbrains.kotlin.plugin.compose:2.2.10`）。
该依赖同时拉入 Jetbrains Compose 多平台构件（Android 变体映射到 `androidx.compose.*` 1.10.x）
与 `io.github.kyant0:shapes`，均来自 Maven Central（阿里云镜像）。

## 参考但未复制

`RikkaApps/Shizuku` 的 `manager/src/main/jni/adb_pairing.cpp` 使用 BoringSSL 的 SPAKE2 实现
（`libadb.so`）；本项目改用上面的纯 Java SPAKE2，行为一致但无需编译 native 库。

## 新增运行时依赖（均来自 Maven Central）

- `org.bouncycastle:bcprov-jdk18on` —— SPAKE2 之后的 HKDF / AES-GCM 运算
- `org.bouncycastle:bcpkix-jdk18on` —— 生成自签名证书（TLS 客户端证书）
- `org.conscrypt:conscrypt-android` —— 提供 TLSv1.3 的 `exportKeyingMaterial`
- `androidx.annotation:annotation`
- `io.github.kyant0:backdrop` —— 液态玻璃按钮（见上文）
