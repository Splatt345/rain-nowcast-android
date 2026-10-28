package nowcast.app.ui

import android.content.Context
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import nowcast.app.App
import nowcast.app.AppColors
import nowcast.app.DarkMode
import nowcast.app.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Fallback palette (no dynamic colour): storm blue, wet-slate surfaces, a teal for "clearing".
internal val AppLight = lightColorScheme(
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

internal val AppDark = darkColorScheme(
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

/**
 * Light and dark schemes around one accent: HSL tones of its hue, a hue-shifted secondary and tertiary,
 * surfaces faintly tinted. HSL, not Material's HCT tones, so contrast on odd accents (pure yellow)
 * is not tuned.
 */
fun seedScheme(seed: Int, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(seed or 0xFF000000.toInt(), it) }
    val h = hsl[0]; val sat = hsl[1].coerceAtLeast(0.15f)
    fun t(l: Float, s: Float = sat, dh: Float = 0f) = Color(ColorUtils.HSLToColor(floatArrayOf((h + dh + 360f) % 360f, s.coerceIn(0f, 1f), l)))
    val s2 = sat * 0.5f; val n = minOf(sat * 0.2f, 0.16f)
    return if (!dark) AppLight.copy(
        primary = t(0.40f), onPrimary = Color.White, primaryContainer = t(0.90f), onPrimaryContainer = t(0.12f),
        inversePrimary = t(0.80f), surfaceTint = t(0.40f),
        secondary = t(0.36f, s2, 30f), onSecondary = Color.White, secondaryContainer = t(0.90f, s2, 30f), onSecondaryContainer = t(0.12f, s2, 30f),
        tertiary = t(0.40f, sat, 70f), onTertiary = Color.White, tertiaryContainer = t(0.90f, sat, 70f), onTertiaryContainer = t(0.12f, sat, 70f),
        background = t(0.975f, n), onBackground = t(0.10f, n), surface = t(0.975f, n), onSurface = t(0.10f, n),
        surfaceVariant = t(0.90f, n), onSurfaceVariant = t(0.32f, n),
        surfaceContainerLowest = Color.White, surfaceContainerLow = t(0.955f, n), surfaceContainer = t(0.935f, n),
        surfaceContainerHigh = t(0.915f, n), surfaceContainerHighest = t(0.895f, n),
        outline = t(0.50f, n), outlineVariant = t(0.80f, n),
    ) else AppDark.copy(
        primary = t(0.80f), onPrimary = t(0.20f), primaryContainer = t(0.30f), onPrimaryContainer = t(0.90f),
        inversePrimary = t(0.40f), surfaceTint = t(0.80f),
        secondary = t(0.78f, s2, 30f), onSecondary = t(0.18f, s2, 30f), secondaryContainer = t(0.28f, s2, 30f), onSecondaryContainer = t(0.90f, s2, 30f),
        tertiary = t(0.80f, sat, 70f), onTertiary = t(0.20f, sat, 70f), tertiaryContainer = t(0.30f, sat, 70f), onTertiaryContainer = t(0.90f, sat, 70f),
        background = t(0.06f, n), onBackground = t(0.90f, n), surface = t(0.06f, n), onSurface = t(0.90f, n),
        surfaceVariant = t(0.20f, n), onSurfaceVariant = t(0.75f, n),
        surfaceContainerLowest = t(0.04f, n), surfaceContainerLow = t(0.09f, n), surfaceContainer = t(0.11f, n),
        surfaceContainerHigh = t(0.14f, n), surfaceContainerHighest = t(0.18f, n),
        outline = t(0.58f, n), outlineVariant = t(0.28f, n),
    )
}

/** (light, dark) for the app's colour choice; wallpaper colours need Android 12, before that it is Rain. */
fun appSchemes(ctx: Context, s: Settings): Pair<ColorScheme, ColorScheme> = when {
    s.appColors == AppColors.CUSTOM -> seedScheme(s.accent, false) to seedScheme(s.accent, true)
    s.appColors == AppColors.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        dynamicLightColorScheme(ctx) to dynamicDarkColorScheme(ctx)
    else -> AppLight to AppDark
}

@Composable
fun NowcastTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val s by (ctx.applicationContext as App).repo.prefs.settings.collectAsState()
    val dark = when (s.darkMode) { DarkMode.SYSTEM -> isSystemInDarkTheme(); DarkMode.LIGHT -> false; DarkMode.DARK -> true }
    val (light, night) = remember(s.appColors, s.accent) { appSchemes(ctx, s) }
    // status-bar icons follow the app's mode, not the system's, when the mode is forced
    LaunchedEffect(dark) {
        val clear = android.graphics.Color.TRANSPARENT
        (ctx as? ComponentActivity)?.enableEdgeToEdge(
            if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
            if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear))
    }
    MaterialTheme(colorScheme = if (dark) night else light, typography = AppType, content = content)
}
