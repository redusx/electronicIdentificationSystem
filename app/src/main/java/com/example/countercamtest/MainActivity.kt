package com.example.countercamtest

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.max
import org.opencv.android.OpenCVLoader

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
                    ContourScreen()
                }
            }
        }
    }
}

@Composable
private fun ContourScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

    val analyzerParams = remember {
        ContourAnalyzer.Params(
            cannyThreshold1 = 50.0,
            cannyThreshold2 = 150.0,
            minContourArea = 50.0
        )
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var segments by remember { mutableStateOf(FloatArray(0)) }
    var srcWidth by remember { mutableStateOf(0) }
    var srcHeight by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(0.0) }

    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val analyzer = remember {
        ContourAnalyzer(
            params = analyzerParams,
            onResult = { frame ->
                mainExecutor.execute {
                    segments = frame.segmentsXYXY
                    srcWidth = frame.srcWidth
                    srcHeight = frame.srcHeight
                    fps = frame.fps
                }
            }
        )
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect

        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(cameraExecutor, analyzer)
            }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageAnalysis
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            if (segments.isNotEmpty() && srcWidth > 0 && srcHeight > 0) {
                val scale = max(size.width / srcWidth.toFloat(), size.height / srcHeight.toFloat())
                val dx = (size.width - srcWidth * scale) / 2f
                val dy = (size.height - srcHeight * scale) / 2f
                fun map(x: Float, y: Float) = Offset(x * scale + dx, y * scale + dy)
                var i = 0
                while (i + 3 < segments.size) {
                    val p1 = map(segments[i], segments[i + 1])
                    val p2 = map(segments[i + 2], segments[i + 3])
                    drawLine(Color.Red, p1, p2, strokeWidth = 2f)
                    i += 4
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x80000000), shape = MaterialTheme.shapes.medium)
        ) {
            Text(
                text = "FPS: ${"%.1f".format(fps)}",
                color = Color.White,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}