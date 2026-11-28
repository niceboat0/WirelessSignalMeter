package com.craxiom.networksurvey.ui.wifi

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.model.WifiNetwork
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults
import com.craxiom.networksurvey.ui.wifi.components.SignalMeterWide
import com.craxiom.networksurvey.ui.wifi.components.WifiGroupChildRow
import com.craxiom.networksurvey.ui.wifi.components.WifiGroupParentRow
import com.craxiom.networksurvey.ui.wifi.components.WifiRowA
import com.craxiom.networksurvey.ui.wifi.components.WifiSortSheet
import com.craxiom.networksurvey.ui.wifi.model.WifiDisplayItem
import com.craxiom.networksurvey.ui.wifi.model.WifiListMode
import com.craxiom.networksurvey.ui.wifi.model.WifiListViewModel
import com.craxiom.networksurvey.util.WifiBand
import com.craxiom.networksurvey.util.frequencyMhzToWifiBand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiListScreen(
    viewModel: WifiListViewModel,
    onNetworkSelected: (WifiNetwork) -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connection = rememberWifiConnection()
    var sort by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val disabled = state.scanStatusId == R.string.wifi_scan_status_disabled
    val openDetails: (String) -> Unit = { bssid ->
        viewModel.wrapperFor(bssid)?.let { onNetworkSelected(WifiNetwork.from(it)) }
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("current") { WifiConnectionCard(connection, disabled) }
        item("nearby") {
            Surface(shape = DashboardCardDefaults.shape, color = DashboardCardDefaults.cardContainerColor()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("附近网络", style = MaterialTheme.typography.titleMedium)
                            val count = if (state.filter.isActive) "${state.visibleApCount} / ${state.apCount} 个接入点"
                                else "${state.apCount} 个接入点"
                            Text("$count · ${stringResource(state.scanStatusId)}", style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { sort = true }) { Icon(Icons.Rounded.Sort, "排序") }
                        IconButton(onClick = onTogglePause, enabled = !disabled) {
                            Icon(if (state.updatesPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                if (state.updatesPaused) "继续更新" else "暂停更新")
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(WifiListMode.GROUPED to "按网络分组", WifiListMode.FLAT to "按接入点")
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(state.mode == mode, { viewModel.setMode(mode) },
                                SegmentedButtonDefaults.itemShape(index, modes.size)) { Text(label) }
                        }
                    }
                }
            }
        }
        if (disabled || state.items.isEmpty()) item("empty") {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        disabled -> "请开启 Wi-Fi 后查看附近网络"
                        state.filter.isActive && state.apCount > 0 -> stringResource(R.string.wifi_filter_empty_state)
                        state.updatesPaused -> "扫描已暂停"
                        state.scanNumber == 0 -> "正在等待首次扫描"
                        else -> "暂未扫描到附近网络"
                    }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
                if (!disabled && state.filter.isActive && state.apCount > 0) {
                    TextButton(onClick = viewModel::clearFilter) { Text(stringResource(R.string.wifi_filter_clear)) }
                }
            }
        }
        if (!disabled) items(state.items, key = { it.key }, contentType = { it.javaClass }) { item ->
            Surface(
                Modifier.padding(start = if (item is WifiDisplayItem.GroupChild) 12.dp else 0.dp).fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = if (item is WifiDisplayItem.GroupChild) colors.surfaceContainer else DashboardCardDefaults.cardContainerColor(),
            ) {
                when (item) {
                    is WifiDisplayItem.Flat -> WifiRowA(ap = item.ap, onClick = { openDetails(item.ap.bssid) })
                    is WifiDisplayItem.GroupParent -> WifiGroupParentRow(group = item, onToggle = { viewModel.toggleGroupExpanded(item.groupKey) })
                    is WifiDisplayItem.GroupChild -> WifiGroupChildRow(ap = item.ap, onClick = { openDetails(item.ap.bssid) })
                }
            }
        }
    }
    if (sort) WifiSortSheet(
        selectedIndex = state.sortIndex, onSelected = viewModel::setSortIndex, onDismiss = { sort = false },
    )
}

private data class WifiConnection(val name: String, val rssi: Int?, val mhz: Int?, val linkMbps: Int?)

private interface WifiConnectionCallbackListener {
    fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities)
    fun onLost(network: Network)
}

private fun createWifiConnectionCallback(listener: WifiConnectionCallbackListener): ConnectivityManager.NetworkCallback {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return createLocationAwareWifiConnectionCallback(listener)
    return object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            listener.onCapabilitiesChanged(network, capabilities)

        override fun onLost(network: Network) = listener.onLost(network)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun createLocationAwareWifiConnectionCallback(
    listener: WifiConnectionCallbackListener,
): ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback(
    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
) {
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
        listener.onCapabilitiesChanged(network, capabilities)

    override fun onLost(network: Network) = listener.onLost(network)
}

@Composable
private fun rememberWifiConnection(): WifiConnection? {
    val context = LocalContext.current.applicationContext
    var connection by remember(context) { mutableStateOf<WifiConnection?>(null) }
    LifecycleResumeEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wifiManager = context.getSystemService(WifiManager::class.java)
        var active: Network? = null
        val handler = Handler(Looper.getMainLooper())
        var resumed = true
        fun update(info: WifiInfo?) {
            val name = info?.ssid?.takeUnless { it == "<unknown ssid>" }?.trim('"').orEmpty()
            connection = info?.let {
                WifiConnection(name, it.rssi.takeIf { value -> value in -126..-1 },
                    it.frequency.takeIf { value -> value > 0 }, it.linkSpeed.takeIf { value -> value > 0 })
            }
        }
        @Suppress("DEPRECATION")
        val lastInfo = runCatching { wifiManager?.connectionInfo }.getOrNull()
        update(lastInfo?.takeIf { it.networkId >= 0 && wifiManager?.isWifiEnabled == true })
        val listener = object : WifiConnectionCallbackListener {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val info = capabilities.transportInfo as? WifiInfo
                handler.post {
                    if (resumed && info != null) {
                        active = network
                        update(info)
                    }
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (resumed && active == network) {
                        active = null
                        connection = null
                    }
                }
            }
        }
        val callback = createWifiConnectionCallback(listener)
        val registered = manager != null && runCatching {
            manager.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        }.isSuccess
        onPauseOrDispose {
            resumed = false
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }
    return connection
}

@Composable
private fun WifiConnectionCard(connection: WifiConnection?, disabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = DashboardCardDefaults.shape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                    Icon(Icons.Rounded.Wifi, null, Modifier.padding(9.dp).size(21.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("当前连接", style = MaterialTheme.typography.labelLarge)
                    Text(
                        when {
                            disabled -> "Wi-Fi 已关闭"
                            connection == null -> "未连接 Wi-Fi"
                            else -> connection.name.ifBlank { "已连接 Wi-Fi" }
                        },
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!disabled && connection != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(connection.rssi?.let { "$it dBm" } ?: "— dBm", style = MaterialTheme.typography.headlineMedium)
                    val band = when (frequencyMhzToWifiBand(connection.mhz)) {
                        WifiBand.GHZ_24 -> "2.4 GHz"
                        WifiBand.GHZ_5 -> "5 GHz"
                        WifiBand.GHZ_6 -> "6 GHz"
                        null -> null
                    }
                    if (band != null) Text(band, style = MaterialTheme.typography.labelLarge)
                }
                SignalMeterWide(connection.rssi)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    connection.mhz?.let { mhz ->
                        val channel = ScanResult.convertFrequencyMhzToChannelIfSupported(mhz)
                        Text(if (channel > 0) "信道 $channel · $mhz MHz" else "$mhz MHz", style = MaterialTheme.typography.bodySmall)
                    }
                    connection.linkMbps?.let { Text("链路 $it Mbps", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
