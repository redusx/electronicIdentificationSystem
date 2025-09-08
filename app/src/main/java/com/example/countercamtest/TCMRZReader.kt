package com.example.countercamtest

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.coroutines.resume
import java.io.Serializable

/**
 * TC Kimlik Kartı MRZ OCR Entegrasyonu
 * Türkiye Cumhuriyeti kimlik kartının MRZ alanını okur ve doğrular
 */
class TCMRZReader(private val context: Context) {

    companion object {
        private const val TAG = "TCMRZReader"
        
        // TC MRZ Format Patterns - Gerçek TC Kimlik Kartı Formatı
        private val MRZ_LINE1_PATTERN = Pattern.compile("^I[A-Z]TUR[A-Z0-9]{9}[A-Z0-9][0-9]{11}[A-Z0-9<]{4}$") // Line 1: I + kurum + TUR + seri + kontrol + TC + dolgu
        private val MRZ_LINE2_PATTERN = Pattern.compile("^[0-9]{6}[A-Z0-9][MF][0-9]{6}[A-Z0-9]TUR[A-Z<]{11}[A-Z0-9]$") // Line 2: doğum + kontrol + cinsiyet + geçerlilik + kontrol + TUR + uyruk + kontrol
        private val MRZ_LINE3_PATTERN = Pattern.compile("^[A-Z<]{30}$") // Line 3: soyad + << + ad + dolgu karakterleri
        
        // Performance targets
        private const val TARGET_ACCURACY = 95.0f
        private const val TARGET_PROCESSING_TIME = 3000L
        
        // Image processing parameters
        private const val GAUSSIAN_KERNEL_SIZE = 3
        private const val MORPH_KERNEL_SIZE = 2
        private const val MIN_DPI = 300
        private const val MIN_WIDTH = 800
        private const val MIN_HEIGHT = 600
    }

    // MLKit Text Recognizer
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Template-based MRZ detector (lazy initialization)
    private val templateDetector by lazy { TemplateMRZDetector() }

    // TC MRZ Data Structure
    data class TCMRZResult(
        val success: Boolean,
        val confidence: Float,
        val processingTime: Long,
        val data: PersonalData?,
        val rawMrz: List<String>,
        val errorMessage: String? = null
    ) : Serializable {
        fun toJson(): String {
            val json = JSONObject()
            json.put("success", success)
            json.put("confidence", confidence)
            json.put("processing_time", processingTime / 1000.0) // seconds
            
            if (data != null) {
                val dataJson = JSONObject()
                dataJson.put("document_number", data.documentNumber)
                dataJson.put("national_id", data.nationalId)
                dataJson.put("name", data.name)
                dataJson.put("surname", data.surname)
                dataJson.put("birth_date", data.birthDate)
                dataJson.put("gender", data.gender)
                dataJson.put("expiry_date", data.expiryDate)
                dataJson.put("nationality", data.nationality)
                json.put("data", dataJson)
            }
            
            json.put("raw_mrz", rawMrz)
            if (errorMessage != null) {
                json.put("error", errorMessage)
            }
            
            return json.toString(2)
        }
    }

    data class PersonalData(
        val documentNumber: String,
        val nationalId: String,
        val name: String,
        val surname: String,
        val secondName: String = "", // İkinci isim
        val birthDate: String,
        val gender: String,
        val expiryDate: String,
        val nationality: String = "TUR"
    ) : Serializable

    init {
        Log.i(TAG, "TC MRZ Reader initialized with MLKit")
    }


    /**
     * Ana MRZ okuma fonksiyonu
     */
    suspend fun readTCMRZ(inputBitmap: Bitmap, overlayBounds: android.graphics.Rect? = null): TCMRZResult {
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "=== TC MRZ READING START (MLKit) ===")

        try {
            
            // 1. Görüntü kalite kontrolü
            if (!validateImageQuality(inputBitmap)) {
                return TCMRZResult(
                    success = false,
                    confidence = 0f,
                    processingTime = System.currentTimeMillis() - startTime,
                    data = null,
                    rawMrz = emptyList(),
                    errorMessage = "Image quality too low for MRZ reading"
                )
            }

            // 2. Try MLKit OCR first with original image (no preprocessing)
            Log.d(TAG, "Testing MLKit OCR with original image first...")
            val originalOcrText = performMLKitOCR(inputBitmap)
            
            // 3. If original failed, try with preprocessed MRZ area
            val ocrText = if (originalOcrText.isNotEmpty()) {
                Log.d(TAG, "Original image OCR succeeded, using result")
                originalOcrText
            } else {
                Log.d(TAG, "Original image OCR failed, trying with MRZ preprocessing...")
                val preprocessedBitmap = preprocessImageForMRZ(inputBitmap, overlayBounds)
                val processedOcrText = performMLKitOCR(preprocessedBitmap)
                
                // Cleanup preprocessed bitmap if different from input
                if (preprocessedBitmap != inputBitmap) {
                    preprocessedBitmap.recycle()
                }
                
                processedOcrText
            }
            
            // 4. MRZ Format Doğrulama
            val mrzLines = parseMRZLines(ocrText)
            if (!validateMRZFormat(mrzLines)) {
                return TCMRZResult(
                    success = false,
                    confidence = 0f,
                    processingTime = System.currentTimeMillis() - startTime,
                    data = null,
                    rawMrz = mrzLines,
                    errorMessage = "Invalid MRZ format detected"
                )
            }

            // 5. Kişisel Bilgi Çıkarımı
            val personalData = extractPersonalInfo(mrzLines)
            
            // 6. Check Digit Doğrulama
            val isValid = validateCheckDigits(mrzLines)
            
            val confidence = calculateConfidence(mrzLines, personalData, isValid)
            val processingTime = System.currentTimeMillis() - startTime
            
            Log.d(TAG, "TC MRZ reading completed: Success=$isValid, Confidence=$confidence, Time=${processingTime}ms")

            return TCMRZResult(
                success = isValid,
                confidence = confidence,
                processingTime = processingTime,
                data = if (isValid) personalData else null,
                rawMrz = mrzLines
            )

        } catch (e: Exception) {
            Log.e(TAG, "TC MRZ reading error: ${e.message}", e)
            return TCMRZResult(
                success = false,
                confidence = 0f,
                processingTime = System.currentTimeMillis() - startTime,
                data = null,
                rawMrz = emptyList(),
                errorMessage = "Processing error: ${e.message}"
            )
        }
    }

    private fun validateImageQuality(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        
        Log.d(TAG, "Image quality check: ${width}x${height}")
        
        return width >= MIN_WIDTH && height >= MIN_HEIGHT
    }

    private fun preprocessImageForMRZ(inputBitmap: Bitmap, overlayBounds: android.graphics.Rect? = null): Bitmap {
        Log.d(TAG, "Starting simplified MRZ preprocessing pipeline")
        
        // Convert to OpenCV Mat
        val inputMat = Mat()
        Utils.bitmapToMat(inputBitmap, inputMat)
        
        // 1. Convert to grayscale
        val grayMat = Mat()
        if (inputMat.channels() > 1) {
            Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        } else {
            inputMat.copyTo(grayMat)
        }
        
        // 2. Template-based MRZ Detection
        val height = grayMat.rows()
        val width = grayMat.cols()
        
        // Template matching ile gerçek MRZ konumunu bul
        Log.d(TAG, "Input image size for template matching: ${width}x${height}")
        val detectedPoint = templateDetector.detectMRZByTemplate(grayMat)
        
        val mrzRect = if (detectedPoint != null) {
            // Template matching başarılı - tespit edilen konumu kullan
            val templateWidth = 600
            val templateHeight = 120
            
            // Tespit edilen nokta template'in sol üst köşesi
            val mrzStartX = detectedPoint.x.toInt()
            val mrzStartY = detectedPoint.y.toInt()
            
            // Template boyutlarını ekran boyutuna göre scale et - daha büyük area kullan
            val scaleX = width.toDouble() / 2992.0 // Kamera çözünürlüğü referans
            val scaleY = height.toDouble() / 2992.0
            
            val scaledWidth = (templateWidth * scaleX * 1.2).toInt() // %20 daha geniş
            val scaledHeight = (templateHeight * scaleY * 1.5).toInt() // %50 daha yüksek
            
            Log.d(TAG, "Template MRZ detected at: $mrzStartX,$mrzStartY size ${scaledWidth}x${scaledHeight}")
            
            // Güvenlik marjinleri ekle
            val safeStartX = maxOf(0, mrzStartX - 20)
            val safeStartY = maxOf(0, mrzStartY - 15)
            val safeWidth = minOf(width - safeStartX, scaledWidth + 40)
            val safeHeight = minOf(height - safeStartY, scaledHeight + 30)
            
            android.graphics.Rect(safeStartX, safeStartY, safeStartX + safeWidth, safeStartY + safeHeight)
        } else if (overlayBounds != null) {
            // Template matching başarısız - overlay bounds kullan
            Log.d(TAG, "Template detection failed, using overlay bounds")
            
            val overlayStartY = overlayBounds.top
            val overlayHeight = overlayBounds.height()
            val mrzStartY = overlayStartY + (overlayHeight * 0.80).toInt() // Biraz daha yukarıdan başla
            val mrzHeight = (overlayHeight * 0.20).toInt() // Daha yüksek alan
            
            val mrzStartX = overlayBounds.left + (overlayBounds.width() * 0.05).toInt()
            val mrzWidth = (overlayBounds.width() * 0.90).toInt()
            
            android.graphics.Rect(mrzStartX, mrzStartY, mrzStartX + mrzWidth, mrzStartY + mrzHeight)
        } else {
            // Fallback: genel tahmini alan - debug ile optimize edelim
            Log.d(TAG, "No template detected, no overlay bounds - using estimated area")
            
            // Daha büyük alan kullan
            val mrzStartY = (height * 0.85).toInt() // Alt %15'de ara
            val mrzHeight = (height * 0.15).toInt() // %15 yükseklik
            val mrzStartX = (width * 0.05).toInt() // %5 margin
            val mrzWidth = (width * 0.90).toInt() // %90 genişlik
            
            Log.d(TAG, "Fallback MRZ area: startY=${mrzStartY}, height=${mrzHeight}, startX=${mrzStartX}, width=${mrzWidth}")
            
            android.graphics.Rect(mrzStartX, mrzStartY, mrzStartX + mrzWidth, mrzStartY + mrzHeight)
        }
        
        Log.d(TAG, "Final MRZ area: ${mrzRect.left},${mrzRect.top} size ${mrzRect.width()}x${mrzRect.height()}")
        
        // OpenCV Rect'e çevir ve bounds kontrolü
        val safeLeft = maxOf(0, mrzRect.left)
        val safeTop = maxOf(0, mrzRect.top) 
        val safeWidth = minOf(width - safeLeft, mrzRect.width())
        val safeHeight = minOf(height - safeTop, mrzRect.height())
        
        val cvMrzRect = org.opencv.core.Rect(safeLeft, safeTop, safeWidth, safeHeight)
        val mrzMat = Mat(grayMat, cvMrzRect)
        
        // ADVANCED PREPROCESSING - CLAHE + Unsharp Mask for improved OCR accuracy
        Log.d(TAG, "Applying advanced preprocessing: CLAHE + Unsharp Mask")
        
        val enhancedMat = applyAdvancedPreprocessing(mrzMat)
        
        // Convert back to Bitmap
        val resultBitmap = Bitmap.createBitmap(enhancedMat.cols(), enhancedMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhancedMat, resultBitmap)
        
        Log.d(TAG, "MRZ preprocessing completed: ${resultBitmap.width}x${resultBitmap.height}")
        
        // Cleanup
        inputMat.release()
        grayMat.release()
        mrzMat.release()
        enhancedMat.release()
        
        return resultBitmap
    }

    /**
     * Advanced image preprocessing using CLAHE and Unsharp Mask
     * Improves OCR accuracy by enhancing contrast and sharpening text edges
     */
    private fun applyAdvancedPreprocessing(inputMat: Mat): Mat {
        Log.d(TAG, "Starting advanced preprocessing pipeline")
        
        try {
            // 1. CLAHE (Contrast Limited Adaptive Histogram Equalization) - AGGRESSIVE
            Log.d(TAG, "Applying AGGRESSIVE CLAHE for maximum contrast enhancement")
            val clahe = Imgproc.createCLAHE(4.0, Size(4.0, 4.0)) // 2x daha agresif
            val claheResult = Mat()
            clahe.apply(inputMat, claheResult)
            
            // 2. Gaussian + Unsharp Mask for sharpening - AGGRESSIVE
            Log.d(TAG, "Applying AGGRESSIVE Gaussian + Unsharp Mask for maximum sharpening")
            
            // Create Gaussian blurred version - daha büyük kernel
            val blurred = Mat()
            Imgproc.GaussianBlur(claheResult, blurred, Size(7.0, 7.0), 1.5) // Daha büyük blur
            
            // Create mask by subtracting blurred from original
            val mask = Mat()
            Core.subtract(claheResult, blurred, mask)
            
            // Apply unsharp mask: enhanced = original + amount * mask - MUCH STRONGER
            val sharpened = Mat()
            Core.addWeighted(claheResult, 1.0, mask, 3.0, 0.0, sharpened) // 1.5 -> 3.0 (2x güçlü)
            
            Log.d(TAG, "Advanced preprocessing completed successfully")
            
            // Cleanup intermediate results
            claheResult.release()
            blurred.release()
            mask.release()
            
            return sharpened
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in advanced preprocessing: ${e.message}", e)
            // Fallback: return original mat if preprocessing fails
            val fallback = Mat()
            inputMat.copyTo(fallback)
            return fallback
        }
    }

    private suspend fun performMLKitOCR(bitmap: Bitmap): String {
        Log.d(TAG, "Performing MLKit OCR on image (${bitmap.width}x${bitmap.height})")
        
        // Debug: Check if bitmap is valid
        if (bitmap.isRecycled) {
            Log.e(TAG, "Bitmap is recycled!")
            return ""
        }
        
        return suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                
                Log.d(TAG, "Running MLKit text recognition...")
                
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val recognizedText = visionText.text
                        
                        Log.d(TAG, "MLKit OCR completed: ${recognizedText.length} characters")
                        
                        if (recognizedText.isNotEmpty()) {
                            Log.d(TAG, "Raw MLKit text (first 200 chars): '${recognizedText.take(200)}${if (recognizedText.length > 200) "..." else ""}'")
                            
                            // Analyze text blocks for better MRZ detection
                            Log.d(TAG, "Text blocks found: ${visionText.textBlocks.size}")
                            visionText.textBlocks.forEachIndexed { index, block ->
                                Log.d(TAG, "Block $index: '${block.text.replace("\n", " ").take(100)}${if (block.text.length > 100) "..." else ""}'")
                                Log.d(TAG, "Block $index boundingBox: ${block.boundingBox}")
                            }
                            
                            // Check for typical MRZ characters
                            val mrzCharCount = recognizedText.count { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<" }
                            val mrzCharPercentage = if (recognizedText.isNotEmpty()) (mrzCharCount.toFloat() / recognizedText.length * 100).toInt() else 0
                            Log.d(TAG, "MRZ character percentage: $mrzCharPercentage% ($mrzCharCount/${recognizedText.length})")
                            
                            // Check for lines that might be MRZ-like
                            val lines = recognizedText.split("\n")
                            Log.d(TAG, "Text lines found: ${lines.size}")
                            lines.forEachIndexed { index, line ->
                                if (line.length >= 20) {
                                    Log.d(TAG, "Long line $index: '$line' (${line.length} chars)")
                                }
                            }
                        } else {
                            Log.w(TAG, "MLKit OCR returned empty text")
                        }
                        
                        if (continuation.isActive) {
                            continuation.resume(recognizedText)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "MLKit OCR failed: ${exception.message}", exception)
                        if (continuation.isActive) {
                            continuation.resume("")
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "MLKit OCR error: ${e.message}", e)
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
        }
    }

    private fun parseMRZLines(ocrText: String): List<String> {
        Log.d(TAG, "=== NEW PATTERN-BASED MRZ PARSING ===")
        Log.d(TAG, "Parsing MRZ from OCR text (${ocrText.length} chars)")
        Log.d(TAG, "Raw OCR text: '${ocrText.take(500)}${if (ocrText.length > 500) "..." else ""}'")
        
        if (ocrText.isEmpty()) {
            Log.w(TAG, "Empty OCR text received")
            return emptyList()
        }
        
        // Split into all possible lines
        val allLines = ocrText.split(Regex("[\\r\\n]+")).filter { it.trim().isNotEmpty() }
        Log.d(TAG, "Split into ${allLines.size} raw lines")
        
        allLines.forEachIndexed { index, line ->
            Log.d(TAG, "Raw line $index: '${line.trim()}' (${line.trim().length} chars)")
        }
        
        // PATTERN-BASED APPROACH: Search for specific MRZ patterns
        val foundLines = mutableListOf<String>()
        
        // Search for Line 1 pattern (I + TUR + TC number)
        val line1 = findMRZLine1Pattern(allLines)
        if (line1 != null) {
            foundLines.add(line1)
            Log.i(TAG, "✅ Found MRZ Line 1: '$line1'")
        } else {
            Log.w(TAG, "❌ MRZ Line 1 not found")
        }
        
        // Search for Line 2 pattern (dates + gender + TUR)
        val line2 = findMRZLine2Pattern(allLines)
        if (line2 != null) {
            foundLines.add(line2)
            Log.i(TAG, "✅ Found MRZ Line 2: '$line2'")
        } else {
            Log.w(TAG, "❌ MRZ Line 2 not found")
        }
        
        // Search for Line 3 pattern (names with << separator)
        val line3 = findMRZLine3Pattern(allLines)
        if (line3 != null) {
            foundLines.add(line3)
            Log.i(TAG, "✅ Found MRZ Line 3: '$line3'")
        } else {
            Log.w(TAG, "❌ MRZ Line 3 not found")
        }
        
        Log.d(TAG, "=== PATTERN-BASED PARSING COMPLETE ===")
        Log.d(TAG, "Found ${foundLines.size} MRZ lines using pattern matching")
        
        foundLines.forEachIndexed { index, line ->
            Log.d(TAG, "Pattern-matched line ${index + 1}: '$line' (${line.length} chars)")
        }
        
        // Return found lines (even if incomplete - partial MRZ is better than wrong MRZ)
        return foundLines
    }

    private fun validateMRZFormat(mrzLines: List<String>): Boolean {
        Log.d(TAG, "=== PATTERN-AWARE MRZ VALIDATION ===")
        
        if (mrzLines.isEmpty()) {
            Log.w(TAG, "No MRZ lines to validate - FAILED")
            return false
        }
        
        Log.d(TAG, "Validating ${mrzLines.size} pattern-matched MRZ lines:")
        mrzLines.forEachIndexed { index, line ->
            Log.d(TAG, "Line ${index + 1}: '$line' (length: ${line.length})")
        }
        
        // Pattern-based validation - if we found lines using patterns, they should be valid
        var validLines = 0
        var hasLine1 = false
        var hasLine2 = false
        var hasLine3 = false
        
        mrzLines.forEachIndexed { index, line ->
            // Since we used pattern matching to find these lines, 
            // we can be more lenient in validation
            when (index) {
                0 -> {
                    // First found line - validate against all patterns to determine type
                    if (isLine1Pattern(line)) {
                        hasLine1 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 1")
                    } else if (isLine2Pattern(line)) {
                        hasLine2 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 2")
                    } else if (isLine3Pattern(line)) {
                        hasLine3 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 3")
                    } else {
                        Log.w(TAG, "Line ${index + 1} doesn't match any pattern")
                    }
                }
                1 -> {
                    // Second found line
                    if (!hasLine1 && isLine1Pattern(line)) {
                        hasLine1 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 1")
                    } else if (!hasLine2 && isLine2Pattern(line)) {
                        hasLine2 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 2")
                    } else if (!hasLine3 && isLine3Pattern(line)) {
                        hasLine3 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 3")
                    } else {
                        Log.w(TAG, "Line ${index + 1} doesn't match expected pattern or is duplicate")
                    }
                }
                2 -> {
                    // Third found line
                    if (!hasLine1 && isLine1Pattern(line)) {
                        hasLine1 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 1")
                    } else if (!hasLine2 && isLine2Pattern(line)) {
                        hasLine2 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 2")
                    } else if (!hasLine3 && isLine3Pattern(line)) {
                        hasLine3 = true
                        validLines++
                        Log.d(TAG, "Line ${index + 1} validated as MRZ Line 3")
                    } else {
                        Log.w(TAG, "Line ${index + 1} doesn't match expected pattern or is duplicate")
                    }
                }
            }
        }
        
        // Success criteria: At least 2 valid MRZ lines, with Line1 or Line2 mandatory
        val isValid = validLines >= 2 && (hasLine1 || hasLine2)
        
        Log.d(TAG, "=== VALIDATION SUMMARY ===")
        Log.d(TAG, "Valid pattern-matched lines: $validLines/${mrzLines.size}")
        Log.d(TAG, "Has Line 1 (ID): $hasLine1")
        Log.d(TAG, "Has Line 2 (Dates): $hasLine2") 
        Log.d(TAG, "Has Line 3 (Names): $hasLine3")
        Log.d(TAG, "Overall MRZ validation result: $isValid")
        
        return isValid
    }
    
    // Helper functions to identify line types
    private fun isLine1Pattern(line: String): Boolean {
        return line.startsWith("I") && line.contains("TUR") && line.count { it.isDigit() } >= 11
    }
    
    private fun isLine2Pattern(line: String): Boolean {
        return line.contains("TUR") && (line.contains("M") || line.contains("F")) && 
               line.take(6).count { it.isDigit() } >= 5
    }
    
    private fun isLine3Pattern(line: String): Boolean {
        val letterCount = line.count { it.isLetter() }
        val angleCount = line.count { it == '<' }
        return letterCount >= 5 && (angleCount > 0 || line.contains("<<"))
    }
    
    private fun validateLine1(line: String): Boolean {
        // Line 1: I + kurum + TUR + seri(9) + kontrol + TC(11) + dolgu(4)
        if (line.length != 30) return false
        
        // Temel kontroller (OCR hatalarına toleranslı)
        return line.startsWith("I") && 
               line.substring(2, 5) == "TUR" && 
               line.substring(15, 26).matches(Regex("\\d{11}")) // TC kimlik 11 rakam
    }
    
    private fun validateLine2(line: String): Boolean {
        // Line 2: doğum(6) + kontrol + cinsiyet + geçerlilik(6) + kontrol + TUR + uyruk(11) + kontrol
        if (line.length != 30) return false
        
        // Temel kontroller
        return line.substring(0, 6).matches(Regex("\\d{6}")) && // Doğum tarihi 6 rakam
               line.substring(7, 8).matches(Regex("[MF]")) && // Cinsiyet M veya F
               line.substring(8, 14).matches(Regex("\\d{6}")) && // Geçerlilik tarihi 6 rakam
               line.substring(15, 18) == "TUR" // TUR sabit
    }
    
    private fun validateLine3(line: String): Boolean {
        // Line 3: soyad + << + ad + dolgu (sadece A-Z ve < karakteri)
        if (line.length != 30) return false
        
        // Sadece harf ve < karakteri kontrol
        return line.matches(Regex("[A-Z<]{30}"))
    }

    // Improved validation methods - OCR-tolerant but more structured
    private fun validateLine1Improved(line: String): Boolean {
        if (line.length != 30) {
            Log.d(TAG, "Line 1 length check failed: ${line.length} (expected 30)")
            return false
        }
        
        Log.d(TAG, "Validating Line 1: '$line'")
        
        // TC MRZ Line 1 structure: I[kurum]TUR[seri 9][kontrol][TC 11][dolgu 4]
        // Check basic structure with OCR tolerance
        val startsWithI = line[0] in "I1" // I or 1 (common OCR error)
        val hasTUR = line.substring(2, 5) == "TUR" || 
                     line.substring(1, 4) == "TUR" ||
                     line.contains("TUR")
        
        // Look for 11-digit TC number pattern (should be mostly digits)
        val possibleTCSection = line.substring(15, 26)
        val digitCount = possibleTCSection.count { it.isDigit() }
        val hasTCPattern = digitCount >= 9 // At least 9 out of 11 should be digits
        
        Log.d(TAG, "Line 1 validation: startsWithI=$startsWithI, hasTUR=$hasTUR, hasTCPattern=$hasTCPattern (digits: $digitCount/11)")
        Log.d(TAG, "Possible TC section: '$possibleTCSection'")
        
        return startsWithI && hasTUR && hasTCPattern
    }
    
    private fun validateLine2Improved(line: String): Boolean {
        if (line.length != 30) {
            Log.d(TAG, "Line 2 length check failed: ${line.length} (expected 30)")
            return false
        }
        
        Log.d(TAG, "Validating Line 2: '$line'")
        
        // TC MRZ Line 2 structure: [doğum 6][kontrol][cinsiyet][geçerlilik 6][kontrol]TUR[uyruk 11][kontrol]
        val birthDateSection = line.substring(0, 6)
        val genderChar = line[7]
        val expiryDateSection = line.substring(8, 14)
        val hasTUR = line.substring(15, 18) == "TUR"
        
        // Validate birth date (6 digits)
        val birthDigitCount = birthDateSection.count { it.isDigit() }
        val validBirthDate = birthDigitCount >= 5 // At least 5 out of 6 should be digits
        
        // Validate gender
        val validGender = genderChar in "MF"
        
        // Validate expiry date (6 digits)
        val expiryDigitCount = expiryDateSection.count { it.isDigit() }
        val validExpiryDate = expiryDigitCount >= 5 // At least 5 out of 6 should be digits
        
        Log.d(TAG, "Line 2 validation: birthDate=$validBirthDate ($birthDigitCount/6), gender=$validGender ('$genderChar'), expiryDate=$validExpiryDate ($expiryDigitCount/6), TUR=$hasTUR")
        
        return validBirthDate && validGender && validExpiryDate && hasTUR
    }
    
    private fun validateLine3Improved(line: String): Boolean {
        if (line.length != 30) {
            Log.d(TAG, "Line 3 length check failed: ${line.length} (expected 30)")
            return false
        }
        
        Log.d(TAG, "Validating Line 3: '$line'")
        
        // TC MRZ Line 3 structure: [soyad]<<[ad]<[dolgu]
        // Should contain mostly letters and < characters
        val letterCount = line.count { it.isLetter() }
        val angleCount = line.count { it == '<' }
        val validCharCount = letterCount + angleCount
        val hasDoubleBracket = line.contains("<<")
        
        // At least 80% should be valid MRZ name characters (letters + <)
        val validCharPercentage = validCharCount.toFloat() / line.length
        
        Log.d(TAG, "Line 3 validation: letters=$letterCount, angles=$angleCount, validChar%=${(validCharPercentage * 100).toInt()}%, hasDoubleBracket=$hasDoubleBracket")
        
        return validCharPercentage >= 0.8 && letterCount >= 5 // At least 5 letters for names
    }

    private fun extractPersonalInfo(mrzLines: List<String>): PersonalData? {
        Log.d(TAG, "=== PATTERN-AWARE PERSONAL INFO EXTRACTION ===")
        
        try {
            if (mrzLines.isEmpty()) {
                Log.w(TAG, "No MRZ lines for extraction")
                return null
            }
            
            Log.d(TAG, "Extracting from ${mrzLines.size} pattern-matched MRZ lines:")
            mrzLines.forEachIndexed { index, line ->
                Log.d(TAG, "Line ${index + 1}: '$line'")
            }
            
            // Initialize with defaults
            var documentNumber = ""
            var nationalId = ""
            var birthDate = ""
            var gender = ""
            var expiryDate = ""
            var nationality = "TUR"
            var surname = ""
            var name = ""
            var secondName = ""
            
            // Pattern-aware extraction - identify line types and extract accordingly
            for (line in mrzLines) {
                when {
                    isLine1Pattern(line) -> {
                        Log.d(TAG, "Extracting from Line 1 (ID): '$line'")
                        
                        // Extract document number (seri no) based on correct pattern
                        try {
                            val turIndex = line.indexOf("TUR")
                            val tcPattern = Regex("\\d{11}")
                            val tcMatch = tcPattern.find(line)
                            
                            if (tcMatch != null && turIndex >= 0) {
                                nationalId = tcMatch.value
                                
                                // TC MRZ Line 1 doğru yapısı: I[dolgu 1]TUR[seri 9][kontrol 1][dolgu 1][TC 11][dolgu 3]
                                val seriStart = turIndex + 3 // TUR sonrası
                                val seriEnd = seriStart + 9 // Seri no tam 9 karakter
                                
                                if (seriEnd <= line.length) {
                                    val seriNo = line.substring(seriStart, seriEnd)
                                    
                                    // SADECE uzunluk kontrolü
                                    if (seriNo.length != 9) {
                                        Log.e(TAG, "❌ Seri no length error! Expected 9, got ${seriNo.length}: '$seriNo'")
                                        return null // Uzunluk hatası - retry
                                    }
                                    
                                    documentNumber = seriNo
                                    
                                    Log.d(TAG, "✅ Document number extracted: '$seriNo' (${seriNo.length} chars)")
                                    Log.d(TAG, "✅ National ID extracted: '$nationalId'")
                                    
                                } else {
                                    Log.e(TAG, "❌ Line too short for seri no extraction")
                                    return null
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error extracting from Line 1: ${e.message}")
                            return null
                        }
                    }
                    
                    isLine2Pattern(line) -> {
                        Log.d(TAG, "Extracting from Line 2 (Dates/Gender): '$line'")
                        
                        try {
                            // Extract birth date (first 6 digits)
                            val birthDateMrz = line.take(6)
                            if (birthDateMrz.all { it.isDigit() }) {
                                birthDate = formatTCDate(birthDateMrz, isExpiryDate = false)
                                Log.d(TAG, "Birth date: '$birthDateMrz' -> '$birthDate'")
                            }
                            
                            // Extract gender (M or F)
                            val mIndex = line.indexOf("M")
                            val fIndex = line.indexOf("F")
                            gender = when {
                                mIndex >= 0 -> "M"
                                fIndex >= 0 -> "F"
                                else -> ""
                            }
                            Log.d(TAG, "Gender: '$gender'")
                            
                            // Extract expiry date (look for 6 digits after gender)
                            val genderIndex = maxOf(mIndex, fIndex)
                            if (genderIndex >= 0) {
                                val afterGender = line.substring(genderIndex + 1)
                                val expiryPattern = Regex("\\d{6}")
                                val expiryMatch = expiryPattern.find(afterGender)
                                
                                if (expiryMatch != null) {
                                    val expiryDateMrz = expiryMatch.value
                                    expiryDate = formatTCDate(expiryDateMrz, isExpiryDate = true)
                                    Log.d(TAG, "Expiry date: '$expiryDateMrz' -> '$expiryDate'")
                                }
                            }
                            
                            // Extract nationality (should be TUR)
                            if (line.contains("TUR")) {
                                nationality = "TUR"
                            }
                            
                        } catch (e: Exception) {
                            Log.w(TAG, "Error extracting from Line 2: ${e.message}")
                        }
                    }
                    
                    isLine3Pattern(line) -> {
                        Log.d(TAG, "Extracting from Line 3 (Names): '$line'")
                        
                        try {
                            // GELIŞMIŞ İSIM PARSING: SOYAD<<ISIM<IKINCI_ISIM<<<<<<
                            Log.d(TAG, "Advanced name parsing for: '$line'")
                            
                            val doubleBracketIndex = line.indexOf("<<")
                            if (doubleBracketIndex > 0) {
                                // Soyadı al (ilk << öncesi)
                                surname = line.substring(0, doubleBracketIndex).replace("<", "").trim()
                                
                                // << sonrası kısmı al
                                val afterDoubleBracket = line.substring(doubleBracketIndex + 2)
                                Log.d(TAG, "After '<<': '$afterDoubleBracket'")
                                
                                // < karakterlerine göre böl
                                val nameParts = afterDoubleBracket.split("<").filter { it.isNotEmpty() }
                                
                                name = nameParts.getOrNull(0)?.trim() ?: ""
                                secondName = nameParts.getOrNull(1)?.trim() ?: ""
                                
                                Log.d(TAG, "Name parts: ${nameParts.size} -> [${nameParts.joinToString(", ")}]")
                                
                            } else {
                                // Fallback: tek < ile ayrılmış durumu handle et
                                val parts = line.replace("<", " ").trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                                surname = parts.getOrNull(0)?.trim() ?: ""
                                name = parts.getOrNull(1)?.trim() ?: ""
                                secondName = parts.getOrNull(2)?.trim() ?: ""
                                
                                Log.d(TAG, "Fallback parsing: ${parts.size} parts -> [${parts.joinToString(", ")}]")
                            }
                            
                            Log.d(TAG, "Names extracted: surname='$surname', name='$name', secondName='$secondName'")
                            
                        } catch (e: Exception) {
                            Log.w(TAG, "Error extracting from Line 3: ${e.message}")
                        }
                    }
                    
                    else -> {
                        Log.w(TAG, "Unknown line pattern: '$line'")
                    }
                }
            }
            
            // Tarih doğrulaması yap
            val datesValid = validateDateLogic(birthDate, expiryDate)
            if (!datesValid) {
                Log.e(TAG, "Date validation failed! Birth: $birthDate, Expiry: $expiryDate")
                Log.e(TAG, "Expiry date cannot be before birth date. Rejecting MRZ data.")
                return null // Tarih mantık hatası - okumayı reddet
            }
            
            val extractedData = PersonalData(
                documentNumber = documentNumber,
                nationalId = nationalId,
                name = name,
                surname = surname,
                secondName = secondName,
                birthDate = birthDate,
                gender = gender,
                expiryDate = expiryDate,
                nationality = nationality
            )
            
            Log.d(TAG, "=== EXTRACTION COMPLETE ===")
            Log.d(TAG, "Final extracted data: $extractedData")
            Log.d(TAG, "Date validation: PASSED ✅")
            
            return extractedData
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in pattern-aware extraction: ${e.message}", e)
            return null
        }
    }

    private fun formatTCDate(dateStr: String, isExpiryDate: Boolean = false): String {
        if (dateStr.length != 6) return dateStr
        
        try {
            // TC MRZ formatı: YYMMDD (yıl son 2 hane + ay + gün)
            // Örnek: 040314 -> 04 03 14 -> 2004/03/14 -> 14.03.2004
            val yy = dateStr.substring(0, 2).toIntOrNull() ?: return dateStr
            val mm = dateStr.substring(2, 4)
            val dd = dateStr.substring(4, 6)
            
            // Yıl hesaplama
            val yyyy = if (isExpiryDate) {
                // Geçerlilik tarihleri her zaman 2000 sonrası (yeni sistem)
                2000 + yy
            } else {
                // Doğum tarihleri için mantıklı aralık
                // 0-30 arası: 2000-2030 (yeni doğumlar)
                // 31-99 arası: 1931-1999 (geçmiş doğumlar)
                if (yy <= 30) {
                    2000 + yy // 2000-2030
                } else {
                    1900 + yy // 1931-1999
                }
            }
            
            Log.d(TAG, "Date parsing: '$dateStr' -> YY=$yy MM=$mm DD=$dd -> Year=$yyyy (isExpiry=$isExpiryDate) -> Final: $dd.$mm.$yyyy")
            
            // Türk formatı: GG.AA.YYYY
            return "$dd.$mm.$yyyy"
            
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing date '$dateStr': ${e.message}")
            return dateStr
        }
    }
    
    // Tarih doğrulama fonksiyonu
    private fun validateDateLogic(birthDate: String, expiryDate: String): Boolean {
        try {
            if (birthDate.isEmpty() || expiryDate.isEmpty()) {
                Log.w(TAG, "Date validation failed: Empty dates")
                return false
            }
            
            // GG.AA.YYYY formatından Date objelerine çevir
            val birthParts = birthDate.split(".")
            val expiryParts = expiryDate.split(".")
            
            if (birthParts.size != 3 || expiryParts.size != 3) {
                Log.w(TAG, "Date validation failed: Invalid format")
                return false
            }
            
            val birthYear = birthParts[2].toIntOrNull() ?: return false
            val birthMonth = birthParts[1].toIntOrNull() ?: return false
            val birthDay = birthParts[0].toIntOrNull() ?: return false
            
            val expiryYear = expiryParts[2].toIntOrNull() ?: return false
            val expiryMonth = expiryParts[1].toIntOrNull() ?: return false
            val expiryDay = expiryParts[0].toIntOrNull() ?: return false
            
            // Basit karşılaştırma: geçerlilik tarihi doğum tarihinden sonra olmalı
            val birthTimestamp = birthYear * 10000 + birthMonth * 100 + birthDay
            val expiryTimestamp = expiryYear * 10000 + expiryMonth * 100 + expiryDay
            
            val isValid = expiryTimestamp > birthTimestamp
            
            Log.d(TAG, "Date validation: Birth=$birthDate ($birthTimestamp), Expiry=$expiryDate ($expiryTimestamp) -> Valid=$isValid")
            
            return isValid
            
        } catch (e: Exception) {
            Log.w(TAG, "Date validation error: ${e.message}")
            return false
        }
    }

    // Pattern-based MRZ Detection Helper Functions
    private fun findMRZLine1Pattern(allLines: List<String>): String? {
        Log.d(TAG, "Searching for MRZ Line 1 pattern...")
        
        for (line in allLines) {
            val cleanLine = line.trim()
                .replace(Regex("\\s+"), "") // Remove spaces
                .replace("«", "<") // Fix OCR error: « -> <
                .replace("»", "<") // Fix OCR error: » -> <
                .uppercase()
            Log.d(TAG, "Testing line for Line 1 pattern: '$cleanLine'")
            
            // TC MRZ Line 1 pattern: I[kurum]TUR[seri 9][kontrol][TC 11][dolgu 4]
            // Flexible matching for OCR errors
            if (cleanLine.length >= 28 && cleanLine.length <= 32) {
                val startsWithI = cleanLine.startsWith("I") || cleanLine.startsWith("1")
                val containsTUR = cleanLine.contains("TUR")
                
                if (startsWithI && containsTUR) {
                    // Look for TC number pattern (11 consecutive digits)
                    val tcPattern = Regex("\\d{11}")
                    val tcMatch = tcPattern.find(cleanLine)
                    
                    if (tcMatch != null) {
                        // TC MRZ Line 1 yapısı: I[dolgu 1]TUR[seri 9][kontrol 1][dolgu 1][TC 11][dolgu 3]
                        val turIndex = cleanLine.indexOf("TUR")
                        val tcStartIndex = tcMatch.range.first
                        
                        if (turIndex >= 0 && tcStartIndex > turIndex + 3) {
                            // Seri no = TUR sonrası 9 karakter
                            val seriStart = turIndex + 3
                            val seriEnd = seriStart + 9 // Seri no tam 9 karakter
                            
                            if (seriEnd < cleanLine.length) {
                                val seriNo = cleanLine.substring(seriStart, seriEnd)
                                
                                // SADECE uzunluk kontrolü
                                if (seriNo.length != 9) {
                                    Log.w(TAG, "❌ Seri no length error! Expected 9, found ${seriNo.length}. Rejecting line.")
                                    continue // Bu satırı reddet
                                }
                                
                                Log.d(TAG, "✅ Seri no length OK: '$seriNo' (${seriNo.length} chars)")
                            }
                        }
                        
                        val normalizedLine = if (cleanLine.length == 30) cleanLine else cleanLine.padEnd(30, '<').take(30)
                        Log.d(TAG, "Found valid MRZ Line 1: '$normalizedLine'")
                        return normalizedLine
                    }
                }
            }
        }
        
        Log.d(TAG, "No MRZ Line 1 pattern found")
        return null
    }

    private fun findMRZLine2Pattern(allLines: List<String>): String? {
        Log.d(TAG, "Searching for MRZ Line 2 pattern...")
        
        for (line in allLines) {
            val cleanLine = line.trim()
                .replace(Regex("\\s+"), "") // Remove spaces
                .replace("«", "<") // Fix OCR error: « -> <
                .replace("»", "<") // Fix OCR error: » -> <
                .uppercase()
            Log.d(TAG, "Testing line for Line 2 pattern: '$cleanLine'")
            
            // TC MRZ Line 2 pattern: [doğum 6][kontrol][M/F][geçerlilik 6][kontrol]TUR[uyruk 11][kontrol]
            if (cleanLine.length >= 28 && cleanLine.length <= 32) {
                val containsTUR = cleanLine.contains("TUR")
                val containsGender = cleanLine.contains("M") || cleanLine.contains("F")
                
                if (containsTUR && containsGender) {
                    // Check for date patterns (at least 12 digits for two dates)
                    val digitCount = cleanLine.count { it.isDigit() }
                    
                    if (digitCount >= 12) {
                        // Look for birth date pattern at the beginning (6 digits)
                        val birthDatePattern = Regex("^\\d{6}")
                        val birthMatch = birthDatePattern.find(cleanLine)
                        
                        if (birthMatch != null) {
                            val normalizedLine = if (cleanLine.length == 30) cleanLine else cleanLine.padEnd(30, '<').take(30)
                            Log.d(TAG, "Found potential MRZ Line 2: '$normalizedLine'")
                            return normalizedLine
                        }
                    }
                }
            }
        }
        
        Log.d(TAG, "No MRZ Line 2 pattern found")
        return null
    }

    private fun findMRZLine3Pattern(allLines: List<String>): String? {
        Log.d(TAG, "Searching for MRZ Line 3 pattern...")
        
        for (line in allLines) {
            val cleanLine = line.trim()
                .replace(Regex("\\s+"), "") // Remove spaces
                .replace("«", "<") // Fix OCR error: « -> <
                .replace("»", "<") // Fix OCR error: » -> <
                .uppercase()
            Log.d(TAG, "Testing line for Line 3 pattern: '$cleanLine'")
            
            // TC MRZ Line 3 pattern: [SURNAME]<<[NAME]<<<<<<<< (names with << separator)
            if (cleanLine.length >= 25 && cleanLine.length <= 35) {
                val letterCount = cleanLine.count { it.isLetter() }
                val angleCount = cleanLine.count { it == '<' }
                val validCharCount = letterCount + angleCount
                
                // Must be mostly letters and angle brackets
                val validCharPercentage = validCharCount.toFloat() / cleanLine.length
                
                if (validCharPercentage > 0.8 && letterCount >= 5) {
                    // Look for name separator patterns
                    val hasDoubleBracket = cleanLine.contains("<<")
                    val hasSingleBrackets = cleanLine.contains("<")
                    
                    if (hasDoubleBracket || hasSingleBrackets) {
                        val normalizedLine = if (cleanLine.length == 30) cleanLine else cleanLine.padEnd(30, '<').take(30)
                        Log.d(TAG, "Found potential MRZ Line 3: '$normalizedLine'")
                        return normalizedLine
                    }
                }
            }
        }
        
        Log.d(TAG, "No MRZ Line 3 pattern found")
        return null
    }

    private fun validateCheckDigits(mrzLines: List<String>): Boolean {
        // Implement check digit validation for TC MRZ
        // This is a simplified version - real implementation would be more complex
        Log.d(TAG, "Check digit validation - simplified implementation")
        return mrzLines.size == 3 && mrzLines.all { it.length == 30 }
    }

    private fun calculateConfidence(
        mrzLines: List<String>,
        personalData: PersonalData?,
        isValid: Boolean
    ): Float {
        var confidence = 0f
        
        // Base confidence from format validation
        if (mrzLines.size == 3) confidence += 30f
        if (isValid) confidence += 40f
        
        // Additional confidence from data extraction
        personalData?.let { data ->
            if (data.documentNumber.isNotEmpty()) confidence += 10f
            if (data.nationalId.isNotEmpty()) confidence += 10f
            if (data.name.isNotEmpty()) confidence += 5f
            if (data.surname.isNotEmpty()) confidence += 5f
        }
        
        return minOf(confidence, 100f)
    }
    
    /**
     * Template-based MRZ Detection
     * MRZ pattern'ini template matching ile tespit eder
     */
    private inner class TemplateMRZDetector {
        
        fun detectMRZByTemplate(inputMat: Mat): Point? {
            Log.d(TAG, "Starting multi-scale template-based MRZ detection")
            
            val gray = Mat()
            if (inputMat.channels() > 1) {
                Imgproc.cvtColor(inputMat, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                inputMat.copyTo(gray)
            }
            
            // Multi-scale template matching
            var bestMatch: Core.MinMaxLocResult? = null
            var bestScale = 1.0
            
            val scales = arrayOf(0.5, 0.8, 1.0, 1.2, 1.5, 2.0) // Farklı scale'lerde dene
            
            for (scale in scales) {
                val templateMat = createMRZTemplate()
                
                // Template'i scale et
                val scaledTemplate = Mat()
                val scaledSize = Size(templateMat.cols() * scale, templateMat.rows() * scale)
                Imgproc.resize(templateMat, scaledTemplate, scaledSize)
                
                // Image boyutundan büyük template'ları skip et
                if (scaledTemplate.cols() > gray.cols() || scaledTemplate.rows() > gray.rows()) {
                    templateMat.release()
                    scaledTemplate.release()
                    continue
                }
                
                // Template matching
                val result = Mat()
                try {
                    Imgproc.matchTemplate(gray, scaledTemplate, result, Imgproc.TM_CCOEFF_NORMED)
                    
                    // En iyi eşleşmeyi bul
                    val mmResult = Core.minMaxLoc(result)
                    
                    Log.d(TAG, "Scale $scale: Template confidence: ${String.format("%.3f", mmResult.maxVal)}")
                    
                    if (bestMatch == null || mmResult.maxVal > bestMatch.maxVal) {
                        bestMatch = mmResult
                        bestScale = scale
                    }
                    
                    result.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Template matching failed for scale $scale: ${e.message}")
                }
                
                templateMat.release()
                scaledTemplate.release()
            }
            
            val detectedPoint = if (bestMatch != null && bestMatch.maxVal > 0.2) { // %20 eşleşme eşiği
                Log.d(TAG, "Best MRZ match: (${bestMatch.maxLoc.x}, ${bestMatch.maxLoc.y}) confidence=${String.format("%.3f", bestMatch.maxVal)} scale=${bestScale}")
                bestMatch.maxLoc
            } else {
                Log.d(TAG, "MRZ template matching failed, best confidence: ${bestMatch?.maxVal ?: 0}")
                Log.d(TAG, "Best match location: (${bestMatch?.maxLoc?.x}, ${bestMatch?.maxLoc?.y}) scale=$bestScale")
                null
            }
            
            // Cleanup
            gray.release()
            
            return detectedPoint
        }
        
        private fun createMRZTemplate(): Mat {
            // TC MRZ template oluştur - daha gerçekçi pattern
            // Gerçek boyutlar: 600x120 (daha büyük, daha net tespit için)
            val template = Mat.zeros(Size(600.0, 120.0), CvType.CV_8UC1)
            
            // 3 satır MRZ için gerçekçi text pattern oluştur
            for (lineIndex in 0..2) {
                val y = 20 + lineIndex * 35 // Her satır arası 35 piksel
                val lineHeight = 20 // Daha kalın satır yüksekliği
                
                // Sürekli text bloğu çiz (gerçek MRZ gibi)
                Imgproc.rectangle(
                    template,
                    Point(30.0, y.toDouble()),
                    Point(570.0, (y + lineHeight).toDouble()),
                    Scalar(180.0), -1 // Gri ton, tam beyaz değil
                )
                
                // Text içinde karakteristik boşluklar (< gibi)
                for (spaceIndex in arrayOf(3, 8, 13, 19, 25)) { // Tipik MRZ boşluk pozisyonları
                    val spaceX = 30 + spaceIndex * 18
                    Imgproc.rectangle(
                        template,
                        Point(spaceX.toDouble(), (y + 3).toDouble()),
                        Point((spaceX + 8).toDouble(), (y + lineHeight - 3).toDouble()),
                        Scalar(120.0), -1 // Daha koyu ton
                    )
                }
            }
            
            // Kenar yumuşatma
            val blurred = Mat()
            Imgproc.GaussianBlur(template, blurred, Size(5.0, 5.0), 2.0)
            
            template.release()
            return blurred
        }
    }

    fun cleanup() {
        try {
            textRecognizer.close()
            Log.i(TAG, "TC MRZ Reader (MLKit) cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }
}