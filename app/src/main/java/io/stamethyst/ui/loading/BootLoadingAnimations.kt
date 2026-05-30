package io.stamethyst.ui.loading

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.config.BootOverlayAnimation
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun BootLoadingAnimation(
    animation: BootOverlayAnimation,
    modifier: Modifier = Modifier
) {
    when (animation) {
        BootOverlayAnimation.INFINITY_ORBIT -> BootOrbitLoadingAnimation(modifier)
        BootOverlayAnimation.COMET -> BootCometLoadingAnimation(modifier)
        BootOverlayAnimation.WAVE -> BootWaveLoadingAnimation(modifier)
        BootOverlayAnimation.HALO -> BootHaloLoadingAnimation(modifier)
        BootOverlayAnimation.ELASTIC_DOTS -> BootElasticDotsLoadingAnimation(modifier)
        BootOverlayAnimation.SPIRAL -> BootSpiralLoadingAnimation(modifier)
        BootOverlayAnimation.PULSE_RINGS -> BootPulseRingsLoadingAnimation(modifier)
        BootOverlayAnimation.ORBITAL_ECLIPSE -> BootOrbitalEclipseLoadingAnimation(modifier)
        BootOverlayAnimation.RUNIC_GATE -> BootRunicGateLoadingAnimation(modifier)
        BootOverlayAnimation.CARD_SHUFFLE -> BootCardShuffleLoadingAnimation(modifier)
        BootOverlayAnimation.PRISM_SWEEP -> BootPrismSweepLoadingAnimation(modifier)
        BootOverlayAnimation.HELIX_LADDER -> BootHelixLadderLoadingAnimation(modifier)
        BootOverlayAnimation.LIQUID_ORB -> BootLiquidOrbLoadingAnimation(modifier)
        BootOverlayAnimation.SIGNAL_STACK -> BootSignalStackLoadingAnimation(modifier)
        BootOverlayAnimation.DIAMOND_FLOW -> BootDiamondFlowLoadingAnimation(modifier)
        BootOverlayAnimation.GRAVITY_WELL -> BootGravityWellLoadingAnimation(modifier)
    }
}

@Composable
internal fun BootAnimationPreviewGrid(
    selectedAnimation: BootOverlayAnimation,
    animationNames: Map<BootOverlayAnimation, String>,
    enabled: Boolean,
    onSelect: (BootOverlayAnimation) -> Unit,
    modifier: Modifier = Modifier,
    optionHeight: Dp = 118.dp,
) {
    val spacing = 10.dp
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        BootOverlayAnimation.entries.chunked(2).forEach { rowAnimations ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                rowAnimations.forEach { animation ->
                    BootAnimationOption(
                        label = animationNames[animation].orEmpty(),
                        animation = animation,
                        selected = selectedAnimation == animation,
                        enabled = enabled,
                        onSelect = { onSelect(animation) },
                        modifier = Modifier
                            .weight(1f)
                            .height(optionHeight)
                    )
                }
                if (rowAnimations.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BootAnimationOption(
    label: String,
    animation: BootOverlayAnimation,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val borderColor = if (selected) {
        colorScheme.primary
    } else {
        colorScheme.outlineVariant.copy(alpha = 0.42f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(colorScheme.surfaceVariant.copy(alpha = if (selected) 0.26f else 0.14f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(8.dp)
    ) {
        BootLoadingAnimation(
            animation = animation,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
@Composable
private fun BootOrbitLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_infinity_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7200, easing = LinearEasing)
        ),
        label = "boot_overlay_infinity_phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1100,
                easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "boot_overlay_infinity_pulse"
    )

    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val orbitWidth = diameter * 0.34f
        val orbitHeight = diameter * 0.20f
        val twoPi = (Math.PI * 2.0).toFloat()
        val turnSlowdown = 0.44f

        fun easedAngle(angle: Float): Float {
            return angle + turnSlowdown * sin(angle * 2f)
        }

        fun pointAt(angle: Float): Offset {
            val eased = easedAngle(angle)
            return Offset(
                x = center.x + sin(eased) * orbitWidth,
                y = center.y + sin(eased * 2f) * orbitHeight
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorScheme.primary.copy(alpha = 0.18f * pulse),
                    colorScheme.secondary.copy(alpha = 0.08f * pulse),
                    colorScheme.surface.copy(alpha = 0f)
                ),
                center = center,
                radius = diameter * 0.5f
            ),
            radius = diameter * 0.5f,
            center = center
        )

        repeat(96) { index ->
            val angle = twoPi * index / 96f
            val trackPoint = pointAt(angle)
            val shimmer = 0.65f + 0.35f * sin(angle + phase)
            drawCircle(
                color = colorScheme.primary.copy(alpha = 0.09f + shimmer * 0.08f),
                radius = diameter * 0.0085f,
                center = trackPoint
            )
        }

        repeat(26) { index ->
            val trailAge = index / 26f
            val trailPoint = pointAt(phase - trailAge * twoPi * 0.42f)
            val alpha = (1f - trailAge) * 0.28f
            drawCircle(
                color = colorScheme.tertiary.copy(alpha = alpha),
                radius = diameter * (0.026f - trailAge * 0.012f).coerceAtLeast(0.006f),
                center = trailPoint
            )
        }

        val particleColors = listOf(
            colorScheme.primary,
            colorScheme.tertiary,
            colorScheme.secondary
        )
        repeat(3) { index ->
            val angle = phase + index * twoPi / 3f
            val particlePoint = pointAt(angle)
            val depth = 0.82f + 0.18f * cos(angle)
            val particleRadius = diameter * 0.036f * depth
            val glowRadius = diameter * 0.085f * depth
            val particleColor = particleColors[index]
            drawCircle(
                color = particleColor.copy(alpha = 0.18f),
                radius = glowRadius,
                center = particlePoint
            )
            drawCircle(
                color = particleColor.copy(alpha = 0.88f),
                radius = particleRadius,
                center = particlePoint
            )
            drawCircle(
                color = colorScheme.surface.copy(alpha = 0.72f),
                radius = particleRadius * 0.38f,
                center = Offset(
                    x = particlePoint.x - particleRadius * 0.26f,
                    y = particlePoint.y - particleRadius * 0.28f
                )
            )
        }

        drawCircle(
            color = colorScheme.primary.copy(alpha = 0.10f + 0.07f * pulse),
            radius = diameter * 0.09f,
            center = center
        )
        drawCircle(
            color = colorScheme.surface,
            radius = diameter * 0.045f,
            center = center
        )
    }
}

@Composable
private fun BootCometLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_comet_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing)
        ),
        label = "boot_overlay_comet_phase"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = diameter * 0.34f
        val twoPi = (Math.PI * 2.0).toFloat()
        repeat(72) { index ->
            val angle = twoPi * index / 72f
            val point = Offset(
                x = center.x + cos(angle) * radius,
                y = center.y + sin(angle) * radius * 0.72f
            )
            drawCircle(
                color = colorScheme.primary.copy(alpha = 0.08f + 0.05f * sin(angle + phase).coerceAtLeast(0f)),
                radius = diameter * 0.008f,
                center = point
            )
        }
        repeat(24) { index ->
            val age = index / 24f
            val angle = phase - age * twoPi * 0.34f
            val point = Offset(
                x = center.x + cos(angle) * radius,
                y = center.y + sin(angle) * radius * 0.72f
            )
            drawCircle(
                color = colorScheme.tertiary.copy(alpha = (1f - age) * 0.34f),
                radius = diameter * (0.035f - age * 0.017f).coerceAtLeast(0.008f),
                center = point
            )
        }
        val head = Offset(
            x = center.x + cos(phase) * radius,
            y = center.y + sin(phase) * radius * 0.72f
        )
        drawCircle(color = colorScheme.primary.copy(alpha = 0.24f), radius = diameter * 0.09f, center = head)
        drawCircle(color = colorScheme.primary, radius = diameter * 0.038f, center = head)
        drawCircle(color = colorScheme.primary.copy(alpha = 0.12f), radius = diameter * 0.12f, center = center)
        drawCircle(color = colorScheme.surface, radius = diameter * 0.045f, center = center)
    }
}

@Composable
private fun BootWaveLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_wave_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing)
        ),
        label = "boot_overlay_wave_phase"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val twoPi = (Math.PI * 2.0).toFloat()
        val left = center.x - diameter * 0.38f
        val width = diameter * 0.76f
        val amplitude = diameter * 0.18f
        repeat(44) { index ->
            val progress = index / 43f
            val x = left + width * progress
            val y = center.y + sin(progress * twoPi * 2f + phase) * amplitude
            val glow = 0.45f + 0.55f * sin(progress * twoPi + phase).coerceAtLeast(0f)
            drawCircle(
                color = colorScheme.primary.copy(alpha = 0.12f + 0.13f * glow),
                radius = diameter * (0.008f + 0.006f * glow),
                center = Offset(x, y)
            )
        }
        repeat(3) { index ->
            val progress = ((phase / twoPi) + index / 3f) % 1f
            val eased = progress + 0.08f * sin(progress * twoPi)
            val x = left + width * eased
            val y = center.y + sin(eased * twoPi * 2f + phase) * amplitude
            val color = when (index) {
                0 -> colorScheme.primary
                1 -> colorScheme.tertiary
                else -> colorScheme.secondary
            }
            drawCircle(color = color.copy(alpha = 0.20f), radius = diameter * 0.075f, center = Offset(x, y))
            drawCircle(color = color, radius = diameter * 0.032f, center = Offset(x, y))
        }
    }
}

@Composable
private fun BootHaloLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_halo_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = LinearEasing)
        ),
        label = "boot_overlay_halo_phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "boot_overlay_halo_pulse"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = diameter * (0.20f + 0.025f * pulse)
        repeat(3) { index ->
            drawCircle(
                color = colorScheme.primary.copy(alpha = 0.12f - index * 0.025f),
                radius = baseRadius + diameter * 0.08f * index,
                center = center,
                style = Stroke(width = diameter * 0.018f)
            )
        }
        repeat(6) { index ->
            val angle = phase + index * (Math.PI.toFloat() / 3f)
            val radius = baseRadius + diameter * 0.12f + sin(phase + index) * diameter * 0.015f
            val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
            drawCircle(
                color = colorScheme.tertiary.copy(alpha = 0.16f),
                radius = diameter * 0.052f,
                center = point
            )
            drawCircle(
                color = colorScheme.primary.copy(alpha = 0.86f),
                radius = diameter * 0.021f,
                center = point
            )
        }
        drawCircle(color = colorScheme.secondary.copy(alpha = 0.18f + pulse * 0.08f), radius = diameter * 0.07f, center = center)
    }
}

@Composable
private fun BootElasticDotsLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_elastic_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing)
        ),
        label = "boot_overlay_elastic_phase"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val spacing = diameter * 0.15f
        repeat(5) { index ->
            val normalized = index - 2f
            val local = phase + index * 0.55f
            val stretch = 0.50f + 0.50f * sin(local)
            val y = center.y - stretch * diameter * 0.16f
            val radius = diameter * (0.023f + 0.018f * stretch)
            val color = when (index % 3) {
                0 -> colorScheme.primary
                1 -> colorScheme.tertiary
                else -> colorScheme.secondary
            }
            val point = Offset(center.x + normalized * spacing, y)
            drawCircle(color = color.copy(alpha = 0.16f), radius = radius * 2.2f, center = point)
            drawCircle(color = color.copy(alpha = 0.88f), radius = radius, center = point)
            drawLine(
                color = color.copy(alpha = 0.20f),
                start = Offset(point.x, center.y + diameter * 0.20f),
                end = Offset(point.x, point.y + radius * 1.4f),
                strokeWidth = diameter * 0.008f
            )
        }
        drawLine(
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
            start = Offset(center.x - diameter * 0.38f, center.y + diameter * 0.20f),
            end = Offset(center.x + diameter * 0.38f, center.y + diameter * 0.20f),
            strokeWidth = diameter * 0.009f
        )
    }
}

@Composable
private fun BootSpiralLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_spiral_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing)
        ),
        label = "boot_overlay_spiral_phase"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(34) { index ->
            val progress = index / 33f
            val angle = phase + progress * Math.PI.toFloat() * 4.5f
            val radius = diameter * (0.04f + progress * 0.36f)
            val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
            val alpha = 0.12f + progress * 0.38f
            val dotRadius = diameter * (0.009f + progress * 0.014f)
            val color = if (index % 2 == 0) colorScheme.primary else colorScheme.tertiary
            drawCircle(color = color.copy(alpha = alpha), radius = dotRadius * 2.1f, center = point)
            drawCircle(color = color.copy(alpha = alpha.coerceAtMost(0.88f)), radius = dotRadius, center = point)
        }
        drawCircle(color = colorScheme.secondary.copy(alpha = 0.16f), radius = diameter * 0.08f, center = center)
        drawCircle(color = colorScheme.surface, radius = diameter * 0.035f, center = center)
    }
}

@Composable
private fun BootPulseRingsLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_pulse_rings_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing)
        ),
        label = "boot_overlay_pulse_rings_phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "boot_overlay_pulse_rings_pulse"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val twoPi = (Math.PI * 2.0).toFloat()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorScheme.primary.copy(alpha = 0.18f),
                    colorScheme.tertiary.copy(alpha = 0.08f),
                    colorScheme.surface.copy(alpha = 0f)
                ),
                center = center,
                radius = diameter * 0.48f
            ),
            radius = diameter * 0.48f,
            center = center
        )
        repeat(4) { index ->
            val local = (phase + index / 4f) % 1f
            val fade = 1f - local
            val alpha = fade * fade * 0.34f
            drawCircle(
                color = colorScheme.primary.copy(alpha = alpha),
                radius = diameter * (0.10f + 0.33f * local),
                center = center,
                style = Stroke(width = diameter * (0.026f - local * 0.012f).coerceAtLeast(0.006f))
            )
        }
        repeat(10) { index ->
            val angle = twoPi * index / 10f + phase * twoPi * 0.65f
            val distance = diameter * (0.22f + 0.018f * sin(phase * twoPi + index))
            val point = Offset(center.x + cos(angle) * distance, center.y + sin(angle) * distance)
            drawCircle(color = colorScheme.secondary.copy(alpha = 0.18f), radius = diameter * 0.026f, center = point)
            drawCircle(color = colorScheme.tertiary.copy(alpha = 0.68f), radius = diameter * 0.010f, center = point)
        }
        drawCircle(color = colorScheme.primary.copy(alpha = 0.24f), radius = diameter * (0.08f + 0.018f * pulse), center = center)
        drawCircle(color = colorScheme.primary.copy(alpha = 0.92f), radius = diameter * (0.035f + 0.006f * pulse), center = center)
    }
}

@Composable
private fun BootOrbitalEclipseLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_eclipse_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 4200, easing = LinearEasing)),
        label = "boot_overlay_eclipse_phase"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val twoPi = (Math.PI * 2.0).toFloat()
        val orbitX = diameter * 0.34f
        val orbitY = diameter * 0.18f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colorScheme.secondary.copy(alpha = 0.20f), colorScheme.primary.copy(alpha = 0.10f), colorScheme.surface.copy(alpha = 0f)),
                center = center,
                radius = diameter * 0.42f
            ),
            radius = diameter * 0.42f,
            center = center
        )
        repeat(88) { index ->
            val angle = twoPi * index / 88f
            val point = Offset(center.x + cos(angle) * orbitX, center.y + sin(angle) * orbitY)
            drawCircle(
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.07f + 0.05f * sin(angle + phase).coerceAtLeast(0f)),
                radius = diameter * 0.0065f,
                center = point
            )
        }
        val moon = Offset(center.x + cos(phase) * orbitX, center.y + sin(phase) * orbitY)
        val counterMoon = Offset(center.x + cos(phase + Math.PI.toFloat()) * orbitX, center.y + sin(phase + Math.PI.toFloat()) * orbitY)
        val depth = 0.72f + 0.28f * sin(phase)
        drawCircle(color = colorScheme.primary.copy(alpha = 0.16f), radius = diameter * 0.17f, center = center)
        drawCircle(color = colorScheme.secondary.copy(alpha = 0.86f), radius = diameter * 0.072f, center = center)
        drawCircle(color = colorScheme.surface.copy(alpha = 0.70f), radius = diameter * 0.040f, center = Offset(center.x - diameter * 0.018f, center.y - diameter * 0.020f))
        drawCircle(color = colorScheme.tertiary.copy(alpha = 0.16f), radius = diameter * 0.080f * depth, center = moon)
        drawCircle(color = colorScheme.tertiary.copy(alpha = 0.90f), radius = diameter * 0.030f * depth, center = moon)
        drawCircle(color = colorScheme.primary.copy(alpha = 0.13f), radius = diameter * 0.050f, center = counterMoon)
        drawCircle(color = colorScheme.primary.copy(alpha = 0.58f), radius = diameter * 0.016f, center = counterMoon)
    }
}

@Composable
private fun BootRunicGateLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_runic_gate_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 5200, easing = LinearEasing)),
        label = "boot_overlay_runic_gate_phase"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val twoPi = (Math.PI * 2.0).toFloat()
        fun vertex(angle: Float, radius: Float): Offset = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        repeat(2) { layer ->
            val radius = diameter * if (layer == 0) 0.34f else 0.24f
            val offset = phase * if (layer == 0) 0.25f else -0.32f
            val alpha = if (layer == 0) 0.34f else 0.22f
            val points = List(4) { index -> vertex(offset + Math.PI.toFloat() / 4f + index * twoPi / 4f, radius) }
            repeat(4) { index ->
                drawLine(color = colorScheme.primary.copy(alpha = alpha), start = points[index], end = points[(index + 1) % 4], strokeWidth = diameter * if (layer == 0) 0.014f else 0.010f)
            }
            points.forEachIndexed { index, point ->
                val glow = 0.55f + 0.45f * sin(phase + index)
                drawCircle(color = colorScheme.tertiary.copy(alpha = 0.12f + 0.08f * glow), radius = diameter * 0.038f, center = point)
                drawCircle(color = colorScheme.tertiary.copy(alpha = 0.64f), radius = diameter * 0.012f, center = point)
            }
        }
        repeat(16) { index ->
            val angle = phase * 0.8f + index * twoPi / 16f
            val radius = diameter * (0.12f + 0.19f * ((index % 4) / 3f))
            drawCircle(color = colorScheme.secondary.copy(alpha = 0.16f + 0.08f * sin(phase + index).coerceAtLeast(0f)), radius = diameter * 0.006f, center = vertex(angle, radius))
        }
        drawCircle(color = colorScheme.primary.copy(alpha = 0.13f), radius = diameter * 0.08f, center = center)
        drawLine(color = colorScheme.secondary.copy(alpha = 0.58f), start = Offset(center.x - diameter * 0.045f, center.y), end = Offset(center.x + diameter * 0.045f, center.y), strokeWidth = diameter * 0.012f)
        drawLine(color = colorScheme.secondary.copy(alpha = 0.58f), start = Offset(center.x, center.y - diameter * 0.045f), end = Offset(center.x, center.y + diameter * 0.045f), strokeWidth = diameter * 0.012f)
    }
}

@Composable
private fun BootCardShuffleLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_card_shuffle_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12800, easing = LinearEasing)
        ),
        label = "boot_overlay_card_shuffle_phase"
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "boot_overlay_card_shuffle_shimmer"
    )
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val cardWidth = diameter * 0.17f
        val cardHeight = diameter * 0.27f
        val corner = CornerRadius(diameter * 0.022f, diameter * 0.022f)
        val cardCount = 9
        val dealEnd = 0.78f
        val resetStart = 0.86f

        fun easeInOutCubic(value: Float): Float {
            val clamped = value.coerceIn(0f, 1f)
            return if (clamped < 0.5f) {
                4f * clamped * clamped * clamped
            } else {
                val shifted = -2f * clamped + 2f
                1f - shifted * shifted * shifted / 2f
            }
        }

        fun easeInOutSine(value: Float): Float {
            val clamped = value.coerceIn(0f, 1f)
            return 0.5f - 0.5f * cos(clamped * Math.PI.toFloat())
        }

        fun smoothPulse(value: Float): Float {
            val clamped = value.coerceIn(0f, 1f)
            return clamped * clamped * (3f - 2f * clamped)
        }

        fun lerp(start: Offset, end: Offset, t: Float): Offset {
            return Offset(
                x = start.x + (end.x - start.x) * t,
                y = start.y + (end.y - start.y) * t
            )
        }

        fun stackLane(index: Int): Float = ((index % 3) - 1).toFloat()

        fun leftPosition(index: Int): Offset {
            val depth = index * diameter * 0.0048f
            return Offset(
                x = center.x - diameter * 0.315f + depth * 0.72f,
                y = center.y + diameter * 0.070f + stackLane(index) * diameter * 0.015f + depth * 0.20f
            )
        }

        fun middlePosition(index: Int): Offset {
            val lane = stackLane(index)
            return Offset(
                x = center.x + lane * diameter * 0.010f,
                y = center.y - diameter * 0.052f + lane * diameter * 0.006f
            )
        }

        fun rightPosition(index: Int): Offset {
            val depth = index * diameter * 0.0048f
            return Offset(
                x = center.x + diameter * 0.315f - depth * 0.72f,
                y = center.y + diameter * 0.070f - stackLane(index) * diameter * 0.015f + depth * 0.20f
            )
        }

        fun drawCard(
            cardCenter: Offset,
            colorIndex: Int,
            alpha: Float,
            shine: Float = 0f,
            faceProgress: Float = 1f
        ) {
            val color = when (colorIndex % 4) {
                0 -> colorScheme.primary
                1 -> colorScheme.tertiary
                2 -> colorScheme.secondary
                else -> colorScheme.primary.copy(alpha = 0.88f)
            }
            val topLeft = Offset(cardCenter.x - cardWidth / 2f, cardCenter.y - cardHeight / 2f)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.90f * alpha),
                        color.copy(alpha = 0.66f * alpha)
                    ),
                    startY = topLeft.y,
                    endY = topLeft.y + cardHeight
                ),
                topLeft = topLeft,
                size = Size(cardWidth, cardHeight),
                cornerRadius = corner
            )
            drawRoundRect(
                color = colorScheme.surface.copy(alpha = 0.30f * alpha),
                topLeft = topLeft,
                size = Size(cardWidth, cardHeight),
                cornerRadius = corner,
                style = Stroke(width = diameter * 0.0045f)
            )
            drawRoundRect(
                color = colorScheme.surface.copy(alpha = 0.33f * alpha * faceProgress),
                topLeft = Offset(topLeft.x + cardWidth * 0.18f, topLeft.y + cardHeight * 0.16f),
                size = Size(cardWidth * 0.64f, diameter * 0.010f),
                cornerRadius = CornerRadius(diameter * 0.006f, diameter * 0.006f)
            )
            drawRoundRect(
                color = colorScheme.surface.copy(alpha = 0.21f * alpha * faceProgress),
                topLeft = Offset(topLeft.x + cardWidth * 0.24f, topLeft.y + cardHeight * 0.28f),
                size = Size(cardWidth * 0.52f, diameter * 0.007f),
                cornerRadius = CornerRadius(diameter * 0.004f, diameter * 0.004f)
            )
            drawCircle(
                color = colorScheme.surface.copy(alpha = 0.48f * alpha * faceProgress),
                radius = diameter * 0.009f,
                center = Offset(cardCenter.x, topLeft.y + cardHeight * 0.70f)
            )
            if (shine > 0f) {
                val sweep = shine.coerceIn(0f, 1f)
                val shineAlpha = smoothPulse(1f - kotlin.math.abs(sweep - 0.5f) * 2f)
                drawRoundRect(
                    color = colorScheme.surface.copy(alpha = 0.20f * alpha * shineAlpha),
                    topLeft = Offset(
                        topLeft.x + cardWidth * (0.08f + sweep * 0.66f),
                        topLeft.y + cardHeight * 0.08f
                    ),
                    size = Size(cardWidth * 0.14f, cardHeight * 0.84f),
                    cornerRadius = CornerRadius(diameter * 0.012f, diameter * 0.012f)
                )
            }
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorScheme.primary.copy(alpha = 0.10f + 0.04f * shimmer),
                    colorScheme.tertiary.copy(alpha = 0.06f),
                    colorScheme.surface.copy(alpha = 0f)
                ),
                center = center,
                radius = diameter * 0.46f
            ),
            radius = diameter * 0.46f,
            center = center
        )

        fun drawRightStack() {
            repeat(cardCount) { index ->
                drawCard(
                    cardCenter = rightPosition(index),
                    colorIndex = index,
                    alpha = 0.80f,
                    faceProgress = 0.86f
                )
            }
        }

        when {
            phase < dealEnd -> {
                val dealStepCount = cardCount + 1
                val step = ((phase / dealEnd) * dealStepCount).coerceIn(0f, dealStepCount - 0.001f)
                val stepIndex = step.toInt().coerceIn(0, dealStepCount - 1)
                val local = step - stepIndex
                val currentCard = stepIndex
                val previousCard = stepIndex - 1

                repeat((stepIndex - 1).coerceAtLeast(0)) { index ->
                    drawCard(
                        cardCenter = rightPosition(index),
                        colorIndex = index,
                        alpha = 0.80f,
                        faceProgress = 0.86f
                    )
                }
                if (currentCard < cardCount - 1) {
                    for (index in cardCount - 1 downTo currentCard + 1) {
                        drawCard(
                            cardCenter = leftPosition(index),
                            colorIndex = index,
                            alpha = 0.74f,
                            faceProgress = 0.82f
                        )
                    }
                }

                if (previousCard in 0 until cardCount) {
                    val t = easeInOutCubic(local)
                    val base = lerp(middlePosition(previousCard), rightPosition(previousCard), t)
                    val lift = sin(t * Math.PI.toFloat()) * diameter * 0.046f
                    val shine = smoothPulse(sin(t * Math.PI.toFloat()).coerceAtLeast(0f))
                    drawCard(
                        cardCenter = Offset(base.x, base.y - lift),
                        colorIndex = previousCard,
                        alpha = 0.86f,
                        shine = shine,
                        faceProgress = 0.84f + 0.16f * shine
                    )
                }
                if (currentCard in 0 until cardCount) {
                    val t = easeInOutSine(local)
                    val base = lerp(leftPosition(currentCard), middlePosition(currentCard), t)
                    val lift = sin(t * Math.PI.toFloat()) * diameter * 0.058f
                    val drift = sin(t * Math.PI.toFloat() * 2f) * diameter * 0.007f
                    val shine = smoothPulse(sin(t * Math.PI.toFloat()).coerceAtLeast(0f))
                    drawCard(
                        cardCenter = Offset(base.x + drift, base.y - lift),
                        colorIndex = currentCard,
                        alpha = 0.88f,
                        shine = shine,
                        faceProgress = 0.84f + 0.16f * shine
                    )
                }
            }
            phase < resetStart -> {
                drawRightStack()
            }
            else -> {
                val reset = ((phase - resetStart) / (1f - resetStart)).coerceIn(0f, 1f)
                repeat(cardCount) { index ->
                    val delay = index * 0.024f
                    val travelDuration = 0.60f + (index % 4) * 0.050f
                    val raw = ((reset - delay) / travelDuration).coerceIn(0f, 1f)
                    val eased = when (index % 3) {
                        0 -> easeInOutSine(raw)
                        1 -> easeInOutCubic(raw)
                        else -> smoothPulse(raw)
                    }
                    val base = lerp(rightPosition(index), leftPosition(index), eased)
                    val lift = sin(eased * Math.PI.toFloat()) * diameter * (0.060f + (index % 3) * 0.014f)
                    val drift = sin(eased * Math.PI.toFloat() * (1.4f + index * 0.08f)) * diameter * 0.010f
                    val shine = smoothPulse(sin(raw * Math.PI.toFloat()).coerceAtLeast(0f))
                    drawCard(
                        cardCenter = Offset(base.x + drift, base.y - lift),
                        colorIndex = index,
                        alpha = 0.82f + 0.06f * shine,
                        shine = shine,
                        faceProgress = 0.86f + 0.12f * shine
                    )
                }
            }
        }
    }
}
@Composable
private fun BootPrismSweepLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_prism_transition")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = tween(durationMillis = 3600, easing = LinearEasing)), label = "boot_overlay_prism_phase")
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val a = Offset(center.x, center.y - diameter * 0.34f)
        val b = Offset(center.x - diameter * 0.33f, center.y + diameter * 0.24f)
        val c = Offset(center.x + diameter * 0.33f, center.y + diameter * 0.24f)
        fun lerp(start: Offset, end: Offset, t: Float): Offset = Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
        fun trianglePoint(progress: Float): Offset {
            val wrapped = (progress % 1f + 1f) % 1f
            val segment = wrapped * 3f
            return when { segment < 1f -> lerp(a, b, segment); segment < 2f -> lerp(b, c, segment - 1f); else -> lerp(c, a, segment - 2f) }
        }
        listOf(a to b, b to c, c to a).forEachIndexed { index, edge ->
            val color = when (index) { 0 -> colorScheme.primary; 1 -> colorScheme.tertiary; else -> colorScheme.secondary }
            drawLine(color = color.copy(alpha = 0.30f), start = edge.first, end = edge.second, strokeWidth = diameter * 0.014f)
        }
        repeat(20) { index ->
            val age = index / 20f
            val point = trianglePoint(phase - age * 0.18f)
            drawCircle(color = colorScheme.primary.copy(alpha = (1f - age) * 0.36f), radius = diameter * (0.030f - age * 0.014f), center = point)
        }
        val sweep = trianglePoint(phase)
        drawCircle(color = colorScheme.tertiary.copy(alpha = 0.22f), radius = diameter * 0.090f, center = sweep)
        drawCircle(color = colorScheme.tertiary.copy(alpha = 0.92f), radius = diameter * 0.032f, center = sweep)
        drawLine(color = colorScheme.primary.copy(alpha = 0.18f), start = center, end = a, strokeWidth = diameter * 0.008f)
        drawLine(color = colorScheme.tertiary.copy(alpha = 0.18f), start = center, end = b, strokeWidth = diameter * 0.008f)
        drawLine(color = colorScheme.secondary.copy(alpha = 0.18f), start = center, end = c, strokeWidth = diameter * 0.008f)
        drawCircle(color = colorScheme.surface.copy(alpha = 0.70f), radius = diameter * 0.034f, center = center)
    }
}

@Composable
private fun BootHelixLadderLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_helix_transition")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = (Math.PI * 2.0).toFloat(), animationSpec = infiniteRepeatable(animation = tween(durationMillis = 3900, easing = LinearEasing)), label = "boot_overlay_helix_phase")
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val left = center.x - diameter * 0.36f
        val width = diameter * 0.72f
        val amplitude = diameter * 0.18f
        val twoPi = (Math.PI * 2.0).toFloat()
        repeat(28) { index ->
            val progress = index / 27f
            val x = left + width * progress
            val wave = sin(progress * twoPi * 1.7f + phase)
            val y1 = center.y + wave * amplitude
            val y2 = center.y - wave * amplitude
            val alpha = 0.10f + 0.16f * (0.5f + 0.5f * sin(phase + progress * twoPi))
            drawLine(color = colorScheme.onSurfaceVariant.copy(alpha = alpha), start = Offset(x, y1), end = Offset(x, y2), strokeWidth = diameter * 0.006f)
            val front = wave > 0f
            drawCircle(color = (if (front) colorScheme.primary else colorScheme.secondary).copy(alpha = 0.62f), radius = diameter * 0.011f, center = Offset(x, y1))
            drawCircle(color = (if (front) colorScheme.secondary else colorScheme.primary).copy(alpha = 0.42f), radius = diameter * 0.009f, center = Offset(x, y2))
        }
        repeat(3) { index ->
            val progress = ((phase / twoPi) + index / 3f) % 1f
            val x = left + width * progress
            val wave = sin(progress * twoPi * 1.7f + phase)
            val point = Offset(x, center.y + wave * amplitude)
            drawCircle(color = colorScheme.tertiary.copy(alpha = 0.20f), radius = diameter * 0.065f, center = point)
            drawCircle(color = colorScheme.tertiary.copy(alpha = 0.86f), radius = diameter * 0.024f, center = point)
        }
    }
}

@Composable
private fun BootLiquidOrbLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_liquid_orb_transition")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = (Math.PI * 2.0).toFloat(), animationSpec = infiniteRepeatable(animation = tween(durationMillis = 4600, easing = LinearEasing)), label = "boot_overlay_liquid_orb_phase")
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val twoPi = (Math.PI * 2.0).toFloat()
        drawCircle(brush = Brush.radialGradient(colors = listOf(colorScheme.primary.copy(alpha = 0.32f), colorScheme.tertiary.copy(alpha = 0.18f), colorScheme.surface.copy(alpha = 0f)), center = center, radius = diameter * 0.30f), radius = diameter * 0.30f, center = center)
        repeat(9) { index ->
            val angle = index * twoPi / 9f + phase * 0.45f
            val wobble = 0.5f + 0.5f * sin(phase + index * 0.9f)
            val point = Offset(center.x + cos(angle) * diameter * (0.028f + wobble * 0.018f), center.y + sin(angle) * diameter * (0.028f + wobble * 0.018f))
            val color = when (index % 3) { 0 -> colorScheme.primary; 1 -> colorScheme.secondary; else -> colorScheme.tertiary }
            drawCircle(color = color.copy(alpha = 0.30f), radius = diameter * (0.092f + wobble * 0.030f), center = point)
        }
        repeat(18) { index ->
            val angle = phase + index * twoPi / 18f
            val radius = diameter * (0.24f + 0.018f * sin(phase * 1.4f + index))
            drawCircle(color = colorScheme.onSurfaceVariant.copy(alpha = 0.13f), radius = diameter * 0.006f, center = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius))
        }
        drawCircle(color = colorScheme.surface.copy(alpha = 0.58f), radius = diameter * 0.038f, center = Offset(center.x - diameter * 0.050f, center.y - diameter * 0.064f))
    }
}

@Composable
private fun BootSignalStackLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_signal_stack_transition")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = (Math.PI * 2.0).toFloat(), animationSpec = infiniteRepeatable(animation = tween(durationMillis = 2800, easing = LinearEasing)), label = "boot_overlay_signal_stack_phase")
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val barWidth = diameter * 0.045f
        val gap = diameter * 0.030f
        val baseY = center.y + diameter * 0.22f
        repeat(9) { index ->
            val normalized = index - 4f
            val local = 0.5f + 0.5f * sin(phase + index * 0.58f)
            val height = diameter * (0.09f + local * 0.28f)
            val x = center.x + normalized * (barWidth + gap)
            val color = when (index % 3) { 0 -> colorScheme.primary; 1 -> colorScheme.tertiary; else -> colorScheme.secondary }
            drawRoundRect(color = color.copy(alpha = 0.18f), topLeft = Offset(x - barWidth * 0.75f, baseY - height - diameter * 0.018f), size = Size(barWidth * 1.5f, height + diameter * 0.036f), cornerRadius = CornerRadius(barWidth, barWidth))
            drawRoundRect(color = color.copy(alpha = 0.78f), topLeft = Offset(x - barWidth / 2f, baseY - height), size = Size(barWidth, height), cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f))
            drawCircle(color = color.copy(alpha = 0.24f), radius = diameter * 0.025f, center = Offset(x, baseY - height))
        }
        drawLine(color = colorScheme.onSurfaceVariant.copy(alpha = 0.13f), start = Offset(center.x - diameter * 0.38f, baseY), end = Offset(center.x + diameter * 0.38f, baseY), strokeWidth = diameter * 0.009f)
    }
}

@Composable
private fun BootDiamondFlowLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_diamond_flow_transition")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = tween(durationMillis = 4000, easing = LinearEasing)), label = "boot_overlay_diamond_flow_phase")
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val top = Offset(center.x, center.y - diameter * 0.33f)
        val right = Offset(center.x + diameter * 0.34f, center.y)
        val bottom = Offset(center.x, center.y + diameter * 0.33f)
        val left = Offset(center.x - diameter * 0.34f, center.y)
        val vertices = listOf(top, right, bottom, left)
        fun lerp(start: Offset, end: Offset, t: Float): Offset = Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
        fun pointAt(progress: Float): Offset {
            val wrapped = (progress % 1f + 1f) % 1f
            val segment = wrapped * 4f
            val edge = segment.toInt().coerceIn(0, 3)
            return lerp(vertices[edge], vertices[(edge + 1) % 4], segment - edge)
        }
        repeat(4) { index -> drawLine(color = colorScheme.primary.copy(alpha = 0.22f), start = vertices[index], end = vertices[(index + 1) % 4], strokeWidth = diameter * 0.012f) }
        repeat(28) { index ->
            val age = index / 28f
            drawCircle(color = colorScheme.tertiary.copy(alpha = (1f - age) * 0.30f), radius = diameter * (0.022f - age * 0.010f).coerceAtLeast(0.006f), center = pointAt(phase - age * 0.30f))
        }
        repeat(3) { index ->
            val point = pointAt(phase + index / 3f)
            val color = when (index) { 0 -> colorScheme.primary; 1 -> colorScheme.secondary; else -> colorScheme.tertiary }
            drawCircle(color = color.copy(alpha = 0.20f), radius = diameter * 0.064f, center = point)
            drawCircle(color = color.copy(alpha = 0.86f), radius = diameter * 0.026f, center = point)
        }
        drawCircle(color = colorScheme.secondary.copy(alpha = 0.14f), radius = diameter * 0.072f, center = center)
    }
}

@Composable
private fun BootGravityWellLoadingAnimation(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "boot_overlay_gravity_well_transition")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = (Math.PI * 2.0).toFloat(), animationSpec = infiniteRepeatable(animation = tween(durationMillis = 5200, easing = LinearEasing)), label = "boot_overlay_gravity_well_phase")
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(brush = Brush.radialGradient(colors = listOf(colorScheme.surface.copy(alpha = 0.88f), colorScheme.primary.copy(alpha = 0.16f), colorScheme.tertiary.copy(alpha = 0.06f), colorScheme.surface.copy(alpha = 0f)), center = center, radius = diameter * 0.38f), radius = diameter * 0.38f, center = center)
        repeat(54) { index ->
            val progress = index / 53f
            val angle = phase + progress * Math.PI.toFloat() * 7.5f
            val radius = diameter * (0.36f - progress * 0.31f)
            val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
            val alpha = 0.08f + progress * 0.52f
            val dotRadius = diameter * (0.006f + progress * 0.015f)
            val color = when (index % 3) { 0 -> colorScheme.primary; 1 -> colorScheme.secondary; else -> colorScheme.tertiary }
            drawCircle(color = color.copy(alpha = alpha * 0.40f), radius = dotRadius * 2.4f, center = point)
            drawCircle(color = color.copy(alpha = alpha.coerceAtMost(0.82f)), radius = dotRadius, center = point)
        }
        drawCircle(color = colorScheme.primary.copy(alpha = 0.18f), radius = diameter * 0.086f, center = center)
        drawCircle(color = colorScheme.surface, radius = diameter * 0.042f, center = center)
        drawCircle(color = colorScheme.onSurface.copy(alpha = 0.20f), radius = diameter * 0.018f, center = center)
    }
}