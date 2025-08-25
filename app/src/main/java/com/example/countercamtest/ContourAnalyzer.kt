// Dosya: ContourAnalyzer.kt
package com.example.countercamtest // Paket adını kontrol et

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f // EKSİK OLAN IMPORT
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

class ContourAnalyzer(
    private val params: Params,
    private val onResult: (Frame) -> Unit
) : ImageAnalysis.Analyzer {

    data class Params(
        var cannyThreshold1: Double,
        var cannyThreshold2: Double,
        var minContourArea: Double
    )

    data class Frame(
        val segmentsXYXY: FloatArray,
        val srcWidth: Int,
        val srcHeight: Int,
        val fps: Double
    )

    private var lastTime = System.currentTimeMillis()

    private lateinit var grayMat: Mat
    private lateinit var claheMat: Mat
    private lateinit var blurMat: Mat
    private lateinit var cannyMat: Mat
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))

    override fun analyze(image: ImageProxy) {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val width = image.width
        val height = image.height

        if (!::grayMat.isInitialized) {
            grayMat = Mat(height, width, CvType.CV_8UC1)
            claheMat = Mat(height, width, CvType.CV_8UC1)
            blurMat = Mat(height, width, CvType.CV_8UC1)
            cannyMat = Mat(height, width, CvType.CV_8UC1)
        }

        val yPlane = image.planes[0]
        val yBuffer: ByteBuffer = yPlane.buffer
        val yData = ByteArray(yBuffer.remaining())
        yBuffer.get(yData)

        grayMat.put(0, 0, yData)

        clahe.apply(grayMat, claheMat)
        Imgproc.GaussianBlur(claheMat, blurMat, Size(3.0, 3.0), 0.0)
        Imgproc.Canny(blurMat, cannyMat, params.cannyThreshold1, params.cannyThreshold2)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(cannyMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val allSegments = mutableListOf<Float>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)

            if (area > params.minContourArea && perimeter > 15) {
                val points = contour.toArray()
                if (points.size > 1) {
                    for (i in 1 until points.size) {
                        val p1 = points[i - 1]
                        val p2 = points[i]
                        allSegments.addAll(listOf(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat()))
                    }
                }
            }
            contour.release()
        }
        hierarchy.release()

        val finalSegments = rotateAllSegments(allSegments.toFloatArray(), width, height, rotationDegrees)

        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

        val currentTime = System.currentTimeMillis()
        val fps = if (currentTime > lastTime) 1000.0 / (currentTime - lastTime) else 0.0
        lastTime = currentTime

        onResult(Frame(finalSegments, rotatedWidth, rotatedHeight, fps))

        image.close()
    }

    // EKSİK OLAN FONKSİYON
    private fun rotateAllSegments(segments: FloatArray, w: Int, h: Int, rotation: Int): FloatArray {
        if (rotation == 0) return segments

        val rotated = FloatArray(segments.size)
        for (i in segments.indices step 2) {
            val rotatedPoint = rotatePoint(segments[i], segments[i + 1], w.toFloat(), h.toFloat(), rotation)
            rotated[i] = rotatedPoint.first
            rotated[i + 1] = rotatedPoint.second
        }
        return rotated
    }

    // Senin bulduğun doğru çalışan fonksiyon
    private fun rotatePoint(x: Float, y: Float, w: Float, h: Float, rotation: Int): Pair<Float, Float> {
        return when (rotation) {
            90 -> Pair(h - 1 - y, x)
            180 -> Pair(w - 1 - x, h - 1 - y)
            270 -> Pair(y, w - 1 - x)
            else -> Pair(x, y)
        }
    }
}