package com.mediplus.spapp.core.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

/**
 * On-device capture-quality guidance ONLY (FR-016, Decision 2). ML Kit tells us whether exactly one
 * reasonably-sized, roughly-frontal face is present so we don't submit an unusable frame. It never
 * performs matching or liveness — those are the server's authoritative decision.
 */
enum class FramingGuidance { GOOD, NO_FACE, MULTIPLE_FACES, FACE_TOO_SMALL, POOR_POSE }

class FaceFramingAnalyzer(
    private val onGuidance: (FramingGuidance) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                onGuidance(evaluate(faces, minOf(input.width, input.height)))
            }
            .addOnCompleteListener { imageProxy.close() } // always release the frame promptly (Principle IV)
    }

    /** Pure framing decision, separated for clarity and testability. */
    fun evaluate(faces: List<Face>, minImageDimension: Int): FramingGuidance = when {
        faces.isEmpty() -> FramingGuidance.NO_FACE
        faces.size > 1 -> FramingGuidance.MULTIPLE_FACES
        else -> singleFaceGuidance(faces.first(), minImageDimension)
    }

    private fun singleFaceGuidance(face: Face, minImageDimension: Int): FramingGuidance {
        val faceSize = minOf(face.boundingBox.width(), face.boundingBox.height())
        if (minImageDimension > 0 && faceSize < minImageDimension * MIN_FACE_RATIO) {
            return FramingGuidance.FACE_TOO_SMALL
        }
        val turned = abs(face.headEulerAngleY) > MAX_YAW_DEGREES || abs(face.headEulerAngleZ) > MAX_ROLL_DEGREES
        return if (turned) FramingGuidance.POOR_POSE else FramingGuidance.GOOD
    }

    private companion object {
        const val MIN_FACE_RATIO = 0.30f
        const val MAX_YAW_DEGREES = 20f
        const val MAX_ROLL_DEGREES = 20f
    }
}
