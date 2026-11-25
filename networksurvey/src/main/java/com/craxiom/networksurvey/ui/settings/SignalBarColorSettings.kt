package com.craxiom.networksurvey.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.cellular.SignalBarColors
import com.craxiom.networksurvey.ui.cellular.SignalBarMetric
import com.craxiom.networksurvey.ui.cellular.SignalBarPreferences
import com.craxiom.networksurvey.ui.common.AppDialog
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import java.util.Locale

@Composable
internal fun SignalBarColorSettings(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var metric by remember { mutableStateOf(SignalBarMetric.LTE_RSRP) }
    var drafts by remember(prefs) { mutableStateOf(SignalBarMetric.entries.associateWith {
        SignalBarPreferences.load(prefs, it).toDraft()
    }) }
    var picker by remember { mutableStateOf<Int?>(null) }
    val draft = drafts.getValue(metric)
    val bounds = draft.bounds.mapNotNull(String::toIntOrNull)
    val valid = SignalBarPreferences.validBounds(bounds)
    val update: (ColorDraft) -> Unit = { drafts = drafts + (metric to it) }

    AppDialog(
        show = true,
        onDismissRequest = onClose,
        title = stringResource(R.string.signal_bar_colors),
        confirmText = stringResource(R.string.signal_color_save),
        dismissText = stringResource(R.string.cancel),
        confirmEnabled = drafts.values.all { SignalBarPreferences.validBounds(it.bounds.mapNotNull(String::toIntOrNull)) },
        onConfirm = {
            SignalBarPreferences.save(prefs, drafts.mapValues { (_, value) ->
                SignalBarColors(value.bounds.map(String::toInt), value.colors)
            })
            onClose()
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WindowDropdownPreference(
                title = metric.unit,
                items = SignalBarMetric.entries.map { it.title },
                selectedIndex = metric.ordinal,
                onSelectedIndexChange = { metric = SignalBarMetric.entries[it] },
            )
            for (index in draft.colors.indices) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (boundary in index..index + 1) {
                        if (boundary > index) Text("–")
                        TextField(
                            value = draft.bounds[boundary],
                            onValueChange = { input ->
                                if (input.length <= 4 && input.all { it == '-' || it.isDigit() }) {
                                    val updated = draft.bounds.toMutableList().apply { set(boundary, input) }
                                    update(draft.copy(bounds = updated))
                                }
                            },
                            label = stringResource(if (boundary == index) R.string.signal_range_min else R.string.signal_range_max),
                            insideMargin = DpSize(8.dp, 8.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val description = stringResource(R.string.signal_range_color, index + 1)
                    Box(Modifier.size(42.dp, 28.dp).clip(MaterialTheme.shapes.small)
                        .background(Color(draft.colors[index])).semantics { contentDescription = description }
                        .clickable(role = Role.Button) { picker = index })
                }
            }
            if (!valid) {
                Text(stringResource(R.string.signal_range_invalid), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(stringResource(R.string.signal_color_reset), onClick = {
                    update(SignalBarPreferences.defaults(metric).toDraft())
                }, modifier = Modifier.weight(1f))
                TextButton(stringResource(R.string.signal_color_reset_all), onClick = {
                    drafts = SignalBarMetric.entries.associateWith { SignalBarPreferences.defaults(it).toDraft() }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
    picker?.let { index ->
        SignalSwatchPicker(draft.colors[index], onClose = { picker = null }, onColor = { color ->
            update(draft.copy(colors = draft.colors.toMutableList().apply { set(index, color) }))
            picker = null
        })
    }
}

private data class ColorDraft(val bounds: List<String>, val colors: List<Int>)
private fun SignalBarColors.toDraft() = ColorDraft(bounds.map(Int::toString), colors)

@Composable
private fun SignalSwatchPicker(color: Int, onClose: () -> Unit, onColor: (Int) -> Unit) {
    var hex by remember(color) { mutableStateOf(String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)) }
    val selected = hex.toIntOrNull(16)?.takeIf { hex.length == 6 }?.or(0xFF000000.toInt())
    val swatches = SignalBarPreferences.defaults(SignalBarMetric.LTE_RSRP).colors
    AppDialog(
        show = true,
        onDismissRequest = onClose,
        title = stringResource(R.string.signal_bar_pick_color),
        confirmText = stringResource(R.string.signal_color_save),
        dismissText = stringResource(R.string.cancel),
        confirmEnabled = selected != null,
        onConfirm = { selected?.let(onColor) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                swatches.forEach { swatch ->
                    val rgb = String.format(Locale.ROOT, "%06X", swatch and 0xFFFFFF)
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(swatch))
                        .semantics { contentDescription = "#$rgb" }.clickable(role = Role.Button) { hex = rgb })
                }
            }
            TextField(hex, { hex = it.filter { character -> character.digitToIntOrNull(16) != null }.take(6) },
                label = "#RRGGBB", singleLine = true)
            Box(Modifier.fillMaxWidth().height(18.dp).clip(CircleShape)
                .background(selected?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHighest))
        }
    }
}
