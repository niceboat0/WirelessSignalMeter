package com.craxiom.networksurvey.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.ui.theme.LocalInterfaceStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PreferenceGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalInterfaceStyle.current
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(style.sectionSpacing)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = style.cardPadding).semantics { heading() },
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
            color = colors.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = style.cardRadius,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = colors.onSurface,
            ),
            content = content,
        )
    }
}

@Composable
internal fun PreferenceRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val style = LocalInterfaceStyle.current
    BasicComponent(
        modifier = modifier.heightIn(min = 48.dp),
        startAction = {
            Icon(icon, null, Modifier.padding(end = 8.dp).size(24.dp), tint = iconTint)
        },
        endActions = { trailing?.invoke() },
        insideMargin = PaddingValues(horizontal = style.cardPadding, vertical = style.parameterRowPadding + 6.dp),
        onClick = onClick,
        role = if (onClick != null) Role.Button else null,
        enabled = enabled,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!summary.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PreferenceLink(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val style = LocalInterfaceStyle.current
    ArrowPreference(
        title = title,
        summary = summary,
        modifier = Modifier.heightIn(min = 48.dp),
        startAction = {
            Icon(icon, null, Modifier.padding(end = 8.dp).size(24.dp), tint = iconTint)
        },
        insideMargin = PaddingValues(horizontal = style.cardPadding, vertical = style.parameterRowPadding + 6.dp),
        onClick = onClick,
    )
}

@Composable
internal fun PreferenceDivider() {
    val style = LocalInterfaceStyle.current
    HorizontalDivider(
        modifier = Modifier.padding(start = style.cardPadding + 40.dp, end = style.cardPadding),
        color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.55f),
    )
}

@Composable
internal fun PreferenceChevron() {
    Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
