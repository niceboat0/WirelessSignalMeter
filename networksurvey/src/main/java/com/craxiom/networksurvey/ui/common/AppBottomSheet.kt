package com.craxiom.networksurvey.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
internal fun AppBottomSheet(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    endAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    WindowBottomSheet(show = show, title = title, onDismissRequest = onDismissRequest, endAction = endAction) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}
