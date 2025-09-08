package com.example.countercamtest

import android.Manifest
import android.graphics.Matrix
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.opencv.core.Point
import org.opencv.core.Size
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.content.Intent

class MainActivity : ComponentActivity() {
    companion object {
        const val TAG = "MainActivity"
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
    
    // MRZ okuma durumu ve analyzer kontrol
    var cardDetected by remember { mutableStateOf(false) }
    var mrzProcessing by remember { mutableStateOf(false) }
    var mrzProcessed by remember { mutableStateOf(false) }
    var analyzerEnabled by remember { mutableStateOf(true) }

    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var analyzerInstance by remember { mutableStateOf<UnifiedMatchingAnalyzer?>(null) }

    val analyzer = remember {
        UnifiedMatchingAnalyzer(unifiedValidator, { result ->
            mainExecutor.execute {
                // Analyzer devre dışıysa işlem yapma
                if (!analyzerEnabled || mrzProcessed) {
                    Log.d("MainActivity", "Analyzer disabled or MRZ already processed - skipping")
                    return@execute
                }
                
                unifiedValidationResult = result
                
                // Kart tespit edildiğinde ve henüz MRZ işlemi yapılmadıysa
                if (result.isValid && !cardDetected && !mrzProcessing && !mrzProcessed) {
                    Log.i("MainActivity", "🎯 CARD DETECTED! Starting MRZ processing...")
                    cardDetected = true
                    mrzProcessing = true
                    
                    // MRZ okumasını kontrol et
                    result.mrzData?.let { mrzResult ->
                        if (mrzResult.success) {
                            Log.i("MainActivity", "🎉 MRZ READ SUCCESS! Disabling analyzer...")
                            mrzProcessed = true
                            mrzProcessing = false
                            analyzerEnabled = false
                            
                            // Analyzer'ı durdur
                            analyzerInstance?.disableAnalysis()
                            
                            Log.i("MainActivity", "📋 Navigating to results...")
                            onMRZDetected(mrzResult)
                        } else {
                            mrzProcessing = false
                            Log.w("MainActivity", "MRZ read failed: ${mrzResult.errorMessage}")
                        }
                    } ?: run {
                        mrzProcessing = false
                        Log.w("MainActivity", "No MRZ data available")
                    }
                }
            }
        }).also { 
            analyzerInstance = it
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            unifiedValidator.cleanup()
        }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1920, 1080))
            .build().apply { setAnalyzer(cameraExecutor, analyzer) }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.unbindAll()
        val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        mainExecutor.execute { camera = boundCamera }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        ScannerScreen(
            camera = camera,
            segments = null,
            srcWidth = 0,
            srcHeight = 0,
            isCardDetected = unifiedValidationResult?.isValid == true
        )

    }
}

