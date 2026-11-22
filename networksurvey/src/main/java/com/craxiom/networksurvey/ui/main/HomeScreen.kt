package com.craxiom.networksurvey.ui.main

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.databinding.ContainerCellularFragmentBinding
import com.craxiom.networksurvey.fragments.MainCellularFragment
import com.craxiom.networksurvey.ui.cellular.model.ServingCellInfo
import com.craxiom.networksurvey.ui.component.PagerSpringSpec
import com.craxiom.networksurvey.ui.kernelsu.FloatingBottomBar
import com.craxiom.networksurvey.ui.kernelsu.FloatingBottomBarItem
import com.craxiom.networksurvey.ui.main.appbar.AppBarAction
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.ui.settings.SettingsScreen
import com.craxiom.networksurvey.ui.rootdiag.RootDiagStatus
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import com.craxiom.networksurvey.ui.rootdiag.DeviceCapabilities
import com.craxiom.networksurvey.ui.rootdiag.RootSignalScreen
import com.craxiom.networksurvey.model.CellularProtocol
import com.craxiom.networksurvey.update.UpdateChecker
import com.craxiom.networksurvey.update.UpdateDialog
import com.craxiom.networksurvey.update.UpdateDownloader
import com.craxiom.networksurvey.util.ThemePref
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import kotlin.math.abs

private enum class HomeTab(@StringRes val title: Int, val icon: ImageVector) {
    Signal(R.string.home_signal, Icons.Rounded.SignalCellularAlt),
    Tools(R.string.home_tools, Icons.Rounded.Build),
    Settings(R.string.settings, Icons.Rounded.Settings),
}

internal val FloatingNavigationClearance = 80.dp

@Composable
fun HomeScreen(
    mainNavController: NavHostController,
    sharedViewModel: SharedViewModel,
    rootDiag: RootDiagViewModel,
) {
    val pagerState = rememberPagerState { HomeTab.entries.size }
    val pagerScope = rememberCoroutineScope()
    var bubbleProgress by remember { mutableFloatStateOf(pagerState.currentPage.toFloat()) }

    val colors = MaterialTheme.colorScheme
    val background = colors.background
    val bottomPadding = FloatingNavigationClearance +
        WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    val openMap = {
        mainNavController.currentBackStackEntry?.savedStateHandle?.set(
            ServingCellInfo.SUBSCRIPTION_KEY,
            sharedViewModel.latestServingCellInfo?.subscriptionId,
        )
        mainNavController.navigate(NavOption.TowerMap.name) { launchSingleTop = true }
    }

    val context = LocalContext.current
    val themeVersion = ThemePref.currentThemeVersion
    val glassEnabled = remember(context, themeVersion) {
        ThemePref.isLiquidGlassEnabled(context) && isRuntimeShaderSupported()
    }
    val backdrop = if (glassEnabled) rememberLayerBackdrop() else null
    val pendingUpdate by UpdateChecker.pendingUpdate
    LaunchedEffect(Unit) {
        UpdateChecker.check(context, force = false)
    }
    pendingUpdate?.let { update ->
        UpdateDialog(
            version = update,
            onDismiss = { UpdateChecker.pendingUpdate.value = null },
            onDownload = {
                UpdateDownloader.download(context, update.apkUrl)
                UpdateChecker.pendingUpdate.value = null
            },
            onIgnore = {
                UpdateChecker.ignoreVersion(context, update.versionCode)
                UpdateChecker.pendingUpdate.value = null
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(background),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                // The signal page hosts an Android Fragment. Keep it mounted when switching tabs.
                beyondViewportPageCount = HomeTab.entries.lastIndex,
                overscrollEffect = null,
            ) { page ->
                when (HomeTab.entries[page]) {
                    HomeTab.Signal -> SignalTab(rootDiag, bottomPadding, pagerState.settledPage == HomeTab.Signal.ordinal)
                    HomeTab.Tools -> HomeTabPage(R.string.professional_tools_title) {
                        ToolsScreen(
                            bottomPadding = bottomPadding,
                            onOpenMap = openMap,
                            onOpenBandLock = {
                                mainNavController.navigate(NavOption.BandLock.name) { launchSingleTop = true }
                            },
                            onOpenRoadCoverage = {
                                mainNavController.navigate(NavOption.RoadCoverage.name) { launchSingleTop = true }
                            },
                            onOpenSpeedTest = {
                                mainNavController.navigate(NavDrawerOption.SpeedTest.name) { launchSingleTop = true }
                            },
                            onOpenSignaling = {
                                mainNavController.navigate(NavOption.Signaling.name) { launchSingleTop = true }
                            },
                        )
                    }
                    HomeTab.Settings -> HomeTabPage(R.string.settings) {
                        SettingsScreen(
                            onNavigateToAcknowledgments = { mainNavController.navigate(NavOption.Acknowledgments.name) },
                            onNavigateToEngParams = { mainNavController.navigate(NavDrawerOption.EngineeringParams.name) },
                            onNavigateToTheme = { mainNavController.navigate(NavOption.ColorPalette.name) },
                            bottomContentPadding = bottomPadding,
                            rootDiag = rootDiag,
                        )
                    }
                }
            }
        }

        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .padding(bottom = 8.dp),
            selectedIndex = pagerState.targetPage,
            onSelected = { index ->
                pagerScope.launch { pagerState.animateScrollToPage(index, animationSpec = PagerSpringSpec) }
            },
            tabsCount = HomeTab.entries.size,
            backdrop = backdrop,
            isBlurEnabled = glassEnabled,
            accentColor = colors.primary,
            surfaceContainer = colors.surfaceContainer,
            onBubbleProgress = { bubbleProgress = it },
        ) { activateTab ->
            HomeTab.entries.forEachIndexed { index, tab ->
                val distance = abs(bubbleProgress - index).coerceIn(0f, 1f)
                val tint = lerp(colors.primary, colors.onSurfaceVariant, distance)
                FloatingBottomBarItem(
                    onClick = { activateTab(index) },
                    isSelected = pagerState.currentPage == index,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(
                        text = stringResource(tab.title),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTabPage(
    @StringRes title: Int,
    actions: List<AppBarAction> = emptyList(),
    content: @Composable (ScrollBehavior) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Column(Modifier.fillMaxSize()) {
        TitleBar(
            title = stringResource(title),
            appBarActions = actions,
            scrollBehavior = scrollBehavior,
            onBackClick = null,
        )
        Box(Modifier.fillMaxWidth().weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            content(scrollBehavior)
        }
    }
}

@Composable
private fun SignalTab(rootDiag: RootDiagViewModel, bottomPadding: Dp, active: Boolean) {
    val signal by rootDiag.signal.collectAsStateWithLifecycle()
    var fragment by remember { mutableStateOf<MainCellularFragment?>(null) }
    var simCount by remember { mutableIntStateOf(0) }
    val androidCells = fragment?.cellularModel?.observeAsState()?.value
    val protocol = androidCells?.servingCellProtocol?.observeAsState()?.value
    val nrBandwidthDiagnostic = androidCells?.nrBandwidthDiagnostic?.observeAsState()?.value
    val nrServing = androidCells?.nrServingCell?.observeAsState()?.value
    // DIAG has no reliable subscription id, so dual-SIM display stays on Android Telephony.
    // Collection itself is not stopped when more than one SIM is active.
    val diagLive = signal.status == RootDiagStatus.Running
    val now = SystemClock.elapsedRealtime()
    val diagServing = signal.hasLiveServingCell(protocol?.name, now) ||
        (protocol == CellularProtocol.LTE && nrServing != null && signal.hasLiveServingCell("NR", now))
    val useRoot = DeviceCapabilities.displayUsesDiag(simCount, protocol?.name, diagServing)
    LaunchedEffect(active, useRoot, protocol, simCount) {
        if (active) {
            rootDiag.recordSignalMode(useRoot, protocol?.name, simCount)
            rootDiag.updateSimContext(simCount, fragment?.currentSubscriptionId?.takeIf { it >= 0 })
        }
    }
    LaunchedEffect(active, useRoot, nrBandwidthDiagnostic) {
        if (active) rootDiag.recordAndroidNrBandwidth(nrBandwidthDiagnostic.takeUnless { useRoot })
    }

    DisposableEffect(fragment) {
        val cellular = fragment
        cellular?.setSimSwitchCallback { _, count, _ -> simCount = count }
        simCount = cellular?.simCount ?: 0
        onDispose { cellular?.setSimSwitchCallback(null) }
    }

    val actions = if (!useRoot && simCount > 1) {
        listOf(AppBarAction(R.drawable.ic_sim_switch, R.string.sim_switch, onClick = { fragment?.switchToNextSim() }))
    } else emptyList()
    HomeTabPage(R.string.home_signal, actions) { scrollBehavior ->
        Box(Modifier.fillMaxSize()) {
            // Keep the Android cellular fragment alive to serve the map and instant fallback.
            Box(Modifier.matchParentSize().alpha(if (useRoot) 0f else 1f).then(
                if (useRoot) Modifier.semantics { hideFromAccessibility() } else Modifier
            )) {
                AndroidViewBinding(ContainerCellularFragmentBinding::inflate, Modifier.fillMaxSize()) {
                    val cellular = cellularFragmentContainerView.getFragment<MainCellularFragment>()
                    cellular.topBarScroll.connection = scrollBehavior.nestedScrollConnection
                    fragment = cellular
                }
            }
            if (useRoot) {
                RootSignalScreen(signal, rootDiag::chartFor, bottomPadding, androidCells, active)
            } else if (diagLive && simCount > 1) {
                Text(
                    stringResource(R.string.diag_dual_sim_unmapped),
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 16.dp, end = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
