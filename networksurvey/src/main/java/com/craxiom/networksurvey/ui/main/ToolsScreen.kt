package com.craxiom.networksurvey.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.common.PreferenceDivider
import com.craxiom.networksurvey.ui.common.PreferenceGroup
import com.craxiom.networksurvey.ui.common.PreferenceLink

@Composable
internal fun ToolsScreen(
    bottomPadding: Dp,
    onOpenMap: () -> Unit,
    onOpenRoadCoverage: () -> Unit,
    onOpenBandLock: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenSignaling: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item("field-tests") {
                PreferenceGroup(stringResource(R.string.tools_field_tests)) {
                    PreferenceLink(
                        Icons.Rounded.Map, stringResource(R.string.tool_tower_map_title),
                        onClick = onOpenMap,
                    )
                    PreferenceDivider()
                    PreferenceLink(
                        Icons.Rounded.Timeline, stringResource(R.string.tool_road_coverage_title),
                        onClick = onOpenRoadCoverage,
                    )
                    PreferenceDivider()
                    PreferenceLink(
                        Icons.Rounded.Speed, stringResource(R.string.tool_speed_test_title),
                        onClick = onOpenSpeedTest,
                    )
                }
            }
            item("diagnostics") {
                PreferenceGroup(stringResource(R.string.tools_diagnostics)) {
                    PreferenceLink(
                        Icons.Rounded.Sensors, stringResource(R.string.tool_signaling_title),
                        iconTint = colors.tertiary,
                        onClick = onOpenSignaling,
                    )
                    PreferenceDivider()
                    PreferenceLink(
                        Icons.Rounded.Lock, stringResource(R.string.tool_band_lock_title),
                        iconTint = colors.tertiary,
                        onClick = onOpenBandLock,
                    )
                }
            }
        }
    }
}
