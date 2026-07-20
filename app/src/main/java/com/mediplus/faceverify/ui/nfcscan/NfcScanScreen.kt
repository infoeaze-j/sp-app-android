package com.mediplus.faceverify.ui.nfcscan

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.faceverify.R
import com.mediplus.faceverify.core.nfc.AccessKeyDeriver
import com.mediplus.faceverify.core.ui.components.ErrorState
import com.mediplus.faceverify.core.ui.components.LoadingState
import com.mediplus.faceverify.core.ui.theme.LocalSpacing
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.NfcAvailability

/**
 * US2 NFC destination. Handles NFC-unavailable/disabled messaging, access-key entry, the live
 * chip read (via NFC reader mode), and the identity-confirmation step. On verification it advances.
 */
@Composable
fun NfcScanRoute(
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NfcScanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val deriver = remember { AccessKeyDeriver() }

    androidx.compose.runtime.LaunchedEffect(state.phase) {
        if (state.phase is NfcPhase.Verified) onVerified()
    }

    // Enable NFC reader mode only while we're actually waiting for a tap.
    DisposableEffect(state.phase, activity) {
        val adapter = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        val scanning = state.phase == NfcPhase.ReadyToScan
        if (activity != null && adapter != null && scanning) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            adapter.enableReaderMode(activity, { tag -> viewModel.onTagDiscovered(tag) }, flags, null)
        }
        onDispose {
            if (activity != null && adapter != null) adapter.disableReaderMode(activity)
        }
    }

    NfcScanScreen(
        state = state,
        onManualKey = { number, dob, expiry ->
            viewModel.setAccessKey(deriver.fromManualEntry(number, dob, expiry))
        },
        onConfirm = viewModel::onConfirm,
        onRetry = viewModel::retry,
        onOpenSettings = {
            activity?.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        },
        modifier = modifier,
    )
}

@Composable
fun NfcScanScreen(
    state: NfcScanUiState,
    onManualKey: (String, String, String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        NfcPhase.CheckingAvailability -> LoadingState(modifier = modifier)
        NfcPhase.Reading -> LoadingState(messageRes = R.string.nfc_reading, modifier = modifier)
        NfcPhase.Validating -> LoadingState(messageRes = R.string.nfc_validating, modifier = modifier)
        NfcPhase.Verified -> LoadingState(modifier = modifier)
        is NfcPhase.Unavailable -> UnavailableContent(phase.availability, onOpenSettings, modifier)
        NfcPhase.NeedsAccessKey -> AccessKeyEntry(onManualKey, modifier)
        NfcPhase.ReadyToScan -> ReadyToScanContent(modifier)
        is NfcPhase.Confirm -> ConfirmContent(phase.identity, onConfirm, modifier)
        is NfcPhase.Failed -> ErrorState(message = phase.message, onAction = onRetry, modifier = modifier)
    }
}

@Composable
private fun UnavailableContent(
    availability: NfcAvailability,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val messageRes = if (availability == NfcAvailability.DISABLED) {
            R.string.nfc_unavailable_disabled
        } else {
            R.string.nfc_unavailable_none
        }
        Text(stringResource(R.string.nfc_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        if (availability == NfcAvailability.DISABLED) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.action_open_settings)) }
        }
    }
}

@Composable
private fun AccessKeyEntry(
    onManualKey: (String, String, String) -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    var number by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.nfc_needs_key_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.nfc_needs_key_desc),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text(stringResource(R.string.nfc_doc_number_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = dob,
            onValueChange = { dob = it.filter(Char::isDigit).take(6) },
            label = { Text(stringResource(R.string.nfc_dob_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        )
        OutlinedTextField(
            value = expiry,
            onValueChange = { expiry = it.filter(Char::isDigit).take(6) },
            label = { Text(stringResource(R.string.nfc_expiry_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        )
        Button(
            onClick = { onManualKey(number, dob, expiry) },
            enabled = number.isNotBlank() && dob.length == 6 && expiry.length == 6,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.nfc_ready_button)) }
    }
}

@Composable
private fun ReadyToScanContent(modifier: Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.nfc_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.nfc_prompt_tap),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.md),
        )
    }
}

@Composable
private fun ConfirmContent(
    identity: DocumentIdentity,
    onConfirm: () -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.lg),
    ) {
        Text(stringResource(R.string.nfc_confirm_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.nfc_confirm_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
        Field(R.string.nfc_field_name, "${identity.surname}, ${identity.givenNames}")
        Field(R.string.nfc_field_number, identity.documentNumber)
        Field(R.string.nfc_field_dob, identity.dateOfBirth)
        Field(R.string.nfc_field_nationality, identity.nationality)
        Field(R.string.nfc_field_sex, identity.sex)
        Field(R.string.nfc_field_expiry, identity.expiryDate.toString())
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.nfc_confirm_button)) }
    }
}

@Composable
private fun Field(labelRes: Int, value: String) {
    val spacing = LocalSpacing.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(FIELD_LABEL_WEIGHT),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val FIELD_LABEL_WEIGHT = 0.4f
