# MVP

Leyenda: ✅ hecho · ⏳ pendiente · ✂️ descartado.

## Agente de Windows

- ✅ Kestrel + `wss://` con certificado autofirmado generado en el primer arranque.
- ✅ Bandeja (WinForms NotifyIcon): estado, abrir panel, gestionar dispositivos, salir.
- ✅ Panel web en `localhost`: estado, código + QR de emparejamiento, dispositivos, conexiones, logs.
- ✅ Emparejamiento (código de 6 dígitos) + challenge-response Ed25519.
- ✅ Router de comandos con descubrimiento de módulos por reflexión.
- ✅ **System**: apagar, reiniciar, suspender, hibernar, bloquear, cerrar sesión.
- ✅ **SystemInfo**: hostname, SO, CPU, RAM, uptime; stream de CPU/RAM.
- ✅ Serilog → fichero diario + últimas 500 líneas en el panel.
- ✅ Registro de auditoría en SQLite (`command_log`, 30 días, sin parámetros ni input continuo), visible en el panel.
- ✅ Disco, GPU, temperaturas y red en SystemInfo.

## Android

- ✅ Descubrimiento mDNS.
- ✂️ Respaldo por broadcast UDP (mDNS es suficiente en la práctica).
- ✅ Emparejamiento con código de 6 dígitos y errores claros.
- ✅ Máquina de estados de conexión con reconexión y backoff.
- ✅ Dashboard: CPU/RAM en vivo, info del sistema, botones de energía.
- ✅ Confirmación antes de acciones destructivas.
- ✅ Touchpad, teclado, multimedia, apps y portapapeles (probados en un móvil real en oct 2026).
- ✅ Emparejamiento por QR y Wake-on-LAN.

## Criterios de aceptación

Sin marcar = no verificado todavía en un móvil real con la app Kotlin.

- [ ] Emparejar en menos de 30 s desde el primer arranque.
- [ ] Apagar el PC desde el teléfono.
- [ ] Bloquear el PC.
- [ ] Reinicio el agente → el móvil se reconecta solo.
- [ ] Cambio de red y vuelvo → redescubre y reconecta.
- [x] Revoco el dispositivo → deja de funcionar al instante (probado a nivel de protocolo: cierre 4001 y la re-autenticación falla).
- [x] Los comandos aparecen en el registro de auditoría (probado a nivel de protocolo y en el panel).
- [ ] `openssl s_client` muestra la misma huella que fijó el móvil.
- [x] Un cliente sin emparejar **no** puede ejecutar comandos (probado a nivel de protocolo).

## Fuera del MVP

Implementado después, en el agente y en el móvil: ratón/teclado, portapapeles
(con historial), multimedia, ventanas, procesos, lanzador de apps, archivos,
terminal, plugins, pantalla remota (con sonido) y notificaciones de Windows; ver
[`ROADMAP.md`](ROADMAP.md).

Más adelante: macros, acceso por Internet.
