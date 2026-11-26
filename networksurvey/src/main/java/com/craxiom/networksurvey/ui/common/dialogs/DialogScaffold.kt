package com.craxiom.networksurvey.ui.common.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Shared Miuix dialog with a scrollable body and fixed actions.
 * @param title the dialog title.
 * @param onDismissRequest invoked when the user dismisses the dialog (back press / outside tap when
 * cancelable, or a button calling it).
 * @param confirmButton the primary action button slot (placed on the right).
 * @param dismissButton the optional secondary action button slot (placed on the left).
 * @param icon an optional leading icon shown before the body.
 * @param iconTint the tint for [icon]; [Color.Unspecified] uses the default content color.
 * @param cancelable when false, the dialog cannot be dismissed by back press or outside tap.
 * @param body the dialog content, already wrapped in a scrollable [Column].
 */
@Composable
internal fun NsAlertDialogScaffold(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    cancelable: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
) {
    WindowDialog(
        show = true,
        onDismissRequest = onDismissRequest.takeIf { cancelable },
        modifier = modifier,
        title = title,
    ) {
        val contentColor = MaterialTheme.colorScheme.onSurface
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = if (iconTint == Color.Unspecified) contentColor else iconTint,
                        )
                    }
                    body()
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/**
 * A checkbox with a text label, used by dialogs that need inline boolean options (for example a
 * "don't show again" toggle). Shared so every dialog renders these the same way.
 */
@Composable
internal fun LabeledCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = ToggleableState(checked),
            onClick = { onCheckedChange(!checked) },
            enabled = enabled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}
