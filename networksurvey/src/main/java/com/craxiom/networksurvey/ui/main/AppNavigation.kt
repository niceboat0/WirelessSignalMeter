package com.craxiom.networksurvey.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.craxiom.messaging.BluetoothRecordData
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.data.api.PlmnRecord
import com.craxiom.networksurvey.databinding.ContainerBluetoothDetailsFragmentBinding
import com.craxiom.networksurvey.databinding.ContainerSettingsFragmentBinding
import com.craxiom.networksurvey.databinding.ContainerTowerMapSettingsFragmentBinding
import com.craxiom.networksurvey.databinding.ContainerWifiDetailsFragmentBinding
import com.craxiom.networksurvey.databinding.ContainerWifiSpectrumFragmentBinding
import com.craxiom.networksurvey.fragments.BLUETOOTH_DATA_KEY
import com.craxiom.networksurvey.fragments.BluetoothDetailsFragment
import com.craxiom.networksurvey.fragments.WifiDetailsFragment
import com.craxiom.networksurvey.fragments.WifiSpectrumFragment
import com.craxiom.networksurvey.model.WifiNetwork
import com.craxiom.networksurvey.ui.acknowledgments.AcknowledgmentsScreen
import com.craxiom.networksurvey.ui.activesurvey.SurveyMonitorScreen
import com.craxiom.networksurvey.ui.cellular.CalculatorScreen
import com.craxiom.networksurvey.ui.cellular.baidumap.BaiduTowerMapScreen
import com.craxiom.networksurvey.ui.cellular.baidumap.RoadCoverageScreen
import com.craxiom.networksurvey.ui.bandlock.BandLockScreen
import com.craxiom.networksurvey.ui.bandlock.BandLockViewModel
import com.craxiom.networksurvey.ui.cellular.model.ServingCellInfo
import com.craxiom.networksurvey.ui.cellular.towermap.ProviderColorOverrideScreen
import com.craxiom.networksurvey.ui.cellular.towermap.ProviderColorOverrideViewModel
import com.craxiom.networksurvey.ui.engparam.EngineeringParamsScreen
import com.craxiom.networksurvey.ui.speedtest.SpeedTestScreen
import com.craxiom.networksurvey.ui.main.appbar.AppBarAction
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.ui.oui.OuiLookupScreen
import com.craxiom.networksurvey.ui.plmn.PlmnDetailsScreen
import com.craxiom.networksurvey.ui.plmn.PlmnLookupScreen
import com.craxiom.networksurvey.ui.plmn.components.PlmnInfoSheet
import com.craxiom.networksurvey.ui.watchlist.WATCHLIST_ENTRY_ID_KEY
import com.craxiom.networksurvey.ui.watchlist.WatchlistHistoryDetailScreen
import com.craxiom.networksurvey.ui.watchlist.WatchlistHistoryDetailViewModel
import com.craxiom.networksurvey.ui.watchlist.WatchlistHistoryScreen
import com.craxiom.networksurvey.ui.watchlist.WatchlistHistoryViewModel
import com.craxiom.networksurvey.ui.watchlist.WatchlistScreen
import com.craxiom.networksurvey.ui.watchlist.WatchlistViewModel
import com.craxiom.networksurvey.ui.settings.ColorPaletteScreen
import com.craxiom.networksurvey.ui.settings.SettingsScreen
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import com.craxiom.networksurvey.ui.rootdiag.SignalingScreen
import com.craxiom.networksurvey.ui.wifi.SsidExclusionListViewModel
import com.craxiom.networksurvey.ui.wifi.model.WifiNetworkInfoList

fun NavGraphBuilder.mainGraph(
    paddingValues: PaddingValues,
    mainNavController: NavHostController,
    sharedViewModel: SharedViewModel,
    rootDiag: RootDiagViewModel,
) {
    navigation(startDestination = NavDrawerOption.None.name, route = NavRoutes.MainRoute.name) {
        composable(NavDrawerOption.None.name) {
            HomeScreen(
                mainNavController = mainNavController,
                sharedViewModel = sharedViewModel,
                rootDiag = rootDiag,
            )
        }

        composable(NavDrawerOption.CellularCalculators.name) {
            Scaffold(
                topBar = { TitleBar("蜂窝计算器") { mainNavController.navigateUp() } },
                popupHost = { },
            ) { innerPadding ->
                Box(modifier = Modifier.padding(paddingValues = innerPadding)) {
                    CalculatorScreen(viewModel = viewModel())
                }
            }
        }

        composable(NavDrawerOption.SurveyMonitor.name) {
            SurveyMonitorScreen(
                onBackPressed = { mainNavController.navigateUp() },
                onNavigateToTowerMapSettings = {
                    mainNavController.navigate(NavOption.TowerMapSettings.name)
                }
            )
        }

        composable(NavDrawerOption.OuiLookup.name) {
            Scaffold(
                topBar = {
                    TitleBar(stringResource(R.string.oui_lookup_drawer_title)) {
                        mainNavController.navigateUp()
                    }
                },
                popupHost = { },
            ) { innerPadding ->
                Box(modifier = Modifier.padding(paddingValues = innerPadding)) {
                    OuiLookupScreen(
                        onOpenSettings = {
                            mainNavController.navigate(NavDrawerOption.Settings.name)
                        }
                    )
                }
            }
        }

        composable(NavDrawerOption.PlmnLookup.name) {
            PlmnLookupInCompose(mainNavController)
        }

        composable(NavOption.PlmnDetails.name) {
            val record = mainNavController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<PlmnRecord>(PlmnRecord.KEY)
            PlmnDetailsInCompose(record, mainNavController)
        }

        composable(NavDrawerOption.Settings.name) {
            SettingsFragmentInCompose(mainNavController, rootDiag)
        }

        // --------- 主题设置（KernelSU ColorPalette 风格独立页） --------- //
        composable(NavOption.ColorPalette.name) {
            ColorPaletteInCompose(mainNavController)
        }

        // --------- Deeper navigation (beyond the nav drawer) --------- //

        composable(NavOption.TowerMapSettings.name) {
            TowerMapSettingsFragmentInCompose(mainNavController)
        }

        composable(NavOption.SsidExclusionList.name) {
            val viewModel =
                viewModel<SsidExclusionListViewModel>()
            com.craxiom.networksurvey.ui.wifi.SsidExclusionListScreen(
                viewModel = viewModel,
                onNavigateUp = { mainNavController.navigateUp() }
            )
        }

        composable(NavDrawerOption.Watchlist.name) {
            val watchlistViewModel = viewModel<WatchlistViewModel>()
            WatchlistScreen(
                viewModel = watchlistViewModel,
                onNavigateUp = { mainNavController.navigateUp() },
                onNavigateToHistory = { mainNavController.navigate(NavOption.WatchlistHistory.name) }
            )
        }

        composable(NavDrawerOption.EngineeringParams.name) {
            EngineeringParamsInCompose(mainNavController)
        }

        composable(NavDrawerOption.SpeedTest.name) {
            SpeedTestInCompose(mainNavController)
        }

        composable(NavOption.WatchlistHistory.name) {
            val watchlistHistoryViewModel = viewModel<WatchlistHistoryViewModel>()
            WatchlistHistoryScreen(
                viewModel = watchlistHistoryViewModel,
                onNavigateUp = { mainNavController.navigateUp() },
                onOpenDetail = { entryId ->
                    mainNavController.currentBackStackEntry?.savedStateHandle?.set(
                        WATCHLIST_ENTRY_ID_KEY,
                        entryId
                    )
                    mainNavController.navigate(NavOption.WatchlistHistoryDetail.name)
                }
            )
        }

        composable(NavOption.WatchlistHistoryDetail.name) {
            val entryId = mainNavController.previousBackStackEntry?.savedStateHandle
                ?.get<Long>(WATCHLIST_ENTRY_ID_KEY) ?: -1L
            val detailViewModel = viewModel<WatchlistHistoryDetailViewModel>()
            WatchlistHistoryDetailScreen(
                viewModel = detailViewModel,
                entryId = entryId,
                onNavigateUp = { mainNavController.navigateUp() }
            )
        }

        composable(NavOption.ProviderColorOverrides.name) {
            val viewModel =
                viewModel<ProviderColorOverrideViewModel>()
            ProviderColorOverrideScreen(
                viewModel = viewModel,
                onNavigateUp = { mainNavController.navigateUp() }
            )
        }

        composable(NavOption.Acknowledgments.name) {
            AcknowledgmentsScreen(
                onNavigateUp = { mainNavController.navigateUp() }
            )
        }

        composable(NavOption.TowerMap.name) {
            val subscriptionId = mainNavController.previousBackStackEntry?.savedStateHandle
                ?.get<Int>(ServingCellInfo.SUBSCRIPTION_KEY)
            val initial = sharedViewModel.latestServingCellInfo
            BaiduTowerMapScreen(
                onBackButtonPressed = { mainNavController.navigateUp() },
                servingCellInfo = initial,
                subscriptionId = subscriptionId,
                rootDiag = rootDiag,
            )
        }

        composable(NavOption.RoadCoverage.name) {
            RoadCoverageScreen(onBack = { mainNavController.navigateUp() })
        }

        composable(NavOption.BandLock.name) { entry ->
            val mainEntry = remember(entry) { mainNavController.getBackStackEntry(NavRoutes.MainRoute.name) }
            val bandLock = viewModel<BandLockViewModel>(viewModelStoreOwner = mainEntry)
            BandLockScreen(rootDiag = rootDiag, onBack = { mainNavController.navigateUp() }, model = bandLock)
        }

        composable(NavOption.Signaling.name) {
            SignalingScreen(rootDiag, onBack = { mainNavController.navigateUp() })
        }

        composable(NavOption.WifiSpectrum.name) {
            WifiSpectrumInCompose(sharedViewModel.wifiNetworkList, mainNavController)
        }

        composable(NavOption.WifiDetails.name) {
            val wifiNetwork =
                mainNavController.previousBackStackEntry?.savedStateHandle?.get<WifiNetwork>(
                    WifiNetwork.KEY
                )

            WifiDetailsInCompose(paddingValues, wifiNetwork)
        }

        composable(NavOption.BluetoothDetails.name) {
            val bluetoothRecordData =
                mainNavController.previousBackStackEntry?.savedStateHandle?.get<BluetoothRecordData>(
                    BLUETOOTH_DATA_KEY
                )

            BluetoothDetailsInCompose(paddingValues, bluetoothRecordData)
        }
    }
}

enum class NavDrawerOption {
    None,
    CellularCalculators,
    OuiLookup,
    PlmnLookup,
    SurveyMonitor,
    Watchlist,
    EngineeringParams,
    SpeedTest,
    Settings,

    // External Links
    UserManual,
    MessagingDocs,
    ReportAnIssue,
    GitHub
}

enum class NavOption {
    TowerMapSettings,
    TowerMap,
    RoadCoverage,
    BandLock,
    Signaling,
    WifiSpectrum,
    WifiDetails,
    BluetoothDetails,
    PlmnDetails,
    SsidExclusionList,
    ProviderColorOverrides,
    Acknowledgments,
    WatchlistHistory,
    WatchlistHistoryDetail,
    ColorPalette
}

@Composable
fun EngineeringParamsInCompose(mainNavController: NavHostController) {
    EngineeringParamsScreen(onNavigateUp = { mainNavController.navigateUp() })
}

@Composable
fun SpeedTestInCompose(mainNavController: NavHostController) {
    SpeedTestScreen(onBack = { mainNavController.navigateUp() })
}

@Composable
fun SettingsFragmentInCompose(mainNavController: NavHostController, rootDiag: RootDiagViewModel) {
    Scaffold(
        topBar = { TitleBar("设置") { mainNavController.navigateUp() } },
        popupHost = { },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(paddingValues = innerPadding)) {
            com.craxiom.networksurvey.ui.settings.SettingsScreen(
                rootDiag = rootDiag,
                onNavigateToAcknowledgments = {
                    mainNavController.navigate(NavOption.Acknowledgments.name)
                },
                onNavigateToEngParams = {
                    mainNavController.navigate(NavDrawerOption.EngineeringParams.name)
                },
                onNavigateToTheme = {
                    mainNavController.navigate(NavOption.ColorPalette.name)
                }
            )
        }
    }
}

/**
 * 主题设置页（KernelSU ColorPalette 风格）。
 */
@Composable
fun ColorPaletteInCompose(mainNavController: NavHostController) {
    ColorPaletteScreen(onBack = { mainNavController.navigateUp() })
}

@Composable
fun TowerMapSettingsFragmentInCompose(mainNavController: NavHostController) {
    Scaffold(
        topBar = { TitleBar("塔图设置") { mainNavController.navigateUp() } },
        popupHost = { },
    ) { innerPadding ->
        AndroidViewBinding(
            ContainerTowerMapSettingsFragmentBinding::inflate,
            modifier = Modifier.padding(paddingValues = innerPadding)
        ) {
        }
    }
}

/**
 * 百度地图基站地图（完全重构版，纯 Compose）
 */
@Composable
fun BaiduTowerMapInCompose(onBack: () -> Unit = {}, servingCellInfo: ServingCellInfo? = null) {
    BaiduTowerMapScreen(
        onBackButtonPressed = onBack,
        servingCellInfo = servingCellInfo
    )
}

@Composable
fun WifiSpectrumInCompose(
    wifiNetworks: WifiNetworkInfoList?,
    mainNavController: NavHostController
) {
    Scaffold(
        topBar = { TitleBar("Wi-Fi 频谱") { mainNavController.navigateUp() } },
        popupHost = { },
    ) { innerPadding ->
        AndroidViewBinding(
            ContainerWifiSpectrumFragmentBinding::inflate,
            modifier = Modifier.padding(paddingValues = innerPadding)
        ) {
            if (wifiNetworks != null) {
                val fragment: WifiSpectrumFragment? =
                    wifiSpectrumFragmentContainerView.getFragment()
                fragment?.setWifiNetworks(wifiNetworks)
            }
        }
    }
}

@Composable
fun WifiDetailsInCompose(paddingValues: PaddingValues, wifiNetwork: WifiNetwork?) {
    if (wifiNetwork != null) {
        AndroidViewBinding(
            ContainerWifiDetailsFragmentBinding::inflate,
            modifier = Modifier.padding(paddingValues = paddingValues)
        ) {
            val fragment: WifiDetailsFragment? = wifiDetailsFragmentContainerView.getFragment()
            fragment?.setWifiNetwork(wifiNetwork)
        }
    }
}

@Composable
fun BluetoothDetailsInCompose(
    paddingValues: PaddingValues,
    bluetoothRecordData: BluetoothRecordData?
) {
    if (bluetoothRecordData != null) {
        AndroidViewBinding(
            ContainerBluetoothDetailsFragmentBinding::inflate,
            modifier = Modifier.padding(paddingValues = paddingValues)
        ) {
            val fragment: BluetoothDetailsFragment? =
                bluetoothDetailsFragmentContainerView.getFragment()
            fragment?.setBluetoothData(bluetoothRecordData)
        }
    }
}

@Composable
fun PlmnLookupInCompose(mainNavController: NavHostController) {
    var showInfoSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TitleBar(
                title = stringResource(R.string.plmn_lookup_drawer_title),
                onBackClick = { mainNavController.navigateUp() },
                appBarActions = listOf(
                    AppBarAction(
                        icon = R.drawable.ic_help,
                        description = R.string.plmn_help_description,
                        onClick = { showInfoSheet = true }
                    )
                )
            )
        },
        popupHost = { },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(paddingValues = innerPadding)) {
            PlmnLookupScreen(
                onRecordClick = { record ->
                    mainNavController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set(PlmnRecord.KEY, record)
                    mainNavController.navigate(NavOption.PlmnDetails.name)
                }
            )
        }
    }

    if (showInfoSheet) {
        PlmnInfoSheet(onDismiss = { showInfoSheet = false })
    }
}

@Composable
fun PlmnDetailsInCompose(record: PlmnRecord?, mainNavController: NavHostController) {
    var showInfoSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TitleBar(
                title = stringResource(R.string.plmn_details_title),
                onBackClick = { mainNavController.navigateUp() },
                appBarActions = listOf(
                    AppBarAction(
                        icon = R.drawable.ic_help,
                        description = R.string.plmn_help_description,
                        onClick = { showInfoSheet = true }
                    )
                )
            )
        },
        popupHost = { },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(paddingValues = innerPadding)) {
            if (record != null) {
                PlmnDetailsScreen(record = record)
            }
        }
    }

    if (showInfoSheet) {
        PlmnInfoSheet(onDismiss = { showInfoSheet = false })
    }
}
