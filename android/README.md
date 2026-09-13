# PC Remote — Android (Kotlin + Jetpack Compose)

Cliente Android nativo. Sustituye a `mobile/` (React Native + Expo), que
sufría cierres opacos en release por incompatibilidades de librerías nativas
con la New Architecture.

## Stack

- **Kotlin 2.4** (integrado en AGP 9) + **Jetpack Compose** (BOM 2026.09)
- **OkHttp 5** para WSS, con *pinning* del certificado por SHA-256
- **BouncyCastle** para Ed25519 (Android Keystore solo lo soporta desde API 33)
- **NsdManager** para descubrimiento mDNS (`_pcremote._tcp`)
- **Android Keystore** (AES-256-GCM) para cifrar las credenciales de cada PC
- **kotlinx-serialization-json** para el protocolo

`compileSdk` 37, `targetSdk` 36, `minSdk` 26 (Android 8.0).

## Estado

- ✅ Descubrimiento: PCs emparejados + escaneo mDNS (varios PCs a la vez)
- ✅ Emparejamiento por **QR** del panel (certificado fijado desde el inicio) o
  con código de 6 dígitos (comprueba que la huella declarada coincide con la
  de la conexión)
- ✅ Dashboard: CPU/RAM en tiempo real, info del sistema, energía
- ✅ **Touchpad**: arrastrar = mover, toque = clic, 2 dedos = clic derecho y
  scroll, mantener = arrastrar; sensibilidad ajustable
- ✅ **Teclado**: escribir texto, teclas especiales, atajos y F1–F12
- ✅ **Multimedia**: lo que suena (con carátula), play/pausa/pistas, volumen
- ✅ **Apps**: lista con búsqueda, abrir con un toque
- ✅ **Portapapeles** en los dos sentidos
- ✅ **Wake-on-LAN**: botón de encendido en la lista (aparece tras la primera
  conexión, cuando la app ya conoce la MAC del PC)
- ✅ Reconexión con backoff; no reintenta si el PC revocó el dispositivo o
  cambió de certificado
- ⚠️ Todo lo anterior compila y la lógica pura tiene tests, pero **no se ha
  probado aún en un móvil real**

El escáner de QR lo proporciona Google Play services (sin permiso de cámara
en la app). En móviles sin Play services, empareja con el código.

Wake-on-LAN solo funciona si está activado en la BIOS/UEFI y en el adaptador
de red del PC; con el "inicio rápido" de Windows algunos equipos no despiertan
desde apagado.

## Tests

```powershell
.\gradlew testDebugUnitTest   # QrPayload, WakeOnLan (JVM, sin dispositivo)
```

### Migración de credenciales (0.1.0 → 0.2.0)

Hasta 0.1.0 las credenciales se guardaban con `EncryptedSharedPreferences`
(`androidx.security:security-crypto`, deprecado). Desde 0.2.0 se cifran
directamente con una clave de Android Keystore. Al abrir la app, las
credenciales antiguas se copian al nuevo almacén y el fichero viejo se borra;
si algo falla, el fichero viejo se conserva. La dependencia `security-crypto`
solo sigue ahí para esa migración.

## Build

Requiere:

- **JDK 17 o 21**. Gradle 9.7 no arranca con JDK 26.
- Android SDK con la plataforma 37 (Gradle descarga lo que falte si las
  licencias están aceptadas).

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
cd android
.\gradlew assembleDebug      # o assembleRelease
```

Si Gradle falla al descargar con `PKIX path building failed` (proxy o
antivirus que inspecciona HTTPS), haz que Java use el almacén de
certificados de Windows:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT -Djavax.net.ssl.trustStore=NUL"
```

El APK queda en `app/build/outputs/apk/<debug|release>/`. El de release va
firmado con el keystore de debug.

## Debug con logcat

Con el móvil conectado por USB y la depuración activada:

```powershell
adb logcat | Select-String "PcRemote|AgentClient|CredentialsStore|AndroidRuntime"
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
            │   ├── AgentClient.kt    # WSS + pinning + streams; PairingClient
            │   ├── QrPayload.kt      # QR del panel
            │   └── WakeOnLan.kt      # magic packet
            ├── data/CredentialsStore.kt  # AES-GCM con Android Keystore
            └── ui/
                ├── PcRemoteApp.kt    # NavHost
                ├── theme/Theme.kt
                ├── screens/{Discovery,Pair,Dashboard}Screen.kt
                └── remote/           # Touchpad, Keyboard, Media, Apps, Clipboard (paneles del dashboard)
```
