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
import com.craxiom.networksurvey.db.recallSurveyLocationMode
import com.craxiom.networksurvey.model.SurveyLocationMode
import com.craxiom.networksurvey.model.SurveyTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.craxiom.networksurvey.db.recallSurveyRecordingFormat
import com.craxiom.networksurvey.ui.rootdiag.DiagLogFormat
import com.craxiom.networksurvey.ui.rootdiag.DiagRecordingPolicy

@Composable
internal fun SurveyTaskBar() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf<SurveyTaskStore?>(null) }
    var task by remember { mutableStateOf<SurveyTask?>(null) }
    var edit by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var needLog by remember { mutableStateOf(false) }
    var logFormat by remember { mutableStateOf(DiagLogFormat.Qmdl) }
    var rememberedFormat by remember { mutableStateOf<String?>(null) }
    var locationMode by remember { mutableStateOf(SurveyLocationMode.DT) }
    var rememberedMode by remember { mutableStateOf<SurveyLocationMode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try {
            val repository = withContext(Dispatchers.IO) { SurveyTaskStore.get(context) }
            store = repository
            repository.active.collect { next ->
                task = next
                val recalled = next?.let {
                    withContext(Dispatchers.IO) {
                        recallSurveyLocationMode(context, it.id) to DiagRecordingPolicy.recordingFormatLabel(
                            if (it.needLog) recallSurveyRecordingFormat(context, it.id) else null,
                        )
                    }
                }
                rememberedMode = recalled?.first
                rememberedFormat = recalled?.second
            }
        } catch (_: android.database.SQLException) { error = true }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(task?.name ?: stringResource(R.string.road_task_idle), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                task?.let {
                    val logLabel = if (it.needLog) {
                        rememberedFormat?.let { name -> stringResource(R.string.road_task_status_log_format, name) }
                            ?: stringResource(R.string.road_task_status_log_on)
                    } else stringResource(R.string.road_task_status_log_off)
                    val status = listOfNotNull(
                        stringResource(R.string.road_task_running),
                        rememberedMode?.label,
                        logLabel,
                    ).joinToString(" · ")
                    Text(status, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task == null) Button(enabled = !busy && store != null, onClick = {
                name = ""
                needLog = false
                logFormat = DiagLogFormat.Qmdl
                locationMode = SurveyLocationMode.DT
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
        if (task != null) Text(
            stringResource(R.string.road_task_active_hint),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.road_map_mark_hint),
            Modifier.padding(top = 2.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error) Text("无法保存任务，请检查存储空间", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    if (edit) AlertDialog(
        onDismissRequest = { if (!busy) edit = false },
        title = { Text(stringResource(R.string.road_task_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.road_task_name)) }, singleLine = true)
                Text("位置记法")
                Text(
                    stringResource(
                        if (locationMode == SurveyLocationMode.DT) R.string.dt_location_summary
                        else R.string.cqt_location_summary,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SurveyLocationMode.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = locationMode == option,
                            onClick = { locationMode = option },
                            shape = SegmentedButtonDefaults.itemShape(index, SurveyLocationMode.entries.size),
                            enabled = !busy,
                        ) { Text(option.label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.road_task_log_title))
                        Text(
                            stringResource(if (needLog) R.string.road_task_log_on_hint else R.string.road_task_log_off_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = needLog, onCheckedChange = { needLog = it }, enabled = !busy)
                }
                if (needLog) {
                    Text(stringResource(R.string.road_task_format))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = logFormat == DiagLogFormat.Qmdl,
                            onClick = { logFormat = DiagLogFormat.Qmdl },
                            enabled = !busy,
                        )
                        Text(stringResource(R.string.diag_qmdl_format_qmdl))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = logFormat == DiagLogFormat.Dlf,
                            onClick = { logFormat = DiagLogFormat.Dlf },
                            enabled = !busy,
                        )
                        Text(stringResource(R.string.diag_qmdl_format_dlf))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        store?.start(name, needLog, locationMode, if (needLog) logFormat.name else null)
                    }
                    edit = false
                }
                catch (_: android.database.SQLException) { error = true }
                finally { busy = false }
            }
        }) { Text(stringResource(if (needLog) R.string.road_task_create_and_record else R.string.road_task_create_confirm)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { edit = false }) { Text("取消") } },
    )
}
