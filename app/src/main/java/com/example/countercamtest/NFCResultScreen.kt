package com.example.countercamtest

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCResultScreen(
    nfcResult: NFCReadResult,
    onNavigateBack: () -> Unit,
    onNavigateToCamera: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "NFC Okuma Sonucu",
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
                    containerColor = if (nfcResult.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = onNavigateToCamera,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Yeni Tarama")
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Result Status Card
            NFCStatusCard(nfcResult = nfcResult)
            
            if (nfcResult.success) {
                // Photo Card
                nfcResult.photo?.let { photo ->
                    PhotoCard(photo = photo)
                }
                
                
                // Personal Information Card
                PersonalInformationCard(nfcResult = nfcResult)
                
                // Document Information Card
                DocumentInformationCard(nfcResult = nfcResult)
                
                // Additional Personal Details Card (DG11)
                if (nfcResult.additionalPersonalDetails.isNotEmpty()) {
                    AdditionalPersonalDetailsCard(nfcResult = nfcResult)
                }
                
                // Additional Document Details Card (DG12)
                if (nfcResult.additionalDocumentDetails.isNotEmpty()) {
                    AdditionalDocumentDetailsCard(nfcResult = nfcResult)
                }
                
                // Digital Signature Card (DG15 + SOD)
                if (nfcResult.digitalSignature.isNotEmpty() || nfcResult.sodPresent) {
                    DigitalSignatureCard(nfcResult = nfcResult)
                }
            } else {
                // Error Information Card
                ErrorInformationCard(nfcResult = nfcResult)
            }
        }
    }
}

@Composable
private fun NFCStatusCard(nfcResult: NFCReadResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (nfcResult.success) Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row {
                Icon(
                    imageVector = if (nfcResult.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (nfcResult.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    text = if (nfcResult.success) "NFC Okuma Başarılı!" else "NFC Okuma Başarısız.Lütfen tekrar deneyin.",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (nfcResult.success) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 7.dp, top = 4.dp)
                )
            }
        }
    }
}


@Composable
private fun PhotoCard(photo: android.graphics.Bitmap) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Kimlik Fotoğrafı",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Kimlik Fotoğrafı",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
private fun PersonalInformationCard(nfcResult: NFCReadResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "👤 Kişisel Bilgiler",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            NFCInfoItem(
                icon = Icons.Default.Person,
                label = "Ad",
                value = nfcResult.firstName.ifEmpty { "Belirtilmemiş" }
            )
            
            NFCInfoItem(
                icon = Icons.Default.Person,
                label = "Soyad",
                value = nfcResult.lastName.ifEmpty { "Belirtilmemiş" }
            )
            
            NFCInfoItem(
                icon = Icons.Default.Person,
                label = "Cinsiyet",
                value = when(nfcResult.gender) {
                    "M" -> "Erkek"
                    "F" -> "Kadın"
                    else -> nfcResult.gender.ifEmpty { "Belirtilmemiş" }
                }
            )
        }
    }
}

@Composable
private fun DocumentInformationCard(nfcResult: NFCReadResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "📄 Belge Bilgileri",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            NFCInfoItem(
                icon = Icons.Default.Flag,
                label = "Veren Ülke",
                value = when(nfcResult.issuingState) {
                    "TUR" -> "Türkiye Cumhuriyeti"
                    else -> nfcResult.issuingState.ifEmpty { "Belirtilmemiş" }
                }
            )
            
            NFCInfoItem(
                icon = Icons.Default.Public,
                label = "Uyrukluk",
                value = when(nfcResult.nationality) {
                    "TUR" -> "Türkiye Cumhuriyeti"
                    else -> nfcResult.nationality.ifEmpty { "Belirtilmemiş" }
                }
            )
        }
    }
}

@Composable
private fun ErrorInformationCard(nfcResult: NFCReadResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFEBEE)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "❌ Hata Detayları",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
            }
            
            Divider(color = Color(0xFFD32F2F).copy(alpha = 0.3f))
            
            Text(
                text = nfcResult.errorMessage ?: "Bilinmeyen hata oluştu",
                fontSize = 14.sp,
                color = Color(0xFFD32F2F),
                lineHeight = 20.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF5F5F5)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "💡 Çözüm Önerileri:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        text = "• Kimlik kartının NFC özelliği açık olduğundan emin olun\n" +
                                "• Kartı telefonun arkasına daha yakın tutun\n" +
                                "• Okuma sırasında kartı hareket ettirmeyin\n" +
                                "• Telefonunuzun NFC özelliğinin açık olduğunu kontrol edin\n" +
                                "• MRZ bilgilerinin doğru okunduğundan emin olun",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun NFCInfoItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AdditionalPersonalDetailsCard(nfcResult: NFCReadResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "👤➕ Ek Kişisel Detaylar (DG11)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            if (nfcResult.fullName.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Person,
                    label = "Tam Ad",
                    value = nfcResult.fullName
                )
            }
            
            if (nfcResult.placeOfBirth.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.LocationOn,
                    label = "Doğum Yeri",
                    value = nfcResult.placeOfBirth
                )
            }
            
            if (nfcResult.address.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Home,
                    label = "Adres",
                    value = nfcResult.address
                )
            }
            
            if (nfcResult.profession.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Work,
                    label = "Meslek",
                    value = nfcResult.profession
                )
            }
            
            if (nfcResult.title.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Star,
                    label = "Unvan",
                    value = nfcResult.title
                )
            }
            
            if (nfcResult.phoneNumber.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Phone,
                    label = "Telefon",
                    value = nfcResult.phoneNumber
                )
            }
            
            // Doğum ve geçerlilik tarihleri
            if (nfcResult.dateOfBirth.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Cake,
                    label = "Doğum Tarihi",
                    value = formatBirthDateForDisplay(nfcResult.dateOfBirth)
                )
            }
            
            if (nfcResult.dateOfExpiry.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Event,
                    label = "Son Geçerlilik",
                    value = formatExpiryDateForDisplay(nfcResult.dateOfExpiry)
                )
            }
            
            // Show additional details if any
            val additionalDetails = nfcResult.additionalPersonalDetails
                .filterNot { (key, _) -> 
                    key in listOf("nameOfHolder", "placeOfBirth_0", "address_0", "profession", "title", "telephone")
                }
            
            if (additionalDetails.isNotEmpty()) {
                Text(
                    text = "Diğer Bilgiler:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                additionalDetails.forEach { (key, value) ->
                    if (value.isNotEmpty()) {
                        NFCInfoItem(
                            icon = Icons.Default.Info,
                            label = key.replace("_", " ").replaceFirstChar { it.uppercase() },
                            value = value
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdditionalDocumentDetailsCard(nfcResult: NFCReadResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "📄➕ Ek Belge Detayları (DG12)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            if (nfcResult.issuingAuthority.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.AccountBalance,
                    label = "Veren Makam",
                    value = nfcResult.issuingAuthority
                )
            }
            
            if (nfcResult.dateOfIssue.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.CalendarToday,
                    label = "Veriliş Tarihi",
                    value = nfcResult.dateOfIssue
                )
            }
            
            if (nfcResult.endorsements.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Note,
                    label = "Notlar ve Gözlemler",
                    value = nfcResult.endorsements
                )
            }
            
            if (nfcResult.taxOrExitRequirements.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Security,
                    label = "Vergi/Çıkış Gereksinimleri",
                    value = nfcResult.taxOrExitRequirements
                )
            }
            
            // Show additional details if any
            val additionalDetails = nfcResult.additionalDocumentDetails
                .filterNot { (key, _) -> 
                    key in listOf("issuingAuthority", "dateOfIssue", "endorsements", "taxExitRequirements")
                }
            
            if (additionalDetails.isNotEmpty()) {
                Text(
                    text = "Diğer Belge Bilgileri:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                additionalDetails.forEach { (key, value) ->
                    if (value.isNotEmpty()) {
                        NFCInfoItem(
                            icon = Icons.Default.Description,
                            label = key.replace("_", " ").replaceFirstChar { it.uppercase() },
                            value = value
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DigitalSignatureCard(nfcResult: NFCReadResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = when(nfcResult.signatureVerification) {
                        "SUCCESS" -> Icons.Default.VerifiedUser
                        "FAILED" -> Icons.Default.Error
                        else -> Icons.Default.Security
                    },
                    contentDescription = null,
                    tint = when(nfcResult.signatureVerification) {
                        "SUCCESS" -> Color(0xFF4CAF50)
                        "FAILED" -> Color(0xFFF44336)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp)
                )
                
                Text(
                    text = "🔐 Dijital İmza ve Güvenlik",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            // Signature Status
            NFCInfoItem(
                icon = when(nfcResult.signatureVerification) {
                    "SUCCESS" -> Icons.Default.CheckCircle
                    "FAILED" -> Icons.Default.Cancel
                    else -> Icons.Default.Help
                },
                label = "Dijital İmza Durumu",
                value = when(nfcResult.signatureVerification) {
                    "SUCCESS" -> "✅ Geçerli ve Doğrulandı"
                    "FAILED" -> "❌ Geçersiz veya Hatalı"
                    else -> "❓ Doğrulanmadı"
                }
            )
            
            // Basic signature info
            if (nfcResult.digitalSignature.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Security,
                    label = "E-İmza Mevcut",
                    value = nfcResult.digitalSignature
                )
            }
            
            if (nfcResult.signatureAlgorithm.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.VpnKey,
                    label = "Algoritma",
                    value = nfcResult.signatureAlgorithm
                )
            }
            
            // Public Key Details
            if (nfcResult.publicKeySize.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Lock,
                    label = "Anahtar Boyutu",
                    value = nfcResult.publicKeySize
                )
            }
            
            if (nfcResult.publicKeyFormat.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Code,
                    label = "Anahtar Formatı",
                    value = nfcResult.publicKeyFormat
                )
            }
            
            // SOD Information
            if (nfcResult.sodPresent) {
                NFCInfoItem(
                    icon = Icons.Default.Verified,
                    label = "Güvenlik Belgesi (SOD)",
                    value = if (nfcResult.sodValid) "✅ Mevcut ve Geçerli" else "⚠️ Mevcut ama Geçersiz"
                )
                
                if (nfcResult.sodSignatureAlgorithm.isNotEmpty()) {
                    NFCInfoItem(
                        icon = Icons.Default.Code,
                        label = "SOD İmza Algoritması",
                        value = nfcResult.sodSignatureAlgorithm
                    )
                }
            }
            
            // Certificate Info
            if (nfcResult.certificateIssuer.isNotEmpty()) {
                NFCInfoItem(
                    icon = Icons.Default.Badge,
                    label = "Sertifika Türü",
                    value = nfcResult.certificateIssuer
                )
            }
            
            // Verification Details
            if (nfcResult.verificationDetails.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "📄 Doğrulama Detayları:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = nfcResult.verificationDetails,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            

            // Advanced Details (Expandable)
            var showAdvancedDetails by remember { mutableStateOf(false) }
            
            TextButton(
                onClick = { showAdvancedDetails = !showAdvancedDetails },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (showAdvancedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (showAdvancedDetails) "Teknik Detayları Gizle" else "Teknik Detayları Göster",
                    fontSize = 14.sp
                )
            }
            
            if (showAdvancedDetails) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "⚙️ Teknik Bilgiler",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        if (nfcResult.publicKeyEncoded.isNotEmpty()) {
                            Text(
                                text = "Public Key (Hex): ${nfcResult.publicKeyEncoded}",
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        if (nfcResult.dataGroupHashes.isNotEmpty()) {
                            Text(
                                text = "Data Group Hashes:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            nfcResult.dataGroupHashes.forEach { (dg, hash) ->
                                Text(
                                    text = "$dg: ${hash.take(32)}...",
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Text(
                            text = "Son Güncelleme: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("tr")).format(java.util.Date(nfcResult.lastVerificationTime))}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
        }
    }
}

// Helper function to format birth dates for display
private fun formatBirthDateForDisplay(dateString: String): String {
    return try {
        // If it's in YYMMDD format, convert to readable format
        if (dateString.length == 6 && dateString.all { it.isDigit() }) {
            val yy = dateString.substring(0, 2).toInt()
            val month = dateString.substring(2, 4)
            val day = dateString.substring(4, 6)
            
            // Birth date logic: Current year 2025
            // If YY > 25, it's 19YY (1926-1999)
            // If YY <= 25, it's 20YY (2000-2025)
            val fullYear = if (yy > 25) {
                1900 + yy  // 1926-1999
            } else {
                2000 + yy  // 2000-2025
            }
            
            "$day/$month/$fullYear"
        } else if (dateString.length == 8 && dateString.all { it.isDigit() }) {
            // YYYYMMDD format
            val year = dateString.substring(0, 4)
            val month = dateString.substring(4, 6)
            val day = dateString.substring(6, 8)
            "$day/$month/$year"
        } else {
            // Already in readable format or unknown format
            dateString
        }
    } catch (e: Exception) {
        dateString // Return original if formatting fails
    }
}

// Helper function to format expiry dates for display
private fun formatExpiryDateForDisplay(dateString: String): String {
    return try {
        // If it's in YYMMDD format, convert to readable format
        if (dateString.length == 6 && dateString.all { it.isDigit() }) {
            val yy = dateString.substring(0, 2).toInt()
            val month = dateString.substring(2, 4)
            val day = dateString.substring(4, 6)
            
            // Expiry date logic: Always in the future (2025+)
            // All YY values should be interpreted as 20YY for expiry dates
            // Exception: if YY is very small (00-99), still use 20YY
            val fullYear = 2000 + yy  // 2000-2099
            
            "$day/$month/$fullYear"
        } else if (dateString.length == 8 && dateString.all { it.isDigit() }) {
            // YYYYMMDD format
            val year = dateString.substring(0, 4)
            val month = dateString.substring(4, 6)
            val day = dateString.substring(6, 8)
            "$day/$month/$year"
        } else {
            // Already in readable format or unknown format
            dateString
        }
    } catch (e: Exception) {
        dateString // Return original if formatting fails
    }
}

