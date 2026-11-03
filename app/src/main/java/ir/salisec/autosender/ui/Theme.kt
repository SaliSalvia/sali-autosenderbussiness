package ir.salisec.autosender.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

val BluePrimary = Color(0xFF176B87)
val GreenOk = Color(0xFF2E7D5B)
val RedErr = Color(0xFFB3261E)
val OrangeWarn = Color(0xFF9A5B00)
val DarkBg = Color(0xFF101820)
val DarkSurface = Color(0xFF18232D)

private val LightScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EAF1),
    onPrimaryContainer = Color(0xFF082D3A),
    secondary = Color(0xFF386A57),
    onSecondary = Color.White,
    tertiary = OrangeWarn,
    onTertiary = Color.White,
    error = RedErr,
    onError = Color.White,
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF16212A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF16212A),
    surfaceVariant = Color(0xFFEEF3F7),
    onSurfaceVariant = Color(0xFF465762)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF83CDE0),
    onPrimary = Color(0xFF003541),
    primaryContainer = Color(0xFF164858),
    onPrimaryContainer = Color(0xFFD0F1FA),
    secondary = Color(0xFF8BD0AD),
    onSecondary = Color(0xFF003822),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF452B00),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = DarkBg,
    onBackground = Color(0xFFE2EAF0),
    surface = DarkSurface,
    onSurface = Color(0xFFE2EAF0),
    surfaceVariant = Color(0xFF202D38),
    onSurfaceVariant = Color(0xFFB8C6D1)
)

/**
 * Professional light/dark Material 3 theme. The product copy is Persian, so the
 * layout direction is forced RTL regardless of the device locale (Thunderbird-
 * like mail clients keep one consistent reading direction).
 */
@Composable
fun SaliTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
            content = content
        )
    }
}
