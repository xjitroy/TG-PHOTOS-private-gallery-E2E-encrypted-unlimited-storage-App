package app.televault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared surface skin. Content stays readable; only explicit floating chrome is translucent.
 * Highlights are procedural and cached: no photo capture, bitmap blur or private-frame sampling.
 * This is Android styling, not Apple's proprietary Liquid Glass rendering engine. */
@Composable
private fun surfaceSkin(color: Color, shape: Shape, border: BorderStroke?, pressed: Boolean = false): Pair<BorderStroke?, Brush?> {
    if (LocalInterfaceStyle.current != app.televault.InterfaceStyle.GLASS || shape == RectangleShape) return border to null
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    return remember(color, shape, border, pressed, colors) {
        val edge = border ?: BorderStroke(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .46f else .86f), colors.outlineVariant.copy(alpha = .14f), Color.White.copy(alpha = if (dark) .18f else .56f))))
        val light = if (dark) .11f else .24f
        edge to Brush.linearGradient(listOf(Color.White.copy(alpha = light + if (pressed) .08f else 0f), Color.White.copy(alpha = .025f), Color.Transparent, colors.primary.copy(alpha = if (dark) .06f else .04f)))
    }
}

/** A clipped, size-cached lens highlight; no backdrop copies, blur passes or idle animation. */
@Composable
private fun glassLens(pressure: Float = 0f): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val tint = MaterialTheme.colorScheme.primary
    return Modifier.drawWithCache {
        val light = Brush.radialGradient(listOf(Color.White.copy(alpha = (if (dark) .12f else .2f) + pressure * .08f), Color.Transparent), center = Offset(size.width * (.18f + pressure * .5f), -size.height * .2f), radius = maxOf(size.width * .85f, size.height).coerceAtLeast(1f))
        val lower = Brush.linearGradient(listOf(Color.Transparent, tint.copy(alpha = if (dark) .045f else .025f)), start = Offset.Zero, end = Offset(size.width, size.height))
        val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .1f else .3f), Color.Transparent, Color.White.copy(alpha = if (dark) .06f else .16f)))
        onDrawBehind {
            drawRect(lower)
            drawRect(light)
            drawRect(rim, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
internal fun AppSurface(modifier: Modifier = Modifier, shape: Shape = RectangleShape, color: Color = MaterialTheme.colorScheme.surface, contentColor: Color = contentColorFor(color), tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp, border: BorderStroke? = null, content: @Composable () -> Unit) {
    val (edge, sheen) = surfaceSkin(color, shape, border)
    MaterialSurface(modifier, shape, color, contentColor, tonalElevation, shadowElevation, edge) {
        if (sheen == null) {
            content()
        } else {
            Box(propagateMinConstraints = true) {
                Box(Modifier.matchParentSize().background(sheen).then(glassLens()))
                content()
            }
        }
    }
}

@Composable
internal fun AppSurface(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = RectangleShape, color: Color = MaterialTheme.colorScheme.surface, contentColor: Color = contentColorFor(color), tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp, border: BorderStroke? = null, interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val glass = LocalInterfaceStyle.current == app.televault.InterfaceStyle.GLASS
    val scale by animateFloatAsState(if (glass && pressed) .985f else 1f, tween(100), label = "Surface press")
    val pressure by animateFloatAsState(if (glass && pressed) 1f else 0f, tween(180), label = "Glass light")
    val (edge, sheen) = surfaceSkin(color, shape, border, pressed)
    MaterialSurface(
        onClick,
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled, shape, color, contentColor, tonalElevation, shadowElevation, edge, interactions
    ) {
        if (sheen == null) {
            content()
        } else {
            Box(propagateMinConstraints = true) {
                Box(Modifier.matchParentSize().background(sheen).then(glassLens(pressure)))
                content()
            }
        }
    }
}

@Composable
internal fun chromeColor(media: Boolean = false): Color = when {
    LocalInterfaceStyle.current != app.televault.InterfaceStyle.GLASS -> if (media) Color.Black.copy(alpha = .72f) else MaterialTheme.colorScheme.surfaceContainerHigh
    media -> Color.Black.copy(alpha = .62f)
    else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .82f)
}
