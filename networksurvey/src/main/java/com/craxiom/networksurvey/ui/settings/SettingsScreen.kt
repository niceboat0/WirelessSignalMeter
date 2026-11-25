package com.craxiom.networksurvey.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.BuildConfig
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import com.craxiom.networksurvey.ui.rootdiag.capabilityLine
import com.craxiom.networksurvey.ui.rootdiag.imsStatusLine
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.rounded.OpenInNew
import com.craxiom.networksurvey.ui.rootdiag.RootDiagPrefs
import com.craxiom.networksurvey.services.RootDiagCollectorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults

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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroup("常用") {
                    SettingsLink(Icons.Rounded.Palette, "外观", "深色模式、动态取色与主题色", onNavigateToTheme)
                    SettingsDivider()
                    SettingsLink(Icons.Rounded.SignalCellularAlt, stringResource(R.string.signal_bar_colors),
                        stringResource(R.string.signal_bar_colors_subtitle)) { showBarColors = true }
                    SettingsDivider()
                    SettingsLink(Icons.Rounded.FileUpload, "基站工参", "导入和管理 CSV 工参", onNavigateToEngParams)
                }
            }
            if (rootDiag != null) item {
                SettingsGroup(stringResource(R.string.diag_settings_group)) {
                    DiagCollectionSettings(rootDiag)
                    SettingsDivider()
                    DeviceCapabilitySummary(rootDiag)
                    SettingsDivider()
                    DiagnosticLogExport(rootDiag)
                }
            }
            item {
                SettingsGroup("关于") {
                    SettingsLink(
                        Icons.Rounded.Info, "开源致谢", "无线无忧 ${BuildConfig.VERSION_NAME}", onNavigateToAcknowledgments,
                    )
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
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.diag_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.diag_export_explanation))
                    Text(stringResource(R.string.diag_export_qmdl_note))
                    Text(stringResource(R.string.diag_qmdl_privacy))
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = includeSamples, role = Role.Checkbox, onValueChange = { includeSamples = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includeSamples, onCheckedChange = null)
                        Text(stringResource(R.string.diag_export_include_mr), Modifier.padding(start = 10.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                    exporter.launch("wireless-diag-$stamp.jsonl")
                }) { Text(stringResource(R.string.diag_export_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}


@Composable
private fun DeviceCapabilitySummary(rootDiag: RootDiagViewModel) {
    val capability by rootDiag.capability.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.diag_capability_title), style = MaterialTheme.typography.titleSmall)
        Text(
            listOf(capability.manufacturer, capability.model, capability.soc, "API ${capability.sdk}").filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(capabilityLine(capability.telephony), style = MaterialTheme.typography.bodySmall)
        Text(capabilityLine(capability.root), style = MaterialTheme.typography.bodySmall)
        Text(capabilityLine(capability.qualcommDiag), style = MaterialTheme.typography.bodySmall)
        Text(capabilityLine(capability.bandLock), style = MaterialTheme.typography.bodySmall)
        Text(imsStatusLine(capability.imsStatus), style = MaterialTheme.typography.bodySmall)
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
    SettingsRow(
        icon = Icons.Rounded.OpenInNew,
        title = stringResource(R.string.diag_overlay_title),
        subtitle = stringResource(R.string.diag_overlay_subtitle),
        modifier = Modifier.toggleable(
            value = overlay,
            role = Role.Switch,
            onValueChange = { enabled ->
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
        ),
        trailing = { Switch(checked = overlay, onCheckedChange = null) },
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
    if (showOverlayHelp) {
        AlertDialog(
            onDismissRequest = { showOverlayHelp = false },
            title = { Text(stringResource(R.string.diag_overlay_title)) },
            text = { Text(stringResource(R.string.diag_overlay_need_permission)) },
            confirmButton = {
                TextButton(onClick = { showOverlayHelp = false }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverlayHelp = false
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.diag_overlay_open_settings)) }
            },
        )
    }
}

@Composable
private fun OverlayFieldToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 68.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DashboardCardDefaults.shape,
            color = DashboardCardDefaults.cardContainerColor(),
            border = BorderStroke(1.dp, DashboardCardDefaults.outlineVariant()),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        Modifier.padding(start = 68.dp, end = 16.dp),
        color = DashboardCardDefaults.outlineVariant(),
    )
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    SettingsRow(icon, title, subtitle, Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(14.dp),
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        trailing()
    }
}
