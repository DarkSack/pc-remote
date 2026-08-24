# Protocolo WebSocket

Transporte: `wss://<host>:47820/ws` (puerto configurable en `appsettings.json`).
Formato: JSON UTF-8, un mensaje por frame WebSocket. `id` en formato ULID.

## Envelope común

Todo mensaje lleva `kind` y `ts` (Unix ms). El resto depende del `kind`.

## 1 · Request / Response

### Cliente → Agente

```jsonc
{
  "kind": "request",
  "id": "01HFZ1V9YKN6M5",
  "domain": "system",
  "action": "shutdown",
  "params": { "force": false, "timeoutSec": 30 },
  "ts": 1787500000
}
```

### Agente → Cliente (éxito)

```jsonc
{
  "kind": "response",
  "id": "01HFZ1V9YKN6M5",
  "success": true,
  "data": { "willShutdownIn": 30 },
  "ts": 1787500000
}
```

### Agente → Cliente (error)

```jsonc
{
  "kind": "response",
  "id": "01HFZ1V9YKN6M5",
  "success": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "The operation requires elevated privileges.",
    "recoverable": false
  },
  "ts": 1787500000
}
```

## 2 · Subscribe / Stream / Unsubscribe

Para datos periódicos (stats en tiempo real, cambios de clipboard, etc.).

```jsonc
// Cliente inicia
{ "kind":"subscribe", "id":"sub_stats_1", "domain":"systeminfo", "action":"stats",
  "params": { "intervalMs": 1000 } }

// Agente confirma
{ "kind":"response", "id":"sub_stats_1", "success":true, "ts":... }

// Agente empieza a mandar N mensajes con mismo id
{ "kind":"stream", "id":"sub_stats_1",
  "data": { "cpu": 23, "ram": 48, "gpu": 31, "netIn": 512000, "netOut": 32000 } }

// Cliente para
{ "kind":"unsubscribe", "id":"sub_stats_1" }
```

## 3 · Event (push del agente)

Sin request previo. El agente emite el evento cuando quiere.

```jsonc
{ "kind":"event", "domain":"clipboard", "action":"changed",
  "data": { "text": "...", "size": 123 } }
```

## 4 · Ping / Pong

Cliente envía ping cada 15 s. Timeout 5 s → cerrar y reconectar.

```jsonc
{ "kind":"ping", "ts": 1787500000 }
{ "kind":"pong", "ts": 1787500000 }
```

## 5 · Bootstrap (mensajes de pairing / auth)

Ver [`PAIRING.md`](PAIRING.md) para el flujo completo. Los 3 kinds:

- `pair_init` — cliente solicita empezar pairing.
- `pair_confirm` — cliente envía código introducido por el usuario + su
  public key Ed25519.
- `auth` — cada reconexión: cliente envía firma de challenge.

## Códigos de error

| Código                | Semántica |
|-----------------------|-----------|
| `PERMISSION_DENIED`   | Requiere elevación o el usuario denegó UAC. |
| `NOT_AUTHENTICATED`   | Sesión inválida o expirada. Cliente debe re-auth. |
| `INVALID_COMMAND`     | Domain/action desconocido. |
| `INVALID_PARAMS`      | Params no cumplen esquema. |
| `NOT_FOUND`           | Recurso no existe (PID, ventana, app). |
| `TIMEOUT`             | El handler tardó más del máximo permitido. |
| `INTERNAL_ERROR`      | Bug o excepción inesperada. Ver `command_log`. |
| `RATE_LIMITED`        | Demasiados comandos en poco tiempo. |

`recoverable: true` significa que el cliente puede reintentar (ej. TIMEOUT).
`false` = reintentar no ayuda.

## Comandos por dominio (referencia)

### `system`

| Action     | Params                                | Data         | Destructivo |
|------------|---------------------------------------|--------------|-------------|
| `shutdown` | `{ force?: bool, timeoutSec?: int }`  | `{ willShutdownIn }` | ✅ |
| `restart`  | idem                                  | idem         | ✅ |
| `sleep`    | —                                     | —            | ❌ |
| `hibernate`| —                                     | —            | ❌ |
| `lock`     | —                                     | —            | ❌ |
| `logoff`   | `{ force?: bool }`                    | —            | ✅ |

### `systeminfo`

| Action  | Kind         | Params                    | Data |
|---------|--------------|---------------------------|------|
| `info`  | request      | —                         | `{ hostname, os, cpuModel, ramTotalMB, gpuModel, uptimeSec }` |
| `stats` | subscribe    | `{ intervalMs }`          | `{ cpu%, ram%, gpu%, netInBps, netOutBps }` |

### `input`

| Action        | Kind    | Params |
|---------------|---------|--------|
| `mouseMove`   | request | `{ dx: int, dy: int }` (relativo) |
| `mouseClick`  | request | `{ button: "left"|"right"|"middle", double?: bool }` |
| `mouseScroll` | request | `{ dy: int }` |
| `mouseDown`   | request | `{ button }` |
| `mouseUp`     | request | `{ button }` |
| `keyPress`    | request | `{ vk: number, modifiers?: string[] }` (`"ctrl"`, `"shift"`, `"alt"`, `"win"`) |
| `keyType`     | request | `{ text: string }` |

### `clipboard`

| Action | Kind      | Params                     | Data |
|--------|-----------|----------------------------|------|
| `get`  | request   | —                          | `{ text }` |
| `set`  | request   | `{ text }`                 | — |
| `watch`| subscribe | —                          | `{ text }` cada vez que cambia |

### `applications`

| Action   | Kind    | Params                            | Data |
|----------|---------|-----------------------------------|------|
| `list`   | request | `{ favoritesOnly?: bool }`        | `{ apps: [{ id, name, iconBase64 }] }` |
| `launch` | request | `{ id }`                          | — |
| `kill`   | request | `{ id }`                          | — |

### `processes`

| Action  | Kind    | Params                                | Data |
|---------|---------|---------------------------------------|------|
| `list`  | request | `{ sortBy?: "cpu"|"memory", limit?: int }` | `{ processes: [{ pid, name, cpu%, memMB }] }` |
| `kill`  | request | `{ pid, force?: bool }`               | — |

### `windows`

| Action     | Kind    | Params                                                | Data |
|------------|---------|-------------------------------------------------------|------|
| `list`     | request | —                                                     | `{ windows: [{ hwnd, title, pid, state }] }` |
| `focus`    | request | `{ hwnd }`                                            | — |
| `minimize` | request | `{ hwnd }`                                            | — |
| `maximize` | request | `{ hwnd }`                                            | — |
| `restore`  | request | `{ hwnd }`                                            | — |
| `close`    | request | `{ hwnd }`                                            | — |

### `media`

| Action      | Kind    | Params            | Data                          |
|-------------|---------|-------------------|-------------------------------|
| `play`      | request | —                 | — |
| `pause`     | request | —                 | — |
| `next`      | request | —                 | — |
| `previous`  | request | —                 | — |
| `volumeGet` | request | —                 | `{ volume: 0-100, muted }`     |
| `volumeSet` | request | `{ volume 0-100 }`| — |
| `mute`      | request | `{ on: bool }`    | — |
| `nowPlaying`| subscribe| —                | `{ title, artist, artworkBase64, isPlaying }` |
