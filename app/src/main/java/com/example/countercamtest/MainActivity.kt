package com.example.countercamtest

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.ImageProxy
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import kotlinx.coroutines.delay
import com.example.countercamtest.objectdetection.objectdetector.ObjectDetectorHelper
import com.example.countercamtest.objectdetection.composables.ResultsOverlay
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult
import android.graphics.Bitmap


class MainActivity : ComponentActivity() {
    companion object {
        init { if (!OpenCVLoader.initLocal()) { OpenCVLoader.initDebug() } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    var currentMRZResult by remember { mutableStateOf<TCMRZReader.TCMRZResult?>(null) }

    NavHost(
        navController = navController,
        startDestination = "camera"
    ) {

        composable("camera") {
            CameraScreen(
                onMRZDetected = { mrzResult ->
                    // Store the MRZ result and navigate
                    currentMRZResult = mrzResult
                    if (mrzResult.success) {
                        navController.navigate("mrz_result")
                    }
                }
            )
        }

        composable("mrz_result") {
            currentMRZResult?.let { mrzResult ->
                MRZResultScreen(
                    mrzResult = mrzResult,
                    onNavigateBack = {
                        navController.navigate("camera") {
                            popUpTo("mrz_result") { inclusive = true }
                        }
                    },
                    onNavigateToNFC = {
                        // Start NFC Activity with MRZ data
                        val intent = Intent(context, NFCActivity::class.java).apply {
                            putExtra(NFCActivity.EXTRA_MRZ_RESULT, mrzResult)
                        }
                        context.startActivity(intent)
                    }
                )
            } ?: run {
                // Fallback if no MRZ result is available
                navController.navigate("camera") {
                    popUpTo("mrz_result") { inclusive = true }
                }
            }
        }
    }
}

@Composable
private fun CameraScreen(
    onMRZDetected: (TCMRZReader.TCMRZResult) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // UnifiedMatchingValidator kullan
    val unifiedValidator = remember { UnifiedMatchingValidator(context) }
    var unifiedValidationResult by remember { mutableStateOf<UnifiedMatchingValidator.UnifiedValidationResult?>(null) }
    var overlayBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }

    // Object Detection states
    var objectDetectionResults by remember { mutableStateOf<ObjectDetectionResult?>(null) }
    var objectDetectorHelper by remember { mutableStateOf<ObjectDetectorHelper?>(null) }
    var frameWidth by remember { mutableStateOf(1920) }
    var frameHeight by remember { mutableStateOf(1080) }

    // MRZ okuma durumu ve analyzer kontrol
    var cardDetected by remember { mutableStateOf(false) }
    var mrzProcessing by remember { mutableStateOf(false) }
    var mrzProcessed by remember { mutableStateOf(false) }
    var analyzerEnabled by remember { mutableStateOf(true) }

    // Kimlik doğrulama durumları
    var isProcessingIdentity by remember { mutableStateOf(false) }
    var identityValidated by remember { mutableStateOf(false) }
    var mrzRetryCount by remember { mutableStateOf(0) }

    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var analyzerInstance by remember { mutableStateOf<UnifiedMatchingAnalyzer?>(null) }

    // Initialize ObjectDetectorHelper immediately
    val objectDetectorHelperInitialized = remember {
        ObjectDetectorHelper(
            context = context,
            threshold = 0.4f, // Confidence threshold for detection
            maxResults = 10, // Increased from 5 to 10
            currentDelegate = ObjectDetectorHelper.DELEGATE_CPU,
            currentModel = ObjectDetectorHelper.MODEL_EFFICIENTDETV2, // Use Lite0 - faster and more general
            runningMode = RunningMode.IMAGE
        ).also {
            Log.i("MainActivity", "🤖 ObjectDetectorHelper initialized with threshold=0.3f, maxResults=10, model=LITE0")
        }
    }

    // Set the initialized helper
    LaunchedEffect(objectDetectorHelperInitialized) {
        objectDetectorHelper = objectDetectorHelperInitialized
    }

    // Helper function to reset detection state
    fun resetDetectionState() {
        cardDetected = false
        mrzProcessing = false
        mrzProcessed = false
        isProcessingIdentity = false
        identityValidated = false
        mrzRetryCount = 0
        analyzerEnabled = true
        analyzerInstance?.enableAnalysis()

        // Reset analyzer to initial state to clear MRZ pause
        analyzerInstance?.resetToInitialState()

        Log.d("MainActivity", "Detection state reset - analyzer reactivated for continuous detection")
    }

    val analyzer = remember(objectDetectorHelperInitialized) {
        UnifiedMatchingAnalyzer(
            unifiedValidator = unifiedValidator,
            objectDetectorHelper = objectDetectorHelperInitialized,
            onValidationResult = { result ->
                mainExecutor.execute {
                    // --- DETAILED LOGGING ---
                    Log.d("MainActivity", "--- Validation Result Received ---")
                    Log.d("MainActivity", "State: analyzerEnabled=$analyzerEnabled, mrzProcessed=$mrzProcessed, cardDetected=$cardDetected, mrzProcessing=$mrzProcessing")
                    Log.d("MainActivity", "Result: isValid=${result.isValid}, mrzSuccess=${result.mrzData?.success}, mrzError='${result.mrzData?.errorMessage}'")
                    // --- END LOGGING ---

                    if (!analyzerEnabled || mrzProcessed) {
                        Log.w("MainActivity", "🚫 SKIPPING result: Analyzer disabled or MRZ already processed.")
                        return@execute
                    }

                    unifiedValidationResult = result

                    if (result.isValid && !cardDetected && !mrzProcessing && !mrzProcessed) {
                        Log.i("MainActivity", "✅ CONDITION MET: Valid card detected and not already processing.")
                        Log.i("MainActivity", "🚀 Setting state: cardDetected=true, isProcessingIdentity=true")
                        cardDetected = true
                        isProcessingIdentity = true

                        result.mrzData?.let { mrzResult ->
                            Log.d("MainActivity", "  ➡️ MRZ data is present. Checking for success...")
                            if (mrzResult.success) {
                                Log.i("MainActivity", "  🎉🎉🎉 MRZ READ SUCCESS! Finalizing...")
                                identityValidated = true
                                isProcessingIdentity = false
                                mrzProcessed = true
                                analyzerEnabled = false
                                analyzerInstance?.disableAnalysis()
                                Log.i("MainActivity", "  ➡️➡️➡️ Navigating to results screen...")
                                onMRZDetected(mrzResult)
                            } else {
                                Log.w("MainActivity", "  ❌ MRZ READ FAILED. Error: ${mrzResult.errorMessage}")
                                mrzRetryCount += 1
                                if (mrzRetryCount >= 3) {
                                    Log.e("MainActivity", "  ❌ MRZ read failed after 3 attempts. Resetting state.")
                                    resetDetectionState()
                                } else {
                                    Log.w("MainActivity", "  ❌ Retrying... (Attempt ${mrzRetryCount}/3). Resetting card detection state.")
                                    isProcessingIdentity = false
                                    cardDetected = false
                                }
                            }
                        } ?: run {
                            Log.e("MainActivity", "  ❌ ERROR: Card is valid, but NO MRZ DATA was returned from validator.")
                            isProcessingIdentity = false
                            cardDetected = false
                        }
                    } else if (result.isValid) {
                        Log.w("MainActivity", "📋 SKIPPING result: Card is valid, but conditions not met. Current state prevents processing (cardDetected=$cardDetected, mrzProcessing=$mrzProcessing, mrzProcessed=$mrzProcessed).")
                    } else {
                        // This is the normal case when no valid card is in view, so we use a less alarming log level.
                        Log.d("MainActivity", "📋 Skipping result: Card is not valid.")
                    }
                }
            },
        onObjectDetectionResult = { objResult, detectionFrameWidth, detectionFrameHeight ->
            mainExecutor.execute {
                Log.d("MainActivity", "📦 Object detection callback received")

                if (objResult != null) {
                    val detectionCount = objResult.detections()?.size ?: 0
                    Log.i("MainActivity", "🎯 Setting objectDetectionResults with $detectionCount detections, frame: ${detectionFrameWidth}x${detectionFrameHeight}")

                    objectDetectionResults = objResult
                    frameWidth = detectionFrameWidth
                    frameHeight = detectionFrameHeight

                    // Log bounding box coordinates for debugging
                    objResult.detections()?.forEachIndexed { index, detection ->
                        val bbox = detection.boundingBox()
                        val category = detection.categories().firstOrNull()?.categoryName() ?: "Unknown"
                        val score = detection.categories().firstOrNull()?.score() ?: 0f
                        Log.i("MainActivity", "🔸 UI Detection #$index: $category (${String.format("%.2f", score)}) bbox=[${bbox.left}, ${bbox.top}, ${bbox.right}, ${bbox.bottom}]")
                    }

                    Log.d("MainActivity", "📱 Current objectDetectionResults state: ${objectDetectionResults?.detections()?.size ?: 0} objects")
                } else {
                    Log.w("MainActivity", "⚠️ Object detection result is null")
                    objectDetectionResults = null
                }
            }
        },
        overlayBounds = overlayBounds
        ).also {
            analyzerInstance = it
        }
    }

    // Reset analyzer when analyzer instance is created
    LaunchedEffect(analyzerInstance) {
        analyzerInstance?.let {
            Log.i("MainActivity", "🔄 Unified Analyzer instance ready - Resetting state")
            it.enableAnalysis()
            resetDetectionState()
        }
    }

    // ORB analyzer timeout mechanism
    LaunchedEffect(isProcessingIdentity) {
        if (isProcessingIdentity) {
            delay(10000) // 10 second timeout for ORB processing
            if (isProcessingIdentity && !mrzProcessed) {
                Log.w("MainActivity", "⏰ ORB processing timeout! Resetting states...")
                resetDetectionState()
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            unifiedValidator.cleanup()
            objectDetectorHelper?.clearObjectDetector()
        }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect

        // Reset detection state to ensure continuous detection
        delay(1000) // Wait for initialization
        resetDetectionState()

        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
            .build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1080, 1920)) // Request higher resolution for better OCR
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().apply {
                setAnalyzer(cameraExecutor) { imageProxy ->
                    // Only use Unified Matching Analysis for now
                    // Object detection will be handled via callback from UnifiedMatchingAnalyzer
                    analyzer.analyze(imageProxy)
                }
            }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.unbindAll()
        val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        mainExecutor.execute { camera = boundCamera }
    }

    // MRZ processing timeout mekanizması
    LaunchedEffect(mrzProcessing) {
        if (mrzProcessing) {
            delay(5000) // 5 saniye timeout
            if (mrzProcessing && !mrzProcessed) {
                Log.w("MainActivity", "⏰ MRZ processing timeout! Resetting states...")
                resetDetectionState()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Full screen camera preview
        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        // Object Detection Results Overlay - full screen
        // Only render if we have valid frame dimensions (not initial values)
        if (frameWidth > 100 && frameHeight > 100) {
            objectDetectionResults?.let { results ->
                Log.d("MainActivity", "🎨 Rendering ResultsOverlay with ${results.detections()?.size ?: 0} detections, frame: ${frameWidth}x${frameHeight}")
                ResultsOverlay(
                    results = results,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight
                )
            }
        }

        // ScannerScreen overlay with card frame
        ScannerScreen(
            camera = camera,
            segments = null,
            srcWidth = 0,
            srcHeight = 0,
            isCardDetected = unifiedValidationResult?.isValid == true,
            onOverlayBoundsChanged = { bounds ->
                overlayBounds = bounds
                analyzerInstance?.updateOverlayBounds(bounds)
                Log.d("MainActivity", "Overlay bounds updated: $bounds")
            },
            isProcessingIdentity = isProcessingIdentity,
            isProcessingMRZ = mrzProcessing,
            identityValidated = identityValidated,
            mrzRetryCount = mrzRetryCount
        )
    }
}

// Extension function to convert ImageProxy to Bitmap safely
private fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}


