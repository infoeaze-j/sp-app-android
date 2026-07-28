package com.mediplus.spapp.dev.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.MaterialTheme
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
import com.mediplus.spapp.dev.AuthScenario
import com.mediplus.spapp.dev.CardScenario
import com.mediplus.spapp.dev.CameraScenario
import com.mediplus.spapp.dev.CurrencyScenario
import com.mediplus.spapp.dev.DevSettings
import com.mediplus.spapp.dev.DiagnosticsScenario
import com.mediplus.spapp.dev.EnrollScenario
import com.mediplus.spapp.dev.FaceScenario
import com.mediplus.spapp.dev.FakeSeam
import com.mediplus.spapp.dev.MemberScenario
import com.mediplus.spapp.dev.ServicesScenario
import com.mediplus.spapp.dev.UpdateScenario

/**
 * Debug scenario picker. Stateless: hoists all state from [settings] and reports edits via callbacks.
 *
 * Two levels of control — the master toggle sends everything to the real backend, and below it each
 * [FakeSeam] has its own toggle so one seam can run real while the rest stay faked.
 */
@Composable
fun DevSettingsScreen(
    settings: DevSettings,
    onFakeEnabled: (Boolean) -> Unit,
    onFakeSeam: (FakeSeam, Boolean) -> Unit,
    onAuth: (AuthScenario) -> Unit,
    onCard: (CardScenario) -> Unit,
    onCamera: (CameraScenario) -> Unit,
    onMember: (MemberScenario) -> Unit,
    onFace: (FaceScenario) -> Unit,
    onServices: (ServicesScenario) -> Unit,
    onCurrency: (CurrencyScenario) -> Unit,
    onEnroll: (EnrollScenario) -> Unit,
    onUpdate: (UpdateScenario) -> Unit,
    onDiagnostics: (DiagnosticsScenario) -> Unit,
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
        MasterToggle(settings.fakeEnabled, onFakeEnabled)

        Seam(settings, FakeSeam.AUTH, "Auth (login)", onFakeSeam) {
            ScenarioPicker("Scenario", AuthScenario.entries, settings.auth, onAuth)
        }
        Seam(settings, FakeSeam.DEVICE, "Device registration (X-Device-Id)", onFakeSeam)
        Seam(settings, FakeSeam.CARD, "Card tap (emulated NFC)", onFakeSeam) {
            ScenarioPicker("Scenario", CardScenario.entries, settings.card, onCard)
        }
        Seam(settings, FakeSeam.CAMERA, "Camera (emulated)", onFakeSeam) {
            ScenarioPicker("Scenario", CameraScenario.entries, settings.camera, onCamera)
            if (settings.isFakeActive(FakeSeam.CAMERA) && !settings.isFakeActive(FakeSeam.FACE)) {
                Warning("Synthetic frames would reach the real /face/verifications.")
            }
        }
        Seam(settings, FakeSeam.MEMBER, "Member verify", onFakeSeam) {
            ScenarioPicker("Scenario", MemberScenario.entries, settings.member, onMember)
        }
        Seam(settings, FakeSeam.FACE, "Face verify", onFakeSeam) {
            ScenarioPicker("Scenario", FaceScenario.entries, settings.face, onFace)
        }
        Seam(settings, FakeSeam.ENROLLMENT, "Services & enrollment", onFakeSeam) {
            ScenarioPicker("Services list", ServicesScenario.entries, settings.services, onServices)
            ScenarioPicker("Currencies", CurrencyScenario.entries, settings.currency, onCurrency)
            ScenarioPicker("Enrollment", EnrollScenario.entries, settings.enroll, onEnroll)
        }
        Seam(settings, FakeSeam.UPDATE, "Self-update (check, download, install)", onFakeSeam) {
            ScenarioPicker("Scenario", UpdateScenario.entries, settings.update, onUpdate)
        }
        Seam(settings, FakeSeam.DIAGNOSTICS, "Diagnostics telemetry (poll/report)", onFakeSeam) {
            ScenarioPicker("Scenario", DiagnosticsScenario.entries, settings.diagnostics, onDiagnostics)
        }
        Seam(settings, FakeSeam.DEVICE_STATE, "Device state snapshot (sensors)", onFakeSeam)

        DevActions(settings.latencyMillis, onLatency, onForceExpire)
    }
}

/** The master kill switch. Off routes every seam to the real backend, whatever its own toggle says. */
@Composable
private fun MasterToggle(fakeEnabled: Boolean, onFakeEnabled: (Boolean) -> Unit) {
    Text("Fake Back Office", style = MaterialTheme.typography.headlineSmall)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Fake backend enabled")
            Text(
                text = "Master switch — off sends every seam to the real backend.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Switch(checked = fakeEnabled, onCheckedChange = onFakeEnabled)
    }
}

/**
 * One switchable seam: its own toggle plus whatever scenario pickers it governs. The toggle is
 * greyed out while the master switch is off, because it cannot take effect then — the "real" tag
 * next to it always reports where calls actually go.
 */
@Composable
private fun Seam(
    settings: DevSettings,
    seam: FakeSeam,
    label: String,
    onFakeSeam: (FakeSeam, Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    HorizontalDivider()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = if (settings.isFakeActive(seam)) "fake" else "real",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            Switch(
                checked = settings.fakeSeams[seam] != false,
                onCheckedChange = { onFakeSeam(seam, it) },
                enabled = settings.fakeEnabled,
            )
        }
        content()
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun DevActions(latencyMillis: Long, onLatency: (Long) -> Unit, onForceExpire: () -> Unit) {
    HorizontalDivider()

    Text("Latency: $latencyMillis ms")
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

@Composable
private fun <T : Enum<T>> ScenarioPicker(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
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
