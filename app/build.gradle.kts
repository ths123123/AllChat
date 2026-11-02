plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nomor build dari GitHub Actions (run_number) dipakai sebagai versi, supaya cek update bisa membandingkan.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.fauzan.allchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fauzan.allchat"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Hanya prosesor HP modern (64-bit ARM) → APK jauh lebih kecil
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging {
        jniLibs { useLegacyPackaging = true } // library native dikompres di dalam APK
    }

    // Kunci tanda tangan tetap: file dibuat oleh GitHub Actions dari secret KEYSTORE_B64,
    // kata sandi dari secret KS_PASS. Tidak pernah disimpan di repo.
    val ksPass = System.getenv("KS_PASS")
    signingConfigs {
        create("fixed") {
            storeFile = file("allchat.jks")
            storePassword = ksPass
            keyAlias = "allchat"
            keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Translate layar: baca teks di HP (model dari Google Play Services), terjemahan online. Tanpa iklan.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
}
