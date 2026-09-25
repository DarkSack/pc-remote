# Protocolo WebSocket

Transporte: `wss://<host>:47820/ws` (puerto configurable en `appsettings.json`).
Formato: JSON UTF-8, un mensaje por frame de texto.

Esta página describe **lo que el agente implementa hoy**. Si cambias un
módulo, actualiza su tabla aquí.

## Envelope común

Todo mensaje lleva `kind`. `ts` (Unix ms) es opcional al enviar; el agente lo
pone en lo que envía. `id` lo elige el cliente y el agente lo devuelve tal
cual (la app Android usa `cmd_<uuid>` y `sub_<uuid>`).

## 1 · Request / Response

```jsonc
// Cliente → Agente
{ "kind": "request", "id": "cmd_1", "domain": "input", "action": "mouseMove",
  "params": { "dx": 12, "dy": -4 } }

// Agente → Cliente (éxito)
{ "kind": "response", "id": "cmd_1", "success": true, "data": { "moved": true }, "ts": 1787500000000 }

// Agente → Cliente (error)
{ "kind": "response", "id": "cmd_1", "success": false,
  "error": { "code": "INVALID_PARAMS", "message": "…", "recoverable": false }, "ts": 1787500000000 }
```

`id`, `domain` y `action` son obligatorios; si falta alguno la respuesta es
`INVALID_PARAMS`. `domain` no distingue mayúsculas.

**Orden y concurrencia.** Las peticiones de un mismo dominio se ejecutan en
el orden en que llegaron (los `mouseMove` no se adelantan unos a otros).
Dominios distintos van en paralelo: un `applications.list` lento no retrasa
el ratón. Por eso las respuestas de dominios distintos pueden llegar en otro
orden; emparéjalas por `id`.

## 2 · Subscribe / Stream / Unsubscribe

```jsonc
{ "kind": "subscribe", "id": "sub_stats", "domain": "systeminfo", "action": "stats",
  "params": { "intervalMs": 1000 } }

{ "kind": "response", "id": "sub_stats", "success": true, "data": { "subscribed": true } }

{ "kind": "stream", "id": "sub_stats", "data": { "cpu": 23.5, "ramPct": 48.1, … }, "ts": … }

{ "kind": "unsubscribe", "id": "sub_stats" }
```

Máximo 16 suscripciones por conexión. Suscribirse con un `id` ya activo
reemplaza la suscripción anterior.

## 3 · Event

Reservado (`kind: "event"`, push sin petición previa). **Todavía no se emite
ninguno.**

## 4 · Ping / Pong

```jsonc
{ "kind": "ping" }   →   { "kind": "pong", "ts": … }
```

Además, el servidor manda frames de *keep-alive* de WebSocket cada 15 s
(`Session.PingIntervalSeconds`), y la app Android usa los ping de OkHttp.

## 5 · Bootstrap: emparejamiento y autenticación

Flujo completo, límites y revocación en [`PAIRING.md`](PAIRING.md).

| Mensaje (cliente) | Respuesta |
|---|---|
| `{ kind: "pair_init" }` | `{ kind: "pair_init_ack", ttlSec }` — el código se muestra en el PC. O `pair_result` con error `RATE_LIMITED`. |
| `{ kind: "pair_confirm", code, deviceName, publicKey }` | `{ kind: "pair_result", success, deviceId, certFingerprint }` o `{ success: false, error }` |
| Cualquier `request` / `subscribe` sin sesión | `{ kind: "auth_challenge", nonce }` (hex, 32 bytes). Solo se emite si no hay uno pendiente. |
| `{ kind: "auth", deviceId, signature }` | `{ kind: "auth_result", success, sessionId }`. Si falla, el agente cierra el socket. |

`publicKey` es la clave pública Ed25519 cruda (32 bytes) en base64;
`signature` es Ed25519 sobre los bytes del nonce, en base64. `deviceName` se
recorta a 64 caracteres y se le quitan los caracteres de control.

## Códigos de cierre

| Código | Cuándo |
|---|---|
| 1008 | Autenticación fallida, más de 20 mensajes sin autenticar, o sin autenticar tras `CodeTtlSeconds` + 60 s (180 s por defecto). |
| 1009 | Frame demasiado grande: 16 KB antes de autenticar, 16 MB después. |
| 4001 | Dispositivo revocado o borrado (en caliente o al intentar autenticar). **El cliente no debe reconectar.** |

## Códigos de error

| Código | Semántica |
|---|---|
| `NOT_AUTHENTICATED` | Sesión terminada (p. ej. revocada) o autenticación fallida. |
| `INVALID_COMMAND` | Dominio o acción desconocidos, o acción no suscribible. |
| `INVALID_PARAMS` | Faltan parámetros o tienen el tipo equivocado. |
| `NOT_FOUND` | El recurso no existe (PID, ventana, app, sesión multimedia). |
| `PERMISSION_DENIED` | Proceso protegido, el propio agente, o sin privilegio de apagado. |
| `TIMEOUT` | La petición se canceló (conexión cerrándose). |
| `INTERNAL_ERROR` | Excepción inesperada en el agente. Ver logs. |
| `RATE_LIMITED` | Demasiados intentos de emparejamiento o de suscripciones. |
| `PAIRING_FAILED` | Código incorrecto o caducado. |
| `PLUGIN_DISABLED` | El plugin de ese dominio está desactivado en el panel del PC (ver [`PLUGINS.md`](PLUGINS.md)). |

---

## Comandos por dominio

Parámetros con `?` son opcionales. Todo lo marcado como *request* se envía con
`kind: "request"`; lo marcado *subscribe*, con `kind: "subscribe"`.

### `ping`

| Action | Kind | Params | Data |
|---|---|---|---|
| `ping` | request | — | `{ pong, agentVersion, sessionId, device, serverTime }` |

### `system`

Ninguna acción recibe parámetros. Apagar, reiniciar y cerrar sesión fuerzan
el cierre de aplicaciones colgadas (`EWX_FORCEIFHUNG`).

| Action | Data | Destructivo |
|---|---|---|
| `shutdown` | `{ action, initiated }` | ✅ |
| `restart` | idem | ✅ |
| `logoff` | idem | ✅ |
| `sleep` | idem | — |
| `hibernate` | idem | — |
| `lock` | idem | — |

### `systeminfo`

| Action | Kind | Params | Data |
|---|---|---|---|
| `info` | request | — | `{ macAddress, broadcast, lanIp, hostname, username, os, osBuild, is64Bit, cpuModel, cpuCores, ramTotalMB, uptimeSec, timezone }` — `macAddress` (`AA:BB:…`) y `broadcast` son los del adaptador de la LAN, para Wake-on-LAN |
| `info` extra (0.4) | | | además `gpuName`, `vramTotalMB`, `agentVersion` |
| `stats` | request o subscribe | `{ intervalMs? }` (250–60000, por defecto 1000; solo en subscribe) | `{ cpu, cpuFreqMHz?, cpuTempC?, ramPct, ramUsedMB, ramTotalMB, gpu?: { name, usage, vramUsedMB, vramTotalMB, tempC }, disks: [{ name, label, totalGB, freeGB, usedPct }], net: { rxBps, txBps, iface, linkMbps }, uptimeSec, ts }` |

Los campos con `?` son `null` cuando Windows no los expone a un proceso sin
administrador: `cpuTempC` sale de las zonas térmicas ACPI (no del sensor del
encapsulado), la GPU de los contadores "GPU Engine" (lo mismo que el
Administrador de tareas) y `gpu.tempC` es siempre `null` por ahora. Dos
clientes a la vez comparten la misma lectura (una muestra vale 700 ms).

### `input`

| Action | Params | Data |
|---|---|---|
| `mouseMove` | `{ dx, dy }` relativo (cada uno recortado a ±2000 px) **o** `{ absolute: true, x, y }` con `x`,`y` en [0, 1] | `{ moved }` |
| `mouseClick` | `{ button?: "left" \| "right" \| "middle", count?: 1–3 }` | `{ clicked, count }` |
| `mouseDown` / `mouseUp` | `{ button? }` — pulsar sin soltar y soltar, para arrastrar | `{ button, down }` |
| `mouseScroll` | `{ amount, horizontal?: bool }` en muescas, **o** `{ delta, horizontal?: bool }` en unidades de rueda (120 = una muesca) para scroll suave. Positivo = arriba/derecha | `{ scrolled }` (unidades de rueda) |
| `mousePos` | — | `{ x, y, screenW, screenH }` |
| `keyPress` | `{ keys: "ctrl+shift+esc" }` — nombres en `VirtualKeys.cs` (`enter`, `f5`, `win`, letras, dígitos…) | `{ pressed }` |
| `keyType` | `{ text }` (máx. 4096 caracteres; se envía como Unicode, no depende del layout). `\n` y `\t` se pulsan como Enter y Tab; `\r` se ignora | `{ typed }` |

Nota: la app envía `mouseMove` y `mouseScroll` sin esperar respuesta, con `id`
`fire_<n>`; el agente responde igual y el cliente descarta esas respuestas.

Si la conexión se cae con un botón pulsado por `mouseDown`, el agente lo suelta.

`PERMISSION_DENIED` (recoverable) cuando Windows rechaza la entrada: pantalla
bloqueada, aviso de UAC o escritorio seguro. **Limitación:** la entrada dirigida a
una ventana que se ejecuta como administrador (p. ej. el Administrador de tareas)
se descarta en silencio y Windows no lo notifica; el agente, que corre sin
elevación, no puede controlarla ni detectarlo.

## Descubrimiento (mDNS)

Servicio `_pcremote._tcp`, puerto WSS. Registros TXT: `hostname`, `os`,
`version` y `fp` (SHA-256 del certificado TLS). La app usa `fp` para reconocer un
PC ya emparejado que ha cambiado de IP y actualizar la dirección guardada.

### `clipboard`

| Action | Kind | Params | Data |
|---|---|---|---|
| `get` | request | — | `{ text, length, truncated }` — `text` va recortado a 1 000 000 caracteres |
| `set` | request | `{ text }` (máx. 1 000 000 caracteres; `""` vacía) | `{ length }` |
| `setImage` | request | `{ imageBase64 }` PNG/JPEG/GIF/BMP, máx. 14 MB de base64 | `{ bytes }` — se copia como PNG (con transparencia) y como mapa de bits |
| `clear` | request | — | `{ cleared }` |
| `watch` | subscribe | — | `{ type, text, length, width, height, files, fileCount, thumbBase64, isPrivate, historyVersion }` al suscribirse y en cada cambio. `type` = `text` \| `image` \| `files` \| `empty`; `text` va recortado a 4096 caracteres; `thumbBase64` (JPEG ≤ 320 px) solo para imágenes. Cuando `historyVersion` cambia, el historial tiene algo nuevo |

Un único hilo STA del agente lee el portapapeles una vez por cambio y alimenta
a la vez `watch` y el historial.

### `cliphistory`

Historial de todo lo copiado en el PC mientras el agente está abierto. Solo en
memoria (máx. 200 entradas / 256 MB). Copiar algo que ya está lo sube arriba en
vez de duplicarlo. No se guarda lo que los gestores de contraseñas marcan como
privado (`ExcludeClipboardContentFromMonitorProcessing`,
`CanIncludeInClipboardHistory = 0`…). Desactivar el plugin borra el historial.

| Action | Params | Data |
|---|---|---|
| `list` | `{ offset?, limit?: 1–100 (50), type?: "text" \| "image" \| "files" }` | `{ version, total, items: [{ id, type, ts, preview, length, width, height, sizeBytes, files, fileCount, thumbBase64 }] }`, lo más reciente primero |
| `get` | `{ id }` | texto: `{ id, type, text, length }`; imagen: `{ id, type, pngBase64, width, height, originalWidth, originalHeight }` (reducida a 2560 px de lado como mucho); archivos: `{ id, type, files }` |
| `restore` | `{ id }` | `{ restored, type }` — lo vuelve a poner en el portapapeles del PC |
| `delete` | `{ id }` | `{ deleted, version }` |
| `clear` | — | `{ cleared, version }` |

### `applications`

| Action | Kind | Params | Data |
|---|---|---|---|
| `list` | request | `{ refresh?: bool, filter? }` — `refresh` fuerza un reescaneo ya (~1 s) | `{ version, count, applications: [{ id, name, source }] }` con `source` = `startmenu` (accesos del menú Inicio, incluidos accesos a URL como los juegos de Steam) \| `uwp` (Store) \| `registry` (programas instalados sin acceso directo), ordenadas por nombre |
| `watch` | subscribe | — | La misma forma que `list`, al suscribirse y **cada vez que cambia el catálogo** (se instala o desinstala algo) |
| `launch` | request | `{ id }` (el de `list`/`watch`) | `{ launched, id, source }`. Solo acepta ids del catálogo |

La lista sale de `shell:AppsFolder` (la misma que "Todas las apps" del menú
Inicio) más el registro. Se mantiene sola: vigila las carpetas del menú Inicio
(reescanea 3 s después del último cambio y otra vez a los 12 y 30 s, porque
Windows tarda unos 5 s en reflejarlo) y reescanea todo cada 2 min para las apps
de la Store. `version` sube con cada cambio. Si una fuente falla al reescanear, se conserva su lista
anterior en vez de dar sus apps por desinstaladas. Los accesos a desinstaladores
no se listan. Los ids son únicos.

### `appicons`

Dominio aparte para que cargar iconos no retrase un `launch` (cada dominio tiene su cola).

| Action | Params | Data |
|---|---|---|
| `get` | `{ ids: [...] }` (máx. 50) | `{ icons: { <id>: base64 PNG 64×64 con transparencia \| null } }`. `null` = la app no tiene icono (o el id no existe). Un id **ausente** del mapa es que no dio tiempo (5 s por petición): pídelo otra vez más tarde. Los iconos se guardan en memoria en el agente |

### `processes`

| Action | Params | Data | Destructivo |
|---|---|---|---|
| `list` | `{ limit?: 1–500 (50), filter?, sort?: "memory" \| "cpu" \| "name" }` | `{ count, total, processes: [{ pid, name, workingMB, cpu, threads, startTime, windowTitle }] }` — `cpu` es el % de toda la máquina desde la llamada anterior (la primera espera 500 ms para medir) | — |
| `kill` | `{ pid }` — mata el árbol entero; rechaza procesos del sistema y el propio agente | `{ killed, name }` | ✅ |

### `windows`

| Action | Params | Data |
|---|---|---|
| `list` | — | `{ windows: [{ hwnd, title, pid, process, foreground, minimized, maximized, x, y, width, height }] }` (solo visibles y con título; `foreground` = la que tiene el foco) |
| `focus` | `{ hwnd }` — restaura si está minimizada | `{ ok }` — `ok` dice si la ventana quedó realmente delante. Funciona aunque el agente esté en segundo plano (Windows limita eso; el agente lo sortea) |
| `minimize` / `maximize` / `restore` | `{ hwnd }` | `{ ok }` |
| `close` | `{ hwnd }` — envía `WM_CLOSE` sin esperar; la app puede preguntar antes de cerrar | `{ ok }` |

### `media`

| Action | Params | Data |
|---|---|---|
| `play` / `pause` / `playPause` / `next` / `previous` | — | `{ ok, source }`; `NOT_FOUND` si no hay sesión multimedia |
| `nowPlaying` | — como **request** | `{ active, source, title, artist, album, status }` |
| `nowPlaying` | — como **subscribe** | `{ active, source, title, artist, album, status, volume, mute, trackChanged, artworkBase64 }` al suscribirse y cada vez que algo cambia (se comprueba cada segundo). La carátula solo viaja con `trackChanged: true`: el cliente reemplaza su imagen por `artworkBase64` (null = sin carátula). Si el reproductor la publica tarde, llega hasta 5 s después en otro mensaje con `trackChanged: true`. Con `trackChanged: false` el cliente conserva la que tenía. Máx. 512 KB |
| `volumeGet` | — | `{ volume: 0–100, mute }` |
| `volumeSet` | `{ volume: 0–100 }` | `{ volume }` |
| `volumeMute` | `{ mute?: bool }` — sin parámetro alterna | `{ mute }` |

### `plugins`

| Action | Params | Data |
|---|---|---|
| `list` | — | `{ plugins: [{ id, domain, name, description, category, version, builtIn, enabled, canDisable, sensitive, loaded, restartRequired, actions }] }` |

Solo lectura: los plugins se activan y desactivan en el panel del PC. Detalles
en [`PLUGINS.md`](PLUGINS.md).

### `activity`

| Action | Params | Data |
|---|---|---|
| `recent` | `{ limit?: 1–500 (100) }` | `{ events: [{ ts, kind, title, detail, level, device }] }`, lo más reciente primero |

`kind`: `agent` (arranque), `session` (conectado / desconectado), `pairing`,
`command` (del registro de auditoría, con un título legible) y `alert` (RAM ≥
92 % o CPU ≥ 85 °C mientras alguien mira las métricas; una cada 15 min como
mucho). `level`: `info` \| `warning` \| `error`.

### `terminal` · desactivado por defecto

Ejecuta comandos **como el usuario** (el agente nunca se eleva). Hay que
activarlo en el panel del PC.

| Action | Params | Data | Destructivo |
|---|---|---|---|
| `info` | — | `{ cwd, shell, shells, user, host }` | — |
| `run` | `{ command (≤ 8 KB), shell?: "powershell" \| "pwsh" \| "cmd", timeoutSec?: 1–600 (60) }` | `{ stdout, stderr, exitCode, cwd, durationMs, timedOut, truncated }` | ✅ |

Cada sesión del móvil tiene su directorio actual: en PowerShell se lee tras
cada comando; en cmd, `cd`, `cd /d` y `X:` los resuelve el agente. La salida se
recorta a 512 KB por flujo; al agotar el tiempo o cortarse la conexión se mata
el árbol de procesos.

### `files`

| Action | Params | Data |
|---|---|---|
| `roots` | — | `{ folders: [{ name, path, kind }], drives: [{ name, path, kind, totalBytes, freeBytes }] }` |
| `list` | `{ path, hidden?: bool }` | `{ path, parent, entries: [{ name, path, dir, size, modified, ext }], truncated }` — carpetas primero, máx. 2000 |
| `open` | `{ path }` | `{ opened }` — con la app predeterminada del PC. **Rechaza programas y scripts** (`.exe`, `.bat`, `.ps1`, `.lnk`… y lo de `PATHEXT`) con `PERMISSION_DENIED` |
| `reveal` | `{ path }` | `{ revealed }` — lo muestra seleccionado en el Explorador |
| `read` | `{ path }` | `{ name, size, base64 }` — máx. 10 MB |

Solo rutas locales absolutas: las UNC (`\\servidor\recurso`) se rechazan con
`INVALID_PARAMS`, para que el móvil no pueda hacer que el PC se autentique
contra otra máquina.

### `network`

| Action | Params | Data |
|---|---|---|
| `info` | — | `{ hostname, lanIp, interfaces: [{ name, description, type, up, speedMbps, mac, ipv4, ipv6, gateways, dns, primary }], tcp: { total, established, listeners } }` |
| `connections` | `{ limit?: 1–500 (100) }` | `{ count, connections: [{ local, remote, state }] }` (sin loopback) |
| `ping` | `{ host?, count?: 1–10 (4) }` — sin `host`, la puerta de enlace | `{ host, results: [ms \| null], avgMs, lossPct }` |
