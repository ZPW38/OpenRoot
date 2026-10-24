# OpenRoot 项目长期约定

## 命名（用户明确要求，2026-10-24）

- **漏洞/引擎一律用 CVE 编号对外呈现**，不要用技术名或产品名：
  CVE-2026-43499（内核 profile / ghostlock）、CVE-2026-43284（page cache / DirtyFrag）、
  CVE-2025-21479（Adreno GPU）、CVE-2026-64560（posix-cpu-timers UAF）。
  代码里的类名（RootExploit / DirtyFragRunner / GpuRunner / PctRunner）保持不动。
- **漏洞列表一行一个 CVE，不绑定设备**：选中后用 `payloadFor(DeviceInfo)` 按设备自动匹配
  payload（如 CVE-2025-21479 的 Quest / Snapdragon 两个 build），不要为同一 CVE 拆成多行。

## 目录 / 构建

- 每个引擎一个源码目录 + 一个 Makefile：`native/`、`native-or/`、`native-gpu/`、`native-pct/`；
  产物统一 `build/<or|gpu|pct>/`，只有 `prepareNativePayloads` 写 jniLibs，随后 `stripNativePayloads`。
- 载荷是 PIE 可执行文件但必须叫 `lib*.so` 才会被 APK 打进 `lib/arm64-v8a/`。
- 本机 make 不在 PATH：用 `/d/android-ndk-r27d/prebuilt/windows-x86_64/bin/make.exe`。
- 构建验证：`./gradlew :app:assembleDebug --offline`（离线可跑，依赖已缓存）。

## 引擎统一契约

- 暂存目录 `/data/local/tmp/openroot`，root 交接统一走 `loader`（`native-or/bootstrap.c`）
  + `openroot.xml`（`su_manager=none` → root 命令通道 `cmd/out/done`）。
- 运行结果记 `ExploitMemory`（按引擎 + `uname -r`）；预检/指纹不符不能记为"不支持"。
