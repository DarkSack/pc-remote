# MVP

## Windows Agent

- ✅ Kestrel + `wss://` con cert autofirmado generado en primer arranque.
- ✅ Tray icon (WinForms NotifyIcon): estado + "Manage devices" + "Exit".
- ✅ Pairing (código 6 dígitos + Ed25519 challenge-response).
- ✅ Command router con reflection de módulos.
- ✅ **`PcRemote.Modules.System`**: shutdown, restart, sleep, hibernate,
  lock, logoff.
- ✅ **`PcRemote.Modules.SystemInfo`**: cpu, ram, disk, hostname, os,
  uptime; stream de stats cada 1 s.
- ✅ Serilog → archivo rotable + últimos 200 en memoria visibles desde
  el tray.
- ✅ Audit log en SQLite.

## Android

- ✅ Discovery mDNS (con fallback UDP broadcast).
- ✅ Pairing UI (input código 6 dígitos, feedback claro de éxito/error).
- ✅ Connection state machine + reconexión con backoff.
- ✅ Dashboard:
  - Stream de CPU/RAM cada 1 s.
  - Info sistema (OS, hostname, uptime).
  - 8 tiles (system/mouse/keyboard/clipboard/apps/media/windows/processes)
    — sólo `System` operativo.
- ✅ Confirmaciones para acciones destructivas.

## Criterios de aceptación

- [ ] Pairing en <30 s desde el primer arranque.
- [ ] Puedo apagar la PC desde el teléfono.
- [ ] Puedo bloquear la PC.
- [ ] Reinicio el agente → el móvil se reconecta solo.
- [ ] Cambio de red y vuelvo → redescubre y reconecta.
- [ ] Revoco el dispositivo desde el tray → deja de funcionar
      inmediatamente.
- [ ] Los comandos aparecen en el audit log.
- [ ] TLS validado: `openssl s_client` reporta el mismo fingerprint que
      el que pinneó el móvil.
- [ ] Un segundo teléfono sin emparejar **no** puede ejecutar comandos.

## Fuera de scope del MVP

Estos van en Fase 4+ (ver [`ROADMAP.md`](ROADMAP.md)):

- Mouse / touchpad
- Teclado virtual
- Clipboard read/write y historial
- Media control (SMTC + volume)
- Windows management
- Processes list + kill
- Applications launcher
- GPU% y stats extendidas
- Traducción / macros / custom commands
- Streaming de pantalla, file manager, acceso internet
