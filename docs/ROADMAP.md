# Roadmap

## Fase 0 · Scaffolding + docs · ✅

Estructura de carpetas, `.csproj`, interfaces base, protocolo documentado,
MVP definido.

## Fase 1 · Fundaciones agente · ✅

- [x] Serilog + `Microsoft.Extensions.Hosting`.
- [x] `WebSocketServer` sobre Kestrel + WSS + cert autofirmado persistente.
- [x] `AgentDatabase` SQLite (devices/sessions/command_log).
- [x] `MdnsPublisher` con Makaretu.Dns.
- [x] Tray funcional: estado, notificaciones de pair code, Manage devices.

## Fase 2 · Auth + protocolo · ✅

- [x] `PairingService`: código 6 dígitos, TTL, rate limit, lockout.
- [x] Ed25519 challenge-response con NSec.
- [x] `CommandRouter` con reflection + `Assembly.LoadFrom` para descubrir
      módulos en el `bin/`.
- [x] Módulos `ping` + esqueletos de los 8 dominios.

## Fase 3 · Sistema + Info + Mobile MVP · ✅

- [x] **3a** `SystemModule` real (shutdown/restart/sleep/hibernate/lock/logoff)
      vía Win32.
- [x] **3a** `SystemInfoModule` real con Perf Counters + WMI + `GlobalMemoryStatusEx`.
- [x] **3b** Subscribe/stream/unsubscribe end-to-end (`IStreamModule`).
- [x] **3c** Mobile core de red: `protocol.ts`, `crypto.ts` (Ed25519),
      `discovery.ts` (mDNS), `pairing.ts`, `connection.ts` (FSM + reconexión +
      streams + ping/pong), `secure.ts` (expo-secure-store).
- [x] **3d** UI: discovery + pairing con código 6 dígitos + dashboard con
      stream de stats + tiles + confirmaciones destructivas (`Alert.alert`).

**Fin del MVP** — criterios de aceptación en [`MVP.md`](MVP.md).

## Fase 4 · Módulos post-MVP

Orden sugerido (por valor / dependencia técnica):

1. **`Input`** (mouse touchpad + teclado virtual). Bloqueante para el
   siguiente hito: control real del PC. Requiere `SendInput` + throttle
   adaptativo por RTT.
2. **`Clipboard`** — read/write + watch con `WM_CLIPBOARDUPDATE`.
3. **`Media`** — SMTC (metadata + play/pause/next) + volumen con NAudio.
4. **`Windows`** — enum + focus/min/max/close.
5. **`Processes`** — list + kill (con confirmación).
6. **`Applications`** — enum 3 fuentes (Registry, StartMenu, UWP) + cache
   SQLite + launch.

## Fase 5 · Nice-to-haves

- Historial clipboard con opt-in explícito + AES-256 + filtros anti-secrets.
- Macros: secuencia de comandos con delays.
- Custom commands: presets configurables ("Gaming mode").
- Screen streaming: capture DXGI → H.264 → WebRTC.
- File manager: navegación + upload / download.
- Notifications: forward de toast de Windows al móvil.

## Fase 6 · Internet remote (mucho después)

- STUN/TURN para NAT traversal.
- Relay server opcional para casos degradados.
- Capa extra de seguridad: token rotation cada X h, IP allowlist,
  geographic restrictions.
- Nunca sin TLS + pinning ni sin doble factor de auth.
