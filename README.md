# Kimlik Kartı Tarama ve NFC Okuma Uygulaması (Android)

Bu proje, Türkiye Cumhuriyeti kimlik kartlarını ve benzeri standartlardaki belgeleri taramak, doğrulamak ve NFC ile çip verilerini okumak için geliştirilmiş kapsamlı bir Android uygulamasıdır. Uygulama, modern Android teknolojileri kullanılarak Jetpack Compose ile oluşturulmuştur.

## 📸 Temel Özellikler

- **Gerçek Zamanlı Kart Tespiti**: Mediapipe Object Detection ile genel kart tespiti ve takibi yapılır ve kimlik kartının uygun konumda bulunması doğrulanır.
- **Kimlik Tanımlama Sistemi**: OpenCV ORB teknolojisi kullanılarak taratılan kartın T.C.Kimlik kartı validasyonu sağlanır.
- **MRZ (Machine Readable Zone) Okuma**: Gelişmiş görüntü işleme (OpenCV) ve metin tanıma (ML Kit) teknikleriyle kimlik kartının altındaki MRZ alanını okur ve verileri (isim, belge no, tarihler vb.) çıkarır.
- **NFC Çip Okuma**: MRZ'den elde edilen verileri kullanarak kimlik kartının çipine güvenli bir şekilde (BAC/PACE protokolleri ile) bağlanır.
- **Veri Doğrulama**: NFC çipinden alınan yüksek çözünürlüklü fotoğraf (DG2) ve diğer kişisel bilgileri görüntüler.
- **Modern Arayüz**: Tamamen Jetpack Compose ile tasarlanmış akıcı ve modern bir kullanıcı deneyimi sunar.

## 🌊 Uygulama Akışı

1.  **Kamera Ekranı**: Kullanıcı uygulamayı açar ve kimlik kartını kameraya gösterir.
2.  **Otomatik Tespit**: Uygulama, ekrandaki kartı otomatik olarak algılar.
3.  **Kart Validasyonu**: Algılanan kartın uı overlay ile eşleşmesine bağlı olarak kart tanımlama sistemi çalışarak kartın validasyonu sağlanır.
4.  **MRZ Okuma**: Kart doğrulandığında, MRZ alanı taranır ve bilgiler çıkarılır.
5.  **MRZ Sonuç Ekranı**: Başarıyla okunan MRZ verileri kullanıcıya gösterilir.
6.  **NFC Okumaya Geçiş**: Kullanıcı, bu ekrandaki "NFC Oku" butonu ile çip okuma işlemini başlatır.
7.  **NFC Okuma Ekranı**: Kullanıcıdan kimlik kartını telefonun arkasındaki NFC antenine yaklaştırması istenir.
8.  **NFC Sonuç Ekranı**: Çipten okunan veriler (fotoğraf dahil) ekranda görüntülenir.

## 🛠️ Kullanılan Teknolojiler ve Kütüphaneler

- **Dil**: [Kotlin](https://kotlinlang.org/)
- **Arayüz (UI)**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **Kamera**: [CameraX](https://developer.android.com/training/camerax)
- **Nesne Tespiti (Kart Algılama)**: [MediaPipe Tasks Vision](https://developers.google.com/mediapipe/solutions/vision/object_detector/android) (EfficientDet-Lite modeli ile)
- **Metin Tanıma (MRZ)**: [Google ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition)
- **Görüntü İşleme (MRZ Analizi)**: [OpenCV](https://opencv.org/)
- **NFC (eID Okuma)**:
    - `org.jmrtd:jmrtd`: Elektronik pasaport ve kimlik kartları için standartları uygular.
    - `net.sf.scuba:scuba-sc-android`: Android için akıllı kart iletişim altyapısı.
- **Kriptografi (NFC Güvenliği)**:
    - `SpongyCastle (madgag)`: Güvenli çip bağlantısı (BAC/PACE) için kriptografik işlemler.
    - `BouncyCastle`: PKI ve sertifika işlemleri.
- **Görüntü Formatı**:
    - `io.github.CshtZrgk:jp2-android`: NFC çipinden gelen JPEG2000 formatındaki yüz fotoğrafını çözümlemek için.
- **Asenkron İşlemler**: Kotlin Coroutines
- **Navigasyon**: Navigation Compose

## 🚀 Kurulum ve Çalıştırma

1.  **Projeyi Klonlayın**:
    ```bash
    git clone <repository-url>
    ```
2.  **Android Studio'da Açın**: Projeyi Android Studio (Iguana veya daha yeni bir sürüm) ile açın.
3.  **Gradle Sync**: Proje açıldığında, Android Studio'nun gerekli tüm bağımlılıkları indirmesi için Gradle senkronizasyonunun tamamlanmasını bekleyin.
4.  **Çalıştırın**:
    - Projeyi derlemek ve çalıştırmak için `Run 'app'` butonuna tıklayın.
    - **Önemli**: Uygulamanın tüm özelliklerini test edebilmek için **NFC özelliği olan fiziksel bir Android cihaz** kullanmanız gerekmektedir. Emülatörler NFC ve gelişmiş kamera özelliklerini desteklemez.

## 📂 Proje Yapısı

Projenin ana mantığı `app` modülü altında yer almaktadır. İşte önemli dosya ve dizinler:

```
/app/src/main/java/com/example/countercamtest/
├── MainActivity.kt             # Ana Activity ve Jetpack Compose navigasyon yapısı
├── UnifiedMatchingAnalyzer.kt  # Kamera akışını analiz eden, kart tespiti ve MRZ okumayı koordine eden merkezi sınıf
├── TCMRZReader.kt              # OpenCV kullanarak MRZ alanını işleyen ve verileri çıkaran sınıf
├── NFCActivity.kt              # NFC okuma işleminin yaşam döngüsünü yöneten Activity
├── NFCReadingScreen.kt         # NFC okuma işleminin yapıldığı Compose ekranı
├── NFCResultScreen.kt          # NFC'den gelen sonuçların gösterildiği Compose ekranı
├── objectdetection/            # MediaPipe ile nesne tespiti için yardımcı sınıflar
└── ui/                         # Jetpack Compose tema, renk ve tipografi ayarları
```
