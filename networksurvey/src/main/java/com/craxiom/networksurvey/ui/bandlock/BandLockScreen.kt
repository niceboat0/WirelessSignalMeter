package com.craxiom.networksurvey.ui.bandlock

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.common.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.ui.rootdiag.DiagRecord
import com.craxiom.networksurvey.ui.rootdiag.RootDiagStatus
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BandLockScreen(rootDiag: RootDiagViewModel, onBack: () -> Unit, model: BandLockViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val qmi by model.qmi.collectAsStateWithLifecycle()
    val signal by rootDiag.signal.collectAsStateWithLifecycle()
    LaunchedEffect(state.profile) { rootDiag.updateBandLockProfile(state.profile) }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    var radio by rememberSaveable { mutableStateOf(LockRadio.Lte) }
    var target by rememberSaveable { mutableStateOf(LockTarget.Bands) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val lockScroll = rememberScrollState()
    val advancedScroll = rememberScrollState()
    var networkMode by rememberSaveable { mutableStateOf(state.appliedMode ?: LockNetworkMode.Auto) }
    LaunchedEffect(state.appliedMode) { state.appliedMode?.let { networkMode = it } }
    var lteBands by rememberSaveable { mutableStateOf("") }
    var nrBands by rememberSaveable { mutableStateOf("") }
    var lteChannel by rememberSaveable { mutableStateOf("") }
    var nrChannel by rememberSaveable { mutableStateOf("") }
    var ltePci by rememberSaveable { mutableStateOf("") }
    var nrPci by rememberSaveable { mutableStateOf("") }
    var nrBand by rememberSaveable { mutableStateOf("") }
    var scs by rememberSaveable { mutableStateOf(30) }
    var pending by remember { mutableStateOf<BandLockCommand?>(null) }
    var confirmation by remember { mutableStateOf("") }
    var consoleText by rememberSaveable { mutableStateOf("") }
    val consoleValid = runCatching { BandLockCommand.console(consoleText) }.getOrNull()
    /* Hysteresis for the serving line: keep rendering the last valid record up
     * to 60 s so sparse DIAG serving heartbeats do not flash the row empty
     * every second-tick when the 15 s freshness window expires in between. */
    var heldServing by remember { mutableStateOf<DiagRecord?>(null) }
    val enabled = !state.busy && (
        state.availability == LockAvailability.Ready ||
            state.availability == LockAvailability.Cached && state.profile.canLock
        )
    val bands = if (radio == LockRadio.Lte) lteBands else if (target == LockTarget.Cell) nrBand else nrBands
    val channel = if (radio == LockRadio.Lte) lteChannel else nrChannel
    val pci = if (radio == LockRadio.Lte) ltePci else nrPci
    val command = runCatching { BandLockCommand.apply(radio, target, bands, channel, pci, scs) }.getOrNull()
    val targetLabel = stringResource(when (target) {
        LockTarget.Bands -> R.string.lock_bands
        LockTarget.Frequency -> R.string.lock_frequency
        LockTarget.Cell -> R.string.lock_cell
    })
    val radioLabel = if (radio == LockRadio.Lte) "LTE" else "NR"
    val freshServing = signal.records.filter {
        it.type == "serving" && it.data.optString("rat") == radioLabel &&
            now - it.receivedAt in 0..15_000 && it.data.optLong("seen_age_ms", 20_001) <= 15_000
    }.maxByOrNull { it.receivedAt }?.takeIf {
        signal.status == RootDiagStatus.Running && it.data.optInt("pci", -1) in 0..radio.maxPci &&
            it.data.optInt("arfcn", -1) in 0..radio.maxChannel
    }
    if (freshServing != null) heldServing = freshServing
    val serving = freshServing?.data ?: heldServing?.takeIf {
        signal.status == RootDiagStatus.Running && it.data.optString("rat") == radioLabel &&
            now - it.receivedAt <= 60_000 &&
            it.data.optInt("pci", -1) in 0..radio.maxPci &&
            it.data.optInt("arfcn", -1) in 0..radio.maxChannel
    }?.data
    val summary = when (target) {
        LockTarget.Bands -> "$radioLabel · $bands"
        LockTarget.Frequency -> "$radioLabel · ARFCN $channel" + if (radio == LockRadio.Nr) " · $scs kHz" else ""
        LockTarget.Cell -> "$radioLabel · ARFCN $channel · PCI $pci" +
            if (radio == LockRadio.Nr) " · n$nrBand · $scs kHz" else ""
    }
    val operating = state.busy && state.availability != LockAvailability.Checking
    BackHandler(operating) { /* Keep the active operation attached until the helper exits. */ }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TitleBar(stringResource(R.string.tool_band_lock_title), compact = true,
                    onBackClick = onBack.takeUnless { operating })
                TabRowWithContour(
                    tabs = listOf(stringResource(R.string.lock_tab_controls), stringResource(R.string.lock_tab_advanced)),
                    selectedTabIndex = if (advanced) 1 else 0,
                    onTabSelected = { if (!state.busy) advanced = it == 1 },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(if (advanced) advancedScroll else lockScroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (advanced) Text(model.device, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(when {
                        state.availability == LockAvailability.Checking -> R.string.lock_checking
                        state.busy -> R.string.lock_working
                        state.availability == LockAvailability.Unchecked -> R.string.lock_unchecked
                        state.availability == LockAvailability.Cached -> R.string.lock_cached_unconfirmed
                        state.availability == LockAvailability.Ready && state.profile.level == LockSupportLevel.Verified -> R.string.lock_ready
                        state.availability == LockAvailability.Ready -> R.string.lock_probe_only
                        else -> R.string.lock_unavailable
                    }), style = MaterialTheme.typography.bodySmall,
                        color = if (state.availability == LockAvailability.Unavailable) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.profile.reason.isNotBlank() &&
                        state.availability in setOf(LockAvailability.Ready, LockAvailability.Unavailable)) {
                        Text(stringResource(when (state.profile.reason) {
                            "mediatek_unsupported" -> R.string.lock_reason_mediatek
                            "exynos_unsupported" -> R.string.lock_reason_exynos
                            "kirin_unsupported" -> R.string.lock_reason_kirin
                            "unverified_model" -> R.string.lock_reason_unverified
                            "no_diag_channel" -> R.string.lock_reason_no_channel
                            else -> R.string.lock_reason_unknown
                        }), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LockIconButton(R.string.lock_refresh, Icons.Rounded.Refresh, !state.busy) {
                    model.refresh(signal.status == RootDiagStatus.Running)
                }
            }
            if (advanced && state.availability == LockAvailability.Ready &&
                (state.channel.isNotEmpty() || state.caps.isNotEmpty())) {
                Text(
                    stringResource(R.string.lock_channel_caps,
                        state.channel.ifEmpty { "-" }, capsLine(state.caps)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (advanced) Text(stringResource(R.string.lock_scope), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.result?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            if (!advanced) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TabRowWithContour(
                    tabs = listOf("4G · LTE", "5G · NR"),
                    selectedTabIndex = radio.ordinal,
                    onTabSelected = { if (!state.busy) radio = LockRadio.entries[it] },
                    modifier = Modifier.fillMaxWidth(),
                )
                TabRowWithContour(
                    tabs = listOf(R.string.lock_bands, R.string.lock_frequency, R.string.lock_cell)
                        .map { stringResource(it) },
                    selectedTabIndex = target.ordinal,
                    onTabSelected = { if (!state.busy) target = LockTarget.entries[it] },
                    modifier = Modifier.fillMaxWidth(),
                )
            if (target != LockTarget.Bands) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.lock_current_cell), style = MaterialTheme.typography.labelLarge)
                    Text(serving?.let {
                        listOfNotNull(
                            radioLabel,
                            it.optInt("band", 0).takeIf { band -> band > 0 }?.let { band ->
                                "${if (radio == LockRadio.Lte) "B" else "n"}$band"
                            },
                            "ARFCN ${it.optInt("arfcn")}", "PCI ${it.optInt("pci")}",
                        ).joinToString(" · ")
                    } ?: stringResource(R.string.lock_current_unavailable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LockIconButton(R.string.lock_use_current_cell, Icons.Rounded.MyLocation,
                    !state.busy && serving != null) {
                    serving?.let {
                        if (radio == LockRadio.Lte) {
                            lteChannel = it.optInt("arfcn").toString()
                            ltePci = it.optInt("pci").toString()
                        } else {
                            nrChannel = it.optInt("arfcn").toString()
                            nrPci = it.optInt("pci").toString()
                            nrBand = it.optInt("band", 0).takeIf { band -> band > 0 }?.toString().orEmpty()
                        }
                    }
                }
            }
            if (target == LockTarget.Bands) {
                val presets = if (radio == LockRadio.Lte) listOf(1, 3, 5, 8, 28, 41) else listOf(1, 3, 28, 41, 77, 78, 79)
                val selected = runCatching {
                    BandLockCommand.apply(radio, LockTarget.Bands, bands, "", "", scs).arguments[1].split(",")
                }.getOrDefault(emptyList())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { band ->
                        val text = band.toString()
                        FilterChip(text in selected, {
                            val next = selected.toMutableSet().apply { if (!add(text)) remove(text) }
                                .sortedBy(String::toInt).joinToString(",")
                            if (radio == LockRadio.Lte) lteBands = next else nrBands = next
                        }, enabled = !state.busy, label = { Text("${if (radio == LockRadio.Lte) "B" else "n"}$band") })
                    }
                }
                OutlinedTextField(
                    bands, { if (radio == LockRadio.Lte) lteBands = it else nrBands = it },
                    Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                    isError = bands.isNotBlank() && command == null,
                    label = { Text(stringResource(R.string.lock_band_list)) },
                    placeholder = { Text(if (radio == LockRadio.Lte) "1,3,5,8,28,41" else "1,28,41,78,79") },
                    supportingText = { Text(stringResource(R.string.lock_band_range, radio.maxBand)) },
                )
            } else {
                if (radio == LockRadio.Nr && target == LockTarget.Cell) {
                    LockNumberField(nrBand, { nrBand = it }, stringResource(R.string.lock_nr_band), !state.busy, 1, 512)
                }
                LockNumberField(channel, { if (radio == LockRadio.Lte) lteChannel = it else nrChannel = it },
                    if (radio == LockRadio.Lte) "EARFCN" else "NR-ARFCN", !state.busy,
                    if (radio == LockRadio.Lte) 0 else 1, radio.maxChannel)
                if (target == LockTarget.Cell) LockNumberField(pci,
                    { if (radio == LockRadio.Lte) ltePci = it else nrPci = it }, "PCI", !state.busy, 0, radio.maxPci)
                if (radio == LockRadio.Nr) {
                    Text("SCS", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120).forEach { value ->
                            FilterChip(scs == value, { scs = value }, enabled = !state.busy, label = { Text("$value kHz") })
                        }
                    }
                }
            }
            val applyLabel = stringResource(R.string.lock_apply)
            Button(
                onClick = { pending = command; confirmation = "$applyLabel · $targetLabel\n$summary" },
                enabled = enabled && command != null, modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(applyLabel)
            }
            val hasBackup = if (radio == LockRadio.Lte) state.lteBackup else state.nrBackup
            val backupHint = if (radio == LockRadio.Lte) state.lteBackupHint else state.nrBackupHint
            val restoreNeedsBackup = target == LockTarget.Bands
            val backupCanBeChecked = hasBackup ||
                (state.availability == LockAvailability.Cached && backupHint)
            val restoreLabel = stringResource(if (target == LockTarget.Bands) R.string.lock_restore_bands else R.string.lock_clear)
            OutlinedButton(
                onClick = { pending = BandLockCommand.restore(radio, target); confirmation = "$restoreLabel · $radioLabel · $targetLabel" },
                enabled = enabled && (!restoreNeedsBackup || backupCanBeChecked),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(restoreLabel)
            }
            if (target == LockTarget.Bands) Text(
                stringResource(when (BandLockVerification.backupCaption(
                    state.availability, radio, hasBackup, backupHint,
                )) {
                    BackupCaption.CachedHint -> R.string.lock_backup_cached
                    BackupCaption.NeedsRecheck -> R.string.lock_cached_unconfirmed
                    BackupCaption.Confirmed -> R.string.lock_backup_available
                    BackupCaption.Needed -> R.string.lock_backup_needed
                }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            /* Instant lock (QMI NAS): an independent backend from the NV/EFS
             * route above. Takes effect without a restart; persistence after
             * SSR is unverified, so it is a separate section. */
            HorizontalDivider()
            Text(stringResource(R.string.lock_qmi_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.lock_qmi_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when (qmi.availability) {
                    LockAvailability.Ready -> R.string.lock_qmi_available
                    LockAvailability.Unavailable -> R.string.lock_qmi_unavailable
                    else -> R.string.lock_qmi_unknown
                }), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = if (qmi.availability == LockAvailability.Unavailable) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                LockIconButton(R.string.lock_qmi_check, Icons.Rounded.Refresh, !qmi.busy) { model.qmiCheck() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.qmiApplyBands(radio, bands) },
                    Modifier.weight(1f), enabled = !qmi.busy && bands.isNotBlank()) {
                    Text(stringResource(R.string.lock_qmi_apply_bands))
                }
                OutlinedButton({ model.qmiRestoreBands() }, Modifier.weight(1f), enabled = !qmi.busy) {
                    Text(stringResource(R.string.lock_qmi_restore_bands))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.qmiLockCell(radio, channel, pci, scs) },
                    Modifier.weight(1f), enabled = !qmi.busy && channel.isNotBlank() && pci.isNotBlank()) {
                    Text(stringResource(R.string.lock_qmi_lock_cell))
                }
                OutlinedButton({ model.qmiClearCell(radio) }, Modifier.weight(1f), enabled = !qmi.busy) {
                    Text(stringResource(R.string.lock_qmi_clear_cell))
                }
            }
            qmi.result?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (qmi.log.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lock_qmi_log), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LockIconButton(R.string.lock_clear_log, Icons.Rounded.DeleteOutline, !qmi.busy, model::qmiClearLog)
                }
                SelectionContainer {
                    Text(qmi.log.takeLast(1200), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.lock_network_mode), style = MaterialTheme.typography.titleSmall)
                TabRowWithContour(
                    tabs = listOf(stringResource(R.string.lock_mode_auto), "LTE", "NR"),
                    selectedTabIndex = networkMode.ordinal,
                    onTabSelected = { if (!state.busy) networkMode = LockNetworkMode.entries[it] },
                    modifier = Modifier.fillMaxWidth(),
                )
            val modeLabel = stringResource(R.string.lock_apply_mode)
            val modeValue = if (networkMode == LockNetworkMode.Auto) stringResource(R.string.lock_mode_auto) else networkMode.argument.uppercase()
            OutlinedButton(
                { pending = BandLockCommand.mode(networkMode); confirmation = "$modeLabel · $modeValue" },
                Modifier.fillMaxWidth(), enabled = enabled,
            ) { Text(modeLabel) }
            HorizontalDivider()
            Text(stringResource(R.string.lock_console_title), style = MaterialTheme.typography.titleSmall)
            val consoleRunLabel = stringResource(R.string.lock_console_run)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    consoleText, { consoleText = it }, Modifier.weight(1f), enabled = !state.busy,
                    singleLine = true, isError = consoleText.isNotBlank() && consoleValid == null,
                    label = { Text(stringResource(R.string.lock_console_placeholder)) },
                    supportingText = { Text(stringResource(R.string.lock_console_hint)) },
                )
                LockIconButton(R.string.lock_console_run, Icons.Rounded.Terminal,
                    enabled && consoleValid != null) {
                    consoleValid?.let {
                        pending = it
                        confirmation = "$consoleRunLabel · ${consoleText.trim()}"
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lock_execution_log), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                LockIconButton(R.string.lock_clear_log, Icons.Rounded.DeleteOutline, !state.busy, model::clearLog)
            }
            SelectionContainer {
                Text(state.log.ifEmpty { stringResource(R.string.lock_log_empty) },
                    Modifier.fillMaxWidth().padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
            }
            }
        }
    }
    pending?.let { action ->
        AppDialog(
            show = true,
            onDismissRequest = { pending = null },
            title = stringResource(R.string.lock_confirm),
            confirmText = stringResource(R.string.lock_apply),
            dismissText = stringResource(android.R.string.cancel),
            confirmEnabled = !state.busy,
            onConfirm = {
                pending = null
                model.run(
                    action,
                    collectorRunning = signal.status == RootDiagStatus.Running,
                    onModemRestartRequested = rootDiag::recoverAfterModemRestart,
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(confirmation)
                Text(stringResource(when {
                    action.modeAfterLock != null && action.restartsModem -> R.string.lock_confirm_apply_restart
                    action.modeAfterLock != null -> R.string.lock_confirm_apply_mode
                    action.restartsModem -> R.string.lock_confirm_restart
                    else -> R.string.lock_confirm_mode
                }))
                if (state.profile.level == LockSupportLevel.ProbeOnly) {
                    Text(stringResource(R.string.lock_confirm_unverified))
                }
            }
        }
    }
}

@Composable
private fun LockNumberField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, min: Int, max: Int) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        isError = value.isNotEmpty() && (!value.matches(Regex("[0-9]+")) || value.toIntOrNull()?.let { it in min..max } != true),
        label = { Text(label) }, supportingText = { Text("$min–$max") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

/* Short capability names for the probe card line. */
private fun shortCapName(key: String) = when (key) {
    "nv" -> "NV"
    "lte_bandpref" -> "LTE"
    "nr_band_pref" -> "NR"
    "nr_nsa_band_pref" -> "NSA"
    "cell_lock_list" -> "CELL"
    "cell_restrict" -> "LTE-FRQ"
    "nr_earfcn_lock" -> "NR-FRQ"
    "nr_pci_lock_info" -> "NR-PCI"
    else -> key
}

private fun capsLine(caps: List<Pair<String, Boolean>>): String =
    if (caps.isEmpty()) "" else caps.joinToString(" ") { (key, ok) ->
        "${shortCapName(key)}${if (ok) "\u2713" else "\u2717"}"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockIconButton(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, action: () -> Unit) {
    val text = stringResource(label)
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), { PlainTooltip { Text(text) } }, rememberTooltipState()) {
        IconButton(action, enabled = enabled) { Icon(icon, contentDescription = text) }
    }
}
