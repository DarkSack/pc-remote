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
| 1009 | Frame demasiado grande: 16 KB antes de autenticar, 4 MB después. |
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
| `FEATURE_DISABLED` | La función opcional o el plugin está desactivado en el panel del PC (ver [PLUGINS.md](PLUGINS.md)). También al suscribirse. |

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
| `info` | request | — | `{ macAddress, broadcast, lanIp, hostname, username, os, osBuild, is64Bit, cpuModel, cpuCores, gpuModel, ramTotalMB, uptimeSec, timezone }` — `macAddress` (`AA:BB:…`) y `broadcast` son los del adaptador de la LAN, para Wake-on-LAN |
| `stats` | request o subscribe | `{ intervalMs? }` (250–60000, por defecto 1000; solo en subscribe) | `{ cpu, cpuFreqMHz?, cpuTempC?, ramPct, ramUsedMB, ramTotalMB, gpu?: { name, usage, tempC?, vramUsedMB?, vramTotalMB?, clockMHz? }, disks: [{ name, label, totalGB, freeGB, usedPct }], net: { downBps, upBps }, uptimeSec, ts }` — los campos con `?` son `null` si el PC no los expone (temperaturas: sensores ACPI; GPU: contadores de Windows y `nvidia-smi` si existe) |

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
| `clear` | request | — | `{ cleared }` |
| `watch` | subscribe | — | `{ text, length }` al suscribirse y en cada cambio; `text` va recortado a 4096 caracteres |
| `setImage` | request | `{ data }` — PNG/JPEG/BMP en base64, máx. 3 MB | `{ width, height }` |
| `historyList` | request | — | `{ enabled, items: [ClipItem] }` |
| `historyWatch` | subscribe | — | `{ op: "snapshot", enabled, items }` al suscribirse; luego `{ op: "add", item }` (una entrada repetida sube arriba con el mismo id), `{ op: "remove", id }` o `{ op: "clear" }` |
| `historyGet` | request | `{ id }` | `{ id, kind, text, width, height, png }` — contenido completo (`png` en base64 para imágenes) |
| `historyRestore` | request | `{ id }` | `{ restored }` — la vuelve a poner en el portapapeles del PC |
| `historyDelete` | request | `{ id }` | `{ deleted }` |
| `historyClear` | request | — | `{ cleared }` |

`ClipItem` = `{ id, ts, kind: "text" | "image" | "files", preview, length, width, height, bytes, thumbnail, files }`:
`preview` son los primeros 280 caracteres, `thumbnail` una miniatura PNG en base64
(solo imágenes) y `files` los nombres copiados. El historial lo lleva el agente
(`Clipboard:HistorySize`, 60 por defecto; 0 lo desactiva), vive en memoria y
respeta lo que las apps marcan como privado (gestores de contraseñas: formatos
`ExcludeClipboardContentFromMonitorProcessing` / `CanIncludeInClipboardHistory = 0`).

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
| `list` | `{ limit?: 1–500 (50), filter?, sort?: "ram" \| "cpu" \| "name", group?: bool }` | `{ count, total, processes: [...] }`. Sin `group`: `{ pid, name, cpu, workingMB, title, isProtected, … }`. Con `group: true` (uno por programa): `{ name, count, pids, cpu, workingMB, title, isProtected }` | — |
| `watch` | subscribe; mismos parámetros + `intervalMs?` (1000–30000, 2000) | La misma forma que `list`, periódicamente | — |
| `kill` | `{ pid }` o `{ pids: [...] }` — mata el árbol entero; rechaza procesos del sistema y el propio agente | `{ killed, pids, name, errors }` | ✅ |

`cpu` es el % del total de la máquina desde la muestra anterior.

### `windows`

| Action | Params | Data |
|---|---|---|
| `list` | — | `{ windows: [{ hwnd, title, pid, process, description, foreground, minimized, maximized, x, y, width, height }] }` (solo visibles y con título; `foreground` = la que tiene el foco) |
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

### `network`

| Action | Params | Data |
|---|---|---|
| `info` | — | `{ hostname, lanIp, mac, interfaces: [{ name, description, type, up, ipv4, prefixLength, ipv6, mac, speedMbps, gateway, dns, primary }], tcp: { established, listening, timeWait, total }, remote: [{ address, ports, count, local }] }` |
| `ping` | `{ host? }` — por defecto la puerta de enlace | `{ host, sent, lost, avgMs, minMs, maxMs }` |

### `files`

Función opcional (activada por defecto). Rutas absolutas; se rechazan rutas UNC
y de dispositivo.

| Action | Kind | Params | Data |
|---|---|---|---|
| `roots` | request | — | `{ places: [{ name, icon, path }], drives: [{ name, path, label, type, ready, totalBytes, freeBytes }] }` |
| `list` | request | `{ path, showHidden? }` | `{ path, name, parent, entries: [{ name, path, dir, size, modified, ext, hidden }], truncated }` — carpetas primero |
| `open` / `reveal` | request | `{ path }` | `{ opened }` — abrir con la app predeterminada / mostrar en el Explorador |
| `read` | request | `{ path, offset?, length? (≤ 1 MiB) }` | `{ name, offset, length, total, eof, data }` (base64) — para descargar por trozos |
| `upload` | request | `{ uploadId, name, offset, data, done, dir? }` | `{ received }`, y con `done: true` también `{ path }` final. Trozos en orden; se escribe como `.part` oculto y se renombra al acabar (sin pisar: `archivo (1).ext`). Por defecto a Descargas. Máx. 2 GB |

### `terminal`

Función opcional, **desactivada por defecto**.

| Action | Kind | Params | Data |
|---|---|---|---|
| `info` | request | — | `{ shell, cwd, user, host }` |
| `exec` | subscribe | `{ command, cwd? }` | Lotes `{ type: "out" \| "err", lines: [...] }` y al final `{ type: "exit", code, cwd, durationMs, truncated, error? }`. `cwd` es la carpeta al terminar (un `cd` se conserva si el cliente la reenvía). Cancelar la suscripción mata el proceso |

Cada comando es un `powershell.exe -NoProfile -NonInteractive` nuevo, sin perfil
y con el mismo usuario que el agente. Salida máx. ~1 M caracteres por comando.

### `plugins`

| Action | Params | Data |
|---|---|---|
| `list` | — | `{ folder, features: [{ id, name, description, icon, enabled }], plugins: [{ id, name, description, icon, version, author, kind: "actions" \| "assembly", enabled, loaded, errors, actions: [{ id, label, description, icon, confirm, output, timeoutSec, params }], domains }] }` |
| `run` | `{ plugin, action, params? }` | `{ started, exitCode, stdout, stderr, timedOut, truncated }`. `INVALID_PARAMS` si un parámetro no cumple sus reglas; `FEATURE_DISABLED` si el plugin está apagado |

Activar y desactivar **no** está en el protocolo: solo en el panel del PC.
Formato de `plugin.json` en [PLUGINS.md](PLUGINS.md).

### `activity`

Lo que pasa en el PC: conexiones, encendido, energía, apps abiertas, procesos
finalizados, alertas de hardware (temperatura ≥ 85 °C, CPU ≥ 95 % un minuto,
RAM ≥ 92 %, disco < 5 % libre), plugins, terminal, archivos. En memoria (300).

| Action | Kind | Params | Data |
|---|---|---|---|
| `list` | request | `{ limit? }` | `{ events: [ActivityEvent] }`, lo más reciente primero |
| `watch` | subscribe | `{ limit? }` | `{ op: "snapshot", events }` y luego `{ op: "add", event }` |

`ActivityEvent` = `{ id, ts, type, title, detail, severity: "info" | "success" | "warning" | "error" }`,
con `type` ∈ `connection`, `power`, `agent`, `alert`, `app`, `process`, `plugin`, `terminal`, `files`, `clipboard`.
