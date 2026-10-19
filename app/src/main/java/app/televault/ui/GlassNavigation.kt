package app.televault.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class GlassTab(val name: String, val icon: ImageVector)

/** Stable destination slots with a readable selected pill and a compact, inset-safe glass shell. */
@Composable
internal fun GlassNavigation(tabs: List<GlassTab>, selected: Int, search: (() -> Unit)? = null, choose: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalInterfaceStyle.current == app.televault.InterfaceStyle.GLASS
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSurface(
            modifier = Modifier.weight(1f).widthIn(max = 460.dp),
            shape = RoundedCornerShape(100.dp),
            color = chromeColor(),
            tonalElevation = if (glass) 0.dp else 3.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        colors.onSurface.copy(alpha = if (glass) .12f else .08f),
                        colors.primary.copy(alpha = .09f),
                        colors.onSurface.copy(alpha = .03f)
                    )
                )
            )
        ) {
            BoxWithConstraints(Modifier.padding(6.dp)) {
                val fontScale = LocalDensity.current.fontScale
                val slotWidth = (maxWidth - 2.dp * (tabs.size - 1).coerceAtLeast(0)) / tabs.size.coerceAtLeast(1)
                // Large type gets an icon above its full two-line label, rather than squeezing
                // the active destination into an animated-width pill and cutting off its name.
                val stacked = fontScale > 1.25f || slotWidth < 52.dp
                val inlineIcon = !stacked && slotWidth >= 52.dp * fontScale
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { index, tab ->
                        val active = index == selected
                        val accent = when (index) {
                            1, 3 -> colors.tertiary
                            2 -> colors.secondary
                            else -> colors.primary
                        }
                        val selectedContainer = when (index) {
                            1, 3 -> colors.tertiaryContainer
                            2 -> colors.secondaryContainer
                            else -> colors.primaryContainer
                        }
                        val selectedContent = when (index) {
                            1, 3 -> colors.onTertiaryContainer
                            2 -> colors.onSecondaryContainer
                            else -> colors.onPrimaryContainer
                        }
                        val tint by animateColorAsState(if (active) selectedContent else colors.onSurfaceVariant, tween(180), label = "Tab color")
                        val background by animateColorAsState(if (active) selectedContainer else Color.Transparent, tween(180), label = "Tab pill")
                        val outline by animateColorAsState(if (active) accent.copy(alpha = .08f) else Color.Transparent, tween(180), label = "Tab outline")
                        val shape = RoundedCornerShape(100.dp)
                        Box(
                            Modifier.weight(if (active && !stacked) 1.28f else 1f).heightIn(min = 52.dp).fillMaxHeight().clip(shape).background(background, shape)
                                .border(1.dp, outline, shape)
                                .selectable(active, role = Role.Tab, onClick = { choose(index) }),
                            contentAlignment = Alignment.Center
                        ) {
                            if (stacked) {
                                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(tab.icon, null, Modifier.size(22.dp), tint = tint)
                                    Text(tab.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = tint, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                Row(Modifier.padding(horizontal = 5.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    if (inlineIcon && active) {
                                        Icon(tab.icon, null, Modifier.size(20.dp), tint = tint)
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    Text(tab.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (search != null) {
            AppSurface(shape = RoundedCornerShape(100.dp), color = chromeColor(), shadowElevation = 8.dp) {
                IconButton(search, Modifier.size(58.dp)) { Icon(Icons.Outlined.Search, label("Search everything", "সবকিছু খুঁজুন"), Modifier.size(26.dp)) }
            }
        }
    }
}
