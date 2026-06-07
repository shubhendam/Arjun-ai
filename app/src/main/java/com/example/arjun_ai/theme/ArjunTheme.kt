package com.example.arjun_ai.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =============================================================================
// JARVIS-inspired dark palette: deep space-black, electric cyan, amber accent.
// =============================================================================
val Cyan = Color(0xFF22D3EE)
val CyanDim = Color(0xFF0E7C90)
val Amber = Color(0xFFFFB020)
val SpaceBlack = Color(0xFF05080D)
val Panel = Color(0xFF0F1620)
val PanelHi = Color(0xFF16202C)
val TextHi = Color(0xFFCFE9F2)
val TextDim = Color(0xFF7E97A6)

private val ArjunColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00232C),
    primaryContainer = Color(0xFF0B3A45),
    onPrimaryContainer = Color(0xFFB7EEF9),
    secondary = Amber,
    onSecondary = Color(0xFF241600),
    secondaryContainer = Color(0xFF3A2A00),
    onSecondaryContainer = Color(0xFFFFE0A6),
    background = SpaceBlack,
    onBackground = TextHi,
    surface = Panel,
    onSurface = TextHi,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextDim,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    outline = Color(0xFF1E3A47),
)

@Composable
fun ArjunTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ArjunColors, content = content)
}

/** App-wide background: dark vertical gradient with a faint cyan glow up top. */
fun Modifier.jarvisBackground(): Modifier = this
    .background(
        Brush.verticalGradient(
            0f to Color(0xFF071019),
            0.45f to SpaceBlack,
            1f to Color(0xFF03050A),
        )
    )
    .drawBehind {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Cyan.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.04f),
                radius = size.width * 0.75f,
            ),
            center = Offset(size.width * 0.5f, size.height * 0.04f),
            radius = size.width * 0.75f,
        )
    }

/**
 * A pulsing arc-reactor-style status orb. [color] reflects the current state;
 * when [active] it breathes, otherwise it sits dim and still.
 */
@Composable
fun StatusOrb(color: Color, active: Boolean, size: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = if (active) 0.45f else 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 750 else 2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                // outer glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.55f * pulse), Color.Transparent),
                    ),
                    radius = this.size.minDimension,
                )
            }
            .clip(CircleShape)
            .background(color.copy(alpha = pulse))
    )
}
