package com.example.countercamtest

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class ContourAnalyzer(
    private val params: Params,
    private val onResult: (Frame) -> Unit
) : ImageAnalysis.Analyzer {

    // Canny algoritması için yeni parametreler
    data class Params(
        var cannyThreshold1: Double, // Düşük eşik
        var cannyThreshold2: Double, // Yüksek eşik
        var minContourArea: Double   // Çok küçük (gürültü) konturları filtrelemek için alan
    )

    data class Frame(
        val segmentsXYXY: FloatArray,
        val srcWidth: Int,
        val srcHeight: Int,
        val fps: Double
    )

    private var lastTime = System.currentTimeMillis()

    // OpenCV Mat nesnelerini tekrar tekrar oluşturmamak için önceden tanımla
    private lateinit var yuvMat: Mat
    private lateinit var grayMat: Mat
    private lateinit var blurMat: Mat
    private lateinit var cannyMat: Mat

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val width = image.width
        val height = image.height

        // Mat nesneleri oluşturulmadıysa, ilk seferde oluştur
        if (!::yuvMat.isInitialized) {
            // YUV formatı 1.5 kat daha fazla satıra sahiptir
            yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
            grayMat = Mat(height, width, CvType.CV_8UC1)
            blurMat = Mat(height, width, CvType.CV_8UC1)
            cannyMat = Mat(height, width, CvType.CV_8UC1)
        }

        // Kamera verisini (YUV) OpenCV Mat'ine kopyala
        val yPlaneBuffer = image.planes[0].buffer
        yPlaneBuffer.rewind()
        val yBytes = ByteArray(yPlaneBuffer.remaining())
        yPlaneBuffer.get(yBytes)
        yuvMat.put(0, 0, yBytes)

        // YUV'dan Gri tonlamalı görüntüye dönüştür
        Imgproc.cvtColor(yuvMat, grayMat, Imgproc.COLOR_YUV2GRAY_NV21)

        // 1. Adım: Gürültüyü Azalt (Gaussian Blur)
        Imgproc.GaussianBlur(grayMat, blurMat, Size(5.0, 5.0), 0.0)

        // 2. Adım: Canny Kenar Tespiti
        Imgproc.Canny(blurMat, cannyMat, params.cannyThreshold1, params.cannyThreshold2)

        // 3. Adım: Kenarlardan Konturları Bul
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(cannyMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val allSegments = mutableListOf<Float>()

        // 4. Adım: Konturları Çizgi Segmentlerine Dönüştür
        for (contour in contours) {
            // Çok küçük konturları (gürültü) atla
            if (Imgproc.contourArea(contour) > params.minContourArea) {
                val points = contour.toArray()
                if (points.size > 1) {
                    for (i in 1 until points.size) {
                        val p1 = points[i - 1]
                        val p2 = points[i]
                        allSegments.addAll(listOf(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat()))
                    }
                    // Konturu kapatmak için son noktayı ilk noktaya bağla
                    val first = points[0]
                    val last = points[points.size - 1]
                    allSegments.addAll(listOf(last.x.toFloat(), last.y.toFloat(), first.x.toFloat(), first.y.toFloat()))
                }
            }
            contour.release() // Belleği serbest bırak
        }
        hierarchy.release()

        // 5. Adım: Koordinatları ve Boyutları Döndürme
        val finalSegments = rotateAllSegments(allSegments.toFloatArray(), width, height, rotationDegrees)

        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

        // FPS hesapla ve sonucu gönder
        val currentTime = System.currentTimeMillis()
        val fps = if (currentTime > lastTime) 1000.0 / (currentTime - lastTime) else 0.0
        lastTime = currentTime

        onResult(Frame(finalSegments, rotatedWidth, rotatedHeight, fps))

        image.close()
    }

    // Bu fonksiyonlar aynı kalabilir
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

    private fun rotatePoint(x: Float, y: Float, w: Float, h: Float, rotation: Int): Pair<Float, Float> {
        return when (rotation) {
            // Telefon sağa 90 derece yatırıldığında (saatin tersi yönünde)
            // Görüntü saat yönünde 90 derece döner.
            // Noktanın yeni X'i, eski Y olur.
            // Noktanın yeni Y'si, (genişlik - eski X) olur.
            90 -> Pair(h - 1 - y, x)

            // Telefon baş aşağı 180 derece döndürüldüğünde.
            // Noktanın yeni X'i (genişlik - eski X)
            // Noktanın yeni Y'si (yükseklik - eski Y)
            // SENİN SORUNUNUN ÇÖZÜMÜ MUHTEMELEN BU SATIRDA
            180 -> Pair(w - 1 - x, h - 1 - y)

            // Telefon sola 90 derece yatırıldığında (saat yönünde)
            // Görüntü saatin tersi yönünde 90 derece (veya 270 derece saat yönünde) döner.
            // Noktanın yeni X'i (yükseklik - eski Y)
            // Noktanın yeni Y'si, eski X olur.
            270 -> Pair(h - 1 - y, x)

            // 0 derece (Dikey)
            else -> Pair(x, y)
        }
    }
}