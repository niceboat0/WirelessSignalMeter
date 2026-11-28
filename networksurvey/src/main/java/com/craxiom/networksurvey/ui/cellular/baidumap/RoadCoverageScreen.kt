package com.craxiom.networksurvey.ui.cellular.baidumap

import android.graphics.Color as MapColor
import android.os.SystemClock
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import top.yukonga.miuix.kmp.basic.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baidu.mapapi.map.Dot
import com.baidu.mapapi.map.DotOptions
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.model.LatLngBounds
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.common.AppDialog
import com.craxiom.networksurvey.ui.common.AppMenu
import com.craxiom.networksurvey.ui.common.AppMenuItem
import top.yukonga.miuix.kmp.basic.Scaffold
import com.craxiom.networksurvey.db.MapPointDbHelper
import com.craxiom.networksurvey.db.SurveyTaskStore
import com.craxiom.networksurvey.db.existingTaskForMapTracking
import com.craxiom.networksurvey.db.recallSurveyLocationMode
import com.craxiom.networksurvey.db.toJson
import com.craxiom.networksurvey.model.SurveyLocationMode
import com.craxiom.networksurvey.model.SurveyTask
import com.craxiom.networksurvey.ui.CqtFloorPlanPanel
import com.craxiom.networksurvey.ui.SurveyTaskBar
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import com.craxiom.networksurvey.ui.rootdiag.TaskRecordingControls
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.util.CoordConverter
import com.craxiom.networksurvey.util.SignalGrade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun RoadCoverageScreen(rootDiag: RootDiagViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val points = remember(context) { MapPointDbHelper.getInstance(context) }
    var tracks by remember { mutableStateOf<List<RoadTrack>>(emptyList()) }
    var selectedTrack by remember { mutableIntStateOf(0) }
    var selectedSample by remember { mutableIntStateOf(0) }
    var focusSample by remember { mutableIntStateOf(-1) }
    var chooseTrack by remember { mutableStateOf(false) }
    var showWeakSections by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var sinr by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }
    var limited by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(MapTrack.isEnabled(context)) }
    var locationMode by remember { mutableStateOf<SurveyLocationMode?>(null) }
    var floorPlanVisible by remember { mutableStateOf(true) }
    var replayTaskId by remember { mutableStateOf<String?>(null) }
    var taskHistory by remember { mutableStateOf<List<SurveyTask>>(emptyList()) }
    var chooseTask by remember { mutableStateOf(false) }
    var replayMenu by remember { mutableStateOf(false) }
    var timeline by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf<List<RoadEventLink>>(emptyList()) }
    var eventLimited by remember { mutableStateOf(false) }
    var eventError by remember { mutableStateOf(false) }
    var eventRefresh by remember { mutableIntStateOf(0) }
    var selectedEvent by remember { mutableStateOf<String?>(null) }
    val track = tracks.getOrNull(selectedTrack)
    val sample = track?.samples?.getOrNull(selectedSample)
    var report by remember(track) { mutableStateOf<RoadReport?>(null) }
    LaunchedEffect(track) {
        report = track?.let { withContext(Dispatchers.Default) { analyzeRoad(it.samples) } }
    }

    fun replaceTracks(next: List<RoadTrack>, fromFile: Boolean, isLimited: Boolean) {
        playing = false
        selectedTrack = 0
        selectedSample = 0
        focusSample = -1
        imported = fromFile
        limited = isLimited
        tracks = next
        replayTaskId = next.firstOrNull()?.taskId
        selectedEvent = null
    }
    fun launchIo(error: Int, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }
    fun loadSaved() = launchIo(R.string.road_read_failed) {
        val (next, isLimited, tasks) = withContext(Dispatchers.IO) {
            val recent = points.getRecentForReplay(ROAD_SAMPLE_LIMIT + 1)
            Triple(roadTracks(recent.take(ROAD_SAMPLE_LIMIT).asReversed().mapNotNull { it.toRoadSample() }),
                recent.size > ROAD_SAMPLE_LIMIT, SurveyTaskStore.get(context).recentTasks())
        }
        replaceTracks(next, fromFile = false, isLimited = isLimited)
        taskHistory = tasks
        if (next.isEmpty()) replayTaskId = tasks.firstOrNull()?.id
        eventRefresh++
    }
    LaunchedEffect(points) { loadSaved() }
    LaunchedEffect(context) {
        val repository = withContext(Dispatchers.IO) { SurveyTaskStore.get(context) }
        repository.active.collect { next ->
            locationMode = next?.let { withContext(Dispatchers.IO) { recallSurveyLocationMode(context, it.id) } }
        }
    }
    LaunchedEffect(timeline, replayTaskId, imported) {
        if (!timeline || imported) return@LaunchedEffect
        try {
            val store = withContext(Dispatchers.IO) { SurveyTaskStore.get(context) }
            store.revision.collect {
                delay(400)
                val (next, isLimited) = withContext(Dispatchers.IO) {
                    val recent = points.getRecentForReplay(ROAD_SAMPLE_LIMIT + 1)
                    roadTracks(recent.take(ROAD_SAMPLE_LIMIT).asReversed().mapNotNull { it.toRoadSample() }) to
                        (recent.size > ROAD_SAMPLE_LIMIT)
                }
                if (!timeline) return@collect
                val previous = tracks.getOrNull(selectedTrack)
                val previousTime = previous?.samples?.getOrNull(selectedSample)?.time
                val index = next.indices.filter {
                    next[it].taskId == replayTaskId && (previous == null ||
                        next[it].samples.first().sourceKey == previous.samples.first().sourceKey)
                }.minByOrNull { kotlin.math.abs(next[it].startTime - (previous?.startTime ?: next[it].startTime)) } ?: -1
                selectedTrack = index
                selectedSample = if (previousTime == null) 0 else next.getOrNull(index)?.samples?.indices
                    ?.minByOrNull { kotlin.math.abs(next[index].samples[it].time - previousTime) } ?: 0
                if (focusSample >= 0) focusSample = selectedSample
                tracks = next
                limited = isLimited
                eventRefresh++
            }
        } catch (_: android.database.SQLException) { eventError = true }
    }
    LaunchedEffect(replayTaskId, imported, tracks, eventRefresh) {
        links = emptyList()
        eventLimited = false
        eventError = false
        val id = replayTaskId ?: return@LaunchedEffect
        if (imported) return@LaunchedEffect
        try {
            val events = withContext(Dispatchers.IO) { SurveyTaskStore.get(context).events(id) }
            eventLimited = events.size > SurveyTaskStore.EVENT_LIMIT
            links = withContext(Dispatchers.Default) { linkRoadEvents(tracks, events.takeLast(SurveyTaskStore.EVENT_LIMIT)) }
        } catch (_: android.database.SQLException) { eventError = true }
    }

    fun selectEvent(link: RoadEventLink) {
        playing = false
        selectedEvent = link.event.id
        if (link.trackIndex != null && link.sampleIndex != null) {
            selectedTrack = link.trackIndex
            selectedSample = link.sampleIndex
            focusSample = link.sampleIndex
        }
    }

    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchIo(R.string.road_import_failed) {
            val next = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException()
                roadTracks(input.bufferedReader(Charsets.UTF_8).use(::readRoadCsv))
            }
            if (next.isEmpty()) throw RoadCsvFormatException()
            replaceTracks(next, fromFile = true, isLimited = false)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && track != null) launchIo(R.string.road_export_failed) {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri) ?: throw IOException()
                output.bufferedWriter(Charsets.UTF_8).use { writeRoadCsv(it, track.samples) }
            }
            Toast.makeText(context, R.string.road_exported, Toast.LENGTH_SHORT).show()
        }
    }
    val exportEvents = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        val id = replayTaskId
        if (uri != null && id != null) launchIo(R.string.road_export_failed) {
            withContext(Dispatchers.IO) {
                val store = SurveyTaskStore.get(context)
                val events = store.events(id)
                val taskSamples = tracks.filter { it.taskId == id }.flatMap { it.samples }.sortedBy { it.time }
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(JSONObject().put("type", "task_export").put("schema", 1)
                        .put("task", store.taskMetadata(id)).put("coordinate_system", "WGS84")
                        .put("samples_limited", limited).put("events_limited", events.size > SurveyTaskStore.EVENT_LIMIT)
                        .put("event_time_basis", "per_event").toString())
                    for (point in taskSamples) writer.appendLine(JSONObject()
                        .put("type", "map_sample").put("task_id", point.taskId)
                        .put("mission_id", point.missionId ?: JSONObject.NULL).put("time_ms", point.time)
                        .put("elapsed_ms", point.elapsedMs ?: JSONObject.NULL).put("clock_id", point.clockId ?: JSONObject.NULL)
                        .put("latitude", point.latitude).put("longitude", point.longitude)
                        .put("location_age_ms", point.locationAgeMs ?: JSONObject.NULL)
                        .put("location_accuracy_m", point.locationAccuracyM ?: JSONObject.NULL)
                        .put("location_provider", point.locationProvider ?: JSONObject.NULL)
                        .put("subscription_id", point.subscriptionId ?: JSONObject.NULL).put("sim_slot", point.simSlot ?: JSONObject.NULL)
                        .put("rat", point.rat ?: JSONObject.NULL).put("pci", point.pci ?: JSONObject.NULL)
                        .put("arfcn", point.arfcn ?: JSONObject.NULL).put("cell_id", point.cellId ?: JSONObject.NULL)
                        .put("plmn", point.plmn ?: JSONObject.NULL).put("tac", point.tac ?: JSONObject.NULL)
                        .put("rsrp", point.rsrp ?: JSONObject.NULL).put("sinr", point.sinr ?: JSONObject.NULL)
                        .put("rsrq", point.rsrq ?: JSONObject.NULL).toString())
                    for (event in events.takeLast(SurveyTaskStore.EVENT_LIMIT)) writer.appendLine(event.toJson().toString())
                }
            }
            Toast.makeText(context, R.string.road_exported, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(playing, track) {
        if (!playing || track == null) return@LaunchedEffect
        val last = track.samples.lastIndex
        val initial = track.samples[selectedSample].time
        val started = SystemClock.elapsedRealtime()
        while (selectedSample < last) {
            val playTime = initial + (SystemClock.elapsedRealtime() - started) * 10
            var lo = selectedSample
            var hi = last
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (track.samples[mid].time <= playTime) lo = mid else hi = mid - 1
            }
            selectedSample = lo
            delay(80)
        }
        playing = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TitleBar(stringResource(R.string.tool_road_coverage_title), compact = true, onBackClick = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            SurveyTaskBar()
            TaskRecordingControls(rootDiag)
            HorizontalDivider()
            if (locationMode == SurveyLocationMode.CQT && floorPlanVisible) {
                CqtFloorPlanPanel(Modifier.weight(1f).fillMaxWidth(), onShowReplay = { floorPlanVisible = false })
            } else {
            if (locationMode == SurveyLocationMode.CQT) {
                TextButton(onClick = { floorPlanVisible = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.cqt_show_floor))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !imported, modifier = Modifier.weight(1f), onClick = {
                    scope.launch {
                        try {
                            taskHistory = withContext(Dispatchers.IO) { SurveyTaskStore.get(context).recentTasks() }
                            chooseTask = true
                        } catch (_: android.database.SQLException) {
                            Toast.makeText(context, R.string.road_read_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(taskHistory.firstOrNull { it.id == replayTaskId }?.name ?: stringResource(R.string.road_task_history),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                TextButton(enabled = !imported && replayTaskId != null, onClick = { playing = false; eventRefresh++; timeline = true }) {
                    Text(stringResource(R.string.road_task_events))
                }
                Box {
                    IconButton(onClick = { replayMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.road_options))
                    }
                    AppMenu(expanded = replayMenu, onDismissRequest = { replayMenu = false }) {
                        AppMenuItem(title = stringResource(R.string.road_reload), enabled = !busy,
                            onClick = { replayMenu = false; loadSaved() })
                        AppMenuItem(title = stringResource(R.string.road_import), enabled = !busy,
                            onClick = {
                                replayMenu = false
                                importCsv.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                            })
                        AppMenuItem(title = stringResource(R.string.road_export), enabled = !busy && track != null,
                            onClick = { replayMenu = false; export.launch("wireless-route-${track?.startTime}.csv") })
                        if (!imported) AppMenuItem(
                            title = stringResource(if (tracking) R.string.road_stop_recording else R.string.road_start_recording),
                            enabled = !busy,
                            onClick = {
                                replayMenu = false
                                if (tracking) {
                                    MapTrack.setEnabled(context, false)
                                    tracking = false
                                    loadSaved()
                                } else launchIo(R.string.road_read_failed) {
                                    val taskId = withContext(Dispatchers.IO) {
                                        SurveyTaskStore.get(context).observationTask()?.id
                                    }
                                    if (existingTaskForMapTracking(taskId) == null) {
                                        Toast.makeText(context, R.string.road_task_required, Toast.LENGTH_LONG).show()
                                        return@launchIo
                                    }
                                    MapTrack.setEnabled(context, true)
                                    tracking = true
                                }
                            },
                        )
                    }
                }
            }
            if (track == null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator()
                    else Text(stringResource(R.string.road_empty), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(modifier = Modifier.weight(1f), onClick = { chooseTrack = true }) {
                        Text(stringResource(R.string.road_track_picker, selectedTrack + 1, tracks.size))
                    }
                    FilterChip(!sinr, { sinr = false }, label = { Text("RSRP") })
                    FilterChip(sinr, { sinr = true }, label = { Text("SINR") })
                }
                if (limited) Text(stringResource(R.string.road_recent_limit),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                RoadMap(track, selectedSample, focusSample, sinr,
                    links.filter { it.trackIndex == selectedTrack }, selectedEvent,
                    onEventClick = { link -> selectEvent(link); timeline = true },
                    modifier = Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(enabled = track.samples.size > 1, onClick = {
                            if (!playing && selectedSample == track.samples.lastIndex) selectedSample = 0
                            focusSample = -1
                            playing = !playing
                        }) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                stringResource(if (playing) R.string.road_pause else R.string.road_play))
                        }
                        Slider(
                            value = selectedSample.toFloat(),
                            onValueChange = {
                                playing = false
                                focusSample = -1
                                selectedSample = it.roundToInt().coerceIn(track.samples.indices)
                            },
                            valueRange = 0f..track.samples.lastIndex.coerceAtLeast(1).toFloat(),
                            enabled = track.samples.size > 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    sample?.let {
                        val metric = if (sinr) it.sinr else it.rsrp
                        val suffix = if (sinr) "dB" else "dBm"
                        val information = listOfNotNull(
                            roadDate.format(Instant.ofEpochMilli(it.time)),
                            it.rat,
                            it.pci?.let { pci -> "PCI $pci" },
                            metric?.let { level -> "${formatRoadValue(level)} $suffix" } ?: "—",
                        ).joinToString(" · ")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(9.dp).background(Color(roadColor(it, sinr)), CircleShape))
                            Text(information, style = MaterialTheme.typography.bodyMedium)
                        }
                        val quality = listOfNotNull(it.locationAccuracyM?.let { n -> "精度 ${formatRoadValue(n)} m" },
                            it.locationAgeMs?.let { n -> "定位龄期 ${formatRoadValue(n / 1000.0)} s" })
                        if (quality.isNotEmpty()) Text(quality.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    }
                    links.firstOrNull { it.event.id == selectedEvent }?.let {
                        Text("${roadClock.format(Instant.ofEpochMilli(it.event.time))} · ${it.event.title}" +
                            when {
                                it.sampleIndex == null -> " · 未关联位置"
                                it.devicePositionOnly -> " · 设备位置 / 默认网络路由"
                                else -> " · 附近打点"
                            },
                            Modifier.clickable { timeline = true }, style = MaterialTheme.typography.labelMedium)
                    }
                    report?.let { result ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.road_summary, track.samples.size, formatRoadValue(result.distanceMeters / 1000)),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { showWeakSections = true }) {
                                Text(stringResource(R.string.road_statistics))
                            }
                        }
                    }
                }
            }
            }
        }
    }
    AppDialog(
        show = chooseTask,
        onDismissRequest = { chooseTask = false },
        title = "回放任务（最近 200 项）",
        confirmText = "关闭",
        onConfirm = { chooseTask = false },
        scrollContent = false,
    ) {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            itemsIndexed(taskHistory) { _, item ->
                TextButton(onClick = {
                    replayTaskId = item.id
                    selectedTrack = tracks.indexOfFirst { it.taskId == item.id }
                    selectedSample = 0; focusSample = -1; selectedEvent = null; playing = false; chooseTask = false
                }) { Column(Modifier.fillMaxWidth()) {
                    Text(item.name)
                    Text("${roadDate.format(Instant.ofEpochMilli(item.startedAt))} · ${if (item.endedAt == null) "进行中" else "已结束"}",
                        style = MaterialTheme.typography.bodySmall)
                } }
            }
            item { TextButton(onClick = {
                replayTaskId = null; selectedTrack = tracks.indexOfFirst { it.taskId == null }
                selectedSample = 0; focusSample = -1; playing = false; chooseTask = false
            }) { Text("旧版无任务打点") } }
        }
    }
    if (timeline) RoadEventTimeline(links, selectedEvent, eventLimited, eventError,
        onDismiss = { timeline = false },
        onSelect = { selectEvent(it); timeline = false },
        onExport = { exportEvents.launch("wireless-task-$replayTaskId.jsonl") })
    AppDialog(
        show = chooseTrack,
        onDismissRequest = { chooseTrack = false },
        title = stringResource(R.string.road_choose_track),
        confirmText = stringResource(android.R.string.cancel),
        onConfirm = { chooseTrack = false },
        scrollContent = false,
    ) {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(tracks) { index, item ->
                    val sim = item.simSlot?.let { stringResource(R.string.road_sim, it + 1) }
                    val time = roadDate.format(Instant.ofEpochMilli(item.startTime))
                    val end = roadClock.format(Instant.ofEpochMilli(item.endTime))
                    TextButton(onClick = {
                        playing = false
                        selectedSample = 0
                        focusSample = -1
                        selectedTrack = index
                        replayTaskId = item.taskId
                        selectedEvent = null
                        chooseTrack = false
                    }) {
                        Text(listOfNotNull("$time – $end", sim, item.plmn,
                            item.taskId?.let { id -> taskHistory.firstOrNull { it.id == id }?.name ?: id.take(8) }).joinToString(" · "))
                    }
                }
            }
    }
    if (track != null) AppDialog(
        show = showWeakSections,
        onDismissRequest = { showWeakSections = false },
        title = stringResource(R.string.road_statistics),
        confirmText = stringResource(android.R.string.cancel),
        onConfirm = { showWeakSections = false },
        scrollContent = false,
    ) {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                report?.let { result ->
                    item {
                        val percent = if (result.samplesWithRsrp == 0) "\u2014" else
                            "${formatRoadValue(result.weakSamples * 100.0 / result.samplesWithRsrp)}%"
                        val average = result.averageRsrp?.let { "${formatRoadValue(it)} dBm" } ?: "\u2014"
                        Text(stringResource(R.string.road_weak_summary, average, percent))
                        Text(stringResource(R.string.road_weak_sections, result.weakSections.size),
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
                itemsIndexed(report?.weakSections.orEmpty()) { _, section ->
                    val start = roadClock.format(Instant.ofEpochMilli(track.samples[section.firstIndex].time))
                    val end = roadClock.format(Instant.ofEpochMilli(track.samples[section.lastIndex].time))
                    TextButton(onClick = {
                        playing = false
                        selectedSample = section.firstIndex
                        focusSample = section.firstIndex
                        showWeakSections = false
                    }) { Text("$start – $end · ${formatRoadValue(section.averageRsrp)} dBm") }
                }
            }
    }
}

private data class RoadLine(val points: List<LatLng>, val colors: List<Int>)

@Composable
private fun RoadMap(track: RoadTrack, index: Int, focus: Int, sinr: Boolean,
                    events: List<RoadEventLink>, selectedEvent: String?,
                    onEventClick: (RoadEventLink) -> Unit, modifier: Modifier) {
    var view by remember { mutableStateOf<TextureMapView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var dot by remember { mutableStateOf<Dot?>(null) }
    var eventIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }
    val click by rememberUpdatedState(onEventClick)
    val currentEvents by rememberUpdatedState(events)
    BaiduMapView(
        onCreate = { created ->
            view = created
            created.showZoomControls(false)
            created.map.setOnMapLoadedCallback { if (view === created) ready = true }
            created.map.setOnMarkerClickListener { marker ->
                val id = marker.extraInfo?.getString("event_id")
                currentEvents.firstOrNull { it.event.id == id }?.let { click(it); true } ?: false
            }
        },
        onRelease = { released ->
            if (view === released) {
                dot = null
                eventIcon?.recycle()
                eventIcon = null
                ready = false
                view = null
            }
        },
        modifier = modifier,
    )
    LaunchedEffect(view, ready, track, sinr, events, selectedEvent) {
        val map = view?.map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val lines = withContext(Dispatchers.Default) { roadLines(track.samples, sinr) }
        dot = null
        map.clear()
        eventIcon?.recycle()
        val bitmap = android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = MapColor.WHITE
        canvas.drawCircle(20f, 20f, 18f, paint)
        paint.color = MapColor.rgb(185, 35, 65)
        canvas.drawCircle(20f, 20f, 13f, paint)
        val icon = BitmapDescriptorFactory.fromBitmap(bitmap)
        eventIcon = icon
        for (line in lines) {
            if (line.points.size >= 2) {
                map.addOverlay(PolylineOptions().points(line.points).colorsValues(line.colors.drop(1))
                    .width(12).zIndex(3))
            } else if (lines.size <= 1024) {
                map.addOverlay(DotOptions().center(line.points.first()).radius(7).color(line.colors.first()).zIndex(3))
            }
        }
        dot = map.addOverlay(DotOptions().center(roadCoordinate(track.samples.first()))
            .radius(9).color(MapColor.WHITE).zIndex(7)) as? Dot
        val markers = events.distinctBy { it.sampleIndex }.takeLast(1_000).toMutableList()
        events.firstOrNull { it.event.id == selectedEvent }?.let { selected ->
            if (markers.none { it.event.id == selected.event.id }) {
                if (markers.size == 1_000) markers.removeAt(0)
                markers.add(selected)
            }
        }
        val options = markers.mapNotNull { event ->
            val sample = event.sampleIndex?.let { track.samples.getOrNull(it) } ?: return@mapNotNull null
            MarkerOptions().position(roadCoordinate(sample)).icon(icon)
                .anchor(0.5f, 0.5f).zIndex(6).extraInfo(Bundle().apply { putString("event_id", event.event.id) })
        }
        if (options.isNotEmpty()) map.addOverlays(options)
    }
    LaunchedEffect(view, ready, track) {
        val map = view?.map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val coordinates = withContext(Dispatchers.Default) { track.samples.map(::roadCoordinate) }
        val update = if (coordinates.all { it == coordinates.first() }) {
            MapStatusUpdateFactory.newLatLngZoom(coordinates.first(), 16f)
        } else {
            val bounds = LatLngBounds.Builder()
            coordinates.forEach { bounds.include(it) }
            MapStatusUpdateFactory.newLatLngBounds(bounds.build())
        }
        map.setMapStatus(update)
    }
    LaunchedEffect(dot, track, index) {
        val sample = track.samples.getOrNull(index) ?: return@LaunchedEffect
        dot?.center = roadCoordinate(sample)
    }
    LaunchedEffect(view, ready, track, focus) {
        val sample = track.samples.getOrNull(focus) ?: return@LaunchedEffect
        if (ready) view?.map?.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(roadCoordinate(sample), 16f))
    }
}

@Composable
private fun RoadEventTimeline(links: List<RoadEventLink>, selected: String?, limited: Boolean, error: Boolean,
                              onDismiss: () -> Unit, onSelect: (RoadEventLink) -> Unit, onExport: () -> Unit) {
    var source by remember { mutableStateOf<String?>(null) }
    val visible = remember(links, source) { links.filter { source == null || it.event.source == source } }
    val state = rememberLazyListState()
    LaunchedEffect(selected, visible) {
        visible.indexOfFirst { it.event.id == selected }.takeIf { it >= 0 }?.let { state.scrollToItem(it) }
    }
    AppDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "事件时间线 · ${links.size}",
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = "导出任务",
        onDismissAction = onExport,
        scrollContent = false,
    ) {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((key, label) in listOf(null to "全部", "android" to "Android", "root_diag" to "DIAG",
                    "speedtest" to "测速", "task" to "任务")) {
                    FilterChip(source == key, { source = key }, label = { Text(label) })
                }
            }
            if (limited) Text("仅显示最近 ${SurveyTaskStore.EVENT_LIMIT} 条事件", style = MaterialTheme.typography.labelSmall)
            if (error) Text("事件读取失败", color = MaterialTheme.colorScheme.error)
            if (visible.isEmpty()) Text("暂无事件", Modifier.padding(vertical = 16.dp))
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 380.dp).weight(1f, fill = false)) {
                itemsIndexed(visible, key = { _, link -> link.event.id }) { _, link ->
                    Column(Modifier.fillMaxWidth()
                        .background(if (link.event.id == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onSelect(link) }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                        Text("${roadClock.format(Instant.ofEpochMilli(link.event.time))} · ${link.event.title}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(link.event.source, link.event.simSlot?.let { "SIM ${it + 1}" },
                            when {
                                link.sampleIndex == null -> "未关联位置"
                                link.devicePositionOnly -> "设备位置 / 网络归属未知"
                                else -> "附近打点（≤10秒）"
                            },
                            if (link.event.timeBasis == "source_age_adjusted") "源时间校正" else "接收时间").joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall)
                        if (link.event.detail.isNotBlank()) Text(link.event.detail,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
            if (links.mapNotNull { it.sampleIndex }.distinct().size > 1_000)
                Text("地图最多显示最近 1000 个事件位置", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun roadLines(samples: List<RoadSample>, sinr: Boolean): List<RoadLine> = buildList {
    var points = mutableListOf<LatLng>()
    var colors = mutableListOf<Int>()
    fun addLine() {
        if (points.isNotEmpty()) add(RoadLine(points, colors))
        points = mutableListOf()
        colors = mutableListOf()
    }
    for ((i, sample) in samples.withIndex()) {
        val point = roadCoordinate(sample)
        val color = roadColor(sample, sinr)
        if (i > 0 && roadDistance(samples[i - 1], sample) == null) addLine()
        if (points.lastOrNull() == point) {
            colors[colors.lastIndex] = color
            continue
        }
        points.add(point)
        colors.add(color)
        if (points.size == 4096) {
            addLine()
            points.add(point)
            colors.add(color)
        }
    }
    addLine()
}

private fun roadCoordinate(sample: RoadSample): LatLng {
    val bd = CoordConverter.wgs84ToBd09(sample.latitude, sample.longitude)
    return LatLng(bd[0], bd[1])
}

private fun roadColor(sample: RoadSample, sinr: Boolean): Int {
    val value = (if (sinr) sample.sinr else sample.rsrp) ?: return MapColor.GRAY
    val level = if (sinr) SignalGrade.fromSinr(floor(value).toInt()) else SignalGrade.fromRsrp(floor(value).toInt())
    return MapColor.parseColor(SignalGrade.hexOf(level))
}

private val roadClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val roadDate = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
private fun formatRoadValue(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
