package nowcast.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Fallback palette (no dynamic colour): storm blue, wet-slate surfaces, a teal for "clearing".
private val Light = lightColorScheme(
    primary = Color(0xFF1F5FA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF), onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF2E7D7B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8EEEC), onSecondaryContainer = Color(0xFF00201F),
    tertiary = Color(0xFF6A4FB8), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DDFF), onTertiaryContainer = Color(0xFF22005D),
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF151B23),
    surface = Color(0xFFF6F8FB), onSurface = Color(0xFF151B23),
    surfaceVariant = Color(0xFFDDE5EF), onSurfaceVariant = Color(0xFF47515E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainer = Color(0xFFEAEFF5), surfaceContainerHigh = Color(0xFFE4EAF1),
    surfaceContainerHighest = Color(0xFFDEE5EE),
    outline = Color(0xFF77808C), outlineVariant = Color(0xFFC6CED9),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC7FF), onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF16477F), onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF8CD5D2), onSecondary = Color(0xFF003736),
    secondaryContainer = Color(0xFF1C4F4D), onSecondaryContainer = Color(0xFFC8EEEC),
    tertiary = Color(0xFFCDBDFF), onTertiary = Color(0xFF381E87),
    tertiaryContainer = Color(0xFF50379E), onTertiaryContainer = Color(0xFFE8DDFF),
    background = Color(0xFF0E141B), onBackground = Color(0xFFE1E6EE),
    surface = Color(0xFF0E141B), onSurface = Color(0xFFE1E6EE),
    surfaceVariant = Color(0xFF2A3440), onSurfaceVariant = Color(0xFFB8C2CF),
    surfaceContainerLowest = Color(0xFF090E13), surfaceContainerLow = Color(0xFF141B23),
    surfaceContainer = Color(0xFF18202A), surfaceContainerHigh = Color(0xFF202934),
    surfaceContainerHighest = Color(0xFF2A3440),
    outline = Color(0xFF8A94A1), outlineVariant = Color(0xFF3B4653),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
)

/** Numbers line up in columns and do not jitter while they tick. */
private const val TNUM = "tnum"

private val Base = Typography()
private val AppType = Typography(
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Medium, fontSize = 48.sp, lineHeight = 52.sp,
        letterSpacing = (-0.03).em, fontFeatureSettings = TNUM),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 30.sp,
        letterSpacing = (-0.01).em),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.04.em, fontFeatureSettings = TNUM),
    labelSmall = Base.labelSmall.copy(fontFeatureSettings = TNUM),
    bodyMedium = Base.bodyMedium.copy(fontFeatureSettings = TNUM),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
)

/** Small all-caps section label. */
val Overline: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Default, letterSpacing = 0.1.em)

@Composable
fun NowcastTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppType, content = content)
}
