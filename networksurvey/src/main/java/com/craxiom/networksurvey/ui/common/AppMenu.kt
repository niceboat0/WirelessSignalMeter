package com.craxiom.networksurvey.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
internal fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    WindowListPopup(
        show = expanded,
        alignment = PopupPositionProvider.Align.End,
        enableWindowDim = false,
        onDismissRequest = onDismissRequest,
    ) {
        ListPopupColumn(content)
    }
}

@Composable
internal fun AppMenuItem(
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        onClick = onClick,
        enabled = enabled,
        role = Role.Button,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        endActions = { trailingIcon?.invoke() },
    ) {
        Text(title, color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
    }
}
