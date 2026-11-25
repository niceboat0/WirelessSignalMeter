package com.craxiom.networksurvey.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import com.craxiom.networksurvey.ui.rootdiag.capabilityLine
import com.craxiom.networksurvey.ui.rootdiag.imsStatusLine
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import com.craxiom.networksurvey.ui.rootdiag.RootDiagPrefs
import com.craxiom.networksurvey.services.RootDiagCollectorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.craxiom.networksurvey.ui.common.PreferenceDivider
import com.craxiom.networksurvey.ui.common.AppDialog
import com.craxiom.networksurvey.ui.common.PreferenceGroup
import com.craxiom.networksurvey.ui.common.PreferenceLink
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsScreen(
    onNavigateToAcknowledgments: () -> Unit = {},
    onNavigateToEngParams: () -> Unit = {},
    onNavigateToTheme: () -> Unit = {},
    bottomContentPadding: Dp = 24.dp,
    rootDiag: RootDiagViewModel? = null,
) {
    var showBarColors by remember { mutableStateOf(false) }
    if (showBarColors) SignalBarColorSettings { showBarColors = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item("general") {
                SettingsGroup(stringResource(R.string.settings_general_group)) {
                    SettingsLink(Icons.Rounded.Palette, "外观", "深色模式、动态取色与主题色", onNavigateToTheme)
                    SettingsDivider()
                    SettingsLink(Icons.Rounded.SignalCellularAlt, stringResource(R.string.signal_bar_colors),
                        stringResource(R.string.signal_bar_colors_subtitle)) { showBarColors = true }
                    SettingsDivider()
                    SettingsLink(Icons.Rounded.FileUpload, "基站工参", "导入和管理 CSV 工参", onNavigateToEngParams)
                }
            }
            if (rootDiag != null) item("diag") {
                SettingsGroup(stringResource(R.string.diag_settings_group)) {
                    DiagCollectionSettings(rootDiag)
                    SettingsDivider()
                    DeviceCapabilitySummary(rootDiag)
                    SettingsDivider()
                    DiagnosticLogExport(rootDiag)
                }
            }
            item("about") {
                SettingsGroup(stringResource(R.string.settings_about_group)) {
                    AboutSettings(onNavigateToAcknowledgments)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogExport(rootDiag: RootDiagViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var includeSamples by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            val message = try {
                rootDiag.exportDiagnosticLog(uri, includeSamples)
                R.string.diag_export_saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                R.string.diag_export_failed
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    SettingsLink(Icons.Rounded.FileDownload, stringResource(R.string.diag_export_title),
        stringResource(R.string.diag_export_subtitle)) {
        includeSamples = false
        showDialog = true
    }
    AppDialog(
        show = showDialog,
        onDismissRequest = { showDialog = false },
        title = stringResource(R.string.diag_export_title),
        confirmText = stringResource(R.string.diag_export_save),
        dismissText = stringResource(android.R.string.cancel),
        onConfirm = {
            showDialog = false
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            exporter.launch("wireless-diag-$stamp.jsonl")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diag_export_explanation), letterSpacing = 0.sp)
            Text(stringResource(R.string.diag_export_qmdl_note), letterSpacing = 0.sp)
            Text(stringResource(R.string.diag_qmdl_privacy), letterSpacing = 0.sp)
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = includeSamples, role = Role.Checkbox, onValueChange = { includeSamples = it },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(state = ToggleableState(includeSamples), onClick = null)
                Text(stringResource(R.string.diag_export_include_mr), Modifier.padding(start = 10.dp), letterSpacing = 0.sp)
            }
        }
    }
}


@Composable
private fun DeviceCapabilitySummary(rootDiag: RootDiagViewModel) {
    val capability by rootDiag.capability.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val device = listOf(capability.model, capability.soc).filter { it.isNotBlank() }.joinToString(" · ")
    SettingsLink(
        Icons.Rounded.PhoneAndroid, stringResource(R.string.diag_capability_title), device,
    ) { showDetails = true }
    AppDialog(
        show = showDetails,
        onDismissRequest = { showDetails = false },
        title = stringResource(R.string.diag_capability_title),
        confirmText = stringResource(android.R.string.ok),
        onConfirm = { showDetails = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                listOf(capability.manufacturer, capability.model, capability.soc, "API ${capability.sdk}")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(capabilityLine(capability.telephony), letterSpacing = 0.sp)
            Text(capabilityLine(capability.root), letterSpacing = 0.sp)
            Text(capabilityLine(capability.qualcommDiag), letterSpacing = 0.sp)
            Text(capabilityLine(capability.bandLock), letterSpacing = 0.sp)
            Text(imsStatusLine(capability.imsStatus), letterSpacing = 0.sp)
        }
    }
}

@Composable
private fun DiagCollectionSettings(rootDiag: RootDiagViewModel? = null) {
    val context = LocalContext.current
    var overlay by remember { mutableStateOf(RootDiagPrefs.overlayWanted(context) && Settings.canDrawOverlays(context)) }
    var showRf by remember { mutableStateOf(RootDiagPrefs.showRf(context)) }
    var showIms by remember { mutableStateOf(RootDiagPrefs.showIms(context)) }
    var showQueue by remember { mutableStateOf(RootDiagPrefs.showQueue(context)) }
    var showOverlayHelp by rememberSaveable { mutableStateOf(false) }
    SwitchPreference(
        title = stringResource(R.string.diag_overlay_title),
        summary = stringResource(R.string.diag_overlay_subtitle),
        startAction = {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary)
        },
        checked = overlay,
        onCheckedChange = { enabled ->
            if (enabled && !Settings.canDrawOverlays(context)) {
                RootDiagPrefs.setOverlayWanted(context, false)
                overlay = false
                showOverlayHelp = true
            } else {
                RootDiagPrefs.setOverlayWanted(context, enabled)
                overlay = enabled
                rootDiag?.onCollectionPreferenceChanged()
                RootDiagCollectorService.refreshOverlay(context)
            }
        },
    )
    if (overlay) {
        OverlayFieldToggle(stringResource(R.string.diag_overlay_show_rf), showRf) {
            RootDiagPrefs.store(context).edit().putBoolean(RootDiagPrefs.OVERLAY_SHOW_RF, it).apply()
            showRf = it
            RootDiagCollectorService.refreshOverlay(context)
        }
        OverlayFieldToggle(stringResource(R.string.diag_overlay_show_ims), showIms) {
            RootDiagPrefs.store(context).edit().putBoolean(RootDiagPrefs.OVERLAY_SHOW_IMS, it).apply()
            showIms = it
            RootDiagCollectorService.refreshOverlay(context)
        }
        OverlayFieldToggle(stringResource(R.string.diag_overlay_show_queue), showQueue) {
            RootDiagPrefs.store(context).edit().putBoolean(RootDiagPrefs.OVERLAY_SHOW_QUEUE, it).apply()
            showQueue = it
            RootDiagCollectorService.refreshOverlay(context)
        }
    }
    AppDialog(
        show = showOverlayHelp,
        onDismissRequest = { showOverlayHelp = false },
        title = stringResource(R.string.diag_overlay_title),
        dismissText = stringResource(android.R.string.cancel),
        confirmText = stringResource(R.string.diag_overlay_open_settings),
        onConfirm = {
            showOverlayHelp = false
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    ) {
        Text(stringResource(R.string.diag_overlay_need_permission), letterSpacing = 0.sp)
    }
}

@Composable
private fun OverlayFieldToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 68.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(state = ToggleableState(checked), onClick = null)
        Text(label, Modifier.padding(start = 10.dp), letterSpacing = 0.sp)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    PreferenceGroup(title, content)
}

@Composable
private fun SettingsDivider() {
    PreferenceDivider()
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    PreferenceLink(icon, title, subtitle, onClick = onClick)
}
