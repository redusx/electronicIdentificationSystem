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
    ) {
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
        val birthDate: String,
        val gender: String,
        val expiryDate: String,
        val nationality: String = "TUR"
    )

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
        
        // MINIMAL PREPROCESSING - MLKit works better with less processing
        Log.d(TAG, "Using minimal preprocessing for MLKit")
        
        // Convert back to Bitmap directly with minimal processing
        val resultBitmap = Bitmap.createBitmap(mrzMat.cols(), mrzMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mrzMat, resultBitmap)
        
        Log.d(TAG, "MRZ preprocessing completed: ${resultBitmap.width}x${resultBitmap.height}")
        
        // Cleanup
        inputMat.release()
        grayMat.release()
        mrzMat.release()
        
        return resultBitmap
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
        Log.d(TAG, "Parsing MRZ from OCR text (${ocrText.length} chars)")
        Log.d(TAG, "Raw OCR text: '${ocrText.take(300)}${if (ocrText.length > 300) "..." else ""}'")
        
        if (ocrText.isEmpty()) {
            Log.w(TAG, "Empty OCR text received")
            return emptyList()
        }
        
        // Step 1: Find potential MRZ lines in the OCR text
        val allLines = ocrText.split(Regex("[\\r\\n]+"))
        Log.d(TAG, "Split into ${allLines.size} raw lines")
        
        allLines.forEachIndexed { index, line ->
            if (line.trim().isNotEmpty()) {
                Log.d(TAG, "Raw line $index: '${line.trim()}' (${line.trim().length} chars)")
            }
        }
        
        // Step 2: Look for MRZ-like patterns
        val potentialMrzLines = mutableListOf<String>()
        
        for (line in allLines) {
            val cleanLine = line.trim()
                .replace(Regex("\\s+"), "") // Remove spaces
                .replace("O", "0") // OCR corrections
                .replace("l", "1") // OCR corrections
                .replace("I", "1") // OCR corrections
                .replace("S", "5") // OCR corrections
                .replace("G", "6") // OCR corrections
                .replace("B", "8") // OCR corrections
                .toUpperCase()
            
            // Check if line has MRZ characteristics
            if (cleanLine.length >= 25) { // MRZ lines are typically 30 chars but allow some error
                val mrzCharCount = cleanLine.count { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<" }
                val mrzPercentage = mrzCharCount.toFloat() / cleanLine.length
                
                Log.d(TAG, "Potential MRZ line: '$cleanLine' (${cleanLine.length} chars, ${(mrzPercentage * 100).toInt()}% MRZ chars)")
                
                if (mrzPercentage > 0.8) { // 80% of characters should be MRZ-valid
                    potentialMrzLines.add(cleanLine)
                    Log.d(TAG, "Added as potential MRZ line")
                }
            }
        }
        
        Log.d(TAG, "Found ${potentialMrzLines.size} potential MRZ lines")
        
        // Step 3: If we have too few lines but one long line, try to split it
        if (potentialMrzLines.size == 1 && potentialMrzLines[0].length >= 75) {
            val longLine = potentialMrzLines[0]
            val splitLines = mutableListOf<String>()
            
            Log.d(TAG, "Attempting to split long line: '$longLine' (${longLine.length} chars)")
            
            // Split into 30-character chunks
            for (i in 0 until longLine.length step 30) {
                val end = minOf(i + 30, longLine.length)
                val chunk = longLine.substring(i, end)
                
                if (chunk.length >= 25) { // Must be reasonably long
                    splitLines.add(chunk)
                    Log.d(TAG, "Split chunk ${splitLines.size}: '$chunk' (${chunk.length} chars)")
                }
                
                if (splitLines.size >= 3) break // TC MRZ has 3 lines
            }
            
            if (splitLines.size >= 2) {
                potentialMrzLines.clear()
                potentialMrzLines.addAll(splitLines)
                Log.d(TAG, "Successfully split into ${splitLines.size} MRZ lines")
            }
        }
        
        // Step 4: Take the best 3 lines and pad/trim to 30 characters if needed
        val finalLines = potentialMrzLines.take(3).map { line ->
            when {
                line.length == 30 -> line
                line.length < 30 -> line.padEnd(30, '<')
                else -> line.take(30)
            }
        }
        
        Log.d(TAG, "Final MRZ lines: ${finalLines.size}")
        finalLines.forEachIndexed { index, line ->
            Log.d(TAG, "Final line ${index + 1}: '$line' (${line.length} chars)")
        }
        
        return finalLines
    }

    private fun validateMRZFormat(mrzLines: List<String>): Boolean {
        if (mrzLines.isEmpty()) {
            Log.w(TAG, "No MRZ lines to validate")
            return false
        }
        
        if (mrzLines.size < 2) {
            Log.w(TAG, "Insufficient MRZ lines: ${mrzLines.size}, need at least 2")
            return false
        }
        
        Log.d(TAG, "Validating TC MRZ format with ${mrzLines.size} lines:")
        mrzLines.forEachIndexed { index, line ->
            Log.d(TAG, "Line ${index + 1}: '$line' (length: ${line.length})")
        }
        
        // Daha esnek validation - sadece temel pattern kontrolleri
        var validLines = 0
        var hasIdPattern = false
        var hasNamePattern = false
        var hasDatePattern = false
        
        mrzLines.forEachIndexed { index, line ->
            when (index) {
                0 -> {
                    val line1Valid = validateLine1Flexible(line)
                    Log.d(TAG, "Line 1 validation: $line1Valid")
                    if (line1Valid) {
                        validLines++
                        hasIdPattern = true
                    }
                }
                1 -> {
                    val line2Valid = validateLine2Flexible(line)
                    Log.d(TAG, "Line 2 validation: $line2Valid")
                    if (line2Valid) {
                        validLines++
                        hasDatePattern = true
                    }
                }
                2 -> {
                    val line3Valid = validateLine3Flexible(line)
                    Log.d(TAG, "Line 3 validation: $line3Valid")
                    if (line3Valid) {
                        validLines++
                        hasNamePattern = true
                    }
                }
            }
        }
        
        // En az 1 satır geçerli olmalı ve temel pattern'lar bulunmalı
        val isValid = validLines >= 1 && (hasIdPattern || hasDatePattern || hasNamePattern)
        
        Log.d(TAG, "Valid lines: $validLines/${mrzLines.size}, hasId: $hasIdPattern, hasDate: $hasDatePattern, hasName: $hasNamePattern")
        Log.d(TAG, "Overall MRZ validation result: $isValid")
        
        return isValid
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

    // Esnek validation metodları - OCR hatalarına daha toleranslı
    private fun validateLine1Flexible(line: String): Boolean {
        if (line.length < 25 || line.length > 35) {
            Log.d(TAG, "Line 1 length check failed: ${line.length}")
            return false
        }
        
        // I ile başlamalı ve TUR içermeli
        val startsWithI = line.startsWith("I") || line.startsWith("1")
        val containsTUR = line.contains("TUR") || line.contains("TU8") || line.contains("TU6")
        val hasNumbers = line.any { it.isDigit() }
        
        Log.d(TAG, "Line 1 flexible validation: startsWithI=$startsWithI, containsTUR=$containsTUR, hasNumbers=$hasNumbers")
        
        return startsWithI && (containsTUR || hasNumbers)
    }
    
    private fun validateLine2Flexible(line: String): Boolean {
        if (line.length < 25 || line.length > 35) {
            Log.d(TAG, "Line 2 length check failed: ${line.length}")
            return false
        }
        
        // Başında tarih benzeri pattern (6 rakam) ve TUR içermeli
        val startNumbers = line.take(6).count { it.isDigit() }
        val containsTUR = line.contains("TUR") || line.contains("TU8") || line.contains("TU6") 
        val hasGender = line.contains("M") || line.contains("F")
        val hasNumbers = line.count { it.isDigit() } >= 12 // En az 12 rakam olmalı (tarihler)
        
        Log.d(TAG, "Line 2 flexible validation: startNumbers=$startNumbers, containsTUR=$containsTUR, hasGender=$hasGender, hasNumbers=$hasNumbers")
        
        return startNumbers >= 4 && (containsTUR || hasGender || hasNumbers)
    }
    
    private fun validateLine3Flexible(line: String): Boolean {
        if (line.length < 25 || line.length > 35) {
            Log.d(TAG, "Line 3 length check failed: ${line.length}")
            return false
        }
        
        // Çoğunlukla harf olmalı ve << pattern'ı içerebilir
        val letterCount = line.count { it.isLetter() }
        val hasDoubleAngle = line.contains("<<") || line.contains("<") || line.contains(">>")
        val letterPercentage = letterCount.toFloat() / line.length
        
        Log.d(TAG, "Line 3 flexible validation: letterCount=$letterCount, hasDoubleAngle=$hasDoubleAngle, letterPercentage=${(letterPercentage * 100).toInt()}%")
        
        return letterPercentage > 0.6 // En az %60 harf
    }

    private fun extractPersonalInfo(mrzLines: List<String>): PersonalData? {
        try {
            if (mrzLines.size != 3) return null
            
            Log.d(TAG, "Extracting personal info from TC MRZ lines")
            
            val line1 = mrzLines[0] // I + kurum + TUR + seri(9) + kontrol + TC(11) + dolgu(4)  
            val line2 = mrzLines[1] // doğum(6) + kontrol + cinsiyet + geçerlilik(6) + kontrol + TUR + uyruk(11) + kontrol
            val line3 = mrzLines[2] // soyad + << + ad + dolgu
            
            // TC MRZ format'ına göre extraction
            // Line 1: I[kurum]TUR[seri 9][kontrol][TC 11][dolgu 4]
            val documentType = line1.substring(0, 1) // "I"
            val issuingAuthority = line1.substring(1, 2) // Kurum
            val countryCode = line1.substring(2, 5) // "TUR"
            val documentNumber = line1.substring(5, 14).trim('<') // Seri numarası
            val nationalId = line1.substring(15, 26) // TC kimlik (11 rakam)
            
            // Line 2: [doğum 6][kontrol][cinsiyet][geçerlilik 6][kontrol]TUR[uyruk 11][kontrol]
            val birthDateMrz = line2.substring(0, 6)
            val birthDate = formatTCDate(birthDateMrz) // YYMMDD -> YYYY-MM-DD
            val gender = line2.substring(7, 8) // M veya F
            val expiryDateMrz = line2.substring(8, 14) 
            val expiryDate = formatTCDate(expiryDateMrz) // YYMMDD -> YYYY-MM-DD
            val nationality = line2.substring(15, 18) // "TUR"
            
            // Line 3: [soyad]<<[ad]<dolgu karakterleri
            // Soyad ve ad arasında "<<" var, ad sonrası dolgu için "<" karakterleri
            val nameSection = line3.replace("<", " ").trim()
            val nameParts = nameSection.split("  ").filter { it.isNotEmpty() }
            val surname = nameParts.getOrNull(0)?.trim() ?: ""
            val name = nameParts.getOrNull(1)?.trim() ?: ""
            
            Log.d(TAG, "Extracted personal data: $surname, $name, $nationalId")
            
            return PersonalData(
                documentNumber = documentNumber,
                nationalId = nationalId,
                name = name,
                surname = surname,
                birthDate = birthDate,
                gender = gender,
                expiryDate = expiryDate,
                nationality = nationality
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting personal info: ${e.message}")
            return null
        }
    }

    private fun formatTCDate(dateStr: String): String {
        if (dateStr.length != 6) return dateStr
        
        // TC MRZ formatı: YYMMDD (yıl son 2 hane + ay + gün)
        // Örnek: 040314 -> 14.03.2004 -> 2004-03-14
        val yy = dateStr.substring(0, 2).toIntOrNull() ?: return dateStr
        val mm = dateStr.substring(2, 4)
        val dd = dateStr.substring(4, 6)
        
        // Yıl hesaplama (2000-2099 arası varsayım)
        val yyyy = if (yy > 50) "19$yy" else "20$yy" // 50+ ise 19XX, altı ise 20XX
        
        return "$yyyy-$mm-$dd"
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