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
                    CameraScreen()
                }
            }
        }
    }
}

@Composable
private fun CameraScreen() {
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

    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val analyzer = remember {
        UnifiedMatchingAnalyzer(unifiedValidator, { result ->
            mainExecutor.execute {
                unifiedValidationResult = result
                if (result.isValid) {
                    Log.i("MainActivity", "CARD FOUND! Confidence: ${result.confidence}")
                }
            }
        })
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

        // MRZ ve güven skorunu göster
        unifiedValidationResult?.let { result ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Güven: ${(result.confidence * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                
                result.mrzData?.let { tcMrz ->
                    if (tcMrz.success) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "📄 TC MRZ Başarıyla Okundu",
                            color = Color.Green,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        tcMrz.data?.let { data ->
                            if (data.documentNumber.isNotEmpty()) {
                                Text(
                                    text = "Belge No: ${data.documentNumber}",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                            if (data.name.isNotEmpty() || data.surname.isNotEmpty()) {
                                Text(
                                    text = "Ad Soyad: ${data.name} ${data.surname}",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                            if (data.nationalId.isNotEmpty()) {
                                Text(
                                    text = "TC No: ${data.nationalId}",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                            if (data.birthDate.isNotEmpty()) {
                                Text(
                                    text = "Doğum: ${data.birthDate}",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Text(
                            text = "MRZ Güven: ${tcMrz.confidence.toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    } else if (result.isValid) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "📄 MRZ Okunamadı",
                            color = Color.Yellow,
                            fontSize = 12.sp
                        )
                        tcMrz.errorMessage?.let { error ->
                            Text(
                                text = error,
                                color = Color.Red,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

