package app.prodl.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.prodl.ProDLApplication
import app.prodl.ui.screens.BrowseScreen
import app.prodl.ui.screens.ConvertScreen
import app.prodl.ui.screens.DiscoverScreen
import app.prodl.ui.screens.DownloadsScreen
import app.prodl.ui.screens.SettingsScreen
import app.prodl.ui.screens.WatchScreen
import app.prodl.ui.theme.ProDLColors

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val TABS = listOf(
    Tab("discover", "Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
    Tab("downloads", "Downloads", Icons.Outlined.Download, Icons.Filled.Download),
    Tab("convert", "Convert", Icons.Outlined.SwapHoriz, Icons.Filled.SwapHoriz),
    Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/** Build a ViewModel with the Application's engines; no DI framework needed. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (ProDLApplication) -> VM): VM {
    val app = LocalContext.current.applicationContext as ProDLApplication
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = create(app) as T
        },
    )
}

@Composable
fun ProDLApp() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    val downloads: DownloadsViewModel = appViewModel { DownloadsViewModel(it) }

    val showBar = TABS.any { it.route == route }

    Scaffold(
        containerColor = ProDLColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = ProDLColors.Card) {
                    TABS.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ProDLColors.Text,
                                selectedTextColor = ProDLColors.Text,
                                indicatorColor = ProDLColors.PrimaryDim,
                                unselectedIconColor = ProDLColors.TextDim,
                                unselectedTextColor = ProDLColors.TextDim,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "discover",
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
        ) {
            composable("discover") {
                val vm: DiscoverViewModel = appViewModel { DiscoverViewModel(it) }
                DiscoverScreen(
                    viewModel = vm,
                    onOpenVideo = { url -> nav.navigate("watch?url=${Uri.encode(url)}") },
                    onOpenBrowse = { url, kind ->
                        nav.navigate("browse?url=${Uri.encode(url)}&kind=$kind")
                    },
                    contentPadding = padding,
                )
            }

            composable("downloads") {
                DownloadsScreen(viewModel = downloads, snackbar = snackbar, contentPadding = padding)
            }

            composable("convert") {
                val vm: ConvertViewModel = appViewModel { ConvertViewModel(it) }
                ConvertScreen(
                    viewModel = vm,
                    downloads = downloads,
                    snackbar = snackbar,
                    contentPadding = padding,
                )
            }

            composable("settings") {
                val vm: SettingsViewModel = appViewModel { SettingsViewModel(it) }
                SettingsScreen(viewModel = vm, contentPadding = padding)
            }

            composable(
                route = "watch?url={url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType }),
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                val vm: WatchViewModel = appViewModel { WatchViewModel(it) }
                WatchScreen(
                    viewModel = vm,
                    url = url,
                    onOpenVideo = { next ->
                        // Same screen, new video: replace so Back returns to Discover.
                        nav.navigate("watch?url=${Uri.encode(next)}") {
                            popUpTo("watch?url={url}") { inclusive = true }
                        }
                    },
                    onStartDownload = { request ->
                        downloads.startDownload(request)
                            .onSuccess {
                                snackbar.currentSnackbarData?.dismiss()
                            }
                            .onFailure { failure ->
                                // Surfaced on the Downloads tab, which owns the cards.
                                nav.navigate("downloads") { launchSingleTop = true }
                            }
                    },
                    contentPadding = padding,
                )
            }

            composable(
                route = "browse?url={url}&kind={kind}",
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("kind") { type = NavType.StringType; defaultValue = "channel" },
                ),
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                val kind = entry.arguments?.getString("kind") ?: "channel"
                val vm: BrowseViewModel = appViewModel { BrowseViewModel(it) }
                BrowseScreen(
                    viewModel = vm,
                    url = url,
                    kind = kind,
                    onOpenVideo = { next -> nav.navigate("watch?url=${Uri.encode(next)}") },
                    contentPadding = padding,
                )
            }
        }
    }
}
