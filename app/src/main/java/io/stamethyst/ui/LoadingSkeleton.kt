package io.stamethyst.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private const val DefaultShimmerDurationMillis = 1180
private const val SoftStartShimmerInitialProgress = -0.18f
private const val SoftStartShimmerFullStrengthProgress = 0.18f
private const val SoftStartShimmerDurationMillis = 1400

internal data class LoadingSkeletonStyle(
    val baseColor: Color,
    val softHighlightColor: Color,
    val highlightColor: Color,
    val shimmerProgress: Float,
)

@Composable
internal fun rememberLoadingSkeletonStyle(
    label: String,
    softenInitialShimmer: Boolean = label.startsWith("workshop_"),
): LoadingSkeletonStyle {
    val transition = rememberInfiniteTransition(label = label)
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_pulse",
    )
    val shimmerInitialProgress = if (softenInitialShimmer) SoftStartShimmerInitialProgress else 0f
    val shimmerProgress by transition.animateFloat(
        initialValue = shimmerInitialProgress,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (softenInitialShimmer) {
                    SoftStartShimmerDurationMillis
                } else {
                    DefaultShimmerDurationMillis
                },
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "${label}_shimmer",
    )
    val skeletonColor = MaterialTheme.colorScheme.onSurface
    val baseAlpha = 0.065f + pulse * 0.035f
    val softHighlightAlpha = 0.11f + pulse * 0.045f
    val highlightAlpha = 0.20f + pulse * 0.055f
    val shimmerStrength = if (softenInitialShimmer) {
        shimmerProgress.toSoftStartShimmerStrength()
    } else {
        1f
    }
    return LoadingSkeletonStyle(
        baseColor = skeletonColor.copy(alpha = baseAlpha),
        softHighlightColor = skeletonColor.copy(
            alpha = lerpAlpha(baseAlpha, softHighlightAlpha, shimmerStrength),
        ),
        highlightColor = skeletonColor.copy(
            alpha = lerpAlpha(baseAlpha, highlightAlpha, shimmerStrength),
        ),
        shimmerProgress = shimmerProgress,
    )
}

private fun Float.toSoftStartShimmerStrength(): Float {
    val progress = (this / SoftStartShimmerFullStrengthProgress).coerceIn(0f, 1f)
    return progress * progress * (3f - 2f * progress)
}

private fun lerpAlpha(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

@Composable
internal fun LoadingSkeletonBlock(
    modifier: Modifier = Modifier,
    style: LoadingSkeletonStyle,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .drawWithCache {
                val travelDistance = size.width + size.height
                val shimmerWidth = travelDistance * 0.42f
                val startX = -shimmerWidth + style.shimmerProgress * (size.width + shimmerWidth * 2f)
                val brush = Brush.linearGradient(
                    colors = listOf(
                        style.baseColor,
                        style.softHighlightColor,
                        style.highlightColor,
                        style.softHighlightColor,
                        style.baseColor,
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(startX + shimmerWidth, size.height),
                )
                onDrawBehind {
                    drawRect(brush = brush)
                }
            },
    )
}
