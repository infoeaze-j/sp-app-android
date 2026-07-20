package com.mediplus.faceverify.core.camera

import android.content.Context
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
import java.util.concurrent.Executor

/**
 * Binds the CameraX use cases (preview, framing analysis, still capture) to a lifecycle and captures
 * a single live frame into a [TransientFrame]. Lifecycle-bound so the camera is released
 * automatically; every [ImageProxy] is closed promptly to keep memory bounded (Principle IV).
 */
class CameraController(private val context: Context) {

    private var imageCapture: ImageCapture? = null

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        analysisExecutor: Executor,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, analyzer) }
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

    /** Capture one frame. The bytes live only in the returned [TransientFrame] (FR-017). */
    fun capture(executor: Executor, onResult: (TransientFrame?) -> Unit) {
        val capture = imageCapture ?: run {
            onResult(null)
            return
        }
        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = image.toBytes()
                    image.close()
                    onResult(TransientFrame(bytes))
                }

                override fun onError(exception: ImageCaptureException) {
                    onResult(null)
                }
            },
        )
    }
}

private fun ImageProxy.toBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}
