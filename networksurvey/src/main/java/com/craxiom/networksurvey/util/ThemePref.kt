package com.craxiom.networksurvey.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableIntStateOf
import androidx.preference.PreferenceManager

/**
 * 主题偏好：浅色 / 深色 / 跟随系统 + Material You（Monet）动态取色开关。
 * 存储于默认 SharedPreferences（与 preferences.xml 的 Preference 打通）：
 *  - theme_mode ：ListPreference，存 String（"0"=浅色 "1"=深色 "2"=跟随系统）
 *  - wallpaper_colors_enabled ：SwitchPreferenceCompat，存 Boolean
 */
object ThemePref {
    const val MODE_LIGHT = 0
    const val MODE_DARK = 1
    const val MODE_SYSTEM = 2
    const val KEY_THEME = "theme_mode"
    // The old key was automatically set to true on install; require a fresh opt-in.
    const val KEY_MONET = "wallpaper_colors_enabled"
    const val KEY_SEED = "theme_seed"
    const val KEY_AMOLED = "amoled_enabled"
    private const val KEY_LIQUID_GLASS = "liquid_glass_navigation"
    private const val KEY_FLOATING_NAVIGATION = "floating_navigation"
    const val KEY_INTERFACE_STYLE = "interface_style"
    const val STYLE_CLASSIC = "classic"
    const val STYLE_MINIMAL = "minimal"
    const val STYLE_COMPACT = "compact"
    // KernelSU ColorPalette 扩展：配色风格 + 规格
    const val KEY_PALETTE_STYLE = "theme_palette_style"
    const val KEY_COLOR_SPEC = "theme_color_spec"

    // 进度条风格切换："miuix" 或 "material3"
    const val KEY_PROGRESS_BAR_STYLE = "progress_bar_style"
    const val PROGRESS_BAR_MIUIX = "miuix"
    const val PROGRESS_BAR_MATERIAL3 = "material3"

    // 默认配色风格/规格（与 KernelSU 默认一致：TonalSpot + SPEC_2021）
    const val DEFAULT_PALETTE_STYLE = "TonalSpot"
    const val DEFAULT_COLOR_SPEC = "SPEC_2021"

    /** 配色风格（PaletteStyle 枚举名，TonalSpot/Neutral/Vibrant/Expressive 等） */
    @JvmStatic
    fun getPaletteStyle(context: Context): String =
        try { prefs(context).getString(KEY_PALETTE_STYLE, DEFAULT_PALETTE_STYLE) ?: DEFAULT_PALETTE_STYLE }
        catch (_: ClassCastException) { DEFAULT_PALETTE_STYLE }

    @JvmStatic
    fun setPaletteStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_PALETTE_STYLE, style).apply()
        themeVersion.intValue++
    }

    /** 配色规格（ColorSpec.SpecVersion 枚举名），SPEC_2021 / SPEC_2025 */
    @JvmStatic
    fun getColorSpec(context: Context): String =
        try { prefs(context).getString(KEY_COLOR_SPEC, DEFAULT_COLOR_SPEC) ?: DEFAULT_COLOR_SPEC }
        catch (_: ClassCastException) { DEFAULT_COLOR_SPEC }

    @JvmStatic
    fun setColorSpec(context: Context, spec: String) {
        prefs(context).edit().putString(KEY_COLOR_SPEC, spec).apply()
        themeVersion.intValue++
    }

    /** 进度条风格：Miuix 或 Material3，默认 Miuix */
    @JvmStatic
    fun getProgressBarStyle(context: Context): String =
        try { prefs(context).getString(KEY_PROGRESS_BAR_STYLE, PROGRESS_BAR_MIUIX) ?: PROGRESS_BAR_MIUIX }
        catch (_: ClassCastException) { PROGRESS_BAR_MIUIX }

    @JvmStatic
    fun setProgressBarStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_PROGRESS_BAR_STYLE, style).apply()
        // 递增版本号，触发依赖进度条样式的 Composable 立即重组（无需等待导航刷新）
        themeVersion.intValue++
    }

    @JvmStatic
    fun isMiuixProgressBar(context: Context): Boolean =
        getProgressBarStyle(context) == PROGRESS_BAR_MIUIX

    /**
     * 主题版本号：每次主题偏好变更时递增，供 Composable 读取以触发重组。
     * NsTheme 读取此值后，当 apply() 被调用时版本号变化 → NsTheme 重组 → 新主题生效。
     */
    private val themeVersion = mutableIntStateOf(0)
    val currentThemeVersion: Int get() = themeVersion.intValue

    fun isLiquidGlassEnabled(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_LIQUID_GLASS, false) } catch (_: ClassCastException) { false }

    fun setLiquidGlassEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIQUID_GLASS, enabled).apply()
        themeVersion.intValue++
    }

    fun isFloatingNavigationEnabled(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_FLOATING_NAVIGATION, false) } catch (_: ClassCastException) { false }

    fun setFloatingNavigationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_NAVIGATION, enabled).apply()
        themeVersion.intValue++
    }

    fun getInterfaceStyle(context: Context): String {
        val style = try { prefs(context).getString(KEY_INTERFACE_STYLE, STYLE_CLASSIC) }
        catch (_: ClassCastException) { null }
        return style.takeIf { it == STYLE_CLASSIC || it == STYLE_MINIMAL || it == STYLE_COMPACT } ?: STYLE_CLASSIC
    }

    fun setInterfaceStyle(context: Context, style: String) {
        val selected = style.takeIf { it == STYLE_CLASSIC || it == STYLE_MINIMAL || it == STYLE_COMPACT }
            ?: STYLE_CLASSIC
        prefs(context).edit().putString(KEY_INTERFACE_STYLE, selected).apply()
        themeVersion.intValue++
    }

    // AMOLED 纯黑模式（深色模式下将 background/surface 全替换为 Color.Black，省电+极致对比度）
    fun isAmoled(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_AMOLED, false) } catch (_: ClassCastException) { false }

    @JvmStatic
    fun setAmoled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMOLED, enabled).apply()
        themeVersion.intValue++
    }

    // 自定义主题主色（静态色板）：关闭 Monet 后可手动选择。
    // 与 arrays.xml 的 theme_seed_values 保持一致：0=默认 1=蓝色 2=绿色 3=紫色 4=橙色 5=红色 6=粉色 7=青色
    const val SEED_DEFAULT = 0
    const val SEED_BLUE = 1
    const val SEED_GREEN = 2
    const val SEED_PURPLE = 3
    const val SEED_ORANGE = 4
    const val SEED_RED = 5
    const val SEED_PINK = 6
    const val SEED_CYAN = 7

    /** Material You 动态取色是否开启（默认关闭）。 */
    @JvmStatic
    fun isMonet(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_MONET, false) } catch (_: ClassCastException) { false }

    fun setMonet(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONET, enabled).apply()
        themeVersion.intValue++
    }

    /** 用户选定的自定义主题主色（关闭 Monet 时生效）。 */
    @JvmStatic
    fun getSeed(context: Context): Int {
        val sp = prefs(context)
        if (!sp.contains(KEY_SEED)) return SEED_DEFAULT
        // ListPreference 以 String 存储（"0"/"1"/"2"...）；兼容旧版 Int
        try {
            val s = sp.getString(KEY_SEED, null)
            if (s != null) return s.toIntOrNull()?.takeIf { it in SEED_DEFAULT..SEED_CYAN } ?: SEED_DEFAULT
        } catch (_: ClassCastException) {
        }
        return try {
            sp.getInt(KEY_SEED, SEED_DEFAULT).takeIf { it in SEED_DEFAULT..SEED_CYAN } ?: SEED_DEFAULT
        } catch (_: ClassCastException) {
            SEED_DEFAULT
        }
    }

    @JvmStatic
    fun setSeed(context: Context, seed: Int) {
        prefs(context).edit()
            .putString(KEY_SEED, seed.toString())
            .putBoolean(KEY_MONET, false)
            .apply()
        themeVersion.intValue++
    }

    /** Android 12（API 31）+ 才支持从系统壁纸提取动态色。 */
    @JvmStatic
    fun isDynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @JvmStatic
    fun get(context: Context): Int {
        val sp = prefs(context)
        if (!sp.contains(KEY_THEME)) return MODE_SYSTEM
        // preferences.xml 的 ListPreference 以 String 存储（"0"/"1"/"2"）；
        // 兼容旧版本 Int 存储，避免 ClassCastException
        try {
            val s = sp.getString(KEY_THEME, null)
            if (s != null) return s.toIntOrNull() ?: MODE_SYSTEM
        } catch (_: ClassCastException) {
        }
        return try {
            sp.getInt(KEY_THEME, MODE_SYSTEM)
        } catch (_: ClassCastException) {
            MODE_SYSTEM
        }
    }

    @JvmStatic
    fun set(context: Context, mode: Int) {
        prefs(context).edit().putString(KEY_THEME, mode.toString()).apply()
    }

    /** 应用夜间模式到 AppCompatDelegate（影响 XML 资源 + Compose 跟随），并递增版本号触发 Compose 重组。 */
    @JvmStatic
    fun apply(context: Context) {
        when (get(context)) {
            MODE_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            MODE_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        // 递增版本号，触发 NsTheme 重组（Monet/Seed/AMOLED 等非深浅模式切换需要此机制）
        themeVersion.intValue++
    }

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
}
