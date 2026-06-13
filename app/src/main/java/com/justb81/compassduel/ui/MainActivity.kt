package com.justb81.compassduel.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.justb81.compassduel.ui.navigation.CompassDuelNavGraph
import com.justb81.compassduel.ui.theme.CompassDuelTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single-Activity host. Screens are rendered as Compose composables. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompassDuelTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    CompassDuelNavGraph(navController = navController)
                }
            }
        }
    }
}
