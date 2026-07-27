package com.mediplus.spapp.core.camera

import android.content.Context
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real CameraX camera: preview, framing analysis, and single-frame capture bound to a
 * lifecycle so the hardware is released automatically. Every [ImageProxy] is closed promptly to
 * keep memory bounded (Principle IV).
 */
class CameraXFaceCamera @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FaceCamera {

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var analysisExecutor: ExecutorService? = null

    // Guards the async provider-resolution listener in bind() against a release() that runs before
    // the listener fires (e.g. the screen is torn down mid-resolve). Set true by release(), cleared
    // at the start of bind(); the listener checks it first, so a release() that lands between the
    // two never leaves a binding nothing will tear down.
    //
    // This is a single flag, not a per-bind generation, so it does NOT make a stale listener safe
    // across bind() -> release() -> bind() while the first future is still pending: the second
    // bind() clears the flag and the first listener would proceed. Callers bind once per instance
    // (the screen takes a fresh camera from the factory on each entry), so that sequence does not
    // arise; add a generation counter if that ever changes.
    //
    // @Volatile for visibility only. Both callers run on the main thread today, and CameraX's own
    // unbind() asserts that, so this closes an ordering hazard rather than a threading one.
    @Volatile
    private var released = true

    override suspend fun isAvailable(): CameraAvailability = try {
        val hasCamera = cameraProvider().hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        if (hasCamera) CameraAvailability.AVAILABLE else CameraAvailability.NO_CAMERA
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CameraAvailability.NO_CAMERA
    }

    override fun createPreviewView(context: Context): View = PreviewView(context)

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    ) {
        // Re-entrant-safe (Fix E): release any prior binding before starting a new one, matching
        // FakeFaceCamera's guarantee that a second bind() cannot leak the first one's resources.
        release()
        released = false

        val surface = previewView as PreviewView
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            // release() may have already run by the time this fires; don't establish a binding
            // that nothing will ever tear down.
            if (released) return@addListener

            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().apply {
                setSurfaceProvider(surface.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(executor, FaceFramingAnalyzer(onGuidance)) }
            val capture = ImageCapture.Builder().build()

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                previewUseCase,
                analysis,
                capture,
            )

            cameraProvider = provider
            preview = previewUseCase
            imageAnalysis = analysis
            imageCapture = capture
        }, ContextCompat.getMainExecutor(context))
    }

    override suspend fun capture(): TransientFrame? {
        val capture = imageCapture ?: return null
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = image.toBytes()
                        image.close()
                        // The coroutine may already be cancelled (e.g. the caller left
                        // composition mid-capture). When the frame can't be delivered to a
                        // consumer, onCancellation still zeroes it (FR-017).
                        val frame = TransientFrame(bytes)
                        cont.resume(frame) { _, _, _ -> frame.clear() }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resume(null)
                    }
                },
            )
        }
    }

    override fun release() {
        released = true
        val provider = cameraProvider
        val analysis = imageAnalysis
        if (provider != null) {
            analysis?.clearAnalyzer()
            provider.unbind(preview, analysis, imageCapture)
        }
        analysisExecutor?.shutdown()
        analysisExecutor = null
        cameraProvider = null
        preview = null
        imageAnalysis = null
        imageCapture = null
    }

    private suspend fun cameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
        }
}

/** Release builds always get the real camera. */
class RealFaceCameraFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FaceCameraFactory {
    override suspend fun create(): FaceCamera = CameraXFaceCamera(context)
}

private fun ImageProxy.toBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}
