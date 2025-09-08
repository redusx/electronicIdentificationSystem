package com.example.countercamtest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MRZResultScreen(
    mrzResult: TCMRZReader.TCMRZResult,
    onNavigateBack: () -> Unit,
    onNavigateToNFC: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "TC Kimlik Kartı MRZ Sonuçları",
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
                    containerColor = if (mrzResult.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            if (mrzResult.success && onNavigateToNFC != null) {
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
                            onClick = onNavigateBack,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
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
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Button(
                            onClick = onNavigateToNFC,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2196F3)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nfc,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("NFC Oku")
                        }
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
            // Başarı Durumu Kartı
            ResultStatusCard(mrzResult = mrzResult)
            
            // Kişisel Bilgiler Kartı
            if (mrzResult.success && mrzResult.data != null) {
                PersonalInfoCard(personalData = mrzResult.data)
            }
            
            // Ham MRZ Verileri Kartı
            RawMRZDataCard(mrzResult = mrzResult)
        }
    }
}

@Composable
private fun ResultStatusCard(mrzResult: TCMRZReader.TCMRZResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (mrzResult.success) Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
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
                    imageVector = if (mrzResult.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (mrzResult.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = if (mrzResult.success) "MRZ Başarıyla Okundu" else "MRZ Okunamadı",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (mrzResult.success) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                    )
                    Text(
                        text = "Güven Oranı: ${String.format("%.1f", mrzResult.confidence)}%",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "İşlem Süresi: ${mrzResult.processingTime}ms",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }
            
            if (!mrzResult.success && mrzResult.errorMessage != null) {
                Text(
                    text = "Hata: ${mrzResult.errorMessage}",
                    color = Color(0xFFD32F2F),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun PersonalInfoCard(personalData: TCMRZReader.PersonalData) {
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
            
            Divider(color = Color.Gray.copy(alpha = 0.3f))
            
            PersonalInfoItem(
                icon = Icons.Default.Person,
                label = "Ad Soyad",
                value = buildString {
                    append(personalData.name)
                    if (personalData.secondName.isNotEmpty()) {
                        append(" ${personalData.secondName}")
                    }
                    append(" ${personalData.surname}")
                }.trim()
            )
            
            PersonalInfoItem(
                icon = Icons.Default.Badge,
                label = "TC Kimlik No",
                value = personalData.nationalId
            )
            
            PersonalInfoItem(
                icon = Icons.Default.CreditCard,
                label = "Belge No",
                value = personalData.documentNumber
            )
            
            PersonalInfoItem(
                icon = Icons.Default.Cake,
                label = "Doğum Tarihi",
                value = personalData.birthDate
            )
            
            PersonalInfoItem(
                icon = Icons.Default.Person,
                label = "Cinsiyet",
                value = when(personalData.gender) {
                    "M" -> "Erkek"
                    "F" -> "Kadın"
                    else -> personalData.gender
                }
            )
            
            PersonalInfoItem(
                icon = Icons.Default.DateRange,
                label = "Son Geçerlilik",
                value = personalData.expiryDate
            )
            
            PersonalInfoItem(
                icon = Icons.Default.Flag,
                label = "Uyrukluk",
                value = when(personalData.nationality) {
                    "TUR" -> "Türkiye Cumhuriyeti"
                    else -> personalData.nationality
                }
            )
        }
    }
}

@Composable
private fun PersonalInfoItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    if (value.isNotEmpty()) {
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
                    color = Color.Gray,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = value,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}


@Composable
private fun RawMRZDataCard(mrzResult: TCMRZReader.TCMRZResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF263238).copy(alpha = 0.05f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "📄 Ham MRZ Verileri",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider(color = Color.Gray.copy(alpha = 0.3f))
            
            if (mrzResult.rawMrz.isNotEmpty()) {
                mrzResult.rawMrz.forEachIndexed { index, line ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF37474F).copy(alpha = 0.1f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Satır ${index + 1}:",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = line.ifEmpty { "(Boş satır)" },
                            fontSize = 14.sp,
                            color = if (line.isNotEmpty()) Color.Black else Color.Gray
                        )
                        Text(
                            text = "${line.length} karakter",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                Text(
                    text = "MRZ verisi bulunamadı",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}