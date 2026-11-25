package com.craxiom.networksurvey.ui.cellular

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SignalCellularAlt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults as MiuixProgressIndicatorDefaults
import androidx.compose.material3.LinearProgressIndicator as M3LinearProgressIndicator
import com.craxiom.networksurvey.util.ThemePref
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.livedata.observeAsState
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.model.AndroidNrCarriers
import com.craxiom.networksurvey.model.CellularProtocol
import com.craxiom.networksurvey.model.EngineeringParam
import com.craxiom.networksurvey.model.StationInfo
import com.craxiom.networksurvey.db.EngineeringParamDbHelper
import androidx.compose.runtime.produceState
import com.craxiom.networksurvey.fragments.model.CellularViewModel
import com.craxiom.networksurvey.fragments.model.NrSecondaryCellViewState
import com.craxiom.networksurvey.ui.component.statustag.StatusTag
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults
import com.craxiom.networksurvey.ui.dashboard.DashboardCardHeader
import com.craxiom.networksurvey.ui.main.FloatingNavigationClearance
import com.craxiom.networksurvey.ui.rootdiag.DiagField
import com.craxiom.networksurvey.ui.rootdiag.NeighborMeasurements
import com.craxiom.networksurvey.ui.rootdiag.SIGNAL_UNAVAILABLE
import com.craxiom.networksurvey.ui.rootdiag.ServingParameterFields
import com.craxiom.networksurvey.ui.rootdiag.SignalParameterCard
import com.craxiom.networksurvey.ui.rootdiag.servingCellIdFields
import com.craxiom.networksurvey.ui.rootdiag.signalDisplayValue
import com.craxiom.networksurvey.ui.theme.InterfaceStyle
import com.craxiom.networksurvey.ui.theme.LocalInterfaceStyle
import com.craxiom.networksurvey.util.ColorUtils
import com.craxiom.networksurvey.util.CellularUtils.UplinkReference
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun CellularDetailsScreen(
    viewModel: CellularViewModel,
    chartViewModel: CellularChartViewModel,
    onNrInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 监听主题/偏好版本号：进度条风格等偏好切换时立即重组刷新，无需等待导航
    @Suppress("UNUSED_EXPRESSION")
    ThemePref.currentThemeVersion

    val airplaneMode by viewModel.getAirplaneModeActive().observeAsState(false)

    val protocol by viewModel.getServingCellProtocol().observeAsState(CellularProtocol.NONE)
    val mcc by viewModel.getMcc().observeAsState("")
    val mnc by viewModel.getMnc().observeAsState("")
    val registeredPlmn by viewModel.getRegisteredPlmn().observeAsState("")
    val cellPlmns by viewModel.getCellPlmns().observeAsState("")
    val areaCode by viewModel.getAreaCode().observeAsState("")
    val cellId by viewModel.getCellId().observeAsState(null)
    val channelNumber by viewModel.getChannelNumber().observeAsState("")
    val frequency by viewModel.getFrequency().observeAsState("")
    val uplink by viewModel.uplinkReference.observeAsState()
    val band by viewModel.getBand().observeAsState("")
    val lteBand by viewModel.getLteBand().observeAsState("")
    val pci by viewModel.getPci().observeAsState("")
    val bandwidth by viewModel.getBandwidth().observeAsState("")
    val ta by viewModel.getTa().observeAsState("")
    val cqi by viewModel.getCqi().observeAsState("")

    val signalOne by viewModel.getSignalOne().observeAsState(null)
    val signalTwo by viewModel.getSignalTwo().observeAsState(null)
    val signalThree by viewModel.getSignalThree().observeAsState(null)

    val nrSecondary by viewModel.getNrSecondaryCell().observeAsState(null)
    val reportedNrCarriers by viewModel.nrCarriers.observeAsState(emptyList())
    val nrCarrierClock by produceState(0L, reportedNrCarriers) {
        if (reportedNrCarriers.size >= 2) {
            do {
                value = SystemClock.elapsedRealtime()
                if (AndroidNrCarriers.displayAt(reportedNrCarriers, value).size < 2) break
                delay(1_000)
            } while (true)
        }
    }
    val nrCarriers = AndroidNrCarriers.displayAt(reportedNrCarriers,
        maxOf(nrCarrierClock, SystemClock.elapsedRealtime()))
    val nrLastSeen by viewModel.getNrLastSeenText().observeAsState("")

    val umtsNeighbors by viewModel.getUmtsNeighbors().observeAsState(null)
    val gsmNeighbors by viewModel.getGsmNeighbors().observeAsState(null)

    val isLive = protocol != CellularProtocol.NONE

    val matchKey = cellId?.takeIf { it > 0 && (protocol == CellularProtocol.NR || protocol == CellularProtocol.LTE) }
        ?.let { CellularViewModel.ServingCellKey(protocol, it, mcc, mnc, pci, channelNumber) }
    val cellMatch by viewModel.servingCellMatch.observeAsState()
    val matchedCell = cellMatch?.takeIf { it.key() == matchKey }?.parameter()
    LaunchedEffect(viewModel, matchKey) {
        if (viewModel.servingCellMatch.value?.key() != matchKey) viewModel.setServingCellMatch(null)
        if (matchKey != null) {
            val station = StationInfo().apply {
                isServing = true
                this.cellId = matchKey.cellId()
                networkType = matchKey.protocol().name
                this.pci = matchKey.pci().substringBefore(' ').trim().toIntOrNull() ?: -1
                earfcn = matchKey.channel().toIntOrNull() ?: -1
                val divisor = if (matchKey.protocol() == CellularProtocol.NR) 4096 else 256
                gnbId = (matchKey.cellId() / divisor).toInt()
                sectorId = (matchKey.cellId() % divisor).toInt()
                this.mcc = matchKey.mcc().toIntOrNull() ?: 0
                this.mnc = matchKey.mnc().toIntOrNull() ?: 0
            }
            val parameter = withContext(Dispatchers.IO) {
                runCatching {
                    val database = EngineeringParamDbHelper.getInstance(context.applicationContext)
                    database.matchServingIdentity(station)
                }.getOrNull()
            }
            viewModel.setServingCellMatch(CellularViewModel.ServingCellMatch(matchKey, parameter))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp)
    ) {
        if (airplaneMode) {
            WarningCard(text = "已开启飞行模式，无法获取蜂窝网络信息")
        }

        ServingNetworkCard(viewModel)

        if (!isLive) return@Column
        if (protocol != CellularProtocol.NR || nrCarriers.count { !it.lastObservation() } < 2) {
            ServingSignalMetrics(protocol, signalOne, signalTwo, signalThree)
        }
        if (protocol == CellularProtocol.NR || protocol == CellularProtocol.LTE) {
            NrCarrierSection(nrCarriers, fromDiag = false)
        }
        nrSecondary?.takeIf { protocol == CellularProtocol.LTE && !it.idle() &&
            nrCarriers.count { carrier -> !carrier.lastObservation() } < 2 }?.let { secondary ->
            NrSecondaryCard(secondary, nrLastSeen, onNrInfoClick)
        }
        val fields = cellIdentityFields(
            protocol, registeredPlmn, cellPlmns, areaCode, cellId, channelNumber, frequency,
            band, lteBand, pci, bandwidth, ta, cqi, matchedCell, uplink,
        )
        SignalParameterCard(stringResource(R.string.serving_cell_parameters), fields.primary)

        // 邻区按制式展示
        if (protocol == CellularProtocol.NR || protocol == CellularProtocol.LTE) {
            NeighborMeasurements(protocol.name, null, emptyList(), viewModel, isSignalTab = false)
        }
        val umtsN = umtsNeighbors
        if (protocol == CellularProtocol.UMTS && !umtsN.isNullOrEmpty()) {
            NeighborSectionCard(title = "UMTS 邻区", neighbors = umtsN.map {
                "UARFCN ${it.uarfcn}  ·  PSC ${it.psc}  ·  RSCP ${it.rscp} dBm"
            })
        }
        val gsmN = gsmNeighbors
        if (protocol == CellularProtocol.GSM && !gsmN.isNullOrEmpty()) {
            NeighborSectionCard(title = "GSM 邻区", neighbors = gsmN.map {
                "ARFCN ${it.arfcn}  ·  BSIC ${it.bsic}  ·  RSSI ${it.rssi} dBm"
            })
        }

        CellularChartComponent(viewModel = chartViewModel)
        SignalParameterCard(stringResource(R.string.diag_cell_details), fields.secondary)

        val navigationInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val bottomClearance = if (ThemePref.isFloatingNavigationEnabled(context)) {
            FloatingNavigationClearance + navigationInset
        } else 24.dp
        Spacer(Modifier.height(bottomClearance))
    }
}

@Composable
internal fun SignalSectionCard(contentPadding: Dp? = null, content: @Composable () -> Unit) {
    val style = LocalInterfaceStyle.current
    val colors = MaterialTheme.colorScheme
    val background = when {
        colors.surfaceContainerLow == Color.Black -> colors.surfaceBright
        style == InterfaceStyle.MINIMAL -> colors.surface
        else -> colors.surfaceContainerLow
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = DashboardCardDefaults.margin,
            vertical = style.sectionSpacing / 2f,
        ),
        cornerRadius = style.cardRadius,
        insideMargin = PaddingValues(contentPadding ?: style.cardPadding),
        colors = CardDefaults.defaultColors(color = background, contentColor = colors.onSurface),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (style == InterfaceStyle.COMPACT) 4.dp else 8.dp)) {
            content()
        }
    }
}

@Composable
internal fun ServingNetworkCard(viewModel: CellularViewModel) {
    val carrier by viewModel.carrier.observeAsState("")
    val registeredPlmn by viewModel.registeredPlmn.observeAsState()
    val heroText by viewModel.heroText.observeAsState("")
    val carrierAggregation by viewModel.carrierAggregation.observeAsState()
    val matchedCell = viewModel.servingCellMatch.observeAsState().value?.parameter()
    val protocol by viewModel.servingCellProtocol.observeAsState(CellularProtocol.NONE)
    val airplaneMode by viewModel.airplaneModeActive.observeAsState(false)
    val cellName = matchedCell?.let { it.cellName.orEmpty().ifBlank { it.siteName.orEmpty() } }.orEmpty()

    val operator = carrier.ifBlank { registeredPlmn.orEmpty() }
    val colors = MiuixTheme.colorScheme
    val carrierIcon = when {
        operator.contains("电信") || operator.contains("Telecom", ignoreCase = true) -> R.drawable.ic_carrier_telecom
        operator.contains("移动") || operator.contains("China Mobile", ignoreCase = true) -> R.drawable.ic_carrier_mobile
        operator.contains("联通") || operator.contains("Unicom", ignoreCase = true) -> R.drawable.ic_carrier_unicom
        operator.contains("广电") || operator.contains("CBN", ignoreCase = true) -> R.drawable.ic_carrier_broadcast
        else -> null
    }

    SignalSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(colors.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (carrierIcon != null) {
                    Icon(painterResource(carrierIcon), null, Modifier.size(25.dp), tint = colors.primary)
                } else {
                    Icon(Icons.Rounded.SignalCellularAlt, null, Modifier.size(25.dp), tint = colors.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MiuixText(
                    text = operator.ifBlank { stringResource(R.string.cellular_title) },
                    modifier = Modifier.semantics { heading() },
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (heroText.isNotBlank()) {
                    MiuixText(heroText, style = MiuixTheme.textStyles.body2, color = colors.primary)
                } else if (protocol == CellularProtocol.NONE && !airplaneMode) {
                    MiuixText(
                        stringResource(R.string.waiting_for_cellular_data),
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        if (cellName.isNotBlank()) {
            MiuixText(cellName, style = MiuixTheme.textStyles.body2, color = colors.onSurfaceSecondary)
        }
        carrierAggregation?.let {
            NetworkStatusPills(it.chipLabels.filter(String::isNotBlank).distinct())
            if (it.summary.isNotBlank()) {
                MiuixText(it.summary, style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
internal fun NetworkStatusPills(pills: List<String>) {
    val colors = MaterialTheme.colorScheme
    if (pills.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            pills.forEach { label ->
                Surface(
                    shape = CircleShape,
                    color = colors.surfaceContainerHigh,
                    contentColor = colors.onSurfaceVariant,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private data class SignalMetric(
    val label: String,
    val value: Number?,
    val minValue: Int,
    val maxNormalizedValue: Int,
    val unit: String,
) {
    val readingText: String get() =
        value?.let { if (it is Double) String.format(Locale.ROOT, "%.1f", it) else it.toString() }
            ?: SIGNAL_UNAVAILABLE
}

@Composable
internal fun ServingSignalMetrics(
    protocol: CellularProtocol,
    signalOne: Number?,
    signalTwo: Number?,
    signalThree: Number?,
    sinrLabel: String? = null,
    sinrNote: String? = null,
) {
    val oneLabel = when (protocol) {
        CellularProtocol.GSM, CellularProtocol.UMTS -> "RSSI"
        CellularProtocol.LTE -> "RSRP"
        CellularProtocol.NR -> "SS-RSRP"
        else -> stringResource(R.string.serving_signal_strength)
    }
    val twoLabel = when (protocol) {
        CellularProtocol.UMTS -> "RSCP"
        CellularProtocol.LTE -> "RSRQ"
        CellularProtocol.NR -> "SS-RSRQ"
        else -> null
    }
    val threeLabel = when (protocol) {
        CellularProtocol.LTE -> sinrLabel ?: "SINR"
        CellularProtocol.NR -> "SS-SINR"
        else -> null
    }
    val two = signalTwo?.takeUnless { protocol == CellularProtocol.UMTS && (it.toInt() == -120 || it.toInt() == -24) }
    val twoUnit = if (protocol == CellularProtocol.UMTS) "dBm" else "dB"
    val metrics = listOfNotNull(
        SignalMetric(oneLabel, signalOne, protocol.getMinSignalOne(), protocol.getMaxNormalizedSignalOne(), "dBm"),
        twoLabel?.let { SignalMetric(it, two, protocol.getMinSignalTwo(), protocol.getMaxNormalizedSignalTwo(), twoUnit) },
        threeLabel?.let {
            SignalMetric(it, signalThree, protocol.getMinSignalThree(), protocol.getMaxNormalizedSignalThree(), "dB")
        },
    )
    val barColors = rememberSignalBarColors(protocol)
    val style = LocalInterfaceStyle.current

    SignalSectionCard {
        Text(
            text = stringResource(R.string.serving_signal_strength),
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp),
            modifier = Modifier.semantics { heading() },
        )
        if (!sinrNote.isNullOrBlank()) {
            Text(sinrNote, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = style == InterfaceStyle.CLASSIC && metrics.size > 1 &&
                maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.15f
            if (columns) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    metrics.forEachIndexed { index, metric ->
                        SignalMetricReading(
                            label = metric.label,
                            reading = metric.readingText,
                            unit = metric.unit,
                            available = metric.value != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            val palette = barColors.getOrNull(index)
                            if (palette != null) ColoredSignalBar(metric.value, palette, Modifier.fillMaxWidth())
                            else MetricProgress(metric.value?.toInt(), metric.minValue, metric.maxNormalizedValue,
                                Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    metrics.forEachIndexed { index, metric ->
                        SignalMetricReading(
                            label = metric.label,
                            reading = metric.readingText,
                            unit = metric.unit,
                            available = metric.value != null,
                            modifier = Modifier.fillMaxWidth(),
                            horizontal = true,
                        ) {
                            val palette = barColors.getOrNull(index)
                            if (palette != null) ColoredSignalBar(metric.value, palette, Modifier.fillMaxWidth())
                            else MetricProgress(metric.value?.toInt(), metric.minValue, metric.maxNormalizedValue,
                                Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SignalMetricReading(
    label: String,
    reading: String,
    unit: String,
    available: Boolean,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    bar: @Composable () -> Unit,
) {
    val style = LocalInterfaceStyle.current
    val colors = MaterialTheme.colorScheme
    val compact = style == InterfaceStyle.COMPACT
    val readingColor = if (available) colors.onSurface else colors.onSurfaceVariant
    val readingWithUnit = if (available) "$reading $unit" else reading
    if (!horizontal) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = colors.onSurfaceVariant)
            Text(
                readingWithUnit,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontFeatureSettings = "tnum",
                    letterSpacing = 0.sp,
                ),
                color = readingColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp, stepSize = 1.sp),
            )
            bar()
        }
    } else {
        Column(
            modifier.padding(vertical = style.parameterRowPadding),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f),
                    style = (if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
                        .copy(letterSpacing = 0.sp),
                    color = colors.onSurfaceVariant)
                Text(
                    readingWithUnit,
                    Modifier.weight(1.3f),
                    style = (if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
                        .copy(
                            fontSize = if (compact) 12.sp else 14.sp,
                            lineHeight = if (compact) 16.sp else 20.sp,
                            fontFeatureSettings = "tnum",
                            letterSpacing = 0.sp,
                        ),
                    fontFamily = if (compact) FontFamily.Monospace else null,
                    fontWeight = FontWeight.SemiBold,
                    color = readingColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            bar()
        }
    }
}

@Composable
private fun SignalBarRow(
    label: String,
    value: Number?,
    minValue: Int,
    maxNormalizedValue: Int,
    palette: SignalBarColors? = null,
    valueText: (Number) -> String,
) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp)
    val bar: @Composable (Modifier) -> Unit = { modifier ->
        if (palette != null) ColoredSignalBar(value, palette, modifier)
        else MetricProgress(value?.toInt(), minValue, maxNormalizedValue, modifier)
    }
    val reading = value?.let(valueText) ?: SIGNAL_UNAVAILABLE

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 270.dp || LocalDensity.current.fontScale > 1.25f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = textStyle, color = colors.onSurfaceVariant)
                    Text(reading, style = textStyle, color = colors.onSurface)
                }
                bar(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.width(74.dp), style = textStyle, color = colors.onSurfaceVariant)
                bar(Modifier.weight(1f))
                Text(
                    text = reading,
                    modifier = Modifier.width(74.dp),
                    style = textStyle,
                    color = colors.onSurface,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun MetricProgress(value: Int?, minValue: Int, maxNormalizedValue: Int, modifier: Modifier) {
    if (value == null || maxNormalizedValue <= 0) {
        Surface(modifier.height(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
    } else {
        SignalProgressBar(value, minValue, maxNormalizedValue, modifier)
    }
}

@Composable
private fun SignalProgressBar(value: Int, minValue: Int, maxNormalizedValue: Int, modifier: Modifier) {
    val normalizedValue = (value.toLong() - minValue).coerceIn(0L, maxNormalizedValue.toLong()).toInt()
    val progress = (normalizedValue.toFloat() / maxNormalizedValue).coerceAtLeast(0.05f)
    val foreground = Color(ColorUtils.getSignalColorForValue(normalizedValue, maxNormalizedValue))
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val barModifier = modifier.height(6.dp)

    if (ThemePref.isMiuixProgressBar(LocalContext.current)) {
        MiuixLinearProgressIndicator(
            modifier = barModifier,
            progress = progress,
            colors = MiuixProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = foreground,
                backgroundColor = track,
            ),
        )
    } else {
        M3LinearProgressIndicator(
            progress = { progress },
            modifier = barModifier,
            color = foreground,
            trackColor = track,
        )
    }
}

@Composable
private fun cellIdentityFields(
    protocol: CellularProtocol,
    registeredPlmn: String,
    cellPlmns: String,
    areaCode: String,
    cellId: Long?,
    channelNumber: String,
    frequency: String,
    band: String,
    lteBand: String,
    pci: String,
    bandwidth: String,
    ta: String,
    cqi: String,
    matchedCell: EngineeringParam?,
    uplink: UplinkReference?,
): ServingParameterFields {
    val areaLabel = if (protocol == CellularProtocol.UMTS || protocol == CellularProtocol.GSM) "LAC" else "TAC"
    val channelLabel = when (protocol) {
        CellularProtocol.GSM -> "ARFCN"
        CellularProtocol.UMTS -> "UARFCN"
        CellularProtocol.LTE, CellularProtocol.NR -> stringResource(R.string.cell_downlink_channel)
        else -> "频点"
    }
    val pciLabel = when (protocol) {
        CellularProtocol.GSM -> "BSIC"
        CellularProtocol.UMTS -> "PSC"
        else -> "PCI"
    }

    val rawCi = cellId?.takeIf { it > 0 }
    val cellIdLabel = when (protocol) {
        CellularProtocol.NR -> "NCI"
        CellularProtocol.LTE -> "ECI"
        else -> "CID"
    }
    val site = matchedCell?.takeIf {
        it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat != 0.0 || it.lon != 0.0)
    }
    val hasLteOrNr = protocol == CellularProtocol.LTE || protocol == CellularProtocol.NR
    fun field(label: String, value: String?) = DiagField(label, value.signalDisplayValue())
    val rows = buildList {
        add(field(pciLabel, pci.substringBefore(' ')))
        if (hasLteOrNr) add(field(stringResource(R.string.diag_band), if (protocol == CellularProtocol.NR) band else lteBand))
        addAll(servingCellIdFields(protocol, rawCi))
        add(field(channelLabel, channelNumber))
        if (hasLteOrNr) {
            add(field(stringResource(R.string.diag_registered_plmn), registeredPlmn))
            add(field(stringResource(R.string.diag_cell_plmns), cellPlmns))
        } else add(field("PLMN", cellPlmns))
        add(field(areaLabel, areaCode))
        add(field(cellIdLabel, rawCi?.toString()))
        if (hasLteOrNr) {
            val dlFrequencyLabel = if (protocol == CellularProtocol.NR) R.string.cell_downlink_reference_frequency
                else R.string.cell_downlink_frequency
            add(field(stringResource(dlFrequencyLabel), frequency))
            add(field(stringResource(R.string.diag_bandwidth_label), bandwidth.takeIf(String::isNotBlank)?.let { "$it MHz" }))
        }
    }
    val detailRows = when (protocol) {
        CellularProtocol.LTE -> listOf("TA" to ta, "CQI" to cqi)
        CellularProtocol.NR, CellularProtocol.GSM -> listOf("TA" to ta)
        else -> emptyList()
    } + if (hasLteOrNr) listOf(
        stringResource(R.string.diag_duplex_mode) to uplink?.duplex(),
        stringResource(R.string.cell_uplink_reference_channel) to uplink?.arfcn()?.takeIf { it >= 0 }?.toString(),
        stringResource(R.string.cell_uplink_reference_frequency) to uplink?.mhz()?.takeIf { it > 0 }?.let {
            val format = if (protocol == CellularProtocol.NR) "%.3f MHz" else "%.1f MHz"
            String.format(Locale.ROOT, format, it)
        },
        stringResource(R.string.cell_site_longitude) to site?.let { String.format(Locale.ROOT, "%.5f", it.lon) },
        stringResource(R.string.cell_site_latitude) to site?.let { String.format(Locale.ROOT, "%.5f", it.lat) },
    ) else emptyList()
    return ServingParameterFields(rows, detailRows.map { (label, value) -> field(label, value) })
}

@Composable
internal fun CellField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** NR 辅卡：NR 数据腿指标 + 空闲徽标 + 最近出现。 */
@Composable
private fun NrSecondaryCard(
    state: NrSecondaryCellViewState,
    lastSeenText: String,
    onInfoClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DashboardCardDefaults.margin, vertical = 8.dp),
        cornerRadius = LocalInterfaceStyle.current.cardRadius,
        colors = CardDefaults.defaultColors(color = DashboardCardDefaults.cardContainerColor()),
    ) {
        DashboardCardHeader(
            icon = R.drawable.ic_cell_tower,
            title = "NR 辅载波",
            onHelpClick = onInfoClick
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.idle) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusTag(
                        label = "空闲",
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lastSeenText.isNotBlank()) {
                        Text(
                            text = lastSeenText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val fields = listOfNotNull(
                "频段" to (state.band().takeIf { it.isNotBlank() } ?: ""),
                "频率" to (state.frequency().takeIf { it.isNotBlank() } ?: ""),
                "PCI" to (state.pci().takeIf { it.isNotBlank() } ?: ""),
                "NARFCN" to (state.narfcn().takeIf { it.isNotBlank() } ?: ""),
            )
            if (fields.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    fields.forEachIndexed { idx, (l, v) ->
                        CellField(l, v, modifier = Modifier.weight(1f))
                        if (idx < fields.lastIndex) Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }
            state.bandwidth().takeIf(String::isNotBlank)?.let {
                CellField(stringResource(R.string.diag_bandwidth_label), "$it MHz")
            }

            val colors = rememberSignalBarColors(CellularProtocol.NR)
            val readings = listOf(state.ssRsrp(), state.ssRsrq(), state.ssSinr())
            SignalBarMetric.forProtocol(CellularProtocol.NR).forEachIndexed { index, metric ->
                SignalBarRow(metric.title.removePrefix("NR "), readings[index], 0, 0, colors[index]) { "$it ${metric.unit}" }
            }
        }
    }
}

@Composable
internal fun NeighborSectionCard(title: String, neighbors: List<String>) {
    val style = LocalInterfaceStyle.current
    SignalSectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp),
            modifier = Modifier.semantics { heading() })
        Column(Modifier.fillMaxWidth()) {
            neighbors.forEachIndexed { index, line ->
                if (index > 0) {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp),
                    fontFamily = if (style == InterfaceStyle.COMPACT) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = style.parameterRowPadding),
                )
            }
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DashboardCardDefaults.margin, vertical = 8.dp),
        cornerRadius = LocalInterfaceStyle.current.cardRadius,
        colors = CardDefaults.defaultColors(color = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
