package com.mediplus.spapp.dev.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.spapp.core.ui.theme.SpAppTheme
import dagger.hilt.android.AndroidEntryPoint

/** Debug-only second launcher: edit the fake back-office scenarios, then return to the app. */
@AndroidEntryPoint
class DevSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: DevSettingsViewModel = hiltViewModel()
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    DevSettingsScreen(
                        settings = settings,
                        onFakeEnabled = vm::setFakeEnabled,
                        onFakeSeam = vm::setFakeSeam,
                        onAuth = vm::setAuth,
                        onCard = vm::setCard,
                        onCamera = vm::setCamera,
                        onMember = vm::setMember,
                        onFace = vm::setFace,
                        onServices = vm::setServices,
                        onCurrency = vm::setCurrency,
                        onEnroll = vm::setEnroll,
                        onUpdate = vm::setUpdate,
                        onDiagnostics = vm::setDiagnostics,
                        onLatency = vm::setLatencyMillis,
                        onForceExpire = vm::forceSessionExpired,
                    )
                }
            }
        }
    }
}
