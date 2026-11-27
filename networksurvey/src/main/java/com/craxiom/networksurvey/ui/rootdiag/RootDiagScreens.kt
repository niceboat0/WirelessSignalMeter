package com.craxiom.networksurvey.ui.rootdiag

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.common.AppDialog
import com.craxiom.networksurvey.ui.common.AppMenu
import com.craxiom.networksurvey.ui.common.AppMenuItem
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults
import com.craxiom.networksurvey.ui.main.appbar.AppBarAction
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.ui.util.copyTextToClipboard
import com.craxiom.networksurvey.util.CellularUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Scaffold

internal data class DiagField(val label: String, val value: String)

private val recordNames = mapOf(
    "lte_cell" to "LTE",
    "nr_cell" to "NR",
    "lte_meas" to "LTE ML1",
    "nr_meas" to "NR ML1",
    "nr_mib" to "NR MIB",
    "nr_snr" to "NR SINR",
    "nr_cfg" to "NR RRC",
    "nas" to "NAS",
    "rrc" to "RRC",
    "nr_pdsch" to "PDSCH",
    "nr_dl_sched" to "PDSCH",
    "nr_ul_sched" to "PUSCH",
    "nr_ul_pwr" to "UL Tx Power",
    "nr_csf" to "CSI",
    "nas_mm" to "NAS · MM",
    "nas_release" to "NAS · Session release",
    "nas_pdu" to "NAS · PDU / PDN",
    "nas_qos" to "NAS · QoS",
    "ims_state" to "IMS",
    "ims_call" to "IMS Call",
    "ims_reg" to "IMS Registration",
    "ims_reg_log" to "IMS Registration",
    "ims_session" to "IMS Session",
    "ims_media" to "RTP / RTCP",
    "sip" to "IMS SIP",
)

private val fieldLabels = mapOf(
    "rat" to "RAT", "pci" to "Physical Cell ID", "arfcn" to "ARFCN", "earfcn" to "EARFCN",
    "ul_earfcn" to "UL EARFCN", "ul_arfcn" to "UL ARFCN", "band" to "Band",
    "freq_mhz" to "Frequency", "ssb_arfcn" to "SSB ARFCN", "dl_arfcn" to "DL ARFCN",
    "dl_pointa" to "DL Point A", "ul_pointa" to "UL Point A", "ssb" to "SSB Beam Index",
    "scs_khz" to "SCS", "rsrp" to "RSRP", "rsrq" to "RSRQ", "sinr" to "SINR",
    "rsrp_rx0" to "RSRP Rx0", "rsrp_rx1" to "RSRP Rx1", "rssi" to "RSSI",
    "rsrq_rx0" to "RSRQ Rx0", "rsrq_rx1" to "RSRQ Rx1",
    "rsrp_filt" to "Filtered RSRP", "rsrq_filt" to "Filtered RSRQ",
    "sinr_rx0" to "SINR Rx0", "sinr_rx1" to "SINR Rx1", "ta" to "TA", "cqi" to "CQI",
    "state" to "State", "tac" to "TAC", "cid" to "Cell Identity", "nci" to "NCI", "nci_hex" to "NCI (hex)",
    "mcc" to "MCC", "mnc" to "MNC", "mnc_digits" to "MNC Digits",
    "bw_mhz" to "Bandwidth", "dl_bw_mhz" to "DL Bandwidth", "ul_bw_mhz" to "UL Bandwidth",
    "tx_antennas" to "Tx Antennas",
    "dl_bw_prb" to "DL Bandwidth (PRB)", "ul_bw_prb" to "UL Bandwidth (PRB)",
    "cc" to "CC", "nrb" to "RBs", "max_layers" to "Max Layers", "layers_avg" to "Layers Avg",
    "rb_avg" to "RBs Avg", "mcs_avg" to "MCS Avg", "mcs_max" to "MCS Max", "mcs_mode" to "MCS Mode",
    "rank" to "Rank", "cqi_min" to "CQI Min", "cqi_max" to "CQI Max", "cqi_avg" to "CQI Avg",
    "mod" to "Modulation", "mod_hist" to "Modulation Counts", "tx_mode" to "Tx Mode",
    "bler_pct" to "BLER", "phy_mbps" to "PHY Throughput", "ul_mbps" to "UL Throughput",
    "mbps" to "Throughput", "pathloss_db" to "Pathloss", "pusch_dbm" to "PUSCH Tx Power",
    "pucch_dbm" to "PUCCH Tx Power", "prach_n" to "PRACH Count",
    "ambr_dl_mbps" to "DL Session-AMBR", "ambr_ul_mbps" to "UL Session-AMBR",
    "dnn" to "DNN", "apn" to "APN", "qci" to "QCI", "5qi" to "5QI", "qfi" to "QFI",
    "ch" to "Channel", "msg" to "Message", "dir" to "Direction", "len" to "Length (bytes)",
    "psi" to "PDU session ID", "ebi" to "EPS bearer ID", "linked_ebi" to "Linked bearer ID",
    "pti" to "Procedure transaction ID", "ngksi" to "ngKSI", "ksi" to "KSI",
    "registration_type" to "Registration type", "registration_result" to "Registration result",
    "attach_type" to "Attach type", "attach_result" to "Attach result",
    "nas_security_header" to "NAS security header type",
    "sib_count" to "SIB count", "first_sib" to "First SIB",
    "ue_capability_containers" to "UE capability RAT containers",
    "requested_reports" to "Requested reports", "included_reports" to "Included reports",
    "rrc_transaction_id" to "RRC transaction ID", "wait_time_s" to "Wait time (s)",
    "cipher_algorithm" to "Ciphering algorithm", "integrity_algorithm" to "Integrity algorithm",
    "sfn_subframe" to "SFN/subframe (packed)", "reg" to "Registration", "reg_expires" to "Registration expiry (s)",
    "ims_pdn" to "IMS data session", "call" to "Call state", "why" to "Last event",
    "ver" to "Packet version", "rrc_release" to "RRC release", "status" to "Response Code", "dt_ms" to "Interval (ms)",
    "access" to "Access Network", "codec" to "Codec", "cause" to "Cause", "src" to "Log Code",
    "from_pci" to "Source PCI", "to_pci" to "Target PCI",
    "from_arfcn" to "Source ARFCN", "to_arfcn" to "Target ARFCN",
    "task_id" to "Task ID", "received_time_ms" to "Received time (ms)",
    "received_elapsed_ms" to "Received monotonic time (ms)", "clock_id" to "Clock domain",
    "sinr_rx2" to "SINR Rx2", "sinr_rx3" to "SINR Rx3",
    "retx_pct" to "Retransmission", "crc_fail" to "CRC failures",
    "meas_event" to "Measurement event", "quantity" to "Quantity",
    "threshold_raw" to "Threshold (raw)", "threshold_dbm" to "Threshold", "threshold_db" to "Threshold",
    "hysteresis_db" to "Hysteresis", "ttt_ms" to "Time to trigger (ms)", "offset_db" to "Offset",
    "meas_id" to "Measurement ID", "report_id" to "Report config ID", "object_id" to "Measurement object ID",
    "object_earfcn" to "Object EARFCN", "object_arfcn" to "Object ARFCN",
)

private fun valueOf(key: String, value: Any): String = when {
    key.endsWith("_dbm") || key in setOf("rsrp", "rsrp_rx0", "rsrp_rx1", "rssi", "rsrp_filt") -> "$value dBm"
    key.endsWith("_db") || key in setOf("rsrq", "sinr", "rsrq_filt", "rsrq_rx0", "rsrq_rx1", "sinr_rx0", "sinr_rx1", "sinr_rx2", "sinr_rx3") -> "$value dB"
    key.endsWith("_mhz") -> "$value MHz"
    key.endsWith("_khz") -> "$value kHz"
    key.endsWith("mbps") -> "$value Mbps"
    key.endsWith("_pct") -> "$value%"
    else -> value.toString()
}

private val nasNames = mapOf(
    "5GMM 0x41" to "Registration Request", "5GMM 0x42" to "Registration Accept",
    "5GMM 0x43" to "Registration Complete", "5GMM 0x44" to "Registration Reject",
    "5GMM 0x4C" to "Service Request", "5GMM 0x4E" to "Service Accept",
    "5GMM 0x56" to "Authentication Request", "5GMM 0x57" to "Authentication Response",
    "5GMM 0x59" to "Authentication Failure", "5GMM 0x4D" to "Service Reject",
    "5GMM 0x5F" to "Security Mode Reject", "5GMM 0x64" to "5GMM Status",
    "5GMM 0x5B" to "Identity Request", "5GMM 0x5C" to "Identity Response",
    "5GMM 0x54" to "Configuration Update Command", "5GMM 0x55" to "Configuration Update Complete",
    "5GMM 0x5D" to "Security Mode Command", "5GMM 0x5E" to "Security Mode Complete",
    "5GMM 0x67" to "UL NAS Transport", "5GMM 0x68" to "DL NAS Transport",
    "5GSM 0xC1" to "PDU Session Establishment Request", "5GSM 0xC2" to "PDU Session Establishment Accept",
    "5GSM 0xCB" to "PDU Session Modification Command", "5GSM 0xD3" to "PDU Session Release Command",
    "EMM 0x41" to "Attach Request", "EMM 0x42" to "Attach Accept", "EMM 0x43" to "Attach Complete",
    "ESM 0xC1" to "Activate Default EPS Bearer Request", "ESM 0xC5" to "Activate Dedicated EPS Bearer Request",
    "ESM 0xCD" to "Deactivate EPS Bearer Request",
)

internal fun DiagRecord.title(): String = data.optString("display_msg").ifBlank {
    data.optString("msg").let { nasNames[it] ?: it }
}.ifBlank {
    data.optString("event").ifBlank {
        data.optString("method").ifBlank {
            if (type == "sip" && data.has("status")) "SIP ${data.optInt("status")}"
            else if (type == "rrc" && data.optString("ch") !in setOf("", "?")) data.optString("ch")
            else recordNames[type] ?: type
        }
    }
}

internal fun DiagRecord.isEstimated(): Boolean = data.optInt("tentative") == 1 || data.optInt("guess") == 1


private val signalTechnicalFields = setOf(
    "type", "t", "src", "meas_src", "sinr_src", "ver", "guess", "layout",
    "tentative", "truncated", "mnc_digits", "msg_type", "subver", "idx", "bad_pkts", "psi", "ebi", "head", "raw", "call_id",
)

internal fun JSONObject?.fixedSignalFields(keys: List<String>): List<DiagField> =
    keys.filterNot { it in signalTechnicalFields || it.endsWith("_hex") }.map { key ->
        val value = this?.opt(key)
        val formatted = when {
            value is Number || value is Boolean -> valueOf(key, value)
            value is String && value.isNotBlank() && value != "?" && !value.startsWith("0x", ignoreCase = true) -> valueOf(key, value)
            else -> "—"
        }
        DiagField(fieldLabels[key] ?: key, formatted)
    }

private enum class ProtocolFilter(val label: Int) {
    All(R.string.diag_filter_all), Nr(R.string.diag_filter_nr), Lte(R.string.diag_filter_lte), Nas(R.string.diag_filter_nas),
    Ims(R.string.diag_filter_ims), Event(R.string.diag_filter_event),
}

private enum class SignalingProtocol(val label: Int) {
    Rrc(R.string.diag_filter_rrc), Nas(R.string.diag_filter_nas), Ims(R.string.diag_filter_ims),
    Event(R.string.diag_filter_event),
}

private val DiagRecord.protocol: SignalingProtocol
    get() = when (type) {
        "rrc" -> SignalingProtocol.Rrc
        "nas", "nas_pdu", "nas_qos", "nas_mm", "nas_release" -> SignalingProtocol.Nas
        "sip", "ims_call", "ims_reg", "ims_session", "ims_reg_log", "ims_state" -> SignalingProtocol.Ims
        else -> SignalingProtocol.Event
    }

private val DiagRecord.isUnknownRrc: Boolean
    get() = type == "rrc" && data.optBoolean("decode_incomplete") &&
        data.optString("ch").let { it == "?" || it.startsWith("pdu", ignoreCase = true) }

private val DiagRecord.isPacket: Boolean get() = type == "rrc" || type == "nas" || type == "nas_pdu" || type == "sip"
private val DiagRecord.isTimelineEvent: Boolean
    get() = protocol == SignalingProtocol.Event || data.optBoolean("timeline_event")

private val DiagRecord.direction: String
    get() = data.optString("dir").ifBlank {
        val channel = data.optString("ch")
        when {
            channel.startsWith("UL-") || channel == "RRCReconfigurationComplete(container)" -> "UL"
            channel.startsWith("DL-") || channel.startsWith("BCCH") || channel.startsWith("PCCH") ||
                channel == "MCCH" || channel == "RRCReconfiguration(container)" -> "DL"
            else -> ""
        }
    }

private val DiagRecord.directionArrow: String get() = when (direction) {
    "UL" -> "↑"
    "DL" -> "↓"
    else -> direction
}

private val DiagRecord.channelLabel: String get() = data.optString("ch").let { channel ->
    when (channel) {
        "", "?" -> ""
        else -> if (type == "rrc" && !channel.contains('(') && !channel.startsWith("pdu", ignoreCase = true)) {
            "$channel-Message"
        } else channel
    }
}

@Composable
private fun DiagRecord.signalingHeading(protocol: String): String =
    listOf(formatTime(time), data.optString("rat").ifBlank { protocol }, directionArrow,
        if (type == "cell_change") stringResource(R.string.diag_event_cell_change)
        else title().replaceFirstChar { it.uppercase() }).filter(String::isNotBlank).joinToString("  ")

private fun DiagRecord.signalingInfo(protocol: String): String = buildList {
    val rat = data.optString("rat")
    /* MeasConfig events carry the trigger parameters rather than a message. */
    if (type == "event" && data.optString("event") == "meas_config") {
        fun number(key: String) = data.optDouble(key, Double.NaN).takeIf { it.isFinite() }
            ?.let { String.format(Locale.ROOT, if (it % 1.0 == 0.0) "%.0f" else "%.1f", it) }
        val parts = mutableListOf<String>()
        data.optString("meas_event").takeIf { it.isNotBlank() }?.let(parts::add)
        val quantity = data.optString("quantity")
        val threshold = when {
            data.has("threshold_dbm") -> number("threshold_dbm")?.let { "$it dBm" }
            data.has("threshold_db") -> number("threshold_db")?.let { "$it dB" }
            else -> null
        }
        if (threshold != null) parts.add(listOf(quantity, threshold).filter(String::isNotBlank).joinToString(" "))
        data.optInt("hysteresis_db", -1).takeIf { it >= 0 }?.let { parts.add("Hyst $it dB") }
        data.optInt("ttt_ms", -1).takeIf { it >= 0 }?.let { parts.add("TTT $it ms") }
        data.optInt("offset_db", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { parts.add("Off $it dB") }
        if (parts.isNotEmpty()) add(parts.joinToString("  ·  "))
        return@buildList
    }
    if (type == "cell_change") {
        fun cell(prefix: String): String = buildList {
            data.optInt("${prefix}_pci", -1).takeIf { it >= 0 }?.let { add("PCI $it") }
            data.optInt("${prefix}_arfcn", -1).takeIf { it >= 0 }?.let {
                add("${if (rat == "LTE") "EARFCN" else "NR-ARFCN"} $it")
            }
        }.joinToString(" ")
        add("${cell("from").ifBlank { "?" }} → ${cell("to").ifBlank { "?" }}")
        return@buildList
    }
    if (isTimelineEvent) {
        listOf("cause", "state", "reg", "call").map(data::optString).filter(String::isNotBlank)
            .distinct().forEach { add(it) }
    }
    if (channelLabel.isNotBlank() && !data.optString("ch").equals(title(), ignoreCase = true)) add(channelLabel)
    else if (type != "rrc" && protocol != rat) add(protocol)
    data.optInt("pci", -1).takeIf { it >= 0 }?.let { add("PCI $it") }
    data.optInt("arfcn", -1).takeIf { it >= 0 }?.let {
        val label = when (rat) {
            "NR" -> "NR-ARFCN"
            "LTE" -> "EARFCN"
            else -> "ARFCN"
        }
        add("$label $it")
    }
    data.optInt("reassembled_len", data.optInt("len", -1)).takeIf { it >= 0 }?.let { add("$it B") }
}.joinToString("  ")

private fun DiagRecord.matches(filter: ProtocolFilter, includeDiagnostics: Boolean): Boolean = when (filter) {
    ProtocolFilter.All -> includeDiagnostics || isPacket
    ProtocolFilter.Nr -> protocol == SignalingProtocol.Rrc && data.optString("rat") == "NR"
    ProtocolFilter.Lte -> protocol == SignalingProtocol.Rrc && data.optString("rat") == "LTE"
    ProtocolFilter.Nas -> protocol == SignalingProtocol.Nas || data.has("dedicatedNAS") || data.has("nested_nas")
    ProtocolFilter.Ims -> protocol == SignalingProtocol.Ims
    ProtocolFilter.Event -> isTimelineEvent
}

@Composable
internal fun SignalingScreen(viewModel: RootDiagViewModel, onBack: () -> Unit) {
    val status by viewModel.signal.collectAsStateWithLifecycle()
    val records by viewModel.signaling.collectAsStateWithLifecycle()
    val qmdl by viewModel.qmdl.collectAsStateWithLifecycle()
    val collectionMode by viewModel.collectionMode.collectAsStateWithLifecycle()
    val exportProgress by viewModel.qmdlExportProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmQmdl by rememberSaveable { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var recordDlf by rememberSaveable { mutableStateOf(false) }
    val qmdlExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportRecordings(uri)
    }
    fun chooseQmdlDestination() {
        confirmExport = true
    }
    var filter by rememberSaveable { mutableStateOf(ProtocolFilter.All) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var showPaging by rememberSaveable { mutableStateOf(true) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var openedMessages by remember { mutableStateOf(emptyList<DiagRecord>()) }
    var openedIndex by remember { mutableStateOf(-1) }
    var linkedPacket by remember { mutableStateOf<DiagRecord?>(null) }
    val visible = remember(records, filter, search, showPaging, showDiagnostics) {
        val query = search.trim()
        records.filter { record ->
            val nas = record.data.optString("nested_nas").ifBlank {
                record.data.optJSONObject("dedicatedNAS")?.optString("nas_message_type").orEmpty()
            }
            record.matches(filter, showDiagnostics || query.isNotEmpty()) &&
                (!record.isUnknownRrc || showDiagnostics || query.isNotEmpty()) &&
                (showPaging || query.isNotEmpty() || !record.isPaging) &&
                (query.isEmpty() || record.title().contains(query, ignoreCase = true) ||
                record.data.optString("msg").contains(query, ignoreCase = true) ||
                record.data.optString("event").contains(query, ignoreCase = true) ||
                (nasNames[nas] ?: nas).contains(query, ignoreCase = true))
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLatest = false
        }
    }
    LaunchedEffect(followLatest, visible.firstOrNull()?.id, openedIndex >= 0) {
        if (followLatest && openedIndex < 0 && visible.isNotEmpty()) listState.requestScrollToItem(0)
    }
    val detail = linkedPacket ?: openedMessages.getOrNull(openedIndex)
    if (detail != null) {
        val parent = if (linkedPacket == null) {
            val relatedId = detail.data.optLong("related_message_id", -1)
            records.firstOrNull { it.isPacket && it.id == relatedId }
        } else null
        val close = {
            if (linkedPacket != null) linkedPacket = null
            else {
                openedIndex = -1
                openedMessages = emptyList()
            }
        }
        BackHandler(onBack = close)
        MessageDetailsScreen(
            detail,
            onClose = close,
            onPrevious = if (linkedPacket == null && openedIndex < openedMessages.lastIndex) ({ openedIndex++ }) else null,
            onNext = if (linkedPacket == null && openedIndex > 0) ({ openedIndex-- }) else null,
            onRelatedPacket = parent?.let { ({ linkedPacket = it }) },
        )
        return
    }
    AppDialog(
        show = confirmQmdl,
        onDismissRequest = { confirmQmdl = false },
        title = stringResource(R.string.diag_qmdl_start),
        confirmText = stringResource(R.string.diag_qmdl_start),
        dismissText = stringResource(android.R.string.cancel),
        onConfirm = {
            confirmQmdl = false
            viewModel.startQmdl(if (recordDlf) DiagLogFormat.Dlf else DiagLogFormat.Qmdl)
        },
    ) {
        Text(stringResource(R.string.diag_qmdl_description))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !recordDlf, onClick = { recordDlf = false })
            Text(stringResource(R.string.diag_qmdl_format_qmdl))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = recordDlf, onClick = { recordDlf = true })
            Text(stringResource(R.string.diag_qmdl_format_dlf))
        }
    }
    AppDialog(
        show = confirmExport,
        onDismissRequest = { confirmExport = false },
        title = stringResource(R.string.diag_export_privacy_title),
        confirmText = stringResource(R.string.diag_qmdl_export),
        dismissText = stringResource(android.R.string.cancel),
        onConfirm = {
            confirmExport = false
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            viewModel.prepareRecordingExport(stamp) { name -> qmdlExporter.launch(name) }
        },
    ) { Text(stringResource(R.string.diag_export_privacy_body)) }

    Scaffold(
        topBar = {
            TitleBar(stringResource(R.string.tool_signaling_title), compact = true, onBackClick = onBack,
                appBarActions = listOf(
                    AppBarAction(R.drawable.ic_search_24, R.string.diag_search_messages, onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) search = ""
                    }, active = searchOpen),
                    AppBarAction(R.drawable.ic_filter, R.string.diag_message_options, onClick = { menuOpen = true },
                        active = !showPaging || showDiagnostics),
                ))
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Box(Modifier.align(Alignment.End).padding(end = 12.dp)) {
                    AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val running = status.status in setOf(RootDiagStatus.Checking, RootDiagStatus.Starting, RootDiagStatus.Running)
                        AppMenuItem(
                            title = stringResource(R.string.diag_follow_latest),
                            trailingIcon = { Checkbox(state = ToggleableState(followLatest), onClick = null) },
                            onClick = { menuOpen = false; followLatest = !followLatest },
                        )
                        AppMenuItem(
                            title = stringResource(R.string.diag_clear_short),
                            enabled = records.isNotEmpty(),
                            onClick = { menuOpen = false; viewModel.clearSignaling() },
                        )
                        HorizontalDivider()
                        AppMenuItem(
                            title = stringResource(if (qmdl.phase == QmdlPhase.Idle) R.string.diag_qmdl_start else R.string.diag_qmdl_stop),
                            enabled = qmdl.phase == QmdlPhase.Recording || qmdl.phase == QmdlPhase.Starting ||
                                qmdl.phase == QmdlPhase.Idle && !qmdl.recovering &&
                                    status.status == RootDiagStatus.Running &&
                                    collectionMode == DiagRecordingPolicy.Mode.TaskRecording && exportProgress == null,
                            onClick = {
                                menuOpen = false
                                if (qmdl.phase == QmdlPhase.Idle) confirmQmdl = true else viewModel.stopQmdl()
                            },
                        )
                        if (qmdl.hasRecording) {
                            AppMenuItem(
                                title = if (qmdl.segments > 1) stringResource(R.string.diag_qmdl_export_count, qmdl.segments)
                                    else stringResource(R.string.diag_qmdl_export),
                                enabled = qmdl.phase == QmdlPhase.Idle && !qmdl.recovering && exportProgress == null,
                                onClick = {
                                    menuOpen = false
                                    chooseQmdlDestination()
                                },
                            )
                        }
                        AppMenuItem(
                            title = stringResource(if (running) R.string.diag_pause else R.string.diag_retry),
                            onClick = { menuOpen = false; if (running) viewModel.pause() else viewModel.retry() },
                        )
                        AppMenuItem(
                            title = stringResource(if (showPaging) R.string.diag_hide_paging else R.string.diag_show_paging),
                            onClick = { menuOpen = false; showPaging = !showPaging },
                        )
                        AppMenuItem(
                            title = stringResource(if (showDiagnostics) R.string.diag_hide_diagnostics else R.string.diag_show_diagnostics),
                            onClick = { menuOpen = false; showDiagnostics = !showDiagnostics },
                        )
                    }
            }
            CaptureErrors(status)
            val qmdlLabel = when (qmdl.phase) {
                QmdlPhase.Starting -> R.string.diag_qmdl_starting
                QmdlPhase.Stopping -> R.string.diag_qmdl_stopping
                QmdlPhase.Recording -> R.string.diag_qmdl_recording
                QmdlPhase.Idle -> when (qmdl.result) {
                    QmdlResult.Saved -> R.string.diag_qmdl_saved
                    QmdlResult.Limited -> R.string.diag_qmdl_limit
                    QmdlResult.Interrupted -> R.string.diag_qmdl_interrupted
                    QmdlResult.Empty -> R.string.diag_qmdl_empty
                    QmdlResult.Error -> R.string.diag_qmdl_failed
                    null -> null
                }
            }
            if (qmdlLabel != null || exportProgress != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val progress = exportProgress
                    val error = qmdl.errorCode
                    val label = when {
                        progress != null -> stringResource(R.string.diag_qmdl_exporting, progress)
                        qmdl.phase == QmdlPhase.Recording -> stringResource(R.string.diag_qmdl_recording,
                            if (qmdl.format == DiagLogFormat.Dlf) "DLF" else "QMDL", qmdl.packets,
                            android.text.format.Formatter.formatShortFileSize(context, qmdl.bytes))
                        qmdlLabel != null && error != null ->
                            "${stringResource(qmdlLabel)} (${stringResource(R.string.diag_error_code, error)})"
                        qmdlLabel != null && qmdl.bytes > 0 && qmdl.phase == QmdlPhase.Idle ->
                            "${stringResource(qmdlLabel)} · ${android.text.format.Formatter.formatShortFileSize(context, qmdl.bytes)}"
                        qmdlLabel != null -> stringResource(qmdlLabel)
                        else -> ""
                    }
                    Text(label, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (qmdl.phase == QmdlPhase.Recording || qmdl.phase == QmdlPhase.Starting) {
                        TextButton(onClick = viewModel::stopQmdl) { Text(stringResource(R.string.diag_qmdl_stop)) }
                    } else if (qmdl.phase == QmdlPhase.Idle && qmdl.hasRecording && !qmdl.recovering && progress == null) {
                        TextButton(onClick = { chooseQmdlDestination() }) {
                            Text(if (qmdl.segments > 1) stringResource(R.string.diag_qmdl_export_count, qmdl.segments)
                                else stringResource(R.string.diag_qmdl_export_short))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (option in ProtocolFilter.entries) {
                    SignalListAction(stringResource(option.label), Modifier.weight(1f), selected = filter == option) {
                        filter = option
                    }
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text(stringResource(R.string.diag_search_messages)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { search = ""; searchOpen = false }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.diag_close))
                        }
                    },
                    singleLine = true,
                    shape = DashboardCardDefaults.smallShape,
                )
            }
            if (status.status != RootDiagStatus.Running) {
                Text(
                    status.failureReason?.let { capabilityReasonText(it) } ?: stringResource(status.status.title),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                reverseLayout = visible.isNotEmpty(),
                verticalArrangement = Arrangement.Top,
            ) {
                if (visible.isEmpty()) {
                    item("empty") {
                        val waitingForModem = status.status == RootDiagStatus.Running &&
                            !status.hasModemPackets && filter != ProtocolFilter.Ims
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(when {
                                    waitingForModem && status.modemWaitTimedOut -> R.string.diag_modem_wait_timed_out
                                    waitingForModem -> R.string.diag_waiting_modem_packets
                                    records.isEmpty() -> R.string.diag_no_messages
                                    else -> R.string.diag_no_matching_messages
                                }),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (waitingForModem && status.modemWaitTimedOut) {
                                TextButton(
                                    onClick = viewModel::reconnectSilentCollector,
                                    enabled = qmdl.phase == QmdlPhase.Idle && !qmdl.recovering,
                                ) { Text(stringResource(R.string.diag_reconnect)) }
                            }
                        }
                    }
                }
                items(visible, key = { it.id }) { record ->
                    MessageRow(record) {
                        openedMessages = visible
                        openedIndex = visible.indexOf(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalListAction(label: String, modifier: Modifier, selected: Boolean = false,
                             enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 2.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
            contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        )) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val DiagRecord.isPaging get() = type == "rrc" && title().equals("Paging", ignoreCase = true)

@Composable
private fun CaptureErrors(status: RootSignalState) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        status.errorCode?.let { Text(stringResource(R.string.diag_error_code, it)) }
        if (status.errorDetails.isNotEmpty()) {
            SelectionContainer {
                Text(
                    status.errorDetails.joinToString("\n"),
                    modifier = Modifier.heightIn(max = 112.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(record: DiagRecord, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val directionColor = when (record.direction) {
        "UL" -> if (dark) Color(0xFF90C6FF) else Color(0xFF125FAE)
        "DL" -> if (dark) Color(0xFF91DC9B) else Color(0xFF246B38)
        else -> colors.onSurface
    }
    val protocol = stringResource(record.protocol.label)
    val details = record.signalingInfo(protocol)
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(record.signalingHeading(protocol), style = MaterialTheme.typography.bodyMedium, color = directionColor,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
}

internal val RootDiagStatus.title: Int get() = when (this) {
    RootDiagStatus.Checking -> R.string.diag_checking
    RootDiagStatus.Unavailable -> R.string.diag_no_root
    RootDiagStatus.Denied -> R.string.diag_denied
    RootDiagStatus.Starting -> R.string.diag_starting
    RootDiagStatus.Unsupported -> R.string.diag_start_failed
    RootDiagStatus.Running -> R.string.diag_root_active
    RootDiagStatus.Stopped -> R.string.diag_stopped
}

private fun formatTime(time: Long): String =
    if (time > 0) DateFormat.format("HH:mm:ss", time).toString() +
        ".${(time % 1_000L).toString().padStart(3, '0')}" else ""

@Composable
internal fun DiagEstimate() {
    Text(
        stringResource(R.string.diag_estimate),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

private val packetHeaderKeys = listOf(
    "ver", "rrc_release", "std_version", "rat", "ch", "rbid", "pci", "nci", "ncgi",
    "arfcn", "sfn_subframe", "sfn", "subframe", "pdu", "len", "reassembled_len", "seg", "src",
    "from_pci", "from_arfcn", "to_pci", "to_arfcn", "rrc_transaction_id",
    "task_id", "received_time_ms", "received_elapsed_ms", "clock_id",
    "collector_run", "message_id", "related_message_id",
)
private val messageMetadataKeys = packetHeaderKeys.toSet() + setOf(
    "t", "type", "msg", "display_msg", "dir", "guess", "tentative", "nci_hex", "mr_status", "mr_bit",
    "decoded_tree", "decode_incomplete", "nested_nas", "mcc", "mnc", "mnc_digits",
)

private fun DiagRecord.packetHeader(): String = buildList {
    val rat = data.optString("rat")
    if (type == "rrc") add(if (rat == "NR") "NR5G RRC OTA Packet" else "LTE RRC OTA Packet")
    for (field in packetHeaderKeys) {
        if (data.isNull(field) || (field == "ncgi" && data.has("nci"))) continue
        if (type == "rrc" && (field == "rat" || field == "ch") && data.has("pdu")) continue
        val value = data.get(field)
        when (field) {
            "ver" -> add("Version: $value")
            "rrc_release" -> add("$rat RRC Release: $value")
            "rbid" -> add("Radio Bearer ID: $value")
            "pdu" -> add("PDU Number: " + channelLabel.ifBlank { value.toString() }.let {
                if (it == value.toString()) it else "$it ($value)"
            })
            "nci" -> {
                val nci = data.optLong("nci")
                val mcc = data.optInt("mcc", -1)
                val mnc = data.optInt("mnc", -1)
                val plmn = if (mcc >= 0 && mnc >= 0) {
                    val digits = data.optInt("mnc_digits", 2).coerceIn(2, 3)
                    "MCC ${mcc.toString().padStart(3, '0')} MNC ${mnc.toString().padStart(digits, '0')} "
                } else ""
                add(String.format(Locale.ROOT, "NR Global Cell Identity: %sNCI 0x%09X", plmn, nci))
            }
            "sfn_subframe" -> add(String.format(Locale.ROOT, "SFN/Subframe (raw): 0x%06X", data.optLong(field)))
            "arfcn" -> when (rat) {
                "NR" -> {
                    val frequency = data.optInt(field, -1).takeIf { it in 0..3_279_165 }
                        ?.let { String.format(Locale.ROOT, " (%.3f MHz)", CellularUtils.narfcnToFrequencyMhz(it)) }.orEmpty()
                    add("NR-ARFCN: $value$frequency")
                }
                "LTE" -> add("EARFCN: $value")
                else -> add("ARFCN: $value")
            }
            "len" -> add("Message Length: $value B")
            else -> {
                val label = when (field) {
                    "seg" -> "Segment"
                    "ncgi" -> "NR Global Cell Identity"
                    "std_version" -> "Standard version"
                    "reassembled_len" -> "Reassembled length (bytes)"
                    "sfn" -> "SFN"
                    "subframe" -> "Subframe"
                    else -> fieldLabels[field] ?: field
                }
                add("$label: ${valueOf(field, value)}")
            }
        }
    }
}.joinToString("\n")

@Composable
private fun MessageDetailsScreen(record: DiagRecord, onClose: () -> Unit,
                                 onPrevious: (() -> Unit)?, onNext: (() -> Unit)?,
                                 onRelatedPacket: (() -> Unit)?) {
    val context = LocalContext.current
    val masked = stringResource(R.string.diag_identity_masked)
    val unknownPaging = stringResource(R.string.diag_paging_unsupported)
    val noFields = stringResource(R.string.diag_no_decoded_fields)
    val extension = stringResource(R.string.diag_paging_extension)
    val partial = stringResource(R.string.diag_partial_decode)
    val estimate = stringResource(R.string.diag_estimate)
    val nasProtected = stringResource(R.string.diag_nas_protected)
    val report = record.data.optJSONObject("measResults")
    val header = remember(record) { record.packetHeader() }
    val body = remember(record, masked, unknownPaging, noFields, extension, partial, nasProtected) {
        if (record.decodedTree.isNotBlank()) return@remember record.decodedTree.trimEnd()
        val tree = record.messageTree(masked, unknownPaging, noFields, extension, partial)
        if (record.data.optJSONObject("dedicatedNAS")?.optInt("nas_security_header", 0) in setOf(2, 4, 5)) {
            "$tree\n\n$nasProtected"
        } else tree
    }
    val copied = stringResource(R.string.value_copied_to_clipboard)
    val protocol = stringResource(record.protocol.label)
    val heading = record.signalingHeading(protocol)
    val info = record.signalingInfo(protocol)
    val warning = if (record.data.optBoolean("decode_incomplete")) stringResource(R.string.diag_decode_incomplete) else ""
    var showSummary by rememberSaveable(record.id) { mutableStateOf(false) }
    val blocks = remember(body) { body.lineSequence().chunked(48) { it.joinToString("\n") }.toList() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SignalListAction(stringResource(R.string.diag_detail_back), Modifier.weight(1f), onClick = onClose)
                SignalListAction(stringResource(R.string.diag_detail_previous), Modifier.weight(1f), enabled = onPrevious != null) {
                    onPrevious?.invoke()
                }
                SignalListAction(stringResource(R.string.diag_detail_next), Modifier.weight(1f), enabled = onNext != null) {
                    onNext?.invoke()
                }
                SignalListAction(stringResource(R.string.diag_copy), Modifier.weight(1f)) {
                    val text = listOf(heading, info, estimate.takeIf { record.isEstimated() }.orEmpty(), warning,
                        header, body).filter(String::isNotBlank).joinToString("\n\n")
                    copyTextToClipboard(context, record.title(), text, copied)
                }
            }
            key(record.id) {
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 16.dp)) {
                        item("heading") {
                            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(heading, style = MaterialTheme.typography.titleSmall)
                                if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (record.isEstimated()) DiagEstimate()
                                if (warning.isNotBlank()) Text(warning, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (onRelatedPacket != null) item("related") {
                            TextButton(onClick = onRelatedPacket) { Text(stringResource(R.string.diag_open_related_message)) }
                        }
                        if (header.isNotBlank()) item("header") {
                            PacketText(header, Modifier.padding(bottom = 16.dp))
                        }
                        items(blocks.size) { index -> PacketText(blocks[index]) }
                        if (report != null) item("summary") {
                            TextButton(onClick = { showSummary = !showSummary }) {
                                Text(stringResource(R.string.diag_report_summary))
                            }
                            if (showSummary) MeasurementReportDetails(report)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementReportDetails(report: JSONObject?) {
    val colors = MaterialTheme.colorScheme
    if (report == null) {
        Text(stringResource(R.string.diag_mr_failed), color = colors.onSurfaceVariant)
        return
    }
    val servingCells = report.optJSONArray("measResultServingMOList") ?: report.optJSONArray("measResultPCell")
    val nrNeighbors = report.optJSONArray("measResultListNR")
    val lteNeighbors = report.optJSONArray("measResultListEUTRA")
    val bestNeighbor = report.optJSONObject("measResultBestNeighCell")
    val partialReport = report.optBoolean("partial")
    Text("measId ${report.optInt("measId")}", style = MaterialTheme.typography.labelLarge, color = colors.primary)

    Surface(shape = DashboardCardDefaults.smallShape, color = colors.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diag_serving), style = MaterialTheme.typography.titleSmall)
            for (index in 0 until (servingCells?.length() ?: 0)) {
                servingCells?.optJSONObject(index)?.let { MeasurementReportCell(it) }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Text(stringResource(R.string.diag_neighbors), style = MaterialTheme.typography.titleSmall)
            if (nrNeighbors == null && lteNeighbors == null && bestNeighbor == null && !partialReport) {
                Text(stringResource(R.string.diag_mr_no_neighbors), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            for (neighbors in listOfNotNull(nrNeighbors, lteNeighbors)) {
                for (index in 0 until neighbors.length()) {
                    neighbors.optJSONObject(index)?.let { MeasurementReportCell(it) }
                }
            }
            if (bestNeighbor != null) {
                Text(stringResource(R.string.diag_mr_best_neighbor), style = MaterialTheme.typography.labelMedium)
                MeasurementReportCell(bestNeighbor)
            }
            if (partialReport) Text(stringResource(R.string.diag_partial_decode),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun MeasurementReportCell(cell: JSONObject) {
    val colors = MaterialTheme.colorScheme
    val rat = cell.optString("rat")
    val label = listOfNotNull(
        rat.takeIf(String::isNotBlank),
        cell.optInt("physCellId", -1).takeIf { it >= 0 }?.let { "PCI $it" },
        cell.optInt("servCellId", -1).takeIf { it >= 0 }?.let { "servCellId $it" },
    ).joinToString(" · ")
    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.primary)
    val cellResults = cell.optJSONObject("cellResults")
    val groups = listOf(
        "SSB" to cellResults?.optJSONObject("resultsSSB-Cell"),
        "CSI-RS" to cellResults?.optJSONObject("resultsCSI-RS-Cell"),
        "" to cell.optJSONObject("measResult"),
    )
    for ((reference, result) in groups) {
        if (result == null) continue
        val values = listOf("RSRP" to "rsrp_index", "RSRQ" to "rsrq_index", "SINR" to "sinr_index")
            .mapNotNull { (metric, key) ->
                val index = result.optInt(key, -1)
                val value = result.reportedQuantity(key, rat)
                when {
                    value != null -> DiagField(metric, "$value ($index)")
                    index >= 0 -> DiagField(metric, stringResource(R.string.diag_quantity_index, index))
                    else -> null
                }
            }
        if (values.isNotEmpty()) {
            if (reference.isNotBlank()) Text(reference, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
            for ((metric, value) in values) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(metric, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text(value, Modifier.weight(2.5f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    val beams = cell.optJSONObject("rsIndexResults")
    if (beams != null) {
        val indexes = buildList {
            for ((key, prefix, indexKey) in listOf(
                Triple("resultsSSB-Indexes", "SSB", "ssb-Index"),
                Triple("resultsCSI-RS-Indexes", "CSI-RS", "csi-RS-Index"),
            )) {
                val list = beams.optJSONArray(key) ?: continue
                val ids = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.opt(indexKey) }
                if (ids.isNotEmpty()) add("$prefix ${ids.joinToString(", ")}")
            }
        }
        if (indexes.isNotEmpty()) Text(indexes.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant)
    }
}

@Composable
private fun PacketText(body: String, modifier: Modifier = Modifier) {
    Text(body, modifier = modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 17.sp,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
}

private fun DiagRecord.messageTree(
    masked: String, unknownPaging: String, noFields: String, extension: String, partial: String,
): String =
    buildString {
        if (type == "rrc") {
            val channel = data.optString("ch")
            appendLine(if (channel.isBlank()) "RRC" else "$channel-Message")
            val messageName = data.optString("msg")
            if (messageName.isNotBlank()) appendLine("  message: $messageName")
            if (channel == "PCCH" && messageName == "Paging") {
                val records = data.optJSONArray("paging_records")
                if (records == null) {
                    appendLine("    $unknownPaging")
                } else {
                    appendLine("    pagingRecordList [${records.length()}]")
                    for (i in 0 until records.length()) {
                        val entry = records.getJSONObject(i)
                        appendLine("      PagingRecord ${i + 1}")
                        appendLine("        ue-Identity: ${entry.optString("ue_identity_type")}")
                        appendLine("          $masked")
                        for (key in listOf("accessType", "cn-Domain")) {
                            if (entry.has(key)) appendLine("        $key: ${entry.getString(key)}")
                        }
                    }
                    for (key in listOf("systemInfoModification", "etws-Indication")) {
                        if (data.optBoolean(key)) appendLine("    $key: true")
                    }
                    if (data.optBoolean("paging_extensions")) appendLine("    $extension")
                }
                return@buildString
            }
            val report = data.optJSONObject("measResults")
            if (report != null) {
                val nr = data.optString("rat") == "NR"
                val choice = if (nr) "measurementReport" else "measurementReport-r8"
                appendLine("    criticalExtensions → $choice")
                appendLine("      measResults")
                appendMeasurementReport(report, "        ", nr)
                if (report.optBoolean("partial")) appendLine("        $partial")
                return@buildString
            }
            if (!appendFields(data, "    ", messageMetadataKeys)) appendLine("    $noFields")
        } else {
            if (!appendFields(data, "", messageMetadataKeys)) appendLine(noFields)
        }
    }.trimEnd()


private fun StringBuilder.appendMeasurementReport(report: JSONObject, indent: String, nr: Boolean) {
    appendLine("${indent}measId: ${report.optInt("measId")}")
    val servingKey = if (nr) "measResultServingMOList" else "measResultPCell"
    val serving = report.optJSONArray(servingKey)
    if (serving != null) {
        appendLine("$indent$servingKey")
        for (index in 0 until serving.length()) {
            val cell = serving.optJSONObject(index) ?: continue
            val prefix = if (nr) "$indent    " else "$indent  "
            if (nr) {
                appendLine("$indent  [$index]")
                cell.opt("servCellId")?.let { appendLine("${prefix}servCellId: $it") }
                appendLine("${prefix}measResultServingCell")
            }
            appendReportCell(cell, if (nr) "$prefix  " else prefix, "physCellId")
        }
    }
    for ((key, pciLabel) in listOf("measResultListNR" to "physCellId",
        "measResultListEUTRA" to if (nr) "eutra-PhysCellId" else "physCellId")) {
        val neighbors = report.optJSONArray(key) ?: continue
        appendLine("${indent}measResultNeighCells → $key")
        for (index in 0 until neighbors.length()) {
            val cell = neighbors.optJSONObject(index) ?: continue
            appendLine("$indent  [$index]")
            appendReportCell(cell, "$indent    ", pciLabel)
        }
    }
    report.optJSONObject("measResultBestNeighCell")?.let {
        appendLine("${indent}measResultBestNeighCell")
        appendReportCell(it, "$indent  ", "physCellId")
    }
}

private fun StringBuilder.appendReportCell(cell: JSONObject, indent: String, pciLabel: String) {
    cell.opt("physCellId")?.let { appendLine("$indent$pciLabel: $it") }
    val rat = cell.optString("rat")
    val measurements = cell.optJSONObject("measResult")
    if (measurements != null) {
        appendField("measResult", "measResult", measurements, indent, rat)
    } else {
        appendLine("${indent}measResult")
        cell.optJSONObject("cellResults")?.let { appendField("cellResults", "cellResults", it, "$indent  ", rat) }
        cell.optJSONObject("rsIndexResults")?.let { appendField("rsIndexResults", "rsIndexResults", it, "$indent  ", rat) }
    }
}

private fun StringBuilder.appendFields(
    data: JSONObject, indent: String, excluded: Set<String> = emptySet(), rat: String = "",
): Boolean {
    val measurementRat = data.optString("rat", rat)
    var hasFields = false
    data.keys().forEach { key ->
        if (key !in excluded && !data.isNull(key)) {
            val quantity = if (measurementRat.isNotBlank()) data.reportedQuantity(key, measurementRat) else null
            if (quantity != null) {
                appendLine("$indent${key.substringBefore('_')}: $quantity (${data.optInt(key)})")
            } else {
                val label = fieldLabels[key] ?: key
                val value = if (key == "nas_message_type") data.optString(key).let { nasNames[it] ?: it } else data.get(key)
                appendField(label, key, value, indent, measurementRat)
            }
            hasFields = true
        }
    }
    return hasFields
}

private fun StringBuilder.appendField(label: String, key: String, value: Any, indent: String, rat: String) {
    when (value) {
        is JSONObject -> {
            appendLine("$indent$label")
            appendFields(value, "$indent  ", rat = rat)
        }
        is JSONArray -> {
            appendLine("$indent$label [${value.length()}]")
            for (i in 0 until value.length()) {
                if (!value.isNull(i)) appendField("[${i + 1}]", key, value.get(i), "$indent  ", rat)
            }
        }
        else -> appendLine("$indent$label: ${valueOf(key, value)}")
    }
}
