# SackDesk

> Antes se llamaba PC Remote. El repositorio y los proyectos (`PcRemote.*`,
> `com.sack.pcremote` en el código) conservan el nombre viejo; lo que ve el
> usuario, la carpeta de datos y el id de Play ya son de SackDesk.

Controla tu PC Windows desde un teléfono Android, en la misma red o desde cualquier otra: **pantalla remota** (verla y manejarla, al estilo de RustDesk), energía, monitor de hardware (CPU, RAM, GPU, discos, red), ratón, teclado, multimedia, apps, procesos, historial del portapapeles (con imágenes), archivos, terminal, actividad y **plugins**.

Monorepo con dos partes:

- **`agent/`** — aplicación de bandeja para Windows en **.NET 10** (C#). Expone un WebSocket seguro (`wss://`), se anuncia por mDNS, ejecuta los comandos y sirve un panel web de administración en `localhost`.
- **`android/`** — app nativa en **Kotlin + Jetpack Compose**.

Comunicación por `wss://` en la LAN con certificado autofirmado y *pinning*, descubrimiento por mDNS y autenticación Ed25519 por dispositivo.

---

## Estado

| Parte | Hecho | Pendiente |
|---|---|---|
| Agente | Módulos `system`, `systeminfo` (CPU/GPU/temperaturas/discos/red), `input`, `clipboard` (+ historial con imágenes), `media`, `windows`, `processes`, `applications`, `network`, `files`, `terminal`, `screen` (pantalla remota, H.264 por la GPU, con sonido y monitores en vertical), `notifications` (las de Windows, desactivado por defecto), `activity`, `plugins`; emparejamiento, revocación, panel web con QR, funciones opcionales y plugins, **tienda de plugins** (catálogo firmado); **un solo `SackDesk.exe`** | Probar en un monitor en vertical de verdad |
| Android | Rediseño «Personal Command Center» (Material 3, tema oscuro y claro), Inicio con estado y métricas, Control, **Pantalla remota** (modo ratón y táctil, zoom, teclado, varias pantallas), Apps, Actividad, Ajustes, Monitor, Procesos, Red, Terminal, Archivos, Portapapeles, Notificaciones de Windows, Plugins; reconexión fiable al volver a la app; bloqueo con huella; Wake-on-LAN. Probada en un móvil real hasta la 0.4 | Probar en un móvil real la pantalla remota (0.5), su sonido y las notificaciones |

Detalle por fases en [`docs/ROADMAP.md`](docs/ROADMAP.md).

---

## Documentación

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — componentes, modelo de concurrencia, capas de seguridad.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — protocolo WebSocket y **todos los comandos con sus parámetros reales**.
- [`docs/PAIRING.md`](docs/PAIRING.md) — emparejamiento, autenticación, revocación y límites.
- [`docs/PLUGINS.md`](docs/PLUGINS.md) — funciones opcionales y cómo escribir plugins.
- [`docs/APIS.md`](docs/APIS.md) — APIs de Windows que usa cada módulo.
- [`docs/MVP.md`](docs/MVP.md) — alcance y criterios de aceptación del MVP.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — fases.
- [`android/README.md`](android/README.md) — compilar e instalar la app.

---

## Estructura

```
pc-remote/
├── branding/                             # logo (SVG) y render.cs → .ico y PNG
├── docs/
├── agent/                                # .NET 10
│   ├── publish.ps1                       # → dist\SackDesk.exe (un solo ejecutable)
│   ├── PcRemote.Agent.slnx
│   ├── Directory.Build.props             # target común (net10.0-windows10.0.19041.0)
│   ├── global.json
│   ├── plugins/                          # 23 plugins incluidos (se copian desactivados a la carpeta de plugins)
│   ├── plugins-tienda/                   # plugins de la tienda (se publican con deploy/plugins/publicar.mjs)
│   ├── src/
│   │   ├── PcRemote.Agent/               # bandeja (WinForms) + arranque + appsettings.json
│   │   ├── PcRemote.Core/                # WebSocket, emparejamiento, sesiones, router, panel web, mDNS, SQLite, plugins, actividad
│   │   ├── PcRemote.Modules.System/      # apagar, reiniciar, suspender, hibernar, bloquear, cerrar sesión (+ ping)
│   │   ├── PcRemote.Modules.SystemInfo/  # info + stream de CPU/RAM/GPU/discos/red, alertas
│   │   ├── PcRemote.Modules.Input/       # ratón y teclado (SendInput)
│   │   ├── PcRemote.Modules.Clipboard/   # leer, escribir, vigilar, historial (texto, imágenes, archivos)
│   │   ├── PcRemote.Modules.Applications/# "Todas las apps" del menú Inicio (Store y Steam incluidos) + registro; iconos; lanzar
│   │   ├── PcRemote.Modules.Processes/   # listar y matar
│   │   ├── PcRemote.Modules.Windows/     # listar, enfocar, minimizar, maximizar, cerrar
│   │   ├── PcRemote.Modules.Media/       # SMTC + volumen
│   │   ├── PcRemote.Modules.Network/     # interfaces, conexiones, ping
│   │   ├── PcRemote.Modules.Notifications/ # notificaciones de Windows (opcional, apagada por defecto)
│   │   ├── PcRemote.Modules.Files/       # explorar, abrir, subir y bajar (opcional)
│   │   ├── PcRemote.Modules.Terminal/    # PowerShell (opcional, apagada por defecto)
│   │   └── PcRemote.Modules.Screen/      # pantalla remota: DXGI + H.264 (Media Foundation) y sonido (WASAPI) por su propio socket
│   └── tests/PcRemote.Tests/             # xUnit: emparejamiento, errores de parámetros, catálogo, teclas…
└── android/                              # Kotlin + Compose
```

---

## Arrancar

### Agente

**Para usarlo:** un solo ejecutable, sin instalar nada (ni .NET). Genéralo con

```bash
powershell -ExecutionPolicy Bypass -File agent/publish.ps1
```

y reparte `dist\SackDesk.exe` (~70 MB). Doble clic y **se instala solo**, para tu
usuario y sin pedir admin: queda en `%LOCALAPPDATA%\Programs\SackDesk`, en el menú
Inicio, arranca con Windows (se quita en el menú de la bandeja) y aparece en
Configuración › Aplicaciones para desinstalarlo. Abrir una versión nueva la
actualiza: cierra la anterior y conserva los móviles emparejados. La primera vez
se abre el panel con el QR para emparejar; abrirlo otra vez con el agente ya en
marcha solo abre el panel. Desinstalar sin preguntas (winget):
`SackDesk.exe --uninstall --quiet`. Si quieres cambiar la configuración, pon un
`appsettings.json` junto al exe o en `%LOCALAPPDATA%\SackDesk\`.

**Para desarrollar** (SDK de .NET 10, Windows 10 2004 o posterior):

```bash
cd agent
dotnet run --project src/PcRemote.Agent
```

Aparece un icono en la bandeja. Desde él puedes abrir el **panel web** (`http://localhost:47810/`), que muestra el estado, genera el código de emparejamiento con su QR y permite revocar dispositivos.

Datos en `%LOCALAPPDATA%\SackDesk\`: `agent.db` (dispositivos), `cert.pfx` + `cert.pass` (certificado TLS; su contraseña va cifrada con DPAPI), `features.json` (funciones y plugins activados), `plugins/` y `logs/`.

La **terminal** y las **notificaciones** vienen apagadas, y los **plugins** nuevos también (el agente trae 23, de pantalla, energía, audio, red, limpieza, winget…): se activan en el panel («Funciones y plugins»), nunca desde el móvil. Ver [`docs/PLUGINS.md`](docs/PLUGINS.md).

Puertos por defecto (en `appsettings.json`):

| Puerto | Uso | Alcance |
|---|---|---|
| 47820 | `wss://…/ws`, protocolo del móvil | LAN |
| 47810 | Panel web | solo `localhost` |

Windows pedirá permiso de firewall para el 47820 la primera vez. Si se deniega, o un antivirus
bloquea la conexión, el móvil entra por el relay (ver `docs/PROTOCOL.md`).

Tests del agente (no necesitan el agente arrancado; uno lee la lista real de apps de este Windows):

```bash
cd agent
dotnet test PcRemote.Agent.slnx
```

### Android

Ver [`android/README.md`](android/README.md).

---

## Principios

- **Comandos tipados** (`system.shutdown`), nunca "ejecuta este string".
- **Cero confianza en el cliente**: el agente valida sesión, parámetros y límites en cada comando.
- **Módulos independientes**, descubiertos por reflexión: un dominio nuevo es un proyecto nuevo, sin tocar Core.
- **Mínimo privilegio**: el agente corre como el usuario, no como administrador.
- **TLS desde el primer día**, con certificado autofirmado y *pinning* en el móvil.

---

Hecho con 💻 por **Sack**.
