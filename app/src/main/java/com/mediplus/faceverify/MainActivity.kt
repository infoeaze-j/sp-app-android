package com.mediplus.faceverify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mediplus.faceverify.core.ui.theme.FaceVerifyTheme
import com.mediplus.faceverify.ui.navigation.NavGraph
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
            FaceVerifyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavGraph()
                }
            }
        }
    }
}
