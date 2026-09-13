# Roadmap

## Fase 0 · Scaffolding + docs · ✅

Estructura de carpetas, `.csproj`, interfaces base, protocolo documentado,
MVP definido.

## Fase 1 · Fundaciones del agente · ✅

- [x] Serilog + `Microsoft.Extensions.Hosting`.
- [x] `WebSocketServer` sobre Kestrel + WSS + certificado autofirmado persistente.
- [x] SQLite para dispositivos.
- [x] `MdnsPublisher`.
- [x] Bandeja: estado, notificaciones de código, gestionar dispositivos.

## Fase 2 · Auth + protocolo · ✅

- [x] `PairingService`: código de 6 dígitos, TTL, bloqueo por intentos.
- [x] Challenge-response Ed25519 con NSec.
- [x] `CommandRouter` con descubrimiento de módulos por reflexión.

## Fase 3 · Sistema + info + MVP móvil · ✅

- [x] `SystemModule` y `SystemInfoModule`.
- [x] Subscribe / stream / unsubscribe (`IStreamModule`).
- [x] Cliente móvil: primero en React Native (`mobile/`, deprecado), después
      reescrito en **Kotlin + Compose** (`android/`) por cierres en release.

## Fase 4 · Módulos del agente · ✅

`input`, `clipboard`, `media`, `windows`, `processes`, `applications`.
Parámetros exactos en [`PROTOCOL.md`](PROTOCOL.md).

## Fase 4.5 · Revisión y endurecimiento · ✅ (sep 2026)

- [x] Revocación efectiva al instante (cierre 4001); códigos de
      emparejamiento ligados a la IP; límites antes de autenticar.
- [x] Colas por dominio: un comando lento no bloquea a los demás.
- [x] Panel protegido contra DNS rebinding, CSRF y XSS.
- [x] Agente en .NET 10; paquetes al día y sin vulnerabilidades conocidas.
- [x] Android: AGP 9, Kotlin 2.4, OkHttp 5, credenciales cifradas con
      Android Keystore (sin `security-crypto`), mDNS con varios PCs.

## Fase 5 · Paridad del móvil · ⏳

- [ ] Touchpad (`input.mouseMove` / `mouseClick` / `mouseScroll`).
- [ ] Teclado virtual (`input.keyType` / `keyPress`).
- [ ] Multimedia (`media.*`).
- [ ] Lanzador de apps (`applications.*`).
- [ ] Portapapeles con `clipboard.watch`.
- [ ] Escáner del QR del panel (rellena host, puerto, código y huella;
      cierra el hueco de *trust-on-first-use* del emparejamiento manual).
- [ ] Borrar `mobile/` cuando haya paridad.
- [ ] Firmar el APK con un keystore propio.

## Fase 6 · Extras

- Registro de auditoría (`command_log`).
- Wake-on-LAN desde el móvil.
- `media.nowPlaying` como stream, con carátula.
- Notificaciones de Windows reenviadas al móvil.
- Historial de portapapeles con opt-in explícito y cifrado.
- Macros y presets ("modo juego").
- Streaming de pantalla (DXGI → H.264 → WebRTC).
- Gestor de archivos.

## Fase 7 · Acceso por Internet (mucho después)

- STUN/TURN para atravesar NAT; relay opcional.
- Rotación de tokens, lista de IPs permitidas.
- Nunca sin TLS + pinning ni sin doble factor.
