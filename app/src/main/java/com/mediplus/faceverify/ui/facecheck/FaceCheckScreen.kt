package com.mediplus.faceverify.ui.facecheck

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.faceverify.R
import com.mediplus.faceverify.core.camera.CameraController
import com.mediplus.faceverify.core.camera.FaceFramingAnalyzer
import com.mediplus.faceverify.core.camera.FramingGuidance
import com.mediplus.faceverify.core.ui.components.ErrorState
import com.mediplus.faceverify.core.ui.components.LoadingState
import com.mediplus.faceverify.core.ui.components.PermissionDeniedState
import com.mediplus.faceverify.core.ui.theme.LocalSpacing
import java.util.concurrent.Executors

/**
 * US3 face-check destination. Gates on patient consent, then captures a live frame with on-device
 * framing guidance and submits it for the authoritative decision. Handles the consent-withheld halt,
 * the same-subject discrepancy halt, no-match/spoof messaging, and the server lockout with cooldown.
 */
@Composable
fun FaceCheckRoute(
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FaceCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(state.phase) {
        if (state.phase is FacePhase.Verified) onVerified()
    }

    FaceCheckScreen(
        state = state,
        onConsent = viewModel::onConsent,
        onGuidance = viewModel::onGuidance,
        onCapture = viewModel::onFrameCaptured,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@Composable
fun FaceCheckScreen(
    state: FaceCheckUiState,
    onConsent: (Boolean) -> Unit,
    onGuidance: (FramingGuidance) -> Unit,
    onCapture: (com.mediplus.faceverify.core.camera.TransientFrame) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        FacePhase.ConsentPrompt -> ConsentContent(onConsent, modifier)
        FacePhase.ConsentWithheldHalt -> TerminalMessage(
            R.string.face_consent_withheld_title,
            R.string.face_consent_withheld_body,
            modifier,
        )
        FacePhase.DiscrepancyHalt -> TerminalMessage(
            R.string.face_discrepancy_title,
            R.string.face_discrepancy_body,
            modifier,
        )
        is FacePhase.Capturing -> CaptureContent(phase, onGuidance, onCapture, modifier)
        FacePhase.Submitting -> LoadingState(messageRes = R.string.face_submitting, modifier = modifier)
        FacePhase.Verified -> LoadingState(modifier = modifier)
        is FacePhase.Failed -> ErrorState(
            message = phase.message,
            onAction = if (phase.canRetry) onRetry else null,
            modifier = modifier,
        )
    }
}

@Composable
private fun ConsentContent(onConsent: (Boolean) -> Unit, modifier: Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.consent_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = spacing.md),
        )
        Button(
            onClick = { onConsent(true) },
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.consent_grant)) }
        OutlinedButton(
            onClick = { onConsent(false) },
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.consent_decline)) }
    }
}

@Composable
private fun CaptureContent(
    phase: FacePhase.Capturing,
    onGuidance: (FramingGuidance) -> Unit,
    onCapture: (com.mediplus.faceverify.core.camera.TransientFrame) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        PermissionDeniedState(
            rationaleRes = R.string.camera_permission_rationale,
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { context.openAppSettings() },
            modifier = modifier,
        )
        return
    }

    CameraCapture(phase, onGuidance, onCapture, modifier)
}

@Composable
private fun CameraCapture(
    phase: FacePhase.Capturing,
    onGuidance: (FramingGuidance) -> Unit,
    onCapture: (com.mediplus.faceverify.core.camera.TransientFrame) -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { CameraController(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        controller.bind(lifecycleOwner, previewView, FaceFramingAnalyzer(onGuidance), analysisExecutor)
        onDispose { analysisExecutor.shutdown() }
    }

    Column(modifier = modifier.fillMaxSize().padding(spacing.md)) {
        val previewDescription = stringResource(R.string.face_camera_preview_desc)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = previewDescription },
            )
        }
        Text(
            text = stringResource(phase.guidance.messageRes()),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(
            onClick = {
                controller.capture(ContextCompat.getMainExecutor(context)) { frame ->
                    if (frame != null) onCapture(frame)
                }
            },
            enabled = phase.canCapture,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.face_capture_button)) }
    }
}

@Composable
private fun TerminalMessage(titleRes: Int, bodyRes: Int, modifier: Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg).semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}

private fun FramingGuidance.messageRes(): Int = when (this) {
    FramingGuidance.GOOD -> R.string.face_framing_good
    FramingGuidance.NO_FACE -> R.string.face_framing_no_face
    FramingGuidance.MULTIPLE_FACES -> R.string.face_framing_multiple
    FramingGuidance.FACE_TOO_SMALL -> R.string.face_framing_too_small
    FramingGuidance.POOR_POSE -> R.string.face_framing_pose
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}
