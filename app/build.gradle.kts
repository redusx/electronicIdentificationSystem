plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.countercamtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.countercamdev"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    // TensorFlow Lite model optimization
    androidResources {
        noCompress += "tflite"
        noCompress += "lite"
    }

    // MediaPipe model assets - no compression for .tflite files
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    val camerax = "1.3.1"   // stabil ve hafif

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // İsteğe bağlı: FPS ölçümü/yardımcılar için
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // OpenCV needed for TCMRZReader (MRZ processing)
    implementation("org.opencv:opencv:4.9.0")

    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    
    // SmartBank Rectangle Detector removed - using MLKit Object Detection

    // --- NFC ve Kimlik Okuma Kütüphaneleri (tananaev projesinden) ---

// 1. JMRTD Çekirdek Kütüphanesi ve Android için Akıllı Kart İletişimi
    implementation("org.jmrtd:jmrtd:0.7.18")
    implementation("net.sf.scuba:scuba-sc-android:0.0.18")

// 2. Kriptografi Kütüphaneleri (Bouncy/Spongy Castle)
// Çip ile güvenli bağlantı (BAC) ve şifreleme işlemleri için.
// "tananaev" projesi SpongyCastle kullanmış, bu daha eski Android sürümleriyle uyumluluk için.
// BouncyCastle yerine bunu kullanmak daha güvenli olabilir.
    implementation("com.madgag.spongycastle:prov:1.54.0.0")
// Güvenlik sertifikaları ve PKI işlemleri için
    implementation("org.bouncycastle:bcpkix-jdk15on:1.65")

// 3. Görüntü Formatı Çözücüleri (En Önemli Kısımlardan Biri)
// Yüksek çözünürlüklü yüz fotoğrafı (DG2) genellikle JPEG2000 formatındadır.
// Bu kütüphane, bu formatı Android'in anlayacağı Bitmap'e çevirir.
    implementation("io.github.CshtZrgk:jp2-android:1.0.0")

// 4. Yardımcı Kütüphaneler
    implementation("commons-io:commons-io:2.11.0")
    implementation("com.github.mhshams:jnbis:1.1.0")

// 5. MLKit Text Recognition (Tesseract yerine)
    // Updated to latest MLKit Text Recognition (research-based)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

// 6. Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")
// Using MLKit Object Detection instead of SmartBank

    // Camera and ML Kit dependencies
    implementation("com.google.mlkit:object-detection:17.0.0")

    implementation("androidx.camera:camera-extensions:1.3.1")
    implementation("androidx.compose.runtime:runtime-livedata:1.5.8")

    // MediaPipe Object Detection dependencies
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

}