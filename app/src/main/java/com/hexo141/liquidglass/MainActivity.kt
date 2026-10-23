package com.hexo141.liquidglass

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.hexo141.liquidglass.root.DirtyFragRunner
import com.hexo141.liquidglass.root.ExploitMemory
import com.hexo141.liquidglass.root.KsuManager
import com.hexo141.liquidglass.root.RootExploit
import com.hexo141.liquidglass.root.RootShell
import com.hexo141.liquidglass.ui.PairingScreen
import com.hexo141.liquidglass.ui.ExploitChoice
import com.hexo141.liquidglass.ui.SupportState

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"

        /** Engine ids used by the picker. */
        const val ENGINE_PROFILE = "profile"
        const val ENGINE_DIRTY_FRAG_KSU = "dirtyfrag-ksu"
        const val ENGINE_DIRTY_FRAG_CMD = "dirtyfrag-cmd"

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

    /** 设备支持状态（状态卡直接用它） */
    private val supportState = mutableStateOf(SupportState.NotConnected)

    /** 可用引擎的名称（内核 profile / DirtyFrag）；null 表示不支持 */
    private val engineLabel = mutableStateOf<String?>(null)

    /** 临时 root 载荷正在运行 */
    private val exploiting = mutableStateOf(false)

    /** 载荷的实时输出 */
    private val exploitLog = mutableStateOf<List<String>>(emptyList())

    /** 载荷结束后的结果行 */
    private val exploitStatus = mutableStateOf<String?>(null)

    /** 进度条：由载荷真实阶段驱动，0..1 */
    private val exploitProgress = mutableStateOf(0f)

    /** 引擎选择层是否显示 */
    private val pickerVisible = mutableStateOf(false)

    /** 当前设备可选的引擎 */
    private val exploitChoices = mutableStateOf<List<ExploitChoice>>(emptyList())

    /** 找到的 root 管理器（含 ksud 路径），没装则为 null */
    private val ksudTarget = mutableStateOf<KsuManager.Target?>(null)

    /** root 命令通道是否可用（bootstrap 已经起来） */
    private val commandMode = mutableStateOf(false)

    /** 每次进入前台只自动直连一次，避免失败后反复重试 */
    private var autoConnectTried = false

    private val refreshHandler = Handler(Looper.getMainLooper())

    /** 配对服务在后台跑的时候，每秒对齐一次状态（通知栏里配对成功也会立刻反映到页面）。 */
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

    /** 配对服务在后台把 isPaired 写成 true 时，主界面立即把配对按钮收起来。 */
    private val pairingStateListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PairingState.KEY_PAIRED) {
                val isPaired = PairingState(this).isPaired
                paired.value = isPaired
                if (isPaired) needsPairing.value = false
                // 服务在配对成功后停止，这里立刻对齐，别等下一次轮询
                pairingRunning.value = AdbPairingService.isRunning()
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
                supportState = supportState.value,
                engineLabel = engineLabel.value,
                deviceInfo = deviceInfo.value,
                exploiting = exploiting.value,
                exploitLog = exploitLog.value,
                exploitStatus = exploitStatus.value,
                exploitProgress = exploitProgress.value,
                exploitChoices = exploitChoices.value,
                pickerVisible = pickerVisible.value,
                onToggle = ::onPrimaryAction,
                commandMode = commandMode.value,
                onExploit = ::openEnginePicker,
                onPickEngine = ::startExploit,
                onDismissPicker = { pickerVisible.value = false },
                onRunCommand = ::runRootCommand
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
        refreshHandler.removeCallbacks(refreshTick)
        refreshHandler.postDelayed(refreshTick, 1000)
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
        connected.value = AdbConnectionManager.isConnected()
        if (!connected.value) {
            supportState.value = SupportState.NotConnected
            engineLabel.value = null
        }
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
                    supportState.value = SupportState.Checking
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

    /**
     * 引擎选择：有精确匹配的内核 profile 就用 profile 引擎，否则退到 page cache 引擎
     * （它只要求内核主次版本能对上随包的模块镜像）。
     */
    /** What can actually run on this device right now. Single source of truth for card + picker. */
    private data class Availability(val profile: Boolean, val pageCache: String?)

    private fun availability(info: DeviceInfo?): Availability {
        // No `connected.value` read here: this runs off the main thread too, where snapshot reads
        // can be stale. A non-null DeviceInfo already means the device answered.
        if (info == null) return Availability(false, null)
        val profile = RootExploit.isPayloadPresent(this) && RootExploit.hasProfile(this, info.kernel)
        val pageCache = if (DirtyFragRunner.isPayloadPresent(this)) {
            DirtyFragRunner.describe(info.androidRelease, info.kernel)
        } else {
            null
        }
        return Availability(profile, pageCache)
    }

    private fun resolveEngine(info: DeviceInfo): Pair<String?, SupportState> {
        val available = availability(info)
        if (available.profile) {
            return getString(R.string.engine_kernel_profile) to SupportState.Supported
        }
        val label = available.pageCache ?: return null to SupportState.Unsupported
        // 没有 profile 时只能"能试"，是否真的能提权要跑过才知道
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
            val info = runCatching { DeviceInfo.fromAdb(AdbConnectionManager.get(this)) }
                .onFailure { Log.w(TAG, "cannot read device info over adb", it) }
                .getOrNull()
            if (info != null) {
                Log.i(TAG, "device info from adb: $info")
                // 状态判定放在主线程做：快照状态在别的线程读到可能是旧值
                runOnUiThread {
                    val (engine, state) = resolveEngine(info)
                    Log.i(TAG, "availability: profile=${availability(info).profile} " +
                        "pageCache=${availability(info).pageCache}")
                    deviceInfo.value = info
                    engineLabel.value = engine
                    supportState.value = state
                }
                val ksud = KsuManager.resolve(this, AdbConnectionManager.get(this)) { line ->
                    Log.i(TAG, line)
                }
                runOnUiThread { ksudTarget.value = ksud }
            }
        }, "adb-device-info").start()
    }

    private fun onPrimaryAction() {
        when {
            pairingRunning.value -> stopPairing()
            connecting.value -> Unit // already busy
            else -> requestStart()
        }
    }

    /**
     * 跑临时 root：载荷通过 ADB 推到 /data/local/tmp 后以 shell 身份执行，
     * 输出实时回显在玻璃日志卡里。
     */
    /** 点击利用按钮：先让用户选引擎，再跑。 */
    private fun openEnginePicker() {
        if (exploiting.value) return
        // 三种方案永远都列出来，能不能用由每一行自己说明
        exploitChoices.value = buildChoices(deviceInfo.value)
        pickerVisible.value = true
    }

    private fun buildChoices(info: DeviceInfo?): List<ExploitChoice> {
        val kernel = info?.kernel.orEmpty()
        val available = availability(info)
        val manager = ksudTarget.value
        val noConnection = info == null

        return listOf(
            ExploitChoice(
                id = ENGINE_PROFILE,
                title = "GhostLock",
                detail = when {
                    available.profile -> "Kernel profile for $kernel"
                    noConnection -> "Connect to adb first"
                    !RootExploit.isPayloadPresent(this) -> "Payload not packaged for this ABI"
                    else -> "No profile for $kernel"
                },
                enabled = available.profile,
            ),
            ExploitChoice(
                id = ENGINE_DIRTY_FRAG_KSU,
                title = "DirtyFrag + KernelSU",
                detail = when {
                    noConnection -> "Connect to adb first"
                    available.pageCache == null -> "Kernel not covered by the module images"
                    manager == null -> "No root manager with libksud.so"
                    else -> "${available.pageCache} - starts ${manager.packageName}"
                },
                enabled = available.pageCache != null && manager != null,
            ),
            ExploitChoice(
                id = ENGINE_DIRTY_FRAG_CMD,
                title = "DirtyFrag + No-KSU mode",
                detail = when {
                    noConnection -> "Connect to adb first"
                    available.pageCache == null -> "Kernel not covered by the module images"
                    else -> "${available.pageCache} - root command, no manager"
                },
                enabled = available.pageCache != null,
            ),
        )
    }

    private fun startExploit(engineId: String) {
        pickerVisible.value = false
        if (exploiting.value) return
        val info = deviceInfo.value ?: return
        if (engineId == ENGINE_PROFILE && !RootExploit.hasProfile(this, info.kernel)) return
        if (engineId != ENGINE_PROFILE &&
            !DirtyFragRunner.isSupported(info.androidRelease, info.kernel)
        ) {
            return
        }

        exploiting.value = true
        exploitLog.value = emptyList()
        exploitStatus.value = null
        exploitProgress.value = 0f
        commandMode.value = false
        Thread({
            // ksud 解析要走 ADB 扫 /data/app，放在工作线程里做，顺便把命令打进日志
                val outcome = runCatching {
                if (engineId == ENGINE_PROFILE) {
                    val ksud = KsuManager.resolve(this, AdbConnectionManager.get(this), ::logLine)
                    runProfileEngine(info, ksud)
                } else {
                    runPageCacheEngine(
                        info = info,
                        ksud = ksudTarget.value,
                        mode = if (engineId == ENGINE_DIRTY_FRAG_KSU) {
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

    /** 内核 profile 引擎：载荷按 uname -r 选 profile，自己完成 ksud late-load。 */
    private fun runProfileEngine(info: DeviceInfo, ksud: KsuManager.Target?): String {
        val result = RootExploit.run(
            context = this,
            manager = AdbConnectionManager.get(this),
            release = info.kernel,
            ksudPath = ksud?.ksudPath,
            onLine = ::logLine,
            onProgress = ::reportProgress,
        )
        ExploitMemory.record(this, info.kernel, result.kernelSuLoaded)
        return when {
            result.kernelSuLoaded -> {
                val managerPackage = result.managerPackage ?: ksud?.packageName
                val opened = managerPackage != null && KsuManager.launchManager(this, managerPackage)
                getString(if (opened) R.string.exploit_ksu_opened else R.string.exploit_ksu_loaded)
            }
            result.exitCode == 0 -> getString(R.string.exploit_finished)
            else -> getString(R.string.exploit_exit_code, result.exitCode)
        }
    }

    /** page cache 引擎：模块由内核加载，bootstrap/ksud 由 ADB 通道提前放到 /data/local/tmp。 */
    private fun runPageCacheEngine(
        info: DeviceInfo,
        ksud: KsuManager.Target?,
        mode: DirtyFragRunner.Mode,
    ): String {
        val result = DirtyFragRunner.run(
            context = this,
            manager = AdbConnectionManager.get(this),
            androidRelease = info.androidRelease,
            kernelRelease = info.kernel,
            suManager = ksud,
            mode = mode,
            onLine = ::logLine,
            onProgress = ::reportProgress,
        )
        ExploitMemory.record(this, info.kernel, result.rooted)
        if (result.rooted && mode == DirtyFragRunner.Mode.ROOT_COMMAND) {
            runOnUiThread { commandMode.value = true }
            return getString(R.string.exploit_root_ready)
        }
        if (result.rooted) {
            val opened = ksud != null && KsuManager.launchManager(this, ksud.packageName)
            return getString(if (opened) R.string.exploit_ksu_opened else R.string.exploit_ksu_loaded)
        }
        return getString(R.string.exploit_exit_code, result.exitCode)
    }

    /** 在 root 命令通道里执行一条命令，输出边跑边回显。 */
    private fun runRootCommand(command: String) {
        logLine("$ $command")
        Thread({
            runCatching {
                RootShell.run(AdbConnectionManager.get(this), command) { line -> logLine(line) }
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
