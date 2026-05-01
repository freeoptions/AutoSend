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
                }
            }
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
