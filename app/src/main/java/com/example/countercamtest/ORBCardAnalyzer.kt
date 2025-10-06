package com.example.countercamtest

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * Real-time ORB-based card detection analyzer for CameraX
 * Optimized for smooth performance and accurate detection
 */
class ORBCardAnalyzer(
    private val cardDetector: ORBCardDetector,
    private val onCardDetected: (ORBCardDetector.CardDetectionResult) -> Unit,
    private val overlayBounds: Rect? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "ORBCardAnalyzer"

        // Performance optimization parameters
        private const val ANALYSIS_INTERVAL_MS = 200L        // Analyze every 200ms for performance
        private const val MIN_CONFIDENCE_THRESHOLD = 0.75f   // Increased minimum confidence for detection (was 0.6f)
        private const val DETECTION_COOLDOWN_MS = 1000L      // Cooldown between successful detections
        private const val MAX_PROCESSING_TIME_MS = 500L      // Maximum processing time before timeout

        // Image preprocessing parameters
        private const val TARGET_WIDTH = 640                 // Resize for performance
        private const val TARGET_HEIGHT = 480
        private const val JPEG_QUALITY = 85                  // Balance between quality and performance
    }

    private var lastAnalysisTime = 0L
    private var lastDetectionTime = 0L
    private var isProcessing = false
    private var analysisEnabled = true

    // Coroutine scope for async processing
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // Skip analysis if disabled or too soon after last analysis
        if (!analysisEnabled || isProcessing ||
            (currentTime - lastAnalysisTime) < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        // Skip if in cooldown period after successful detection
        if ((currentTime - lastDetectionTime) < DETECTION_COOLDOWN_MS) {
            imageProxy.close()
            return
        }

        lastAnalysisTime = currentTime
        isProcessing = true

        // Process image asynchronously
        analysisScope.launch {
            try {
                val bitmap = convertImageProxyToBitmap(imageProxy)
                if (bitmap != null) {

                    // Process with timeout
                    val result = withTimeoutOrNull(MAX_PROCESSING_TIME_MS) {
                        cardDetector.detectCard(
                            inputBitmap = bitmap,
                            overlayBounds = overlayBounds,
                            imageSize = Size(imageProxy.width, imageProxy.height)
                        )
                    }

                    if (result != null) {
                        handleDetectionResult(result, currentTime)
                    } else {
                        Log.w(TAG, "Card detection timed out after ${MAX_PROCESSING_TIME_MS}ms")
                    }

                    bitmap.recycle()
                } else {
                    Log.w(TAG, "Failed to convert ImageProxy to Bitmap")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis error: ${e.message}")
            } finally {
                isProcessing = false
                imageProxy.close()
            }
        }
    }

    /**
     * Convert ImageProxy to Bitmap for OpenCV processing
     */
    @androidx.camera.core.ExperimentalGetImage
    private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null

            // Convert YUV_420_888 to RGB
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy Y plane
            yBuffer.get(nv21, 0, ySize)

            // Copy UV planes
            val uvPixelStride = image.planes[1].pixelStride
            if (uvPixelStride == 1) {
                uBuffer.get(nv21, ySize, uSize)
                vBuffer.get(nv21, ySize + uSize, vSize)
            } else {
                // Interleaved UV
                val uvBuffer = ByteArray(uSize + vSize)
                uBuffer.get(uvBuffer, 0, uSize)
                vBuffer.get(uvBuffer, uSize, vSize)

                var uvIndex = 0
                for (i in 0 until uSize step uvPixelStride) {
                    nv21[ySize + uvIndex] = uvBuffer[i]
                    nv21[ySize + uvIndex + 1] = uvBuffer[uSize + i]
                    uvIndex += 2
                }
            }

            // Convert to OpenCV Mat
            val yuvMat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
            yuvMat.put(0, 0, nv21)

            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)

            // Resize for performance
            val resizedMat = Mat()
            val originalSize = org.opencv.core.Size(rgbMat.cols().toDouble(), rgbMat.rows().toDouble())
            val targetSize = org.opencv.core.Size(TARGET_WIDTH.toDouble(), TARGET_HEIGHT.toDouble())

            Imgproc.resize(rgbMat, resizedMat, targetSize)

            // Convert to Bitmap
            val bitmap = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resizedMat, bitmap)

            // Cleanup
            yuvMat.release()
            rgbMat.release()
            resizedMat.release()

            Log.d(TAG, "Image converted: ${image.width}x${image.height} -> ${TARGET_WIDTH}x${TARGET_HEIGHT}")

            bitmap

        } catch (e: Exception) {
            Log.e(TAG, "Image conversion error: ${e.message}")
            null
        }
    }

    /**
     * Handle detection result and trigger callback if valid
     */
    private fun handleDetectionResult(result: ORBCardDetector.CardDetectionResult, currentTime: Long) {
        Log.d(TAG, "Detection result: detected=${result.isCardDetected}, confidence=${result.confidence}, time=${result.processingTimeMs}ms")

        if (result.isCardDetected && result.confidence >= MIN_CONFIDENCE_THRESHOLD) {
            Log.i(TAG, "✅ CARD DETECTED! Confidence: ${result.confidence}, Matches: ${result.matchCount}")
            Log.i(TAG, "Card info: Area=${result.cardArea}, AspectRatio=${result.aspectRatio}")

            result.corners?.let { corners ->
                Log.d(TAG, "Card corners: ${corners.contentToString()}")
            }

            result.boundingBox?.let { bbox ->
                Log.d(TAG, "Bounding box: $bbox")
            }

            lastDetectionTime = currentTime

            // Trigger callback on main thread
            CoroutineScope(Dispatchers.Main).launch {
                onCardDetected(result)
            }
        } else if (result.isCardDetected) {
            Log.d(TAG, "Card detected but confidence too low: ${result.confidence} < $MIN_CONFIDENCE_THRESHOLD")
        } else {
            Log.d(TAG, "No card detected: ${result.errorMessage}")
        }
    }

    /**
     * Enable/disable analysis
     */
    fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
        Log.d(TAG, "Analysis ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if analysis is currently enabled
     */
    fun isAnalysisEnabled(): Boolean = analysisEnabled

    /**
     * Reset detection cooldown (useful when starting new detection session)
     */
    fun resetDetectionCooldown() {
        lastDetectionTime = 0L
        lastAnalysisTime = 0L
        Log.d(TAG, "Detection cooldown reset")
    }

    /**
     * Update overlay bounds for position validation
     */
    fun updateOverlayBounds(newBounds: Rect?) {
        // Note: This analyzer creates with fixed overlay bounds
        // For dynamic updates, consider recreating the analyzer
        Log.d(TAG, "Overlay bounds update requested: $newBounds")
    }

    /**
     * Get performance statistics
     */
    fun getPerformanceStats(): PerformanceStats {
        return PerformanceStats(
            isProcessing = isProcessing,
            analysisEnabled = analysisEnabled,
            lastAnalysisTime = lastAnalysisTime,
            lastDetectionTime = lastDetectionTime
        )
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up ORB Card Analyzer...")

            analysisEnabled = false
            analysisScope.cancel()

            Log.i(TAG, "ORB Card Analyzer cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    /**
     * Performance statistics data class
     */
    data class PerformanceStats(
        val isProcessing: Boolean,
        val analysisEnabled: Boolean,
        val lastAnalysisTime: Long,
        val lastDetectionTime: Long
    ) {
        val timeSinceLastAnalysis: Long
            get() = System.currentTimeMillis() - lastAnalysisTime

        val timeSinceLastDetection: Long
            get() = System.currentTimeMillis() - lastDetectionTime
    }
}