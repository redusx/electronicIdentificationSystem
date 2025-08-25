package com.example.countercamtest

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScannerScreen() {
    var flashEnabled by remember { mutableStateOf(false) }

    // 👇 Tarama animasyonu offset (0f..1f arası sürekli ileri geri döngü)
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
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                RotatedCardContainer(scanOffset = scanOffset)
            }

            BottomControls(
                flashEnabled = flashEnabled,
                onFlashToggle = { flashEnabled = !flashEnabled },
                onCapture = { /* TODO: Capture action */ }
            )
        }
    }
}

@Composable
private fun RotatedCardContainer(
    scanOffset: Float,
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenWidth = configuration.screenWidthDp.dp

    Box(
        modifier = Modifier.width(screenHeight).height(screenWidth).graphicsLayer { rotationZ = 90f },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CardCanvas(
                scanOffset = scanOffset,
            )
            Spacer(modifier = Modifier.height(15.dp))
            Text(text = "TC Kimlik Kartı Arka Yüzünü Hizalayın", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Text(text = "Kartı çerçeve içinde tutun", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun CardCanvas(
    scanOffset: Float
) {
    Box(
        modifier = Modifier.fillMaxWidth().scale(1.2f).aspectRatio(1.586f)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cardWidth = size.width
            val cardHeight = size.height

            // --- card rectangle ---
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.2f),
                size = size,
                cornerRadius = CornerRadius(cardHeight * 0.05f))
            drawRoundRect(
                color = Color.White,
                size = size,
                cornerRadius = CornerRadius(cardHeight * 0.05f),
                style = Stroke(width = 1.dp.toPx()))
            drawChipArea(cardWidth, cardHeight)
            drawMRZArea(cardWidth, cardHeight)
            drawScanningAnimation(cardWidth, cardHeight, scanOffset)

        }
    }
}


@Composable
fun BottomControls(
    flashEnabled: Boolean,
    onFlashToggle: () -> Unit,
    onCapture: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp, top = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onFlashToggle,
            modifier = Modifier
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = "Flash",
                tint = if (flashEnabled) Color.Yellow else Color.White
            )
        }
        Button(
            onClick = onCapture,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Gray.copy(alpha = 0.3f), CircleShape)
                    .border(2.dp, Color.Gray, CircleShape)
            )
        }
        Spacer(modifier = Modifier.size(48.dp))
    }
}

fun DrawScope.drawChipArea(cardWidth: Float, cardHeight: Float) {
    // Çip, kart yüksekliğinin yaklaşık %25'i kadardır.
//    val chipHeight = cardHeight * 0.20f
//    // Genişliği de yüksekliğinin %80'i gibi bir oranda olsun.
//    val chipWidth = chipHeight * 1.0f
    val chipWidth = cardWidth * 0.15f
    val chipHeight = cardHeight * 0.23f

    // 2. Konumu hassaslaştıralım.
    // Yatayda, kartın sol kenarından biraz içeride (%7 boşluk bırakalım).
    val marginHorizontal = cardWidth * 0.10f
    // Dikeyde, tam olarak ortalamak için.
    val marginVertical = (cardHeight * 0.40f)*0.85f

    drawRoundRect(
        color = Color.White, // Sarı
        topLeft = Offset(marginHorizontal, marginVertical),
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(chipHeight * 0.1f),
        style = Stroke(width = 2.dp.toPx())
    )
}

fun DrawScope.drawMRZArea(cardWidth: Float, cardHeight: Float) {
    // 1. Yüksekliği ayarlayalım. %30 genellikle iyi bir değerdir.
    val mrzHeight = cardHeight * 0.40f

    // 2. Genişliği ve konumu ayarlayalım.
    // MRZ alanı, kartın en alt kenarında küçük bir boşluk bırakarak daha iyi görünür.
    val horizontalMargin = 4.dp.toPx() // Sağdan ve soldan çok az boşluk
    val bottomMargin = 4.dp.toPx()     // Alttan çok az boşluk

    val mrzWidth = cardWidth - (horizontalMargin * 2)
    val mrzTop = cardHeight - mrzHeight - bottomMargin
    val mrzLeft = horizontalMargin

    drawRect(
        color = Color.White, // Kırmızı
        topLeft = Offset(mrzLeft, mrzTop),
        size = Size(mrzWidth, mrzHeight),
        style = Stroke(width = 2.dp.toPx())
    )
}


fun DrawScope.drawScanningAnimation(cardWidth: Float, cardHeight: Float, scanOffset: Float) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            Color(0xFF4CAF50).copy(alpha = 0.7f),
            Color.Transparent
        )
    )
    val animatedY = scanOffset * cardHeight

    drawRect(
        brush = gradient,
        topLeft = Offset(0f, animatedY - cardHeight / 4),
        size = Size(cardWidth, cardHeight / 2),
    )
}