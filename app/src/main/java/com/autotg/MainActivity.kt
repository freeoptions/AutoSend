package com.autotg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autotg.ui.screens.ConfigScreen
import com.autotg.ui.screens.MainScreen
import com.autotg.ui.viewmodels.MainViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.autotg.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AutoTGApp()
                    PermissionCheck()
                }
            }
        }
    }
}

@Composable
fun PermissionCheck() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showBatteryDialog by remember { mutableStateOf(!PermissionUtils.isBatteryOptimizationIgnored(context)) }
    var showAutostartDialog by remember { mutableStateOf(PermissionUtils.isXiaomi()) } // Simple check for Xiaomi

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("Battery Optimization") },
            text = { Text("To ensure reliable scheduled messages, please set battery optimization to 'Unrestricted'.") },
            confirmButton = {
                TextButton(onClick = {
                    PermissionUtils.requestIgnoreBatteryOptimizations(context)
                    showBatteryDialog = false
                }) {
                    Text("Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text("Later")
                }
            }
        )
    } else if (showAutostartDialog) {
        // Only show if Xiaomi and not ignored yet (using a simple state for now, ideally persist this choice)
        var dismissedAutostart by remember { mutableStateOf(false) }
        if (!dismissedAutostart) {
            AlertDialog(
                onDismissRequest = { dismissedAutostart = true },
                title = { Text("Xiaomi Optimization") },
                text = { Text("On MIUI/HyperOS, please enable 'Autostart' for AutoTG to work in the background.") },
                confirmButton = {
                    TextButton(onClick = {
                        PermissionUtils.openXiaomiAutostartSettings(context)
                        dismissedAutostart = true
                        showAutostartDialog = false
                    }) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dismissedAutostart = true }) {
                        Text("Dismiss")
                    }
                }
            )
        }
    }
}

@Composable
fun AutoTGApp() {
    var currentScreen by remember { mutableStateOf(Screen.Main) }
    val mainViewModel: MainViewModel = viewModel()

    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            Screen.Main -> MainScreen(
                viewModel = mainViewModel,
                onNavigateToConfig = { currentScreen = Screen.Config }
            )
            Screen.Config -> ConfigScreen(
                onNavigateBack = { currentScreen = Screen.Main }
            )
        }
    }
}

enum class Screen {
    Main, Config
}
