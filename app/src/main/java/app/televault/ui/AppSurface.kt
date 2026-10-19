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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
        val edge = border ?: BorderStroke(.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .25f else .66f), colors.outlineVariant.copy(alpha = .3f), Color.White.copy(alpha = if (dark) .07f else .26f))))
        val light = if (dark) .09f else .28f
        edge to Brush.linearGradient(listOf(Color.White.copy(alpha = light + if (pressed) .08f else 0f), colors.primary.copy(alpha = .025f), Color.Transparent, colors.primary.copy(alpha = if (dark) .04f else .025f)))
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
                Box(Modifier.matchParentSize().background(sheen))
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
                Box(Modifier.matchParentSize().background(sheen))
                content()
            }
        }
    }
}

@Composable
internal fun chromeColor(media: Boolean = false): Color = when {
    LocalInterfaceStyle.current != app.televault.InterfaceStyle.GLASS -> if (media) Color.Black.copy(alpha = .72f) else MaterialTheme.colorScheme.surfaceContainerHigh
    media -> Color.Black.copy(alpha = .62f)
    else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .88f)
}
