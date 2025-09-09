package com.example.countercamtest

import android.graphics.Bitmap
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

class UnifiedMatchingAnalyzer(
    private val unifiedValidator: UnifiedMatchingValidator,
    private val onValidationResult: (UnifiedMatchingValidator.UnifiedValidationResult) -> Unit,
    private var overlayBounds: android.graphics.Rect? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "UnifiedMatchingAnalyzer"
        private const val ANALYSIS_INTERVAL_MS = 1000L // Optimized interval for better performance
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val SUCCESS_PAUSE_DURATION = 5000L // 5 seconds pause after success
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
    private var hasFoundValidMRZ = false
    private var successfulMRZResult: UnifiedMatchingValidator.UnifiedValidationResult? = null

    override fun analyze(image: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // Check if analysis should be paused after successful MRZ reading
        if (hasFoundValidMRZ && successfulMRZResult?.mrzData?.success == true) {
            Log.d(TAG, "Analysis paused - Valid MRZ already found")
            image.close()
            return
        }

        // Check if analysis is disabled
        if (!isAnalysisEnabled) {
            Log.d(TAG, "Analysis disabled")
            image.close()
            return
        }

        // Pause after recent success to allow user to view results
        if (lastSuccessTime > 0 && (currentTime - lastSuccessTime) < SUCCESS_PAUSE_DURATION) {
            Log.d(TAG, "Analysis paused after recent success")
            image.close()
            return
        }

        // Adaptive interval based on recent performance
        if (consecutiveFailures > 3) {
            currentInterval = ANALYSIS_INTERVAL_MS * 2 // Slow down if many failures
        } else if (consecutiveFailures == 0 && successfulAnalyses > 3) {
            currentInterval = (ANALYSIS_INTERVAL_MS * 0.8).toLong() // Speed up if successful
        } else {
            currentInterval = ANALYSIS_INTERVAL_MS
        }

        // Check analysis interval
        if (currentTime - lastAnalysisTime < currentInterval) {
            image.close()
            return
        }

        lastAnalysisTime = currentTime
        totalAnalyses++

        analysisScope.launch {
            try {
                val analysisStartTime = System.currentTimeMillis()

                Log.d(TAG, "Starting unified analysis (#$totalAnalyses)")

                // Convert ImageProxy to Bitmap with optimized method
                val bitmap = imageProxyToBitmap(image)

                // Perform unified validation with overlay bounds
                val result = unifiedValidator.validateWithUnifiedMatching(bitmap, overlayBounds)

                val analysisEndTime = System.currentTimeMillis()
                val analysisTime = analysisEndTime - analysisStartTime

                // Update performance metrics
                updatePerformanceMetrics(result, analysisTime)

                Log.d(TAG, "Unified analysis completed: Valid=${result.isValid}, " +
                        "Matches=${result.goodMatches}/${result.totalMatches}, " +
                        "Homography=${result.homographyFound}, " +
                        "Scale=${String.format("%.2f", result.scaleRatio)}, " +
                        "Rotation=${String.format("%.1f", result.rotationAngle)}°, " +
                        "Processing=${result.processingTimeMs}ms, " +
                        "Total=${analysisTime}ms")

                // Check if we have a successful MRZ result
                if (result.mrzData?.success == true) {
                    Log.i(TAG, "🎉 SUCCESSFUL MRZ READING - STOPPING ANALYSIS")
                    hasFoundValidMRZ = true
                    successfulMRZResult = result
                    isAnalysisEnabled = false
                }

                // Call result callback on main thread
                launch(Dispatchers.Main) {
                    onValidationResult(result)
                }

                // Cleanup bitmap
                bitmap.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Unified analysis error: ${e.message}")
                consecutiveFailures++

                // Return error result
                launch(Dispatchers.Main) {
                    onValidationResult(
                        UnifiedMatchingValidator.UnifiedValidationResult(
                            isValid = false,
                            confidence = 0f,
                            totalMatches = 0,
                            goodMatches = 0,
                            homographyFound = false,
                            perspectiveCorrected = false,
                            resolutionAdapted = false,
                            scaleRatio = 1.0,
                            rotationAngle = 0.0,
                            homographyMatrix = null,
                            matchedKeypoints = null,
                            processingTimeMs = 0L,
                            errorMessage = "Analysis error: ${e.message}",
                            mrzData = null
                        )
                    )
                }
            } finally {
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

    private suspend fun imageProxyToBitmap(image: ImageProxy): Bitmap = suspendCoroutine { continuation ->
        try {
            // Optimized YUV to RGB conversion
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // YUV_420_888 to NV21 conversion
            yBuffer.get(nv21, 0, ySize)

            // Handle UV plane interleaving
            val uvPixelStride = image.planes[1].pixelStride
            if (uvPixelStride == 1) {
                // Packed UV
                uBuffer.get(nv21, ySize, uSize)
                vBuffer.get(nv21, ySize + uSize, vSize)
            } else {
                // Planar UV - need to interleave
                val uvBuffer = ByteArray(uSize)
                val vvBuffer = ByteArray(vSize)
                uBuffer.get(uvBuffer)
                vBuffer.get(vvBuffer)

                var uvIndex = ySize
                for (i in 0 until uSize) {
                    nv21[uvIndex++] = vvBuffer[i]
                    if (i < uvBuffer.size) {
                        nv21[uvIndex++] = uvBuffer[i]
                    }
                }
            }

            // Convert to OpenCV Mat
            val yuvMat = Mat(image.height + image.height / 2, image.width, org.opencv.core.CvType.CV_8UC1)
            yuvMat.put(0, 0, nv21)

            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)

            // Convert to Bitmap
            val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, bitmap)

            // Cleanup OpenCV Mats
            yuvMat.release()
            rgbMat.release()

            continuation.resume(bitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion error: ${e.message}")
            // Return a small fallback bitmap
            continuation.resume(
                Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            )
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
}