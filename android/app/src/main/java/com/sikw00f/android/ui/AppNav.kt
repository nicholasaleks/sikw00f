package com.sikw00f.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("connect", "Connect", Icons.Filled.Cable),
    Tab("scan", "Scan", Icons.Filled.Radar),
    Tab("settings", "Settings", Icons.Filled.Settings),
    Tab("logs", "Logs", Icons.Filled.List)
)

@Composable
fun AppNav(viewModel: AppViewModel) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                TABS.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "connect",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("connect") { ConnectScreen(viewModel) }
            composable("scan") {
                ScanScreen(viewModel) { netId -> navController.navigate("drone/$netId") }
            }
            composable(
                "drone/{netId}",
                arguments = listOf(navArgument("netId") { type = NavType.IntType })
            ) { backStackEntry ->
                val netId = backStackEntry.arguments?.getInt("netId") ?: 0
                DroneDetailScreen(
                    netId = netId,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") { SettingsScreen(viewModel) }
            composable("logs") { LogScreen(onOpenSession = { id -> navController.navigate("session/$id") }) }
            composable(
                "session/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                SessionDetailScreen(
                    id = backStackEntry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
