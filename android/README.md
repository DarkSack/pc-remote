# PC Remote — Android (Kotlin + Jetpack Compose)

Cliente Android nativo. Reemplazo del legacy `mobile/` (React Native + Expo)
que sufría crashes opacos en release por incompatibilidad de libs nativas
con la New Architecture. Ver [decisión en el README raíz](../README.md).

## Stack

- **Kotlin 2.0** + **Jetpack Compose** (BOM 2024.10)
- **OkHttp 4** para WSS + cert pinning por SHA-256
- **BouncyCastle** para Ed25519 (compatible con todos los Android >= 26)
- **NsdManager** built-in para mDNS discovery (`_pcremote._tcp`)
- **EncryptedSharedPreferences** (Android Keystore, AES-256 GCM) para
  credenciales de dispositivos
- **kotlinx-serialization-json** para el protocolo

## Estado

MVP (paridad con Fase 3 del legacy):

- ✅ Discovery: lista de emparejados + escaneo mDNS
- ✅ Pair: código 6 dígitos + nombre del dispositivo
- ✅ Dashboard: tiles CPU/RAM en tiempo real + info sistema + power controls
- ⏳ Fase 5-mobile (touchpad, keyboard, media, apps, clipboard) — pendiente

## Build

Requiere:

- JDK 17 o 21 (Adoptium recomendado)
- Android SDK 35 con build-tools 35.0.0
- `ANDROID_HOME` y `JAVA_HOME` configurados

```bash
# PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd android
.\gradlew assembleRelease
```

APK sale en `app/build/outputs/apk/release/app-release.apk` (self-signed
con debug keystore).

## Debug con logcat

Cuando el móvil está conectado por USB con depuración activada:

```bash
adb logcat | Select-String "PcRemote|AgentClient|AndroidRuntime"
```

## Estructura

```
android/
├── build.gradle.kts, settings.gradle.kts, gradle.properties
└── app/
    ├── build.gradle.kts, proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/{strings,themes}.xml
        └── java/com/sack/pcremote/
            ├── MainActivity.kt
            ├── net/
            │   ├── Protocol.kt       # tipos del protocolo WSS
            │   ├── Crypto.kt         # Ed25519 vía BouncyCastle
            │   ├── Discovery.kt      # NsdManager mDNS
            │   └── AgentClient.kt    # WSS + cert pinning + streams
            ├── data/CredentialsStore.kt  # EncryptedSharedPreferences
            └── ui/
                ├── PcRemoteApp.kt    # NavHost
                ├── theme/Theme.kt
                └── screens/{Discovery,Pair,Dashboard}Screen.kt
```
