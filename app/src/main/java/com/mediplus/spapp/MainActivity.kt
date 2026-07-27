package com.mediplus.spapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mediplus.spapp.core.ui.theme.SpAppTheme
import com.mediplus.spapp.ui.navigation.NavGraph
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-Activity Compose host. The sequential verification journey (sign in → NFC → face →
 * add service) is driven by [NavGraph]; this Activity only owns window setup and the theme.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavGraph()
                }
            }
        }
    }
}
