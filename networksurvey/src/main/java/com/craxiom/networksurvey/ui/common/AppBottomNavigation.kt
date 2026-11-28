package com.craxiom.networksurvey.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.ui.kernelsu.FloatingBottomBar
import com.craxiom.networksurvey.ui.kernelsu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import kotlin.math.abs

internal data class AppNavigationItem(@param:StringRes val labelRes: Int, val icon: ImageVector)

@Composable
internal fun AppBottomNavigation(
    items: List<AppNavigationItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
) {
    val colors = MaterialTheme.colorScheme
    val background = colors.surface
    val mode = if (LocalDensity.current.fontScale > 1.5f) NavigationBarDisplayMode.IconOnly
        else NavigationBarDisplayMode.IconWithSelectedLabel
    val itemColors = NavigationBarDefaults.navigationBarItemColors(
        selectedContentColor = colors.primary,
        unselectedContentColor = colors.onSurfaceVariant,
    )
    Box(modifier.background(background).windowInsetsPadding(windowInsets)) {
        NavigationBar(color = background, defaultWindowInsetsPadding = false, mode = mode) {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                    icon = item.icon,
                    label = stringResource(item.labelRes),
                    colors = itemColors,
                )
            }
        }
    }
}

@Composable
internal fun AppFloatingNavigation(
    items: List<AppNavigationItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var bubbleProgress by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    FloatingBottomBar(
        modifier = modifier,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        tabsCount = items.size,
        onBubbleProgress = { bubbleProgress = it },
    ) { activateTab ->
        items.forEachIndexed { index, item ->
            val tint = lerp(colors.primary, colors.onSurfaceVariant, abs(bubbleProgress - index).coerceIn(0f, 1f))
            FloatingBottomBarItem(onClick = { activateTab(index) }, isSelected = selectedIndex == index) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(
                    text = stringResource(item.labelRes),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
