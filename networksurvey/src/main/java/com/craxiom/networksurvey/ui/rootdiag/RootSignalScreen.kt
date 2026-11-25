package com.craxiom.networksurvey.ui.rootdiag

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.TrafficStats
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craxiom.messaging.LteRecordData
import com.craxiom.messaging.NrRecordData
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.fragments.model.CarrierAggregationViewState
import com.craxiom.networksurvey.util.CarrierAggregation
import com.craxiom.networksurvey.constants.LteMessageConstants
import com.craxiom.networksurvey.db.EngineeringParamDbHelper
import com.craxiom.networksurvey.fragments.model.CellularViewModel
import com.craxiom.networksurvey.constants.NetworkSurveyConstants.UNSET_VALUE
import com.craxiom.networksurvey.model.StationInfo
import com.craxiom.networksurvey.ui.cellular.baidumap.MapNeighborCell
import com.craxiom.networksurvey.model.NrCarrier
import com.craxiom.networksurvey.ui.cellular.NrCarrierSection
import com.craxiom.networksurvey.ui.cellular.NrBandwidthCell
import com.craxiom.networksurvey.ui.cellular.saveMeasuredNrBandwidth
import com.craxiom.networksurvey.model.CellularProtocol
import com.craxiom.networksurvey.ui.cellular.CellularChartComponent
import com.craxiom.networksurvey.ui.cellular.CellularChartViewModel
import com.craxiom.networksurvey.ui.cellular.ColoredSignalBar
import com.craxiom.networksurvey.ui.cellular.rememberSignalBarColors
import com.craxiom.networksurvey.ui.cellular.SignalBarColors
import com.craxiom.networksurvey.ui.cellular.ServingCellHeader
import com.craxiom.networksurvey.ui.cellular.ServingSignalMetrics
import com.craxiom.networksurvey.ui.cellular.SignalSectionCard
import com.craxiom.networksurvey.ui.cellular.baidumap.isRecent
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults
import com.craxiom.networksurvey.util.CalculationUtils
import com.craxiom.networksurvey.util.CellularUtils
import com.craxiom.networksurvey.util.TelephonyStateUtils.NrMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

private data class ServingIdentity(
    val rat: String,
    val cid: Long,
    val pci: Int,
    val arfcn: Int,
    val mcc: Int,
    val mnc: Int,
    val mncDigits: Int,
) {
    val isNr get() = rat == "NR"
    val plmn get() = if (mcc in 1..999 && mnc in 0..999) {
        mcc.toString().padStart(3, '0') + mnc.toString().padStart(if (mncDigits == 3) 3 else 2, '0')
    } else ""

    constructor(rat: String, data: JSONObject?) : this(
        rat, data?.optLong("cid") ?: 0, data?.optInt("pci", -1) ?: -1,
        data?.optInt("arfcn", -1) ?: -1, data?.optInt("mcc", -1) ?: -1, data?.optInt("mnc", -1) ?: -1,
        data?.optInt("mnc_digits", -1) ?: -1,
    )

    fun toStation() = StationInfo().apply {
        isServing = true
        cellId = cid
        networkType = rat
        pci = this@ServingIdentity.pci
        earfcn = arfcn
        val divisor = if (isNr) 4096 else 256
        gnbId = (cid / divisor).toInt()
        sectorId = (cid % divisor).toInt()
        mcc = this@ServingIdentity.mcc
        mnc = this@ServingIdentity.mnc
    }
}

private data class RegisteredOperator(val plmn: String, val name: String)
private fun String.validPlmn() = takeIf { length in 5..6 && all { it in '0'..'9' } }
private fun String.displayPlmn() = "${take(3)}/${drop(3)}"

@Composable
private fun rememberRegisteredOperator(active: Boolean): RegisteredOperator? {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val operator by produceState<RegisteredOperator?>(null, active, context, lifecycle) {
        if (!active) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val subId = SubscriptionManager.getActiveDataSubscriptionId()
                            .takeIf(SubscriptionManager::isValidSubscriptionId)
                            ?: SubscriptionManager.getDefaultDataSubscriptionId()
                        if (!SubscriptionManager.isValidSubscriptionId(subId)) return@runCatching null
                        val telephony = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId)
                            ?: return@runCatching null
                        val plmn = telephony.networkOperator.orEmpty().validPlmn() ?: return@runCatching null
                        RegisteredOperator(plmn, telephony.networkOperatorName.orEmpty())
                    }.getOrNull()
                }
                delay(2_000)
            }
        }
    }
    return operator
}

@Composable
internal fun RootSignalScreen(
    state: RootSignalState,
    chartFor: (String) -> CellularChartViewModel,
    bottomPadding: Dp,
    androidCells: CellularViewModel?,
    isSignalTab: Boolean,
) {
    val records = remember(state.records) { state.records.groupBy { it.type } }
    val serving = records["serving"].orEmpty().associateBy { it.data.optString("rat") }
    val systemProtocol = androidCells?.servingCellProtocol?.observeAsState()?.value
    val nrMode = androidCells?.nrMode?.observeAsState()?.value
    val androidLte = androidCells?.lteServingCell?.observeAsState()?.value?.takeIf { systemProtocol == CellularProtocol.LTE }
    val androidNr = androidCells?.nrServingCell?.observeAsState()?.value
        ?.takeIf { systemProtocol == CellularProtocol.NR || systemProtocol == CellularProtocol.LTE }
    val now = SystemClock.elapsedRealtime()
    // The collector republishes both serving snapshots. Use the age of the modem observation when only one RAT is live.
    val liveDiagRat = serving.entries.filter { (_, record) ->
        val modemAge = record.data.optLong("seen_age_ms", -1)
        modemAge in 0..3_000 && now - record.receivedAt + modemAge <= 3_000
    }.singleOrNull()?.key
    val rat = liveDiagRat ?: when (systemProtocol) {
        CellularProtocol.NR -> "NR"
        CellularProtocol.LTE -> if (androidNr != null) "NR" else "LTE"
        else -> serving.values.maxWithOrNull(compareBy<DiagRecord> { it.receivedAt }
            .thenBy { it.data.optString("rat") == "NR" })?.data?.optString("rat")
    }
    val servingCell = rat?.let(serving::get)
    val lteData = androidLte?.takeIf { rat == "LTE" }
    val nrData = androidNr?.takeIf { rat == "NR" }
    val diagServing = servingCell?.takeIf {
        (lteData == null || lteData.matches(it.data, "arfcn")) && (nrData == null || nrData.matches(it.data))
    }
    val servingData = remember(diagServing, lteData, nrData) {
        lteData?.withDiag(diagServing?.data) ?: nrData?.withDiag(diagServing?.data) ?: diagServing?.data
    }
    val context = LocalContext.current
    val bandwidthCell = NrBandwidthCell.from(nrData)
    val measuredBandwidth = diagServing?.takeIf { record ->
        val modemAge = record.data.optLong("seen_age_ms", -1)
        isSignalTab && state.status == RootDiagStatus.Running && record.id !in state.cachedMeasureIds &&
            bandwidthCell != null && record.data.optLong("cid", -1) == bandwidthCell.nci &&
            record.data.channel("pci") == bandwidthCell.pci && record.data.channel("arfcn") == bandwidthCell.channel &&
            modemAge >= 0 && now - record.receivedAt + modemAge in 0..20_000L
    }?.data?.number("bw_mhz")
    LaunchedEffect(bandwidthCell, measuredBandwidth, diagServing?.id) {
        if (bandwidthCell != null && measuredBandwidth != null)
            saveMeasuredNrBandwidth(context, bandwidthCell, measuredBandwidth)
    }
    val lteRrcSinr = records.latest("lte_rrc_sinr")?.takeIf {
        rat == "LTE" && it.id !in state.cachedMeasureIds && SystemClock.elapsedRealtime() - it.receivedAt <= 5_000 &&
            servingData != null && it.data.channel("pci") == servingData.channel("pci") &&
            it.data.channel("arfcn") == servingData.channel("arfcn")
    }?.data?.neighborMetric("sinr_index", "LTE", singleReading = true)?.value
    val status = if (state.status == RootDiagStatus.Running) "" else
        state.failureReason?.let { capabilityReasonText(it) } ?: stringResource(state.status.title)
    val identity = rat?.let { ServingIdentity(it, servingData) }
    val lteSecondaries = if (identity?.rat == "LTE" && state.status == RootDiagStatus.Running &&
        servingCell != null && servingCell.id !in state.cachedMeasureIds) {
        val samples = records["lte_meas"].orEmpty().mapNotNull { record ->
            if (record.source != "B193" || record.id in state.cachedMeasureIds || record.isEstimated()) return@mapNotNull null
            val data = record.data
            CarrierAggregation.B193Sample(data.optLong("ca_report", -1), data.optInt("cc", -1),
                data.optInt("pci", -1), data.optInt("earfcn", -1), data.optInt("is_serving") == 1, record.receivedAt)
        }
        CarrierAggregation.b193Secondaries(samples, identity.pci, identity.arfcn, now)
    } else emptyList()
    val operator = rememberRegisteredOperator(isSignalTab)
    fun matchesServing(record: DiagRecord) = identity != null && record.data.optString("rat") == identity.rat &&
        record.data.channel("pci") == identity.pci && record.data.channel("arfcn") == identity.arfcn
    val broadcast = records["plmn_broadcast"].orEmpty().firstOrNull(::matchesServing)?.data?.optJSONArray("plmns")
    val plmnIndex = records["plmn_selection"].orEmpty().firstOrNull(::matchesServing)?.data?.optInt("index", -1) ?: -1
    val selectedPlmn = if (plmnIndex > 0) broadcast?.optString(plmnIndex - 1)?.validPlmn() else null
    val registeredPlmn = operator?.plmn ?: selectedPlmn
    val cellPlmns = if (broadcast != null) {
        (0 until broadcast.length()).mapNotNull { broadcast.optString(it).validPlmn() }.distinct()
    } else listOfNotNull(identity?.plmn?.validPlmn())
    val fields = identity?.let { servingCellFields(it, servingData, records, registeredPlmn, cellPlmns) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
    ) {
        item("network") {
            val lteCa = lteSecondaries.takeIf { it.isNotEmpty() }?.let { rows ->
                CarrierAggregationViewState(rows.map { row ->
                    CarrierAggregation.chipLabel(CarrierAggregation.Component(CarrierAggregation.LTE,
                        row.pci, row.earfcn, CellularUtils.downlinkEarfcnToBand(row.earfcn), 0))
                }, stringResource(R.string.ca_lte_b193, rows.size))
            }
            RootNetworkHeader(identity, nrMode, status,
                operator?.name.orEmpty().ifBlank { registeredPlmn?.displayPlmn().orEmpty() }, lteCa)
        }
        if (rat != null && fields != null &&
            (rat != "NR" || state.nrCarriers.count { !it.lastObservation() } < 2)) {
            item("metrics:$rat") {
                ServingSignalMetrics(
                    if (rat == "NR") CellularProtocol.NR else CellularProtocol.LTE,
                    servingData?.number("rsrp"), servingData?.number("rsrq"), servingData?.number("sinr") ?: lteRrcSinr,
                )
            }
        }
        if (state.nrCarriers.size >= 2) item("nr-carriers") {
            NrCarrierSection(state.nrCarriers, fromDiag = true)
        }
        if (rat == null || fields == null) return@LazyColumn
        item("serving:$rat") { SignalParameterCard(stringResource(R.string.serving_cell_parameters), fields.primary) }
        item("neighbors:$rat") {
            NeighborMeasurements(rat, servingData, state.records, null, isSignalTab, state.nrCarriers,
                lteSecondaries.map { it.earfcn to it.pci }.toSet())
        }
        item("trend:$rat") {
            SignalSectionCard(contentPadding = 0.dp) { CellularChartComponent(viewModel = chartFor(rat)) }
        }
        item("cell-details:$rat") { SignalParameterCard(stringResource(R.string.diag_cell_details), fields.secondary) }
        item("nas:$rat") { NasConfiguration(rat, records) }
        item("throughput:$rat") { LayerThroughput(rat, records, identity, isSignalTab) }
        item("scheduling:$rat") { PhysicalScheduling(rat, identity, servingData, records) }
    }
}

private fun LteRecordData.matches(diag: JSONObject, channelKey: String): Boolean =
    hasPci() && hasEarfcn() && diag.channel("pci") == pci.value && diag.channel(channelKey) == earfcn.value &&
        (!hasEci() || diag.optLong("cid") <= 0L || diag.optLong("cid") == eci.value.toLong())

private fun LteRecordData.withDiag(diag: JSONObject?) = JSONObject(diag?.toString() ?: "{}").apply {
    fun fill(key: String, value: Number) {
        if (number(key) == null) put(key, value)
    }
    if (hasPci()) fill("pci", pci.value)
    if (hasEarfcn()) fill("arfcn", earfcn.value)
    if (hasEarfcn() && optInt("band") <= 0) {
        val band = CellularUtils.downlinkEarfcnToBand(earfcn.value)
        if (band > 0) put("band", band)
    }
    if (hasEci()) fill("cid", eci.value)
    if (hasMcc()) fill("mcc", mcc.value)
    if (hasMnc()) fill("mnc", mnc.value)
    if (hasTac()) fill("tac", tac.value)
    if (hasRsrp() && number("rsrp") == null) { fill("rsrp", rsrp.value); put("rsrp_src", "Android API") }
    if (hasRsrq() && number("rsrq") == null) { fill("rsrq", rsrq.value); put("rsrq_src", "Android API") }
    if (hasSignalStrength()) fill("rssi", signalStrength.value)
    if (hasSnr() && snr.value.isFinite() && number("sinr") == null) {
        put("sinr", snr.value)
        put("sinr_src", "Android RSSNR")
    }
    if (hasTa()) put("ta", ta.value)
    if (hasCqi()) put("cqi", cqi.value)
    if (optDouble("bw_mhz", 0.0) <= 0) {
        LteMessageConstants.getLteBandwidth(lteBandwidth).toDoubleOrNull()?.let { put("bw_mhz", it) }
    }
}

private fun NrRecordData.matches(diag: JSONObject): Boolean =
    hasPci() && hasNarfcn() && diag.channel("pci") == pci.value && diag.channel("arfcn") == narfcn.value &&
        (!hasNci() || diag.optLong("cid") <= 0L || diag.optLong("cid") == nci.value)

private fun NrRecordData.withDiag(diag: JSONObject?) = JSONObject(diag?.toString() ?: "{}").apply {
    fun fill(key: String, value: Number) {
        if (number(key) == null) put(key, value)
    }
    if (hasPci()) fill("pci", pci.value)
    if (hasNarfcn()) {
        fill("arfcn", narfcn.value)
        CellularUtils.narfcnToFrequencyMhz(narfcn.value).takeIf { it >= 0 }?.let { fill("freq_mhz", it) }
        CellularUtils.downlinkNarfcnToBand(narfcn.value).takeIf { it > 0 }?.let { fill("band", it) }
    }
    if (hasNci()) fill("cid", nci.value)
    if (hasMcc()) fill("mcc", mcc.value)
    if (hasMnc()) fill("mnc", mnc.value)
    if (hasTac()) fill("tac", tac.value)
    if (hasSsRsrp() && number("rsrp") == null) { fill("rsrp", ssRsrp.value); put("rsrp_src", "Android SS-RSRP") }
    if (hasSsRsrq() && number("rsrq") == null) { fill("rsrq", ssRsrq.value); put("rsrq_src", "Android SS-RSRQ") }
    if (hasSsSinr() && number("sinr") == null) { fill("sinr", ssSinr.value); put("sinr_src", "Android SS-SINR") }
}

@Composable
private fun WaitingForDiagData() {
    Text(stringResource(R.string.diag_waiting_data), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun sessionPurpose(data: JSONObject?): String? {
    if (data == null) return null
    val network = data.optString("dnn").ifBlank { data.optString("apn") }
    return stringResource(when {
        data.optInt("ims") == 1 -> R.string.diag_session_ims
        data.optInt("ims") == 2 -> R.string.diag_ims_inferred
        network.isNotBlank() -> R.string.diag_session_data
        else -> return null
    })
}

private fun Map<String, List<DiagRecord>>.latest(type: String): DiagRecord? =
    get(type)?.maxByOrNull { it.id }

internal const val SIGNAL_UNAVAILABLE = "-"

internal fun String?.signalDisplayValue() = this?.trim()?.takeUnless {
    it.isEmpty() || it == "—" || it == "–" || it == "?"
} ?: SIGNAL_UNAVAILABLE

private fun JSONObject?.signalValue(key: String): String =
    fixedSignalFields(listOf(key)).single().value.signalDisplayValue()

private val lteApnOperatorSuffix = Regex(
    """\.mnc[0-9]{3}\.mcc[0-9]{3}(?:\.gprs)?\.?$""", RegexOption.IGNORE_CASE,
)

private fun Map<String, List<DiagRecord>>.cellConfig(type: String, serving: JSONObject?): JSONObject? {
    val pci = serving?.channel("pci") ?: return null
    val arfcn = serving.channel("arfcn") ?: return null
    return get(type)?.firstOrNull {
        it.data.channel("pci") == pci && it.data.channel("arfcn") == arfcn
    }?.data
}

@Composable
private fun NasConfiguration(rat: String, records: Map<String, List<DiagRecord>>) {
    val nr = rat == "NR"
    val idKey = if (nr) "psi" else "ebi"
    val networkKey = if (nr) "dnn" else "apn"
    val ambrKeys = if (nr) listOf("ambr_dl_mbps", "ambr_ul_mbps") else emptyList()
    val sessions = records["nas_pdu"].orEmpty().filter { it.data.optString("rat") == rat }
        .associateBy { it.data.optInt(idKey, -1) }
    val qos = records["nas_qos"].orEmpty().filter { it.data.optString("rat") == rat }
        .groupBy { if (nr) it.data.optInt(idKey, -1) else it.data.optInt("linked_ebi", it.data.optInt(idKey, -1)) }
    val sessionIds = (sessions.keys + qos.keys).filter { it > 0 }.sorted()
    val qualityBySession = sessionIds.associateWith { id ->
        val reports = qos[id].orEmpty()
        val qualities = if (nr) {
            reports.maxByOrNull { it.id }?.data?.objects("flows").orEmpty().mapNotNull { flow ->
                val qfi = flow.optInt("qfi", -1).takeIf { it in 1..63 } ?: return@mapNotNull null
                val fiveQi = flow.optInt("5qi").takeIf { it > 0 } ?: return@mapNotNull null
                qfi to fiveQi
            }
        } else {
            (listOfNotNull(sessions[id]) + reports).sortedBy { it.id }.mapNotNull { record ->
                val qci = record.data.optInt("qci").takeIf { it > 0 } ?: return@mapNotNull null
                record.data.optInt("ebi") to qci
            }
        }
        qualities.toMap().toSortedMap()
    }
    var selection by rememberSaveable(rat) { mutableStateOf(-1) }
    val id = selection.takeIf { it in sessionIds } ?: sessionIds.firstOrNull()
    val session = sessions[id]
    val qosReports = qos[id].orEmpty()
    val mm = records["nas_mm"].orEmpty().firstOrNull { it.data.optString("rat") == rat }?.data
    val network = session?.data.signalValue(networkKey)
    val nrSession = session?.data?.takeIf { nr }
    val pduType = when (nrSession?.channel("pdu_type")) {
        1 -> "IPv4"
        2 -> "IPv6"
        3 -> "IPv4v6"
        4 -> "Unstructured"
        5 -> "Ethernet"
        else -> null
    }
    val slice = nrSession?.channel("sst")?.let { sst ->
        listOfNotNull("SST $sst", nrSession.optString("sd").takeIf { it.isNotBlank() }?.let { "SD $it" })
            .joinToString(" · ")
    }
    val fields = listOf(
        DiagField("MM State", mm.signalValue("state")),
        DiagField(if (nr) "DNN" else "APN", if (nr) network else network.replace(lteApnOperatorSuffix, "")),
        DiagField(stringResource(R.string.diag_session_purpose),
            sessionPurpose(session?.data ?: qosReports.maxByOrNull { it.id }?.data).signalDisplayValue()),
    ) + listOfNotNull(
        pduType?.let { DiagField(stringResource(R.string.diag_pdu_type), it) },
        nrSession?.channel("ssc_mode")?.let { DiagField("SSC", it.toString()) },
        slice?.let { DiagField("S-NSSAI", it) },
    ) + session?.data.fixedSignalFields(ambrKeys)
    val qualityOptions = qualityBySession[id].orEmpty()
    var selectedFlow by rememberSaveable(rat, id) { mutableStateOf(-1) }
    val flow = selectedFlow.takeIf { it in qualityOptions } ?: qualityOptions.keys.firstOrNull()
    val optionsByLabel = qualityOptions.entries.associate { (index, value) ->
        "$value · ${if (nr) "QFI" else "EBI"} $index" to index
    }
    val qualityLabel = optionsByLabel.entries.firstOrNull { it.value == flow }?.key.signalDisplayValue()

    SignalSectionCard(contentPadding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.diag_nas), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            SignalSelection(id?.toString().signalDisplayValue(), sessionIds.map(Int::toString), if (nr) "PSI " else "EBI ") {
                selection = it.toInt()
            }
        }
        SignalParameterTable(fields) {
            SignalParameterRow(if (nr) "5QI" else "QCI") {
                SignalSelection(qualityLabel, optionsByLabel.keys.toList(), "") {
                    selectedFlow = optionsByLabel.getValue(it)
                }
            }
        }
    }
}

private data class DirectionRow(val parameter: String, val ul: String = SIGNAL_UNAVAILABLE, val dl: String = SIGNAL_UNAVAILABLE)

private const val THROUGHPUT_FRESH_MS = 3_000L
private const val LAST_DIAG_MS = 30_000L

private fun DiagRecord.matchesLteServing(identity: ServingIdentity?) =
    identity != null && data.channel("pci") == identity.pci && data.channel("earfcn") == identity.arfcn

@Composable
private fun LayerThroughput(rat: String, records: Map<String, List<DiagRecord>>, identity: ServingIdentity?, active: Boolean) {
    val owner = LocalLifecycleOwner.current
    val traffic by produceState(MobileThroughput(0, null, null), owner, active, rat) {
        if (active) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var sample = MobileTrafficSample.read()
            value = MobileThroughput(sample.time, null, null)
            while (isActive) {
                delay(1_000)
                val next = MobileTrafficSample.read()
                value = next.rateSince(sample)
                sample = next
            }
        }
    }
    val now = maxOf(traffic.sampledAt, SystemClock.elapsedRealtime())
    fun fresh(record: DiagRecord) = active && now - record.receivedAt in 0..THROUGHPUT_FRESH_MS
    fun recent(type: String) = records.latest(type)?.takeIf {
        fresh(it) && when {
            type.startsWith("lte_") -> it.matchesLteServing(identity)
            it.data.has("pci") -> it.data.channel("pci") == identity?.pci && it.data.channel("arfcn") == identity?.arfcn
            else -> true
        }
    }
    fun rate(layer: String, direction: String) = records["layer_throughput"].orEmpty().filter {
        it.data.optString("rat") == rat && it.data.optString("layer") == layer &&
            it.data.optString("dir") == direction && fresh(it) &&
            (rat != "LTE" || !it.data.has("pci") || it.matchesLteServing(identity))
    }.maxByOrNull { it.id }?.data?.number("mbps")
    fun reported(type: String, field: String, entries: String? = null): Double? {
        val report = recent(type) ?: return null
        return if (entries == null) report.data.number(field) else {
            val rows = report.data.objects(entries)
            val matching = if (type == "nr_dl_sched" && !report.data.has("pci")) rows.filter {
                it.optInt("cc", -1) == 0 && it.channel("pci") == identity?.pci &&
                    it.channel("arfcn") == identity?.arfcn
            } else rows
            val values = matching.mapNotNull { it.number(field) }
            values.takeIf { it.isNotEmpty() }?.sum()
        }
    }
    fun row(name: String, ul: Double?, dl: Double?) =
        DirectionRow(name, (ul ?: 0.0).mbpsText(), (dl ?: 0.0).mbpsText())
    val nr = rat == "NR"
    val phyUl = if (nr) reported("nr_ul_sched", "ul_mbps") else reported("lte_ul_sched", "phy_tb_mbps", "cc")
    val phyDl = if (nr) reported("nr_dl_sched", "phy_tb_mbps", "cc") ?: reported("nr_pdsch", "phy_tb_mbps", "recs")
        else reported("lte_dl_sched", "phy_tb_mbps", "cc")
    val macUlStats = rate("MAC", "UL")
    val macUlTb = if (nr && macUlStats == null) reported("nr_mac_ul_window", "mac_new_tb_mbps") else null
    val macDlStats = rate("MAC", "DL") ?: if (nr) reported("nr_pdsch", "mac_mbps", "recs") else null
    val macDlTb = if (macDlStats != null) null else if (nr)
        reported("nr_pdsch", "mbps", "recs") ?: reported("nr_dl_sched", "mac_crc_tb_mbps", "cc")
    else reported("lte_dl_sched", "mac_crc_tb_mbps", "cc")
    val macDl = macDlStats ?: macDlTb
    val rows = listOf(
        row("APP", traffic.ul.takeIf { active }, traffic.dl.takeIf { active }),
        row("PDCP", rate("PDCP", "UL"), rate("PDCP", "DL")),
        row("RLC", rate("RLC", "UL"), rate("RLC", "DL")),
        row("MAC", macUlStats ?: macUlTb, macDl),
        row("PHY", phyUl, phyDl),
    )
    SignalSectionCard(contentPadding = 12.dp) {
        Text("${stringResource(R.string.diag_throughput)} (Mbps)", style = MaterialTheme.typography.titleSmall)
        DirectionTable(rows)
    }
}

private data class MobileThroughput(val sampledAt: Long, val ul: Double?, val dl: Double?)

private data class MobileTrafficSample(val time: Long, val tx: Long, val rx: Long) {
    fun rateSince(previous: MobileTrafficSample): MobileThroughput {
        val elapsed = time - previous.time
        fun rate(current: Long, before: Long) =
            if (elapsed > 0 && before >= 0 && current >= before) (current - before) * 0.008 / elapsed else null
        return MobileThroughput(time, rate(tx, previous.tx), rate(rx, previous.rx))
    }

    companion object {
        fun read() = MobileTrafficSample(SystemClock.elapsedRealtime(),
            TrafficStats.getMobileTxBytes(), TrafficStats.getMobileRxBytes())
    }
}

private fun Double?.mbpsText() = if (this == null) SIGNAL_UNAVAILABLE else String.format(Locale.ROOT, "%.2f", this)

@Composable
private fun Map<String, List<DiagRecord>>.servingSchedule(identity: ServingIdentity?): List<DiagRecord> {
    if (identity == null || identity.arfcn < 0 || identity.pci < 0) return emptyList()
    val now = SystemClock.elapsedRealtime()
    return get("schedule_sample").orEmpty().filter {
        it.data.optString("rat") == identity.rat && it.data.channel("serving_pci") == identity.pci &&
            it.data.channel("serving_arfcn") == identity.arfcn && now - it.receivedAt <= LAST_DIAG_MS
    }
}

@Composable
private fun PhysicalScheduling(
    rat: String, identity: ServingIdentity?, serving: JSONObject?, records: Map<String, List<DiagRecord>>,
) {
    val nr = rat == "NR"
    val prefix = if (nr) "nr" else "lte"
    val snapshots = records.servingSchedule(identity)
    fun samples(suffix: String) = snapshots.filter { it.data.optString("group") == "${prefix}_$suffix" }
        .associateBy { it.data.optInt("cc", -1) }
    val dl = samples("dl_sched")
    val ul = samples("ul_sched")
    val gmTx = samples("ul_tx")
    val pdsch = samples("pdsch")
    val csi = samples("csf")
    val layersLabel = stringResource(R.string.diag_mimo_layers)
    val retxLabel = stringResource(R.string.diag_retransmission)
    val carriers = (dl.keys + ul.keys + gmTx.keys + pdsch.keys + csi.keys + 0).sorted()
    var selected by rememberSaveable(rat) { mutableStateOf(0) }
    val cc = selected.takeIf { it in carriers } ?: carriers.firstOrNull() ?: 0
    val up = ul[cc]?.data
    val down = dl[cc]?.data
    val ulTx = gmTx[cc]?.data
    val feedback = csi[cc]?.data
    val now = SystemClock.elapsedRealtime()
    fun phyRate(sample: DiagRecord?, key: String) = sample
        ?.takeIf { now - it.receivedAt in 0..THROUGHPUT_FRESH_MS }?.data?.number(key) ?: 0.0
    fun transportBlocksPerSecond(carrier: JSONObject?): String {
        val blocks = carrier?.number("tb")?.takeIf { it >= 0 } ?: return SIGNAL_UNAVAILABLE
        val ms = carrier?.number("dt_ms")?.takeIf { it > 0 } ?: return SIGNAL_UNAVAILABLE
        return String.format(Locale.ROOT, "%.1f", 1000 * blocks / ms)
    }
    val rows = listOf(
        DirectionRow("CQI", dl = if (nr) feedback.signalValue("cqi_avg") else
            (feedback?.channel("cqi") ?: serving?.channel("cqi")?.takeIf { cc == 0 })?.toString().signalDisplayValue()),
        DirectionRow("Rank", up.signalValue("rank"), feedback.signalValue("rank")),
        DirectionRow("MCS", (if (nr) up else ulTx).signalValue("mcs_avg"), down.signalValue("mcs_avg")),
        DirectionRow("RBs", up.signalValue("rb_avg"), down.signalValue("rb_avg")),
        DirectionRow("Modulation", up.signalValue("mod"), down.signalValue("mod")),
        DirectionRow(layersLabel, up.signalValue("layers_avg"), down.signalValue("layers_avg")),
        DirectionRow(stringResource(R.string.diag_mimo_tx_mode),
            if (nr) up.signalValue("tx_mode") else SIGNAL_UNAVAILABLE,
            if (nr) SIGNAL_UNAVAILABLE else feedback.signalValue("tx_mode")),
        DirectionRow("TB/s", transportBlocksPerSecond(up), transportBlocksPerSecond(down)),
        DirectionRow("BLER", dl = (down?.number("bler_pct") ?: pdsch[cc]?.data?.number("bler_pct"))
            ?.let { "$it%" }.signalDisplayValue()),
    ) + (if (nr) listOf(
        DirectionRow(retxLabel, up.signalValue("bler_pct")),
    ) else listOf(
        DirectionRow("UL HARQ Retx", (ulTx?.number("retx_pct") ?: up?.number("retx_pct"))
            ?.let { "$it%" }.signalDisplayValue()),
    )) + listOf(
        DirectionRow("PHY (Mbps)", phyRate(ul[cc], if (nr) "ul_mbps" else "phy_tb_mbps").mbpsText(),
            phyRate(dl[cc], "phy_tb_mbps").mbpsText()),
    ) + listOfNotNull(pdsch[cc]?.data?.number("retx_pct")
        ?.let { DirectionRow("DL HARQ Retx", dl = "$it%") })
    val modulationRows = listOf("QPSK", "16QAM", "64QAM", "256QAM", "1024QAM", "BPSK")
        .mapIndexed { index, name ->
            val ulShare = up.modulationShare(if (nr) (index + 1) % 6 else index)
            val dlShare = down.modulationShare(index)
            DirectionRow("$name (%)", ulShare.mbpsText(), dlShare.mbpsText())
        }
    SignalSectionCard(contentPadding = 12.dp) {
        Text(stringResource(R.string.diag_scheduling), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        val arfcn = (down?.channel("arfcn") ?: identity?.arfcn?.takeIf { cc == 0 && it >= 0 })
            ?.toString().signalDisplayValue()
        val pci = (down?.channel("pci") ?: identity?.pci?.takeIf { cc == 0 && it >= 0 }).let {
            it?.toString().signalDisplayValue()
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${if (nr) "NR-ARFCN" else "EARFCN"} $arfcn · PCI $pci", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            SignalSelection(cc.toString(), carriers.map(Int::toString), "CC ") {
                selected = it.toInt()
            }
        }
        DirectionTable(rows + modulationRows)
    }
}

private fun JSONObject?.modulationShare(index: Int): Double? {
    val histogram = this?.optJSONArray("mod_hist") ?: return null
    val count = (0 until histogram.length()).sumOf { histogram.optLong(it) }
    return count.takeIf { it > 0 && index < histogram.length() }?.let { 100.0 * histogram.optLong(index) / it }
}

@Composable
private fun DirectionTable(rows: List<DirectionRow>) {
    val header = DirectionRow(stringResource(R.string.diag_parameter), stringResource(R.string.diag_uplink),
        stringResource(R.string.diag_downlink))
    Surface(shape = DashboardCardDefaults.smallShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 10.dp)) {
            (listOf(header) + rows).forEachIndexed { index, row ->
                if (index > 0) SignalTableDivider()
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 27.dp).padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val style = MaterialTheme.typography.bodySmall
                    Text(row.parameter, Modifier.weight(1.5f), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(row.ul, row.dl).forEach { value ->
                        Text(value.signalDisplayValue(), Modifier.weight(1f), textAlign = TextAlign.End,
                            style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalSelection(selected: String, options: List<String>, prefix: String, select: (String) -> Unit) {
    val canSelect = options.size > 1
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text("$prefix$selected${if (canSelect) " ▾" else ""}",
            modifier = (if (canSelect) Modifier.clickable { expanded = true } else Modifier)
                .padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (canSelect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        DropdownMenu(expanded = expanded && canSelect, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text("$prefix$option") }, onClick = {
                    expanded = false
                    select(option)
                })
            }
        }
    }
}

@Composable
private fun RootNetworkHeader(
    identity: ServingIdentity?, nrMode: NrMode?, status: String, operator: String, lteCa: CarrierAggregationViewState?,
) {
    val context = LocalContext.current.applicationContext
    val cellName by produceState("", identity, context) {
        value = ""
        if (identity != null && identity.cid > 0) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val database = EngineeringParamDbHelper.getInstance(context)
                    database.matchServingIdentity(identity.toStation())
                        ?.let { it.cellName.orEmpty().ifBlank { it.siteName.orEmpty() } }.orEmpty()
                }.getOrDefault("")
            }
        }
    }
    val network = when (identity?.rat) {
        "NR" -> when (nrMode) {
            NrMode.STANDALONE -> "5G · NR SA"
            NrMode.NON_STANDALONE -> "5G · NR NSA"
            else -> "5G · NR"
        }
        "LTE" -> "4G · LTE"
        else -> ""
    }
    SignalSectionCard {
        ServingCellHeader(
            carrier = operator,
            cellName = cellName,
            networkDescription = network,
            voicePill = null,
            dataPill = null,
            brandingPill = status,
            carrierAggregation = lteCa,
        )
        if (identity == null) WaitingForDiagData()
    }
}

@Composable
private fun servingCellFields(
    identity: ServingIdentity,
    data: JSONObject?,
    records: Map<String, List<DiagRecord>>,
    registeredPlmn: String?,
    cellPlmns: List<String>,
): ServingParameterFields {
    val lteCell = records["lte_cell"].orEmpty().firstOrNull {
        val cell = it.data
        !identity.isNr && cell.channel("earfcn") == identity.arfcn && cell.channel("pci") == identity.pci &&
            (identity.cid <= 0 || cell.optLong("cid") <= 0 || cell.optLong("cid") == identity.cid)
    }?.data
    val lteMib = records["lte_mib"].orEmpty().firstOrNull {
        !identity.isNr && it.data.channel("earfcn") == identity.arfcn && it.data.channel("pci") == identity.pci
    }?.data
    val nrConfig = records.latest("nr_cfg")?.takeIf { identity.isNr && !it.isEstimated() }?.data?.takeIf {
        it.optInt("pci", -1) == identity.pci && it.channel("ssb_arfcn") == identity.arfcn
    }
    val nrCarrier = if (!identity.isNr) null else records.cellConfig("nr_carrier_config", data)
        ?: nrConfig?.objects("cells")?.firstOrNull {
        it.optInt("pci", -1) == identity.pci && it.channel("ssb_arfcn") == identity.arfcn
    }
    val broadcast = if (identity.isNr) records.cellConfig("nr_sib1_config", data) else null
    val mib = if (identity.isNr) records.cellConfig("nr_mib_config", data) else null
    val dedicatedFrequency = if (identity.isNr) records.cellConfig("nr_frequency_config", data) else null
    val access = records.cellConfig(if (identity.isNr) "nr_access_config" else "lte_access_config", data)
    val nrMeasurement = records["nr_meas"].orEmpty().firstOrNull {
        !it.isEstimated() && it.data.optInt("truncated") != 1 &&
            it.data.optInt("pci", -1) == identity.pci && it.data.channel("arfcn") == identity.arfcn
    }?.data
    val frequencyBand = data?.optInt("band")?.takeIf { it > 0 } ?: nrCarrier?.optInt("band")?.takeIf { it > 0 }
        ?: if (!identity.isNr) CellularUtils.downlinkEarfcnToBand(identity.arfcn).takeIf { it > 0 } else null
    val frequency = if (identity.isNr) data.signalValue("freq_mhz") else
        CellularUtils.downlinkEarfcnToFrequencyMhz(identity.arfcn).takeIf { it >= 0 }
            ?.let { String.format(Locale.ROOT, "%.1f MHz", it) }.signalDisplayValue()
    val band = frequencyBand?.let { if (identity.isNr) "n$it" else "B$it" } ?: "—"
    val bandwidth = sequenceOf(lteCell, lteMib, data, nrCarrier)
        .firstOrNull { (it?.number("bw_mhz") ?: 0.0) > 0.0 }
    val protocol = if (identity.isNr) CellularProtocol.NR else CellularProtocol.LTE
    val summary = listOf(
        DiagField("PCI", identity.pci.takeIf { it >= 0 }?.toString() ?: "—"),
        DiagField(stringResource(R.string.diag_band), band),
    ) + servingCellIdFields(protocol, identity.cid) + listOf(
        DiagField(if (identity.isNr) "SSB NR-ARFCN" else "EARFCN", identity.arfcn.takeIf { it >= 0 }?.toString() ?: "—"),
        DiagField(stringResource(R.string.diag_registered_plmn), registeredPlmn?.displayPlmn() ?: "—"),
        DiagField(stringResource(R.string.diag_cell_plmns), cellPlmns.joinToString(" · ") { it.displayPlmn() }.ifBlank { "—" }),
        DiagField("TAC", data.signalValue("tac")),
        DiagField(if (identity.isNr) "NCI" else "ECI", identity.cid.takeIf { it > 0 }?.toString() ?: "—"),
    ) + listOf(
        DiagField(if (identity.isNr) "SSB (MHz)" else stringResource(R.string.diag_frequency_label),
            frequency),
    ) + listOfNotNull(
        if (!identity.isNr || bandwidth != null)
            DiagField(stringResource(R.string.diag_bandwidth_label), bandwidth.signalValue("bw_mhz"))
        else null,
    )
    val lteMeasurement = if (identity.isNr) null else records["lte_meas"].orEmpty().filter {
        !it.isEstimated() && it.data.optString("src") == "B193" &&
            it.data.optInt("is_serving", 1) != 0 &&
            it.data.channel("pci") == identity.pci && it.data.channel("earfcn") == identity.arfcn
    }.maxByOrNull { it.id }?.data
    val rrcSignal = records["rrc_state"].orEmpty().firstOrNull {
        it.data.optString("rat") == identity.rat && it.data.channel("pci") == identity.pci &&
            it.data.channel("arfcn") == identity.arfcn
    }
    val rrcState = sequenceOf(rrcSignal, nrConfig?.let { records.latest("nr_cfg") })
        .filterNotNull().sortedByDescending { it.id }
        .map { it.data.signalValue("state") }.firstOrNull { it != "-" }
    val scs = nrCarrier?.channel("scs_khz")?.takeIf { it > 0 }
        ?: broadcast?.channel("grid_scs_khz")?.takeIf { it > 0 } ?: when (mib?.optString("scs_common")) {
        "15 / 60 kHz" -> if (identity.arfcn >= 2_016_667) 60 else 15
        "30 / 120 kHz" -> if (identity.arfcn >= 2_016_667) 120 else 30
        else -> null
    }?.takeIf { identity.arfcn > 0 }
    val dlCenter = nrCarrier?.channel("dl_arfcn")?.takeIf { it in 1..3_279_165 }
    val ssbGscn = if (identity.isNr) nrSsbGscn(identity.arfcn, frequencyBand, bandwidth?.channel("bw_mhz")) else null
    val dlPointA = dedicatedFrequency?.channel("dl_pointa")?.takeIf { it in 1..3_279_165 }
        ?: nrDlPointA(identity.arfcn, frequencyBand, broadcast, mib?.channel("ssb_subcarrier_offset"), dlCenter)
    val nrDuplex = nrDuplexMode(frequencyBand)
    val ulPointA = (dedicatedFrequency?.channel("ul_pointa") ?: broadcast?.channel("ul_pointa"))
        ?.takeIf { it in 1..3_279_165 }
        ?: dlPointA?.takeIf { nrDuplex == "TDD" }
    val dlBwp = broadcast?.channel("dl_bwp_riv")?.let(::initialBwp)
    val ulBwp = broadcast?.channel("ul_bwp_riv")?.let(::initialBwp)
    val rar = records["nr_rach"].orEmpty().filter {
        identity.isNr && it.source == "0xB88A" && it.time > 0 &&
            it.data.channel("pci") == identity.pci && it.data.channel("arfcn") == identity.arfcn
    }.maxByOrNull { it.id }
    val lastRarTa = rar?.data?.channel("ta")?.toString()
    val servingSnr = records["nr_snr"].orEmpty().filter {
        !it.isEstimated() && it.data.optInt("carrier", -1) == 0 &&
            it.data.channel("pci") == identity.pci && it.data.channel("arfcn") == identity.arfcn &&
            it.data.number("sinr") != null
    }
    val trsSnr = servingSnr.filter { it.data.optString("rs") == "TRS" }.maxByOrNull { it.id }?.data?.number("sinr")
    val ssbChains = records["nr_ssb_rx"].orEmpty().filter {
        it.data.channel("pci") == identity.pci && it.data.channel("arfcn") == identity.arfcn &&
            SystemClock.elapsedRealtime() - it.receivedAt <= NR_RX_SAMPLE_TIMEOUT
    }.sortedWith(compareByDescending<DiagRecord> { it.source == "B8D8" }.thenByDescending { it.id })
    val schedulePrefix = if (identity.isNr) "nr" else "lte"
    val pcellSchedule = records.servingSchedule(identity).filter { it.data.channel("cc") == 0 }
    fun scheduled(suffix: String) = pcellSchedule.filter {
        !it.isEstimated() && it.data.optString("group") == "${schedulePrefix}_$suffix"
    }.maxByOrNull { it.id }?.data
    val pusch = scheduled("ul_pwr:pusch")
    val pucch = if (identity.isNr) scheduled("ul_pwr:pucch") else
        scheduled("pucch_tx:pucch") ?: scheduled("pucch_power:pucch") ?: scheduled("ul_tx:pucch")
    val puschDbm = pusch?.number("pusch_dbm") ?: scheduled("ul_sched")?.number("pusch_dbm")
    val pucchDbm = pucch?.number("pucch_dbm")
    val transmitPower = listOf(
        DiagField("PUSCH Tx Power", puschDbm?.let { "$it dBm" }.signalDisplayValue()),
        DiagField("PUCCH Tx Power", pucchDbm?.let { "$it dBm" }.signalDisplayValue()),
    )
    fun cyclicPrefix(value: Int?) = when (value) {
        0 -> "Normal"
        1 -> "Extended"
        else -> SIGNAL_UNAVAILABLE
    }
    val downlinkCp = broadcast?.channel("dl_cp_type")
    val uplinkCp = broadcast?.channel("ul_cp_type")
    val nrCp = if (downlinkCp != null && uplinkCp != null && downlinkCp != uplinkCp) {
        "DL ${cyclicPrefix(downlinkCp)} / UL ${cyclicPrefix(uplinkCp)}"
    } else cyclicPrefix(downlinkCp ?: uplinkCp)
    val lteCqi = scheduled("csf")?.channel("cqi") ?: data?.channel("cqi")
    val lteTdd = records.cellConfig("lte_tdd_config", data)
    val lteAssignment = lteTdd?.channel("assignment")
    val ltePattern = lteAssignment?.let { lteTddPattern(it, lteTdd.channel("special") ?: -1) }
    val tddScs = broadcast?.channel("tdd_scs_khz")
    val tddPatterns = listOf(1, 2).mapNotNull { index ->
        val pattern = broadcast?.optJSONObject("tdd_pattern$index") ?: return@mapNotNull null
        val period = pattern.channel("period_us")?.takeIf { it > 0 } ?: return@mapNotNull null
        val dlSlots = pattern.channel("dl_slots") ?: return@mapNotNull null
        val dlSymbols = pattern.channel("dl_symbols") ?: return@mapNotNull null
        val ulSlots = pattern.channel("ul_slots") ?: return@mapNotNull null
        val ulSymbols = pattern.channel("ul_symbols") ?: return@mapNotNull null
        val periodMs = (period / 1000.0).toString().removeSuffix(".0")
        val base = stringResource(R.string.diag_tdd_pattern_value, dlSlots, dlSymbols, ulSlots, ulSymbols)
        val split = tddScs?.let { scs ->
            // Extended CP uses 60 kHz. If the initial BWPs disagree, keep the raw broadcast values.
            if (scs == 60 && (downlinkCp == null || downlinkCp != uplinkCp)) null
            else nrTddAllocation(scs, scs == 60 && downlinkCp == 1, period, dlSlots, dlSymbols, ulSlots, ulSymbols)
        }
        val value = if (split == null) base else "$base\n" +
            stringResource(R.string.diag_tdd_symbol_ratio, split.downlink, split.uplink, split.flexible)
        DiagField(stringResource(R.string.diag_tdd_pattern, index, periodMs), value)
    }
    val commonAccessFields = listOf(
        Triple("p_max_dbm", R.string.diag_broadcast_p_max, "dBm"),
        Triple("preamble_target_dbm", R.string.diag_preamble_target_power, "dBm"),
        Triple("preamble_ramp_db", R.string.diag_preamble_ramp, "dB"),
        Triple("preamble_max_tx", R.string.diag_preamble_max_tx, ""),
    ).mapNotNull { (key, label, unit) ->
        access?.number(key)?.let { DiagField(stringResource(label), "${it.toInt()} $unit".trimEnd()) }
    }
    val lteAccessFields = listOfNotNull(
        access?.number("q_rx_lev_min_dbm")?.let { DiagField("Q-RxLevMin", "${it.toInt()} dBm") },
        access?.channel("prach_cfg_index")?.let { DiagField("PRACH Config Index", it.toString()) },
        access?.channel("prach_root")?.let { DiagField("PRACH Root Index", it.toString()) },
        access?.channel("rar_window_sf")?.let {
            DiagField(stringResource(R.string.diag_rar_window), stringResource(R.string.diag_subframes, it))
        },
        access?.channel("preamble_count")?.let {
            DiagField(stringResource(R.string.diag_preamble_count), it.toString())
        },
    )
    val ltePhr = records.cellConfig("lte_phr", data)?.channel("ph_index")?.takeIf { it in 0..63 }
    val lteBsr = records.cellConfig("lte_bsr", data)
    val lteMacFields = listOfNotNull(
        ltePhr?.let {
            val range = if (it == 63) "≥40 dB" else "[${it - 23}, ${it - 22}) dB"
            DiagField(stringResource(R.string.diag_lte_phr), range)
        },
        (0..3).mapNotNull { index ->
            lteBsr?.channel("lcg$index")?.let { "LCG$index $it" }
        }.chunked(2).joinToString("\n") { it.joinToString(" · ") }.takeIf { it.isNotEmpty() }?.let {
            DiagField(stringResource(R.string.diag_lte_bsr_index), it)
        },
    )
    val engineering = if (identity.isNr) listOf(
        DiagField("RRC", rrcState.signalDisplayValue()),
        DiagField(stringResource(R.string.diag_duplex_mode), nrDuplex.signalDisplayValue()),
        DiagField("SSB Beam Index", nrMeasurement.signalValue("ssb")),
        DiagField("SSB GSCN", ssbGscn?.toString().signalDisplayValue()),
        DiagField(stringResource(R.string.diag_ssb_period), broadcast?.number("ssb_period_ms")
            ?.let { "${it.toInt()} ms" }.signalDisplayValue()),
        DiagField("SS/PBCH Block Power", broadcast?.number("ssb_power_dbm")
            ?.let { "${it.toInt()} dBm" }.signalDisplayValue()),
        DiagField("SCS", scs?.let { "$it kHz" }.signalDisplayValue()),
        DiagField(stringResource(R.string.diag_initial_bwp_cp), nrCp),
        DiagField("DL Center ARFCN / MHz", dlCenter?.let {
            String.format(Locale.ROOT, "%d / %.2f", it, CellularUtils.narfcnToFrequencyMhz(it))
        }.signalDisplayValue()),
        DiagField("DL Point A", dlPointA?.toString().signalDisplayValue()),
        DiagField("UL Point A", ulPointA?.toString().signalDisplayValue()),
        DiagField("Offset to Point A (RB)", broadcast.signalValue("offset_to_pointa")),
        DiagField("SSB Subcarrier Offset", mib.signalValue("ssb_subcarrier_offset")),
        DiagField("Q-RxLevMin (dBm)", broadcast.signalValue("q_rx_lev_min_dbm")),
        DiagField(stringResource(R.string.diag_initial_dl_bwp), dlBwp?.let { (start, length) ->
            stringResource(R.string.diag_bwp_rbs, start, length)
        }.signalDisplayValue()),
        DiagField(stringResource(R.string.diag_initial_ul_bwp), ulBwp?.let { (start, length) ->
            stringResource(R.string.diag_bwp_rbs, start, length)
        }.signalDisplayValue()),
        DiagField(stringResource(R.string.diag_max_dl_layers), nrCarrier?.channel("max_layers")
            ?.takeIf { it in 1..8 }?.toString().signalDisplayValue()),
        DiagField("PRACH Config Index", broadcast.signalValue("prach_cfg_index")),
        DiagField("PRACH Root Index", broadcast.signalValue("prach_root")),
        DiagField(stringResource(R.string.diag_last_nr_rar_ta), lastRarTa.signalDisplayValue()),
        DiagField("DL Path Loss", pusch?.number("pathloss_db")?.let {
            String.format(Locale.ROOT, "%.1f dB", it)
        }.signalDisplayValue()),
    ) + transmitPower + commonAccessFields + (0..3).map { rx ->
        val sinr = ssbChains.firstOrNull { it.data.channel("rx") == rx }?.data?.number("sinr")
        DiagField("SS-SINR Rx$rx", sinr?.let { String.format(Locale.ROOT, "%.1f dB", it) }.signalDisplayValue())
    } + listOfNotNull(
        trsSnr?.let { DiagField("TRS SINR", String.format(Locale.ROOT, "%.1f dB", it)) },
        pusch?.number("pusch_pcmax")?.let { DiagField("PUSCH P-CMAX", String.format(Locale.ROOT, "%.1f dBm", it)) },
    ) +
        nrMeasurement.fixedSignalFields(listOf("rsrp_rx0", "rsrp_rx1")) +
        listOfNotNull(tddScs?.let { DiagField("TDD SCS", "$it kHz") }) + tddPatterns else
        listOf(DiagField("RRC", rrcState.signalDisplayValue()),
            DiagField(stringResource(R.string.diag_duplex_mode), lteDuplexMode(frequencyBand).signalDisplayValue()),
            DiagField("UL CP Type", cyclicPrefix(access?.channel("ul_cp_type"))),
            DiagField("CQI", lteCqi?.toString().signalDisplayValue())) +
        data.fixedSignalFields(listOf("rssi", "ta")) + lteCell.fixedSignalFields(listOf("ul_earfcn")) +
        lteMeasurement.fixedSignalFields(listOf("rsrp_rx0", "rsrp_rx1", "rsrq_rx0", "rsrq_rx1", "sinr_rx0", "sinr_rx1")) +
        (if (lteCell?.has("dl_bw_prb") == true) lteCell else lteMib).fixedSignalFields(listOf("dl_bw_prb")) +
        lteCell.fixedSignalFields(listOf("ul_bw_prb")) + lteMib.fixedSignalFields(listOf("tx_antennas")) +
        transmitPower + commonAccessFields + lteAccessFields + lteMacFields + listOfNotNull(ltePattern?.let {
            DiagField(stringResource(R.string.diag_lte_tdd_assignment, lteAssignment),
                stringResource(R.string.diag_lte_tdd_value, it.frames, it.downlink, it.uplink, it.switching, it.special))
        })
    return ServingParameterFields(summary, engineering)
}

private fun lteDuplexMode(band: Int?): String? = when (band ?: 0) {
    29, 32, 67, 69, 75, 76 -> "SDL"
    in 33..54 -> "TDD"
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 18, 19, 20, 21, 22, 23, 24, 25,
    26, 27, 28, 30, 31, 65, 66, 68, 70, 71, 72, 73, 74, 85, 87, 88, 103, 106 -> "FDD"
    else -> null
}

// 3GPP TS 38.104, tables 5.2-1 and 5.2-2.
private fun nrDuplexMode(band: Int?): String? = when (band) {
    1, 2, 3, 5, 7, 8, 12, 13, 14, 18, 20, 24, 25, 26, 28, 30, 31,
    65, 66, 70, 71, 72, 74, 85, 87, 88, 91, 92, 93, 94, 100, 105, 106, 109 -> "FDD"
    34, 38, 39, 40, 41, 46, 48, 50, 51, 53, 54, 77, 78, 79, 90,
    96, 101, 102, 104, 257, 258, 259, 260, 261, 262, 263 -> "TDD"
    29, 67, 75, 76 -> "SDL"
    80, 81, 82, 83, 84, 86, 89, 95, 97, 98, 99 -> "SUL"
    else -> null
}

private fun nrSsbGscn(arfcn: Int, band: Int?, bandwidthMhz: Int?): Int? {
    // Use the regular global synchronization raster, TS 38.104 table 5.4.3.1-1.
    // Band n100 and 3 MHz channels can use separate rasters.
    if (band == 100 || bandwidthMhz == 3 || (arfcn < 200_000 && bandwidthMhz == null)) return null
    return when (arfcn) {
        in 240 until 600_000 -> {
            val n = arfcn / 240
            when (arfcn % 240) {
                10 -> 3 * n - 1
                30 -> 3 * n
                50 -> 3 * n + 1
                else -> null
            }
        }
        in 600_000 until 2_016_667 -> {
            val delta = arfcn - 600_000
            if (delta % 96 == 0) 7_499 + delta / 96 else null
        }
        in 2_016_667..3_279_165 -> {
            val delta = arfcn - 2_016_667
            if (delta % 288 == 0) 22_256 + delta / 288 else null
        }
        else -> null
    }
}

private fun initialBwp(riv: Int): Pair<Int, Int>? {
    // The SIB1 locationAndBandwidth RIV uses a reference bandwidth of 275 RBs.
    val rbCount = 275
    if (riv !in 0 until rbCount * (rbCount + 1) / 2) return null
    val start = riv % rbCount
    val length = riv / rbCount + 1
    return if (start + length <= rbCount) start to length else (rbCount - 1 - start) to (rbCount + 2 - length)
}

private fun nrDlPointA(ssbArfcn: Int, band: Int?, broadcast: JSONObject?, mibOffset: Int?, center: Int?): Int? {
    // FR1 bands with a single SSB subcarrier spacing in the synchronization raster.
    val ssbScsKhz = when (band) {
        1, 3, 8, 20, 28 -> 15
        77, 78, 79 -> 30
        else -> return null
    }
    val offsetToPointA = broadcast?.channel("offset_to_pointa")?.takeIf { it in 0..2199 } ?: return null
    if (ssbArfcn !in 1..2_016_666) return null
    val ssbKhz = (CellularUtils.narfcnToFrequencyMhz(ssbArfcn) * 1000).roundToInt()
    val ssbWithoutFineOffset = ssbKhz - 120 * ssbScsKhz - 12 * offsetToPointA * 15
    val mibLowBits = mibOffset?.takeIf { it in 0..15 }
    val centerKhz = center?.takeIf { it in 1..2_016_666 }
        ?.let { (CellularUtils.narfcnToFrequencyMhz(it) * 1000).roundToInt() }
    val gridOffset = broadcast.channel("grid_offset")?.takeIf { it in 0..2199 }
    val rb = broadcast.channel("grid_rbs")?.takeIf { it in 1..275 }
    val scs = broadcast.channel("grid_scs_khz")?.takeIf { it in setOf(15, 30, 60) }
    // Resolve the four MIB bits against the reported RF center and grid when available.
    val fromGrid = if (centerKhz != null && gridOffset != null && rb != null && scs != null)
        (centerKhz - (12 * gridOffset + 6 * rb) * scs).takeIf {
            val fineOffset = ssbWithoutFineOffset - it
            fineOffset in 0..(23 * 15) && fineOffset % 15 == 0 &&
                (mibLowBits == null || (fineOffset / 15) % 16 == mibLowBits)
        } else null
    val pointAKhz = fromGrid ?: mibLowBits?.takeIf { it >= 8 }?.let { ssbWithoutFineOffset - it * 15 }
        ?: return null
    if (pointAKhz <= 0) return null
    val (baseArfcn, baseKhz, stepKhz) = if (pointAKhz < 3_000_000) Triple(0, 0, 5)
        else Triple(600_000, 3_000_000, 15)
    val deltaKhz = pointAKhz - baseKhz
    return if (deltaKhz % stepKhz == 0) baseArfcn + deltaKhz / stepKhz else null
}

internal data class ServingParameterFields(val primary: List<DiagField>, val secondary: List<DiagField>)

internal fun servingCellIdFields(protocol: CellularProtocol, cid: Long?): List<DiagField> {
    val id = cid?.takeIf { it > 0 }
    fun field(label: String, value: Number?) = DiagField(label, value?.toString().signalDisplayValue())
    return when (protocol) {
        CellularProtocol.NR -> listOf(
            field("gNB ID (24 bit)", id?.let { CalculationUtils.getGnbIdFromNci(it, 24) }),
            field("Cell ID", id?.let { CalculationUtils.getSectorIdFromNci(it, 24) }),
        )
        CellularProtocol.LTE -> listOf(
            field("eNB ID", id?.let { CalculationUtils.getEnodebIdFromCellId(it.toInt()) }),
            field("Cell ID", id?.let { CalculationUtils.getSectorIdFromCellId(it.toInt()) }),
        )
        CellularProtocol.UMTS -> listOf(
            field("RNC", id?.let { CalculationUtils.getUmtsRncFromCid(it.toInt()) }),
            field("Cell ID", id?.let { CalculationUtils.getUmtsShortCellIdFromCid(it.toInt()) }),
        )
        else -> emptyList()
    }
}

@Composable
internal fun SignalParameterCard(title: String, fields: List<DiagField>, lastRow: (@Composable () -> Unit)? = null) {
    if (fields.isEmpty()) return
    SignalSectionCard(contentPadding = 12.dp) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        SignalParameterTable(fields, lastRow)
    }
}

@Composable
private fun SignalTableDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

private fun JSONObject.number(key: String) = optDouble(key).takeIf { it.isFinite() }

@Composable
private fun SignalParameterTable(fields: List<DiagField>, lastRow: (@Composable () -> Unit)? = null) {
    Surface(shape = DashboardCardDefaults.smallShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 10.dp)) {
            fields.forEachIndexed { index, field ->
                if (index > 0) SignalTableDivider()
                SignalParameterRow(field.label) {
                    val value = field.value.signalDisplayValue()
                    Text(value, textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Medium, maxLines = if ('\n' in value) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (value == SIGNAL_UNAVAILABLE) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (lastRow != null) {
                SignalTableDivider()
                lastRow()
            }
        }
    }
}

@Composable
internal fun SignalParameterRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

private data class NeighborReading(
    val rat: String,
    val channel: Int,
    val pci: Int,
    val rsrp: NeighborMetric?,
    val rsrq: NeighborMetric?,
    val sinr: NeighborMetric?,
    val source: String,
    val reference: String? = null,
    val sinrOnly: Boolean = false,
    val beam: Int? = null,
)

private data class NeighborMetric(
    val text: String,
    val value: Double,
    val beamReference: String? = null,
    val otherReference: String? = null,
    val source: String? = null,
)
private fun Double.reading() = NeighborMetric(String.format(Locale.ROOT, "%.1f", this), this)

private data class NeighborKey(val rat: String, val pci: Int, val channel: Int)
private data class NeighborMatch(val name: String, val distanceMeters: Int)
private val NeighborReading.matchKey: NeighborKey
    get() = NeighborKey(rat, pci, channel)

private fun directNeighborReadings(rat: String, serving: JSONObject?, records: List<DiagRecord>): List<NeighborReading> = buildList {
    for (record in records.sortedWith(compareByDescending<DiagRecord> { it.receivedAt }.thenByDescending { it.id })) {
        val data = record.data
        if (rat == "NR" && record.type == "nr_neighbor_sinr") {
            val channel = data.channel("arfcn")?.takeIf { it in 1..3_279_165 } ?: continue
            val pci = data.optInt("pci", -1)
            if (pci !in 0..1007) continue
            val reference = NrMeasurementReference.Ssb.label
            val sinr = data.number("sinr")?.reading()?.copy(beamReference = reference) ?: continue
            add(NeighborReading(rat, channel, pci, null, null, sinr,
                "DIAG ${record.source}", reference, sinrOnly = true))
        }
        if (rat == "NR" && record.type == "nr_meas" && !record.isEstimated() && data.optInt("truncated") != 1) {
            val channel = data.channel("arfcn")?.takeIf { it in 1..3_279_165 } ?: continue
            for (entry in data.objects("cells")) {
                val pci = entry.optInt("pci", -1)
                if (pci !in 0..1007 || pci == data.optInt("pci", -1) ||
                    (pci == serving?.optInt("pci", -1) && channel == serving.channel("arfcn"))
                ) continue
                add(NeighborReading(rat, channel, pci,
                    entry.number("rsrp")?.reading(), entry.number("rsrq")?.reading(),
                    entry.number("sinr")?.reading(), "DIAG ${record.source}",
                    beam = entry.channel("ssb")?.takeIf { it in 0..63 }))
            }
        }
        if (rat == "LTE" && record.type == "lte_meas") {
            val channel = data.channel("earfcn")?.takeIf { it <= 262_143 } ?: continue
            if (record.source == "B193" && data.optInt("is_serving", -1) == 0) {
                val pci = data.optInt("pci", -1)
                if (pci in 0..503) {
                    add(NeighborReading(rat, channel, pci, data.number("rsrp")?.reading(),
                        data.number("rsrq")?.reading(), data.number("sinr")?.reading(), "DIAG B193"))
                }
                continue
            }
            for (entry in data.objects("cells")) {
                val pci = entry.optInt("pci", -1)
                if (pci !in 0..503) continue
                add(NeighborReading(rat, channel, pci, entry.number("rsrp")?.reading(),
                    entry.number("rsrq")?.reading(), null, "DIAG ${record.source}"))
            }
        }
    }
}

/* Measurement records that feed the neighbor table for a RAT; keep in sync
   with directNeighborReadings(). */
private val neighborSourceTypes = mapOf(
    "NR" to setOf("nr_neighbor_sinr", "nr_meas"),
    "LTE" to setOf("lte_meas"),
)
private const val NEIGHBOR_SNAPSHOT_WINDOW_MS = 30_000L

/* Diagnostics snapshot of the neighbor table state at export time.
 *
 * rows alone cannot distinguish "no neighbours detected" from "readings
 * expired out of MEASUREMENT_TIMEOUT" (e.g. a DIY band-lock SSR stalls the
 * DIAG stream for >30 s and every measurement record is dropped). The
 * newest_age_ms / stale pair makes that difference explicit:
 *   stale=1                    -> data window lapsed; rows=0 is not evidence
 *                                 of "no neighbours"
 *   newest_age_ms=-1, stale=0  -> no measurement record for this RAT at all
 *   newest_age_ms small        -> rows reflect live data. */
internal fun neighborTableDiagnostics(records: List<DiagRecord>): List<JSONObject> = listOf("LTE", "NR").map { rat ->
    val serving = records.lastOrNull { it.type == "serving" && it.data.optString("rat") == rat }?.data
    val grouped = directNeighborReadings(rat, serving, records).groupBy { it.matchKey }
    val visible = grouped.values.map(::mergeNeighborReadings).filterNot { it.isServingNeighbor(serving) }
    val sources = JSONObject()
    grouped.values.flatten().groupingBy { it.source }.eachCount().forEach { (source, count) -> sources.put(source, count) }
    val sourceTypes = neighborSourceTypes[rat] ?: emptySet()
    val newestAge = records.asSequence().filter { it.type in sourceTypes }
        .maxOfOrNull { it.receivedAt }?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
    JSONObject().put("type", "neighbor_table_snapshot").put("rat", rat).put("source_entries", sources)
        .put("rows", visible.size).put("rows_with_rsrp", visible.count { it.rsrp != null })
        .put("rows_with_rsrq", visible.count { it.rsrq != null }).put("rows_with_sinr", visible.count { it.sinr != null })
        .put("rows_with_beam", visible.count { it.beam != null })
        .put("rows_with_sinr_only", visible.count { it.sinrOnly })
        .put("serving_rows_excluded", grouped.size - visible.size)
        .put("rrc_sinr_samples", records.count { it.type == "rrc_neighbor_sinr" && it.data.optString("rat") == rat })
        .put("newest_age_ms", newestAge ?: -1L)
        .put("stale", newestAge != null && newestAge > NEIGHBOR_SNAPSHOT_WINDOW_MS)
}

private fun NeighborReading.isServingNeighbor(serving: JSONObject?) =
    pci == serving?.channel("pci") && channel == serving.channel("arfcn")

internal fun RootSignalState.mapDiagNeighbors(station: StationInfo): Set<MapNeighborCell>? {
    if (status != RootDiagStatus.Running) return null
    val rat = station.networkType
    val latestServing = records.lastOrNull { it.type == "serving" && it.data.optString("rat") == rat }
        ?: return null
    val serving = latestServing.data
    if (serving.channel("pci") != station.pci || serving.channel("arfcn") != station.earfcn) return null
    val cellId = serving.optLong("cid", -1)
    if (cellId > 0 && station.cellId > 0 && cellId != station.cellId) return null
    val now = SystemClock.elapsedRealtime()
    val seenAge = serving.optLong("seen_age_ms", -1)
    if (seenAge < 0 || now - latestServing.receivedAt + seenAge > 20_000L) return null
    val recent = records.filter { it.id !in cachedMeasureIds && now - it.receivedAt in 0..30_000L }
    return directNeighborReadings(rat, serving, recent).asSequence()
        .filterNot { it.sinrOnly || it.isServingNeighbor(serving) }
        .map { MapNeighborCell(rat == "NR", it.pci, it.channel) }
        .toSet()
        .takeIf { it.isNotEmpty() }
}

@Composable
internal fun NeighborMeasurements(
    rat: String,
    serving: JSONObject?,
    records: List<DiagRecord>,
    androidCells: CellularViewModel?,
    isSignalTab: Boolean,
    nrCarriers: List<NrCarrier> = emptyList(),
    lteSecondaries: Set<Pair<Int, Int>> = emptySet(),
) {
    val androidNr = if (rat == "NR") androidCells?.nrNeighbors?.observeAsState()?.value.orEmpty() else emptyList()
    val androidLte = if (rat == "LTE") androidCells?.lteNeighbors?.observeAsState()?.value.orEmpty() else emptyList()
    val androidSource = stringResource(R.string.diag_neighbor_android_source)
    val diag = remember(records, serving, rat) {
        directNeighborReadings(rat, serving, records)
    }
    val neighbors = remember(diag, androidNr, androidLte, rat, androidSource) {
        val android = if (rat == "NR") androidNr.filter { it.pci in 0..1007 && it.narfcn in 1..3_279_165 }.flatMap {
            val ssb = NeighborReading("NR", it.narfcn, it.pci,
                it.ssRsrp.reading(), it.ssRsrq.reading(), it.ssSinr.reading(), androidSource, NrMeasurementReference.Ssb.label)
            val csi = it.csiSinr.reading()?.let { sinr ->
                NeighborReading("NR", it.narfcn, it.pci, null, null, sinr,
                    androidSource, NrMeasurementReference.CsiRs.label, sinrOnly = true)
            }
            listOfNotNull(ssb, csi)
        } else androidLte.filter { it.pci in 0..503 && it.earfcn in 0..262_143 }.map {
            NeighborReading("LTE", it.earfcn, it.pci,
                it.rsrp.reading(), it.rsrq.reading(), it.sinr.reading(), androidSource)
        }
        (diag + android).groupBy { it.matchKey }.values.map(::mergeNeighborReadings)
    }
    val keys = remember(neighbors) { neighbors.map { it.matchKey }.toSet() }
    val matches = rememberNeighborMatches(keys, isSignalTab)

    val ordered = remember(neighbors, serving, nrCarriers, lteSecondaries) {
        val nrServing = nrCarriers.filterNot { it.lastObservation() }.mapTo(mutableSetOf()) { it.identity() }
        neighbors.filterNot {
            it.sinrOnly || it.isServingNeighbor(serving) ||
                (rat == "LTE" && (it.channel to it.pci) in lteSecondaries) ||
                (rat == "NR" && NrCarrier.Identity(it.pci, it.channel) in nrServing)
        }
            .sortedWith(compareByDescending<NeighborReading> { it.rsrp?.value ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.channel }.thenBy { it.pci })
    }
    val palettes = rememberSignalBarColors(if (rat == "NR") CellularProtocol.NR else CellularProtocol.LTE)
    val valueLine = MaterialTheme.typography.labelMedium.lineHeight
    val detailLine = MaterialTheme.typography.labelSmall.lineHeight
    val rowHeight = with(LocalDensity.current) { valueLine.toDp() } + 15.dp
    val detailHeight = with(LocalDensity.current) { detailLine.toDp() } + 2.dp
    val visibleRows = 4
    SignalSectionCard(contentPadding = 12.dp) {
        Text(stringResource(R.string.diag_neighbor_count, ordered.size), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        if (ordered.isNotEmpty()) {
            val showBeam = rat == "NR"
            fun heightOf(neighbor: NeighborReading) =
                rowHeight + if (matches[neighbor.matchKey] != null) detailHeight else 0.dp
            val tableHeight = ordered.take(visibleRows).fold(0.dp) { height, neighbor -> height + heightOf(neighbor) }
            Surface(shape = DashboardCardDefaults.smallShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    NeighborColumns(rat, showBeam)
                    SignalTableDivider()
                    Column(Modifier.fillMaxWidth().height(tableHeight)
                        .verticalScroll(rememberScrollState(), enabled = ordered.size > visibleRows)) {
                        ordered.forEachIndexed { index, neighbor ->
                            Box(Modifier.height(heightOf(neighbor))) {
                                NeighborMeasurement(neighbor, matches[neighbor.matchKey], palettes, showBeam)
                                if (index + 1 < ordered.size) {
                                    HorizontalDivider(Modifier.align(Alignment.BottomCenter),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun mergeNeighborReadings(readings: List<NeighborReading>): NeighborReading {
    val primary = readings.firstOrNull { it.source == "DIAG B179" && it.rsrp != null }
        ?: readings.firstOrNull { !it.sinrOnly && it.rsrp != null }
        ?: readings.firstOrNull { !it.sinrOnly } ?: readings.first()
    val isCsi = primary.reference == NrMeasurementReference.CsiRs.label
    val compatible = readings.filter { (it.reference == NrMeasurementReference.CsiRs.label) == isCsi }
    val sources = linkedSetOf(primary.source)
    fun measured(metric: (NeighborReading) -> NeighborMetric?): NeighborMetric? =
        metric(primary)?.copy(source = primary.source) ?: compatible.firstOrNull { metric(it) != null }?.let {
            sources += it.source
            metric(it)?.copy(source = it.source)
        }

    val rsrp = measured { it.rsrp }
    val rsrq = measured { it.rsrq }
    val sinr = measured { it.sinr?.takeIf { metric -> metric.beamReference == null } }
        ?: measured { it.sinr }
        ?: readings.firstOrNull { it.reference != null && it.sinr != null }?.let {
            sources += it.source
            it.sinr?.copy(otherReference = it.reference, source = it.source)
        }
    sources.remove(sinr?.source)
    return primary.copy(rsrp = rsrp, rsrq = rsrq, sinr = sinr, source = sources.joinToString(" · "))
}

@Composable
private fun NeighborColumns(rat: String, showBeam: Boolean) {
    val labels = listOf("PCI", if (rat == "NR") "ARFCN" else "EARFCN", "RSRP\ndBm", "RSRQ\ndB")
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBeam) Text("Beam", Modifier.weight(neighborBeamWeight), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        for ((index, label) in labels.withIndex()) {
            Text(label, Modifier.weight(neighborColumnWeights[index]), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

private val neighborColumnWeights = listOf(0.7f, 1.1f, 1.2f, 1.2f)
private const val neighborBeamWeight = 0.85f

@Composable
private fun NeighborMeasurement(
    neighbor: NeighborReading, match: NeighborMatch?, palettes: List<SignalBarColors>, showBeam: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val valueStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showBeam) Text(neighbor.beam?.toString().signalDisplayValue(), Modifier.weight(neighborBeamWeight),
                style = valueStyle, maxLines = 1)
            Text(neighbor.pci.toString(), Modifier.weight(neighborColumnWeights[0]),
                style = valueStyle, color = colors.primary, maxLines = 1)
            Text(neighbor.channel.toString(), Modifier.weight(neighborColumnWeights[1]),
                style = valueStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOf(neighbor.rsrp, neighbor.rsrq).forEachIndexed { index, reading ->
                Column(Modifier.weight(neighborColumnWeights[index + 2]), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(reading?.text.signalDisplayValue(), style = valueStyle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    ColoredSignalBar(reading?.value, palettes[index], Modifier.fillMaxWidth())
                }
            }
        }
        if (match != null) {
            Text("${match.name} · ${stringResource(R.string.diag_neighbor_distance, match.distanceMeters)}",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberNeighborMatches(keys: Set<NeighborKey>, active: Boolean): Map<NeighborKey, NeighborMatch> {
    val context = LocalContext.current.applicationContext
    var fix by remember { mutableStateOf<Location?>(null) }
    val hasKeys = keys.isNotEmpty()
    LifecycleResumeEffect(active, hasKeys) {
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { candidate ->
            if (candidate.isRecent() && candidate.hasAccuracy() && candidate.accuracy <= 100f) {
                val previous = fix
                if (previous == null || candidate.elapsedRealtimeNanos > previous.elapsedRealtimeNanos &&
                    (candidate.accuracy <= previous.accuracy * 2 || !previous.isRecent())
                ) fix = candidate
            }
        }
        if (active && hasKeys && manager != null &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                runCatching {
                    manager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                    manager.requestLocationUpdates(provider, 5_000L, 0f, listener, context.mainLooper)
                }
            }
        }
        onPauseOrDispose {
            runCatching { manager?.removeUpdates(listener) }
            fix = null
        }
    }

    val location = fix?.takeIf { it.isRecent() }
    val matchState by produceState(emptyMap<NeighborKey, NeighborMatch>(), location, keys) {
        if (location == null || keys.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val database = EngineeringParamDbHelper.getInstance(context)
            buildMap {
                val distance = FloatArray(1)
                for (key in keys) {
                    val param = runCatching {
                        database.matchMeasuredNeighbor(location.latitude, location.longitude, key.rat == "NR", key.pci, key.channel)
                    }.getOrNull() ?: continue
                    val name = param.cellName.orEmpty().ifBlank { param.siteName.orEmpty() }
                    if (name.isBlank()) continue
                    Location.distanceBetween(location.latitude, location.longitude, param.lat, param.lon, distance)
                    put(key, NeighborMatch(name, distance[0].toInt()))
                }
            }
        }
    }
    return if (location == null) emptyMap() else matchState
}

private fun Int.reading() = takeUnless { it == UNSET_VALUE }?.let { NeighborMetric(it.toString(), it.toDouble()) }
private fun JSONObject.channel(key: String) = optInt(key, -1).takeIf { it >= 0 }

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

private fun JSONObject.neighborMetric(key: String, rat: String, singleReading: Boolean = false): NeighborMetric? {
    val code = optInt(key, -1)
    val nr = rat == "NR"
    val (edge, step, max) = when (key) {
        "rsrp_index" -> if (nr) Triple(-156.0, 1.0, 126) else Triple(-140.0, 1.0, 97)
        "rsrq_index" -> if (nr) Triple(-43.0, 0.5, 127) else Triple(-19.5, 0.5, 34)
        "sinr_index", "ssb_beam_sinr_index", "csi_beam_sinr_index" -> Triple(-23.0, 0.5, 127)
        else -> return null
    }
    if (code !in 0..max) return null
    fun format(value: Double) = String.format(Locale.ROOT, if (value % 1.0 == 0.0) "%.0f" else "%.1f", value)
    val lower = edge + step * (code - 1)
    val representative = when (key) {
        "rsrq_index" -> (code - if (nr) 87 else 40) / 2.0
        else -> edge + step * code
    }
    val text = when {
        code == 0 -> "<${format(edge)}"
        code == max -> "≥${format(lower)}"
        singleReading -> format(representative)
        else -> "[${format(lower)}, ${format(lower + step)})"
    }
    return NeighborMetric(text, representative)
}

/** L3 quantization bins, per TS 38.133 / 36.133. */
internal fun JSONObject.reportedQuantity(key: String, rat: String): String? =
    neighborMetric(key, rat)?.let { "${it.text} ${if (key == "rsrp_index") "dBm" else "dB"}" }
