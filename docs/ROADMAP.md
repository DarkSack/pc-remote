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
- [x] Cliente móvil: primero en React Native (`mobile/`, ya borrado), después
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

## Fase 5 · Paridad del móvil · ✅ (probada en un móvil real, oct 2026)

- [x] Touchpad: mover con aceleración, toque = clic, 2 dedos = clic derecho y
      scroll suave, mantener = arrastrar (`input.mouseDown/Up`, `mouseScroll.delta`).
- [x] Teclado: texto (`input.keyType`) + teclas, atajos y F1–F12 (`keyPress`).
- [x] Multimedia con carátula (`media.nowPlaying` en stream) y volumen.
- [x] Lanzador de apps con búsqueda.
- [x] Portapapeles en los dos sentidos (`clipboard.watch` / `set`).
- [x] Escáner del QR del panel: el certificado se fija desde el primer byte y
      no hace falta `pair_init`.
- [x] Probar todo lo anterior en un móvil real.
- [x] Borrar `mobile/` después de esa prueba.
- [x] Firmar el APK con un keystore propio (fuera del repo; ver `android/README.md`).

## Fase 6 · Extras

- [x] Registro de auditoría (`command_log`, 30 días) visible en el panel.
- [x] Wake-on-LAN desde el móvil (la MAC se aprende al conectar).
- [x] `media.nowPlaying` como stream, con carátula.
- [x] Procesos en el móvil (pantalla Procesos) y ventanas (Apps: traer al frente, cerrar).
- [x] Historial del portapapeles (texto, imágenes y archivos): solo en memoria, nunca
      en disco, y sin lo que los gestores de contraseñas marcan como privado.
- [x] Archivos: explorar, abrir en el PC, subir y bajar.
- [x] Plugins (23 incluidos) y funciones opcionales, que se activan solo desde el panel.
- [x] Pantalla remota (0.5.0): DXGI → H.264 por la GPU → socket propio por WSS (sin
      WebRTC: en la LAN no hace falta). Con sonido (WASAPI loopback → PCM) y
      monitores en vertical (el PC gira la imagen). Pendiente: probarla en un móvil
      real (también el sonido) y en un monitor girado de verdad, acceso desde fuera
      de casa (relay) y escritorio seguro (UAC/bloqueo, requiere un servicio de
      sistema).
- [x] Notificaciones de Windows en el móvil (función `notifications`, apagada por
      defecto): se leen de la base de datos de Windows. Pendiente: probarlas en un
      móvil real; avisos con la app cerrada (servicio en segundo plano).
- Macros y presets ("modo juego").

## Fase 7 · Acceso por Internet (mucho después)

- STUN/TURN para atravesar NAT; relay opcional.
- Rotación de tokens, lista de IPs permitidas.
- Nunca sin TLS + pinning ni sin doble factor.
