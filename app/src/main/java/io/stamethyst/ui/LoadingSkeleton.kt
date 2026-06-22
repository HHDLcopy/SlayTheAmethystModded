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

internal data class LoadingSkeletonStyle(
    val baseColor: Color,
    val softHighlightColor: Color,
    val highlightColor: Color,
    val shimmerProgress: Float,
)

@Composable
internal fun rememberLoadingSkeletonStyle(label: String): LoadingSkeletonStyle {
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
    val shimmerProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1180),
            repeatMode = RepeatMode.Restart,
        ),
        label = "${label}_shimmer",
    )
    val skeletonColor = MaterialTheme.colorScheme.onSurface
    return LoadingSkeletonStyle(
        baseColor = skeletonColor.copy(alpha = 0.065f + pulse * 0.035f),
        softHighlightColor = skeletonColor.copy(alpha = 0.11f + pulse * 0.045f),
        highlightColor = skeletonColor.copy(alpha = 0.20f + pulse * 0.055f),
        shimmerProgress = shimmerProgress,
    )
}

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
