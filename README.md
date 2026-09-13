# PC Remote

Controla tu PC Windows desde un teléfono Android en la misma red local: energía, estadísticas en vivo, ratón, teclado, portapapeles, apps, procesos, ventanas y multimedia.

Monorepo con dos partes:

- **`agent/`** — aplicación de bandeja para Windows en **.NET 10** (C#). Expone un WebSocket seguro (`wss://`), se anuncia por mDNS, ejecuta los comandos y sirve un panel web de administración en `localhost`.
- **`android/`** — app nativa en **Kotlin + Jetpack Compose**.

`mobile/` es la primera versión del cliente (React Native + Expo). Está **deprecada**: sufría cierres opacos en release y se sustituyó por `android/`. Se conserva como referencia hasta que la app Kotlin tenga todas sus pantallas.

Comunicación por `wss://` en la LAN con certificado autofirmado y *pinning*, descubrimiento por mDNS y autenticación Ed25519 por dispositivo.

---

## Estado

| Parte | Hecho | Pendiente |
|---|---|---|
| Agente | Los 9 módulos (`system`, `systeminfo`, `input`, `clipboard`, `media`, `windows`, `processes`, `applications`, `ping`), emparejamiento, revocación, panel web con QR | Registro de auditoría (`command_log`), eventos push |
| Android | Descubrimiento, emparejamiento con código, dashboard de CPU/RAM, info del sistema y botones de energía | Touchpad, teclado, multimedia, apps y portapapeles; escáner de QR |

Detalle por fases en [`docs/ROADMAP.md`](docs/ROADMAP.md).

---

## Documentación

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — componentes, modelo de concurrencia, capas de seguridad.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — protocolo WebSocket y **todos los comandos con sus parámetros reales**.
- [`docs/PAIRING.md`](docs/PAIRING.md) — emparejamiento, autenticación, revocación y límites.
- [`docs/APIS.md`](docs/APIS.md) — APIs de Windows que usa cada módulo.
- [`docs/MVP.md`](docs/MVP.md) — alcance y criterios de aceptación del MVP.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — fases.
- [`android/README.md`](android/README.md) — compilar e instalar la app.

---

## Estructura

```
pc-remote/
├── docs/
├── agent/                                # .NET 10
│   ├── PcRemote.Agent.slnx
│   ├── Directory.Build.props             # target común (net10.0-windows10.0.19041.0)
│   ├── global.json
│   └── src/
│       ├── PcRemote.Agent/               # bandeja (WinForms) + arranque + appsettings.json
│       ├── PcRemote.Core/                # WebSocket, emparejamiento, sesiones, router, panel web, mDNS, SQLite
│       ├── PcRemote.Modules.System/      # apagar, reiniciar, suspender, hibernar, bloquear, cerrar sesión (+ ping)
│       ├── PcRemote.Modules.SystemInfo/  # info estática + stream de CPU/RAM
│       ├── PcRemote.Modules.Input/       # ratón y teclado (SendInput)
│       ├── PcRemote.Modules.Clipboard/   # leer, escribir, vigilar
│       ├── PcRemote.Modules.Applications/# menú Inicio + registro + UWP; lanzar
│       ├── PcRemote.Modules.Processes/   # listar y matar
│       ├── PcRemote.Modules.Windows/     # listar, enfocar, minimizar, maximizar, cerrar
│       └── PcRemote.Modules.Media/       # SMTC + volumen
├── android/                              # Kotlin + Compose
└── mobile/                               # DEPRECADO (React Native)
```

---

## Arrancar

### Agente

Necesita el **SDK de .NET 10** y Windows 10 2004 o posterior.

```bash
cd agent
dotnet run --project src/PcRemote.Agent
```

Aparece un icono en la bandeja. Desde él puedes abrir el **panel web** (`http://localhost:47810/`), que muestra el estado, genera el código de emparejamiento con su QR y permite revocar dispositivos.

Datos en `%LOCALAPPDATA%\PcRemote\`: `agent.db` (dispositivos), `cert.pfx` + `cert.pass` (certificado TLS; su contraseña va cifrada con DPAPI) y `logs/`.

Puertos por defecto (en `appsettings.json`):

| Puerto | Uso | Alcance |
|---|---|---|
| 47820 | `wss://…/ws`, protocolo del móvil | LAN |
| 47810 | Panel web | solo `localhost` |

Windows pedirá permiso de firewall para el 47820 la primera vez.

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
