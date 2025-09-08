package com.example.countercamtest

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

data class NFCReadResult(
    val success: Boolean,
    val firstName: String = "",
    val lastName: String = "",
    val gender: String = "",
    val issuingState: String = "",
    val nationality: String = "",
    val photo: android.graphics.Bitmap? = null,
    val errorMessage: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCReadingScreen(
    mrzResult: TCMRZReader.TCMRZResult,
    onNavigateBack: () -> Unit,
    onNFCReadComplete: (NFCReadResult) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var isReading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("NFC kartınızı telefonun arkasına yaklaştırın") }
    
    // NFC Adapter
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    
    // Convert MRZ data for NFC reading
    val passportNumber = remember(mrzResult) { 
        mrzResult.data?.documentNumber?.replace("<", "") ?: ""
    }
    val birthDate = remember(mrzResult) {
        convertDateToNFCFormat(mrzResult.data?.birthDate)
    }
    val expiryDate = remember(mrzResult) {
        convertDateToNFCFormat(mrzResult.data?.expiryDate)
    }

    // Handle NFC intent
    LaunchedEffect(Unit) {
        if (nfcAdapter == null) {
            statusText = "Bu cihaz NFC'yi desteklemiyor"
            return@LaunchedEffect
        }
        
        if (!nfcAdapter.isEnabled) {
            statusText = "Lütfen NFC'yi açın"
            return@LaunchedEffect
        }
    }

    // NFC tag reading function
    fun handleNFCTag(tag: Tag) {
        if (passportNumber.isEmpty() || birthDate.isNullOrEmpty() || expiryDate.isNullOrEmpty()) {
            statusText = "MRZ verilerinde eksik bilgi var"
            return
        }

        coroutineScope.launch {
            isReading = true
            statusText = "Kimlik okunuyor..."
            
            try {
                val result = withContext(Dispatchers.IO) {
                    readNFCData(tag, passportNumber, birthDate!!, expiryDate!!)
                }
                onNFCReadComplete(result)
            } catch (e: Exception) {
                Log.e("NFCReadingScreen", "NFC okuma hatası", e)
                val errorResult = NFCReadResult(
                    success = false,
                    errorMessage = "NFC okuma hatası: ${e.message}"
                )
                onNFCReadComplete(errorResult)
            } finally {
                isReading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "NFC Kimlik Okuma",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            
            // NFC Animation
            NFCAnimationView(isReading = isReading)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Status Text
            Text(
                text = statusText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (isReading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
                
                Text(
                    text = "Lütfen kimliğinizi hareket ettirmeyin",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // MRZ Info Card
            MRZInfoCard(mrzResult = mrzResult)
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Instructions
            InstructionCard()
        }
    }

    // NFC Intent handling would be done in the parent Activity/Fragment
    // This is just the UI component
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCReadingScreenWithCallback(
    mrzResult: TCMRZReader.TCMRZResult,
    detectedTag: Tag?,
    onNavigateBack: () -> Unit,
    onNFCReadComplete: (NFCReadResult) -> Unit,
    onTagProcessed: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    
    var isReading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("NFC kartınızı telefonun arkasına yaklaştırın") }
    
    // Convert MRZ data for NFC reading
    val passportNumber = remember(mrzResult) { 
        val docNum = mrzResult.data?.documentNumber?.replace("<", "") ?: ""
        Log.d("NFCReadingScreen", "Document Number: '$docNum'")
        docNum
    }
    val birthDate = remember(mrzResult) {
        val originalDate = mrzResult.data?.birthDate
        val convertedDate = convertDateToNFCFormat(originalDate)
        Log.d("NFCReadingScreen", "Birth Date: '$originalDate' → '$convertedDate'")
        convertedDate
    }
    val expiryDate = remember(mrzResult) {
        val originalDate = mrzResult.data?.expiryDate
        val convertedDate = convertDateToNFCFormat(originalDate)
        Log.d("NFCReadingScreen", "Expiry Date: '$originalDate' → '$convertedDate'")
        convertedDate
    }

    // Debug: Log all MRZ data
    LaunchedEffect(mrzResult) {
        Log.d("NFCReadingScreen", "=== MRZ Data Debug ===")
        Log.d("NFCReadingScreen", "Success: ${mrzResult.success}")
        Log.d("NFCReadingScreen", "Confidence: ${mrzResult.confidence}")
        mrzResult.data?.let { data ->
            Log.d("NFCReadingScreen", "Document Number: '${data.documentNumber}'")
            Log.d("NFCReadingScreen", "National ID: '${data.nationalId}'")
            Log.d("NFCReadingScreen", "Name: '${data.name}'")
            Log.d("NFCReadingScreen", "Surname: '${data.surname}'")
            Log.d("NFCReadingScreen", "Birth Date: '${data.birthDate}'")
            Log.d("NFCReadingScreen", "Expiry Date: '${data.expiryDate}'")
            Log.d("NFCReadingScreen", "Gender: '${data.gender}'")
            Log.d("NFCReadingScreen", "Nationality: '${data.nationality}'")
        } ?: Log.w("NFCReadingScreen", "MRZ data is null!")
        
        Log.d("NFCReadingScreen", "=== NFC Ready Check ===")
        Log.d("NFCReadingScreen", "Passport Number empty: ${passportNumber.isEmpty()}")
        Log.d("NFCReadingScreen", "Birth Date null: ${birthDate == null}")
        Log.d("NFCReadingScreen", "Expiry Date null: ${expiryDate == null}")
        Log.d("NFCReadingScreen", "======================")
    }

    // Handle detected NFC tag
    LaunchedEffect(detectedTag) {
        detectedTag?.let { tag ->
            if (!isReading && passportNumber.isNotEmpty() && 
                birthDate != null && expiryDate != null) {
                
                isReading = true
                statusText = "Kimlik okunuyor..."
                
                try {
                    val result = withContext(Dispatchers.IO) {
                        readNFCData(tag, passportNumber, birthDate, expiryDate)
                    }
                    onNFCReadComplete(result)
                } catch (e: Exception) {
                    Log.e("NFCReadingScreen", "NFC okuma hatası", e)
                    val errorResult = NFCReadResult(
                        success = false,
                        errorMessage = "NFC okuma hatası: ${e.message}"
                    )
                    onNFCReadComplete(errorResult)
                } finally {
                    isReading = false
                    onTagProcessed()
                }
            } else if (passportNumber.isEmpty() || birthDate.isNullOrEmpty() || expiryDate.isNullOrEmpty()) {
                statusText = "MRZ verilerinde eksik bilgi var"
                onTagProcessed()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "NFC Kimlik Okuma",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            
            // NFC Animation
            NFCAnimationView(isReading = isReading)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Status Text
            Text(
                text = statusText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (isReading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
                
                Text(
                    text = "Lütfen kimliğinizi hareket ettirmeyin",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // MRZ Info Card
            MRZInfoCard(mrzResult = mrzResult)
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Instructions
            InstructionCard()
        }
    }
}

@Composable
private fun NFCAnimationView(isReading: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "nfc_animation")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "nfc_scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "nfc_alpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(200.dp)
    ) {
        // Background circle
        Canvas(
            modifier = Modifier
                .size(180.dp)
                .alpha(if (isReading) alpha else 0.3f)
                .scale(if (isReading) scale else 1f)
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2
            
            drawCircle(
                color = if (isReading) Color(0xFF4CAF50) else Color(0xFF2196F3),
                radius = radius,
                center = center,
                style = Stroke(width = 8.dp.toPx())
            )
        }
        
        // NFC Icon
        Icon(
            imageVector = Icons.Default.Nfc,
            contentDescription = "NFC",
            modifier = Modifier.size(80.dp),
            tint = if (isReading) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
        )
        
        // Ripple effect when reading
        if (isReading) {
            val rippleAlpha by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Restart
                ),
                label = "ripple_alpha"
            )
            
            val rippleScale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Restart
                ),
                label = "ripple_scale"
            )
            
            Canvas(
                modifier = Modifier
                    .size(200.dp)
                    .alpha(rippleAlpha)
                    .scale(rippleScale)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.width / 4
                
                drawCircle(
                    color = Color(0xFF4CAF50),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun MRZInfoCard(mrzResult: TCMRZReader.TCMRZResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "📄 MRZ Bilgileri",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            mrzResult.data?.let { data ->
                InfoRow("Ad Soyad:", "${data.name} ${data.surname}")
                InfoRow("Belge No:", data.documentNumber)
                InfoRow("Doğum Tarihi:", data.birthDate)
                InfoRow("Son Kullanma:", data.expiryDate)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InstructionCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Nasıl Kullanılır?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Text(
                text = "• Kimlik kartınızı telefonun arkasına yaklaştırın\n" +
                        "• Okuma sırasında kartı hareket ettirmeyin\n" +
                        "• İşlem tamamlanana kadar bekleyin",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                lineHeight = 20.sp
            )
        }
    }
}

private fun convertDateToNFCFormat(dateStr: String?): String? {
    if (dateStr == null || dateStr.isEmpty()) {
        Log.e("NFCReadingScreen", "Date string is null or empty")
        return null
    }
    
    return try {
        // TC kimlik kartından gelen tarihler genellikle zaten YYMMDD formatında
        // Eğer 6 karakter ise direkt kullan
        if (dateStr.length == 6 && dateStr.all { it.isDigit() }) {
            Log.d("NFCReadingScreen", "Date already in YYMMDD format: $dateStr")
            return dateStr
        }
        
        // Eğer yyyy-MM-dd formatındaysa dönüştür
        if (dateStr.contains("-") && dateStr.length >= 8) {
            val fromFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val toFormat = SimpleDateFormat("yyMMdd", Locale.US)
            val converted = toFormat.format(fromFormat.parse(dateStr)!!)
            Log.d("NFCReadingScreen", "Converted date from $dateStr to $converted")
            return converted
        }
        
        // Eğer dd.MM.yyyy formatındaysa (TC formatı)
        if (dateStr.contains(".") && dateStr.length >= 8) {
            val fromFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)
            val toFormat = SimpleDateFormat("yyMMdd", Locale.US)
            val converted = toFormat.format(fromFormat.parse(dateStr)!!)
            Log.d("NFCReadingScreen", "Converted date from $dateStr to $converted")
            return converted
        }
        
        Log.w("NFCReadingScreen", "Unknown date format: $dateStr")
        null
    } catch (e: Exception) {
        Log.e("NFCReadingScreen", "Date conversion error for: $dateStr", e)
        null
    }
}

private suspend fun readNFCData(
    tag: Tag,
    passportNumber: String,
    birthDate: String,
    expiryDate: String
): NFCReadResult = withContext(Dispatchers.IO) {
    try {
        val isoDep = IsoDep.get(tag)
        isoDep.timeout = 10000
        
        val cardService = CardService.getInstance(isoDep)
        cardService.open()
        
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false
        )
        service.open()
        
        val bacKey: BACKeySpec = BACKey(passportNumber, birthDate, expiryDate)
        
        // Try PACE protocol first
        var paceSucceeded = false
        try {
            val cardAccessFile = service.getInputStream(PassportService.EF_CARD_ACCESS)?.let {
                CardAccessFile(it)
            }
            val paceInfo = cardAccessFile?.securityInfos?.filterIsInstance<PACEInfo>()?.firstOrNull()
            if (paceInfo != null) {
                service.doPACE(bacKey, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), null)
                paceSucceeded = true
            }
        } catch (e: Exception) {
            Log.w("NFCReadingScreen", "PACE failed, trying BAC", e)
        }
        
        service.sendSelectApplet(paceSucceeded)
        
        if (!paceSucceeded) {
            service.doBAC(bacKey)
        }
        
        // Read data groups
        val dg1Stream = service.getInputStream(PassportService.EF_DG1)
        val dg1File = DG1File(dg1Stream)
        
        val dg2Stream = service.getInputStream(PassportService.EF_DG2)
        val dg2File = DG2File(dg2Stream)
        
        // Extract photo
        var bitmap: android.graphics.Bitmap? = null
        val faceInfos = dg2File.faceInfos
        if (faceInfos.isNotEmpty() && faceInfos.first().faceImageInfos.isNotEmpty()) {
            val faceImageInfo = faceInfos.first().faceImageInfos.first()
            val imageStream: InputStream = faceImageInfo.imageInputStream
            bitmap = ImageUtil.decodeImage(null, faceImageInfo.mimeType, imageStream)
        }
        
        val mrzInfo = dg1File.mrzInfo
        
        NFCReadResult(
            success = true,
            firstName = mrzInfo.secondaryIdentifier.replace("<", " ").trim(),
            lastName = mrzInfo.primaryIdentifier.replace("<", " ").trim(),
            gender = mrzInfo.gender.toString(),
            issuingState = mrzInfo.issuingState,
            nationality = mrzInfo.nationality,
            photo = bitmap
        )
        
    } catch (e: Exception) {
        Log.e("NFCReadingScreen", "NFC reading failed", e)
        NFCReadResult(
            success = false,
            errorMessage = e.message ?: "Bilinmeyen hata"
        )
    }
}