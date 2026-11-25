package com.craxiom.networksurvey.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.util.ThemePref

enum class InterfaceStyle(
    val preferenceValue: String,
    @param:StringRes val labelRes: Int,
    val cardRadius: Dp,
    val cardPadding: Dp,
    val sectionSpacing: Dp,
    val parameterRowPadding: Dp,
) {
    CLASSIC(ThemePref.STYLE_CLASSIC, R.string.interface_style_classic, 24.dp, 16.dp, 12.dp, 8.dp),
    MINIMAL(ThemePref.STYLE_MINIMAL, R.string.interface_style_minimal, 8.dp, 16.dp, 12.dp, 8.dp),
    COMPACT(ThemePref.STYLE_COMPACT, R.string.interface_style_compact, 4.dp, 12.dp, 6.dp, 5.dp);

    companion object {
        fun fromPreference(value: String): InterfaceStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: CLASSIC
    }
}

val LocalInterfaceStyle = staticCompositionLocalOf { InterfaceStyle.CLASSIC }
