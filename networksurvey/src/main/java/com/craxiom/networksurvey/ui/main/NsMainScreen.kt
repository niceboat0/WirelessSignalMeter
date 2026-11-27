package com.craxiom.networksurvey.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import android.view.Window
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.component.PagerNavigationSpringSpec
import com.craxiom.networksurvey.fragments.BLUETOOTH_DATA_KEY
import com.craxiom.networksurvey.model.WifiNetwork
import com.craxiom.networksurvey.ui.cellular.model.ServingCellInfo
import com.craxiom.networksurvey.ui.rootdiag.RootDiagViewModel
import com.craxiom.networksurvey.ui.theme.NsTheme
import com.craxiom.networksurvey.util.BatteryOptimizationHelper
import top.yukonga.miuix.kmp.basic.Scaffold
import timber.log.Timber


@Composable
fun MainCompose(
    mainNavController: NavHostController = rememberNavController(),
    appVersion: String,
    deepLinkViewModel: DeepLinkViewModel = viewModel(),
    window: Window? = null,
) {
    val context = LocalContext.current

    val viewModel = viewModel<SharedViewModel>()
    val app = LocalContext.current.applicationContext as Application
    val rootDiag = viewModel<RootDiagViewModel>(
        viewModelStoreOwner = RootDiagViewModelStore,
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app),
    )
    if (!LocalInspectionMode.current) {
        LifecycleStartEffect(rootDiag) {
            rootDiag.enterForeground()
            onStopOrDispose { rootDiag.leaveForeground() }
        }
    }
    // Ensure we use activity context for proper intent launching
    val activity = context as? android.app.Activity
    val batteryOptimizationHelper = remember {
        BatteryOptimizationHelper(activity ?: context)
    }
    var showBatteryDialog by remember { mutableStateOf(false) }

    // Observe navigation events using modern Compose StateFlow pattern
    val navigationEvent by viewModel.navigationEvent.collectAsStateWithLifecycle()
    LaunchedEffect(navigationEvent) {
        navigationEvent?.let { event ->
            when (event) {
                is NavigationEvent.TowerMapSettings -> {
                    mainNavController.navigate(NavOption.TowerMapSettings.name)
                }

                is NavigationEvent.SsidExclusionList -> {
                    mainNavController.navigate(NavOption.SsidExclusionList.name)
                }

                is NavigationEvent.Watchlist -> {
                    mainNavController.navigate(NavDrawerOption.Watchlist.name)
                }

                is NavigationEvent.Acknowledgments -> {
                    mainNavController.navigate(NavOption.Acknowledgments.name)
                }

                is NavigationEvent.ProviderColorOverrides -> {
                    mainNavController.navigate(NavOption.ProviderColorOverrides.name)
                }

                is NavigationEvent.TowerMap -> {
                    mainNavController.currentBackStackEntry?.savedStateHandle?.set(
                        ServingCellInfo.SUBSCRIPTION_KEY,
                        event.servingCellInfo.subscriptionId
                    )
                    mainNavController.navigate(NavOption.TowerMap.name)
                }

                is NavigationEvent.WifiDetails -> {
                    mainNavController.currentBackStackEntry?.savedStateHandle?.set(
                        WifiNetwork.KEY,
                        event.wifiNetwork
                    )
                    mainNavController.navigate(NavOption.WifiDetails.name)
                }

                is NavigationEvent.BluetoothDetails -> {
                    mainNavController.currentBackStackEntry?.savedStateHandle?.set(
                        BLUETOOTH_DATA_KEY,
                        event.bluetoothData
                    )
                    mainNavController.navigate(NavOption.BluetoothDetails.name)
                }

                is NavigationEvent.Settings -> {
                    mainNavController.navigate(NavDrawerOption.Settings.name)
                }
            }
            // Clear the event after handling to prevent re-processing
            viewModel.clearNavigationEvent()
        }
    }

    // Observe dialog events using modern Compose StateFlow pattern
    val dialogEvent by viewModel.dialogEvent.collectAsStateWithLifecycle()
    LaunchedEffect(dialogEvent) {
        dialogEvent?.let { event ->
            when (event) {
                is DialogEvent.BatteryOptimization -> {
                    if (batteryOptimizationHelper.shouldPromptForBatteryOptimization()) {
                        showBatteryDialog = true
                    }
                }
            }
            // Clear the event after handling
            viewModel.clearDialogEvent()
        }
    }

    NsTheme(window = window) {
        val deepLinkDestination by deepLinkViewModel.navigationDestination.collectAsStateWithLifecycle()
        LaunchedEffect(deepLinkDestination) {
            deepLinkDestination?.let { destination ->
                Timber.i("Navigating to %s from deep link", destination)
                mainNavController.navigate(destination) {
                    popUpTo(NavDrawerOption.None.name)
                }
                // Clear the navigation event to prevent re-navigation
                deepLinkViewModel.clearNavigation()
            }
        }

        // Render the activity-driven startup permission/GPS/GNSS dialogs using the shared
        // Compose dialog components.
        val startupActions = activity as? StartupDialogActions
        if (startupActions != null) {
            StartupDialogs(actions = startupActions, viewModel = viewModel)
        }

        // Show battery dialog when triggered, but never on top of a startup permission dialog so the
        // user resolves permissions first.
        val startupDialog by viewModel.startupDialog.collectAsStateWithLifecycle()
        if (showBatteryDialog && startupDialog == null) {
            BatteryOptimizationDialog(
                onDismiss = { showBatteryDialog = false },
                onGoToSettings = {
                    showBatteryDialog = false
                    batteryOptimizationHelper.openBatteryOptimizationSettings()
                },
                batteryOptimizationHelper = batteryOptimizationHelper
            )
        }

        // 上传日志对话框已随日志功能一并移除（原抽屉「报告问题/上传日志」入口同步删除）

        Scaffold(
            popupHost = { }
        ) { paddingValues ->
            NavHost(
                mainNavController,
                startDestination = NavRoutes.MainRoute.name,
                enterTransition = {
                    slideInHorizontally(
                        animationSpec = PagerNavigationSpringSpec,
                        initialOffsetX = { it }
                    ) + fadeIn(animationSpec = tween(300))
                },
                exitTransition = {
                    slideOutHorizontally(
                        animationSpec = PagerNavigationSpringSpec,
                        targetOffsetX = { -it }
                    ) + fadeOut(animationSpec = tween(200))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        animationSpec = PagerNavigationSpringSpec,
                        initialOffsetX = { -it }
                    ) + fadeIn(animationSpec = tween(300))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        animationSpec = PagerNavigationSpringSpec,
                        targetOffsetX = { it }
                    ) + fadeOut(animationSpec = tween(200))
                },
            ) {
                mainGraph(
                    paddingValues = paddingValues,
                    mainNavController = mainNavController,
                    sharedViewModel = viewModel,
                    rootDiag = rootDiag,
                )
            }
        }
    }
}

enum class NavRoutes {
    MainRoute,
}

@Preview
@Composable
fun MainActivityPreview() {
    MainCompose(appVersion = "1.0.0")
}
