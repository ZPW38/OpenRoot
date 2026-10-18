package com.hexo141.liquidglass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexo141.liquidglass.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 主界面：左上角一颗液态玻璃胶囊按钮。
 *
 * 玻璃效果需要"被折射的背景"，所以整屏都是一个 ComposeView：
 * 背景层（渐变 + `layerBackdrop`）负责提供折射源，按钮叠在上面。
 */
@Composable
fun PairingScreen(
    running: Boolean,
    onToggle: () -> Unit,
    backgroundColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberLayerBackdrop()
    val dark = isSystemInDarkTheme()

    Box(modifier.fillMaxSize()) {
        // 折射源：顶部一层主色渐变，往下过渡回主题背景色
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = if (dark) 0.45f else 0.35f),
                            backgroundColor
                        )
                    )
                )
        )

        LiquidGlassButton(
            onClick = onToggle,
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp)
        ) {
            BasicText(
                text = stringResource(
                    if (running) R.string.adb_pairing_stop_button else R.string.adb_pairing_start
                ),
                style = TextStyle(
                    color = if (dark) Color(0xFFEDE6F4) else Color(0xFF241E30),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}
