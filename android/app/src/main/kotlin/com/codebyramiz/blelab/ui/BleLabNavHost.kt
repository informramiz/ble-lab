package com.codebyramiz.blelab.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codebyramiz.blelab.ui.device.DeviceRoute
import com.codebyramiz.blelab.ui.scan.ScanRoute
import kotlinx.serialization.Serializable

@Serializable
data object ScanDestination

/** Type-safe route; [DeviceViewModel][com.codebyramiz.blelab.ui.device.DeviceViewModel] reads the args from SavedStateHandle. */
@Serializable
data class DeviceDestination(val address: String, val name: String?)

@Composable
fun BleLabNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ScanDestination) {
        composable<ScanDestination> {
            ScanRoute(onOpenDevice = { device -> navController.navigate(DeviceDestination(device.address, device.name)) })
        }
        composable<DeviceDestination> {
            DeviceRoute(onBack = { navController.popBackStack() })
        }
    }
}
