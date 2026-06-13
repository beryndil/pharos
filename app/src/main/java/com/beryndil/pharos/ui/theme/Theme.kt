package com.beryndil.pharos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.beryndil.pharos.settings.ThemeMode

// Restrained, fixed brand scheme (DESIGN.md): calm teal accent on a neutral canvas, no dynamic
// color. Deliberately NOT Material baseline purple.
private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = ErrorLight,
    onError = OnErrorLight,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = ErrorDark,
    onError = OnErrorDark,
)

/**
 * The Pharos app theme.
 *
 * @param themeMode Resolved from [com.beryndil.pharos.settings.AppearanceRepository]; defaults to
 *   [ThemeMode.SYSTEM] so that callers that have not yet read the persisted preference get a
 *   sensible appearance.
 * @param textScale In-app font scale factor, multiplicative on top of the system font scale (sp).
 *   Applied to every MaterialTheme typography style. 1.0 = default (no change).
 *   Sourced from [com.beryndil.pharos.settings.TextScale.factor].
 */
@Composable
fun PharosTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    textScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Recompute the scaled typography only when the factor actually changes. scaleTypography() is a
    // pure function so this is safe to remember purely on the factor value.
    val typography = remember(textScale) { scaleTypography(textScale) }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = typography,
        content = content,
    )
}

/**
 * Returns a [Typography] with every style's [androidx.compose.ui.unit.TextUnit] scaled by [factor].
 *
 * Because the base values are in sp and the multiplication preserves sp units, the result is still
 * in sp — the scaling stacks multiplicatively with the system font scale and never replaces it.
 * Factor 1.0 returns the Material3 default [Typography] instance without allocation overhead.
 */
internal fun scaleTypography(factor: Float): Typography {
    if (factor == 1.0f) return Typography()
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * factor),
        displayMedium = base.displayMedium.copy(fontSize = base.displayMedium.fontSize * factor),
        displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize * factor),
        headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize * factor),
        headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * factor),
        headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize * factor),
        titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * factor),
        titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * factor),
        titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * factor),
        bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * factor),
        bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * factor),
        bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * factor),
        labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * factor),
        labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * factor),
        labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * factor),
    )
}
