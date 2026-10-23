# OpenRoot

OpenRoot is an Android app that talks to the device's own ADB daemon over wireless debugging. It
pairs once, remembers the key, reconnects without a pairing code, shows what the device actually is,
and can run a bundled payload that grants temporary root on supported kernels.

## Features

- **Wireless debugging pairing.** Discovers `_adb-tls-pairing._tcp` over mDNS, collects the six
  digit code from a notification, and completes the ADB pairing handshake (TLS 1.3 keying material
  export, SPAKE2 over edwards25519, AES-128-GCM peer info exchange).
- **Pair once, connect afterwards.** The successful pairing is remembered, so the pairing button
  disappears and the app connects directly with the stored RSA key. If the direct connection fails,
  the pairing button comes back and a code can be used again.
- **Device information over ADB.** Name, SoC, Android version, kernel release and SELinux mode are
  queried from the connected daemon (`getprop`, `uname -r`, `getenforce`), not from the app's own
  build props.
- **Kernel support status.** The main screen reports whether the connected kernel has a bundled
  profile.
- **Temporary root, two engines.** On an arm64 device the app picks whichever engine can run:
  - *Kernel profile*: the payload and a profile matching the exact `uname -r` are pushed to
    `/data/local/tmp/openroot` and executed as the `shell` user.
  - *DirtyFrag*: a page cache write primitive that loads a small kernel module. It needs no
    per-device profile, only a kernel major/minor that matches one of the bundled module images,
    and it runs inside the app process because it uses the IPsec transform the app installs.

  In both cases `ksud` is handed to the payload (or staged for the module) so KernelSU comes up
  right after root, and the manager is opened when the handoff reports success.

## Requirements

| Item | Requirement |
| --- | --- |
| Pairing | Android 11 (API 30) or newer, wireless debugging enabled |
| Payload | `arm64-v8a` device |
| Build | JDK 17+, Android SDK with API 36, Android NDK r27d |

The UI itself runs from Android 7.0 (API 24), but pairing and the payload need newer releases.

## Build

1. Point Gradle at the SDK (`local.properties`, `sdk.dir`).
2. Install Android NDK r27d. The build looks for it in this order:
   `ndk.dir` in `local.properties` → `ANDROID_NDK_HOME` → `NDK_ROOT` → `D:/android-ndk-r27d`.
3. Build:

```sh
./gradlew :app:assembleDebug
```

The APK contains the payload (`lib/arm64-v8a/libghostlock.so`) and every bundled kernel profile
(`assets/kernel_profiles/*.bin`). Both are produced by the build itself:

- `buildNativeExploit` runs the NDK `make` over `native/` and produces `build/native/ghostlock`.
- `prepareNativeExploit` copies it into `app/src/main/jniLibs/arm64-v8a/libghostlock.so`.
- `stripNativeExploit` strips the packaged copy.

To build the payload by hand:

```sh
export NDK_ROOT=/path/to/android-ndk-r27d
make -C native ghostlock        # writes build/native/ghostlock
```

## Usage

1. Enable **Wireless debugging** in Developer options on the device.
2. Open OpenRoot and tap **Start pairing**. The app keeps a foreground notification while it looks
   for the pairing service.
3. Open **Pair device with pairing code** on the device, then enter the six digit code in the
   notification. Once it succeeds, the pairing button is hidden.
4. From then on the app connects by itself. The status card shows `Supported` or `Unsupported` for
   the connected kernel, and **Exploit** appears only when a profile is available.
5. Tap **Exploit** to run the payload. The native log is shown at the bottom of the screen.

## How it works

```
MainActivity ──┬─ AdbPairingService ── mDNS ── PairingConnectionCtx ── PairingAuthCtx ── Spake2
               │                                                └── TLS 1.3 (bundled Conscrypt)
               ├─ AdbConnectionManager ── AdbConnection ── AdbShell ── getprop / uname / getenforce
               └─ RootExploit ── payload + profile ── /data/local/tmp/openroot
```

- Pairing TLS uses the Conscrypt copy bundled in the APK, so `exportKeyingMaterial` is a normal
  library call instead of a hidden-API reflection into the platform provider.
- SPAKE2 is implemented in plain Java (`crypto/spake2`) on top of a small self-contained
  edwards25519 group (`Ed25519Group`). The mask points are the standard SPAKE2 `M` and `N`.
- The shell helpers in `adb/AdbShell.kt` stream commands over the ADB connection; the same channel
  is used to push the payload and its profile.

## Supported kernels and adding a new device

### What the status card means

| State | Meaning |
| --- | --- |
| `Supported` | A kernel profile matches this exact `uname -r`, or a previous run of another engine succeeded on this build |
| `Untested` | An engine can be attempted but has never succeeded here; whether the device is actually vulnerable is only known after a run |
| `Unsupported` | No profile matches and no module image covers this kernel major/minor, or the device is not arm64 |

The profile engine is an exact match, so `Supported` there means "this build was verified". The page
cache engine cannot tell a patched kernel from a vulnerable one without running, which is why it
starts as `Untested` and remembers the outcome of the first run.

The payload needs a profile that matches the target kernel **exactly**, including suffixes such as
`-4k` or `-abogki...`. Profiles are matched by the full `uname -r` string and live in
`app/src/main/assets/kernel_profiles/<release>.bin`. The app reports `Unsupported` when the string
has no matching file.

To add a device:

1. Connect OpenRoot to the device and read the **Kernel** line on the main screen (or run
   `adb shell uname -r`). Copy the string exactly.
2. Produce a profile for that kernel build. A profile is the GLK1 binary layout the payload reads:
   the kernel release, the structure offsets the exploit needs, and the route selection. Offsets are
   derived from the device's own `boot.img` (or a full OTA package) for that build.
3. Save the file as `app/src/main/assets/kernel_profiles/<release>.bin`, for example
   `app/src/main/assets/kernel_profiles/6.1.138-android14-11-g0c3d559bcd85-ab14529422.bin`.
4. Rebuild and install. The status card switches to `Supported` and the **Exploit** button appears.
5. Run it once and watch the log. A profile with wrong offsets usually fails early with a read or
   lookup error instead of gaining root; treat a new profile as unverified until a run succeeds.

Notes:

- Do not rename or trim the release string; `6.1.90-perf+` and `6.1.90-perf` are different keys.
- Several profiles may share a kernel major version but differ in offsets. Add one file per exact
  build rather than guessing.
- To list what is bundled:

```sh
ls app/src/main/assets/kernel_profiles
```

## Project layout

```
app/src/main/java/com/hexo141/liquidglass/
  adb/        mDNS discovery, pairing service, ADB connection, shell helpers, SPAKE2 glue
  crypto/     SPAKE2 context, edwards25519 group, scalar reduction
  device/     device information read over ADB
  root/       payload push and execution
  ui/         liquid glass components and screens
native/       arm64 payload sources for the kernel-profile engine (Makefile)
native-or/    arm64 payload sources for the page-cache engine (Makefile)
app/src/main/assets/kernel_profiles/   bundled kernel profiles
```

## Links

- Kernel-profile engine upstream: <https://github.com/YuKongA/ghostlock-app>
- Page-cache engine upstream: <https://github.com/diabl0w/DFRoot>

## Continuous integration

`.github/workflows/android.yml` builds on every push to `main`: it installs JDK 17 and NDK r27d,
runs `./gradlew assembleRelease` with `ANDROID_NDK_HOME` set, signs the APK, and publishes a release
tagged with the Beijing date.

## License

See `LICENSE`. Third-party notices are collected in `THIRD_PARTY_NOTICES.md`.
