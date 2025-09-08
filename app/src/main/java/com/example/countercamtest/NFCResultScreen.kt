package com.example.countercamtest

import androidx.compose.foundation.Image
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
            Icon(
                imageVector = if (nfcResult.success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (nfcResult.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                modifier = Modifier.size(48.dp)
            )
            
            Text(
                text = if (nfcResult.success) "✅ NFC Okuma Başarılı!" else "❌ NFC Okuma Başarısız",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (nfcResult.success) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                textAlign = TextAlign.Center
            )
            
            Text(
                text = if (nfcResult.success) "Kimlik kartınızdaki tüm bilgiler başarıyla okundu." 
                       else "Kimlik kartı okunamadı. Lütfen tekrar deneyin.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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
                text = "📷 Kimlik Fotoğrafı",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Kimlik Fotoğrafı",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            
            Text(
                text = "Kimlik kartından okunan orijinal fotoğraf",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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