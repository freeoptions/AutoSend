package com.autosend

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autosend.ui.screens.ConfigScreen
import com.autosend.ui.screens.LogScreen
import com.autosend.ui.screens.MainScreen
import com.autosend.ui.viewmodels.MainViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import com.autosend.utils.PermissionUtils
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
                    AutoSendApp()
                    PermissionCheck()
                }
            }
        }
    }
}

@Composable
fun PermissionCheck() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var showExactAlarmDialog by remember { mutableStateOf(!PermissionUtils.canScheduleExactAlarms(context)) }
    var showBatteryDialog by remember { mutableStateOf(!PermissionUtils.isBatteryOptimizationIgnored(context)) }
    var showNotificationDialog by remember { mutableStateOf(!PermissionUtils.areNotificationsEnabled(context)) }
    var showAutostartDialog by remember {
        mutableStateOf(PermissionUtils.isXiaomi() && !PermissionUtils.isXiaomiAutostartEnabled(context))
    }

    if (showExactAlarmDialog) {
        AlertDialog(
            onDismissRequest = { showExactAlarmDialog = false },
            title = { Text("精确闹钟权限") },
            text = { Text("为了让 0 点这类定时任务准点触发，请允许 AutoSend 设置精确闹钟。否则系统可能会延后发送，只能走后台兜底。") },
            confirmButton = {
                TextButton(onClick = {
                    PermissionUtils.openExactAlarmSettings(context)
                    showExactAlarmDialog = false
                }) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExactAlarmDialog = false }) {
                    Text("稍后")
                }
            }
        )
    } else if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("电池优化设置") },
            text = { Text("为了确保定时消息能准时发送，请将 AutoSend 的电池优化设置为“无限制”。") },
            confirmButton = {
                TextButton(onClick = {
                    PermissionUtils.requestIgnoreBatteryOptimizations(context)
                    showBatteryDialog = false
                }) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text("稍后")
                }
            }
        )
    } else if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationDialog = false },
            title = { Text("任务执行通知权限") },
            text = { Text("AutoSend 到点发送时会短暂显示任务执行通知，并在完成后立即移除。建议允许通知，便于确认短时服务正在工作。") },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        PermissionUtils.openNotificationSettings(context)
                    }
                    showNotificationDialog = false
                }) {
                    Text("去开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationDialog = false }) {
                    Text("稍后")
                }
            }
        )
    } else if (showAutostartDialog) {
        AlertDialog(
            onDismissRequest = { showAutostartDialog = false },
            title = { Text("小米/澎湃系统优化") },
            text = { Text("在小米手机上，请开启“自启动”权限，以允许 AutoSend 在后台运行。") },
            confirmButton = {
                TextButton(onClick = {
                    PermissionUtils.openXiaomiAutostartSettings(context)
                    showAutostartDialog = false
                }) {
                    Text("去开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutostartDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
fun AutoSendApp() {
    var currentScreen by remember { mutableStateOf(Screen.Main) }
    val mainViewModel: MainViewModel = viewModel()

    // Handle system back gesture
    BackHandler(enabled = currentScreen != Screen.Main) {
        currentScreen = Screen.Main
    }

    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            Screen.Main -> MainScreen(
                viewModel = mainViewModel,
                onNavigateToConfig = { currentScreen = Screen.Config },
                onNavigateToLogs = { currentScreen = Screen.Logs }
            )
            Screen.Config -> ConfigScreen(
                onNavigateBack = { currentScreen = Screen.Main }
            )
            Screen.Logs -> LogScreen(
                viewModel = mainViewModel,
                onNavigateBack = { currentScreen = Screen.Main }
            )
        }
    }
}

enum class Screen {
    Main, Config, Logs
}
