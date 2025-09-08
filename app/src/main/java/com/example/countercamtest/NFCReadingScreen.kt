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
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.SODFile
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
    val errorMessage: String? = null,
    
    // Ek Belge Bilgileri (DG1 genişletilmiş)
    val documentType: String = "",
    val documentNumber: String = "",
    val personalNumber: String = "",
    val dateOfBirth: String = "",
    val dateOfExpiry: String = "",
    
    // Ek Kişisel Detaylar (DG11)
    val fullName: String = "",
    val placeOfBirth: String = "",
    val address: String = "",
    val profession: String = "",
    val title: String = "",
    val phoneNumber: String = "",
    val additionalPersonalDetails: Map<String, String> = emptyMap(),
    
    // Ek Belge Detayları (DG12) 
    val issuingAuthority: String = "",
    val dateOfIssue: String = "",
    val endorsements: String = "",
    val taxOrExitRequirements: String = "",
    val additionalDocumentDetails: Map<String, String> = emptyMap(),
    
    // E-imza ve Güvenlik Bilgileri (Genişletilmiş)
    val digitalSignature: String = "",
    val signatureAlgorithm: String = "",
    val certificateIssuer: String = "",
    val signatureValid: Boolean = false,
    
    // Detaylı E-İmza Bilgileri (DG15)
    val publicKeySize: String = "",
    val publicKeyFormat: String = "",
    val publicKeyEncoded: String = "",
    val signatureHashAlgorithm: String = "",
    val certificateSerialNumber: String = "",
    val certificateValidFrom: String = "",
    val certificateValidTo: String = "",
    val certificateFingerprint: String = "",
    
    // SOD (Security Object Document) Bilgileri
    val sodPresent: Boolean = false,
    val sodValid: Boolean = false,
    val sodSignatureAlgorithm: String = "",
    val sodIssuer: String = "",
    val dataGroupHashes: Map<String, String> = emptyMap(),
    
    // İmza Doğrulama Durumu
    val signatureVerification: String = "", // "SUCCESS", "FAILED", "NOT_VERIFIED"
    val verificationDetails: String = "",
    val lastVerificationTime: Long = 0L
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
    var statusText by remember { mutableStateOf("NFC özelliğinin açık olduğundan emin olun.Kimlik kartınızı telefonun NFC alanına temas ettirin") }
    
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
            
            Spacer(modifier = Modifier.weight(1f))
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
    var statusText by remember { mutableStateOf("Kimlik kartınızı telefonunuzun NFC alanına temas ettirin") }
    
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
            
            Spacer(modifier = Modifier.weight(1f))
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
        modifier = Modifier
            .size(280.dp)
            .padding(40.dp)
    ) {
        // Background circle
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (isReading) alpha else 0.3f)
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.width.coerceAtMost(size.height) / 2) - 20.dp.toPx()
            val scaledRadius = if (isReading) baseRadius * scale else baseRadius
            
            drawCircle(
                color = if (isReading) Color(0xFF4CAF50) else Color(0xFF2196F3),
                radius = scaledRadius.coerceAtMost(baseRadius * 1.2f), // Limit max scale
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
                    .fillMaxSize()
                    .alpha(rippleAlpha)
                    .scale(rippleScale)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = (size.width.coerceAtMost(size.height) / 4)
                
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
    val startTime = System.currentTimeMillis()
    
    try {
        val isoDep = IsoDep.get(tag)
        isoDep.timeout = 15000 // Increased timeout for multiple data groups
        
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
                Log.d("NFCReadingScreen", "PACE authentication succeeded")
            }
        } catch (e: Exception) {
            Log.w("NFCReadingScreen", "PACE failed, trying BAC", e)
        }
        
        service.sendSelectApplet(paceSucceeded)
        
        if (!paceSucceeded) {
            service.doBAC(bacKey)
            Log.d("NFCReadingScreen", "BAC authentication succeeded")
        }
        
        // Detect available data groups
        val availableDataGroups = mutableListOf<String>()
        
        // Try to read SOD to get available data groups
        try {
            val sodStream = service.getInputStream(PassportService.EF_SOD)
            val sodFile = SODFile(sodStream)
            val dataGroupHashes = sodFile.dataGroupHashes
            dataGroupHashes.keys.forEach { dgNumber ->
                availableDataGroups.add("DG$dgNumber")
            }
            Log.d("NFCReadingScreen", "Available data groups from SOD: $availableDataGroups")
        } catch (e: Exception) {
            Log.w("NFCReadingScreen", "Could not read SOD file", e)
        }
        
        // Read mandatory data groups
        val dg1Stream = service.getInputStream(PassportService.EF_DG1)
        val dg1File = DG1File(dg1Stream)
        val mrzInfo = dg1File.mrzInfo
        
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
        
        // Try to read additional data groups
        var dg11Data: Map<String, String> = emptyMap()
        var dg12Data: Map<String, String> = emptyMap()
        var signatureInfo: Map<String, String> = emptyMap()
        
        // Read DG11 (Additional Personal Details)
        try {
            val dg11Stream = service.getInputStream(PassportService.EF_DG11)
            val dg11File = DG11File(dg11Stream)
            
            val dg11DataBuilder = mutableMapOf<String, String>()
            
            dg11File.nameOfHolder?.let { dg11DataBuilder["nameOfHolder"] = it }
            dg11File.otherNames?.forEach { name -> 
                dg11DataBuilder["otherName_${dg11DataBuilder.size}"] = name 
            }
            dg11File.personalNumber?.let { dg11DataBuilder["personalNumber"] = it }
            dg11File.placeOfBirth?.forEach { place -> 
                dg11DataBuilder["placeOfBirth_${dg11DataBuilder.size}"] = place 
            }
            dg11File.permanentAddress?.forEach { address -> 
                dg11DataBuilder["address_${dg11DataBuilder.size}"] = address 
            }
            dg11File.telephone?.let { dg11DataBuilder["telephone"] = it }
            dg11File.profession?.let { dg11DataBuilder["profession"] = it }
            dg11File.title?.let { dg11DataBuilder["title"] = it }
            
            dg11Data = dg11DataBuilder.toMap()
            availableDataGroups.add("DG11")
            Log.d("NFCReadingScreen", "DG11 read successfully: ${dg11Data.keys}")
            
        } catch (e: Exception) {
            Log.w("NFCReadingScreen", "Could not read DG11", e)
        }
        
        // Read DG12 (Additional Document Details)
        try {
            val dg12Stream = service.getInputStream(PassportService.EF_DG12)
            val dg12File = DG12File(dg12Stream)
            
            val dg12DataBuilder = mutableMapOf<String, String>()
            
            dg12File.issuingAuthority?.let { dg12DataBuilder["issuingAuthority"] = it }
            dg12File.dateOfIssue?.let { dg12DataBuilder["dateOfIssue"] = it }
            dg12File.endorsementsAndObservations?.let { dg12DataBuilder["endorsements"] = it }
            dg12File.taxOrExitRequirements?.let { dg12DataBuilder["taxExitRequirements"] = it }
            
            dg12Data = dg12DataBuilder.toMap()
            availableDataGroups.add("DG12")
            Log.d("NFCReadingScreen", "DG12 read successfully: ${dg12Data.keys}")
            
        } catch (e: Exception) {
            Log.w("NFCReadingScreen", "Could not read DG12", e)
        }
        
        // Read DG15 (Public Key for Digital Signature)
        try {
            val dg15Stream = service.getInputStream(PassportService.EF_DG15)
            val dg15File = DG15File(dg15Stream)
            
            val signatureInfoBuilder = mutableMapOf<String, String>()
            
            // DG15 contains public key information
            dg15File.publicKey?.let { publicKey ->
                signatureInfoBuilder["publicKeyAlgorithm"] = publicKey.algorithm
                signatureInfoBuilder["publicKeyFormat"] = publicKey.format
                signatureInfoBuilder["hasDigitalSignature"] = "Evet"
            }
            
            signatureInfo = signatureInfoBuilder.toMap()
            availableDataGroups.add("DG15")
            Log.d("NFCReadingScreen", "DG15 read successfully: Digital signature support available")
            
        } catch (e: Exception) {
            Log.w("NFCReadingScreen", "Could not read DG15", e)
        }
        
        val endTime = System.currentTimeMillis()
        val readingTime = endTime - startTime
        
        NFCReadResult(
            success = true,
            firstName = mrzInfo.secondaryIdentifier.replace("<", " ").trim(),
            lastName = mrzInfo.primaryIdentifier.replace("<", " ").trim(),
            gender = mrzInfo.gender.toString(),
            issuingState = mrzInfo.issuingState,
            nationality = mrzInfo.nationality,
            photo = bitmap,
            
            // Extended DG1 data
            documentType = mrzInfo.documentType?.toString() ?: "",
            documentNumber = mrzInfo.documentNumber,
            personalNumber = mrzInfo.personalNumber ?: "",
            dateOfBirth = mrzInfo.dateOfBirth,
            dateOfExpiry = mrzInfo.dateOfExpiry,
            
            // DG11 data
            fullName = dg11Data["nameOfHolder"] ?: "",
            placeOfBirth = dg11Data["placeOfBirth_0"] ?: "",
            address = dg11Data["address_0"] ?: "",
            profession = dg11Data["profession"] ?: "",
            title = dg11Data["title"] ?: "",
            phoneNumber = dg11Data["telephone"] ?: "",
            additionalPersonalDetails = dg11Data,
            
            // DG12 data
            issuingAuthority = dg12Data["issuingAuthority"] ?: "",
            dateOfIssue = dg12Data["dateOfIssue"] ?: "",
            endorsements = dg12Data["endorsements"] ?: "",
            taxOrExitRequirements = dg12Data["taxExitRequirements"] ?: "",
            additionalDocumentDetails = dg12Data,
            
            // E-imza bilgileri (Temel)
            digitalSignature = signatureInfo["hasDigitalSignature"] ?: "Hayır",
            signatureAlgorithm = signatureInfo["publicKeyAlgorithm"] ?: "",
            certificateIssuer = if (paceSucceeded) "PACE Sertifikası" else "BAC Anahtarı",
            signatureValid = signatureInfo.isNotEmpty(),
            
            // E-imza bilgileri (Detaylı)
            publicKeySize = signatureInfo["publicKeySize"] ?: "",
            publicKeyFormat = signatureInfo["publicKeyFormat"] ?: "",
            publicKeyEncoded = signatureInfo["publicKeyEncoded"] ?: "",
            signatureHashAlgorithm = signatureInfo["hashAlgorithm"] ?: "",
            certificateSerialNumber = signatureInfo["certificateSerial"] ?: "",
            certificateValidFrom = signatureInfo["validFrom"] ?: "",
            certificateValidTo = signatureInfo["validTo"] ?: "",
            certificateFingerprint = signatureInfo["fingerprint"] ?: "",
            
            // SOD bilgileri
            sodPresent = signatureInfo["sodPresent"] == "true",
            sodValid = signatureInfo["sodValid"] == "true",
            sodSignatureAlgorithm = signatureInfo["sodAlgorithm"] ?: "",
            sodIssuer = signatureInfo["sodIssuer"] ?: "",
            dataGroupHashes = signatureInfo.filterKeys { it.startsWith("hash_") },
            
            // İmza doğrulama
            signatureVerification = signatureInfo["verification"] ?: "NOT_VERIFIED",
            verificationDetails = signatureInfo["verificationDetails"] ?: "",
            lastVerificationTime = System.currentTimeMillis()
        )
        
    } catch (e: Exception) {
        Log.e("NFCReadingScreen", "NFC reading failed", e)
        val endTime = System.currentTimeMillis()
        NFCReadResult(
            success = false,
            errorMessage = e.message ?: "Bilinmeyen hata"
        )
    }
}