package com.bettertorrent.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bettertorrent.app.ui.add.AddTorrentSheet
import com.bettertorrent.app.ui.components.BackgroundGuideSheet
import com.bettertorrent.app.ui.detail.TorrentDetailScreen
import com.bettertorrent.app.ui.library.LibraryScreen
import com.bettertorrent.app.ui.settings.SettingsScreen
import com.bettertorrent.app.ui.transfers.TransfersScreen

object Routes {
    const val TRANSFERS = "transfers"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{hash}"
    fun detail(hash: String) = "detail/$hash"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TRANSFERS, "Transfers", Icons.Rounded.Download),
    Tab(Routes.LIBRARY, "Library", Icons.Rounded.FolderOpen),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val actions = LocalAppActions.current
    val torrents by vm.torrents.collectAsStateWithLifecycle()
    val addState by vm.addState.collectAsStateWithLifecycle()
    val pending by vm.pendingDetail.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate(Routes.detail(it)) { launchSingleTop = true }
            vm.pendingDetail.value = null
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = route == null || tabs.any { it.route == route }
    val activeCount = torrents.count { it.state.isActive }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar, modifier = if (topLevel) Modifier else Modifier.navigationBarsPadding())
        },
        bottomBar = {
            if (topLevel) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = {
                                if (tab.route == Routes.TRANSFERS && activeCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeCount") } }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.TRANSFERS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.TRANSFERS) {
                TransfersScreen(vm = vm, onOpenTorrent = { nav.navigate(Routes.detail(it)) })
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(onGoToTransfers = { nav.switchTab(Routes.TRANSFERS) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm = vm)
            }
            composable(Routes.DETAIL) { backStack ->
                val hash = backStack.arguments?.getString("hash").orEmpty()
                TorrentDetailScreen(
                    vm = vm,
                    hash = hash,
                    onBack = { nav.popBackStack() },
                    onOpenLibrary = { nav.switchTab(Routes.LIBRARY) },
                )
            }
        }
    }

    val showGuide by vm.showGuide.collectAsStateWithLifecycle()
    if (showGuide) {
        BackgroundGuideSheet(
            guide = vm.oemGuide,
            onAllowBattery = actions::requestBatteryExemption,
            onOpenStep = actions::openOemStep,
            onOpenHelp = actions::openUrl,
            onDismiss = vm::closeGuide,
        )
    }

    if (addState !is AddState.Hidden) {
        AddTorrentSheet(
            state = addState,
            vm = vm,
            onPlay = { req -> actions.play(req) },
        )
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
