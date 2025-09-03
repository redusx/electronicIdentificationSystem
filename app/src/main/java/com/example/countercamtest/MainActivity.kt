package com.example.countercamtest

import android.Manifest
import android.os.Bundle
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {
    companion object {
        init {
            if (!OpenCVLoader.initLocal()) {
                OpenCVLoader.initDebug()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    ValidationScreen()
                }
            }
        }
    }
}

@Composable
private fun ValidationScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.PERFORMANCE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var validationResult by remember { mutableStateOf(HybridAnalyzer.ValidationResult(isValid = false)) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val analyzer = remember {
        HybridAnalyzer(context = context) { result ->
            mainExecutor.execute {
                // Artık Mat release etmeye gerek yok, çünkü Mat gelmiyor.
                validationResult = result
            }
        }
    }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1280, 720))
            .build().apply { setAnalyzer(cameraExecutor, analyzer) }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.unbindAll()
        val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        mainExecutor.execute { camera = boundCamera }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScannerScreen(camera = camera, segments = null, srcWidth = 0, srcHeight = 0)
        ValidationOverlay(result = validationResult)
    }
}

@Composable
private fun ValidationOverlay(result: HybridAnalyzer.ValidationResult) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { config.screenHeightDp.dp.toPx() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // DEĞİŞİKLİK: Mat yerine, güvenli kopyalanmış 'corners' listesini kontrol et
        if (result.isValid && result.corners != null && result.srcWidth > 0) {

            fun map(point: DoubleArray): Offset {
                val srcWidth = result.srcWidth.toFloat()
                val srcHeight = result.srcHeight.toFloat()
                val x = point[0].toFloat()
                val y = point[1].toFloat()

                val rotatedX: Float
                val rotatedY: Float
                val rotatedWidth: Float
                val rotatedHeight: Float

                // AŞAMA 1: DÖNDÜRME (Sadece 90 ve 270 derece için)
                if (result.rotationDegrees == 90 || result.rotationDegrees == 270) {
                    rotatedX = y
                    rotatedY = srcWidth - x
                    rotatedWidth = srcHeight
                    rotatedHeight = srcWidth
                } else {
                    rotatedX = x
                    rotatedY = y
                    rotatedWidth = srcWidth
                    rotatedHeight = srcHeight
                }

                // AŞAMA 2: ÖLÇEKLEME (FILL_CENTER mantığı)
                val scaleX = screenWidth / rotatedWidth
                val scaleY = screenHeight / rotatedHeight
                val scale = max(scaleX, scaleY)

                // AŞAMA 3: ORTALAMA
                val scaledWidth = rotatedWidth * scale
                val scaledHeight = rotatedHeight * scale
                val dx = (screenWidth - scaledWidth) / 2f
                val dy = (screenHeight - scaledHeight) / 2f

                val finalX = rotatedX * scale + dx
                val finalY = rotatedY * scale + dy

                return Offset(finalX, finalY)
            }

            try {
                // Güvenli kopyalanmış listeden noktaları al
                val p1 = map(result.corners[0])
                val p2 = map(result.corners[1])
                val p3 = map(result.corners[2])
                val p4 = map(result.corners[3])

                val color = Color.Green
                val stroke = 3.dp.toPx()

                drawLine(color, p1, p2, strokeWidth = stroke)
                drawLine(color, p2, p3, strokeWidth = stroke)
                drawLine(color, p3, p4, strokeWidth = stroke)
                drawLine(color, p4, p1, strokeWidth = stroke)
            } catch (e: Exception) {
                // Bu bloğa girme ihtimali artık çok düşük.
            }
        }
    }
}