plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sack.pcremote"
    // Current AndroidX (navigation 2.10, core 1.19…) refuses to build below 37.
    // targetSdk stays at 36: compiling against 37 does not opt into Android 17's
    // behaviour changes, targeting it would — do that as its own tested step.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sack.pcremote"
        minSdk = 26          // Android 8.0
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
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

    // Built-in Kotlin takes its JVM target from here.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.VERSION_NAME for Ajustes > Acerca de
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Compose BOM manages all Compose lib versions.
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // LifecycleStartEffect: drop the PC connection while the app is in the background.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Bottom bar on phones, navigation rail on tablets / unfolded foldables.
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.10.1")

    // Optional app lock (fingerprint / face / device PIN).
    implementation("androidx.biometric:biometric:1.1.0")

    // ONLY to migrate credentials saved by versions <= 0.1.0. The library is
    // deprecated; CredentialsStore now encrypts with Android Keystore directly.
    // Remove once no install from before 0.2.0 is left.
    implementation("androidx.security:security-crypto:1.1.0")

    // QR del panel. El escáner lo pone Google Play services: sin permiso de cámara
    // en la app y sin meter un modelo de ML en el APK. Sin Play services queda el
    // emparejamiento manual con código.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Cliente WebSocket + cert pinning.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    // JSON del protocolo.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Ed25519 (BouncyCastle): el Keystore de Android solo tiene Ed25519 desde API 33.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    // Coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Debug-only Compose tooling.
    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM unit tests for logic that does not need a device (QR parsing, WoL packets).
    testImplementation("junit:junit:4.13.2")
}
