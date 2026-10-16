package yacht.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Extra colours the Material scheme has no slot for. */
data class GameColors(
    val me: Color,
    val opp: Color,
    val recommend: Color,
    val onRecommend: Color,
    val dieBody: Color,
    val diePip: Color,
    val dieOne: Color,
    val good: Color,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D0E),
    secondary = Color(0xFFB26A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE9B0),
    onSecondaryContainer = Color(0xFF3F2A00),
    tertiary = Color(0xFF1E5AA8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7E3FF),
    onTertiaryContainer = Color(0xFF001B3E),
    background = Color(0xFFF3F5EF),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE3E8E0),
    onSurfaceVariant = Color(0xFF434A42),
    outline = Color(0xFF8E978C),
    outlineVariant = Color(0xFFC3CABF),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8BD48F),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF1D5B22),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFFFC857),
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFE9B0),
    tertiary = Color(0xFFA9C7FF),
    onTertiary = Color(0xFF002F65),
    tertiaryContainer = Color(0xFF12447F),
    onTertiaryContainer = Color(0xFFD7E3FF),
    background = Color(0xFF0F1410),
    onBackground = Color(0xFFE1E4DD),
    surface = Color(0xFF161C17),
    onSurface = Color(0xFFE1E4DD),
    surfaceVariant = Color(0xFF263029),
    onSurfaceVariant = Color(0xFFC0C9BD),
    outline = Color(0xFF8A9488),
    outlineVariant = Color(0xFF414A41),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightGame = GameColors(
    me = Color(0xFF2E7D32), opp = Color(0xFF1E5AA8), recommend = Color(0xFFFFC94D), onRecommend = Color(0xFF3F2A00),
    dieBody = Color(0xFFFFFDF7), diePip = Color(0xFF222222), dieOne = Color(0xFFD32F2F), good = Color(0xFF2E7D32),
)

private val DarkGame = GameColors(
    me = Color(0xFF8BD48F), opp = Color(0xFFA9C7FF), recommend = Color(0xFFFFC857), onRecommend = Color(0xFF2B1D00),
    dieBody = Color(0xFFF2EFE6), diePip = Color(0xFF1C1C1C), dieOne = Color(0xFFD32F2F), good = Color(0xFF8BD48F),
)

val LocalGameColors = staticCompositionLocalOf { LightGame }

@Composable
fun YachtTheme(dark: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalGameColors provides if (dark) DarkGame else LightGame) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

val gameColors: GameColors
    @Composable get() = LocalGameColors.current
