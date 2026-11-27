package com.craxiom.networksurvey.ui.speedtest

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.db.SpeedTestDbHelper
import com.craxiom.networksurvey.db.SurveyTaskStore
import com.craxiom.networksurvey.model.SurveyEvent
import com.craxiom.networksurvey.model.SpeedTestRecord
import com.craxiom.networksurvey.ui.common.AppBottomSheet
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults
import com.craxiom.networksurvey.ui.dashboard.DashboardCardHeader
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.craxiom.networksurvey.update.SpeedNode
import com.craxiom.networksurvey.update.SpeedNodeRepository
import com.craxiom.networksurvey.util.CryptoManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

private data class ServerBrowser(val path: String, val upload: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val db = remember(context) { SpeedTestDbHelper.getInstance(context) }
    val profiles = remember(context) { SpeedProfiles(context) }
    val scope = rememberCoroutineScope()
    val configs = remember { mutableStateMapOf<SpeedProtocol, SpeedServer>() }
    var protocol by remember { mutableStateOf(SpeedProtocol.HTTP) }
    val server = configs[protocol] ?: SpeedServer(protocol)
    fun edit(server: SpeedServer) { configs[server.protocol] = server }

    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(emptyList<SpeedServer>()) }
    var records by remember { mutableStateOf(emptyList<SpeedTestRecord>()) }
    var test by remember { mutableStateOf<Job?>(null) }
    var helper by remember { mutableStateOf<SpeedTransport?>(null) }
    var status by remember { mutableStateOf("选择节点或输入服务器地址") }
    var message by remember { mutableStateOf<String?>(null) }
    var sample by remember { mutableStateOf<SpeedSample?>(null) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    var values by remember { mutableStateOf(emptyList<Long>()) }
    var measureUpload by remember { mutableStateOf(false) }
    var upload by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableStateOf("10") }
    var streams by rememberSaveable { mutableStateOf("4") }
    var size by rememberSaveable { mutableStateOf("10") }
    var repeats by rememberSaveable { mutableStateOf("1") }
    var interval by rememberSaveable { mutableStateOf("5") }
    var showSaved by rememberSaveable { mutableStateOf(false) }
    var showNodes by rememberSaveable { mutableStateOf(false) }
    var nodes by remember { mutableStateOf(emptyList<SpeedNode>()) }
    var nodeLoading by remember { mutableStateOf(false) }
    var nodeError by remember { mutableStateOf("") }
    var browser by remember { mutableStateOf<ServerBrowser?>(null) }
    var entries by remember { mutableStateOf(emptyList<SpeedEntry>()) }
    var browsing by remember { mutableStateOf(false) }
    val busy = test != null || helper != null
    val isUpload = upload && protocol != SpeedProtocol.HTTP
    val latestServer by rememberUpdatedState(server)
    val latestLoaded by rememberUpdatedState(loaded)
    val latestStreams by rememberUpdatedState(streams)

    LaunchedEffect(profiles) {
        try {
            val (current, favorites, history) = withContext(Dispatchers.IO) {
                Triple(profiles.current(), profiles.saved(), db.getRecent(15))
            }
            current?.let {
                edit(it)
                protocol = it.protocol
            }
            saved = favorites
            streams = profiles.streams()
            records = history
            nodes = withContext(Dispatchers.IO) { SpeedNodeRepository.cached(context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "无法读取本地测速设置"
        } finally {
            loaded = true
        }
    }
    DisposableEffect(profiles) {
        onDispose {
            val operation = helper
            val config = latestServer
            val connectionCount = latestStreams
            if (operation != null || latestLoaded) {
                val save = latestLoaded
                Thread {
                    operation?.close()
                    if (save) profiles.saveCurrent(config, connectionCount)
                }.start()
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); message = null }
    }

    fun refreshNodes() {
        if (nodeLoading) return
        nodeLoading = true
        nodeError = ""
        SpeedNodeRepository.fetch(context) { response ->
            nodeLoading = false
            if (response.nodes.isNotEmpty()) nodes = response.nodes
            else nodeError = if (nodes.isEmpty()) "节点列表暂不可用" else "显示上次获取的节点"
        }
    }

    fun browse(target: ServerBrowser) {
        if (busy || !loaded) return
        browser = target
        entries = emptyList()
        browsing = true
        scope.launch {
            try {
                SpeedTransport(server).use { client ->
                    helper = client
                    val found = client.list(target.path)
                    if (browser == target) entries = found
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (browser == target) {
                    browser = null
                    message = speedError(error)
                }
            } finally {
                helper = null
                browsing = false
            }
        }
    }

    fun check() {
        if (busy || !loaded) return
        scope.launch {
            try {
                SpeedTransport(server).use { client ->
                    helper = client
                    message = client.check(isUpload)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = speedError(error)
            } finally {
                helper = null
            }
        }
    }

    fun start() {
        if (busy || !loaded) return
        val endpoint = try { server.endpoint(isUpload) } catch (error: Exception) {
            message = speedError(error)
            return
        }
        val count = repeats.toIntOrNull()?.takeIf { it in 1..20 }
        val wait = interval.toIntOrNull()?.takeIf { it in 1..120 }
        val connections = streams.toIntOrNull()?.takeIf { it in 1..8 }
        val duration = seconds.toIntOrNull()?.takeIf { it in 3..60 }
        val uploadSize = size.toIntOrNull()?.takeIf { it in 1..500 }
        if (count == null || wait == null || connections == null || (isUpload && uploadSize == null) ||
            (!isUpload && duration == null)) {
            message = "请检查测速参数的取值范围"
            advanced = true
            return
        }
        measureUpload = isUpload
        sample = null
        result = null
        values = emptyList()
        status = "正在连接"
        test = scope.launch {
            var taskId: String? = null
            suspend fun event(kind: String, title: String, detail: String = "") = withContext(Dispatchers.IO) {
                val id = taskId ?: return@withContext
                SurveyTaskStore.get(context).append(SurveyEvent(
                    java.util.UUID.randomUUID().toString(), id, System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(), SurveyTaskStore.CLOCK_ID, "speedtest", kind, title, detail,
                ))
            }
            try {
                // Capture once: task switches during a request must not steal its eventual result.
                taskId = withContext(Dispatchers.IO) { SurveyTaskStore.get(context).observationTask()?.id }
                event("speed_start", "测速开始", "${server.protocol.name} · 默认网络路由 · $count 次")
                for (index in 1..count) {
                    status = if (count == 1) "正在测速" else "正在进行第 $index / $count 次测速"
                    sample = null
                    result = null
                    values = emptyList()
                    val reading = SpeedTransport(server).use { client ->
                        val report: (SpeedSample) -> Unit = {
                            sample = it
                            values = (values + it.bitsPerSecond).takeLast(120)
                        }
                        if (isUpload) client.upload(requireNotNull(uploadSize) * 1_000_000L, connections, report)
                        else client.download(requireNotNull(duration), connections, report)
                    }
                    result = reading
                    records = withContext(Dispatchers.IO) {
                        db.insert(SpeedTestRecord().apply {
                            this.taskId = taskId
                            time = System.currentTimeMillis()
                            serverType = server.protocol.name
                            serverUrl = endpoint.substringBefore('?')
                            if (isUpload) uploadSpeed = reading.average else downloadSpeed = reading.average
                            dataSize = reading.bytes
                            this.duration = reading.seconds.roundToInt().coerceAtLeast(1)
                            isContinuous = count > 1
                        })
                        db.getRecent(15)
                    }
                    event("speed_result", "测速完成（第 $index 次）",
                        "${if (isUpload) "上传" else "下载"} · ${formatRate(reading.average)} · ${formatTraffic(reading.bytes)} · 默认网络路由")
                    if (index < count) {
                        status = "已完成 $index / $count 次 · $wait 秒后继续"
                        delay(wait * 1_000L)
                    }
                }
                status = "测速完成"
            } catch (cancelled: CancellationException) {
                status = "已停止"
                withContext(NonCancellable) { runCatching { event("speed_cancel", "测速取消") } }
                throw cancelled
            } catch (error: Exception) {
                status = speedError(error)
                sample = null
                runCatching { event("speed_error", "测速失败", status) }
            } finally {
                test = null
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TitleBar(
                stringResource(R.string.tool_speed_test_title),
                scrollBehavior = scrollBehavior,
                onBackClick = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.widthIn(max = 600.dp).fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = DashboardCardDefaults.margin, end = DashboardCardDefaults.margin,
                    top = 8.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("measurement") {
                    SpeedPanel(
                        title = if (test != null || result != null) {
                            if (measureUpload) "上传速度" else "下载速度"
                        } else if (isUpload) "上传测速" else "下载测速",
                        icon = R.drawable.ic_speed_test,
                        action = {
                            Surface(
                                shape = DashboardCardDefaults.smallShape,
                                color = colors.secondaryContainer,
                                contentColor = colors.onSecondaryContainer,
                            ) {
                                Text(protocol.label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        },
                    ) {
                        val value = result?.average ?: if (test != null) sample?.bitsPerSecond else null
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                value?.let(::formatRateValue) ?: "—",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary,
                            )
                            Text("Mbps", Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurfaceVariant)
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (values.size >= 2) SpeedPlot(values)
                        if (test != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        HorizontalDivider(color = DashboardCardDefaults.outlineVariant())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SpeedStat("峰值", (result?.peak ?: values.maxOrNull())?.let(::formatRate) ?: "—", Modifier.weight(1f))
                            SpeedStat("流量", (result?.bytes ?: if (test != null) sample?.bytes else null)
                                ?.let(::formatTraffic) ?: "—", Modifier.weight(1f))
                        }
                        Button(
                            onClick = { if (test == null) start() else { status = "正在停止"; test?.cancel() } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                            enabled = loaded && helper == null,
                            colors = if (test != null) ButtonDefaults.buttonColors(containerColor = colors.error)
                                else ButtonDefaults.buttonColors(),
                        ) { Text(if (test != null) "停止测速" else "开始测速") }
                    }
                }
                item("server") {
                    SpeedPanel("测速服务器", icon = R.drawable.ic_cloud_connection) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { showNodes = true; refreshNodes() }, enabled = !busy && loaded) { Text("预设节点") }
                            OutlinedButton(onClick = { showSaved = true }, enabled = !busy && loaded) { Text("已保存") }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SpeedProtocol.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = protocol == option, onClick = { protocol = option },
                                    shape = SegmentedButtonDefaults.itemShape(index, SpeedProtocol.entries.size), enabled = !busy,
                                ) { Text(option.label) }
                            }
                        }
                        SpeedField(server.address, { edit(server.copy(address = it)) },
                            if (protocol == SpeedProtocol.HTTP) "测速文件链接" else "服务器地址",
                            !busy, if (protocol == SpeedProtocol.FTP) "ftp://服务器:21" else "https://服务器/路径")
                        if (protocol != SpeedProtocol.HTTP) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!isUpload, { upload = false }, { Text("下载") }, enabled = !busy)
                                FilterChip(isUpload, { upload = true }, { Text("上传") }, enabled = !busy)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isUpload) SpeedField(server.uploadPath, { edit(server.copy(uploadPath = it)) },
                                    "上传目录", !busy, "默认使用下载文件所在目录", Modifier.weight(1f))
                                else SpeedField(server.downloadPath, { edit(server.copy(downloadPath = it)) },
                                    "下载文件路径", !busy, "默认使用地址中的文件路径", Modifier.weight(1f))
                                FilledTonalButton(onClick = {
                                    val path = runCatching { if (isUpload) server.upload() else server.download().let {
                                        if (it.endsWith('/')) it else parentDirectory(it)
                                    } }.getOrElse { message = speedError(it); return@FilledTonalButton }
                                    browse(ServerBrowser(path, isUpload))
                                }, enabled = !busy && loaded) { Text("浏览") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SpeedField(server.user, { edit(server.copy(user = it)) }, "用户名", !busy, "", Modifier.weight(1f))
                                OutlinedTextField(server.password, { edit(server.copy(password = it)) },
                                    Modifier.weight(1f), enabled = !busy, label = { Text("密码") }, singleLine = true,
                                    shape = DashboardCardDefaults.smallShape, visualTransformation = PasswordVisualTransformation())
                            }
                            if (protocol == SpeedProtocol.FTP) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("使用 FTP 被动模式", style = MaterialTheme.typography.bodyMedium)
                                    Switch(server.passive, { edit(server.copy(passive = it)) }, enabled = !busy)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = ::check, enabled = !busy && loaded) { Text("检查连接") }
                            TextButton(onClick = {
                                if (server.address.isBlank()) { message = "请先输入服务器地址"; return@TextButton }
                                val snapshot = server
                                scope.launch {
                                    try {
                                        saved = withContext(Dispatchers.IO) { profiles.add(snapshot) }
                                        message = "服务器已保存"
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        message = "无法保存服务器"
                                    }
                                }
                            }, enabled = !busy && loaded) { Text("保存服务器") }
                        }
                    }
                }
                item("options") {
                    SpeedPanel("测速参数", icon = R.drawable.ic_settings, action = {
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起" else "调整") }
                    }) {
                        Text(if (isUpload) "$size MB · $streams 路并发" else "$seconds 秒 · $streams 路并发",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        if (advanced) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isUpload) NumericSpeedField(size, { size = it }, "大小 MB · 1–500", !busy, Modifier.weight(1f))
                                else NumericSpeedField(seconds, { seconds = it }, "秒数 · 3–60", !busy, Modifier.weight(1f))
                                NumericSpeedField(streams, { streams = it }, "并发 · 1–8", !busy, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumericSpeedField(repeats, { repeats = it }, "次数 · 1–20", !busy, Modifier.weight(1f))
                                NumericSpeedField(interval, { interval = it }, "间隔秒 · 1–120", !busy, Modifier.weight(1f))
                            }
                        }
                    }
                }
                item("historyHeading") {
                    Text("近期记录", Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.titleMedium)
                }
                if (records.isEmpty()) item("noHistory") { SpeedPanel("暂无测速记录") {} }
                items(records, key = { it.id }) { record ->
                    val upstream = record.uploadSpeed > 0
                    SpeedPanel(if (upstream) "上传 · ${formatRate(record.uploadSpeed)}" else "下载 · ${formatRate(record.downloadSpeed)}") {
                        Text("${record.timeText}  ·  ${record.serverType}  ·  ${formatTraffic(record.dataSize)}",
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(record.serverUrl.orEmpty().substringBefore('?'), color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        record.taskId?.let { Text("任务 $it", style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
    }
    }

    AppBottomSheet(
        show = showNodes,
        title = "预设测速节点",
        onDismissRequest = { showNodes = false },
        endAction = {
            IconButton(onClick = ::refreshNodes, enabled = !nodeLoading) { Icon(Icons.Rounded.Refresh, "刷新节点") }
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (nodeLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (nodeError.isNotEmpty()) Text(nodeError, Modifier.padding(vertical = 8.dp), color = colors.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(nodes) { node ->
                    TextButton(
                        onClick = {
                            edit(SpeedServer(SpeedProtocol.HTTP, node.url))
                            protocol = SpeedProtocol.HTTP
                            showNodes = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(node.name) }
                }
            }
        }
    }
    AppBottomSheet(show = showSaved, title = "已保存的服务器", onDismissRequest = { showSaved = false }) {
        Column(Modifier.fillMaxWidth()) {
            if (saved.isEmpty()) Text("暂无保存的服务器", Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(saved) { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                edit(profile)
                                protocol = profile.protocol
                                showSaved = false
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("${profile.protocol.label} · ${profile.address.substringBefore('?')}", maxLines = 2) }
                        IconButton(onClick = { scope.launch { saved = withContext(Dispatchers.IO) { profiles.remove(profile) } } }) {
                            Icon(Icons.Rounded.Delete, "删除保存的服务器")
                        }
                    }
                }
            }
        }
    }
    browser?.let { target ->
        AppBottomSheet(
            show = true,
            title = if (target.upload) "选择上传目录" else "选择测速文件",
            onDismissRequest = { browser = null },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(target.path, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (browsing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target.path != "/") TextButton(onClick = { browse(target.copy(path = parentDirectory(target.path))) }, enabled = !browsing) { Text("上一级") }
                    if (target.upload) TextButton(onClick = { edit(server.copy(uploadPath = target.path)); browser = null },
                        enabled = !browsing) { Text("使用当前目录") }
                }
                val shown = if (target.upload) entries.filter { it.directory } else entries
                if (!browsing && shown.isEmpty()) Text("此处没有可选项", Modifier.padding(vertical = 14.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(shown) { entry ->
                        TextButton(onClick = {
                            if (entry.directory) browse(target.copy(path = entry.path))
                            else { edit(server.copy(downloadPath = entry.path)); browser = null }
                        }, modifier = Modifier.fillMaxWidth()) { Text(entry.name + if (entry.directory) "  ›" else "") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedPanel(
    title: String,
    icon: Int? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DashboardCardDefaults.shape,
        color = DashboardCardDefaults.cardContainerColor(),
        border = BorderStroke(1.dp, DashboardCardDefaults.outlineVariant()),
    ) {
        Column {
            if (icon != null) {
                DashboardCardHeader(icon = icon, title = title, action = action)
            }
            Column(
                Modifier.fillMaxWidth().padding(
                    start = 16.dp, end = 16.dp, top = if (icon == null) 14.dp else 0.dp, bottom = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon == null) Text(title, style = MaterialTheme.typography.titleSmall)
                content()
            }
        }
    }
}

@Composable
private fun SpeedField(value: String, onValue: (String) -> Unit, title: String, enabled: Boolean, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(title) }, placeholder = { Text(hint) }, shape = DashboardCardDefaults.smallShape)
}

@Composable
private fun NumericSpeedField(value: String, onValue: (String) -> Unit, title: String, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(value, { onValue(it.filter(Char::isDigit).take(3)) }, modifier, enabled = enabled, singleLine = true,
        label = { Text(title) }, shape = DashboardCardDefaults.smallShape, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun SpeedStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SpeedPlot(values: List<Long>) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(76.dp)) {
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val step = size.width / values.lastIndex
        val points = values.mapIndexed { index, rate -> Offset(index * step, size.height * (1 - rate.toFloat() / max) * 0.88f + size.height * 0.05f) }
        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawLine(colors.primary.copy(alpha = 0.2f), Offset(0f, size.height), Offset(size.width, size.height))
        drawPath(fill, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.24f), colors.primary.copy(alpha = 0.02f))))
        drawPath(line, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun formatRateValue(bits: Long) = String.format(Locale.getDefault(), "%.1f", bits / 1_000_000.0)

private fun formatRate(bits: Long) = "${formatRateValue(bits)} Mbps"

private fun formatTraffic(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
}

private class SpeedProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("speed_servers", Context.MODE_PRIVATE)
    private val crypto = CryptoManager()

    fun streams() = (prefs.getString("last_thread_count", "4")?.toIntOrNull() ?: 4).coerceIn(1, 8).toString()

    fun current(): SpeedServer? {
        decode(prefs.getString("profile_current", null))?.let { return it }
        val legacy = prefs.getString("last_type", null) ?: return null
        val protocol = parseProtocol(legacy)
        val host = prefs.getString("last_host", "").orEmpty()
        val port = prefs.getInt("last_port", -1)
        val address = prefs.getString("last_url", "").orEmpty().ifBlank { legacyAddress(protocol, host, port) }
        return SpeedServer(protocol, address, prefs.getString("last_path", "").orEmpty(),
            prefs.getString("last_up_dir", "").orEmpty(), prefs.getString("last_user", "").orEmpty(),
            prefs.getString("last_pass", "").orEmpty(), prefs.getBoolean("last_ftp_passive", true))
    }

    fun saved(): List<SpeedServer> {
        prefs.getString("profiles_v2", null)?.let {
            return runCatching {
                val array = JSONArray(it)
                List(array.length()) { index -> decode(array.getString(index)) }.filterNotNull()
            }.getOrDefault(emptyList())
        }
        return prefs.getStringSet("saved_servers", emptySet()).orEmpty().sorted().mapNotNull { entry ->
            val fields = entry.split('|')
            if (fields.size < 4) return@mapNotNull null
            val protocol = parseProtocol(fields[0])
            val address = fields.getOrNull(7).orEmpty().ifBlank { legacyAddress(protocol, fields[1], fields[2].toIntOrNull() ?: -1) }
            SpeedServer(protocol, address, fields[3], fields.getOrNull(8).orEmpty(),
                fields.getOrNull(4).orEmpty(), fields.getOrNull(5).orEmpty(), fields.getOrNull(6) != "false")
        }
    }

    fun saveCurrent(server: SpeedServer, streams: String) {
        val payload = encode(server) ?: return
        prefs.edit().putString("profile_current", payload).putString("last_thread_count", streams).remove("last_pass").apply()
    }

    @Synchronized
    fun add(server: SpeedServer): List<SpeedServer> = persist((listOf(server) + saved().filterNot {
        it.protocol == server.protocol && it.address == server.address && it.user == server.user &&
            it.downloadPath == server.downloadPath && it.uploadPath == server.uploadPath
    }).take(50))

    @Synchronized
    fun remove(server: SpeedServer): List<SpeedServer> = persist(saved().filterNot { it == server })

    private fun persist(profiles: List<SpeedServer>): List<SpeedServer> {
        val values = profiles.map { encode(it) ?: error("无法加密测速配置") }
        prefs.edit().putString("profiles_v2", JSONArray(values).toString()).remove("saved_servers").apply()
        return profiles
    }

    private fun parseProtocol(value: String) = SpeedProtocol.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SpeedProtocol.HTTP

    private fun legacyAddress(protocol: SpeedProtocol, host: String, port: Int): String {
        if (host.isBlank() || "://" in host || protocol == SpeedProtocol.HTTP) return host
        val scheme = if (protocol == SpeedProtocol.FTP) "ftp" else if (port in setOf(80, 5005, 8080)) "http" else "https"
        return "$scheme://$host" + if (port > 0) ":$port" else ""
    }

    private fun encode(server: SpeedServer): String? = crypto.encrypt(JSONObject()
        .put("protocol", server.protocol.name).put("address", server.address)
        .put("download", server.downloadPath).put("upload", server.uploadPath)
        .put("user", server.user).put("password", server.password).put("passive", server.passive).toString())

    private fun decode(value: String?): SpeedServer? = runCatching {
        val json = JSONObject(crypto.decrypt(value) ?: return null)
        SpeedServer(parseProtocol(json.getString("protocol")), json.optString("address"), json.optString("download"),
            json.optString("upload"), json.optString("user"), json.optString("password"), json.optBoolean("passive", true))
    }.getOrNull()
}
