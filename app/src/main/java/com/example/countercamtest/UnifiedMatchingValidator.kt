package com.example.countercamtest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.features2d.ORB
import org.opencv.features2d.DescriptorMatcher
import org.opencv.calib3d.Calib3d
import org.opencv.imgproc.Imgproc
import java.io.IOException
import kotlin.math.*

class UnifiedMatchingValidator(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedMatchingValidator"

        // Enhanced matching parameters
        private const val MIN_MATCHES_FOR_HOMOGRAPHY = 12
        private const val HOMOGRAPHY_CONFIDENCE = 0.995
        private const val HOMOGRAPHY_THRESHOLD = 3.0
        private const val GOOD_MATCH_RATIO = 0.65f
        private const val MIN_MATCH_COUNT = 8

        // Resolution adaptation parameters
        private const val TARGET_REFERENCE_WIDTH = 1920.0
        private const val TARGET_REFERENCE_HEIGHT = 1200.0
        private const val SCALE_INVARIANT_FACTOR = 1.5f

        // Image preprocessing parameters
        private const val GAUSSIAN_BLUR_KERNEL = 3
        private const val CLAHE_CLIP_LIMIT = 2.0
        private const val CLAHE_TILE_SIZE = 8
    }

    // Optimal ORB configuration for scale and perspective invariance
    private val orb: ORB = ORB.create(
        2000,    // More features for better matching across scales
        1.2f,    // Lower scale factor for finer scale invariance
        12,      // More pyramid levels for better scale coverage
        20,      // Lower edge threshold for more features
        0,       // First level
        2,       // WTA_K
        ORB.HARRIS_SCORE, // Harris corner score for better feature quality
        31,      // Larger patch size for more distinctive features
        10       // Lower FAST threshold for more features
    )

    private val matcher: DescriptorMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    // Reference image data
    private var referenceKeyPoints = MatOfKeyPoint()
    private var referenceDescriptors = Mat()
    private var referenceMat = Mat()
    private var originalReferenceSize = Size(0.0, 0.0)
    private var preprocessedReferenceSize = Size(0.0, 0.0)

    data class UnifiedValidationResult(
        val isValid: Boolean,
        val confidence: Float,
        val totalMatches: Int,
        val goodMatches: Int,
        val homographyFound: Boolean,
        val perspectiveCorrected: Boolean,
        val resolutionAdapted: Boolean,
        val scaleRatio: Double,
        val rotationAngle: Double,
        val homographyMatrix: Mat?,
        val matchedKeypoints: List<Pair<Point, Point>>?,
        val processingTimeMs: Long,
        val errorMessage: String? = null
    )

    init {
        loadAndPrepareReferenceImage()
    }

    private fun loadAndPrepareReferenceImage() {
        try {
            // Load reference image from assets
            val inputStream = context.assets.open("ref_id_card.png")
            val referenceBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (referenceBitmap == null) {
                Log.e(TAG, "Reference image could not be loaded")
                return
            }

            // Store original dimensions
            originalReferenceSize = Size(referenceBitmap.width.toDouble(), referenceBitmap.height.toDouble())

            // Convert to Mat
            val originalRefMat = Mat()
            Utils.bitmapToMat(referenceBitmap, originalRefMat)

            // Apply resolution adaptation and preprocessing
            referenceMat = preprocessAndAdaptResolution(originalRefMat, true)
            preprocessedReferenceSize = Size(referenceMat.width().toDouble(), referenceMat.height().toDouble())

            // Extract features from preprocessed reference
            extractReferenceFeatures()

            Log.i(TAG, "Reference image loaded and preprocessed: ${originalReferenceSize.width}x${originalReferenceSize.height} -> ${preprocessedReferenceSize.width}x${preprocessedReferenceSize.height}")
            Log.i(TAG, "Reference features extracted: ${referenceKeyPoints.total()} keypoints")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to load reference image: ${e.message}")
        }
    }

    private fun preprocessAndAdaptResolution(inputMat: Mat, isReference: Boolean = false): Mat {
        val processedMat = Mat()

        // Convert to grayscale if needed
        if (inputMat.channels() > 1) {
            Imgproc.cvtColor(inputMat, processedMat, Imgproc.COLOR_BGR2GRAY)
        } else {
            inputMat.copyTo(processedMat)
        }

        // Resolution adaptation
        val currentSize = Size(processedMat.width().toDouble(), processedMat.height().toDouble())
        val adaptedMat = Mat()

        if (isReference) {
            // Adapt reference to target resolution
            val scaleX = TARGET_REFERENCE_WIDTH / currentSize.width
            val scaleY = TARGET_REFERENCE_HEIGHT / currentSize.height
            val scale = min(scaleX, scaleY) // Maintain aspect ratio

            val newSize = Size(currentSize.width * scale, currentSize.height * scale)
            Imgproc.resize(processedMat, adaptedMat, newSize, 0.0, 0.0, Imgproc.INTER_CUBIC)
        } else {
            // Adapt camera image to match reference scale
            val refRatio = preprocessedReferenceSize.width / preprocessedReferenceSize.height
            val currentRatio = currentSize.width / currentSize.height

            val targetWidth: Double
            val targetHeight: Double

            if (currentRatio > refRatio) {
                // Current image is wider, adapt height
                targetHeight = preprocessedReferenceSize.height * SCALE_INVARIANT_FACTOR
                targetWidth = targetHeight * currentRatio
            } else {
                // Current image is taller, adapt width
                targetWidth = preprocessedReferenceSize.width * SCALE_INVARIANT_FACTOR
                targetHeight = targetWidth / currentRatio
            }

            val newSize = Size(targetWidth, targetHeight)
            Imgproc.resize(processedMat, adaptedMat, newSize, 0.0, 0.0, Imgproc.INTER_CUBIC)
        }

        // Advanced preprocessing pipeline
        val finalMat = Mat()

        // 1. Gaussian blur to reduce noise
        Imgproc.GaussianBlur(adaptedMat, finalMat, Size(GAUSSIAN_BLUR_KERNEL.toDouble(), GAUSSIAN_BLUR_KERNEL.toDouble()), 0.0)

        // 2. CLAHE (Contrast Limited Adaptive Histogram Equalization)
        val clahe = Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, Size(CLAHE_TILE_SIZE.toDouble(), CLAHE_TILE_SIZE.toDouble()))
        val claheMat = Mat()
        clahe.apply(finalMat, claheMat)

        // 3. Bilateral filter for edge preservation
        val bilateralMat = Mat()
        Imgproc.bilateralFilter(claheMat, bilateralMat, 9, 75.0, 75.0)

        // Cleanup
        processedMat.release()
        adaptedMat.release()
        finalMat.release()
        claheMat.release()

        return bilateralMat
    }

    private fun extractReferenceFeatures() {
        orb.detectAndCompute(referenceMat, Mat(), referenceKeyPoints, referenceDescriptors)
    }

    fun validateWithUnifiedMatching(inputBitmap: Bitmap): UnifiedValidationResult {
        val startTime = System.currentTimeMillis()

        try {
            // Convert bitmap to Mat
            val inputMat = Mat()
            Utils.bitmapToMat(inputBitmap, inputMat)

            // Preprocess and adapt resolution
            val processedInputMat = preprocessAndAdaptResolution(inputMat)

            // Extract features from processed input
            val inputKeyPoints = MatOfKeyPoint()
            val inputDescriptors = Mat()
            orb.detectAndCompute(processedInputMat, Mat(), inputKeyPoints, inputDescriptors)

            if (inputDescriptors.empty() || referenceDescriptors.empty()) {
                return UnifiedValidationResult(
                    isValid = false,
                    confidence = 0f,
                    totalMatches = 0,
                    goodMatches = 0,
                    homographyFound = false,
                    perspectiveCorrected = false,
                    resolutionAdapted = true,
                    scaleRatio = 1.0,
                    rotationAngle = 0.0,
                    homographyMatrix = null,
                    matchedKeypoints = null,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    errorMessage = "No features detected"
                )
            }

            // Match features
            val matches = mutableListOf<MatOfDMatch>()
            matcher.knnMatch(inputDescriptors, referenceDescriptors, matches, 2)

            // Filter good matches using Lowe's ratio test
            val goodMatches = mutableListOf<DMatch>()
            val matchedPoints = mutableListOf<Pair<Point, Point>>()

            for (matchList in matches) {
                val matchArray = matchList.toArray()
                if (matchArray.size >= 2) {
                    val bestMatch = matchArray[0]
                    val secondBestMatch = matchArray[1]

                    if (bestMatch.distance < GOOD_MATCH_RATIO * secondBestMatch.distance) {
                        goodMatches.add(bestMatch)

                        val inputPoints = inputKeyPoints.toArray()
                        val referencePoints = referenceKeyPoints.toArray()

                        if (bestMatch.queryIdx < inputPoints.size && bestMatch.trainIdx < referencePoints.size) {
                            matchedPoints.add(
                                Pair(inputPoints[bestMatch.queryIdx].pt, referencePoints[bestMatch.trainIdx].pt)
                            )
                        }
                    }
                }
            }

            // Calculate scale and rotation from matches
            val (scaleRatio, rotationAngle) = calculateTransformationMetrics(matchedPoints)

            // Homography estimation for perspective correction
            var homographyMatrix: Mat? = null
            var homographyFound = false
            var perspectiveCorrected = false

            if (goodMatches.size >= MIN_MATCHES_FOR_HOMOGRAPHY) {
                val srcPoints = MatOfPoint2f()
                val dstPoints = MatOfPoint2f()

                val srcArray = goodMatches.map { match ->
                    inputKeyPoints.toArray()[match.queryIdx].pt
                }.toTypedArray()

                val dstArray = goodMatches.map { match ->
                    referenceKeyPoints.toArray()[match.trainIdx].pt
                }.toTypedArray()

                srcPoints.fromArray(*srcArray)
                dstPoints.fromArray(*dstArray)

                homographyMatrix = Calib3d.findHomography(
                    srcPoints, dstPoints,
                    Calib3d.RANSAC,
                    HOMOGRAPHY_THRESHOLD,
                    Mat(),
                    2000,
                    HOMOGRAPHY_CONFIDENCE
                )

                homographyFound = !homographyMatrix.empty()

                if (homographyFound) {
                    // Apply perspective correction and re-match
                    val correctedMat = Mat()
                    Imgproc.warpPerspective(
                        processedInputMat, correctedMat, homographyMatrix,
                        Size(referenceMat.width().toDouble(), referenceMat.height().toDouble())
                    )

                    // Re-extract features from corrected image
                    val correctedKeyPoints = MatOfKeyPoint()
                    val correctedDescriptors = Mat()
                    orb.detectAndCompute(correctedMat, Mat(), correctedKeyPoints, correctedDescriptors)

                    // Re-match with corrected image
                    if (!correctedDescriptors.empty()) {
                        val correctedMatches = mutableListOf<MatOfDMatch>()
                        matcher.knnMatch(correctedDescriptors, referenceDescriptors, correctedMatches, 2)

                        // Update good matches with corrected results
                        goodMatches.clear()
                        matchedPoints.clear()

                        for (matchList in correctedMatches) {
                            val matchArray = matchList.toArray()
                            if (matchArray.size >= 2) {
                                val bestMatch = matchArray[0]
                                val secondBestMatch = matchArray[1]

                                if (bestMatch.distance < GOOD_MATCH_RATIO * secondBestMatch.distance) {
                                    goodMatches.add(bestMatch)

                                    val correctedPoints = correctedKeyPoints.toArray()
                                    val referencePoints = referenceKeyPoints.toArray()

                                    if (bestMatch.queryIdx < correctedPoints.size && bestMatch.trainIdx < referencePoints.size) {
                                        matchedPoints.add(
                                            Pair(correctedPoints[bestMatch.queryIdx].pt, referencePoints[bestMatch.trainIdx].pt)
                                        )
                                    }
                                }
                            }
                        }
                        perspectiveCorrected = true
                    }

                    correctedMat.release()
                }
            }

            // Calculate confidence based on multiple factors
            val confidence = calculateUnifiedConfidence(goodMatches.size, homographyFound, scaleRatio, rotationAngle)
            val isValid = goodMatches.size >= MIN_MATCH_COUNT && confidence > 0.3f

            // Cleanup
            inputMat.release()
            processedInputMat.release()
            inputDescriptors.release()

            Log.d(TAG, "Unified validation completed: Valid=$isValid, Matches=${goodMatches.size}, Homography=$homographyFound, Scale=$scaleRatio, Rotation=$rotationAngle")

            return UnifiedValidationResult(
                isValid = isValid,
                confidence = confidence,
                totalMatches = matches.size,
                goodMatches = goodMatches.size,
                homographyFound = homographyFound,
                perspectiveCorrected = perspectiveCorrected,
                resolutionAdapted = true,
                scaleRatio = scaleRatio,
                rotationAngle = rotationAngle,
                homographyMatrix = homographyMatrix,
                matchedKeypoints = if (matchedPoints.isNotEmpty()) matchedPoints else null,
                processingTimeMs = System.currentTimeMillis() - startTime
            )

        } catch (e: Exception) {
            Log.e(TAG, "Unified validation error: ${e.message}")
            return UnifiedValidationResult(
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
                processingTimeMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message
            )
        }
    }

    private fun calculateTransformationMetrics(matchedPoints: List<Pair<Point, Point>>): Pair<Double, Double> {
        if (matchedPoints.size < 2) return Pair(1.0, 0.0)

        var totalScale = 0.0
        var totalRotation = 0.0
        var validPairs = 0

        for (i in 0 until matchedPoints.size - 1) {
            for (j in i + 1 until matchedPoints.size) {
                val (src1, dst1) = matchedPoints[i]
                val (src2, dst2) = matchedPoints[j]

                val srcDist = sqrt((src2.x - src1.x).pow(2) + (src2.y - src1.y).pow(2))
                val dstDist = sqrt((dst2.x - dst1.x).pow(2) + (dst2.y - dst1.y).pow(2))

                if (srcDist > 10 && dstDist > 10) { // Avoid very small distances
                    totalScale += dstDist / srcDist

                    val srcAngle = atan2(src2.y - src1.y, src2.x - src1.x)
                    val dstAngle = atan2(dst2.y - dst1.y, dst2.x - dst1.x)
                    totalRotation += dstAngle - srcAngle

                    validPairs++
                }
            }
        }

        val avgScale = if (validPairs > 0) totalScale / validPairs else 1.0
        val avgRotation = if (validPairs > 0) totalRotation / validPairs else 0.0

        return Pair(avgScale, Math.toDegrees(avgRotation))
    }

    private fun calculateUnifiedConfidence(
        goodMatches: Int,
        homographyFound: Boolean,
        scaleRatio: Double,
        rotationAngle: Double
    ): Float {
        // Base confidence from match count
        val baseConfidence = min(goodMatches.toFloat() / 30f, 1.0f) * 0.4f

        // Homography bonus
        val homographyBonus = if (homographyFound) 0.3f else 0.0f

        // Scale consistency bonus (penalize extreme scales)
        val scaleConsistency = when {
            scaleRatio in 0.7..1.4 -> 0.2f
            scaleRatio in 0.5..2.0 -> 0.1f
            else -> 0.0f
        }

        // Rotation consistency bonus (penalize extreme rotations)
        val rotationConsistency = when {
            abs(rotationAngle) < 10 -> 0.1f
            abs(rotationAngle) < 30 -> 0.05f
            else -> 0.0f
        }

        return min(baseConfidence + homographyBonus + scaleConsistency + rotationConsistency, 1.0f)
    }

    fun cleanup() {
        try {
            referenceKeyPoints.release()
            referenceDescriptors.release()
            referenceMat.release()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }
}