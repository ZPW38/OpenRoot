package com.hexo141.liquidglass.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Slides [second] in over [first] while fading both, used when the exploit log opens or closes.
 *
 * The two screens paint the same wallpaper, so only the cards move: the outgoing screen drifts left
 * and fades out while the incoming one comes in from the right. Both stay composed for the length
 * of the animation, then the hidden one leaves the tree.
 */
@Composable
fun PageSlide(
    showSecond: Boolean,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (showSecond) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "page-slide"
    )

    Box(Modifier.fillMaxSize()) {
        if (progress < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f - progress
                        translationX = -progress * 48.dp.toPx()
                    }
            ) {
                first()
            }
        }
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = progress
                        translationX = (1f - progress) * 48.dp.toPx()
                    }
            ) {
                second()
            }
        }
    }
}
