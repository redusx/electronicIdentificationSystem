package com.example.countercamtest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.graphics.ImageFormat

class UnifiedMatchingAnalyzer(
    private val unifiedValidator: UnifiedMatchingValidator,
    private val objectDetectorHelper: com.example.countercamtest.objectdetection.objectdetector.ObjectDetectorHelper?,
    private val onValidationResult: (UnifiedMatchingValidator.UnifiedValidationResult) -> Unit,
    private val onObjectDetectionResult: ((com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult?, Int, Int) -> Unit)? = null,
    private var overlayBounds: android.graphics.Rect? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "UnifiedMatchingAnalyzer"
        private const val ANALYSIS_INTERVAL_MS = 200L // Reduced for more real-time analysis
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val SUCCESS_PAUSE_DURATION = 2000L // Reduced to 2 seconds pause after success
        private const val AUTO_RESET_TIMEOUT = 10000L // Auto reset after 10 seconds
    }

    private var lastAnalysisTime = 0L
    private val analysisScope = CoroutineScope(Dispatchers.Default)

    // Performance tracking
    private var consecutiveFailures = 0
    private var totalAnalyses = 0
    private var successfulAnalyses = 0
    private var averageProcessingTime = 0L

    // Adaptive analysis parameters
    private var currentInterval = ANALYSIS_INTERVAL_MS
    private var lastSuccessTime = 0L

    // State management for stopping analysis after success
    private var isAnalysisEnabled = true
    private var isValidationInProgress = false // Flag to prevent re-analysis during heavy validation
    private var hasFoundValidMRZ = false
    private var successfulMRZResult: UnifiedMatchingValidator.UnifiedValidationResult? = null

    override fun analyze(image: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // If validation is already running, or analysis is paused/disabled, skip frame.
        if (isValidationInProgress || hasFoundValidMRZ || !isAnalysisEnabled) {
            if(isValidationInProgress) Log.d(TAG, "Validation in progress, skipping frame...")
            image.close()
            return
        }

        if (currentTime - lastAnalysisTime < currentInterval) {
            image.close()
            return
        }
        lastAnalysisTime = currentTime
        totalAnalyses++ // Increment the counter for each analysis attempt

        analysisScope.launch {
            val analysisStartTime = System.currentTimeMillis()
            try {
                val bitmap = imageProxyToBitmap(image)

                // Step 1: Always run object detection
                val objDetectionResultBundle = objectDetectorHelper?.detectImage(bitmap)
                val detectionResult = objDetectionResultBundle?.results?.firstOrNull()
                val detections = detectionResult?.detections()
                val topDetection = detections?.maxByOrNull { it.categories().first().score() }

                // --- DETAILED LOGGING ---
                Log.d(TAG, "--- Frame Analysis ---")
                Log.d(TAG, "Overlay Bounds: ${overlayBounds?.toShortString()}")
                if (detections.isNullOrEmpty()) {
                    Log.d(TAG, "MediaPipe: No objects detected.")
                } else {
                    Log.d(TAG, "MediaPipe: Detected ${detections.size} objects.")
                    detections.forEachIndexed { i, det ->
                        val cat = det.categories().first()
                        Log.d(TAG, "  - Obj $i: ${cat.categoryName()} (Score: ${String.format("%.3f", cat.score())}) at ${det.boundingBox()}")
                    }
                    if (topDetection != null) {
                        Log.d(TAG, "Top Detection BBox (for overlap check): ${topDetection.boundingBox()}")
                    }
                }
                // --- END LOGGING ---

                // Always send detection result to UI for drawing bounding boxes
                onObjectDetectionResult?.invoke(detectionResult, bitmap.width, bitmap.height)

                var validationResult: UnifiedMatchingValidator.UnifiedValidationResult

                // Step 2: Check for overlap to trigger expensive validation
                val hasOverlap = topDetection != null && checkOverlap(topDetection.boundingBox(), overlayBounds)

                if (hasOverlap) {
                    isValidationInProgress = true // <-- LOCK ACQUIRED
                    Log.i(TAG, "✅✅✅ OVERLAP SUCCESS: Triggering full validation & PAUSING analysis.")
                    
                    // Step 3 & 4: Run OpenCV and MRZ OCR
                    validationResult = unifiedValidator.validateWithUnifiedMatching(bitmap, overlayBounds)

                    // Check if we have a successful MRZ result from the validation
                    if (validationResult.mrzData?.success == true) {
                        Log.i(TAG, "🎉 SUCCESSFUL MRZ READING - STOPPING ANALYSIS")
                        hasFoundValidMRZ = true
                        successfulMRZResult = validationResult
                        isAnalysisEnabled = false
                    }
                } else {
                    Log.i(TAG, "❌❌❌ OVERLAP FAILED: Skipping full validation.")
                    // No valid detection or overlap, return an empty result without running validation
                    validationResult = createEmptyValidationResult(System.currentTimeMillis() - analysisStartTime)
                }

                // Update performance metrics and log results
                updatePerformanceMetrics(validationResult, System.currentTimeMillis() - analysisStartTime)
                logValidationResult(validationResult, System.currentTimeMillis() - analysisStartTime)

                // Step 5: Return the result to the main thread
                launch(Dispatchers.Main) {
                    onValidationResult(validationResult)
                }

                bitmap.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Unified analysis error: ${e.message}", e)
                consecutiveFailures++
                launch(Dispatchers.Main) {
                    onValidationResult(createEmptyValidationResult(System.currentTimeMillis() - analysisStartTime, "Analysis error: ${e.message}"))
                }
            } finally {
                isValidationInProgress = false // <-- LOCK RELEASED
                image.close()
            }
        }
    }

    private fun updatePerformanceMetrics(result: UnifiedMatchingValidator.UnifiedValidationResult, totalTime: Long) {
        if (result.isValid) {
            consecutiveFailures = 0
            successfulAnalyses++
            lastSuccessTime = System.currentTimeMillis()
        } else {
            consecutiveFailures++
        }

        // Update rolling average processing time
        averageProcessingTime = if (totalAnalyses == 1) {
            totalTime
        } else {
            (averageProcessingTime * (totalAnalyses - 1) + totalTime) / totalAnalyses
        }

        // Log performance stats periodically
        if (totalAnalyses % 10 == 0) {
            val successRate = (successfulAnalyses.toFloat() / totalAnalyses * 100).toInt()
            Log.i(TAG, "Performance Stats: Success Rate: ${successRate}%, " +
                    "Avg Processing Time: ${averageProcessingTime}ms, " +
                    "Consecutive Failures: $consecutiveFailures")
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        // The ImageAnalysis is configured to output RGBA_8888 format.
        // We assume the format is RGBA_8888 and use a simple, direct conversion
        // to avoid potential errors in the complex YUV conversion logic.
        val bitmapBuffer = Bitmap.createBitmap(
            image.width,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        // Copy pixels from the ImageProxy's buffer to the bitmap buffer.
        val buffer = image.planes[0].buffer
        buffer.rewind()
        bitmapBuffer.copyPixelsFromBuffer(buffer)

        // Rotate the bitmap to match the screen orientation.
        val matrix = android.graphics.Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )
        
        // It's crucial to recycle the intermediate bitmap to avoid memory leaks.
        bitmapBuffer.recycle()

        return rotatedBitmap
    }

    private fun createEmptyBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun getImageFormatName(format: Int): String {
        return when (format) {
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.NV21, 1 -> "NV21"
            ImageFormat.JPEG -> "JPEG"
            androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 -> "RGBA_8888"
            ImageFormat.YV12 -> "YV12"
            ImageFormat.YUV_422_888 -> "YUV_422_888"
            ImageFormat.YUV_444_888 -> "YUV_444_888"
            else -> "Unknown($format)"
        }
    }

    // Performance monitoring methods
    fun getPerformanceStats(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val successRate = if (totalAnalyses > 0) (successfulAnalyses.toFloat() / totalAnalyses * 100).toInt() else 0

        return mapOf(
            "total_analyses" to totalAnalyses,
            "successful_analyses" to successfulAnalyses,
            "success_rate_percent" to successRate,
            "consecutive_failures" to consecutiveFailures,
            "average_processing_time_ms" to averageProcessingTime,
            "current_interval_ms" to currentInterval,
            "last_success_time" to lastSuccessTime,
            "time_since_last_success_ms" to (currentTime - lastSuccessTime)
        )
    }

    fun resetPerformanceStats() {
        consecutiveFailures = 0
        totalAnalyses = 0
        successfulAnalyses = 0
        averageProcessingTime = 0L
        currentInterval = ANALYSIS_INTERVAL_MS
        lastSuccessTime = 0L // Reset success pause instead of setting current time
        Log.i(TAG, "Performance stats reset")
    }

    // Adaptive tuning methods
    fun adaptAnalysisFrequency(increase: Boolean) {
        currentInterval = if (increase) {
            (currentInterval * 0.8).toLong().coerceAtLeast(500L) // Faster, min 500ms
        } else {
            (currentInterval * 1.5).toLong().coerceAtMost(5000L) // Slower, max 5s
        }
        Log.d(TAG, "Analysis interval adapted to: ${currentInterval}ms")
    }

    fun forceAnalysis() {
        lastAnalysisTime = 0L // Reset to force next analysis
    }

    fun updateOverlayBounds(bounds: android.graphics.Rect?) {
        overlayBounds = bounds
        Log.d(TAG, "Overlay bounds updated: $bounds")
    }

    // Methods to control analysis state
    fun enableAnalysis() {
        isAnalysisEnabled = true
        hasFoundValidMRZ = false
        successfulMRZResult = null
        lastSuccessTime = 0L // Reset success pause
        Log.d(TAG, "Analysis enabled - reset to initial state")
    }

    fun disableAnalysis() {
        isAnalysisEnabled = false
        Log.d(TAG, "Analysis disabled")
    }

    fun hasValidMRZResult(): Boolean = hasFoundValidMRZ && successfulMRZResult?.mrzData?.success == true

    fun getSuccessfulResult(): UnifiedMatchingValidator.UnifiedValidationResult? = successfulMRZResult

    /**
     * Comprehensive reset to initial state - fixes stuck analysis
     */
    fun resetToInitialState() {
        isAnalysisEnabled = true
        hasFoundValidMRZ = false
        successfulMRZResult = null
        lastSuccessTime = 0L
        lastAnalysisTime = 0L
        consecutiveFailures = 0
        currentInterval = ANALYSIS_INTERVAL_MS
        Log.i(TAG, "🔄 COMPLETE RESET - Analysis state cleared, ready for new detection")
    }

    /**
     * Quick reset - only clears success state but keeps performance stats
     */
    fun quickReset() {
        hasFoundValidMRZ = false
        successfulMRZResult = null
        lastSuccessTime = 0L
        isAnalysisEnabled = true
        Log.i(TAG, "⚡ QUICK RESET - Ready for new detection")
    }

    /**
     * Force immediate analysis bypass - emergency unfreeze
     */
    fun forceUnfreeze() {
        lastSuccessTime = 0L
        lastAnalysisTime = 0L
        isAnalysisEnabled = true
        hasFoundValidMRZ = false
        Log.w(TAG, "🚨 FORCE UNFREEZE - Emergency analysis restart")
    }

    /**
     * Check if analysis is currently stuck/paused
     */
    fun isAnalysisStuck(): Boolean {
        val currentTime = System.currentTimeMillis()
        return (lastSuccessTime > 0 && (currentTime - lastSuccessTime) < SUCCESS_PAUSE_DURATION) ||
               !isAnalysisEnabled ||
               hasFoundValidMRZ
    }

    /**
     * Get detailed status for debugging
     */
    fun getAnalysisStatus(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        return mapOf(
            "analysis_enabled" to isAnalysisEnabled,
            "has_found_valid_mrz" to hasFoundValidMRZ,
            "last_success_time" to lastSuccessTime,
            "time_since_success" to if (lastSuccessTime > 0) (currentTime - lastSuccessTime) else 0L,
            "is_stuck" to isAnalysisStuck(),
            "success_pause_remaining" to if (lastSuccessTime > 0)
                maxOf(0L, SUCCESS_PAUSE_DURATION - (currentTime - lastSuccessTime)) else 0L,
            "will_auto_reset_in" to if (lastSuccessTime > 0)
                maxOf(0L, AUTO_RESET_TIMEOUT - (currentTime - lastSuccessTime)) else 0L
        )
    }

    // --- HELPER METHODS FOR NEW LOGIC ---

    private fun checkOverlap(detectionBoxF: android.graphics.RectF?, overlayBox: android.graphics.Rect?): Boolean {
        Log.d(TAG, "--- Overlap Check ---")
        Log.d(TAG, "Detection Box (Bitmap Coords): ${detectionBoxF}")
        Log.d(TAG, "Overlay Box (View Coords): ${overlayBox?.toShortString()}")

        if (overlayBox == null || detectionBoxF == null) {
            Log.w(TAG, "Overlap check failed: One of the boxes is null.")
            return false
        }

        val detectionRect = android.graphics.Rect()
        detectionBoxF.round(detectionRect)

        val intersectRect = android.graphics.Rect()
        if (!intersectRect.setIntersect(overlayBox, detectionRect)) {
            Log.d(TAG, "Overlap check failed: No intersection between boxes.")
            return false
        }

        val intersectArea = intersectRect.width() * intersectRect.height()
        val overlayArea = overlayBox.width() * overlayBox.height()

        if (overlayArea == 0) {
            Log.w(TAG, "Overlap check failed: Overlay area is zero.")
            return false
        }

        val overlapRatio = intersectArea.toFloat() / overlayArea.toFloat()
        val isSufficientlyOverlapping = overlapRatio > 0.20f // Lowering threshold to 20% for testing

        Log.d(TAG, "Intersection Rect: ${intersectRect.toShortString()}")
        Log.d(TAG, "Intersection Area: $intersectArea | Overlay Area: $overlayArea")
        Log.d(TAG, "Overlap Ratio (Intersection/Overlay): ${String.format("%.2f", overlapRatio)}")
        Log.d(TAG, "Overlap Result: $isSufficientlyOverlapping (Threshold: >0.20)")

        return isSufficientlyOverlapping
    }

    private fun createEmptyValidationResult(processingTime: Long, error: String? = "No valid object detected"): UnifiedMatchingValidator.UnifiedValidationResult {
        return UnifiedMatchingValidator.UnifiedValidationResult(
            isValid = false, confidence = 0f, totalMatches = 0, goodMatches = 0,
            homographyFound = false, perspectiveCorrected = false, resolutionAdapted = false,
            scaleRatio = 1.0, rotationAngle = 0.0, homographyMatrix = null,
            matchedKeypoints = null, processingTimeMs = processingTime, errorMessage = error,
            mrzData = null
        )
    }

    private fun logValidationResult(result: UnifiedMatchingValidator.UnifiedValidationResult, totalTime: Long) {
        Log.d(TAG, "Unified analysis completed: Valid=${result.isValid}, " +
                "Confidence=${String.format("%.2f", result.confidence)}, " +
                "Matches=${result.goodMatches}/${result.totalMatches}, " +
                "Homography=${result.homographyFound}, " +
                "Scale=${String.format("%.2f", result.scaleRatio)}, " +
                "Rotation=${String.format("%.1f", result.rotationAngle)}°, " +
                "Processing=${result.processingTimeMs}ms, " +
                "Total=${totalTime}ms")
    }
}