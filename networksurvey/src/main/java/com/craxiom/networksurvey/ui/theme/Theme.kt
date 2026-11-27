package com.craxiom.networksurvey.ui.theme

import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.craxiom.networksurvey.util.ThemePref
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

internal fun themeSeedColor(seed: Int): Color = when (seed) {
    ThemePref.SEED_BLUE -> Color(0xFF1565C0)
    ThemePref.SEED_GREEN -> Color(0xFF2E7D32)
    ThemePref.SEED_PURPLE -> Color(0xFF7B1FA2)
    ThemePref.SEED_ORANGE -> Color(0xFFE65100)
    ThemePref.SEED_RED -> Color(0xFFC62828)
    ThemePref.SEED_PINK -> Color(0xFFAD3259)
    ThemePref.SEED_CYAN -> Color(0xFF00696D)
    else -> Color(0xFF3482FF)
}

@Composable
fun NsTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean? = null,
    window: Window? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val version = ThemePref.currentThemeVersion
    val mode = remember(context, version) { ThemePref.get(context) }
    val dark = darkTheme ?: when (mode) {
        ThemePref.MODE_LIGHT -> false
        ThemePref.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val useWallpaper = ThemePref.isDynamicColorSupported() &&
        (dynamicColor ?: remember(context, version) { ThemePref.isMonet(context) })
    val seed = remember(context, version) { ThemePref.getSeed(context) }
    val paletteName = remember(context, version) { ThemePref.getPaletteStyle(context) }
    val specName = remember(context, version) { ThemePref.getColorSpec(context) }
    val interfaceStyle = remember(context, version) {
        InterfaceStyle.fromPreference(ThemePref.getInterfaceStyle(context))
    }
    val pureBlack = dark && remember(context, version) { ThemePref.isAmoled(context) }
    val neutral = remember(dark) { if (dark) miuixDarkColorScheme() else miuixLightColorScheme() }
    val accent = if (useWallpaper || seed != ThemePref.SEED_DEFAULT) {
        val controller = remember(dark, useWallpaper, seed, paletteName, specName) {
            ThemeController(
                colorSchemeMode = if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
                keyColor = if (useWallpaper) null else themeSeedColor(seed),
                paletteStyle = ThemePaletteStyle.entries.firstOrNull { it.name == paletteName }
                    ?: ThemePaletteStyle.TonalSpot,
                colorSpec = if (specName == "SPEC_2025") ThemeColorSpec.Spec2025 else ThemeColorSpec.Spec2021,
            )
        }
        controller.currentColors()
    } else {
        neutral
    }

    val page = if (pureBlack) Color.Black else neutral.surface
    val card = if (pureBlack) Color(0xFF171717) else neutral.surfaceContainer
    val miuixColors = remember(neutral, accent, page, card) {
        neutral.copy(
            background = card,
            surface = page,
            surfaceVariant = card,
            surfaceContainer = card,
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            primaryVariant = accent.primaryVariant,
            onPrimaryVariant = accent.onPrimaryVariant,
            primaryContainer = accent.primary.copy(alpha = 0.13f).compositeOver(card),
            onPrimaryContainer = accent.primary,
            tertiaryContainer = accent.primary.copy(alpha = 0.13f).compositeOver(card),
            onTertiaryContainer = accent.primary,
            tertiaryContainerVariant = accent.primary.copy(alpha = 0.20f).compositeOver(card),
            disabledPrimary = accent.disabledPrimary,
            disabledOnPrimary = accent.disabledOnPrimary,
            disabledPrimaryButton = accent.disabledPrimaryButton,
            disabledOnPrimaryButton = accent.disabledOnPrimaryButton,
            disabledPrimarySlider = accent.disabledPrimarySlider,
            sliderKeyPointForeground = accent.sliderKeyPointForeground,
        )
    }
    val materialColors = remember(miuixColors, dark, pureBlack) {
        miuixColors.toMaterialColorScheme(dark, pureBlack)
    }

    SideEffect {
        publishViewColors(materialColors)
        if (window != null) {
            val background = materialColors.background.toArgb()
            window.statusBarColor = background
            window.navigationBarColor = background
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MiuixTheme(colors = miuixColors) {
        // Use Miuix's rounded-shape fallback without compiling an AGSL corner mask.
        CompositionLocalProvider(
            LocalInterfaceStyle provides interfaceStyle,
            LocalSquircleEnabled provides false,
        ) {
            MaterialTheme(colorScheme = materialColors, typography = AppTypography) {
                // Miuix containers do not supply a Material foreground color.
                CompositionLocalProvider(LocalContentColor provides materialColors.onBackground, content = content)
            }
        }
    }
}

private fun Colors.toMaterialColorScheme(dark: Boolean, pureBlack: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val summary = onSurfaceVariantSummary.compositeOver(surfaceContainer)
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = summary,
        onSecondary = surfaceContainer,
        secondaryContainer = surfaceContainerHigh,
        onSecondaryContainer = onSurface,
        tertiary = onTertiaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = surface,
        onBackground = onSurface,
        surface = surfaceContainer,
        onSurface = onSurface,
        surfaceVariant = surfaceContainerHigh,
        onSurfaceVariant = summary,
        outline = outline,
        outlineVariant = dividerLine,
        scrim = Color.Black,
        surfaceDim = surface,
        surfaceBright = surfaceContainer,
        surfaceContainerLowest = surface,
        surfaceContainerLow = if (pureBlack) Color.Black else surfaceContainer,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}

private fun publishViewColors(colors: ColorScheme) {
    ActiveThemeColors.primary = ActiveThemeColors.toArgb(colors.primary)
    ActiveThemeColors.onPrimary = ActiveThemeColors.toArgb(colors.onPrimary)
    ActiveThemeColors.primaryContainer = ActiveThemeColors.toArgb(colors.primaryContainer)
    ActiveThemeColors.onPrimaryContainer = ActiveThemeColors.toArgb(colors.onPrimaryContainer)
    ActiveThemeColors.secondaryContainer = ActiveThemeColors.toArgb(colors.secondaryContainer)
    ActiveThemeColors.onSecondaryContainer = ActiveThemeColors.toArgb(colors.onSecondaryContainer)
    ActiveThemeColors.surface = ActiveThemeColors.toArgb(colors.surface)
    ActiveThemeColors.surfaceContainer = ActiveThemeColors.toArgb(colors.surfaceContainer)
    ActiveThemeColors.surfaceContainerLow = ActiveThemeColors.toArgb(colors.surfaceContainerLow)
    ActiveThemeColors.surfaceContainerHigh = ActiveThemeColors.toArgb(colors.surfaceContainerHigh)
    ActiveThemeColors.onSurface = ActiveThemeColors.toArgb(colors.onSurface)
    ActiveThemeColors.onSurfaceVariant = ActiveThemeColors.toArgb(colors.onSurfaceVariant)
    ActiveThemeColors.background = ActiveThemeColors.toArgb(colors.background)
}
