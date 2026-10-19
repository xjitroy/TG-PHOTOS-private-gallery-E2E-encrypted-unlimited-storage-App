package app.televault.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.televault.AppearanceMode
import app.televault.InterfaceStyle

private val Light = lightColorScheme(
    primary = Color(0xFF3465D9), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF), onPrimaryContainer = Color(0xFF0C245E),
    secondary = Color(0xFF006B69), onSecondary = Color.White,
    secondaryContainer = Color(0xFFB5F0EA), onSecondaryContainer = Color(0xFF00201F),
    background = Color(0xFFFBF9FC), onBackground = Color(0xFF101827),
    surface = Color(0xFFFBF9FC), onSurface = Color(0xFF101827),
    surfaceVariant = Color(0xFFE1E7F0), onSurfaceVariant = Color(0xFF445065),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F5FC),
    surfaceContainer = Color(0xFFEBF0FA), surfaceContainerHigh = Color(0xFFE4EBF7),
    surfaceContainerHighest = Color(0xFFDCE5F3), surfaceDim = Color(0xFFD5DDED),
    surfaceBright = Color(0xFFFBF9FC), tertiary = Color(0xFF80558B),
    onTertiary = Color.White, tertiaryContainer = Color(0xFFF7D9FF), onTertiaryContainer = Color(0xFF32123D),
    outline = Color(0xFF737E90), outlineVariant = Color(0xFFC3CCD9),
    inverseSurface = Color(0xFF293446), inverseOnSurface = Color(0xFFF3F6FB), inversePrimary = Color(0xFFABBFFF),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002), scrim = Color.Black
)
private val Dark = darkColorScheme(
    primary = Color(0xFFABBFFF), onPrimary = Color(0xFF062C71),
    primaryContainer = Color(0xFF263D85), onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFF80CAC8), onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF00504D), onSecondaryContainer = Color(0xFFB5F0EA),
    background = Color(0xFF060B15), onBackground = Color(0xFFF3F6FB),
    surface = Color(0xFF0C1422), onSurface = Color(0xFFF3F6FB),
    surfaceVariant = Color(0xFF293446), onSurfaceVariant = Color(0xFFB0BED1),
    surfaceContainerLowest = Color(0xFF060B15), surfaceContainerLow = Color(0xFF101A2C),
    surfaceContainer = Color(0xFF1C2230), surfaceContainerHigh = Color(0xFF252D3C),
    surfaceContainerHighest = Color(0xFF303A4C), surfaceDim = Color(0xFF10131B),
    surfaceBright = Color(0xFF354054), tertiary = Color(0xFFC5A6FF),
    onTertiary = Color(0xFF4B2456), tertiaryContainer = Color(0xFF4F2988), onTertiaryContainer = Color(0xFFF7D9FF),
    outline = Color(0xFF8593AA), outlineVariant = Color(0xFF445065),
    inverseSurface = Color(0xFFE4EBF7), inverseOnSurface = Color(0xFF101827), inversePrimary = Color(0xFF3465D9),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6), scrim = Color.Black
)

private val AppShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp))

internal val LocalInterfaceStyle = staticCompositionLocalOf { InterfaceStyle.GLASS }

@Composable
fun TeleVaultTheme(dynamicColor: Boolean = true, appearanceMode: AppearanceMode = AppearanceMode.SYSTEM, interfaceStyle: InterfaceStyle = InterfaceStyle.GLASS, content: @Composable () -> Unit) {
    val dark = when (appearanceMode) {
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context, view, dark) {
        val host = themeActivity(context)
        val window = host?.window
        if (window != null) {
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
    val generated = remember(context, dynamicColor, dark) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> Dark
            else -> Light
        }
    }
    val glass = interfaceStyle == InterfaceStyle.GLASS
    // Keep every wallpaper role and its paired readable content color in either style.
    // Glass treatment is independent of the Material You color switch.
    val palette = generated
    // Static app-owned color wash. No sampling of photos, screenshots, wallpaper bitmaps
    // or expensive per-frame blur; the same composition tree survives style changes.
    val backdrop = remember(palette, glass, dark) {
        val strength = if (dark) .18f else .12f
        Brush.linearGradient(
            listOf(
                lerp(palette.background, palette.primary, if (glass) strength else 0f),
                lerp(palette.background, palette.secondary, if (glass) strength * .65f else 0f),
                lerp(palette.background, palette.tertiary, if (glass) strength else 0f)
            )
        )
    }
    val colors = remember(palette, glass) {
        if (!glass) {
            palette.copy(surfaceTint = palette.primary)
        } else {
            // Dialogs, menus, inputs and sheets consume these roles too. Keep their surfaces
            // opaque so content underneath never compromises text contrast or shows through a
            // sensitive dialog. Glass transparency belongs to the backdrop and explicit chrome.
            palette.copy(
                background = palette.background,
                surfaceTint = palette.primary
            )
        }
    }
    CompositionLocalProvider(LocalInterfaceStyle provides interfaceStyle) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, content = {
            Box(Modifier.fillMaxSize().background(backdrop)) { content() }
        })
    }
}

private tailrec fun themeActivity(context: android.content.Context): android.app.Activity? = when (context) {
    is android.app.Activity -> context
    is android.content.ContextWrapper -> if (context.baseContext !== context) themeActivity(context.baseContext) else null
    else -> null
}
