package com.craxiom.networksurvey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.db.SurveyTaskStore
import com.craxiom.networksurvey.model.SurveyTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SurveyTaskBar() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf<SurveyTaskStore?>(null) }
    var task by remember { mutableStateOf<SurveyTask?>(null) }
    var edit by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var needLog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try {
            val repository = withContext(Dispatchers.IO) { SurveyTaskStore.get(context) }
            store = repository
            repository.active.collect { task = it }
        } catch (_: android.database.SQLException) { error = true }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(task?.name ?: stringResource(R.string.road_task_idle), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                task?.let {
                    Text(stringResource(R.string.road_task_running), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task == null) Button(enabled = !busy && store != null, onClick = {
                name = ""
                needLog = false
                edit = true
            }) { Text(stringResource(R.string.road_task_new)) }
            else OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { store?.finish() } }
                    catch (_: android.database.SQLException) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.road_task_finish)) }
        }
        if (error) Text("无法保存任务，请检查存储空间", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    if (edit) AlertDialog(
        onDismissRequest = { if (!busy) edit = false },
        title = { Text("新建采集任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("任务名称") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("录制 QMDL")
                        Text("仅此任务期间录制基带日志", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = needLog, onCheckedChange = { needLog = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { withContext(Dispatchers.IO) { store?.start(name, needLog) }; edit = false }
                catch (_: android.database.SQLException) { error = true }
                finally { busy = false }
            }
        }) { Text("开始") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { edit = false }) { Text("取消") } },
    )
}
