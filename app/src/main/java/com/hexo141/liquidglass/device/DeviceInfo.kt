package com.hexo141.liquidglass.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.hexo141.liquidglass.adb.AbsAdbConnectionManager
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
    val kernel: String,
    val selinux: String,
) {
    companion object {
        private const val UNKNOWN = "Unknown"

        /** Props are queried in one shell round trip; order matters when parsing the output. */
        private val COMMANDS = listOf(
            "getprop ro.product.manufacturer",
            "getprop ro.product.model",
            "getprop ro.soc.manufacturer",
            "getprop ro.soc.model",
            "getprop ro.board.platform",
            "getprop ro.hardware",
            "uname -r",
            "getenforce",
        )

        // ---------------------------------------------------------------- over ADB

        @Throws(Exception::class)
        fun fromAdb(manager: AbsAdbConnectionManager): DeviceInfo {
            val output = AdbShell.exec(manager, COMMANDS.joinToString("; "))
            val lines = output.split('\n').map { it.trim() }
            fun line(index: Int): String = lines.getOrNull(index).orEmpty()

            return DeviceInfo(
                deviceName = deviceName(line(0), line(1)),
                chipset = chipset(line(2), line(3), line(4), line(5)),
                kernel = line(6).ifEmpty { UNKNOWN },
                selinux = line(7).ifEmpty { UNKNOWN },
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
                kernel = System.getProperty("os.version")?.trim().orEmpty().ifEmpty { UNKNOWN },
                selinux = localSelinux(),
            )
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

        private fun deviceName(manufacturer: String, model: String): String = when {
            model.isEmpty() -> UNKNOWN
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
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
