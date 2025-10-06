package com.example.countercamtest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.features2d.ORB
import org.opencv.features2d.DescriptorMatcher
import org.opencv.calib3d.Calib3d
import org.opencv.imgproc.Imgproc
import java.io.IOException
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gelişmiş ORB tabanlı kimlik kartı tespit sistemi
 * Homography, köşe tespiti, alan kontrolü ve overlay pozisyon doğrulaması ile
 */
class ORBCardDetector(private val context: Context) {

    companion object {
        private const val TAG = "ORBCardDetector"

        // ORB Feature Detection parameters - optimized for real-time card detection
        private const val ORB_N_FEATURES = 2000        // Daha fazla feature için
        private const val ORB_SCALE_FACTOR = 1.15f     // Daha yüksek çözünürlük için
        private const val ORB_N_LEVELS = 10            // Daha fazla pyramid level
        private const val ORB_EDGE_THRESHOLD = 20      // Daha düşük threshold
        private const val ORB_FIRST_LEVEL = 0
        private const val ORB_WTA_K = 2
        private const val ORB_PATCH_SIZE = 31
        private const val ORB_FAST_THRESHOLD = 10      // Daha düşük FAST threshold

        // Matching parameters - tightened for accurate detection
        private const val MIN_MATCHES_FOR_DETECTION = 15    // Minimum eşleşme sayısı (increased for accuracy)
        private const val GOOD_MATCH_RATIO = 0.65f          // Lowe's ratio test (tightened)
        private const val MIN_HOMOGRAPHY_MATCHES = 12       // Homography için minimum eşleşme (increased)

        // Card validation parameters - relaxed for initial detection
        private const val ID_CARD_ASPECT_RATIO = 1.59f      // TC Kimlik kartı oranı (85.60mm × 53.98mm)
        private const val ASPECT_RATIO_TOLERANCE = 0.20f    // %20 tolerans (gevşetildi)
        private const val MIN_AREA_RATIO = 0.05f             // Minimum alan oranı (ekranın %5'i - gevşetildi)
        private const val MAX_AREA_RATIO = 0.80f             // Maksimum alan oranı (ekranın %80'i)
        private const val MIN_CARD_WIDTH = 150               // Minimum kart genişliği (pixel - düşürüldü)
        private const val MIN_CARD_HEIGHT = 90               // Minimum kart yüksekliği (pixel - düşürüldü)
        private const val MIN_CARD_DIAGONAL = 200            // Minimum kart diagonal uzunluğu (pixel - düşürüldü)
        private const val MIN_OVERLAY_COVERAGE = 0.65f      // Kartın overlay içindeki minimum kaplama oranı (sıkılaştırıldı)

        // Homography validation
        private const val HOMOGRAPHY_CONFIDENCE = 0.99
        private const val HOMOGRAPHY_THRESHOLD = 3.0
        private const val MAX_REPROJECTION_ERROR = 5.0
    }

    private val orb: ORB = ORB.create(
        ORB_N_FEATURES,
        ORB_SCALE_FACTOR,
        ORB_N_LEVELS,
        ORB_EDGE_THRESHOLD,
        ORB_FIRST_LEVEL,
        ORB_WTA_K,
        ORB.FAST_SCORE,
        ORB_PATCH_SIZE,
        ORB_FAST_THRESHOLD
    )

    private val matcher: DescriptorMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    // Reference image data
    private var referenceKeyPoints = MatOfKeyPoint()
    private var referenceDescriptors = Mat()
    private var referenceMat = Mat()
    private var referenceCorners = MatOfPoint2f()

    init {
        // Ensure OpenCV is initialized
        if (!OpenCVLoader.initLocal()) {
            Log.w(TAG, "OpenCV local initialization failed, trying debug mode")
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV initialization failed completely!")
            } else {
                Log.i(TAG, "OpenCV initialized successfully in debug mode")
            }
        } else {
            Log.i(TAG, "OpenCV initialized successfully in local mode")
        }

        Log.i(TAG, "ORB Card Detector initialized")
        Log.i(TAG, "ORB Config: Features=$ORB_N_FEATURES, Scale=$ORB_SCALE_FACTOR, Levels=$ORB_N_LEVELS")
        Log.i(TAG, "Detection Params: MinMatches=$MIN_MATCHES_FOR_DETECTION, AspectRatio=$ID_CARD_ASPECT_RATIO±$ASPECT_RATIO_TOLERANCE")

        loadReferenceImage()
    }

    /**
     * Check if ORB is available
     */
    private fun isORBAvailable(): Boolean {
        return try {
            val testOrb = ORB.create()
            val testMat = Mat(100, 100, CvType.CV_8UC1, Scalar(128.0))
            val testKeyPoints = MatOfKeyPoint()
            val testDescriptors = Mat()

            testOrb.detectAndCompute(testMat, Mat(), testKeyPoints, testDescriptors)
            val success = testKeyPoints.total() >= 0

            testMat.release()
            testKeyPoints.release()
            testDescriptors.release()

            success
        } catch (e: Exception) {
            Log.e(TAG, "ORB availability test failed: ${e.message}")
            false
        }
    }

    /**
     * Load reference ID card image and extract features
     */
    private fun loadReferenceImage() {
        try {
            if (!isORBAvailable()) {
                Log.e(TAG, "ORB not available, cannot load reference image")
                return
            }

            val inputStream = context.assets.open("ref_id_card.png")
            val referenceBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (referenceBitmap != null) {
                Log.d(TAG, "Reference bitmap loaded: ${referenceBitmap.width}x${referenceBitmap.height}")

                Utils.bitmapToMat(referenceBitmap, referenceMat)

                // Convert to grayscale
                val grayMat = Mat()
                if (referenceMat.channels() > 1) {
                    Imgproc.cvtColor(referenceMat, grayMat, Imgproc.COLOR_BGR2GRAY)
                } else {
                    referenceMat.copyTo(grayMat)
                }

                // Extract reference features
                orb.detectAndCompute(grayMat, Mat(), referenceKeyPoints, referenceDescriptors)

                // Define reference card corners (top-left, top-right, bottom-right, bottom-left)
                val corners = arrayOf(
                    Point(0.0, 0.0),
                    Point(grayMat.cols().toDouble(), 0.0),
                    Point(grayMat.cols().toDouble(), grayMat.rows().toDouble()),
                    Point(0.0, grayMat.rows().toDouble())
                )
                referenceCorners.fromArray(*corners)

                val featureCount = referenceKeyPoints.total()
                Log.i(TAG, "Reference image loaded: $featureCount features detected")
                Log.i(TAG, "Reference corners: ${corners.contentToString()}")

                grayMat.release()
            } else {
                Log.e(TAG, "Failed to decode reference image bitmap")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load reference image: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading reference image: ${e.message}")
        }
    }

    /**
     * Main card detection function
     */
    suspend fun detectCard(
        inputBitmap: Bitmap,
        overlayBounds: Rect? = null,
        imageSize: Size? = null
    ): CardDetectionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            // Check prerequisites
            if (!isORBAvailable()) {
                return@withContext CardDetectionResult(
                    isCardDetected = false,
                    confidence = 0.0f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    errorMessage = "ORB not available"
                )
            }

            if (referenceDescriptors.empty()) {
                return@withContext CardDetectionResult(
                    isCardDetected = false,
                    confidence = 0.0f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    errorMessage = "Reference features not loaded"
                )
            }

            // Convert input to OpenCV Mat
            val inputMat = Mat()
            Utils.bitmapToMat(inputBitmap, inputMat)

            val grayMat = Mat()
            if (inputMat.channels() > 1) {
                Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_BGR2GRAY)
            } else {
                inputMat.copyTo(grayMat)
            }

            // Extract features from input image
            val inputKeyPoints = MatOfKeyPoint()
            val inputDescriptors = Mat()

            orb.detectAndCompute(grayMat, Mat(), inputKeyPoints, inputDescriptors)

            if (inputDescriptors.empty()) {
                return@withContext CardDetectionResult(
                    isCardDetected = false,
                    confidence = 0.0f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    errorMessage = "No features detected in input image"
                )
            }

            Log.d(TAG, "Features detected: input=${inputKeyPoints.total()}, reference=${referenceKeyPoints.total()}")

            // Match features
            val matches = mutableListOf<MatOfDMatch>()
            matcher.knnMatch(inputDescriptors, referenceDescriptors, matches, 2)

            // Filter good matches using Lowe's ratio test
            val goodMatches = mutableListOf<DMatch>()
            for (matchList in matches) {
                val matchArray = matchList.toArray()
                if (matchArray.size >= 2) {
                    val bestMatch = matchArray[0]
                    val secondBestMatch = matchArray[1]

                    if (bestMatch.distance < GOOD_MATCH_RATIO * secondBestMatch.distance) {
                        goodMatches.add(bestMatch)
                    }
                }
            }

            Log.d(TAG, "Good matches found: ${goodMatches.size}")

            if (goodMatches.size < MIN_MATCHES_FOR_DETECTION) {
                Log.d(TAG, "❌ Detection failed: Insufficient matches ${goodMatches.size} < $MIN_MATCHES_FOR_DETECTION")
                return@withContext CardDetectionResult(
                    isCardDetected = false,
                    confidence = 0.0f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    errorMessage = "Insufficient matches: ${goodMatches.size} < $MIN_MATCHES_FOR_DETECTION"
                )
            }

            // Find homography if enough matches
            if (goodMatches.size < MIN_HOMOGRAPHY_MATCHES) {
                return@withContext CardDetectionResult(
                    isCardDetected = false,
                    confidence = 0.0f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    errorMessage = "Insufficient matches for homography: ${goodMatches.size} < $MIN_HOMOGRAPHY_MATCHES"
                )
            }

            val result = findHomographyAndValidateCard(
                goodMatches,
                inputKeyPoints,
                referenceKeyPoints,
                grayMat,
                overlayBounds,
                imageSize
            )

            // Cleanup
            inputMat.release()
            grayMat.release()
            inputKeyPoints.release()
            inputDescriptors.release()

            result.copy(processingTimeMs = System.currentTimeMillis() - startTime)

        } catch (e: Exception) {
            Log.e(TAG, "Card detection error: ${e.message}")
            return@withContext CardDetectionResult(
                isCardDetected = false,
                confidence = 0.0f,
                processingTimeMs = System.currentTimeMillis() - startTime,
                errorMessage = "Detection error: ${e.message}"
            )
        }
    }

    /**
     * Find homography and validate detected card
     */
    private fun findHomographyAndValidateCard(
        goodMatches: List<DMatch>,
        inputKeyPoints: MatOfKeyPoint,
        referenceKeyPoints: MatOfKeyPoint,
        imageMat: Mat,
        overlayBounds: Rect?,
        imageSize: Size?
    ): CardDetectionResult {
        try {
            // Extract matching points
            val srcPoints = MatOfPoint2f()
            val dstPoints = MatOfPoint2f()

            val srcArray = goodMatches.map { match ->
                referenceKeyPoints.toArray()[match.trainIdx].pt
            }.toTypedArray()

            val dstArray = goodMatches.map { match ->
                inputKeyPoints.toArray()[match.queryIdx].pt
            }.toTypedArray()

            srcPoints.fromArray(*srcArray)
            dstPoints.fromArray(*dstArray)

            // Find homography
            val homography = Calib3d.findHomography(
                srcPoints, dstPoints,
                Calib3d.RANSAC,
                HOMOGRAPHY_THRESHOLD,
                Mat(),
                2000,
                HOMOGRAPHY_CONFIDENCE
            )

            if (homography.empty()) {
                return CardDetectionResult(
                    isCardDetected = false,
                    confidence = 0.0f,
                    errorMessage = "Homography calculation failed"
                )
            }

            // Transform reference corners to input image
            val transformedCorners = MatOfPoint2f()
            Core.perspectiveTransform(referenceCorners, transformedCorners, homography)

            val corners = transformedCorners.toArray()
            Log.d(TAG, "Transformed corners: ${corners.contentToString()}")

            // Validate card geometry
            val validation = validateCardGeometry(corners, imageMat, overlayBounds, imageSize)
            if (!validation.isValid) {
                return CardDetectionResult(
                    isCardDetected = false,
                    confidence = 0.0f,
                    errorMessage = validation.errorMessage
                )
            }

            // Calculate confidence
            val confidence = calculateConfidence(goodMatches.size, homography, corners)

            // Calculate bounding box
            val boundingBox = calculateBoundingBox(corners)

            // Cleanup
            srcPoints.release()
            dstPoints.release()
            transformedCorners.release()
            homography.release()

            return CardDetectionResult(
                isCardDetected = true,
                confidence = confidence,
                corners = corners,
                boundingBox = boundingBox,
                matchCount = goodMatches.size,
                cardArea = validation.cardArea,
                aspectRatio = validation.aspectRatio
            )

        } catch (e: Exception) {
            Log.e(TAG, "Homography validation error: ${e.message}")
            return CardDetectionResult(
                isCardDetected = false,
                confidence = 0.0f,
                errorMessage = "Homography error: ${e.message}"
            )
        }
    }

    /**
     * Validate card geometry (area, aspect ratio, overlay position)
     */
    private fun validateCardGeometry(
        corners: Array<Point>,
        imageMat: Mat,
        overlayBounds: Rect?,
        imageSize: Size?
    ): CardValidation {
        try {
            val imageWidth = imageMat.cols()
            val imageHeight = imageMat.rows()
            val imageArea = imageWidth * imageHeight

            // Check if corners are valid (within image bounds)
            for (corner in corners) {
                if (corner.x < 0 || corner.x > imageWidth || corner.y < 0 || corner.y > imageHeight) {
                    return CardValidation(false, "Corner outside image bounds: $corner")
                }
            }

            // Calculate card area using cross product
            val cardArea = calculatePolygonArea(corners)
            val areaRatio = cardArea / imageArea

            Log.d(TAG, "Card area: $cardArea, Image area: $imageArea, Ratio: $areaRatio")

            if (areaRatio < MIN_AREA_RATIO) {
                return CardValidation(false, "Card too small: ratio=$areaRatio < $MIN_AREA_RATIO")
            }

            if (areaRatio > MAX_AREA_RATIO) {
                return CardValidation(false, "Card too large: ratio=$areaRatio > $MAX_AREA_RATIO")
            }

            // Calculate card dimensions
            val width1 = distanceBetweenPoints(corners[0], corners[1])
            val width2 = distanceBetweenPoints(corners[2], corners[3])
            val height1 = distanceBetweenPoints(corners[1], corners[2])
            val height2 = distanceBetweenPoints(corners[3], corners[0])

            val avgWidth = (width1 + width2) / 2
            val avgHeight = (height1 + height2) / 2

            Log.d(TAG, "Card dimensions: width=$avgWidth, height=$avgHeight")

            if (avgWidth < MIN_CARD_WIDTH || avgHeight < MIN_CARD_HEIGHT) {
                return CardValidation(false, "Card dimensions too small: ${avgWidth}x${avgHeight} (min: ${MIN_CARD_WIDTH}x${MIN_CARD_HEIGHT})")
            }

            // Calculate diagonal length for additional size validation
            val diagonal = kotlin.math.sqrt(avgWidth * avgWidth + avgHeight * avgHeight)
            Log.d(TAG, "Card diagonal: $diagonal (min required: $MIN_CARD_DIAGONAL)")

            if (diagonal < MIN_CARD_DIAGONAL) {
                return CardValidation(false, "Card too small - diagonal: $diagonal < $MIN_CARD_DIAGONAL")
            }

            // Calculate aspect ratio
            val aspectRatio = avgWidth / avgHeight
            val aspectDiff = abs(aspectRatio - ID_CARD_ASPECT_RATIO)

            Log.d(TAG, "Aspect ratio: $aspectRatio (target: $ID_CARD_ASPECT_RATIO, diff: $aspectDiff)")

            if (aspectDiff > ASPECT_RATIO_TOLERANCE) {
                return CardValidation(false, "Invalid aspect ratio: $aspectRatio (expected: $ID_CARD_ASPECT_RATIO±$ASPECT_RATIO_TOLERANCE)")
            }

            // Validate overlay position if provided
            overlayBounds?.let { overlay ->
                val validation = validateOverlayPosition(corners, overlay, imageSize)
                if (!validation.isValid) {
                    return validation
                }
            }

            return CardValidation(true, cardArea = cardArea, aspectRatio = aspectRatio)

        } catch (e: Exception) {
            return CardValidation(false, "Geometry validation error: ${e.message}")
        }
    }

    /**
     * Validate that card corners are within overlay bounds and card covers sufficient area
     */
    private fun validateOverlayPosition(corners: Array<Point>, overlayBounds: Rect, imageSize: Size?): CardValidation {
        try {
            // Convert overlay bounds to image coordinates if needed
            val overlay = if (imageSize != null) {
                // Scale overlay bounds to match image size
                val scaleX = imageSize.width.toFloat() / 1920f // Assuming camera resolution
                val scaleY = imageSize.height.toFloat() / 1080f

                Rect(
                    (overlayBounds.left * scaleX).toInt(),
                    (overlayBounds.top * scaleY).toInt(),
                    (overlayBounds.right * scaleX).toInt(),
                    (overlayBounds.bottom * scaleY).toInt()
                )
            } else {
                overlayBounds
            }

            Log.d(TAG, "Overlay bounds: $overlay")

            // Check if all corners are within overlay with strict margin
            val margin = 5 // Very small margin for strict overlay compliance
            var cornersOutside = 0
            for (i in corners.indices) {
                val corner = corners[i]
                if (corner.x < overlay.left - margin || corner.x > overlay.right + margin ||
                    corner.y < overlay.top - margin || corner.y > overlay.bottom + margin) {
                    Log.d(TAG, "Corner $i outside overlay: $corner not in $overlay")
                    cornersOutside++
                }
            }

            // Allow maximum 1 corner slightly outside for flexibility
            if (cornersOutside > 1) {
                return CardValidation(false, "Too many corners ($cornersOutside) outside overlay guide")
            }

            // Calculate card area within overlay
            val cardArea = calculatePolygonArea(corners)
            val overlayArea = (overlay.width() * overlay.height()).toDouble()
            val coverageRatio = cardArea / overlayArea

            Log.d(TAG, "Overlay coverage: card=$cardArea, overlay=$overlayArea, ratio=$coverageRatio")

            if (coverageRatio < MIN_OVERLAY_COVERAGE) {
                return CardValidation(false, "Card covers too little of overlay: ${String.format("%.2f", coverageRatio)} < $MIN_OVERLAY_COVERAGE")
            }

            Log.d(TAG, "✅ Card properly positioned in overlay (coverage: ${String.format("%.2f", coverageRatio)})")
            return CardValidation(true)

        } catch (e: Exception) {
            return CardValidation(false, "Overlay validation error: ${e.message}")
        }
    }

    /**
     * Calculate polygon area using shoelace formula
     */
    private fun calculatePolygonArea(corners: Array<Point>): Double {
        var area = 0.0
        val n = corners.size

        for (i in 0 until n) {
            val j = (i + 1) % n
            area += corners[i].x * corners[j].y
            area -= corners[j].x * corners[i].y
        }

        return abs(area) / 2.0
    }

    /**
     * Calculate distance between two points
     */
    private fun distanceBetweenPoints(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Calculate bounding box from corners
     */
    private fun calculateBoundingBox(corners: Array<Point>): Rect {
        val minX = corners.minOf { it.x }.toInt()
        val maxX = corners.maxOf { it.x }.toInt()
        val minY = corners.minOf { it.y }.toInt()
        val maxY = corners.maxOf { it.y }.toInt()

        return Rect(minX, minY, maxX, maxY)
    }

    /**
     * Calculate detection confidence with size and distance penalties
     */
    private fun calculateConfidence(matchCount: Int, homography: Mat, corners: Array<Point>): Float {
        var confidence = 0.0f

        // Base confidence from match count (30%)
        val matchConfidence = (matchCount.toFloat() / (MIN_MATCHES_FOR_DETECTION * 3)).coerceIn(0.0f, 1.0f)
        confidence += matchConfidence * 0.3f

        // Geometric confidence (25%)
        val geometryConfidence = calculateGeometryConfidence(corners)
        confidence += geometryConfidence * 0.25f

        // Size confidence - penalize very small cards (25%)
        val sizeConfidence = calculateSizeConfidence(corners)
        confidence += sizeConfidence * 0.25f

        // Distance confidence - penalize distant cards (20%)
        val distanceConfidence = calculateDistanceConfidence(corners)
        confidence += distanceConfidence * 0.2f

        Log.d(TAG, "Confidence breakdown: match=$matchConfidence, geometry=$geometryConfidence, size=$sizeConfidence, distance=$distanceConfidence")

        return confidence.coerceIn(0.0f, 1.0f)
    }

    /**
     * Calculate confidence based on geometry
     */
    private fun calculateGeometryConfidence(corners: Array<Point>): Float {
        try {
            // Check if corners form a reasonable quadrilateral
            val width1 = distanceBetweenPoints(corners[0], corners[1])
            val width2 = distanceBetweenPoints(corners[2], corners[3])
            val height1 = distanceBetweenPoints(corners[1], corners[2])
            val height2 = distanceBetweenPoints(corners[3], corners[0])

            // Check width consistency
            val widthDiff = abs(width1 - width2) / maxOf(width1, width2)
            val heightDiff = abs(height1 - height2) / maxOf(height1, height2)

            val consistencyConfidence = 1.0f - ((widthDiff + heightDiff) / 2.0f).toFloat()

            return consistencyConfidence.coerceIn(0.0f, 1.0f)

        } catch (e: Exception) {
            return 0.0f
        }
    }

    /**
     * Calculate confidence based on card size (penalize very small cards)
     */
    private fun calculateSizeConfidence(corners: Array<Point>): Float {
        try {
            val width1 = distanceBetweenPoints(corners[0], corners[1])
            val width2 = distanceBetweenPoints(corners[2], corners[3])
            val height1 = distanceBetweenPoints(corners[1], corners[2])
            val height2 = distanceBetweenPoints(corners[3], corners[0])

            val avgWidth = (width1 + width2) / 2
            val avgHeight = (height1 + height2) / 2
            val diagonal = kotlin.math.sqrt(avgWidth * avgWidth + avgHeight * avgHeight)

            // Size confidence based on diagonal length
            val sizeConfidence = when {
                diagonal >= 500 -> 1.0f          // Large card = full confidence
                diagonal >= 400 -> 0.8f          // Medium-large card
                diagonal >= MIN_CARD_DIAGONAL -> 0.5f  // Minimum acceptable size
                else -> 0.0f                     // Too small = no confidence
            }

            Log.d(TAG, "Size confidence: diagonal=$diagonal -> confidence=$sizeConfidence")
            return sizeConfidence

        } catch (e: Exception) {
            return 0.0f
        }
    }

    /**
     * Calculate confidence based on distance (inferred from card size and position)
     */
    private fun calculateDistanceConfidence(corners: Array<Point>): Float {
        try {
            // Calculate card area
            val cardArea = calculatePolygonArea(corners)

            // Calculate position relative to image center
            val centerX = corners.map { it.x }.average()
            val centerY = corners.map { it.y }.average()

            // Assume image is 640x480 (our processing resolution)
            val imageCenterX = 320.0
            val imageCenterY = 240.0

            val distanceFromCenter = kotlin.math.sqrt(
                (centerX - imageCenterX) * (centerX - imageCenterX) +
                (centerY - imageCenterY) * (centerY - imageCenterY)
            )

            // Distance confidence: cards near center and with good area get higher confidence
            val areaConfidence = when {
                cardArea >= 50000 -> 1.0f        // Large area = close distance
                cardArea >= 30000 -> 0.8f        // Medium area
                cardArea >= 20000 -> 0.5f        // Small area
                else -> 0.0f                     // Very small area = too far
            }

            val centerConfidence = when {
                distanceFromCenter <= 100 -> 1.0f    // Very centered
                distanceFromCenter <= 150 -> 0.8f    // Reasonably centered
                distanceFromCenter <= 200 -> 0.5f    // Off-center but acceptable
                else -> 0.2f                         // Too far from center
            }

            val distanceConfidence = (areaConfidence + centerConfidence) / 2f

            Log.d(TAG, "Distance confidence: area=$cardArea, centerDist=$distanceFromCenter -> confidence=$distanceConfidence")
            return distanceConfidence

        } catch (e: Exception) {
            return 0.0f
        }
    }

    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up ORB Card Detector...")

            if (!referenceKeyPoints.empty()) {
                referenceKeyPoints.release()
            }

            if (!referenceDescriptors.empty()) {
                referenceDescriptors.release()
            }

            if (!referenceMat.empty()) {
                referenceMat.release()
            }

            if (!referenceCorners.empty()) {
                referenceCorners.release()
            }

            Log.i(TAG, "ORB Card Detector cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    /**
     * Card detection result data class
     */
    data class CardDetectionResult(
        val isCardDetected: Boolean,
        val confidence: Float,
        val corners: Array<Point>? = null,
        val boundingBox: Rect? = null,
        val matchCount: Int = 0,
        val cardArea: Double = 0.0,
        val aspectRatio: Double = 0.0,
        val processingTimeMs: Long = 0L,
        val errorMessage: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CardDetectionResult

            if (isCardDetected != other.isCardDetected) return false
            if (confidence != other.confidence) return false
            if (corners != null) {
                if (other.corners == null) return false
                if (!corners.contentEquals(other.corners)) return false
            } else if (other.corners != null) return false
            if (boundingBox != other.boundingBox) return false

            return true
        }

        override fun hashCode(): Int {
            var result = isCardDetected.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + (corners?.contentHashCode() ?: 0)
            result = 31 * result + (boundingBox?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Card validation result
     */
    private data class CardValidation(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val cardArea: Double = 0.0,
        val aspectRatio: Double = 0.0
    )
}