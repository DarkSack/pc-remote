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

## Estado (0.5.0)

Diseño «Personal Command Center»: Material 3 con tema centralizado
(`ui/theme`), oscuro por defecto con claro real y colores dinámicos opcionales.
Barra inferior en teléfonos y *navigation rail* en tablets.

| Pestaña | Qué hay |
|---|---|
| **Inicio** | Estado del PC (en línea, latencia, tiempo conectado, IP, última sincronización), acciones rápidas (bloquear, suspender, reiniciar, apagar con confirmación; silenciar), CPU/RAM/GPU/disco con gráficas, red, y accesos a las herramientas |
| **Control** | Touchpad, teclado y multimedia (carátula, controles, volumen), y **Ver pantalla** |
| **Apps** | Rejilla con iconos reales, cuáles están abiertas, favoritas y recientes; abrir, traer al frente, cerrar |
| **Actividad** | Línea de tiempo del PC agrupada por día, con filtros |
| **Ajustes** | Conexión, tema, confirmaciones, vibración, alertas, bloqueo con huella/PIN, olvidar el PC, licencias |

Herramientas: **Monitor** (10 min de historial de CPU, RAM, GPU y red; discos,
temperaturas, VRAM), **Procesos** (agrupados, orden, búsqueda, finalizar),
**Red** (interfaces, conexiones, ping desde el PC), **Terminal** (PowerShell,
historial, copiar), **Archivos** (explorar, abrir en el PC, subir y bajar),
**Portapapeles** (todo el historial del PC con imágenes; enviar texto o una foto
al PC) y **Plugins** (ejecutar las acciones de los plugins activados en el PC).

**Pantalla remota** (Inicio › Pantalla, o Control › Ver pantalla), al estilo de RustDesk:

- Vídeo H.264 decodificado por hardware (`MediaCodec`) sobre un `TextureView`, con el
  cursor del PC dibujado encima (su forma real).
- **Modo ratón** (por defecto): arrastrar mueve el cursor; toque = clic; 2 dedos toque =
  clic derecho; mantener = arrastrar; 2 dedos arrastrar = scroll; pellizcar = zoom (la
  vista sigue al cursor).
- **Modo táctil**: tocar hace clic ahí; mantener = clic derecho; mantener y mover =
  arrastrar; 1 dedo = desplazar la vista con zoom o scroll sin él.
- Barra plegable: teclado del móvil (lo escrito se replica en el PC, también las
  correcciones), teclas especiales con Ctrl/Alt/Mayús/Win fijables, F1–F12 y atajos;
  monitor; calidad (velocidad, equilibrada, calidad); estadísticas (fps, Mbps, latencia,
  códec).
- Pantalla completa y pantalla encendida mientras se mira; el vídeo se corta al salir de
  la app y se reanuda al volver.

Emparejar también se puede **escribiendo la IP** (cuando el router bloquea mDNS y no se
puede escanear el QR).

Conexión:

- Al volver a la app comprueba que el socket siga vivo (ping) y, si no,
  reconecta **al momento**, sin esperar el backoff. Un latido cada 5 s detecta
  conexiones medio abiertas (Wi-Fi que se durmió, PC suspendido).
- Tras dos fallos busca el PC por la huella de su certificado: primero por
  mDNS y, si no aparece en otra dirección, recorriendo la subred del móvil
  (como mucho cada 2 min, o al pulsar «Reintentar»). Si cambió de IP, se
  mueve solo a la nueva.
- Si en la dirección guardada contesta **otro** PC (otro certificado), también
  busca el suyo antes de rendirse.
- Errores legibles («El PC no respondió — ¿está encendido y con PC Remote
  abierto?») con el detalle técnico tras «Ver detalles».
- No reintenta si el PC revocó el móvil, ni si cambió de certificado y no
  aparece en ninguna otra dirección.
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

El APK queda en `app/build/outputs/apk/<debug|release>/`.

### Firma de release

El APK de release se firma con la clave propia de PC Remote, que **no está en
el repositorio**. Gradle la busca en `android/keystore.properties` (ignorado por
git):

```properties
storeFile=C:/Users/<tú>/.android-keys/pcremote-release.jks
storePassword=…
keyAlias=pcremote
keyPassword=…
```

o, para una CI, en las variables `PCREMOTE_KEYSTORE`,
`PCREMOTE_KEYSTORE_PASSWORD` (y opcionalmente `PCREMOTE_KEY_ALIAS`,
`PCREMOTE_KEY_PASSWORD`). Sin ninguna de las dos, el release se firma con la
clave de depuración y Gradle lo avisa: ese APK **no** puede instalarse encima
de uno firmado con la clave propia.

Guarda una copia del `.jks` y de `keystore.properties` fuera del PC (gestor
de contraseñas, USB…). Si se pierden, las actualizaciones de la app ya
instalada son imposibles: habría que desinstalarla y volver a emparejar.

Para crear una clave nueva (solo la primera vez):

```powershell
keytool -genkeypair -keystore $HOME\.android-keys\pcremote-release.jks -storetype PKCS12 `
  -alias pcremote -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PC Remote, O=DarkSack"
```

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
