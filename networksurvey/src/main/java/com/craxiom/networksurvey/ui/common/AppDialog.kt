package com.craxiom.networksurvey.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AppDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismissAction: () -> Unit = onDismissRequest,
    confirmEnabled: Boolean = true,
    scrollContent: Boolean = true,
    content: @Composable () -> Unit,
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismissRequest) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val body = Modifier.fillMaxWidth().weight(1f, fill = false)
            Column(if (scrollContent) body.verticalScroll(rememberScrollState()) else body) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dismissText != null) {
                    TextButton(dismissText, onDismissAction, Modifier.weight(1f))
                }
                TextButton(
                    confirmText,
                    onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = confirmEnabled,
                )
            }
        }
    }
}
