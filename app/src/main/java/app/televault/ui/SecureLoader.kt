package app.televault.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** A bounded ornamental activity indicator; never invents transfer progress. */
@Composable
internal fun SecureLoader(modifier: Modifier = Modifier, percent: Int? = null) {
    val transition = rememberInfiniteTransition(label = "Secure loading")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "Orbit")
    val colors = MaterialTheme.colorScheme
    val description = label("Loading", "লোড হচ্ছে")
    Box(modifier.size(44.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension * .4f
            repeat(3) { index ->
                val radians = Math.toRadians((angle + index * 120).toDouble())
                val color = when (index) {
                    0 -> colors.primary
                    1 -> colors.tertiary
                    else -> colors.secondary
                }
                drawCircle(color, radius = size.minDimension * .055f, center = Offset(center.x + cos(radians).toFloat() * radius, center.y + sin(radians).toFloat() * radius))
            }
        }
        if (percent == null) Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = LocalContentColor.current) else Text("${percent.coerceIn(0, 100)}%", fontSize = 8.sp, lineHeight = 9.sp, color = LocalContentColor.current)
    }
}
