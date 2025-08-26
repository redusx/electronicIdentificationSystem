package com.example.countercamtest

import androidx.camera.core.Camera
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.RoundRect as ComposeRoundRect

@Composable
fun ScannerScreen(
    camera: Camera?,
    segments: FloatArray? = null,
    srcWidth: Int = 0,
    srcHeight: Int = 0) {
    var flashEnabled by remember { mutableStateOf(false) }

    val scanAnimation = rememberInfiniteTransition(label = "scan_animation")
    val scanOffset by scanAnimation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_offset_anim"
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
                segments = segments,
                srcWidth = srcWidth,
                srcHeight = srcHeight
            )

            Spacer(modifier = Modifier.weight(0.5f))

            BottomControls(
                flashEnabled = flashEnabled,
                onFlashToggle = {
                    flashEnabled = !flashEnabled
                    camera?.cameraControl?.enableTorch(flashEnabled)
                }
            )
        }
    }
}

@Composable
private fun CardOverlayContainer(
    scanOffset: Float,
    segments: FloatArray? = null,
    srcWidth: Int = 0,
    srcHeight: Int = 0
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CardCanvas(
            scanOffset = scanOffset,
            segments = segments,
            srcWidth = srcWidth,
            srcHeight = srcHeight
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
    segments: FloatArray? = null,
    srcWidth: Int = 0,
    srcHeight: Int = 0
) {
    Box(
        // ######################################################################
        //
        // <--- KARTIN BÜYÜKLÜĞÜNÜ (GENİŞLİĞİNİ VE YÜKSEKLİĞİNİ) BU SATIRLARDAN AYARLARSINIZ
        //
        // <--- 1. GENİŞLİK AYARI: "fillMaxWidth(0.85f)" değerini değiştirin.
        //      Örneğin, "fillMaxWidth(0.95f)" kartı daha geniş yapar.
        //
        // <--- 2. YÜKSEKLİK AYARI: Yükseklik, "aspectRatio" ile genişliğe göre
        //      otomatik ayarlanır. Bu satırı genellikle değiştirmeniz gerekmez.
        //
        // ######################################################################
        modifier = Modifier
            .fillMaxWidth(0.90f)
            .aspectRatio(1f / 1.586f)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cardWidth = size.width
            val cardHeight = size.height
            val corner = CornerRadius(cardWidth * 0.05f)

            drawRoundRect(
                color = Color.Black.copy(alpha = 0.2f),
                size = size,
                cornerRadius = corner)
            drawRoundRect(
                color = Color.White,
                size = size,
                cornerRadius = corner,
                style = Stroke(width = 1.dp.toPx())
            )

            //drawChipAreaVertical(cardWidth, cardHeight)
            //drawBarcodeArea(cardWidth, cardHeight)
            drawScanningAnimation(cardWidth, cardHeight, scanOffset)

            val roundedPath = Path().apply {
                addRoundRect(
                    ComposeRoundRect(
                        left = 0f,
                        top = 0f,
                        right = cardWidth,
                        bottom = cardHeight,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(x = corner.x, y = corner.y)
                    )
                )
            }

            clipPath(roundedPath) {
                if (segments != null && segments.isNotEmpty() && srcWidth > 0 && srcHeight > 0) {
                    val scale = max(cardWidth / srcWidth.toFloat(), cardHeight / srcHeight.toFloat())
                    val dx = (cardWidth - srcWidth * scale) / 2f
                    val dy = (cardHeight - srcHeight * scale) / 2f

                    fun map(x: Float, y: Float) = Offset(x * scale + dx, y * scale + dy)

                    var i = 0
                    val stroke = 2.dp.toPx()
                    while (i + 3 < segments.size) {
                        val p1 = map(segments[i],     segments[i + 1])
                        val p2 = map(segments[i + 2], segments[i + 3])
                        drawLine(Color.Red, p1, p2, strokeWidth = stroke)
                        i += 4
                    }
                }
            }
        }
    }
}

@Composable
fun BottomControls(
    flashEnabled: Boolean,
    onFlashToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp, top = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onFlashToggle,
            modifier = Modifier
                .size(64.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = "Flash",
                tint = if (flashEnabled) Color.Yellow else Color.White
            )
        }
    }
}

//fun DrawScope.drawChipAreaVertical(cardWidth: Float, cardHeight: Float) {
//    val chipWidth = cardWidth * 0.20f
//    val chipHeight = cardHeight * 0.15f
//
//    val marginHorizontal = ((cardWidth*0.90f) - chipWidth) / 2f
//    val marginVertical = cardHeight * 0.10f
//
//    drawRoundRect(
//        color = Color.White,
//        topLeft = Offset(marginHorizontal, marginVertical),
//        size = Size(chipWidth, chipHeight),
//        cornerRadius = CornerRadius(chipHeight * 0.1f),
//        style = Stroke(width = 1.dp.toPx())
//    )
//}

//fun DrawScope.drawBarcodeArea(cardWidth: Float, cardHeight: Float) {
//    val areaHeight = cardHeight * 0.25f
//    val horizontalMargin = cardWidth * 0.1f
//    val bottomMargin = cardHeight * 0.05f
//
//    val areaWidth = cardWidth - (horizontalMargin * 2)
//    val areaTop = cardHeight - areaHeight - bottomMargin
//    val areaLeft = horizontalMargin
//
//    drawRect(
//        color = Color.White,
//        topLeft = Offset(areaLeft, areaTop),
//        size = Size(areaWidth, areaHeight),
//        style = Stroke(width = 2.dp.toPx())
//    )
//}

fun DrawScope.drawScanningAnimation(cardWidth: Float, cardHeight: Float, scanOffset: Float) {
    val gradientHeight = cardHeight * 0.4f
    val animatedY = scanOffset * (cardHeight - gradientHeight)

    val gradient = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            Color(0xFF00FF00).copy(alpha = 0.2f),
            Color.Transparent
        ),
        startY = animatedY,
        endY = animatedY + gradientHeight
    )

    drawRect(
        brush = gradient,
        topLeft = Offset(0f, animatedY),
        size = Size(cardWidth, gradientHeight)
    )
}