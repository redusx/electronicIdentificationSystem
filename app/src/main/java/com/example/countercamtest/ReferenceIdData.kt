package com.example.countercamtest

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect

object ReferenceIdData {
    // Referans resmin orijinal boyutları
    const val REF_WIDTH = 1536.0
    const val REF_HEIGHT = 1024.0

    // Referans resim üzerindeki bölgelerin (Region of Interest - ROI)
    // x, y, genişlik, yükseklik olarak tanımlanması.
    // Bu değerler referans resim üzerinden manuel olarak ölçülmüştür.
    val chipArea = Rect(131, 329, 257, 233)
    val barcodeArea = Rect(650, 41, 548, 86)
    val mrzArea = Rect(32, 663, 1479, 259)
    val penNumberArea = Rect(1486, 173, 49, 262) // Dikey metin olduğu için
    val ministryText = Rect(418, 234, 640, 314)

    // Referans kartın köşe noktaları
    val corners = Mat(4, 1, CvType.CV_32FC2).apply {
        put(0, 0, floatArrayOf(0f, 0f))
        put(1, 0, floatArrayOf(REF_WIDTH.toFloat(), 0f))
        put(2, 0, floatArrayOf(REF_WIDTH.toFloat(), REF_HEIGHT.toFloat()))
        put(3, 0, floatArrayOf(0f, REF_HEIGHT.toFloat()))
    }

}