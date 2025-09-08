# NFC Kimlik Kartı Okuma Entegrasyonu

Bu proje, TC Kimlik kartlarının MRZ (Machine Readable Zone) okuma özelliğine NFC (Near Field Communication) ile çip okuma işlevselliğini entegre eder.

## 🚀 Özellikler

### 1. MRZ Okuma (Mevcut)
- Kamera ile kimlik kartının MRZ alanını tarar
- OCR ile kişisel bilgileri çıkarır
- Türkiye Cumhuriyeti kimlik kartı formatını destekler

### 2. NFC Çip Okuma (Yeni Eklenen)
- JMRTD kütüphanesi kullanılarak NFC ile kimlik kartı çipini okur
- MRZ verilerini BAC/PACE protokolleri için kullanır
- Kimlik kartındaki fotoğrafı ve ek bilgileri çıkarır

## 📱 Uygulama Akışı

```
1. Kamera Tarama Ekranı
   ↓
2. MRZ Sonuç Ekranı → [NFC Oku Butonu]
   ↓
3. NFC Okuma Ekranı
   ↓
4. NFC Sonuç Ekranı
```

## 🏗️ Teknik Yapı

### Yeni Dosyalar
- **NFCActivity.kt**: NFC işlemleri için ana Activity
- **NFCReadingScreen.kt**: NFC okuma UI (Jetpack Compose)
- **NFCResultScreen.kt**: NFC sonuçlarını gösteren ekran
- **nfc_tech_filter.xml**: NFC teknoloji filtreleri

### Güncellenen Dosyalar
- **MainActivity.kt**: NFC Activity'ye navigation eklendi
- **MRZResultScreen.kt**: "NFC Oku" butonu eklendi
- **TCMRZReader.kt**: Serializable desteği eklendi
- **AndroidManifest.xml**: NFC permissions ve Activity tanımları

## 🔧 Kullanılan Kütüphaneler

### NFC İşlemleri
```kotlin
implementation("org.jmrtd:jmrtd:0.7.18")
implementation("net.sf.scuba:scuba-sc-android:0.0.18")
```

### Kriptografi
```kotlin
implementation("com.madgag.spongycastle:prov:1.54.0.0")
implementation("org.bouncycastle:bcpkix-jdk15on:1.65")
```

### Görüntü İşleme
```kotlin
implementation("io.github.CshtZrgk:jp2-android:1.0.0")
implementation("commons-io:commons-io:2.11.0")
```

## 🔐 Güvenlik Protokolleri

### BAC (Basic Access Control)
- Pasaport numarası, doğum tarihi ve son kullanma tarihi kullanılır
- MRZ'den elde edilen verilerle güvenli bağlantı kurulur

### PACE (Password Authenticated Connection Establishment)
- Modern kimlik kartları için gelişmiş güvenlik protokolü
- BAC başarısız olursa otomatik olarak denenır

## 📋 Gereksinimler

### Donanım
- NFC özelliği olan Android cihaz
- Minimum API Level 26
- Kamera desteği

### İzinler
```xml
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.CAMERA" />
```

## 🎯 Kullanım

1. **Kimlik Tarama**: Uygulamayı açın ve kimlik kartınızı kameraya gösterin
2. **MRZ Okuma**: Kart otomatik olarak tanınır ve MRZ bilgileri çıkarılır
3. **NFC Okuma**: MRZ sonuç sayfasında "NFC Oku" butonuna basın
4. **Çip Okuma**: Kimlik kartınızı telefonun arkasına yaklaştırın
5. **Sonuç**: Çip içerisindeki bilgiler ve fotoğraf görüntülenir

## ⚠️ Önemli Notlar

- NFC okuma için önce MRZ taraması yapılmalıdır
- MRZ verilerindeki eksiklik durumunda NFC okuma başarısız olur
- Bazeski kimlik kartlarında NFC çipi bulunmayabilir
- JMRTD kütüphanesi sadece ISO 14443 Type B kartları destekler

## 🐛 Bilinen Sorunlar

- Eski Android sürümlerinde SpongyCastle uyumluluğu
- JPEG2000 format desteği için ek kütüphane gereksinimi
- NFC okuma süresinin uzayabilmesi

## 🔄 Geliştirme Notları

Uygulama tamamen Jetpack Compose ile yazılmıştır ve modern Android geliştirme standartlarını takip eder. NFC entegrasyonu, mevcut MRZ okuma altyapısına minimum müdahale ile eklenmiştir.