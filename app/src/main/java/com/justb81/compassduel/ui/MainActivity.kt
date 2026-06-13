package com.justb81.compassduel.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.justb81.compassduel.data.preferences.ThemePreference
import com.justb81.compassduel.ui.navigation.CompassDuelNavGraph
import com.justb81.compassduel.ui.settings.SettingsViewModel
import com.justb81.compassduel.ui.theme.CompassDuelTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single-Activity host. Screens are rendered as Compose composables. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val themePreference by settingsViewModel.themePreference.collectAsStateWithLifecycle()
            // Resolve SYSTEM here: isSystemInDarkTheme() is @Composable and cannot live in the VM.
            val useDarkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            CompassDuelTheme(useDarkTheme = useDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    CompassDuelNavGraph(navController = navController)
                }
            }
        }
    }
}
