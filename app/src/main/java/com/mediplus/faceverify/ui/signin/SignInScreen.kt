package com.mediplus.faceverify.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.faceverify.R
import com.mediplus.faceverify.core.ui.theme.LocalSpacing

/**
 * US1 sign-in destination. Owns credential entry, loading, non-revealing errors, sign-in lockout
 * messaging, and the "session ended" notice. On success, it hands control back to the nav graph.
 */
@Composable
fun SignInRoute(
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            viewModel.onNavigated()
            onSignedIn()
        }
    }

    SignInScreen(
        state = state,
        onIdentifierChange = viewModel::onIdentifierChange,
        onSecretChange = viewModel::onSecretChange,
        onSubmit = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
fun SignInScreen(
    state: SignInUiState,
    onIdentifierChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.signin_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.signin_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.lg),
        )

        if (state.sessionEndedNotice) {
            Text(
                text = stringResource(R.string.signin_session_ended),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        OutlinedTextField(
            value = state.identifier,
            onValueChange = onIdentifierChange,
            label = { Text(stringResource(R.string.signin_identifier_label)) },
            singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.secret,
            onValueChange = onSecretChange,
            label = { Text(stringResource(R.string.signin_secret_label)) },
            singleLine = true,
            enabled = !state.isLoading,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                val label = if (passwordVisible) R.string.signin_hide_password else R.string.signin_show_password
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(stringResource(label))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm),
        )

        val error = state.error
        if (error != null) {
            Text(
                text = stringResource(error.bodyRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.lg)
                .heightIn(min = spacing.minTouchTarget),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = spacing.lg))
            } else {
                Text(stringResource(R.string.signin_submit))
            }
        }

        Text(
            text = stringResource(R.string.signin_version, state.versionName, state.versionCode),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.lg),
        )
    }
}
