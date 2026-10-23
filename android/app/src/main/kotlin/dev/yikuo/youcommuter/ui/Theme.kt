package dev.yikuo.youcommuter.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5B), onPrimary = Color.White,
    secondary = Color(0xFF426653), onSecondary = Color.White,
    background = Color(0xFFF6F8F7), surface = Color.White,
    onBackground = Color(0xFF172521), onSurface = Color(0xFF172521),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEDF3EF),
    surfaceContainer = Color(0xFFE7EFEB), surfaceContainerHigh = Color(0xFFDFE9E3),
    surfaceContainerHighest = Color(0xFFDCE7DF),
    onSurfaceVariant = Color(0xFF52635A), outlineVariant = Color(0xFFCDDBD2), outline = Color(0xFF74887B),
    secondaryContainer = Color(0xFFD1EBDD), onSecondaryContainer = Color(0xFF163B2C),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF77D8BB), onPrimary = Color(0xFF00382D),
    secondary = Color(0xFFA8CDB7), onSecondary = Color(0xFF123827),
    background = Color(0xFF101815), surface = Color(0xFF19251F),
    onBackground = Color(0xFFE4EEE8), onSurface = Color(0xFFE4EEE8),
    surfaceContainerLowest = Color(0xFF101815), surfaceContainerLow = Color(0xFF1D2B23),
    surfaceContainer = Color(0xFF24352B), surfaceContainerHigh = Color(0xFF2B3E32),
    surfaceContainerHighest = Color(0xFF34483B),
    onSurfaceVariant = Color(0xFFB5C7BA), outlineVariant = Color(0xFF405548), outline = Color(0xFF8CA594),
    secondaryContainer = Color(0xFF2B4E3D), onSecondaryContainer = Color(0xFFD1EBDD),
)

@Composable
fun YouCommuterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
