package ir.salisec.autosender.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BluePrimary = Color(0xFF1E88E5)
val GreenOk = Color(0xFF43A047)
val RedErr = Color(0xFFE53935)
val OrangeWarn = Color(0xFFFB8C00)
val DarkBg = Color(0xFF0E1116)
val DarkSurface = Color(0xFF161B22)

@Composable
fun SaliTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BluePrimary,
            secondary = GreenOk,
            error = RedErr,
            tertiary = OrangeWarn,
            background = DarkBg,
            surface = DarkSurface
        ),
        content = content
    )
}
