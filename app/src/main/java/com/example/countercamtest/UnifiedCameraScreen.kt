package com.example.countercamtest

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun UnifiedCameraScreen(
    onNavigateToMRZResult: (TCMRZReader.TCMRZResult) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera permission
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // Unified validator
    val unifiedValidator = remember { UnifiedMatchingValidator(context) }

    // Validation state
    var validationResult by remember { mutableStateOf<UnifiedMatchingValidator.UnifiedValidationResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showPerformanceStats by remember { mutableStateOf(false) }
    
    // MRZ navigation state
    var shouldNavigateToMRZ by remember { mutableStateOf(false) }
    var mrzResultForNavigation by remember { mutableStateOf<TCMRZReader.TCMRZResult?>(null) }

    // Camera executor
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    // Analyzer reference for controlling analysis
    var analyzerRef by remember { mutableStateOf<UnifiedMatchingAnalyzer?>(null) }

    // Handle navigation after successful MRZ reading
    LaunchedEffect(shouldNavigateToMRZ, mrzResultForNavigation) {
        if (shouldNavigateToMRZ && mrzResultForNavigation != null) {
            kotlinx.coroutines.delay(1500) // Wait 1.5 seconds to show success message
            onNavigateToMRZResult(mrzResultForNavigation!!)
            shouldNavigateToMRZ = false // Reset navigation state
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            unifiedValidator.cleanup()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Camera preview
            UnifiedCameraPreview(
                context = context,
                lifecycleOwner = lifecycleOwner,
                executor = cameraExecutor,
                validator = unifiedValidator,
                onValidationResult = { result ->
                    validationResult = result
                    
                    // Check if we have a successful MRZ result
                    if (result.mrzData?.success == true && !shouldNavigateToMRZ) {
                        Log.i("UnifiedCameraScreen", "Successful MRZ reading detected - preparing for navigation")
                        mrzResultForNavigation = result.mrzData
                        shouldNavigateToMRZ = true
                        
                        // Stop the analyzer
                        analyzerRef?.disableAnalysis()
                    }
                },
                onAnalysisStateChange = { analyzing ->
                    isAnalyzing = analyzing
                },
                onAnalyzerCreated = { analyzer ->
                    analyzerRef = analyzer
                }
            )

            // Overlay UI
            UnifiedOverlayUI(
                validationResult = validationResult,
                isAnalyzing = isAnalyzing,
                showDetails = showDetails,
                showPerformanceStats = showPerformanceStats,
                onToggleDetails = { showDetails = !showDetails },
                onTogglePerformanceStats = { showPerformanceStats = !showPerformanceStats },
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {

    }
}

@Composable
fun UnifiedCameraPreview(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    executor: ExecutorService,
    validator: UnifiedMatchingValidator,
    onValidationResult: (UnifiedMatchingValidator.UnifiedValidationResult) -> Unit,
    onAnalysisStateChange: (Boolean) -> Unit,
    onAnalyzerCreated: (UnifiedMatchingAnalyzer) -> Unit = {}
) {
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        setupUnifiedCamera(
            context = context,
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            executor = executor,
            validator = validator,
            onValidationResult = onValidationResult,
            onAnalysisStateChange = onAnalysisStateChange,
            onAnalyzerCreated = onAnalyzerCreated
        )
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

private fun setupUnifiedCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    executor: ExecutorService,
    validator: UnifiedMatchingValidator,
    onValidationResult: (UnifiedMatchingValidator.UnifiedValidationResult) -> Unit,
    onAnalysisStateChange: (Boolean) -> Unit,
    onAnalyzerCreated: (UnifiedMatchingAnalyzer) -> Unit = {}
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Image analysis use case - optimized for unified matching
            val unifiedAnalyzer = UnifiedMatchingAnalyzer(validator, { result ->
                onAnalysisStateChange(false)
                onValidationResult(result)
            })
            
            // Notify that analyzer is created
            onAnalyzerCreated(unifiedAnalyzer)
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1920, 1080)) // High resolution for better matching
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(1) // Minimal queue for real-time processing
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(executor, unifiedAnalyzer)
                }

            // Camera selector - prefer back camera for better image quality
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Bind use cases
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            // Enable camera controls for better image quality
            val cameraControl = camera.cameraControl
            val cameraInfo = camera.cameraInfo

            // Enable auto-focus using preview surface
            try {
                val factory = previewView.meteringPointFactory
                val centerPoint = factory.createPoint(0.5f, 0.5f)
                val action = FocusMeteringAction.Builder(centerPoint).build()

                if (cameraInfo.isFocusMeteringSupported(action)) {
                    cameraControl.startFocusAndMetering(action)
                }
            } catch (e: Exception) {
                Log.w("UnifiedCameraSetup", "Auto-focus setup failed: ${e.message}")
            }

            onAnalysisStateChange(true)

        } catch (exc: Exception) {
            Log.e("UnifiedCameraSetup", "Camera setup error", exc)
        }
    }, ContextCompat.getMainExecutor(context))
}

@Composable
fun UnifiedOverlayUI(
    validationResult: UnifiedMatchingValidator.UnifiedValidationResult?,
    isAnalyzing: Boolean,
    showDetails: Boolean,
    showPerformanceStats: Boolean,
    onToggleDetails: () -> Unit,
    onTogglePerformanceStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top section - Main status
        UnifiedStatusCard(
            validationResult = validationResult,
            isAnalyzing = isAnalyzing
        )

        Spacer(modifier = Modifier.weight(1f))

        // Middle section - Camera frame
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            UnifiedIdCardFrame(validationResult = validationResult)
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom section - Controls and details
        Column {
            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleDetails,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Blue.copy(alpha = 0.8f)
                    )
                ) {
                    Icon(
                        imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showDetails) "Gizle" else "Detay", fontSize = 12.sp)
                }

                Button(
                    onClick = onTogglePerformanceStats,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Magenta.copy(alpha = 0.8f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showPerformanceStats) "Gizle" else "Stats", fontSize = 12.sp)
                }
            }

            // Details section
            if (showDetails && validationResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                UnifiedDetailsCard(validationResult = validationResult)
            }

            // Performance stats section
            if (showPerformanceStats) {
                Spacer(modifier = Modifier.height(8.dp))
                PerformanceStatsCard()
            }
        }
    }
}

@Composable
fun UnifiedStatusCard(
    validationResult: UnifiedMatchingValidator.UnifiedValidationResult?,
    isAnalyzing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isAnalyzing -> Color.Blue.copy(alpha = 0.9f)
                validationResult?.isValid == true -> Color.Green.copy(alpha = 0.9f)
                validationResult?.isValid == false -> Color.Red.copy(alpha = 0.9f)
                else -> Color.Gray.copy(alpha = 0.9f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        isAnalyzing -> Icons.Default.Search
                        validationResult?.isValid == true -> Icons.Default.CheckCircle
                        validationResult?.isValid == false -> Icons.Default.Error
                        else -> Icons.Default.PhotoCamera
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )

                Column {
                    Text(
                        text = when {
                            isAnalyzing -> "🔄 Gelişmiş Analiz Yapılıyor..."
                            validationResult?.isValid == true -> "✅ Geçerli TC Kimlik Kartı"
                            validationResult?.isValid == false -> "❌ Geçersiz veya Farklı Belge"
                            else -> "📱 Kimlik Kartını Çerçeveye Yerleştirin"
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Enhanced details
            validationResult?.let { result ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Güven: ${(result.confidence * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )

                    Text(
                        text = "Eşleşme: ${result.goodMatches}/${result.totalMatches}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (result.homographyFound) "🎯 Perspektif Düzeltildi" else "📐 Direkt Analiz",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )

                    Text(
                        text = "Ölçek: ${String.format("%.1f", result.scaleRatio)}x",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun UnifiedIdCardFrame(
    validationResult: UnifiedMatchingValidator.UnifiedValidationResult?
) {
    val frameColor = when {
        validationResult?.isValid == true -> Color.Green
        validationResult?.isValid == false -> Color.Red
        else -> Color.White
    }

    Canvas(
        modifier = Modifier
            .width(340.dp)
            .height(220.dp)
    ) {
        // Main frame
        drawIdCardFrame(frameColor)

        // Enhancement indicators
        validationResult?.let { result ->
            drawEnhancementIndicators(result)
        }
    }
}

private fun DrawScope.drawIdCardFrame(color: Color) {
    val strokeWidth = 4.dp.toPx()
    val cornerLength = 35.dp.toPx()

    // Corner lines (same as before but larger)
    // Top-left
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(cornerLength, 0f), strokeWidth = strokeWidth)
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(0f, cornerLength), strokeWidth = strokeWidth)

    // Top-right
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(size.width, 0f), end = androidx.compose.ui.geometry.Offset(size.width - cornerLength, 0f), strokeWidth = strokeWidth)
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(size.width, 0f), end = androidx.compose.ui.geometry.Offset(size.width, cornerLength), strokeWidth = strokeWidth)

    // Bottom-left
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(cornerLength, size.height), strokeWidth = strokeWidth)
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(0f, size.height - cornerLength), strokeWidth = strokeWidth)

    // Bottom-right
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(size.width, size.height), end = androidx.compose.ui.geometry.Offset(size.width - cornerLength, size.height), strokeWidth = strokeWidth)
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(size.width, size.height), end = androidx.compose.ui.geometry.Offset(size.width, size.height - cornerLength), strokeWidth = strokeWidth)
}

private fun DrawScope.drawEnhancementIndicators(result: UnifiedMatchingValidator.UnifiedValidationResult) {
    val indicatorSize = 15.dp.toPx()
    val padding = 8.dp.toPx()

    // Homography indicator (top-right)
    if (result.homographyFound) {
        drawRect(
            color = Color.Cyan,
            topLeft = androidx.compose.ui.geometry.Offset(size.width - indicatorSize - padding, padding),
            size = androidx.compose.ui.geometry.Size(indicatorSize, indicatorSize),
            style = Stroke(width = 2.dp.toPx())
        )
    }

    // Perspective correction indicator (top-left)
    if (result.perspectiveCorrected) {
        drawRect(
            color = Color.Yellow,
            topLeft = androidx.compose.ui.geometry.Offset(padding, padding),
            size = androidx.compose.ui.geometry.Size(indicatorSize, indicatorSize),
            style = Stroke(width = 2.dp.toPx())
        )
    }

    // Resolution adaptation indicator (bottom-right)
    if (result.resolutionAdapted) {
        drawRect(
            color = Color.Magenta,
            topLeft = androidx.compose.ui.geometry.Offset(size.width - indicatorSize - padding, size.height - indicatorSize - padding),
            size = androidx.compose.ui.geometry.Size(indicatorSize, indicatorSize),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun UnifiedDetailsCard(
    validationResult: UnifiedMatchingValidator.UnifiedValidationResult
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "🔬 Gelişmiş Analiz Sonuçları",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Technical metrics
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 200.dp)
            ) {
                items(
                    listOf(
                        "Eşleşme" to "${validationResult.goodMatches} / ${validationResult.totalMatches}",
                        "Güven Skoru" to "${(validationResult.confidence * 100).toInt()}%",
                        "Homografi" to if (validationResult.homographyFound) "✅ Bulundu" else "❌ Bulunamadı",
                        "Perspektif Düzeltme" to if (validationResult.perspectiveCorrected) "✅ Uygulandı" else "❌ Uygulanmadı",
                        "Çözünürlük Uyarlama" to if (validationResult.resolutionAdapted) "✅ Aktif" else "❌ Pasif",
                        "Ölçek Oranı" to String.format("%.2fx", validationResult.scaleRatio),
                        "Dönüş Açısı" to String.format("%.1f°", validationResult.rotationAngle),
                        "İşlem Süresi" to "${validationResult.processingTimeMs}ms"
                    )
                ) { (label, value) ->
                    UnifiedDetailItem(label = label, value = value)
                }
            }
        }
    }
}

@Composable
fun PerformanceStatsCard() {
    // This would be connected to the analyzer's performance stats
    // For now, showing placeholder
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = "📊 Performans İstatistikleri",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "• Başarı Oranı: ---%\n• Ortalama İşlem Süresi: ---ms\n• Toplam Analiz: ---\n• Ardışık Hata: ---",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun UnifiedDetailItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}