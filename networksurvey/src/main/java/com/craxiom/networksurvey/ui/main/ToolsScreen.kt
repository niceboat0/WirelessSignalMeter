package com.craxiom.networksurvey.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults

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
    val light = colors.surface.luminance() > 0.5f

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ToolCard(
                    R.string.tool_tower_map_title, R.string.tool_tower_map_description,
                    Icons.Rounded.Map, colors.primary, onOpenMap,
                )
            }
            item {
                ToolCard(
                    R.string.tool_road_coverage_title, R.string.tool_road_coverage_description,
                    Icons.Rounded.Timeline, colors.primary, onOpenRoadCoverage,
                )
            }
            item {
                ToolCard(
                    R.string.tool_band_lock_title, R.string.tool_band_lock_description,
                    Icons.Rounded.Lock, colors.tertiary, onOpenBandLock,
                )
            }
            item {
                ToolCard(
                    R.string.tool_speed_test_title, R.string.tool_speed_test_description,
                    Icons.Rounded.Speed, if (light) Color(0xFF25669C) else Color(0xFF9ACBFF), onOpenSpeedTest,
                )
            }
            item {
                ToolCard(
                    R.string.tool_signaling_title, R.string.tool_signaling_description,
                    Icons.Rounded.Sensors, colors.tertiary, onOpenSignaling,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(
    @StringRes title: Int,
    @StringRes description: Int,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container = DashboardCardDefaults.cardContainerColor()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = DashboardCardDefaults.shape,
        border = BorderStroke(1.dp, DashboardCardDefaults.outlineVariant()),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 92.dp).padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(15.dp),
                color = lerp(container, tint, 0.15f),
                contentColor = tint,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(description),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = colors.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}
