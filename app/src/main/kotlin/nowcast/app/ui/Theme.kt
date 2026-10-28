package nowcast.app.ui

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import nowcast.app.*
import nowcast.app.data.*

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
    val hue = hsl[0]; val seedSat = hsl[1].coerceAtLeast(0.15f)
    fun tone(lightness: Float, saturation: Float = seedSat, hueShift: Float = 0f) = Color(ColorUtils.HSLToColor(floatArrayOf((hue + hueShift + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)))
    val mutedSat = seedSat * 0.5f; val neutralSat = minOf(seedSat * 0.2f, 0.16f)
    return if (!dark) AppLight.copy(
        primary = tone(0.40f), onPrimary = Color.White, primaryContainer = tone(0.90f), onPrimaryContainer = tone(0.12f),
        inversePrimary = tone(0.80f), surfaceTint = tone(0.40f),
        secondary = tone(0.36f, mutedSat, 30f), onSecondary = Color.White, secondaryContainer = tone(0.90f, mutedSat, 30f), onSecondaryContainer = tone(0.12f, mutedSat, 30f),
        tertiary = tone(0.40f, seedSat, 70f), onTertiary = Color.White, tertiaryContainer = tone(0.90f, seedSat, 70f), onTertiaryContainer = tone(0.12f, seedSat, 70f),
        background = tone(0.975f, neutralSat), onBackground = tone(0.10f, neutralSat), surface = tone(0.975f, neutralSat), onSurface = tone(0.10f, neutralSat),
        surfaceVariant = tone(0.90f, neutralSat), onSurfaceVariant = tone(0.32f, neutralSat),
        surfaceContainerLowest = Color.White, surfaceContainerLow = tone(0.955f, neutralSat), surfaceContainer = tone(0.935f, neutralSat),
        surfaceContainerHigh = tone(0.915f, neutralSat), surfaceContainerHighest = tone(0.895f, neutralSat),
        outline = tone(0.50f, neutralSat), outlineVariant = tone(0.80f, neutralSat),
    ) else AppDark.copy(
        primary = tone(0.80f), onPrimary = tone(0.20f), primaryContainer = tone(0.30f), onPrimaryContainer = tone(0.90f),
        inversePrimary = tone(0.40f), surfaceTint = tone(0.80f),
        secondary = tone(0.78f, mutedSat, 30f), onSecondary = tone(0.18f, mutedSat, 30f), secondaryContainer = tone(0.28f, mutedSat, 30f), onSecondaryContainer = tone(0.90f, mutedSat, 30f),
        tertiary = tone(0.80f, seedSat, 70f), onTertiary = tone(0.20f, seedSat, 70f), tertiaryContainer = tone(0.30f, seedSat, 70f), onTertiaryContainer = tone(0.90f, seedSat, 70f),
        background = tone(0.06f, neutralSat), onBackground = tone(0.90f, neutralSat), surface = tone(0.06f, neutralSat), onSurface = tone(0.90f, neutralSat),
        surfaceVariant = tone(0.20f, neutralSat), onSurfaceVariant = tone(0.75f, neutralSat),
        surfaceContainerLowest = tone(0.04f, neutralSat), surfaceContainerLow = tone(0.09f, neutralSat), surfaceContainer = tone(0.11f, neutralSat),
        surfaceContainerHigh = tone(0.14f, neutralSat), surfaceContainerHighest = tone(0.18f, neutralSat),
        outline = tone(0.58f, neutralSat), outlineVariant = tone(0.28f, neutralSat),
    )
}

/** (light, dark) for the app's colour choice; wallpaper colours need Android 12, before that it is Rain. */
fun appSchemes(ctx: Context, settings: Settings): Pair<ColorScheme, ColorScheme> = when {
    settings.appColors == AppColors.CUSTOM -> seedScheme(settings.accent, false) to seedScheme(settings.accent, true)
    settings.appColors == AppColors.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        dynamicLightColorScheme(ctx) to dynamicDarkColorScheme(ctx)
    else -> AppLight to AppDark
}

@Composable
fun NowcastTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val settings by (ctx.applicationContext as App).repo.prefs.settings.collectAsState()
    val dark = when (settings.darkMode) { DarkMode.SYSTEM -> isSystemInDarkTheme(); DarkMode.LIGHT -> false; DarkMode.DARK -> true }
    val (light, night) = remember(settings.appColors, settings.accent) { appSchemes(ctx, settings) }
    // status-bar icons follow the app's mode, not the system's, when the mode is forced
    LaunchedEffect(dark) {
        val clear = android.graphics.Color.TRANSPARENT
        (ctx as? ComponentActivity)?.enableEdgeToEdge(
            if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
            if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear))
    }
    MaterialTheme(colorScheme = if (dark) night else light, typography = AppType, content = content)
}
