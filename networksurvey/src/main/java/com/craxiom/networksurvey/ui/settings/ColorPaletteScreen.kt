package com.craxiom.networksurvey.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.cellular.SignalMetricReading
import com.craxiom.networksurvey.ui.common.AppBottomNavigation
import com.craxiom.networksurvey.ui.common.AppFloatingNavigation
import com.craxiom.networksurvey.ui.common.AppNavigationItem
import com.craxiom.networksurvey.ui.common.PreferenceGroup
import com.craxiom.networksurvey.ui.dashboard.DashboardCardDefaults
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.ui.theme.InterfaceStyle
import com.craxiom.networksurvey.ui.theme.LocalInterfaceStyle
import com.craxiom.networksurvey.ui.theme.themeSeedColor
import com.craxiom.networksurvey.util.ThemePref
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

private val displayModes = listOf(ThemePref.MODE_SYSTEM, ThemePref.MODE_LIGHT, ThemePref.MODE_DARK)
private val themeSeeds = listOf("默认", "蓝色", "绿色", "紫色", "橙色", "红色", "玫红", "青色")
private val paletteStyles = ThemePaletteStyle.entries.map { it.name }
private val colorSpecs = listOf("SPEC_2021", "SPEC_2025")

@Composable
fun ColorPaletteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = ThemePref.currentThemeVersion
    val mode = remember(version, context) { ThemePref.get(context) }
    val dynamicSupported = ThemePref.isDynamicColorSupported()
    val dynamic = dynamicSupported && remember(version, context) { ThemePref.isMonet(context) }
    val seed = remember(version, context) { ThemePref.getSeed(context) }
    val paletteStyle = remember(version, context) { ThemePref.getPaletteStyle(context) }
    val colorSpec = remember(version, context) { ThemePref.getColorSpec(context) }
    val amoled = remember(version, context) { ThemePref.isAmoled(context) }
    val dark = mode == ThemePref.MODE_DARK || (mode == ThemePref.MODE_SYSTEM && isSystemInDarkTheme())
    val glassSupported = remember { isRuntimeShaderSupported() }
    val glassPreference = remember(version, context) { ThemePref.isLiquidGlassEnabled(context) }
    val floatingNavigation = remember(version, context) { ThemePref.isFloatingNavigationEnabled(context) }
    val glass = floatingNavigation && glassSupported && glassPreference
    val interfaceStyle = LocalInterfaceStyle.current
    val colors = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TitleBar("主题", compact = true, onBackClick = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 600.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = DashboardCardDefaults.margin, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(interfaceStyle.sectionSpacing),
            ) {
                item("preview") { ThemePreview(glass, floatingNavigation, dark, dark && amoled) }
                item("interface-style") {
                    ThemeGroup(stringResource(R.string.interface_style)) {
                        Column(Modifier.selectableGroup()) {
                            InterfaceStyle.entries.forEachIndexed { index, style ->
                                if (index > 0) ThemeDivider()
                                ThemeRadio(stringResource(style.labelRes), interfaceStyle == style) {
                                    ThemePref.setInterfaceStyle(context, style.preferenceValue)
                                }
                            }
                        }
                    }
                }
                item("mode") {
                    ThemeGroup("显示模式") {
                        Box(Modifier.padding(8.dp)) {
                            DisplayModeSelector(selectedMode = mode, onModeSelected = { next ->
                                if (mode != next) {
                                    ThemePref.set(context, next)
                                    ThemePref.apply(context)
                                }
                            })
                        }
                        if (mode != ThemePref.MODE_LIGHT) {
                            ThemeToggle(Icons.Rounded.DarkMode, "纯黑背景", "深色模式下生效", amoled) {
                                ThemePref.setAmoled(context, it)
                            }
                        }
                    }
                }
                item("palette") {
                    ThemeGroup("主题配色") {
                        if (dynamicSupported) {
                            ThemeToggle(Icons.Rounded.Wallpaper, "跟随系统壁纸", "使用系统动态取色", dynamic) {
                                ThemePref.setMonet(context, it)
                            }
                            ThemeDivider()
                        }
                        Text(
                            if (dynamic) "自定义主题色" else "主题色 · ${themeSeeds[seed]}",
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                        Column(Modifier.selectableGroup().padding(vertical = 4.dp)) {
                            themeSeeds.chunked(4).forEachIndexed { row, swatches ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    swatches.forEachIndexed { column, label ->
                                        val index = row * 4 + column
                                        val selected = !dynamic && index == seed
                                        Box(
                                            Modifier.size(48.dp).clip(CircleShape).selectable(selected, role = Role.RadioButton) {
                                                ThemePref.setSeed(context, index)
                                            }.semantics { contentDescription = label },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                Modifier.size(36.dp)
                                                    .border(
                                                        2.dp,
                                                        if (selected) colors.primary else Color.Transparent,
                                                        CircleShape,
                                                    )
                                                    .padding(4.dp).clip(CircleShape).background(themeSeedColor(index)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!dynamic && seed != ThemePref.SEED_DEFAULT) {
                            ThemeDivider()
                            ThemeOptions("配色风格", paletteStyle, paletteStyles) {
                                ThemePref.setPaletteStyle(context, it)
                            }
                            ThemeDivider()
                            ThemeOptions("配色算法", colorSpec, colorSpecs) {
                                ThemePref.setColorSpec(context, it)
                            }
                        }
                    }
                }
                item("effects") {
                    ThemeGroup("界面效果") {
                        ThemeToggle(
                            Icons.Rounded.ViewDay,
                            stringResource(R.string.floating_navigation),
                            null,
                            floatingNavigation,
                        ) { ThemePref.setFloatingNavigationEnabled(context, it) }
                        ThemeDivider()
                        ThemeToggle(
                            Icons.Rounded.BlurOn,
                            stringResource(R.string.liquid_glass),
                            when {
                                !glassSupported -> stringResource(R.string.liquid_glass_unsupported)
                                !floatingNavigation -> stringResource(R.string.liquid_glass_needs_floating)
                                else -> null
                            },
                            glassSupported && glassPreference,
                            enabled = floatingNavigation && glassSupported,
                        ) { ThemePref.setLiquidGlassEnabled(context, it) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DisplayModeSelector(selectedMode: Int, onModeSelected: (Int) -> Unit) {
    val labels = listOf("跟随系统", "浅色", "深色")
    val colors = MaterialTheme.colorScheme
    val activeMode = selectedMode.takeIf { it in displayModes } ?: ThemePref.MODE_SYSTEM
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth / displayModes.size >= 96.dp * LocalDensity.current.fontScale
        val track = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceContainerHighest)
            .selectableGroup()
            .padding(4.dp)
        if (horizontal) {
            Row(track, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                displayModes.forEachIndexed { index, mode ->
                    DisplayModeOption(
                        label = labels[index],
                        selected = activeMode == mode,
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(track, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                displayModes.forEachIndexed { index, mode ->
                    DisplayModeOption(
                        label = labels[index],
                        selected = activeMode == mode,
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThemeGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    PreferenceGroup(title, content)
}

@Composable
private fun ThemeDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun ThemeToggle(
    icon: ImageVector,
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = onChange,
        summary = summary,
        enabled = enabled,
        startAction = {
            val colors = MaterialTheme.colorScheme
            Icon(icon, null, Modifier.padding(end = 6.dp).size(22.dp),
                tint = if (enabled) colors.primary else colors.onSurfaceVariant)
        },
    )
}

@Composable
private fun ThemeRadio(title: String, checked: Boolean, onClick: () -> Unit) {
    RadioButtonPreference(
        title = title,
        selected = checked,
        modifier = Modifier.semantics { selected = checked },
        radioButtonLocation = RadioButtonLocation.End,
        onClick = onClick,
    )
}

@Composable
private fun ThemeOptions(title: String, current: String, items: List<String>, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedItem = current.takeIf { it in items } ?: items.first()
    ArrowPreference(
        title = title,
        summary = selectedItem,
        onClick = { expanded = !expanded },
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(folmeSpring(damping = 0.85f, response = 0.3f)),
        exit = shrinkVertically(folmeSpring(damping = 0.9f, response = 0.28f)),
    ) {
        Column(Modifier.selectableGroup()) {
            items.forEach { item ->
                ThemeRadio(item, item == selectedItem) {
                    expanded = false
                    onSelect(item)
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(glass: Boolean, floatingNavigation: Boolean, dark: Boolean, amoled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val style = LocalInterfaceStyle.current
    val backdrop = if (glass) rememberLayerBackdrop() else null
    var page by rememberSaveable { mutableIntStateOf(0) }
    val icons = listOf(Icons.Rounded.SignalCellularAlt, Icons.Rounded.Build, Icons.Rounded.Settings)
    val labels = listOf(R.string.home_signal, R.string.home_tools, R.string.settings)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("主题预览", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, letterSpacing = 0.sp)
            Text(if (amoled) "纯黑" else if (dark) "深色" else "浅色",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, letterSpacing = 0.sp)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = style.cardRadius,
        ) {
            Box {
                Column {
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .padding(style.cardPadding)
                            .padding(bottom = if (floatingNavigation) 88.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(style.sectionSpacing),
                    ) {
                        Text(stringResource(labels[page]), style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface, letterSpacing = 0.sp)
                        if (page == 0) {
                            ThemePreviewReadings()
                        } else {
                            val titles = if (page == 1) listOf("基站地图", "测试任务", "锁网") else listOf("外观", "关于")
                            titles.forEachIndexed { index, title ->
                                if (index > 0) ThemeDivider()
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = style.parameterRowPadding),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(icons[page], null, Modifier.size(20.dp), tint = colors.primary)
                                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurface, letterSpacing = 0.sp)
                                }
                            }
                        }
                    }
                    if (!floatingNavigation) AppBottomNavigation(
                        items = icons.mapIndexed { index, icon -> AppNavigationItem(labels[index], icon) },
                        selectedIndex = page,
                        onSelected = { page = it },
                        windowInsets = WindowInsets(0),
                    )
                }
                if (floatingNavigation) AppFloatingNavigation(
                    items = icons.mapIndexed { index, icon -> AppNavigationItem(labels[index], icon) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp),
                    selectedIndex = page,
                    onSelected = { page = it },
                    backdrop = backdrop,
                )
            }
        }
    }
}

@Composable
private fun ThemePreviewReadings() {
    val style = LocalInterfaceStyle.current
    val readings = listOf(Triple("RSRP", "-86", "dBm"), Triple("RSRQ", "-10", "dB"), Triple("SINR", "23", "dB"))
    val progresses = listOf(0.68f, 0.6f, 0.82f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = style == InterfaceStyle.CLASSIC && maxWidth >= 280.dp &&
            LocalDensity.current.fontScale <= 1.15f
        if (columns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                readings.forEachIndexed { index, (label, reading, unit) ->
                    SignalMetricReading(label, reading, unit, true, Modifier.weight(1f)) {
                        LinearProgressIndicator(progress = { progresses[index] }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Column {
                readings.forEachIndexed { index, (label, reading, unit) ->
                    SignalMetricReading(label, reading, unit, true, Modifier.fillMaxWidth(), horizontal = true) {
                        LinearProgressIndicator(progress = { progresses[index] }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
