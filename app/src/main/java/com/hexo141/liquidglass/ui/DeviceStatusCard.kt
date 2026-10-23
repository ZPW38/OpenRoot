package com.hexo141.liquidglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle

/** Whether the connected kernel has a bundled profile. */
enum class SupportState {
    NotConnected,
    Supported,
    Unsupported,
}

/**
 * Status card for the connected device: a large state word, the kernel release and one line of
 * detail, with a matching badge on the right. Same liquid glass material as the rest of the
 * screen, tinted by [state].
 */
@Composable
fun DeviceStatusCard(
    backdrop: Backdrop,
    state: SupportState,
    release: String,
    modifier: Modifier = Modifier,
    surfaceColor: Color = Color.Unspecified,
) {
    val accent = when (state) {
        SupportState.Supported -> Color(0xFF4ADE80)
        SupportState.Unsupported -> Color(0xFFF87171)
        SupportState.NotConnected -> Color.White.copy(alpha = 0.65f)
    }
    val title = when (state) {
        SupportState.Supported -> "Supported"
        SupportState.Unsupported -> "Unsupported"
        SupportState.NotConnected -> "Not connected"
    }
    val subtitle = when (state) {
        SupportState.NotConnected -> "No adbd session yet"
        else -> release.ifEmpty { "Unknown kernel" }
    }
    val detail = when (state) {
        SupportState.Supported -> "Kernel profile and payload are ready"
        SupportState.Unsupported -> "No profile bundled for this kernel"
        SupportState.NotConnected -> "Connect to check device support"
    }

    Box(
        modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(24.dp) },
                effects = {
                    vibrancy()
                    blur(4f.dp.toPx())
                    lens(10f.dp.toPx(), 22f.dp.toPx())
                },
                onDrawSurface = {
                    if (surfaceColor.isSpecified) drawRect(surfaceColor)
                    // Soft tinted wash so the state reads at a glance, then a light sheen.
                    drawRect(accent.copy(alpha = 0.10f))
                    drawRect(Color.Black.copy(alpha = 0.12f))
                    drawRect(Color.White.copy(alpha = 0.06f))
                }
            )
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)
    ) {
        Column(modifier = Modifier.align(Alignment.CenterStart)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = statusShadow
                )
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = subtitle,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    shadow = statusShadow
                )
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = detail,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    shadow = statusShadow
                )
            )
        }

        StatusBadge(
            state = state,
            accent = accent,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(66.dp)
        )
    }
}

@Composable
private fun StatusBadge(state: SupportState, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val inset = stroke / 2f
        val diameter = size.minDimension - stroke
        drawArc(
            color = accent.copy(alpha = 0.35f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke)
        )
        // An arc over the ring keeps the badge from looking flat.
        drawArc(
            color = accent,
            startAngle = 200f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        val w = size.width
        val h = size.height
        when (state) {
            SupportState.Supported -> {
                val path = Path().apply {
                    moveTo(w * 0.30f, h * 0.52f)
                    lineTo(w * 0.44f, h * 0.66f)
                    lineTo(w * 0.72f, h * 0.36f)
                }
                drawPath(
                    path = path,
                    color = accent,
                    style = Stroke(width = stroke * 1.1f, cap = StrokeCap.Round)
                )
            }

            SupportState.Unsupported -> {
                val path = Path().apply {
                    moveTo(w * 0.36f, h * 0.36f)
                    lineTo(w * 0.64f, h * 0.64f)
                    moveTo(w * 0.64f, h * 0.36f)
                    lineTo(w * 0.36f, h * 0.64f)
                }
                drawPath(
                    path = path,
                    color = accent,
                    style = Stroke(width = stroke * 1.1f, cap = StrokeCap.Round)
                )
            }

            SupportState.NotConnected -> {
                drawLine(
                    color = accent,
                    start = Offset(w * 0.36f, h * 0.5f),
                    end = Offset(w * 0.64f, h * 0.5f),
                    strokeWidth = stroke * 1.1f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private val statusShadow = Shadow(
    color = Color.Black.copy(alpha = 0.45f),
    offset = Offset(0f, 1f),
    blurRadius = 6f
)
