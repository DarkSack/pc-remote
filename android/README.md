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

## Estado (0.4.0)

Diseño «Personal Command Center»: Material 3 con tema centralizado
(`ui/theme`), oscuro por defecto con claro real y colores dinámicos opcionales.
Barra inferior en teléfonos y *navigation rail* en tablets.

| Pestaña | Qué hay |
|---|---|
| **Inicio** | Estado del PC (en línea, latencia, tiempo conectado, IP, última sincronización), acciones rápidas (bloquear, suspender, reiniciar, apagar con confirmación; silenciar), CPU/RAM/GPU/disco con gráficas, red, y accesos a las herramientas |
| **Control** | Touchpad, teclado y multimedia (carátula, controles, volumen) |
| **Apps** | Rejilla con iconos reales, cuáles están abiertas, favoritas y recientes; abrir, traer al frente, cerrar |
| **Actividad** | Línea de tiempo del PC agrupada por día, con filtros |
| **Ajustes** | Conexión, tema, confirmaciones, vibración, alertas, bloqueo con huella/PIN, olvidar el PC, licencias |

Herramientas: **Monitor** (10 min de historial de CPU, RAM, GPU y red; discos,
temperaturas, VRAM), **Procesos** (agrupados, orden, búsqueda, finalizar),
**Red** (interfaces, conexiones, ping desde el PC), **Terminal** (PowerShell,
historial, copiar), **Archivos** (explorar, abrir en el PC, subir y bajar),
**Portapapeles** (todo el historial del PC con imágenes; enviar texto o una foto
al PC) y **Plugins** (ejecutar las acciones de los plugins activados en el PC).

Conexión:

- Al volver a la app comprueba que el socket siga vivo (ping) y, si no,
  reconecta **al momento**, sin esperar el backoff. Un latido cada 5 s detecta
  conexiones medio abiertas (Wi-Fi que se durmió, PC suspendido).
- Tras dos fallos busca el PC por mDNS (por la huella del certificado) y, si
  cambió de IP, se mueve solo a la nueva.
- Errores legibles («El PC no respondió — ¿está encendido y con PC Remote
  abierto?») con el detalle técnico tras «Ver detalles».
- No reintenta si el PC revocó el móvil o cambió de certificado.
- Con la app en segundo plano la conexión dura 30 s y luego se cierra.

⚠️ Compila y los tests de lógica pasan, pero **no se ha probado aún en un móvil
real**.

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
app/src/main/java/com/sack/pcremote/
├── MainActivity.kt, PcRemoteApplication.kt
├── data/        # CredentialsStore (Keystore), AppSettings, AppPrefs (favoritas)
├── net/         # Protocol, AgentClient (WSS + pinning + latido), AgentError, Discovery, Crypto, QrPayload, WakeOnLan
├── session/     # PcSession (ViewModel por PC), TerminalSession
└── ui/
    ├── theme/       # Color.kt (paletas + colores extendidos), Theme.kt
    ├── components/  # PcStatusCard, SystemMetricCard, NetworkStatusCard, QuickActionButton,
    │                # SectionHeader, EmptyState, ErrorState, Skeleton, Sparkline/HistoryChart…
    ├── screens/     # Dispositivos, Emparejar, Bloqueo
    ├── pc/          # PcScaffold (navegación) + una pantalla por archivo
    └── remote/      # Touchpad, Teclado, Multimedia
```

Icono: `res/drawable/ic_launcher_*.xml` (adaptativo, con versión monocroma para
los iconos temáticos de Android 13+). El logo original está en `branding/`.
