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
 * Cross-fades the consent gate into the main screen once both documents are accepted.
 *
 * Both screens paint the same wallpaper full-screen, so only the scrim and the cards move: the
 * gate drifts back and fades out while the main screen comes forward from 94% and slides up a
 * little. When the run is over the gate leaves the composition entirely, so its licence download
 * and scroll state are gone.
 *
 * The animation is driven by `animateFloatAsState` (animation-core, already used elsewhere) rather
 * than `AnimatedContent`, because the app deliberately does not depend on the `animation` artifact.
 */
@Composable
fun ConsentTransition(
    accepted: Boolean,
    consent: @Composable () -> Unit,
    main: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (accepted) 1f else 0f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "consent-to-main"
    )

    Box(Modifier.fillMaxSize()) {
        if (progress < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Fades out while receding, so the main screen reads as "in front".
                        alpha = 1f - progress
                        val scale = 1f + 0.06f * progress
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                consent()
            }
        }
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = progress
                        val scale = 0.94f + 0.06f * progress
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - progress) * 24.dp.toPx()
                    }
            ) {
                main()
            }
        }
    }
}
