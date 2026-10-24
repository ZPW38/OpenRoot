package com.hexo141.liquidglass.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.hexo141.liquidglass.adb.Shell
import com.hexo141.liquidglass.adb.AdbShell
import java.io.File

/**
 * Hardware / OS details of the device adbd is running on.
 *
 * Two sources:
 * - [fromLocal] reads this process' own build props, so the cards are never empty (and stay
 *   meaningful while there is no connection yet).
 * - [fromAdb] runs `getprop` / `uname -r` / `getenforce` through the ADB connection, which is the
 *   authoritative answer once connected (and stays correct if the daemon is on another device).
 */
data class DeviceInfo(
    val deviceName: String,
    val chipset: String,
    /** GPU model, e.g. "Adreno (TM) 730"; the GPU engine matches on this. */
    val gpu: String,
    /** Raw release string, e.g. "15" — engines match on this. */
    val androidRelease: String,
    val androidVersion: String,
    val kernel: String,
    val selinux: String,
    /** `ro.product.device` (e.g. "OP6113L1"); the CVE-2026-64560 payload gates on it. */
    val codename: String = "Unknown",
    /** `ro.build.fingerprint`; the CVE-2026-64560 payload only accepts one exact build. */
    val fingerprint: String = "Unknown",
) {
    companion object {
        private const val UNKNOWN = "Unknown"

        /**
         * One shell round trip with a marker before every query: `getprop <missing>` prints
         * nothing at all, so markers keep the fields aligned no matter which prop is absent.
         */
        private const val MARKER = "@@"

        private val QUERIES = listOf(
            "getprop ro.product.manufacturer",
            "getprop ro.product.model",
            "getprop ro.soc.manufacturer",
            "getprop ro.soc.model",
            "getprop ro.board.platform",
            "getprop ro.hardware",
            "getprop ro.build.version.release",
            "getprop ro.build.version.sdk",
            "uname -r",
            "getenforce",
            "cat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null",
            "getprop ro.hardware.egl",
            "getprop ro.product.device",
            "getprop ro.build.fingerprint",
        )

        private val COMMAND: String =
            QUERIES.joinToString("; ") { "echo '$MARKER'; $it" }

        // ---------------------------------------------------------------- over ADB

        @Throws(Exception::class)
        fun fromAdb(shell: Shell): DeviceInfo {
            val output = shell.exec(COMMAND)
            val fields = output.split(MARKER).map { it.trim() }
            fun field(index: Int): String = fields.getOrNull(index).orEmpty()

            return DeviceInfo(
                deviceName = deviceName(field(1), field(2)),
                chipset = chipset(field(3), field(4), field(5), field(6)),
                gpu = gpu(field(11), field(12)),
                androidRelease = field(7),
                androidVersion = androidVersion(field(7), field(8)),
                kernel = field(9).ifEmpty { UNKNOWN },
                selinux = field(10).ifEmpty { UNKNOWN },
                codename = field(13).ifEmpty { UNKNOWN },
                fingerprint = field(14).ifEmpty { UNKNOWN },
            )
        }

        // ---------------------------------------------------------------- locally

        fun fromLocal(context: Context): DeviceInfo {
            val customName = runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.trim().orEmpty()
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()

            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL?.trim().orEmpty()
            } else {
                ""
            }
            val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER?.trim().orEmpty()
            } else {
                ""
            }

            return DeviceInfo(
                deviceName = customName.ifEmpty { deviceName(manufacturer, model) },
                chipset = chipset(socManufacturer, socModel,
                    Build.BOARD?.trim().orEmpty(), Build.HARDWARE?.trim().orEmpty()),
                gpu = localGpu(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                androidVersion = androidVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT.toString()),
                kernel = System.getProperty("os.version")?.trim().orEmpty().ifEmpty { UNKNOWN },
                selinux = localSelinux(),
                codename = Build.DEVICE?.trim().orEmpty().ifEmpty { UNKNOWN },
                fingerprint = Build.FINGERPRINT?.trim().orEmpty().ifEmpty { UNKNOWN },
            )
        }

        /** KGSL exposes the Adreno model here on Qualcomm devices. */
        private fun localGpu(): String {
            val gpu = runCatching {
                File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim()
            }.getOrNull().orEmpty()
            return gpu.ifEmpty {
                runCatching { Build.HARDWARE?.trim().orEmpty() }.getOrDefault("")
            }.ifEmpty { UNKNOWN }
        }

        /** SELinux mode from selinuxfs: 1 = enforcing, 0 = permissive. */
        private fun localSelinux(): String {
            if (!File("/sys/fs/selinux").exists()) return "Disabled"
            val enforcing = runCatching {
                File("/sys/fs/selinux/enforce").readText().trim()
            }.getOrNull()
            return when (enforcing) {
                "1" -> "Enforcing"
                "0" -> "Permissive"
                else -> UNKNOWN
            }
        }

        // ---------------------------------------------------------------- helpers

        /** e.g. "Android 15 (API 35)". */
        private fun androidVersion(release: String, sdk: String): String {
            val version = release.trim()
            val api = sdk.trim()
            return when {
                version.isEmpty() && api.isEmpty() -> UNKNOWN
                version.isEmpty() -> "API $api"
                api.isEmpty() -> "Android $version"
                else -> "Android $version (API $api)"
            }
        }

        private fun deviceName(manufacturer: String, model: String): String = when {
            model.isEmpty() -> UNKNOWN
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }

        /**
         * GPU label: KGSL's `gpu_model` is authoritative ("Adreno (TM) 730"), while
         * `ro.hardware.egl` still says "adreno" on builds that hide the sysfs node.
         */
        private fun gpu(gpuModel: String, hardwareEgl: String): String = when {
            gpuModel.isNotEmpty() && !gpuModel.equals(UNKNOWN, ignoreCase = true) -> gpuModel
            hardwareEgl.isNotEmpty() && !hardwareEgl.equals(UNKNOWN, ignoreCase = true) -> hardwareEgl
            else -> UNKNOWN
        }

        private fun chipset(
            socManufacturer: String,
            socModel: String,
            boardPlatform: String,
            hardware: String,
        ): String {
            if (socModel.isNotEmpty() && !socModel.equals(UNKNOWN, ignoreCase = true)) {
                return when {
                    socManufacturer.isEmpty() || socModel.startsWith(socManufacturer, ignoreCase = true) ->
                        socModel
                    else -> "$socManufacturer $socModel"
                }
            }
            // Older releases (and some OEMs) only fill in the board/hardware props.
            return listOf(boardPlatform, hardware)
                .firstOrNull { it.isNotEmpty() && !it.equals(UNKNOWN, ignoreCase = true) }
                ?: UNKNOWN
        }
    }
}
