package com.example.countercamtest

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

@Composable
fun ScannerScreen(
    camera: Camera?,
    segments: FloatArray? = null,
    srcWidth: Int = 0,
    srcHeight: Int = 0,
    isCardDetected: Boolean = false
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
                isCardDetected = isCardDetected
            )

            Spacer(modifier = Modifier.weight(0.5f))

            // Flash controls disabled
            // BottomControls(
            //     flashEnabled = flashEnabled,
            //     onFlashToggle = {
            //         flashEnabled = !flashEnabled
            //         camera?.cameraControl?.enableTorch(flashEnabled)
            //     }
            // )
        }
    }
}

@Composable
private fun CardOverlayContainer(
    scanOffset: Float,
    isCardDetected: Boolean = false
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
            modifier = Modifier.onGloballyPositioned { coords ->
                cardOffset = coords.positionInRoot()
            }
        )
        Spacer(modifier = Modifier.height(15.dp))
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

@Composable
fun CardCanvas(
    scanOffset: Float,
    isCardDetected: Boolean = false,
    modifier: Modifier = Modifier
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

            // Kart tespit edildiğinde yeşil arka plan, yoksa siyah
            val backgroundColor = if (isCardDetected) Color.Green.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f)
            val borderColor = if (isCardDetected) Color.Green else Color.White

            drawRoundRect(color = backgroundColor, size = size, cornerRadius = corner)
            drawRoundRect(color = borderColor, size = size, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
            
            // Kart tespit edildiğinde animasyonu durdur
            if (!isCardDetected) {
                drawScanningAnimation(cardWidth, cardHeight, scanOffset)
            }
        }
    }
}


fun DrawScope.drawScanningAnimation(cardWidth: Float, cardHeight: Float, scanOffset: Float) {
    val gradientHeight = cardHeight * 0.4f
    val animatedY = scanOffset * (cardHeight - gradientHeight)
    val gradient = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color(0xFF00FF00).copy(alpha = 0.2f), Color.Transparent),
        startY = animatedY,
        endY = animatedY + gradientHeight
    )
    drawRect(brush = gradient, topLeft = Offset(0f, animatedY), size = Size(cardWidth, gradientHeight))
}