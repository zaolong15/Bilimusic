package app.bilimusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** warm-restrained-ui 令牌：暖米白底 + 陶土橙强调色，浅色/深色各一套。 */
@Immutable
class Tokens(
    val textTertiary: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val bgSubtle: Color,
    val bgInset: Color,
    val success: Color,
    val successSoft: Color
)

private val LightTokens = Tokens(
    textTertiary = Color(0xFF8A877F), borderSubtle = Color(0x141F1E1D), borderDefault = Color(0x261F1E1D),
    bgSubtle = Color(0xFFF3F1EA), bgInset = Color(0xFFECE9E0),
    success = Color(0xFF4A7C59), successSoft = Color(0xFFE6F0E8)
)
private val DarkTokens = Tokens(
    textTertiary = Color(0xFF8F8C83), borderSubtle = Color(0x14F2F0EA), borderDefault = Color(0x29F2F0EA),
    bgSubtle = Color(0xFF3A3936), bgInset = Color(0xFF1F1E1D),
    success = Color(0xFF7FB48F), successSoft = Color(0xFF34403A)
)
val LocalTokens = staticCompositionLocalOf { LightTokens }

private val LightScheme = lightColorScheme(
    primary = Color(0xFFC15F3C), onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E7DF), onPrimaryContainer = Color(0xFF9A4A2D),
    secondary = Color(0xFF5E5D59), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E7DF), onSecondaryContainer = Color(0xFF9A4A2D),
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFF3F1EA), onSurfaceVariant = Color(0xFF5E5D59),
    surfaceTint = Color.Transparent,
    outline = Color(0x261F1E1D), outlineVariant = Color(0x141F1E1D),
    error = Color(0xFFB5403A), onError = Color.White,
    errorContainer = Color(0xFFF7E4E2), onErrorContainer = Color(0xFFB5403A),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF3F1EA)
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFD9775A), onPrimary = Color(0xFF1F1E1D),
    primaryContainer = Color(0xFF433329), onPrimaryContainer = Color(0xFFE58A6E),
    secondary = Color(0xFFBDBAB1), onSecondary = Color(0xFF1F1E1D),
    secondaryContainer = Color(0xFF433329), onSecondaryContainer = Color(0xFFE58A6E),
    background = Color(0xFF262624), onBackground = Color(0xFFF2F0EA),
    surface = Color(0xFF30302E), onSurface = Color(0xFFF2F0EA),
    surfaceVariant = Color(0xFF3A3936), onSurfaceVariant = Color(0xFFBDBAB1),
    surfaceTint = Color.Transparent,
    outline = Color(0x29F2F0EA), outlineVariant = Color(0x14F2F0EA),
    error = Color(0xFFE07A72), onError = Color(0xFF1F1E1D),
    errorContainer = Color(0xFF40322F), onErrorContainer = Color(0xFFE07A72),
    surfaceContainerLowest = Color(0xFF30302E), surfaceContainerLow = Color(0xFF30302E),
    surfaceContainer = Color(0xFF30302E), surfaceContainerHigh = Color(0xFF30302E),
    surfaceContainerHighest = Color(0xFF3A3936)
)

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
)

// 圆角只用三档：6 / 10 / 16
val RadiusSm = 6.dp
val RadiusMd = 10.dp
val RadiusLg = 16.dp
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(RadiusSm),
    small = RoundedCornerShape(RadiusSm),
    medium = RoundedCornerShape(RadiusMd),
    large = RoundedCornerShape(RadiusLg),
    extraLarge = RoundedCornerShape(RadiusLg)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalTokens provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
