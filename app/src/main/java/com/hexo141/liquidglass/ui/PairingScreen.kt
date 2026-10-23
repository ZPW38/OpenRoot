package com.hexo141.liquidglass.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexo141.liquidglass.R
import com.hexo141.liquidglass.device.DeviceInfo
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 主界面：整屏壁纸 + 设备信息玻璃卡片 + 居中的液态玻璃胶囊按钮。
 *
 * 壁纸同时是玻璃的折射源（和 E:\AndroidLiquidGlass 目录示例用照片一样），
 * 所以整屏是一个 ComposeView：壁纸走 `layerBackdrop`，按钮叠在上面做折射。
 *
 * 配对成功后 adbd 已经存了我们的公钥，配对按钮就完全收起（[paired] 为 true 且没有
 * [needsPairing]），主界面只负责直连和展示设备信息；只有直连失败时按钮才会重新出现。
 */
@Composable
fun PairingScreen(
    running: Boolean,
    paired: Boolean,
    connecting: Boolean,
    connected: Boolean,
    needsPairing: Boolean,
    deviceInfo: DeviceInfo?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberLayerBackdrop()

    // API < 31 没有 RenderEffect，折射不生效；补一层半透明底，避免按钮在壁纸上几乎看不见
    val glassSurfaceColor =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Color.Black.copy(alpha = 0.3f)
        else Color.Unspecified

    val showPairingButton = running || !paired || needsPairing

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

        DeviceInfoCards(
            backdrop = backdrop,
            info = deviceInfo,
            statusText = statusText,
            surfaceColor = glassSurfaceColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // 顶部留出状态栏之后的呼吸空间，卡片不要贴着信息栏
                .padding(start = 20.dp, end = 20.dp, top = 20.dp)
        )

        if (showPairingButton) {
            LiquidGlassButton(
                onClick = onToggle,
                backdrop = backdrop,
                surfaceColor = glassSurfaceColor,
                modifier = Modifier.align(Alignment.Center)
            ) {
                BasicText(
                    text = stringResource(
                        if (running) R.string.adb_pairing_stop_button else R.string.adb_pairing_start
                    ),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp,
                        // 壁纸明暗不定，加一层淡阴影保证可读
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.45f),
                            offset = Offset(0f, 1f),
                            blurRadius = 6f
                        )
                    )
                )
            }
        }
    }
}
