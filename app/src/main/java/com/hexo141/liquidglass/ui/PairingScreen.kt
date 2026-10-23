package com.hexo141.liquidglass.ui

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexo141.liquidglass.R
import com.hexo141.liquidglass.device.DeviceInfo
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 主界面：整屏壁纸 + 设备信息玻璃卡片 + 居中的液态玻璃按钮。
 *
 * 壁纸同时是玻璃的折射源（和 E:\AndroidLiquidGlass 目录示例用照片一样），
 * 所以整屏是一个 ComposeView：壁纸走 `layerBackdrop`，按钮叠在上面做折射。
 *
 * 配对成功后 adbd 已经存了我们的公钥，配对按钮就完全收起（[paired] 为 true 且没有
 * [needsPairing]），主界面只负责直连和展示设备信息；只有直连失败时按钮才会重新出现。
 * 连接建立后下方的 Exploit 按钮负责跑临时 root 载荷。
 */
@Composable
fun PairingScreen(
    running: Boolean,
    paired: Boolean,
    connecting: Boolean,
    connected: Boolean,
    needsPairing: Boolean,
    supportState: SupportState,
    engineLabel: String?,
    deviceInfo: DeviceInfo?,
    exploiting: Boolean,
    exploitLog: List<String>,
    exploitStatus: String?,
    exploitProgress: Float,
    exploitChoices: List<ExploitChoice>,
    pickerVisible: Boolean,
    commandMode: Boolean,
    onToggle: () -> Unit,
    onExploit: () -> Unit,
    onPickEngine: (String) -> Unit,
    onDismissPicker: () -> Unit,
    onRunCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberLayerBackdrop()

    // 旋转/分屏后配置变了要重新取图，否则横屏还是竖屏那张壁纸
    val configuration = LocalConfiguration.current

    // API < 31 没有 RenderEffect，折射不生效；补一层半透明底，避免按钮在壁纸上几乎看不见
    val glassSurfaceColor =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Color.Black.copy(alpha = 0.3f)
        else Color.Unspecified

    // 已配对后配对按钮一律收起；只有直连失败（needsPairing）才重新露出来
    val showPairingButton = !paired || needsPairing

    val statusText = when {
        connecting -> "Connecting…"
        connected -> "Connected"
        paired -> "Waiting for adbd"
        else -> "Not paired"
    }

    Box(modifier.fillMaxSize()) {
        key(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
            Image(
                painter = painterResource(R.drawable.bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxSize()
                // 状态栏之后留出足够的呼吸空间，标题和卡片都往下走一点
                .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = "OpenRoot",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.45f),
                            offset = Offset(0f, 2f),
                            blurRadius = 8f
                        )
                    )
                )
                Spacer(Modifier.weight(1f))
                val context = LocalContext.current
                GitHubIcon(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                )
            }
            Spacer(Modifier.height(14.dp))
            DeviceStatusCard(
                backdrop = backdrop,
                state = supportState,
                release = deviceInfo?.kernel.orEmpty(),
                engineLabel = engineLabel,
                surfaceColor = glassSurfaceColor
            )
            // 没连上就没有可信的设备信息，整块卡片不显示
            if (connected && deviceInfo != null) {
                Spacer(Modifier.height(10.dp))
                DeviceInfoCards(
                    backdrop = backdrop,
                    info = deviceInfo,
                    statusText = statusText,
                    surfaceColor = glassSurfaceColor
                )
            }
            // 利用按钮一直显示：不支持时点开也能看到有哪些方案、以及为什么不可用
            Spacer(Modifier.height(14.dp))
            LiquidGlassButton(
                onClick = onExploit,
                backdrop = backdrop,
                tint = ExploitBlue,
                surfaceColor = glassSurfaceColor,
                height = 46.dp,
                horizontalPadding = 22.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicText(
                    text = stringResource(
                        if (exploiting) R.string.exploit_running else R.string.exploit_button
                    ),
                    style = buttonTextStyle()
                )
            }
            // 日志拿走剩下的高度：区域固定、内容在卡片内部滚动，永远顶不出屏幕
            if (exploitLog.isNotEmpty() || exploitStatus != null) {
                Spacer(Modifier.height(10.dp))
                ExploitLogCard(
                    backdrop = backdrop,
                    lines = exploitLog,
                    status = exploitStatus,
                    surfaceColor = glassSurfaceColor,
                    commandInput = commandMode,
                    onCommand = onRunCommand,
                    progress = exploitProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showPairingButton) {
                LiquidGlassButton(
                    onClick = onToggle,
                    backdrop = backdrop,
                    surfaceColor = glassSurfaceColor,
                    height = 50.dp,
                    horizontalPadding = 22.dp
                ) {
                    BasicText(
                        text = stringResource(
                            if (running) R.string.adb_pairing_stop_button else R.string.adb_pairing_start
                        ),
                        style = buttonTextStyle()
                    )
                }
            }
        }

        EnginePicker(
            visible = pickerVisible,
            backdrop = backdrop,
            choices = exploitChoices,
            onPick = onPickEngine,
            onDismiss = onDismissPicker,
            surfaceColor = glassSurfaceColor
        )

    }
}

@Composable
private fun buttonTextStyle() = TextStyle(
    color = Color.White,
    fontSize = 15.sp,
    // 壁纸明暗不定，加一层淡阴影保证可读
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.45f),
        offset = Offset(0f, 1f),
        blurRadius = 6f
    )
)

/** 利用按钮的强调色。 */
private val ExploitBlue = Color(0xFF0A84FF)

/** Repository link shown next to the title. */
private const val PROJECT_URL = "https://github.com/hexo141/OpenRoot"

/** GitHub mark, drawn straight from the source path so no extra drawable is needed. */
@Composable
private fun GitHubIcon(modifier: Modifier = Modifier) {
    val path = remember {
        PathParser().parsePathString(GITHUB_PATH).toPath().apply {
            translate(Offset(GITHUB_OFFSET_X, GITHUB_OFFSET_Y))
        }
    }
    Canvas(modifier) {
        withTransform({ scale(size.width / 20f, size.height / 20f, Offset.Zero) }) {
            drawPath(path, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

private const val GITHUB_OFFSET_X = -84f
private const val GITHUB_OFFSET_Y = -7399f

private const val GITHUB_PATH =
    "M94,7399 C99.523,7399 104,7403.59 104,7409.253 C104,7413.782 101.138,7417.624 " +
        "97.167,7418.981 C96.66,7419.082 96.48,7418.762 96.48,7418.489 C96.48,7418.151 " +
        "96.492,7417.047 96.492,7415.675 C96.492,7414.719 96.172,7414.095 95.813,7413.777 " +
        "C98.04,7413.523 100.38,7412.656 100.38,7408.718 C100.38,7407.598 99.992,7406.684 " +
        "99.35,7405.966 C99.454,7405.707 99.797,7404.664 99.252,7403.252 C99.252,7403.252 " +
        "98.414,7402.977 96.505,7404.303 C95.706,7404.076 94.85,7403.962 94,7403.958 " +
        "C93.15,7403.962 92.295,7404.076 91.497,7404.303 C89.586,7402.977 88.746,7403.252 " +
        "88.746,7403.252 C88.203,7404.664 88.546,7405.707 88.649,7405.966 C88.01,7406.684 " +
        "87.619,7407.598 87.619,7408.718 C87.619,7412.646 89.954,7413.526 92.175,7413.785 " +
        "C91.889,7414.041 91.63,7414.493 91.54,7415.156 C90.97,7415.418 89.522,7415.871 " +
        "88.63,7414.304 C88.63,7414.304 88.101,7413.319 87.097,7413.247 C87.097,7413.247 " +
        "86.122,7413.234 87.029,7413.87 C87.029,7413.87 87.684,7414.185 88.139,7415.37 " +
        "C88.139,7415.37 88.726,7417.2 91.508,7416.58 C91.513,7417.437 91.522,7418.245 " +
        "91.522,7418.489 C91.522,7418.76 91.338,7419.077 90.839,7418.982 C86.865,7417.627 " +
        "84,7413.783 84,7409.253 C84,7403.59 88.478,7399 94,7399"
