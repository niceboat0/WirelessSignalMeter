package com.craxiom.networksurvey.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.craxiom.networksurvey.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * Shared chrome for form style modal bottom sheets (Watchlist add, SSID exclusion add, provider
 * color add), with a fixed title and actions around the scrollable form.
 *
 * Two deliberate behaviors:
 * - Only the content area scrolls. The title and button row stay fixed so Cancel and the confirm
 *   button remain visible when the IME pushes the sheet up or the content is tall.
 * - [onConfirm] does not dismiss the sheet. Callers that validate on confirm can keep the sheet
 *   open to show an error, and call [onDismiss] themselves on success.
 */
@Composable
fun NsFormBottomSheet(
    title: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AppBottomSheet(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                content()
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
                TextButton(
                    text = confirmText,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
