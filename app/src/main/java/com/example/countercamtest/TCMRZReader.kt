package com.example.countercamtest

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.coroutines.resume
import java.io.Serializable
import java.util.concurrent.Executors

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

    // MLKit Text Recognizer with MRZ-optimized settings for better MRZ recognition
    private val textRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.Builder()
            .setExecutor(Executors.newSingleThreadExecutor()) // Dedicated thread for better performance
            .build()
    )
    
    // Template-based MRZ detector removed - using direct OCR on whole image

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

    // Light condition detection
    enum class LightCondition {
        VERY_LOW, LOW, NORMAL, HIGH
    }

    init {
        // Ensure OpenCV is initialized for image preprocessing
        if (!OpenCVLoader.initLocal()) {
            Log.w(TAG, "OpenCV local initialization failed, trying debug mode")
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV initialization failed completely! Image preprocessing may not work.")
            } else {
                Log.i(TAG, "OpenCV initialized successfully in debug mode")
            }
        } else {
            Log.i(TAG, "OpenCV initialized successfully in local mode")
        }

        Log.i(TAG, "TC MRZ Reader initialized with MLKit")
    }

    /**
     * Check if OpenCV is properly initialized
     */
    private fun isOpenCVAvailable(): Boolean {
        return try {
            // Try to create a simple Mat to test OpenCV functionality
            val testMat = Mat()
            testMat.release()
            true
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV not available: ${e.message}")
            false
        }
    }

    /**
     * Analyze light conditions in the image to determine preprocessing strategy
     */
    private fun analyzeLightConditions(bitmap: Bitmap): LightCondition {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var totalBrightness = 0.0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            // Luminance formula (ITU-R BT.709)
            totalBrightness += 0.299 * r + 0.587 * g + 0.114 * b
        }

        val avgBrightness = totalBrightness / pixels.size

        Log.d(TAG, "Average brightness: $avgBrightness")

        return when {
            avgBrightness < 50 -> {
                Log.d(TAG, "Light condition: VERY_LOW")
                LightCondition.VERY_LOW
            }
            avgBrightness < 100 -> {
                Log.d(TAG, "Light condition: LOW")
                LightCondition.LOW
            }
            avgBrightness < 180 -> {
                Log.d(TAG, "Light condition: NORMAL")
                LightCondition.NORMAL
            }
            else -> {
                Log.d(TAG, "Light condition: HIGH")
                LightCondition.HIGH
            }
        }
    }


    /**
     * Check if OCR text is suitable for MRZ processing
     */
    private fun isTextSuitableForMRZ(text: String): Boolean {
        if (text.isEmpty()) return false

        // MRZ için uygun karakter oranını kontrol et
        val mrzChars = text.count { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<" }
        val ratio = mrzChars.toFloat() / text.length
        val isLongEnough = text.length > 30

        Log.d(TAG, "MRZ suitability: length=${text.length}, mrzRatio=${String.format("%.2f", ratio)}, suitable=${ratio > 0.6 && isLongEnough}")

        return ratio > 0.6 && isLongEnough
    }

    /**
     * Multi-scale OCR approach for challenging conditions
     */
    private suspend fun performMultiScaleOCR(bitmap: Bitmap, lightCondition: LightCondition): String {
        val scales = when (lightCondition) {
            LightCondition.VERY_LOW -> listOf(1.5f, 2.0f, 1.2f)
            LightCondition.LOW -> listOf(1.3f, 1.7f)
            else -> listOf(1.2f)
        }

        Log.d(TAG, "Trying multi-scale OCR with scales: $scales")

        for (scale in scales) {
            try {
                val scaledWidth = (bitmap.width * scale).toInt()
                val scaledHeight = (bitmap.height * scale).toInt()

                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap, scaledWidth, scaledHeight, true
                )

                val result = performMLKitOCR(scaledBitmap)
                scaledBitmap.recycle()

                if (isTextSuitableForMRZ(result)) {
                    Log.d(TAG, "Multi-scale OCR successful at scale: $scale")
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Multi-scale OCR failed at scale $scale: ${e.message}")
                continue
            }
        }

        Log.w(TAG, "Multi-scale OCR failed at all scales")
        return ""
    }

    /**
     * Ana MRZ okuma fonksiyonu - Enhanced with adaptive processing
     */
    suspend fun readTCMRZ(inputBitmap: Bitmap, overlayBounds: android.graphics.Rect? = null): TCMRZResult {
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "=== TC MRZ READING START (Enhanced MLKit) ===")

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

            // 2. Analyze light conditions first
            val lightCondition = analyzeLightConditions(inputBitmap)
            Log.d(TAG, "Detected light condition: $lightCondition")

            // 3. Try MLKit OCR first with original image (no preprocessing)
            Log.d(TAG, "Performing MLKit OCR on original image...")
            val originalOcrText = performMLKitOCR(inputBitmap)

            // 4. Evaluate original OCR result
            var ocrText = ""
            if (isTextSuitableForMRZ(originalOcrText)) {
                Log.d(TAG, "Original image OCR succeeded, using result")
                ocrText = originalOcrText
            } else {
                Log.d(TAG, "Original image OCR unsuitable, trying adaptive preprocessing...")

                // 5. Apply light-condition adaptive preprocessing
                val preprocessedBitmap = applyLowLightPreprocessing(inputBitmap, lightCondition)
                val processedOcrText = performMLKitOCR(preprocessedBitmap)

                if (isTextSuitableForMRZ(processedOcrText)) {
                    Log.d(TAG, "Adaptive preprocessing OCR succeeded")
                    ocrText = processedOcrText
                } else {
                    Log.d(TAG, "Single-scale preprocessing failed, trying multi-scale approach...")
                    // 6. Try multi-scale approach for challenging conditions
                    ocrText = performMultiScaleOCR(preprocessedBitmap, lightCondition)
                }

                // Cleanup preprocessed bitmap if different from input
                if (preprocessedBitmap != inputBitmap) {
                    preprocessedBitmap.recycle()
                }
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

    // MRZ region detection removed - using direct OCR on whole image

    /**
     * Advanced low-light preprocessing with adaptive parameters
     * Optimized for different lighting conditions
     */
    private fun applyLowLightPreprocessing(inputBitmap: Bitmap, lightCondition: LightCondition): Bitmap {
        Log.d(TAG, "Applying low-light preprocessing for condition: $lightCondition")

        // Check if OpenCV is available before processing
        if (!isOpenCVAvailable()) {
            Log.w(TAG, "OpenCV not available, returning original bitmap without preprocessing")
            return inputBitmap
        }

        try {
            val inputMat = Mat()
            Utils.bitmapToMat(inputBitmap, inputMat)

            // Convert to grayscale
            val grayMat = Mat()
            if (inputMat.channels() > 1) {
                Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_BGR2GRAY)
            } else {
                inputMat.copyTo(grayMat)
            }

            // 1. Gamma Correction (especially for low light)
            val gammaValue = when (lightCondition) {
                LightCondition.VERY_LOW -> 0.4
                LightCondition.LOW -> 0.6
                else -> 1.0
            }

            val gammaCorrected = Mat()
            if (gammaValue != 1.0) {
                val lookupTable = Mat(1, 256, CvType.CV_8U)
                val lutData = ByteArray(256)
                for (i in 0..255) {
                    val corrected = Math.pow(i / 255.0, gammaValue) * 255.0
                    lutData[i] = corrected.coerceIn(0.0, 255.0).toInt().toByte()
                }
                lookupTable.put(0, 0, lutData)
                Core.LUT(grayMat, lookupTable, gammaCorrected)
                lookupTable.release()
                Log.d(TAG, "Applied gamma correction: $gammaValue")
            } else {
                grayMat.copyTo(gammaCorrected)
            }

            // 2. Optimized CLAHE based on research (industry best practices)
            val claheParams = when (lightCondition) {
                LightCondition.VERY_LOW -> Pair(3.0, Size(8.0, 8.0))     // Research: clipLimit 2-3 optimal
                LightCondition.LOW -> Pair(2.5, Size(8.0, 8.0))          // Research: tileGridSize (8,8) standard
                LightCondition.NORMAL -> Pair(2.0, Size(8.0, 8.0))       // Research: clipLimit=2.0 most effective
                LightCondition.HIGH -> Pair(2.0, Size(8.0, 8.0))         // Consistent with research findings
            }

            val clahe = Imgproc.createCLAHE(claheParams.first, claheParams.second)
            val claheResult = Mat()
            clahe.apply(gammaCorrected, claheResult)
            Log.d(TAG, "Applied CLAHE: clipLimit=${claheParams.first}, tileSize=${claheParams.second}")

            // 3. MRZ-Specific Morphological Operations (Research-based)
            val morphProcessed = Mat()

            // First: Blackhat morphological operation (reveals dark text on light background)
            val blackhatKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 3.0))
            val blackhat = Mat()
            Imgproc.morphologyEx(claheResult, blackhat, Imgproc.MORPH_BLACKHAT, blackhatKernel)

            // Enhance the original with blackhat result
            val enhanced = Mat()
            Core.add(claheResult, blackhat, enhanced)

            // Second: Opening (noise removal) with square kernel
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val opened = Mat()
            Imgproc.morphologyEx(enhanced, opened, Imgproc.MORPH_OPEN, openKernel)

            // Third: Closing (gap filling) with rectangular kernel (width 3x height for MRZ)
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 3.0))
            Imgproc.morphologyEx(opened, morphProcessed, Imgproc.MORPH_CLOSE, closeKernel)

            Log.d(TAG, "Applied MRZ-specific morphological operations: blackhat + opening + closing")

            // 4. Enhanced Noise Reduction (after morphological operations)
            val denoised = Mat()
            when (lightCondition) {
                LightCondition.VERY_LOW, LightCondition.LOW -> {
                    // Stronger denoising for low light
                    Imgproc.bilateralFilter(morphProcessed, denoised, 9, 75.0, 75.0)
                    Log.d(TAG, "Applied bilateral filter for noise reduction")
                }
                else -> {
                    // Light denoising for normal light
                    Imgproc.GaussianBlur(morphProcessed, denoised, Size(3.0, 3.0), 1.0)
                    Log.d(TAG, "Applied Gaussian blur for light denoising")
                }
            }

            // Cleanup morphological matrices
            blackhat.release()
            enhanced.release()
            opened.release()
            morphProcessed.release()
            blackhatKernel.release()
            openKernel.release()
            closeKernel.release()

            // 4. Adaptive Sharpening
            val sharpened = Mat()
            if (lightCondition != LightCondition.VERY_LOW) {
                val blurred = Mat()
                Imgproc.GaussianBlur(denoised, blurred, Size(3.0, 3.0), 1.0)
                val mask = Mat()
                Core.subtract(denoised, blurred, mask)

                val sharpAmount = when (lightCondition) {
                    LightCondition.LOW -> 0.3
                    LightCondition.NORMAL -> 0.5
                    LightCondition.HIGH -> 0.7
                    else -> 0.0
                }
                Core.addWeighted(denoised, 1.0, mask, sharpAmount, 0.0, sharpened)

                blurred.release()
                mask.release()
                Log.d(TAG, "Applied adaptive sharpening: amount=$sharpAmount")
            } else {
                denoised.copyTo(sharpened)
                Log.d(TAG, "Skipped sharpening for very low light")
            }

            // Convert back to bitmap
            val resultBitmap = Bitmap.createBitmap(sharpened.cols(), sharpened.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(sharpened, resultBitmap)

            // Cleanup
            inputMat.release()
            grayMat.release()
            gammaCorrected.release()
            claheResult.release()
            denoised.release()
            sharpened.release()

            Log.d(TAG, "Low-light preprocessing completed successfully")
            return resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Error in low-light preprocessing: ${e.message}", e)
            return inputBitmap // Return original on error
        }
    }

    /**
     * Legacy light preprocessing - kept for backward compatibility
     */
    private fun applyLightPreprocessing(inputBitmap: Bitmap): Bitmap {
        return applyLowLightPreprocessing(inputBitmap, LightCondition.NORMAL)
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
    
    // Template-based MRZ Detection removed - using direct OCR approach

    fun cleanup() {
        try {
            textRecognizer.close()
            Log.i(TAG, "TC MRZ Reader (MLKit) cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }
}