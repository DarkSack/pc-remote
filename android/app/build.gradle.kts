plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sack.pcremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sack.pcremote"
        minSdk = 26          // Android 8.0 — required for EncryptedSharedPreferences AES256_GCM
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // keep off until we validate proguard rules
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")   // self-sign for now
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Compose BOM manages all Compose lib versions.
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Persistencia segura de credenciales (Android Keystore + AES-GCM).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Cliente WebSocket + cert pinning.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON del protocolo.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Ed25519 (BouncyCastle) — sin depender de JCA modern.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")

    // Coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Debug-only Compose tooling.
    debugImplementation("androidx.compose.ui:ui-tooling")
}
