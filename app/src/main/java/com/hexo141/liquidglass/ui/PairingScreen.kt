package com.hexo141.liquidglass.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
    supported: Boolean,
    deviceInfo: DeviceInfo?,
    exploiting: Boolean,
    exploitLog: List<String>,
    exploitStatus: String?,
    onToggle: () -> Unit,
    onExploit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberLayerBackdrop()

    // API < 31 没有 RenderEffect，折射不生效；补一层半透明底，避免按钮在壁纸上几乎看不见
    val glassSurfaceColor =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Color.Black.copy(alpha = 0.3f)
        else Color.Unspecified

    val showPairingButton = running || !paired || needsPairing

    val supportState = when {
        !connected -> SupportState.NotConnected
        supported -> SupportState.Supported
        else -> SupportState.Unsupported
    }

    val statusText = when {
        connecting -> "Connecting…"
        connected -> "Connected"
        paired -> "Waiting for adbd"
        else -> "Not paired"
    }

    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                // 状态栏之后留出足够的呼吸空间，标题和卡片都往下走一点
                .padding(start = 20.dp, end = 20.dp, top = 44.dp)
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
            Spacer(Modifier.height(14.dp))
            DeviceStatusCard(
                backdrop = backdrop,
                state = supportState,
                release = deviceInfo?.kernel.orEmpty(),
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
            // 利用按钮跟着设备信息走，不再悬浮在屏幕中间
            if (connected && supported) {
                Spacer(Modifier.height(14.dp))
                LiquidGlassButton(
                    onClick = onExploit,
                    backdrop = backdrop,
                    tint = ExploitBlue,
                    surfaceColor = glassSurfaceColor,
                    height = 46.dp,
                    horizontalPadding = 22.dp
                ) {
                    BasicText(
                        text = stringResource(
                            if (exploiting) R.string.exploit_running else R.string.exploit_button
                        ),
                        style = buttonTextStyle()
                    )
                }
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

        if (exploitLog.isNotEmpty() || exploitStatus != null) {
            ExploitLogCard(
                backdrop = backdrop,
                lines = exploitLog,
                status = exploitStatus,
                surfaceColor = glassSurfaceColor,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            )
        }
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
