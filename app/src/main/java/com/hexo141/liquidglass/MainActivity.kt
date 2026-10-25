package com.hexo141.liquidglass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import com.hexo141.liquidglass.adb.AdbAuthenticationFailedException
import com.hexo141.liquidglass.adb.AdbConnectionManager
import com.hexo141.liquidglass.adb.AdbKeyManager
import com.hexo141.liquidglass.adb.AdbPairingService
import com.hexo141.liquidglass.adb.AdbShellAdapter
import com.hexo141.liquidglass.adb.ConnectionMode
import com.hexo141.liquidglass.adb.ConnectionSettings
import com.hexo141.liquidglass.adb.PairingState
import com.hexo141.liquidglass.adb.Shell
import com.hexo141.liquidglass.consent.ConsentStore
import com.hexo141.liquidglass.device.DeviceInfo
import com.hexo141.liquidglass.root.DirtyFragRunner
import com.hexo141.liquidglass.root.ExploitMemory
import com.hexo141.liquidglass.root.GpuRunner
import com.hexo141.liquidglass.root.KsuManager
import com.hexo141.liquidglass.root.PctRunner
import com.hexo141.liquidglass.root.RootExploit
import com.hexo141.liquidglass.root.RootHandoff
import com.hexo141.liquidglass.root.RootShell
import com.hexo141.liquidglass.ui.ConsentScreen
import com.hexo141.liquidglass.ui.ConsentTransition
import com.hexo141.liquidglass.ui.ExploitLogScreen
import com.hexo141.liquidglass.shizuku.ShizukuBridge
import com.hexo141.liquidglass.shizuku.ShizukuShell
import com.hexo141.liquidglass.ui.AboutScreen
import com.hexo141.liquidglass.ui.AppScaffold
import com.hexo141.liquidglass.ui.PairingScreen
import com.hexo141.liquidglass.ui.ExploitChoice
import com.hexo141.liquidglass.ui.SupportState
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"

        /** Engine ids used by the picker; one per vulnerability, never per device or payload. */
        const val ENGINE_CVE_43499 = "cve-2026-43499"
        const val ENGINE_CVE_64560 = "cve-2026-64560"
        const val ENGINE_CVE_21479 = "cve-2025-21479"
        const val ENGINE_CVE_43284 = "cve-2026-43284"

        /** How long to wait for `_adb-tls-connect._tcp` before giving up on a direct connect. */
        const val CONNECT_TIMEOUT_MS = 6_000L

        /** How long the plain TCP connect may take, including the on-device authorisation prompt. */
        const val TCP_CONNECT_TIMEOUT_S = 45L

        /**
         * Reference width of the layout in dp. Bigger number = smaller UI (more dp per screen):
         * 411 is a Pixel/OnePlus phone, 460 draws everything about 11% smaller.
         */
        const val DESIGN_WIDTH_DP = 460f

        /** Density clamps, so neither a tiny nor a huge panel produces an unusable layout. */
        const val MIN_UI_SCALE = 1.0f
        const val MAX_UI_SCALE = 3.5f
    }

    /** First start: the GPL-3.0 text and the temporary-root disclaimer must both be accepted. */
    private val consentAccepted = mutableStateOf(false)

    /** Whether the pairing service runs; Compose state so the button label follows it. */
    private val pairingRunning = mutableStateOf(false)

    /** Already paired: adbd holds our public key, so no pairing code is needed. */
    private val paired = mutableStateOf(false)

    /** A direct connect is in flight. */
    private val connecting = mutableStateOf(false)

    /** How the app reaches a shell: wireless pairing, a plain TCP endpoint, or Shizuku. */
    private val connectionMode = mutableStateOf(ConnectionMode.PAIRING)

    /** `host:port` shown next to the TCP button. */
    private val tcpEndpoint = mutableStateOf("127.0.0.1:5555")

    /** Shizuku service running and this app allowed to use it. */
    private val shizukuReady = mutableStateOf(false)

    /** adbd is connected. */
    private val connected = mutableStateOf(false)

    /** Direct connect failed: show the pairing button again so a code can be used. */
    private val needsPairing = mutableStateOf(false)

    /** Device info: local props first, replaced by the values read over ADB once connected. */
    private val deviceInfo = mutableStateOf<DeviceInfo?>(null)

    /** Support state, used directly by the status card. */
    private val supportState = mutableStateOf(SupportState.NotConnected)

    /** CVE id of the engine in use; null when nothing can run here. */
    private val engineLabel = mutableStateOf<String?>(null)

    /** A temporary-root payload is running. */
    private val exploiting = mutableStateOf(false)

    /** Live payload output. */
    private val exploitLog = mutableStateOf<List<String>>(emptyList())

    /** Result line after the payload ends. */
    private val exploitStatus = mutableStateOf<String?>(null)

    /** Progress bar, driven by the payload's real stages, 0..1. */
    private val exploitProgress = mutableStateOf(0f)

    /** Whether the vulnerability picker is shown. */
    private val pickerVisible = mutableStateOf(false)

    /** Active bottom-bar tab: 0 home, 1 exploit log, 2 about. */
    private val selectedTab = mutableStateOf(0)

    /** Vulnerabilities offered for the connected device. */
    private val exploitChoices = mutableStateOf<List<ExploitChoice>>(emptyList())

    /** Resolved root manager (with its ksud path); null when none is installed. */
    private val ksudTarget = mutableStateOf<KsuManager.Target?>(null)

    /** Root command channel available (bootstrap came up). */
    private val commandMode = mutableStateOf(false)

    /** Auto-connect at most once per foreground entry, so a failure does not loop. */
    private var autoConnectTried = false

    /** Persisted connection mode and TCP endpoint. */
    private val settings by lazy { ConnectionSettings(this) }

    private val refreshHandler = Handler(Looper.getMainLooper())

    /** While pairing runs in the background, align the state once a second. */
    private val refreshTick = object : Runnable {
        override fun run() {
            refreshState()
            if (pairingRunning.value) refreshHandler.postDelayed(this, 1000)
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPairing() else toast(R.string.adb_pairing_permission_needed)
        }

    /** Hide the pairing button as soon as the service writes isPaired = true. */
    private val pairingStateListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PairingState.KEY_PAIRED) {
                val isPaired = PairingState(this).isPaired
                paired.value = isPaired
                if (isPaired) needsPairing.value = false
                // The service stops after a successful pairing: align now, do not wait for the tick.
                pairingRunning.value = AdbPairingService.isRunning()
            }
        }

    /**
     * Makes the layout measure the same on every device, ignoring "Display size" and "Font size".
     *
     * The density is derived from the shortest screen side so the layout always spans
     * [DESIGN_WIDTH_DP] dp: devices ship wildly different factory densities, and using the raw
     * physical one made the cards look bloated on some of them and correct on others. Raising the
     * design width is the single knob for the overall scale — a bigger number means more dp fit on
     * the screen, i.e. smaller text, cards and paddings. It is clamped so a tiny or a huge panel
     * does not end up unusable, and `fontScale` is pinned to 1.0 for the same reason.
     *
     * The activity has no `configChanges`, so a system change recreates it and lands here again.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1f

        val metrics = newBase.resources.displayMetrics
        val shortestSide = minOf(metrics.widthPixels, metrics.heightPixels)
        if (shortestSide > 0) {
            val scale = (shortestSide / DESIGN_WIDTH_DP).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
            config.densityDpi = (scale * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        }

        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // The wallpaper runs edge to edge; each screen pads its own content against
        // WindowInsets.safeDrawing, so nothing sits under the status or navigation bar.

        consentAccepted.value = ConsentStore.isAccepted(this)
        connectionMode.value = settings.mode
        tcpEndpoint.value = settings.endpoint()

        // Back closes the log page or the picker first, and only then leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectedTab.value != 0 -> selectedTab.value = 0
                    pickerVisible.value -> pickerVisible.value = false
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        findViewById<ComposeView>(R.id.main).setContent {
            // Fade into the main screen once both documents are accepted; before that it is not
            // composed at all and no ADB work happens.
            ConsentTransition(
                accepted = consentAccepted.value,
                consent = { ConsentScreen(onAccept = ::acceptConsent) },
                main = {
                    AppScaffold(
                        selectedTab = selectedTab.value,
                        onTabSelected = { selectedTab.value = it },
                        home = { backdrop ->
                            PairingScreen(
                                running = pairingRunning.value,
                                paired = paired.value,
                                connecting = connecting.value,
                                connected = connected.value,
                                needsPairing = needsPairing.value,
                                supportState = supportState.value,
                                engineLabel = engineLabel.value,
                                deviceInfo = deviceInfo.value,
                                exploiting = exploiting.value,
                                exploitLog = exploitLog.value,
                                exploitStatus = exploitStatus.value,
                                exploitChoices = exploitChoices.value,
                                pickerVisible = pickerVisible.value,
                                mode = connectionMode.value,
                                tcpEndpoint = tcpEndpoint.value,
                                shizukuReady = shizukuReady.value,
                                backdrop = backdrop,
                                onToggle = ::onPrimaryAction,
                                onConnectTcp = { connect() },
                                onConnectShizuku = ::connectShizuku,
                                onSelectMode = ::setConnectionMode,
                                onExploit = ::openEnginePicker,
                                onPickEngine = ::startExploit,
                                onDismissPicker = { pickerVisible.value = false },
                                onOpenLog = { selectedTab.value = 1 },
                            )
                        },
                        log = { backdrop ->
                            ExploitLogScreen(
                                lines = exploitLog.value,
                                status = exploitStatus.value,
                                progress = exploitProgress.value,
                                commandMode = commandMode.value,
                                running = exploiting.value,
                                onCommand = ::runRootCommand,
                                backdrop = backdrop,
                            )
                        },
                        about = { backdrop ->
                            AboutScreen(backdrop = backdrop)
                        },
                    )
                },
            )
        }

        if (consentAccepted.value) startMain()
    }

    /** Both documents accepted: store the version and time, then start what the screen needs. */
    private fun acceptConsent() {
        ConsentStore.accept(this)
        consentAccepted.value = true
        startMain()
        beginLiveUpdates()
    }

    private fun startMain() {
        // Fill local device info first so the cards are never empty; ADB values replace it.
        deviceInfo.value = DeviceInfo.fromLocal(this)
        shizukuReady.value = ShizukuBridge.isReady()
        refreshState()
        intent?.let { handleDebugPair(it) }
    }

    /** State sync while the main screen lives: one tick a second, instant pairing updates, one auto-connect. */
    private fun beginLiveUpdates() {
        refreshState()
        getSharedPreferences(PairingState.PREF_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(pairingStateListener)
        autoConnectTried = false
        maybeAutoConnect()
        refreshHandler.removeCallbacks(refreshTick)
        refreshHandler.postDelayed(refreshTick, 1000)
    }

    override fun onResume() {
        super.onResume()
        // No main screen and no auto-connect before the documents are accepted.
        if (!consentAccepted.value) return
        beginLiveUpdates()
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTick)
        getSharedPreferences(PairingState.PREF_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(pairingStateListener)
        super.onPause()
    }

    private fun refreshState() {
        pairingRunning.value = AdbPairingService.isRunning()
        paired.value = PairingState(this).isPaired
        connected.value = when (connectionMode.value) {
            ConnectionMode.SHIZUKU -> ShizukuBridge.isReady()
            else -> AdbConnectionManager.isConnected()
        }
        if (!connected.value) {
            supportState.value = SupportState.NotConnected
            engineLabel.value = null
            // Drop the picker when the connection is gone; the button is hidden as well.
            pickerVisible.value = false
        }
        loadDeviceInfo()
    }

    // ------------------------------------------------------------------ actions

    /**
     * Connect directly when the device is already paired instead of asking for a code again; only a
     * failure brings the pairing button back.
     *
     * A device paired before the isPaired flag existed still has our ADB key stored, so that case
     * tries one silent direct connect and fills the flag in when it works.
     */
    private fun maybeAutoConnect() {
        if (autoConnectTried) return
        if (pairingRunning.value || connected.value || connecting.value) return
        when (connectionMode.value) {
            // Nothing to pair: just try the stored endpoint or the running Shizuku service.
            ConnectionMode.TCP, ConnectionMode.SHIZUKU -> {
                autoConnectTried = true
                connect(silent = true)
            }
            ConnectionMode.PAIRING -> {
                val isPaired = paired.value
                if (!isPaired && !AdbKeyManager.hasStoredKey(this)) return
                autoConnectTried = true
                connect(silent = !isPaired)
            }
        }
    }

    /** The command channel the payload runners should use for the selected mode. */
    private fun currentShell(): Shell =
        if (connectionMode.value == ConnectionMode.SHIZUKU) {
            ShizukuShell()
        } else {
            AdbShellAdapter(AdbConnectionManager.get(this))
        }

    /**
     * Plain `adb tcpip` endpoint. No pairing code is involved: adbd sends the ordinary
     * "Allow USB debugging?" prompt on the device and the user accepts the key this app already
     * holds. Because that prompt can take a while, a rejected first attempt must not abort.
     */
    private fun connectTcp(): Boolean {
        val manager = AdbConnectionManager.get(this)
        manager.setThrowOnUnauthorised(false)
        manager.setTimeout(TCP_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        val ok = manager.connect(settings.tcpHost, settings.tcpPort)
        manager.setTimeout(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        return ok
    }

    /** Switches the transport and starts over with a clean state. */
    private fun setConnectionMode(mode: ConnectionMode) {
        if (mode == connectionMode.value || connecting.value) return
        runCatching { AdbConnectionManager.get(this).disconnect() }
        connected.value = false
        supportState.value = SupportState.NotConnected
        engineLabel.value = null
        pickerVisible.value = false
        needsPairing.value = false
        autoConnectTried = false
        settings.mode = mode
        connectionMode.value = mode
        tcpEndpoint.value = settings.endpoint()
        shizukuReady.value = ShizukuBridge.isReady()
        maybeAutoConnect()
    }

    /** Shizuku mode entry point: ask for the permission once, then connect. */
    private fun connectShizuku() {
        if (!ShizukuBridge.isAvailable()) {
            toast(R.string.shizuku_not_running)
            return
        }
        if (ShizukuBridge.hasPermission()) {
            shizukuReady.value = true
            connect()
            return
        }
        ShizukuBridge.requestPermission { granted ->
            runOnUiThread {
                shizukuReady.value = ShizukuBridge.isReady()
                if (granted) {
                    connect()
                } else {
                    toast(R.string.shizuku_permission_denied)
                }
            }
        }
    }

    private fun connect(silent: Boolean = false) {
        if (connecting.value) return
        connecting.value = true
        needsPairing.value = false
        val mode = connectionMode.value
        Thread({
            val outcome = runCatching {
                when (mode) {
                    ConnectionMode.TCP -> connectTcp()
                    ConnectionMode.SHIZUKU -> ShizukuBridge.isReady()
                    ConnectionMode.PAIRING -> {
                        val manager = AdbConnectionManager.get(this)
                        manager.setThrowOnUnauthorised(true)
                        manager.connectTls(this, CONNECT_TIMEOUT_MS)
                    }
                }
            }
            runOnUiThread {
                connecting.value = false
                if (outcome.getOrNull() == true) {
                    connected.value = true
                    supportState.value = SupportState.Checking
                    if (mode == ConnectionMode.PAIRING) {
                        paired.value = true
                        PairingState(this).isPaired = true
                    }
                    Log.i(TAG, "connected in mode $mode")
                    loadDeviceInfo()
                } else {
                    val error = outcome.exceptionOrNull()
                    Log.w(TAG, "connect failed in mode $mode (silent=$silent)", error)
                    if (silent) return@runOnUiThread
                    // adbd rejected our key: the pairing is really gone
                    if (error is AdbAuthenticationFailedException) {
                        PairingState(this).isPaired = false
                        paired.value = false
                    }
                    when (mode) {
                        ConnectionMode.PAIRING -> {
                            needsPairing.value = true
                            toast(R.string.adb_connect_failed_fallback)
                        }
                        ConnectionMode.TCP -> toast(R.string.adb_tcp_connect_failed)
                        ConnectionMode.SHIZUKU -> toast(R.string.shizuku_connect_failed)
                    }
                }
            }
        }, "adb-direct-connect").start()
    }

    /**
     * What can actually run on this device right now. Single source of truth for card + picker.
     *
     * The picker lists vulnerabilities (CVE ids), not devices or payload variants: the payload for
     * a given CVE is resolved from the device here, so one row covers every build of that bug.
     */
    private data class Availability(
        val profile: Boolean,
        val pageCache: String?,
        val gpuMatched: Boolean,
        val gpuPayload: GpuRunner.Payload?,
        val pctMatched: Boolean,
        val pctPayload: PctRunner.Payload?,
    )

    private fun availability(info: DeviceInfo?): Availability {
        // No `connected.value` read here: this runs off the main thread too, where snapshot reads
        // can be stale. A non-null DeviceInfo already means the device answered.
        if (info == null) return Availability(false, null, false, null, false, null)
        val profile = RootExploit.isPayloadPresent(this) && RootExploit.hasProfile(this, info.kernel)
        // The page cache engine is refused below Android 12L (API 32) even when a module image
        // matches the kernel, so it must not be offered there either - otherwise the row says
        // "Run" and the tap is silently dropped.
        val pageCache = if (DirtyFragRunner.isPayloadPresent(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2
        ) {
            DirtyFragRunner.describe(info.androidRelease, info.kernel)
        } else {
            null
        }
        // A Qualcomm GPU is necessary but not sufficient: whether the firmware is patched is only
        // known after a run, so the card reports Untested until ExploitMemory has an answer.
        val gpuMatched = GpuRunner.isSupported(info)
        val gpuPayload = GpuRunner.payloadFor(info)
            ?.takeIf { GpuRunner.isPayloadPresent(this, it) }
        // Same rule for CVE-2026-64560: the device family only says the port is worth trying, the
        // payload's own `--preflight` decides whether this build is the ported one.
        val pctMatched = PctRunner.isSupported(info)
        val pctPayload = PctRunner.payloadFor(info)
            ?.takeIf { PctRunner.isPayloadPresent(this, it) }
        return Availability(profile, pageCache, gpuMatched, gpuPayload, pctMatched, pctPayload)
    }

    private fun resolveEngine(info: DeviceInfo): Pair<String?, SupportState> {
        val available = availability(info)
        if (available.profile) {
            return getString(R.string.engine_kernel_profile) to SupportState.Supported
        }
        // A matched device build is more specific than "this SoC has a GPU", so it comes first.
        if (available.pctPayload != null) {
            val state = when (ExploitMemory.result(this, info.kernel, ExploitMemory.ENGINE_PCT)) {
                true -> SupportState.Supported
                false -> SupportState.Unsupported
                null -> SupportState.Candidate
            }
            return PctRunner.describe(info) to state
        }
        if (available.gpuPayload != null) {
            val state = when (ExploitMemory.result(this, info.kernel, ExploitMemory.ENGINE_GPU)) {
                true -> SupportState.Supported
                false -> SupportState.Unsupported
                null -> SupportState.Candidate
            }
            return GpuRunner.describe(info) to state
        }
        val label = available.pageCache ?: return null to SupportState.Unsupported
        // Without a profile this is only "worth trying": a run is the only way to know.
        val state = when (ExploitMemory.result(this, info.kernel)) {
            true -> SupportState.Supported
            false -> SupportState.Unsupported
            null -> SupportState.Candidate
        }
        return label to state
    }

    /** Pulls the device details over ADB, replacing the locally read placeholder. */
    private fun loadDeviceInfo() {
        if (!connected.value) return
        Thread({
            val info = runCatching { DeviceInfo.fromAdb(currentShell()) }
                .onFailure { Log.w(TAG, "cannot read device info over adb", it) }
                .getOrNull()
            if (info != null) {
                Log.i(TAG, "device info from adb: $info")
                // Decide on the main thread: snapshot reads on another thread can be stale.
                runOnUiThread {
                    val (engine, state) = resolveEngine(info)
                    Log.i(TAG, "availability: profile=${availability(info).profile} " +
                        "pageCache=${availability(info).pageCache}")
                    deviceInfo.value = info
                    engineLabel.value = engine
                    supportState.value = state
                }
                val ksud = KsuManager.resolve(this, currentShell()) { line ->
                    Log.i(TAG, line)
                }
                runOnUiThread { ksudTarget.value = ksud }
            }
        }, "adb-device-info").start()
    }

    private fun onPrimaryAction() {
        when {
            connecting.value -> Unit // already busy
            connectionMode.value != ConnectionMode.PAIRING -> connect()
            pairingRunning.value -> stopPairing()
            else -> requestStart()
        }
    }

    /**
     * Runs temporary root: the payload is pushed to /data/local/tmp over ADB and executed as the
     * `shell` user, with its output mirrored live in the log card.
     */
    /** Exploit tapped: let the user pick a vulnerability first. */
    private fun openEnginePicker() {
        if (exploiting.value) return
        // The button is hidden until adbd is connected; this is the second line of defence.
        if (!connected.value) {
            toast(R.string.exploit_needs_adb)
            return
        }
        // All four CVEs are always listed; each row explains whether it can run.
        exploitChoices.value = buildChoices(deviceInfo.value)
        pickerVisible.value = true
    }

    private fun buildChoices(info: DeviceInfo?): List<ExploitChoice> {
        val kernel = info?.kernel.orEmpty()
        val available = availability(info)
        val manager = ksudTarget.value
        val noConnection = info == null
        val handoff = if (manager == null) {
            "root 命令通道"
        } else {
            "将启动 ${manager.packageName}"
        }

        return listOf(
            ExploitChoice(
                id = ENGINE_CVE_43499,
                title = "CVE-2026-43499",
                detail = when {
                    available.profile -> "适配 $kernel 的内核配置"
                    noConnection -> "请先连接 adb"
                    !RootExploit.isPayloadPresent(this) -> "此 ABI 未打包对应载荷"
                    else -> "没有适配 $kernel 的配置"
                },
                enabled = available.profile,
            ),
            ExploitChoice(
                id = ENGINE_CVE_64560,
                title = "CVE-2026-64560",
                detail = when {
                    noConnection -> "请先连接 adb"
                    !available.pctMatched -> "此设备没有移植版本"
                    available.pctPayload == null -> "此 ABI 未打包对应载荷"
                    else -> "竞态利用，最多 32 次尝试，SELinux 保持关闭，$handoff"
                },
                enabled = available.pctPayload != null,
            ),
            ExploitChoice(
                id = ENGINE_CVE_21479,
                title = "CVE-2025-21479",
                detail = when {
                    noConnection -> "请先连接 adb"
                    !available.gpuMatched -> "GPU 不支持"
                    available.gpuPayload == null -> "此 ABI 未打包对应载荷"
                    else -> "SELinux 保持关闭，$handoff"
                },
                enabled = available.gpuPayload != null,
            ),
            ExploitChoice(
                id = ENGINE_CVE_43284,
                title = "CVE-2026-43284",
                detail = when {
                    noConnection -> "请先连接 adb"
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 ->
                        "需要 Android 12L（API 32）或更高版本"
                    available.pageCache == null -> "内核不在模块镜像的覆盖范围内"
                    else -> "${available.pageCache}, $handoff"
                },
                enabled = available.pageCache != null,
            ),
        )
    }

    private fun startExploit(engineId: String) {
        pickerVisible.value = false
        if (exploiting.value) return
        // The payload is pushed over ADB, so do nothing without a connection.
        if (!connected.value) {
            toast(R.string.exploit_needs_adb)
            return
        }
        val info = deviceInfo.value ?: return
        // The payload is resolved from the device, not from the row: one row per CVE.
        val gpuPayload = if (engineId == ENGINE_CVE_21479) {
            GpuRunner.payloadFor(info)?.takeIf { GpuRunner.isPayloadPresent(this, it) }
        } else {
            null
        }
        val pctPayload = if (engineId == ENGINE_CVE_64560) {
            PctRunner.payloadFor(info)?.takeIf { PctRunner.isPayloadPresent(this, it) }
        } else {
            null
        }
        // CVE-2026-43284 hands off to a manager when one is installed, otherwise it keeps a root
        // command channel; the page cache engine itself does not care which. Which one applies is
        // decided after a fresh resolve inside the worker, not from the cached value.
        when (engineId) {
            ENGINE_CVE_43499 ->
                if (!RootExploit.hasProfile(this, info.kernel)) return
            ENGINE_CVE_64560 -> if (pctPayload == null) return
            ENGINE_CVE_21479 -> if (gpuPayload == null) return
            else -> if (!DirtyFragRunner.isSupported(info.androidRelease, info.kernel)) {
                toast(
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
                        R.string.exploit_needs_android12l
                    } else {
                        R.string.exploit_unsupported_kernel
                    }
                )
                return
            }
        }

        exploiting.value = true
        exploitLog.value = emptyList()
        exploitStatus.value = null
        exploitProgress.value = 0f
        commandMode.value = false
        // The log is its own tab: show it while the payload runs.
        selectedTab.value = 1
        Thread({
            // Resolving ksud scans /data/app over ADB: done once on this worker and shared by all
            // four engines, which also puts the scan itself in the log.
            val outcome = runCatching {
                val ksud = KsuManager.resolve(this, currentShell(), ::logLine)
                    ?.takeIf { it.packageName.isNotEmpty() }
                when {
                    engineId == ENGINE_CVE_43499 -> runProfileEngine(info, ksud)
                    pctPayload != null -> runPctEngine(info, pctPayload, ksud)
                    gpuPayload != null -> runGpuEngine(info, gpuPayload, ksud)
                    else -> runPageCacheEngine(
                        info = info,
                        ksud = ksud,
                        mode = if (ksud != null) {
                            DirtyFragRunner.Mode.KERNEL_SU
                        } else {
                            DirtyFragRunner.Mode.ROOT_COMMAND
                        },
                    )
                }
            }
            runOnUiThread {
                exploiting.value = false
                exploitProgress.value = 1f
                exploitStatus.value = outcome.getOrElse { error ->
                    Log.w(TAG, "exploit failed", error)
                    error.message ?: error.javaClass.simpleName
                }
            }
        }, "root-exploit").start()
    }

    private fun reportProgress(value: Float) {
        runOnUiThread { exploitProgress.value = value }
    }

    private fun logLine(line: String) {
        runOnUiThread { exploitLog.value = (exploitLog.value + line).takeLast(300) }
    }

    /** CVE-2026-43499: the payload picks its profile by `uname -r` and late-loads ksud itself. */
    private fun runProfileEngine(info: DeviceInfo, ksud: KsuManager.Target?): String {
        val result = RootExploit.run(
            context = this,
            shell = currentShell(),
            release = info.kernel,
            ksudPath = ksud?.ksudPath,
            ksudPackage = ksud?.packageName,
            onLine = ::logLine,
            onProgress = ::reportProgress,
        )
        ExploitMemory.record(this, info.kernel, result.kernelSuLoaded)
        return when {
            result.kernelSuLoaded -> {
                // The payload loads the module itself; the app only has to bring the manager up.
                val opened = KsuManager.launchWithRetry(
                    context = this,
                    shell = currentShell(),
                    packageName = ksud?.packageName.orEmpty(),
                    log = ::logLine,
                )
                getString(if (opened) R.string.exploit_ksu_opened else R.string.exploit_ksu_manual)
            }
            result.exitCode == 0 -> getString(R.string.exploit_finished)
            else -> getString(R.string.exploit_exit_code, result.exitCode)
        }
    }

    /**
     * CVE-2025-21479: the payload runs as `shell` over the ADB channel and re-execs the staged
     * `bootstrap` as a child once root is held; bootstrap then late-loads ksud or starts the root
     * command channel according to its preferences.
     */
    private fun runGpuEngine(
        info: DeviceInfo,
        payload: GpuRunner.Payload,
        ksud: KsuManager.Target?,
    ): String {
        val result = GpuRunner.run(
            context = this,
            shell = currentShell(),
            payload = payload,
            suManager = ksud,
            onLine = ::logLine,
            onProgress = ::reportProgress,
        )
        ExploitMemory.record(this, info.kernel, result.handoff.rooted, ExploitMemory.ENGINE_GPU)
        return reportHandoff(ksud, result.handoff, result.exitCode)
    }

    /**
     * CVE-2026-64560: the payload runs `--preflight` first to confirm the fingerprint and kernel
     * really are the ported build, then the race. Once root is held it hands the root child to the
     * staged bootstrap, same as the other engines.
     */
    private fun runPctEngine(
        info: DeviceInfo,
        payload: PctRunner.Payload,
        ksud: KsuManager.Target?,
    ): String {
        val result = PctRunner.run(
            context = this,
            shell = currentShell(),
            payload = payload,
            suManager = ksud,
            onLine = ::logLine,
            onProgress = ::reportProgress,
        )
        // A refusal before the race says nothing about the vulnerability, so it is not remembered.
        if (!result.attempted) {
            return if (result.buildMismatch) {
                getString(R.string.exploit_profile_gate_failed)
            } else {
                getString(R.string.exploit_exit_code, result.exitCode)
            }
        }
        ExploitMemory.record(this, info.kernel, result.handoff.rooted, ExploitMemory.ENGINE_PCT)
        return reportHandoff(ksud, result.handoff, result.exitCode)
    }

    /** CVE-2026-43284: the module is loaded by the kernel; bootstrap and ksud are staged over ADB. */
    private fun runPageCacheEngine(
        info: DeviceInfo,
        ksud: KsuManager.Target?,
        mode: DirtyFragRunner.Mode,
    ): String {
        val result = DirtyFragRunner.run(
            context = this,
            shell = currentShell(),
            androidRelease = info.androidRelease,
            kernelRelease = info.kernel,
            suManager = ksud,
            mode = mode,
            onLine = ::logLine,
            onProgress = ::reportProgress,
        )
        ExploitMemory.record(this, info.kernel, result.handoff.rooted)
        return reportHandoff(ksud, result.handoff, result.exitCode)
    }

    /**
     * Turns what `bootstrap` started into the status line and, when KernelSU is up, opens the
     * manager. A root that exists but has no manager (or whose late-load failed) is reported as
     * the command channel instead of being thrown away as "not vulnerable".
     */
    private fun reportHandoff(
        ksud: KsuManager.Target?,
        handoff: RootHandoff.Result,
        exitCode: Int,
    ): String {
        logLine("<i> ${handoff.detail}")
        return when (handoff.state) {
            RootHandoff.State.KERNEL_SU -> {
                val opened = KsuManager.launchWithRetry(
                    context = this,
                    shell = currentShell(),
                    packageName = ksud?.packageName.orEmpty(),
                    log = ::logLine,
                )
                getString(if (opened) R.string.exploit_ksu_opened else R.string.exploit_ksu_manual)
            }
            RootHandoff.State.COMMAND -> {
                runOnUiThread { commandMode.value = true }
                getString(R.string.exploit_root_ready)
            }
            RootHandoff.State.NONE ->
                if (exitCode == 0) {
                    getString(R.string.exploit_finished)
                } else {
                    getString(R.string.exploit_exit_code, exitCode)
                }
        }
    }

    /** Runs one command through the root channel and mirrors its output while it runs. */
    private fun runRootCommand(command: String) {
        logLine("$ $command")
        Thread({
            runCatching {
                RootShell.run(currentShell(), command) { line -> logLine(line) }
            }.onFailure { error -> logLine("<e> ${error.message}") }
        }, "root-shell").start()
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
        refreshHandler.removeCallbacks(refreshTick)
        refreshState()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
