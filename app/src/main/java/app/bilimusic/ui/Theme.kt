package app.bilimusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bilimusic.data.Appearance

/** warm-restrained-ui 令牌：暖米白底 + 强调色，浅色/深色各一套。强调色、圆角、标题字体可由用户调整。 */
@Immutable
class Tokens(
    val textTertiary: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val bgSubtle: Color,
    val bgInset: Color,
    val radiusSm: Dp,
    val radiusMd: Dp,
    val radiusLg: Dp
)
val LocalTokens = staticCompositionLocalOf { baseTokens(false, 1) }

private fun baseTokens(dark: Boolean, corners: Int): Tokens {
    // 圆角永远只有三档（小/中/大），用户只能整体切换这三档的松紧
    val r = when (corners) { 0 -> Triple(4, 8, 12); 2 -> Triple(10, 16, 24); else -> Triple(6, 10, 16) }
    return if (dark) Tokens(Color(0xFF8F8C83), Color(0x14F2F0EA), Color(0x29F2F0EA), Color(0xFF3A3936), Color(0xFF1F1E1D), r.first.dp, r.second.dp, r.third.dp)
    else Tokens(Color(0xFF8A877F), Color(0x141F1E1D), Color(0x261F1E1D), Color(0xFFF3F1EA), Color(0xFFECE9E0), r.first.dp, r.second.dp, r.third.dp)
}

class AccentPreset(val name: String, val light: Color, val dark: Color)

/** 预设强调色：低饱和，且在白色按钮文字下对比度足够。第 0 项是默认的陶土橙。 */
val AccentPresets = listOf(
    AccentPreset("陶土", Color(0xFFC15F3C), Color(0xFFD9775A)),
    AccentPreset("灰蓝", Color(0xFF4F6F8F), Color(0xFF8FB0CF)),
    AccentPreset("苔绿", Color(0xFF4A7C59), Color(0xFF7FB48F)),
    AccentPreset("赭黄", Color(0xFF9E6516), Color(0xFFD9A94F)),
    AccentPreset("灰紫", Color(0xFF735F85), Color(0xFFA592B3))
)

const val CUSTOM_ACCENT = 5
private val Ink = Color(0xFF1F1E1D)

/** WCAG 对比度。 */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** 在按钮色上用白字还是深字：谁对比度高用谁。 */
fun onAccent(c: Color): Color = if (contrast(Color.White, c) >= contrast(Ink, c)) Color.White else Ink

/** 自定义颜色在页面底色上对比度不足 4.5 时，浅色模式逐步加深、深色模式逐步提亮，直到看得清。 */
fun ensureContrast(c: Color, bg: Color, dark: Boolean, min: Float = 4.5f): Color {
    var x = c
    var i = 0
    while (contrast(x, bg) < min && i < 30) { x = lerp(x, if (dark) Color.White else Color.Black, 0.06f); i++ }
    return x
}

/** 当前外观下实际使用的强调色。 */
fun accentFor(ap: Appearance, dark: Boolean): Color {
    if (ap.accent != CUSTOM_ACCENT) {
        val p = AccentPresets[ap.accent.coerceIn(AccentPresets.indices)]
        return if (dark) p.dark else p.light
    }
    val raw = Color.hsv(ap.hue.coerceIn(0f, 360f), if (dark) 0.40f else 0.55f, if (dark) 0.85f else 0.65f)
    return ensureContrast(raw, (if (dark) DarkBase else LightBase).background, dark)
}

private val LightBase = lightColorScheme(
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
private val DarkBase = darkColorScheme(
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

private fun scheme(dark: Boolean, accent: Color): ColorScheme {
    val base = if (dark) DarkBase else LightBase
    val container = lerp(base.background, accent, if (dark) 0.18f else 0.12f)
    val onContainer = if (dark) lerp(accent, Color.White, 0.25f) else lerp(accent, Color.Black, 0.3f)
    return base.copy(
        primary = accent, onPrimary = onAccent(accent),
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondaryContainer = container, onSecondaryContainer = onContainer
    )
}

private fun typography(serif: Boolean): Typography {
    val title = if (serif) FontFamily.Serif else FontFamily.SansSerif
    val sans = FontFamily.SansSerif
    return Typography(
        headlineMedium = TextStyle(fontFamily = title, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
        titleLarge = TextStyle(fontFamily = title, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
        titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontFamily = sans, fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 24.sp),
        bodySmall = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    )
}

@Composable
fun resolveDark(mode: Int): Boolean = when (mode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun AppTheme(ap: Appearance, content: @Composable () -> Unit) {
    val dark = resolveDark(ap.mode)
    val tokens = baseTokens(dark, ap.corners)
    CompositionLocalProvider(LocalTokens provides tokens) {
        MaterialTheme(
            colorScheme = scheme(dark, accentFor(ap, dark)),
            typography = typography(ap.serifTitles),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(tokens.radiusSm), small = RoundedCornerShape(tokens.radiusSm),
                medium = RoundedCornerShape(tokens.radiusMd),
                large = RoundedCornerShape(tokens.radiusLg), extraLarge = RoundedCornerShape(tokens.radiusLg)
            ),
            content = content
        )
    }
}
