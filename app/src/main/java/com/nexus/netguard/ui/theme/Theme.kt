package com.nexus.netguard.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object NexusColors {
    val Bg = Color(0xFF070B14)
    val Surface = Color(0xFF0F1626)
    val SurfaceHi = Color(0xFF16203A)
    val Neon = Color(0xFF00F5A0)
    val Cyan = Color(0xFF00D1FF)
    val Danger = Color(0xFFFF3D71)
    val Amber = Color(0xFFFFB020)
    val Outline = Color(0xFF26324F)
    val TextDim = Color(0xFF8A96B2)
    val Text = Color(0xFFE6ECFF)
}

private val NexusScheme = darkColorScheme(
    primary = NexusColors.Neon,
    onPrimary = NexusColors.Bg,
    secondary = NexusColors.Cyan,
    background = NexusColors.Bg,
    onBackground = NexusColors.Text,
    surface = NexusColors.Surface,
    onSurface = NexusColors.Text,
    surfaceVariant = NexusColors.SurfaceHi,
    onSurfaceVariant = NexusColors.TextDim,
    error = NexusColors.Danger,
    outline = NexusColors.Outline,
)

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NexusScheme, content = content)
}

/** Pulsing neon border when [active], static dim border otherwise. */
@Composable
fun Modifier.glowBorder(active: Boolean): Modifier {
    val transition = rememberInfiniteTransition(label = "glow")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val color = if (active) NexusColors.Neon.copy(alpha = pulse) else NexusColors.Outline
    return this.border(1.5.dp, color, RoundedCornerShape(18.dp))
}

@Composable
fun NexusCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(NexusColors.Surface)
            .glowBorder(glow)
            .padding(16.dp),
        content = content,
    )
}
