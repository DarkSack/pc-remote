# Roadmap

## Fase 0 · Scaffolding + docs · **✅ ACTUAL**

Este commit. Estructura de carpetas, `.csproj`, interfaces base, protocolo
documentado, MVP definido. Sin lógica.

## Fase 1 · Fundaciones agente (~1 sem)

- [ ] Serilog + `Microsoft.Extensions.Hosting` cableado en `PcRemote.Agent`.
- [ ] `PcRemote.Core.Server.WebSocketServer` sobre Kestrel + WSS.
- [ ] Generación automática del certificado autofirmado en primer arranque
      (guardado en `%LOCALAPPDATA%\PcRemote\cert.pfx`).
- [ ] `PcRemote.Core.Storage.AgentDatabase` con migraciones (SQLite).
- [ ] `PcRemote.Core.Discovery.MdnsPublisher` con Makaretu.Dns.
- [ ] Tray funcional: muestra puerto, IP, estado de conexiones.

## Fase 2 · Auth + protocolo (~1 sem)

- [ ] `PcRemote.Core.Auth.PairingService`: código 6 dígitos, rate limit,
      lockout.
- [ ] Ed25519 challenge-response con NSec.Cryptography.
- [ ] `PcRemote.Core.Router.CommandRouter`: descubre módulos por
      reflection, dispatchea.
- [ ] Módulo de prueba `ping` → verifica end-to-end.
- [ ] "Manage devices" en el tray (list + revoke).

## Fase 3 · Sistema + Info + Mobile MVP (~1 sem)

- [ ] Implementación de `SystemModule` (shutdown/restart/sleep/hibernate/
      lock/logoff) usando Win32.
- [ ] Implementación de `SystemInfoModule` con Perf Counters + WMI +
      `GlobalMemoryStatusEx`.
- [ ] Mobile: scaffold Expo con `expo-router`.
- [ ] `src/net/discovery.ts` con `react-native-zeroconf`.
- [ ] `src/net/connection.ts` FSM + reconexión + pinning.
- [ ] `src/net/crypto.ts` Ed25519 con `@noble/ed25519`.
- [ ] Pairing UI + Dashboard con stream de stats + tiles.
- [ ] Confirmaciones destructivas (SweetAlert-equivalente en RN).

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
