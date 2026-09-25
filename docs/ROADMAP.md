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
- [x] Cliente móvil: primero en React Native (ya eliminado del repo), después
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

## Fase 5 · Paridad del móvil · ✅ en código (sep 2026), ⏳ probar en dispositivo

- [x] Touchpad: mover con aceleración, toque = clic, 2 dedos = clic derecho y
      scroll suave, mantener = arrastrar (`input.mouseDown/Up`, `mouseScroll.delta`).
- [x] Teclado: texto (`input.keyType`) + teclas, atajos y F1–F12 (`keyPress`).
- [x] Multimedia con carátula (`media.nowPlaying` en stream) y volumen.
- [x] Lanzador de apps con búsqueda.
- [x] Portapapeles en los dos sentidos (`clipboard.watch` / `set`).
- [x] Escáner del QR del panel: el certificado se fija desde el primer byte y
      no hace falta `pair_init`.
- [ ] Probar todo lo anterior en un móvil real.
- [x] Borrar `mobile/` (React Native): eliminado del repositorio.
- [ ] Firmar el APK con un keystore propio.

## Fase 6 · Extras

- [x] Registro de auditoría (`command_log`, 30 días) visible en el panel.
- [x] Wake-on-LAN desde el móvil (la MAC se aprende al conectar).
- [x] `media.nowPlaying` como stream, con carátula.
- [x] Ventanas y procesos en el móvil (con % de CPU y orden).
- [x] Historial del portapapeles con imágenes (plugin propio, solo en memoria).
- [x] Explorador de archivos, terminal, red y actividad.
- Notificaciones de Windows reenviadas al móvil.
- Macros y presets ("modo juego").
- Streaming de pantalla (DXGI → H.264 → WebRTC).

## Fase 6.5 · Producto · ✅ (sep 2026)

- [x] Rediseño completo de la app (Material 3, identidad propia, tema claro,
      tablet, accesibilidad, háptica).
- [x] Reconexión fiable al volver a la app.
- [x] Sistema de plugins (activar/desactivar desde el panel; DLL externas).
- [x] Icono nuevo en Android, exe, bandeja y panel.
- [x] `PcRemote.exe` único, sin instalar .NET; CI que lo compila con el APK.

## Fase 7 · Acceso por Internet (mucho después)

- STUN/TURN para atravesar NAT; relay opcional.
- Rotación de tokens, lista de IPs permitidas.
- Nunca sin TLS + pinning ni sin doble factor.
