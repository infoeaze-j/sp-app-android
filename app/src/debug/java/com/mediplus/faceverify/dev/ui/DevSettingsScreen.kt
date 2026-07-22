package com.mediplus.faceverify.dev.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediplus.faceverify.dev.AuthScenario
import com.mediplus.faceverify.dev.CardScenario
import com.mediplus.faceverify.dev.CameraScenario
import com.mediplus.faceverify.dev.CurrencyScenario
import com.mediplus.faceverify.dev.DevSettings
import com.mediplus.faceverify.dev.EnrollScenario
import com.mediplus.faceverify.dev.FaceScenario
import com.mediplus.faceverify.dev.MemberScenario
import com.mediplus.faceverify.dev.ServicesScenario

/** Debug scenario picker. Stateless: hoists all state from [settings] and reports edits via callbacks. */
@Composable
fun DevSettingsScreen(
    settings: DevSettings,
    onFakeEnabled: (Boolean) -> Unit,
    onAuth: (AuthScenario) -> Unit,
    onCard: (CardScenario) -> Unit,
    onCamera: (CameraScenario) -> Unit,
    onMember: (MemberScenario) -> Unit,
    onFace: (FaceScenario) -> Unit,
    onServices: (ServicesScenario) -> Unit,
    onCurrency: (CurrencyScenario) -> Unit,
    onEnroll: (EnrollScenario) -> Unit,
    onLatency: (Long) -> Unit,
    onForceExpire: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Fake Back Office", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Fake backend enabled", modifier = Modifier.weight(1f))
            Switch(checked = settings.fakeEnabled, onCheckedChange = onFakeEnabled)
        }

        HorizontalDivider()

        ScenarioPicker("Auth (login)", AuthScenario.entries, settings.auth, onAuth)
        ScenarioPicker("Card tap (emulated NFC)", CardScenario.entries, settings.card, onCard)
        ScenarioPicker("Camera (emulated)", CameraScenario.entries, settings.camera, onCamera)
        ScenarioPicker("Member verify", MemberScenario.entries, settings.member, onMember)
        ScenarioPicker("Face verify", FaceScenario.entries, settings.face, onFace)
        ScenarioPicker("Services list", ServicesScenario.entries, settings.services, onServices)
        ScenarioPicker("Currencies", CurrencyScenario.entries, settings.currency, onCurrency)
        ScenarioPicker("Enrollment", EnrollScenario.entries, settings.enroll, onEnroll)

        HorizontalDivider()

        Text("Latency: ${settings.latencyMillis} ms")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0L, 500L, 1500L).forEach { preset ->
                OutlinedButton(onClick = { onLatency(preset) }) { Text("$preset") }
            }
        }

        HorizontalDivider()

        Button(onClick = onForceExpire, modifier = Modifier.fillMaxWidth()) {
            Text("Force session expired")
        }
    }
}

@Composable
private fun <T : Enum<T>> ScenarioPicker(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }) { Text(selected.name) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
