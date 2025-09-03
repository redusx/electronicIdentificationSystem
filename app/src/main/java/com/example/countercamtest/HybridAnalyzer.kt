package com.example.countercamtest

import android.content.Context
import android.graphics.BitmapFactory
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.countercamtest.ReferenceIdData
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class HybridAnalyzer(
    context: Context,
    private val onResult: (ValidationResult) -> Unit
) : ImageAnalysis.Analyzer {

    data class ValidationResult(
        val isValid: Boolean,
        val corners: List<DoubleArray>? = null,
        val srcWidth: Int = 0,
        val srcHeight: Int = 0,
        val rotationDegrees: Int = 0,
        val fps: Double = 0.0
    )

    private val orb: ORB = ORB.create(500)
    private val matcher: BFMatcher = BFMatcher.create(Core.NORM_HAMMING)
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    private val refMatWarped = Mat()
    private val refKeyPoints = MatOfKeyPoint()
    private val refDescriptors = Mat()
    private var lastTime = System.currentTimeMillis()

    init {
        // --- DEĞİŞİKLİK 1: Referans Görüntüyü Doğru Boyutlandırma ---
        val refBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ref_id_card)
        val tempMat = Mat()
        Utils.bitmapToMat(refBitmap, tempMat)

        // Referans görüntüyü, ReferenceIdData'daki boyutlara ölçekle
        val targetSize = Size(ReferenceIdData.REF_WIDTH, ReferenceIdData.REF_HEIGHT)
        Imgproc.resize(tempMat, refMatWarped, targetSize)

        // Artık doğru boyutlandırılmış Mat üzerinde işlemlere devam et
        Imgproc.cvtColor(refMatWarped, refMatWarped, Imgproc.COLOR_RGBA2GRAY)
        tempMat.release() // Geçici Mat'i serbest bırak

        // Maske artık %100 doğru şekilde, ölçeklenmiş resimle aynı boyutta olacak
        val mask = Mat.zeros(refMatWarped.size(), CvType.CV_8UC1)
        val white = Scalar(255.0)
        Imgproc.rectangle(mask, ReferenceIdData.chipArea.tl(), ReferenceIdData.chipArea.br(), white, -1)
        Imgproc.rectangle(mask, ReferenceIdData.mrzArea.tl(), ReferenceIdData.mrzArea.br(), white, -1)
        Imgproc.rectangle(mask, ReferenceIdData.barcodeArea.tl(), ReferenceIdData.barcodeArea.br(), white, -1)
        orb.detectAndCompute(refMatWarped, mask, refKeyPoints, refDescriptors)
        mask.release()
    }

    override fun analyze(image: ImageProxy) {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val frameMatGray = image.toGrayMat(rotationDegrees)
        val frameClahe = Mat()
        clahe.apply(frameMatGray, frameClahe)

        val cardCandidates = findCardCandidates(frameClahe)
        var finalResult = ValidationResult(isValid = false)

        for (candidateCornersMat in cardCandidates) {
            if (verifyCardContent(frameClahe, candidateCornersMat)) {
                val cornersList = listOf(
                    candidateCornersMat.get(0, 0),
                    candidateCornersMat.get(1, 0),
                    candidateCornersMat.get(2, 0),
                    candidateCornersMat.get(3, 0)
                )
                finalResult = ValidationResult(isValid = true, corners = cornersList)
                break
            }
        }

        cardCandidates.forEach { it.release() }

        val rotatedSize = image.getRotatedSize(rotationDegrees)
        val currentTime = System.currentTimeMillis()
        val fps = if (currentTime > lastTime) 1000.0 / (currentTime - lastTime) else 0.0
        lastTime = currentTime

        onResult(
            finalResult.copy(
                fps = fps,
                srcWidth = rotatedSize.first,
                srcHeight = rotatedSize.second,
                rotationDegrees = rotationDegrees
            )
        )

        frameMatGray.release()
        frameClahe.release()
        image.close()
    }

    private fun findCardCandidates(image: Mat): List<Mat> {
        val candidates = mutableListOf<Mat>()
        val blurredMat = Mat()
        Imgproc.GaussianBlur(image, blurredMat, Size(5.0, 5.0), 0.0)

        val cannyMat = Mat()
        Imgproc.Canny(blurredMat, cannyMat, 50.0, 150.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        val closedMat = Mat()
        Imgproc.morphologyEx(cannyMat, closedMat, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closedMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        contours.sortByDescending { Imgproc.contourArea(it) }

        for (contour in contours.take(5)) {
            val matOfPoint2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(matOfPoint2f, true)
            val approx = MatOfPoint2f()
            // --- DEĞİŞİKLİK 2: Daha hassas köşe tespiti için tolerans düşürüldü ---
            Imgproc.approxPolyDP(matOfPoint2f, approx, 0.02 * perimeter, true)

            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                // --- DEĞİŞİKLİK 3: En-Boy Oranı (Aspect Ratio) Filtresi Eklendi ---
                val rect = Imgproc.minAreaRect(matOfPoint2f)
                val size = rect.size
                val aspectRatio = if (size.height != 0.0) max(size.width, size.height) / min(size.width, size.height) else 0.0

                val expectedAspectRatio = 1.586
                val tolerance = 0.4 // %40'a kadar tolerans
                if (abs(aspectRatio - expectedAspectRatio) < tolerance) {
                    val corners = Mat(4, 1, CvType.CV_32FC2)
                    val points = approx.toArray().sortedWith(compareBy({ it.y }, { it.x }))
                    val topPoints = points.take(2).sortedBy { it.x }
                    val bottomPoints = points.takeLast(2).sortedBy { it.x }
                    corners.put(0, 0, floatArrayOf(topPoints[0].x.toFloat(), topPoints[0].y.toFloat()))
                    corners.put(1, 0, floatArrayOf(topPoints[1].x.toFloat(), topPoints[1].y.toFloat()))
                    corners.put(2, 0, floatArrayOf(bottomPoints[1].x.toFloat(), bottomPoints[1].y.toFloat()))
                    corners.put(3, 0, floatArrayOf(bottomPoints[0].x.toFloat(), bottomPoints[0].y.toFloat()))
                    candidates.add(corners)
                }
            }
            approx.release()
            matOfPoint2f.release()
            contour.release()
        }

        blurredMat.release()
        cannyMat.release()
        closedMat.release()
        hierarchy.release()
        kernel.release()

        return candidates
    }

    private fun verifyCardContent(image: Mat, corners: Mat): Boolean {
        val warpedMat = Mat()
        val targetCorners = Mat(4, 1, CvType.CV_32FC2).apply {
            put(0, 0, floatArrayOf(0f, 0f))
            put(1, 0, floatArrayOf(ReferenceIdData.REF_WIDTH.toFloat(), 0f))
            put(2, 0, floatArrayOf(ReferenceIdData.REF_WIDTH.toFloat(), ReferenceIdData.REF_HEIGHT.toFloat()))
            put(3, 0, floatArrayOf(0f, ReferenceIdData.REF_HEIGHT.toFloat()))
        }
        val transform = Imgproc.getPerspectiveTransform(corners, targetCorners)
        Imgproc.warpPerspective(image, warpedMat, transform, Size(ReferenceIdData.REF_WIDTH, ReferenceIdData.REF_HEIGHT))

        val sceneKeyPoints = MatOfKeyPoint()
        val sceneDescriptors = Mat()
        orb.detectAndCompute(warpedMat, Mat(), sceneKeyPoints, sceneDescriptors)

        var result = false
        if (!refDescriptors.empty() && !sceneDescriptors.empty()) {
            val knnMatches = mutableListOf<MatOfDMatch>()
            matcher.knnMatch(refDescriptors, sceneDescriptors, knnMatches, 2)
            val ratioThreshold = 0.75f
            val goodMatchesCount = knnMatches.count {
                val good = it.rows() > 1 && it.toArray()[0].distance < ratioThreshold * it.toArray()[1].distance
                it.release()
                good
            }
            if (goodMatchesCount > 8) {
                result = true
            }
        }

        warpedMat.release()
        targetCorners.release()
        transform.release()
        sceneKeyPoints.release()
        sceneDescriptors.release()

        return result
    }

    private fun ImageProxy.toGrayMat(rotationDegrees: Int): Mat {
        val yPlane = planes[0]
        val yBuffer: ByteBuffer = yPlane.buffer
        val yData = ByteArray(yBuffer.remaining())
        yBuffer.get(yData)
        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, yData)
        return when (rotationDegrees) {
            90 -> mat.t().also { Core.flip(it, it, 1) }
            180 -> mat.also { Core.flip(it, it, -1) }
            270 -> mat.also { Core.flip(it, it, 0) }
            else -> mat
        }
    }

    private fun ImageProxy.getRotatedSize(rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees == 90 || rotationDegrees == 270) Pair(height, width) else Pair(width, height)
}