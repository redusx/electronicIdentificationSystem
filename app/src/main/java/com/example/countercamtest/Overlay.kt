package com.example.countercamtest

import android.util.Log
import androidx.camera.core.Camera
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.RoundRect as ComposeRoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas

@Composable
fun ScannerScreen(
    camera: Camera?,
    segments: FloatArray? = null,
    srcWidth: Int = 0,
    srcHeight: Int = 0,
    isCardDetected: Boolean = false,
    onOverlayBoundsChanged: ((android.graphics.Rect) -> Unit)? = null,
    // Yeni akış durumu parametreleri
    isProcessingIdentity: Boolean = false,
    isProcessingMRZ: Boolean = false,
    identityValidated: Boolean = false,
    mrzRetryCount: Int = 0
) {
    var flashEnabled by remember { mutableStateOf(false) }

    val scanAnimation = rememberInfiniteTransition(label = "scan_animation")
    val scanOffset by scanAnimation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_offset_anim",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.5f))

            CardOverlayContainer(
                scanOffset = scanOffset,
                isCardDetected = isCardDetected,
                onOverlayBoundsChanged = onOverlayBoundsChanged,
                isProcessingIdentity = isProcessingIdentity,
                isProcessingMRZ = isProcessingMRZ,
                identityValidated = identityValidated,
                mrzRetryCount = mrzRetryCount
            )

            Spacer(modifier = Modifier.weight(0.5f))

            // Controls removed - clean UI
        }

    }
}

@Composable
private fun CardOverlayContainer(
    scanOffset: Float,
    isCardDetected: Boolean = false,
    onOverlayBoundsChanged: ((android.graphics.Rect) -> Unit)? = null,
    isProcessingIdentity: Boolean = false,
    isProcessingMRZ: Boolean = false,
    identityValidated: Boolean = false,
    mrzRetryCount: Int = 0
) {



    var cardOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CardCanvas(
            scanOffset = scanOffset,
            isCardDetected = isCardDetected,
            isProcessingIdentity = isProcessingIdentity,
            identityValidated = identityValidated,
            modifier = Modifier.onGloballyPositioned { coords ->
                cardOffset = coords.positionInRoot()
                // Calculate overlay bounds and notify callback
                val bounds = android.graphics.Rect(
                    coords.positionInRoot().x.toInt(),
                    coords.positionInRoot().y.toInt(),
                    (coords.positionInRoot().x + coords.size.width).toInt(),
                    (coords.positionInRoot().y + coords.size.height).toInt()
                )
                onOverlayBoundsChanged?.invoke(bounds)
            }
        )
        Spacer(modifier = Modifier.height(15.dp))
        // Dinamik durum mesajları
        when {
            isProcessingMRZ -> {
                val (mrzText, mrzSubText) = when {
                    mrzRetryCount > 0 -> Pair(
                        "MRZ Bilgileri Okunuyor... (${mrzRetryCount}/3)",
                        "Yeni görüntü ile deneniyor"
                    )
                    else -> Pair(
                        "MRZ Bilgileri Okunuyor...",
                        "Lütfen kartı sabit tutun"
                    )
                }
                Text(
                    text = mrzText,
                    color = if (mrzRetryCount > 0) Color.Cyan else Color.Yellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = mrzSubText,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            isProcessingIdentity -> {
                Text(
                    text = "Kimlik Kartı Doğrulanıyor...",
                    color = Color.Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "Kart analiz ediliyor",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            identityValidated -> {
                Text(
                    text = "Kimlik Kartı Doğrulandı ✓",
                    color = Color.Green,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "MRZ verisi bekleniyor...",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            isCardDetected -> {
                Text(
                    text = "Kart Tespit Edildi!",
                    color = Color.Green,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "Kartı çerçeve içinde hizalayın",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            else -> {
                Text(
                    text = "TC Kimlik Kartı Arka Yüzünü Hizalayın",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "Kartı çerçeve içinde tutun",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun CardCanvas(
    scanOffset: Float,
    isCardDetected: Boolean = false,
    modifier: Modifier = Modifier,
    // ORB kimlik doğrulama durumları
    isProcessingIdentity: Boolean = false,
    identityValidated: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.90f)
            .aspectRatio(1f / 1.586f)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cardWidth = size.width
            val cardHeight = size.height
            val corner = CornerRadius(cardWidth * 0.05f)

            // Durum bazında renk seçimi
            val (backgroundColor, borderColor) = when {
                identityValidated -> Pair(Color.Green.copy(alpha = 0.4f), Color.Green)
                isProcessingIdentity -> Pair(Color.Cyan.copy(alpha = 0.3f), Color.Cyan)
                isCardDetected -> Pair(Color.Yellow.copy(alpha = 0.3f), Color.Yellow)
                else -> Pair(Color.Black.copy(alpha = 0.2f), Color.White)
            }

            drawRoundRect(color = backgroundColor, size = size, cornerRadius = corner)
            drawRoundRect(color = borderColor, size = size, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
            
            // ORB kimlik doğrulama tamamlanana kadar animasyon devam eder
            if (!identityValidated) {
                drawScanningAnimation(
                    cardWidth = cardWidth, 
                    cardHeight = cardHeight, 
                    scanOffset = scanOffset,
                    isProcessingIdentity = isProcessingIdentity,
                    isCardDetected = isCardDetected
                )
            }
        }
    }
}


fun DrawScope.drawScanningAnimation(
    cardWidth: Float, 
    cardHeight: Float, 
    scanOffset: Float,
    isProcessingIdentity: Boolean = false,
    isCardDetected: Boolean = false
) {
    val gradientHeight = cardHeight * 0.4f
    val animatedY = scanOffset * (cardHeight - gradientHeight)
    
    // Durum bazında animasyon rengi
    val animationColor = when {
        isProcessingIdentity -> Color.Cyan // Kimlik doğrulanıyor
        isCardDetected -> Color.Yellow // Kart tespit edildi
        else -> Color.Green // Varsayılan tarama
    }
    
    val gradient = Brush.verticalGradient(
        colors = listOf(Color.Transparent, animationColor.copy(alpha = 0.3f), Color.Transparent),
        startY = animatedY,
        endY = animatedY + gradientHeight
    )
    drawRect(brush = gradient, topLeft = Offset(0f, animatedY), size = Size(cardWidth, gradientHeight))
}



