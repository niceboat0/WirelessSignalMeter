package com.craxiom.networksurvey.ui.cellular.baidumap

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.craxiom.networksurvey.ui.common.AppBottomSheet
import com.craxiom.networksurvey.ui.common.dialogs.NsConfirmationDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.model.LatLng
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.db.EngineeringParamDbHelper
import com.craxiom.networksurvey.ui.engparam.EngineeringSectorSheet
import com.craxiom.networksurvey.db.MapPointDbHelper
import com.craxiom.networksurvey.listeners.ICellularSurveyRecordListener
import com.craxiom.networksurvey.model.CellularRecordWrapper
import com.craxiom.networksurvey.model.EngineeringParam
import com.craxiom.networksurvey.model.StationInfo
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults
import com.craxiom.networksurvey.ui.main.appbar.AppBarAction
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.services.NetworkSurveyService
import com.craxiom.networksurvey.ui.cellular.model.ServingCellInfo
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import com.craxiom.networksurvey.ui.rootdiag.mapDiagNeighbors
import com.craxiom.networksurvey.util.CoordConverter
import com.craxiom.networksurvey.util.SignalGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaiduTowerMapScreen(
    onBackButtonPressed: (() -> Unit)?,
    servingCellInfo: ServingCellInfo? = null,
    subscriptionId: Int? = null,
    rootDiag: RootDiagViewModel? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember(context) { EngineeringParamDbHelper.getInstance(context.applicationContext) }
    val pointsDb = remember(context) { MapPointDbHelper.getInstance(context.applicationContext) }
    val updates = rememberMapMeasurements(servingCellInfo, subscriptionId)
    val servingStation = remember(updates?.serving) { updates?.serving?.servingCell?.toStationInfo() }
    val rootSignal = rootDiag?.signal?.collectAsStateWithLifecycle()?.value
    val singleSim = remember(context, rootSignal?.status, updates?.serving?.subscriptionId) {
        rootSignal != null && runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoCount == 1
        }.getOrDefault(false)
    }
    val neighborCells = remember(rootSignal, singleSim, servingStation, updates?.neighbors) {
        if (singleSim && servingStation != null) {
            rootSignal?.mapDiagNeighbors(servingStation) ?: updates?.neighbors.orEmpty()
        } else updates?.neighbors.orEmpty()
    }

    var area by remember { mutableStateOf<BaiduMapArea?>(null) }
    var ready by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<BaiduMapArea.Viewport?>(null) }
    var location by remember { mutableStateOf<Location?>(null) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var showLabels by rememberSaveable { mutableStateOf(true) }
    var showPoints by rememberSaveable { mutableStateOf(true) }
    var showLink by rememberSaveable { mutableStateOf(true) }
    var satellite by rememberSaveable { mutableStateOf(false) }
    var netFilter by rememberSaveable { mutableStateOf("全部") }
    var metric by rememberSaveable { mutableStateOf("RSRP") }
    var servingParam by remember { mutableStateOf<EngineeringParam?>(null) }
    var selected by remember { mutableStateOf<EngineeringParam?>(null) }
    var detail by remember { mutableStateOf<EngineeringParam?>(null) }
    var detailOrigin by remember { mutableStateOf<EngineeringParam?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showLayers by rememberSaveable { mutableStateOf(false) }
    var showLocationInfo by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var cameraPositioned by remember { mutableStateOf(false) }
    var lastCameraFix by remember { mutableStateOf<Location?>(null) }
    val trackRevision by MapTrack.revision.collectAsStateWithLifecycle()
    var autoTrack by remember { mutableStateOf(MapTrack.isEnabled(context)) }
    var pointCount by remember { mutableStateOf(0) }
    var visibleSectors by remember { mutableStateOf<Int?>(null) }
    var renderedSectors by remember { mutableStateOf(0) }
    var paramsCount by remember { mutableStateOf<Int?>(null) }
    var paramsLoaded by remember { mutableStateOf(false) }
    var pointActionBusy by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(mapLocationPermissions(context)) }
    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions = mapLocationPermissions(context)
        if (permissions) {
            follow = true
        } else {
            Toast.makeText(context, "可继续查看地图。定位和打点需要位置权限", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(db) {
        paramsCount = withContext(Dispatchers.IO) { runCatching { db.count }.getOrNull() }
        paramsLoaded = true
    }

    LaunchedEffect(pointsDb, trackRevision) {
        val count = withContext(Dispatchers.IO) { runCatching { pointsDb.count() }.getOrNull() }
        if (count != null) pointCount = count
    }

    LaunchedEffect(
        servingStation?.networkType, servingStation?.cellId, servingStation?.mcc, servingStation?.mnc,
        servingStation?.pci, servingStation?.earfcn, paramsCount,
    ) {
        servingParam = null
        val station = servingStation ?: return@LaunchedEffect
        servingParam = withContext(Dispatchers.IO) {
            runCatching { db.matchServingIdentity(station) }.getOrNull()
        }
        if (servingParam == null && station.pci >= 0 && station.earfcn >= 0) {
            snapshotFlow { location }.filterNotNull().collectLatest { fix ->
                val nearby = withContext(Dispatchers.IO) {
                    runCatching { db.matchNearbyServing(fix.latitude, fix.longitude, station) }.getOrNull()
                }
                servingParam = nearby
            }
        }
    }

    val map = area
    val currentViewport = viewport
    val sectorCandidates by produceState(emptyList<EngineeringParam>(), currentViewport, netFilter, paramsCount) {
        val bounds = currentViewport
        value = if (bounds == null || bounds.zoom < BaiduMapArea.SECTOR_ZOOM || (paramsCount ?: 0) == 0) {
            emptyList()
        } else {
            delay(100L)
            withContext(Dispatchers.IO) {
                runCatching {
                    db.getByRange(
                        bounds.minLat - bounds.latSpan * 0.16, bounds.minLon - bounds.lonSpan * 0.16,
                        bounds.maxLat + bounds.latSpan * 0.16, bounds.maxLon + bounds.lonSpan * 0.16,
                    ).filter {
                        BaiduMapArea.isValid(it.lat, it.lon) &&
                            (netFilter == "全部" || BaiduMapArea.netTypeLabel(it.netType) == netFilter)
                    }
                }.getOrDefault(emptyList())
            }
        }
    }
    /* 卫星/普通瓦片切换：SDK 内置能力（BaiduMap.MAP_TYPE_SATELLITE），切换仅
     * 重绘底图，扇区/打点/连线覆盖物不受影响；卫星图层级上限低于普通图。 */
    LaunchedEffect(ready, satellite) {
        area?.baiduMap?.setMapType(
            if (satellite) BaiduMap.MAP_TYPE_SATELLITE else BaiduMap.MAP_TYPE_NORMAL
        )
    }
    LaunchedEffect(map, ready, currentViewport, sectorCandidates, selected?.id, servingParam?.id, showLabels) {
        if (map == null || !ready || currentViewport == null) return@LaunchedEffect
        val important = setOfNotNull(selected?.id, servingParam?.id)
        val candidates = sectorCandidates
        val (visible, displayed) = withContext(Dispatchers.Default) {
            val within = candidates.count { currentViewport.contains(it.lat, it.lon) }
            val far = currentViewport.zoom < BaiduMapArea.CLOSE_ZOOM
            val sorted = if (far && candidates.size > 800) {
                candidates.sortedWith(compareBy<EngineeringParam> { it.id !in important }
                    .thenBy { !currentViewport.contains(it.lat, it.lon) }
                    .thenBy { currentViewport.distanceFromCenter(it.lat, it.lon) }).take(800)
            } else candidates
            within to sorted
        }
        map.renderSectors(displayed, currentViewport, selected?.id, servingParam?.id, showLabels)
        renderedSectors = displayed.count { currentViewport.contains(it.lat, it.lon) }
        visibleSectors = visible
    }

    LaunchedEffect(map, ready, currentViewport, trackRevision, metric, netFilter, showPoints) {
        if (map == null || !ready || currentViewport == null) return@LaunchedEffect
        val visible = withContext(Dispatchers.IO) {
            if (!showPoints) emptyList() else runCatching {
                with(currentViewport) {
                    pointsDb.getByRange(
                        minLat - latSpan * 0.12, minLon - lonSpan * 0.12,
                        maxLat + latSpan * 0.12, maxLon + lonSpan * 0.12,
                        netFilter, metric == "SINR", 600,
                    )
                }
            }.getOrDefault(emptyList())
        }
        map.renderPoints(visible, metric)
    }

    LifecycleResumeEffect(permissions) {
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { incoming ->
            val previous = location
            if (previous == null || incoming.elapsedRealtimeNanos > previous.elapsedRealtimeNanos &&
                (!previous.hasAccuracy() || incoming.accuracy <= previous.accuracy * 2 ||
                    incoming.elapsedRealtimeNanos - previous.elapsedRealtimeNanos > 15_000_000_000L)
            ) {
                location = incoming
            }
        }
        if (permissions && manager != null) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)) {
                runCatching {
                    manager.getLastKnownLocation(provider)?.takeIf { it.isRecent() }?.let(listener::onLocationChanged)
                    manager.requestLocationUpdates(provider, 2_000L, 3f, listener)
                }
            }
        } else {
            location = null
        }
        onPauseOrDispose { manager?.removeUpdates(listener) }
    }

    val coordinate = remember(location) {
        location?.let {
            val bd = CoordConverter.wgs84ToBd09(it.latitude, it.longitude)
            LatLng(bd[0], bd[1])
        }
    }
    val neighborMatches by produceState(
        emptyMap<MapNeighborCell, EngineeringParam>(), neighborCells, location, paramsCount, showLink,
    ) {
        val fix = location
        value = if (fix == null || !showLink || (paramsCount ?: 0) == 0) emptyMap() else withContext(Dispatchers.IO) {
            neighborCells.mapNotNull { cell ->
                runCatching { db.matchMeasuredNeighbor(fix.latitude, fix.longitude, cell.isNr, cell.pci, cell.channel) }
                    .getOrNull()?.let { cell to it }
            }.toMap()
        }
    }
    LaunchedEffect(map, ready, showLink, coordinate, servingParam, neighborCells, neighborMatches) {
        if (map == null || !ready) return@LaunchedEffect
        map.updateNeighborLinks(coordinate.takeIf { showLink }, servingParam,
            neighborMatches.filterKeys { it in neighborCells }.values)
    }
    LaunchedEffect(map, ready, coordinate, servingParam, follow, showLink) {
        if (map == null || !ready) return@LaunchedEffect
        val current = coordinate
        if (current != null) {
            map.updateLocation(current.latitude, current.longitude, true, 0f)
            map.updateLink(servingParam, current.latitude, current.longitude, showLink)
            val fix = location
            val previous = lastCameraFix
            if (follow && fix != null && (!cameraPositioned || previous == null || previous.distanceTo(fix) >= 6f)) {
                lastCameraFix = fix
                if (!cameraPositioned) {
                    cameraPositioned = true
                    map.baiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(current, 16f))
                } else {
                    map.baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLng(current))
                }
            }
        } else {
            map.updateLocation(0.0, 0.0, false, 0f)
            map.updateLink(null, 0.0, 0.0, false)
            val serving = servingParam
            if (!cameraPositioned && follow && serving != null && BaiduMapArea.isValid(serving.lat, serving.lon)) {
                val bd = CoordConverter.wgs84ToBd09(serving.lat, serving.lon)
                map.baiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(LatLng(bd[0], bd[1]), 16f))
                cameraPositioned = true
            }
        }
    }

    LifecycleResumeEffect(context) {
        permissions = mapLocationPermissions(context)
        onPauseOrDispose {}
    }

    fun locate() {
        if (!permissions) {
            requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager?.isLocationEnabled != true) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        follow = true
        lastCameraFix = location
        if (coordinate == null) {
            Toast.makeText(context, "正在等待定位", Toast.LENGTH_SHORT).show()
        } else {
            map?.baiduMap?.animateMapStatus(MapStatusUpdateFactory.newLatLng(coordinate))
        }
    }

    val colors = MaterialTheme.colorScheme
    val sectorStatus = when {
        paramsLoaded && paramsCount == null -> "无法读取工参"
        paramsCount == 0 -> "未导入工参"
        currentViewport != null && currentViewport.zoom < BaiduMapArea.SECTOR_ZOOM -> "放大查看扇区"
        visibleSectors == null -> null
        renderedSectors < (visibleSectors ?: 0) -> "$renderedSectors / $visibleSectors 个扇区"
        else -> "视野内 $visibleSectors 个扇区"
    }
    val linkStatus = when {
        !permissions -> "等待定位权限"
        coordinate == null -> "等待定位"
        servingStation == null -> "等待服务小区"
        !showLink -> "连线已关闭"
        servingParam == null -> "未匹配工参"
        else -> "已匹配服务小区"
    }
    val trackingLabel = when {
        !autoTrack -> "打点已暂停"
        !permissions -> "授予定位权限"
        coordinate == null || servingStation == null -> "等待打点"
        else -> "自动打点"
    }
    fun toggleTracking() {
        if (permissions || !autoTrack) {
            autoTrack = !autoTrack
            MapTrack.setEnabled(context, autoTrack)
        }
        if (autoTrack && !permissions) requestLocation.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TitleBar(
                title = stringResource(R.string.tool_tower_map_title),
                compact = true,
                onBackClick = onBackButtonPressed,
                appBarActions = listOf(
                    AppBarAction(R.drawable.ic_search_24, R.string.tower_map_search_action, onClick = { showSearch = true }),
                    AppBarAction(R.drawable.ic_layers, R.string.tower_map_layers_action, onClick = { showLayers = true }),
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            MapSignalCard(
                station = servingStation,
                cell = servingParam,
                linkStatus = linkStatus,
                pointCount = pointCount,
                tracking = autoTrack && permissions,
                trackingLabel = trackingLabel,
                onTracking = ::toggleTracking,
                onOpenCell = {
                    servingParam?.let { param ->
                        selected = param
                        detailOrigin = param
                        detail = param
                    }
                },
                modifier = Modifier.padding(horizontal = DashboardCardDefaults.margin, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DashboardCardDefaults.margin),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("全部", "4G", "5G").forEach { filter ->
                    FilterChip(
                        selected = netFilter == filter,
                        onClick = {
                            netFilter = filter
                            selected = selected?.takeIf {
                                filter == "全部" || BaiduMapArea.netTypeLabel(it.netType) == filter
                            }
                        },
                        label = { Text(filter) },
                    )
                }
                sectorStatus?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(colors.surfaceContainerLow)) {
                BaiduMapView(
                    onCreate = { created ->
                        created.apply {
                            showZoomControls(false)
                            val renderer = BaiduMapArea(this)
                            val baidu = renderer.baiduMap
                            baidu.setBuildingsEnabled(false)
                            baidu.setIndoorEnable(false)
                            baidu.uiSettings.setRotateGesturesEnabled(false)
                            baidu.uiSettings.setOverlookingGesturesEnabled(false)
                            baidu.setMaxAndMinZoomLevel(21f, BaiduMapArea.MAP_MIN_ZOOM)
                            baidu.setOnMapLoadedCallback {
                                if (area === renderer) {
                                    ready = true
                                    viewport = renderer.viewport(baidu.mapStatus)
                                }
                            }
                            baidu.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
                                override fun onMapStatusChangeStart(status: MapStatus?) = Unit
                                override fun onMapStatusChangeStart(status: MapStatus?, reason: Int) {
                                    if (area !== renderer) return
                                    if (reason == BaiduMap.OnMapStatusChangeListener.REASON_GESTURE) {
                                        follow = false
                                        cameraPositioned = true
                                    }
                                }
                                override fun onMapStatusChange(status: MapStatus?) = Unit
                                override fun onMapStatusChangeFinish(status: MapStatus?) {
                                    if (ready && area === renderer) viewport = renderer.viewport(status)
                                }
                            })
                            baidu.setOnMarkerClickListener { marker ->
                                if (area !== renderer) return@setOnMarkerClickListener false
                                val param = renderer.paramOf(marker) ?: return@setOnMarkerClickListener false
                                selected = param
                                detailOrigin = param
                                detail = param
                                true
                            }
                            area = renderer
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { released ->
                        if (area?.mapView === released) {
                            area = null
                            ready = false
                            viewport = null
                        }
                    },
                )
                if (!ready && area != null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
                }
                location?.let { fix ->
                    MapLocationCard(
                        fix, showLocationInfo, onToggle = { showLocationInfo = !showLocationInfo },
                        Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 264.dp),
                    )
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MapControl(
                        icon = Icons.Default.MyLocation,
                        description = if (follow) "回到当前位置" else "定位并跟随",
                        selected = follow && coordinate != null,
                        onClick = ::locate,
                    )
                    Surface(
                        shape = DashboardCardDefaults.smallShape,
                        color = DashboardCardDefaults.cardContainerColor(),
                        border = BorderStroke(1.dp, DashboardCardDefaults.outlineVariant()),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(enabled = ready, onClick = {
                                follow = false
                                map?.baiduMap?.animateMapStatus(MapStatusUpdateFactory.zoomIn())
                            }) { Icon(Icons.Default.Add, "放大地图") }
                            androidx.compose.material3.HorizontalDivider(Modifier.width(30.dp), color = colors.outlineVariant)
                            IconButton(enabled = ready, onClick = {
                                follow = false
                                map?.baiduMap?.animateMapStatus(MapStatusUpdateFactory.zoomOut())
                            }) { Icon(Icons.Default.Remove, "缩小地图") }
                        }
                    }
                }
            }
        }
    }

    if (showSearch) {
        TowerSearchSheet(
            onDismiss = { showSearch = false },
            onResult = { param ->
                showSearch = false
                selected = param
                detailOrigin = param
                detail = param
                if (BaiduMapArea.isValid(param.lat, param.lon)) {
                    follow = false
                    cameraPositioned = true
                    if (netFilter != "全部" && netFilter != BaiduMapArea.netTypeLabel(param.netType)) netFilter = "全部"
                    val bd = CoordConverter.wgs84ToBd09(param.lat, param.lon)
                    map?.baiduMap?.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(LatLng(bd[0], bd[1]), 18f))
                }
            },
            db = db,
        )
    }

    val nearbySectors by produceState(emptyList<EngineeringParam>(), detailOrigin?.id, db) {
        val origin = detailOrigin
        value = if (origin == null) emptyList() else withContext(Dispatchers.IO) {
            val latitudeRadius = 120.0 / 111_000.0
            val longitudeRadius = latitudeRadius / kotlin.math.cos(Math.toRadians(origin.lat)).coerceAtLeast(0.01)
            val distance = FloatArray(1)
            val neighbors = runCatching {
                db.getByRange(origin.lat - latitudeRadius, origin.lon - longitudeRadius,
                    origin.lat + latitudeRadius, origin.lon + longitudeRadius)
            }.getOrDefault(emptyList())
                .filter { it.id != origin.id && BaiduMapArea.isValid(it.lat, it.lon) }
                .map { candidate ->
                    Location.distanceBetween(origin.lat, origin.lon, candidate.lat, candidate.lon, distance)
                    candidate to distance[0]
                }
                .filter { it.second <= 120f }
                .sortedWith(compareBy<Pair<EngineeringParam, Float>> { it.first.siteName != origin.siteName }
                    .thenBy { it.second }.thenBy { it.first.azimuth }.thenBy { it.first.id })
                .map { it.first }
            listOf(origin) + neighbors
        }
    }
    detail?.let { param ->
        val index = nearbySectors.indexOfFirst { it.id == param.id }
        fun selectNearby(offset: Int) {
            val next = nearbySectors[(index + offset + nearbySectors.size) % nearbySectors.size]
            selected = next
            detail = next
            if (netFilter != "全部" && netFilter != BaiduMapArea.netTypeLabel(next.netType)) netFilter = "全部"
        }
        EngineeringSectorSheet(
            param,
            onDismiss = { detail = null; detailOrigin = null },
            nearbyPosition = if (index >= 0 && nearbySectors.size > 1) (index + 1) to nearbySectors.size else null,
            onPreviousSector = { selectNearby(-1) },
            onNextSector = { selectNearby(1) },
        )
    }

    if (showLayers) {
        MapLayerSheet(
            showLabels, showPoints, showLink, satellite, metric, pointCount, pointActionBusy,
            onToggleLabels = { showLabels = !showLabels },
            onTogglePoints = { showPoints = !showPoints },
            onToggleLink = { showLink = !showLink },
            onToggleSatellite = { satellite = !satellite },
            onMetric = { metric = it },
            onDismiss = { showLayers = false },
            onClear = {
                showLayers = false
                confirmClear = true
            },
            onExport = {
                pointActionBusy = true
                scope.launch {
                    val path = withContext(Dispatchers.IO) {
                        runCatching { pointsDb.exportCsv(context.cacheDir) }.getOrNull()
                    }
                    val shared = path != null && runCatching {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(path))
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享打点数据"))
                    }.isSuccess
                    if (!shared) Toast.makeText(context, "无法导出打点", Toast.LENGTH_SHORT).show()
                    pointActionBusy = false
                }
            },
        )
    }

    if (confirmClear) {
        NsConfirmationDialog(
            title = "清空打点？",
            message = "将删除已保存的 ${pointCount} 个打点。",
            confirmText = "清空",
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                pointActionBusy = true
                scope.launch {
                    val deleted = runCatching { MapTrack.clear(context) }.isSuccess
                    if (deleted) pointCount = 0
                    else Toast.makeText(context, "清空失败", Toast.LENGTH_SHORT).show()
                    pointActionBusy = false
                }
            },
        )
    }
}

@Composable
private fun MapSignalCard(
    station: StationInfo?,
    cell: EngineeringParam?,
    linkStatus: String,
    pointCount: Int,
    tracking: Boolean,
    trackingLabel: String,
    onTracking: () -> Unit,
    onOpenCell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DashboardCardDefaults.shape,
        color = DashboardCardDefaults.cardContainerColor(),
        border = BorderStroke(1.dp, DashboardCardDefaults.outlineVariant()),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val technology = station?.let { BaiduMapArea.netTypeLabel(it.networkType) }
                if (technology != null && technology != "未知") {
                    Surface(shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                        Text(technology, Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(
                    Modifier.weight(1f).then(if (cell == null) Modifier else Modifier.clickable(onClick = onOpenCell)),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        cell?.displayName ?: "当前服务小区",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("$linkStatus · $pointCount 个打点", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                FilterChip(selected = tracking, onClick = onTracking,
                    label = { Text(trackingLabel, style = MaterialTheme.typography.labelMedium) })
            }
            if (station != null) {
                val rsrp = station.rsrp.takeIf { it in -160..-20 }
                val rsrq = station.rsrq.takeIf { it in -45..10 }
                val sinr = station.sinr.takeIf { it in -40..50 }
                val nr = BaiduMapArea.is5G(station.networkType)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapSignalValue(if (nr) "SS-RSRP" else "RSRP", rsrp?.let { "$it dBm" },
                        Modifier.weight(1.25f), rsrp?.let(SignalGrade::barRatioRsrp))
                    MapSignalValue("RSRQ", rsrq?.let { "$it dB" },
                        Modifier.weight(1f), rsrq?.let(SignalGrade::barRatioRsrq))
                    MapSignalValue("SINR", sinr?.let { "$it dB" },
                        Modifier.weight(1f), sinr?.let(SignalGrade::barRatioSinr))
                    MapSignalValue("PCI", station.pci.takeIf { it >= 0 }?.toString(), Modifier.weight(0.65f))
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val channel = station.earfcn.takeIf { it >= 0 }
                    if (channel != null) Text("频点 $channel", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                    if (station.mcc > 0) Text("PLMN ${station.mcc}/${station.mnc}",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    if (station.tac > 0) Text("TAC ${station.tac}",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MapSignalValue(label: String, value: String?, modifier: Modifier, progress: Float? = null) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value ?: "—", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        if (progress != null) LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp),
            color = colors.primary, trackColor = colors.surfaceContainerHighest,
            gapSize = 0.dp, drawStopIndicator = {},
        )
    }
}

@Composable
private fun MapLocationCard(fix: Location, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val accuracy = if (fix.hasAccuracy()) " · ±${fix.accuracy.toInt()} 米" else ""
    Surface(
        modifier = modifier,
        shape = DashboardCardDefaults.smallShape,
        color = DashboardCardDefaults.cardContainerColor(),
        border = BorderStroke(1.dp, DashboardCardDefaults.outlineVariant()),
    ) {
        Column {
            Row(
                Modifier.clickable(onClick = onToggle).padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(15.dp), tint = colors.primary)
                Text("定位$accuracy", style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "收起定位信息" else "展开定位信息", Modifier.size(17.dp))
            }
            if (expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(String.format(Locale.ROOT, "纬度 %.6f°", fix.latitude), style = MaterialTheme.typography.bodySmall)
                    Text(String.format(Locale.ROOT, "经度 %.6f°", fix.longitude), style = MaterialTheme.typography.bodySmall)
                    if (fix.hasSpeed()) Text("速度 ${(fix.speed * 3.6f).toInt()} km/h", style = MaterialTheme.typography.bodySmall)
                    if (fix.hasAltitude()) Text("高度 ${fix.altitude.toInt()} 米", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MapControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = DashboardCardDefaults.smallShape,
        color = if (selected) colors.primaryContainer else DashboardCardDefaults.cardContainerColor(),
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, DashboardCardDefaults.outlineVariant()),
    ) {
        IconButton(onClick = onClick) { Icon(icon, description) }
    }
}

private fun mapLocationPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun Location.isRecent(): Boolean =
    SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos in 0..30_000_000_000L

@Composable
private fun rememberMapMeasurements(initial: ServingCellInfo?, subscription: Int?): MapMeasurements? {
    val context = LocalContext.current.applicationContext
    val updates = remember(initial, subscription) {
        MutableStateFlow(initial?.takeIf { subscription == null || it.subscriptionId == subscription }
            ?.let { MapMeasurements(it, emptySet()) })
    }
    LifecycleResumeEffect(context, updates) {
        var service: NetworkSurveyService? = null
        val listener = object : ICellularSurveyRecordListener {
            override fun onCellularBatch(cellularGroup: List<CellularRecordWrapper>, subscriptionId: Int) {
                val preferred = subscription ?: SubscriptionManager.getDefaultDataSubscriptionId()
                    .takeIf { SubscriptionManager.isValidSubscriptionId(it) }
                if (preferred != null && subscriptionId != preferred &&
                    subscriptionId != com.craxiom.networksurvey.services.controller.CellularController.DEFAULT_SUBSCRIPTION_ID
                ) return
                val serving = cellularGroup.mapServingCell()
                if (serving != null) {
                    val primary = serving.toStationInfo()
                    val neighbors = cellularGroup.asSequence().mapNotNull { it.mapNeighborCell() }
                        .filterNot { primary != null && it.pci == primary.pci && it.channel == primary.earfcn &&
                            it.isNr == (primary.networkType == "NR") }
                        .toSet()
                    updates.value = MapMeasurements(ServingCellInfo(serving, subscriptionId), neighbors)
                } else if (preferred != null || updates.value?.serving?.subscriptionId == subscriptionId) {
                    updates.value = null
                }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? NetworkSurveyService.SurveyServiceBinder)?.service as? NetworkSurveyService
                service?.registerCellularSurveyRecordListener(listener)
                service?.runSingleCellularScan()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service?.unregisterCellularSurveyRecordListener(listener)
                service = null
            }
        }
        val bound = context.bindService(Intent(context, NetworkSurveyService::class.java), connection, Context.BIND_AUTO_CREATE)
        onPauseOrDispose {
            service?.unregisterCellularSurveyRecordListener(listener)
            if (bound) context.unbindService(connection)
        }
    }
    return updates.collectAsStateWithLifecycle().value
}

private data class MapMeasurements(val serving: ServingCellInfo, val neighbors: Set<MapNeighborCell>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapLayerSheet(
    labels: Boolean,
    points: Boolean,
    link: Boolean,
    satellite: Boolean,
    metric: String,
    pointCount: Int,
    busy: Boolean,
    onToggleLabels: () -> Unit,
    onTogglePoints: () -> Unit,
    onToggleLink: () -> Unit,
    onToggleSatellite: () -> Unit,
    onMetric: (String) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    AppBottomSheet(show = true, title = "地图图层", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = labels, onClick = onToggleLabels, label = { Text("扇区名称") })
                FilterChip(selected = points, onClick = onTogglePoints, label = { Text("打点") })
                FilterChip(selected = link, onClick = onToggleLink, label = { Text("小区连线") })
                FilterChip(selected = satellite, onClick = onToggleSatellite, label = { Text("卫星图") })
            }
            if (link) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MapLegendEntry("服务小区", BaiduMapArea.SERVING_LINK_COLOR)
                    MapLegendEntry("邻区", BaiduMapArea.NEIGHBOR_LINK_COLOR)
                }
            }
            Text("扇区颜色", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    "移动" to BaiduMapArea.COLOR_CMCC, "联通" to BaiduMapArea.COLOR_CUCC,
                    "电信" to BaiduMapArea.COLOR_CTCC, "广电" to BaiduMapArea.COLOR_CBN,
                ).forEach { (name, color) -> MapLegendEntry(name, color) }
            }
            Text("打点指标", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("RSRP", "SINR").forEach { item ->
                    FilterChip(selected = metric == item, onClick = { onMetric(item) }, label = { Text(item) })
                }
            }
            val sinr = metric == "SINR"
            val grades = if (sinr) listOf(30, 20, 12, 5, -5) else listOf(-70, -90, -100, -110, -125)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                grades.forEach { grade -> MapLegendEntry("$grade", BaiduMapArea.gradeColor(grade, sinr)) }
                Text(if (sinr) "dB" else "dBm", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onExport, enabled = !busy && pointCount > 0) { Text("导出 CSV") }
                TextButton(onClick = onClear, enabled = !busy && pointCount > 0) { Text("清空打点") }
            }
        }
    }
}

@Composable
private fun MapLegendEntry(label: String, color: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(Color(color), CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TowerSearchSheet(
    onDismiss: () -> Unit,
    onResult: (EngineeringParam) -> Unit,
    db: EngineeringParamDbHelper,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<EngineeringParam>()) }
    var completedQuery by remember { mutableStateOf<String?>(null) }
    val term = query.trim()
    LaunchedEffect(term, db) {
        if (term.isEmpty()) {
            results = emptyList()
            completedQuery = null
        } else {
            delay(220)
            results = withContext(Dispatchers.IO) { runCatching { db.search(term, 50) }.getOrDefault(emptyList()) }
            completedQuery = term
        }
    }
    AppBottomSheet(show = true, title = "搜索工参", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                label = { Text("小区名、站名、CI、PCI 或频点") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "清除搜索") }
                    }
                },
            )
            when {
                term.isEmpty() -> Unit
                completedQuery != term -> Text("正在搜索…", Modifier.padding(12.dp))
                results.isEmpty() -> Text("未找到匹配工参", Modifier.padding(12.dp))
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(results, key = { it.id }) { param ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { onResult(param) }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(param.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "PCI ${param.pci} · ${BaiduMapArea.netTypeLabel(param.netType)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ==================== 工参详情弹窗 ==================== */
