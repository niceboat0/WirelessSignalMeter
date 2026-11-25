package com.craxiom.networksurvey.ui.cellular

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.model.CellularProtocol
import com.craxiom.networksurvey.model.NrCarrier
import com.craxiom.networksurvey.ui.theme.InterfaceStyle
import com.craxiom.networksurvey.ui.theme.LocalInterfaceStyle
import java.util.Locale

@Composable
internal fun NrCarrierSection(carriers: List<NrCarrier>, fromDiag: Boolean) {
    if (carriers.size < 2) return
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val palettes = rememberSignalBarColors(CellularProtocol.NR)
    val style = LocalInterfaceStyle.current
    SignalSectionCard {
        val currentCount = carriers.count { !it.lastObservation() }
        val hasHeld = currentCount != carriers.size
        val total = if (hasHeld) 0 else NrCarrier.totalBandwidthMhz(carriers)
        val title = if (hasHeld) stringResource(R.string.nr_carriers_heading)
            else stringResource(R.string.nr_carriers_title, currentCount)
        val statusLabel = when {
            fromDiag && hasHeld -> R.string.nr_carriers_diag_waiting
            fromDiag -> null
            hasHeld -> R.string.nr_carriers_android_waiting
            else -> R.string.nr_carriers_android
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (total > 0) "$title · $total MHz" else title, Modifier.semantics { heading() },
                style = typography.titleSmall.copy(letterSpacing = 0.sp))
            if (statusLabel != null) {
                Text(stringResource(statusLabel), color = colors.onSurfaceVariant,
                    style = typography.labelMedium.copy(letterSpacing = 0.sp))
            }
        }
        for (carrier in carriers) key(carrier.identity()) {
            HorizontalDivider(color = colors.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val role = when (carrier.role()) {
                    NrCarrier.Role.PRIMARY -> stringResource(R.string.nr_carrier_primary)
                    NrCarrier.Role.SECONDARY -> if (carrier.index() >= 0) "SCC${carrier.index()}" else "SCC"
                    NrCarrier.Role.SERVING -> stringResource(R.string.nr_carrier_serving)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(role, style = typography.labelLarge.copy(letterSpacing = 0.sp), fontWeight = FontWeight.SemiBold)
                    if (carrier.band().isNotBlank()) Text(carrier.band(), style = typography.labelLarge.copy(letterSpacing = 0.sp))
                    if (carrier.bandwidthMhz() > 0) Text("${carrier.bandwidthMhz()} MHz",
                        style = typography.labelLarge.copy(letterSpacing = 0.sp))
                    if (carrier.lastObservation()) {
                        Text(stringResource(R.string.nr_carrier_last_observation), color = colors.onSurfaceVariant,
                            style = typography.labelMedium.copy(letterSpacing = 0.sp))
                    }
                }
                val id = carrier.identity()
                val channel = if (fromDiag) "SSB ARFCN" else "NR-ARFCN"
                Text("PCI ${id.pci()} · $channel ${id.arfcn()}", color = colors.onSurfaceVariant,
                    style = typography.bodySmall.copy(letterSpacing = 0.sp))
                if (fromDiag && !carrier.lastObservation() &&
                    (carrier.downlinkState() != NrCarrier.CaState.UNKNOWN ||
                        carrier.uplinkState() != NrCarrier.CaState.UNKNOWN)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        NrCaState(R.string.nr_carrier_dl_state, carrier.downlinkState())
                        NrCaState(R.string.nr_carrier_ul_state, carrier.uplinkState())
                    }
                }
                val signal = carrier.signal()
                if (!carrier.lastObservation()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = style == InterfaceStyle.CLASSIC && maxWidth >= 280.dp &&
                            LocalDensity.current.fontScale <= 1.15f
                        if (columns) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NrCarrierMetric(Modifier.weight(1f), "SS-RSRP", signal.rsrp(), "dBm", palettes[0])
                                NrCarrierMetric(Modifier.weight(1f), "SS-RSRQ", signal.rsrq(), "dB", palettes[1])
                                NrCarrierMetric(Modifier.weight(1f), "SS-SINR", signal.sinr(), "dB", palettes[2])
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                NrCarrierMetric(Modifier.fillMaxWidth(), "SS-RSRP", signal.rsrp(), "dBm", palettes[0], true)
                                NrCarrierMetric(Modifier.fillMaxWidth(), "SS-RSRQ", signal.rsrq(), "dB", palettes[1], true)
                                NrCarrierMetric(Modifier.fillMaxWidth(), "SS-SINR", signal.sinr(), "dB", palettes[2], true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NrCaState(format: Int, state: NrCarrier.CaState) {
    val label = when (state) {
        NrCarrier.CaState.ACTIVATED -> R.string.nr_carrier_activated
        NrCarrier.CaState.CONFIGURED -> R.string.nr_carrier_configured
        NrCarrier.CaState.DECONFIGURED -> R.string.nr_carrier_deconfigured
        NrCarrier.CaState.UNKNOWN -> return
    }
    val colors = MaterialTheme.colorScheme
    Text(stringResource(format, stringResource(label)),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
        color = if (state == NrCarrier.CaState.ACTIVATED) colors.primary else colors.onSurfaceVariant)
}

@Composable
private fun NrCarrierMetric(
    modifier: Modifier,
    label: String,
    value: Double?,
    unit: String,
    palette: SignalBarColors,
    horizontal: Boolean = false,
) {
    val reading = when {
        value == null -> "-"
        value == value.toInt().toDouble() -> value.toInt().toString()
        else -> String.format(Locale.ROOT, "%.1f", value)
    }
    SignalMetricReading(label, reading, unit, value != null, modifier, horizontal) {
        ColoredSignalBar(value, palette, Modifier.fillMaxWidth())
    }
}
