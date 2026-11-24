package com.craxiom.networksurvey.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.R

/**
 * 软件更新提示弹窗：展示新版本信息、更新日志，下载统一经过安全校验。
 */
@Composable
fun UpdateDialog(
    version: RemoteVersion,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onIgnore: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "发现新版本 v${version.versionName}",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (version.title.isNotEmpty()) {
                    Text(
                        text = version.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (version.releaseNotes.isNotEmpty()) {
                    Text(
                        text = "更新日志：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    version.releaseNotes.forEach { note ->
                        Text(
                            text = "• $note",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload) {
                Text(if (UpdateDownloader.isReady(version)) stringResource(R.string.update_install_now) else "立即更新")
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onIgnore != null && !version.forceUpdate) {
                    TextButton(onClick = onIgnore) {
                        Text("忽略此版本")
                    }
                }
                if (!version.forceUpdate) {
                    TextButton(onClick = onDismiss) {
                        Text("稍后再说")
                    }
                }
            }
        }
    )
}
