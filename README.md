# PC Remote

Controla tu PC Windows desde un teléfono Android en la misma red local. Arquitectura modular pensada para crecer: mouse, teclado, clipboard, apps, procesos, ventanas, media, y más.

Monorepo con dos partes:

- **`agent/`** — Aplicación de escritorio Windows en .NET 8 (C#) que corre como tray, expone WebSocket seguro (wss) y ejecuta comandos.
- **`mobile/`** — App Android en React Native + Expo + TypeScript.

Comunicación via **wss://** en LAN, descubrimiento por **mDNS**, autenticación con **Ed25519 challenge-response**.

---

## Estado

📐 **Fase 0 · Scaffolding + Docs** — completado en este commit.
La estructura de carpetas, los `.csproj`, las interfaces base y el protocolo están definidos.
No hay lógica de negocio implementada aún.

Roadmap: ver [`docs/ROADMAP.md`](docs/ROADMAP.md).
Alcance del MVP: ver [`docs/MVP.md`](docs/MVP.md).

---

## Documentación

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — arquitectura, componentes, decisiones.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — protocolo WebSocket completo con ejemplos.
- [`docs/PAIRING.md`](docs/PAIRING.md) — flujo de pairing y autenticación.
- [`docs/APIS.md`](docs/APIS.md) — APIs de Windows por módulo.
- [`docs/MVP.md`](docs/MVP.md) — alcance y criterios de aceptación del MVP.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — fases de implementación.

---

## Estructura

```
pc-remote/
├── README.md
├── docs/                        # Arquitectura, protocolo, APIs, MVP, roadmap
├── agent/                       # Windows agent (.NET 8, C#)
│   ├── PcRemote.Agent.sln
│   ├── global.json
│   ├── Directory.Build.props
│   └── src/
│       ├── PcRemote.Agent/                # Tray entrypoint
│       ├── PcRemote.Core/                 # Server, auth, router, protocol
│       ├── PcRemote.Modules.System/       # shutdown/restart/sleep/hibernate/lock/logoff
│       ├── PcRemote.Modules.SystemInfo/   # cpu/ram/gpu/disk/net perf
│       ├── PcRemote.Modules.Input/        # mouse + keyboard (SendInput)
│       ├── PcRemote.Modules.Clipboard/
│       ├── PcRemote.Modules.Applications/ # enum + launch (Registry + StartMenu + UWP)
│       ├── PcRemote.Modules.Processes/    # list + kill
│       ├── PcRemote.Modules.Windows/      # enum + focus/min/max/close
│       └── PcRemote.Modules.Media/        # SMTC + Core Audio volume
└── mobile/                      # Android app (React Native + Expo + TS)
    ├── package.json
    ├── tsconfig.json
    ├── app.json
    ├── app/                     # expo-router
    └── src/
        ├── net/                 # discovery, connection FSM, protocol, crypto
        ├── stores/              # zustand
        ├── storage/             # secure-store, SQLite
        └── ui/                  # components, theme
```

---

## Arrancar (Fase 0, scaffolding)

**Agent** (necesita SDK .NET 8):

```bash
cd agent
dotnet restore
dotnet build
# El tray aún no muestra funcionalidad real. Fase 1 la enciende.
dotnet run --project src/PcRemote.Agent
```

**Mobile** (necesita Node 20+):

```bash
cd mobile
npm install
npx expo start
```

---

## Principios

- **Comandos tipados** (`system.shutdown`, no `POST /execute`).
- **Cero confianza en el cliente**: agente valida auth, autorización, esquema, permisos.
- **Módulos independientes** (reflection-based discovery).
- **Mínimo privilegio**: agente corre como usuario, no como admin. Elevación on-demand.
- **TLS desde día uno** con cert autofirmado + pinning.

---

Hecho con 💻 por **Sack**.
