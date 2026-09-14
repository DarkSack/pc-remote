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
| `stats` | request o subscribe | `{ intervalMs? }` (250–60000, por defecto 1000; solo en subscribe) | `{ cpu, ramPct, ramUsedMB, ramTotalMB, ts }` |

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

### `applications`

| Action | Kind | Params | Data |
|---|---|---|---|
| `list` | request | `{ refresh?: bool, filter? }` — `refresh` fuerza un reescaneo ya | `{ version, count, applications: [{ id, name, source }] }` con `source` = `startmenu` \| `registry` \| `uwp`, ordenadas por nombre |
| `watch` | subscribe | — | La misma forma que `list`, al suscribirse y **cada vez que cambia el catálogo** (se instala o desinstala algo) |
| `launch` | request | `{ id }` (el de `list`/`watch`) | `{ launched, id, source }`. Solo acepta ids del catálogo |

El catálogo se mantiene solo: vigila las carpetas del menú Inicio (con 3 s de
espera para agrupar las ráfagas de un instalador) y reescanea todo cada 10 min
para lo que no deja acceso directo. `version` sube con cada cambio.

### `appicons`

Dominio aparte para que cargar iconos no retrase un `launch` (cada dominio tiene su cola).

| Action | Params | Data |
|---|---|---|
| `get` | `{ ids: [...] }` (máx. 50) | `{ icons: { <id>: base64 PNG 64×64 con transparencia \| null } }`. Los iconos se guardan en memoria en el agente |

### `processes`

| Action | Params | Data | Destructivo |
|---|---|---|---|
| `list` | `{ limit?: 1–500 (50), filter? }` — ordenados por memoria | `{ count, processes: [{ pid, name, workingMB, threads, startTime }] }` | — |
| `kill` | `{ pid }` — mata el árbol entero; rechaza procesos del sistema y el propio agente | `{ killed, name }` | ✅ |

### `windows`

| Action | Params | Data |
|---|---|---|
| `list` | — | `{ windows: [{ hwnd, title, pid, process, minimized, maximized, x, y, width, height }] }` (solo visibles y con título) |
| `focus` | `{ hwnd }` — restaura si está minimizada | `{ ok }` |
| `minimize` / `maximize` / `restore` | `{ hwnd }` | `{ ok }` |
| `close` | `{ hwnd }` — envía `WM_CLOSE` sin esperar; la app puede preguntar antes de cerrar | `{ ok }` |

### `media`

| Action | Params | Data |
|---|---|---|
| `play` / `pause` / `playPause` / `next` / `previous` | — | `{ ok, source }`; `NOT_FOUND` si no hay sesión multimedia |
| `nowPlaying` | — como **request** | `{ active, source, title, artist, album, status }` |
| `nowPlaying` | — como **subscribe** | `{ active, source, title, artist, album, status, volume, mute, trackChanged, artworkBase64 }` al suscribirse y cada vez que algo cambia (se comprueba cada segundo). La carátula solo viaja cuando cambia la pista (`trackChanged: true`); si no, `artworkBase64` es null y el cliente conserva la que tenía. Máx. 512 KB |
| `volumeGet` | — | `{ volume: 0–100, mute }` |
| `volumeSet` | `{ volume: 0–100 }` | `{ volume }` |
| `volumeMute` | `{ mute?: bool }` — sin parámetro alterna | `{ mute }` |
