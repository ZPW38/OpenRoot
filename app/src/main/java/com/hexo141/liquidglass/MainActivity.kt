package com.hexo141.liquidglass

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hexo141.liquidglass.adb.AdbPairingService
import com.hexo141.liquidglass.ui.PairingScreen

class MainActivity : AppCompatActivity() {

    /** 配对服务是否在运行；用 Compose 状态保存，按钮文案自动跟着变 */
    private val pairingRunning = mutableStateOf(false)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPairing() else toast(R.string.adb_pairing_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val (backgroundColor, accentColor) = themeColors()
        findViewById<ComposeView>(R.id.main).setContent {
            PairingScreen(
                running = pairingRunning.value,
                onToggle = { if (pairingRunning.value) stopPairing() else requestStart() },
                backgroundColor = backgroundColor,
                accentColor = accentColor
            )
        }

        pairingRunning.value = AdbPairingService.isRunning()
    }

    override fun onResume() {
        super.onResume()
        pairingRunning.value = AdbPairingService.isRunning()
    }

    private fun requestStart() {
        if (hasNotificationPermission()) {
            startPairing()
        } else {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startPairing() {
        AdbPairingService.start(this)
        pairingRunning.value = true
    }

    private fun stopPairing() {
        AdbPairingService.stop(this)
        pairingRunning.value = false
    }

    /** 取当前 XML 主题（Material3 DayNight）的 colorBackground / colorPrimary */
    private fun themeColors(): Pair<Color, Color> {
        val typedArray = theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorBackground, android.R.attr.colorPrimary)
        )
        return try {
            Color(typedArray.getColor(0, 0xFFFFFBFE.toInt())) to
                Color(typedArray.getColor(1, 0xFF6750A4.toInt()))
        } finally {
            typedArray.recycle()
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
