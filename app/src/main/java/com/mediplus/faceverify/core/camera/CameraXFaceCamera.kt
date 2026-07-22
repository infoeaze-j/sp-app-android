package com.mediplus.faceverify.core.camera

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

    private var imageCapture: ImageCapture? = null
    private var analysisExecutor: ExecutorService? = null

    override suspend fun isAvailable(): CameraAvailability =
        runCatching { cameraProvider().hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }
            .getOrDefault(false)
            .let { if (it) CameraAvailability.AVAILABLE else CameraAvailability.NO_CAMERA }

    override fun createPreviewView(context: Context): View = PreviewView(context)

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    ) {
        val surface = previewView as PreviewView
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(surface.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(executor, FaceFramingAnalyzer(onGuidance)) }
            val capture = ImageCapture.Builder().build()
            imageCapture = capture

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
                capture,
            )
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
                        cont.resume(TransientFrame(bytes))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resume(null)
                    }
                },
            )
        }
    }

    override fun release() {
        analysisExecutor?.shutdown()
        analysisExecutor = null
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
