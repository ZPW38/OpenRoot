package com.hexo141.liquidglass

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hexo141.liquidglass.adb.AdbAuthenticationFailedException
import com.hexo141.liquidglass.adb.AdbConnectionManager
import com.hexo141.liquidglass.adb.AdbKeyManager
import com.hexo141.liquidglass.adb.AdbPairingService
import com.hexo141.liquidglass.adb.PairingState
import com.hexo141.liquidglass.device.DeviceInfo
import com.hexo141.liquidglass.ui.PairingScreen

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"

        /** How long to wait for `_adb-tls-connect._tcp` before giving up on a direct connect. */
        const val CONNECT_TIMEOUT_MS = 6_000L
    }

    /** 配对服务是否在运行；用 Compose 状态保存，按钮文案自动跟着变 */
    private val pairingRunning = mutableStateOf(false)

    /** 已经配对过（adbd 里存着我们的公钥），不需要再输配对码 */
    private val paired = mutableStateOf(false)

    /** 正在直连中 */
    private val connecting = mutableStateOf(false)

    /** 已连上 adbd */
    private val connected = mutableStateOf(false)

    /** 直连失败后重新露出配对按钮，让用户决定是否用配对码再配一次 */
    private val needsPairing = mutableStateOf(false)

    /** 设备信息：先用本机属性铺底，连上 adbd 后再换成 ADB 查询到的值 */
    private val deviceInfo = mutableStateOf<DeviceInfo?>(null)

    /** 每次进入前台只自动直连一次，避免失败后反复重试 */
    private var autoConnectTried = false

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPairing() else toast(R.string.adb_pairing_permission_needed)
        }

    /** 配对服务在后台把 isPaired 写成 true 时，主界面立即把配对按钮收起来。 */
    private val pairingStateListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PairingState.KEY_PAIRED) {
                val isPaired = PairingState(this).isPaired
                paired.value = isPaired
                if (isPaired) needsPairing.value = false
            }
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

        findViewById<ComposeView>(R.id.main).setContent {
            PairingScreen(
                running = pairingRunning.value,
                paired = paired.value,
                connecting = connecting.value,
                connected = connected.value,
                needsPairing = needsPairing.value,
                deviceInfo = deviceInfo.value,
                onToggle = ::onPrimaryAction
            )
        }

        // 先填本机信息，避免卡片空着；连上 adbd 之后再用 ADB 的结果覆盖
        deviceInfo.value = DeviceInfo.fromLocal(this)

        refreshState()

        intent?.let { handleDebugPair(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        getSharedPreferences(PairingState.PREF_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(pairingStateListener)
        autoConnectTried = false
        maybeAutoConnect()
    }

    override fun onPause() {
        getSharedPreferences(PairingState.PREF_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(pairingStateListener)
        super.onPause()
    }

    private fun refreshState() {
        pairingRunning.value = AdbPairingService.isRunning()
        paired.value = PairingState(this).isPaired
        connected.value = AdbConnectionManager.isConnected()
        loadDeviceInfo()
    }

    // ------------------------------------------------------------------ actions

    /**
     * 已经配对过就直接连，不再弹配对码；失败才把配对按钮露出来。
     *
     * 升级前配过对的设备还没有 isPaired 标记，但本地已经存了 ADB 密钥，这种情况先静默试一次
     * 直连，成功就把标记补上。
     */
    private fun maybeAutoConnect() {
        if (autoConnectTried) return
        if (pairingRunning.value || connected.value || connecting.value) return
        val isPaired = paired.value
        if (!isPaired && !AdbKeyManager.hasStoredKey(this)) return
        autoConnectTried = true
        connect(silent = !isPaired)
    }

    private fun connect(silent: Boolean = false) {
        if (connecting.value) return
        connecting.value = true
        needsPairing.value = false
        Thread({
            val outcome = runCatching {
                AdbConnectionManager.get(this).connectTls(this, CONNECT_TIMEOUT_MS)
            }
            runOnUiThread {
                connecting.value = false
                if (outcome.getOrNull() == true) {
                    connected.value = true
                    paired.value = true
                    PairingState(this).isPaired = true
                    Log.i(TAG, "connected without pairing code")
                    loadDeviceInfo()
                } else {
                    val error = outcome.exceptionOrNull()
                    Log.w(TAG, "direct connect failed (silent=$silent)", error)
                    if (silent) return@runOnUiThread
                    // adbd rejected our key: the pairing is really gone
                    if (error is AdbAuthenticationFailedException) {
                        PairingState(this).isPaired = false
                        paired.value = false
                    }
                    needsPairing.value = true
                    toast(R.string.adb_connect_failed_fallback)
                }
            }
        }, "adb-direct-connect").start()
    }

    /** Pulls the device details over ADB, replacing the locally read placeholder. */
    private fun loadDeviceInfo() {
        if (!connected.value) return
        Thread({
            val info = runCatching { DeviceInfo.fromAdb(AdbConnectionManager.get(this)) }
                .onFailure { Log.w(TAG, "cannot read device info over adb", it) }
                .getOrNull()
            if (info != null) runOnUiThread { deviceInfo.value = info }
        }, "adb-device-info").start()
    }

    private fun onPrimaryAction() {
        when {
            pairingRunning.value -> stopPairing()
            connecting.value -> Unit // already busy
            else -> requestStart()
        }
    }

    /** Debug-only hook so an ADB-driven smoke test can run a pairing attempt with a known code. */
    private fun handleDebugPair(intent: Intent) {
        val code = intent.getStringExtra(AdbPairingService.EXTRA_DEBUG_CODE) ?: return
        val port = intent.getIntExtra(AdbPairingService.EXTRA_DEBUG_PORT, -1)
        val host = intent.getStringExtra(AdbPairingService.EXTRA_DEBUG_HOST)
        AdbPairingService.debugPair(this, code, port, host)
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

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
