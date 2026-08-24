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

## Fase 4 · Módulos post-MVP · ✅

Los 6 módulos han sido implementados en tandas separadas, cada uno con
su propio commit funcional. Todos compilan en la solution completa.

1. **`Input`** ✅ — mouse move (relativo/absoluto), click (l/r/m + count),
   scroll (v/h), pos, keyPress (combos tipo `ctrl+shift+esc`), keyType
   (Unicode). Deltas clampeados a ±2000 para evitar jumps.
2. **`Clipboard`** ✅ — get/set/clear + watch como `IStreamModule` (poll
   500 ms). STA marshaling on-demand para el API WinForms.
3. **`Media`** ✅ — SMTC (play/pause/next/previous/nowPlaying con título/
   artista/álbum + PlaybackStatus) + NAudio (volumeGet/Set/Mute del
   default endpoint).
4. **`Windows`** ✅ — list (top-level visibles con hwnd/title/pid/rect),
   focus / minimize / maximize / restore / close (WM_CLOSE).
5. **`Processes`** ✅ — list (top por WorkingSet, filter opcional), kill
   (guardas: self-pid + procesos protegidos del sistema). Destructive:true.
6. **`Applications`** ✅ — 3 fuentes (Start Menu .lnk, Registry Uninstall,
   UWP vía `Get-StartApps`) con cache TTL 5 min y dedup por nombre; launch
   estable por id (`source:key`).

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
