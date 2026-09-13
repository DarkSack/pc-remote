# Pairing y autenticación

## Objetivo

Impedir que cualquier dispositivo de la red LAN pueda ejecutar comandos.
La primera vez el usuario aprueba explícitamente en la PC (mostrando un
código de 6 dígitos). Después, cada dispositivo se identifica con su
propia llave criptográfica.

## Diseño

- **Ed25519** para firmas (no RSA, no JWT) — pequeño, rápido, seguro.
- **Certificate pinning** para TLS — un cert autofirmado por dispositivo.
- **Sin secrets compartidos** — cada dispositivo tiene su keypair.
- **Revocación por dispositivo** desde el tray.

## Flujo de pairing (una vez por dispositivo)

```
┌─────── ANDROID ───────┐              ┌──────── AGENT ────────┐
│                       │              │                       │
│ 1. Discovery mDNS     │──────────────►│ Publicando servicio  │
│                       │              │                       │
│ 2. Handshake TLS      │══════════════►│ Ofrece cert self-sgn │
│    (guarda finger-    │◄══════════════│                      │
│     print SHA-256)    │              │                       │
│                       │              │                       │
│ 3. { kind:"pair_init" }────────────►│                      │
│                       │              │ 4. Genera código      │
│                       │              │    6 dígitos          │
│                       │              │    Muestra en tray +  │
│                       │              │    notification       │
│                       │              │                       │
│ 5. Usuario ve código  │              │                       │
│    y lo escribe       │              │                       │
│                       │              │                       │
│ 6. { pair_confirm,    │              │                       │
│      code: "742918",  │              │                       │
│      deviceName,      │───────────►│                      │
│      publicKey }      │              │ 7. Valida código      │
│                       │              │    (rate limit 3 int, │
│                       │              │     lockout 5min)     │
│                       │              │    Guarda device en DB│
│                       │              │                       │
│ 8. { deviceId,        │◄──────────│                      │
│      agentPublicKey,  │              │                       │
│      certFingerprint }│              │                       │
│                       │              │                       │
│ 9. Guarda en Android  │              │                       │
│    Keystore:          │              │                       │
│    - deviceId         │              │                       │
│    - privateKey       │              │                       │
│    - certFingerprint  │              │                       │
└───────────────────────┘              └───────────────────────┘
```

## Flujo de autenticación (cada reconexión)

```
1. Cliente abre wss://<host>:47820/ws
2. Valida certificate fingerprint contra el pinneado
   → si NO coincide: abort, alerta "¿Reinstalaste el agente?"
3. Agente envía: { kind: "auth_challenge", nonce: <32 bytes random hex> }
4. Cliente responde: { kind: "auth",
                        deviceId,
                        signature: Ed25519_sign(privateKey, nonce) }
5. Agente valida firma con publicKey almacenada
   → OK: sesión creada { sessionId, expiresAt: now + 24h }
   → FAIL o device revocado: { NOT_AUTHENTICATED } + cerrar socket
6. Cliente puede enviar comandos normales
```

## Revocación

Desde el tray → "Manage devices…", o desde el panel web (Revocar / Borrar).
Los dos pasan por `DeviceAdmin`:

1. `UPDATE devices SET revoked=1 WHERE id=?` (o `DELETE` si es borrar).
2. Termina sus sesiones: cualquier comando ya en cola comprueba la sesión
   antes de ejecutarse y no llega a correr.
3. Cierra sus sockets con código `4001 Device revoked`. La app no se
   reconecta al recibir 4001.

Pendiente: emitir `event { domain:"device", action:"revoked" }` a otras
sesiones.

## Almacenamiento

### Agente (SQLite, `agent.db`)

```sql
CREATE TABLE devices (
  id            TEXT PRIMARY KEY,              -- ULID
  name          TEXT NOT NULL,
  public_key    BLOB NOT NULL,                 -- 32 bytes Ed25519
  paired_at     INTEGER NOT NULL,
  last_seen_at  INTEGER,
  revoked       INTEGER NOT NULL DEFAULT 0
);
```

### Mobile (Android Keystore vía `expo-secure-store`)

- `deviceId` (por agente)
- `privateKey` (bytes crudos Ed25519, base64)
- `certFingerprint` (SHA-256 del cert del agente)

## Rate limiting

Lo que implementa el agente hoy:

- **Códigos ligados a quien los pide.** Un código pedido con `pair_init` solo
  vale desde esa misma IP; repetir `pair_init` reutiliza el código vivo (y solo
  vuelve a notificar pasados 15 s). Un código generado en el panel vale desde
  cualquier IP, pero solo hay uno a la vez. Como mucho hay 5 códigos vivos.
- **Bloqueo:** 3 códigos erróneos → la IP queda bloqueada 5 min, y pierde su
  código pendiente.
- **Antes de autenticar:** frames de 16 KB como máximo y 20 mensajes; después
  se cierra el socket. Un `auth` fallido también cierra (1008, o 4001 si el
  dispositivo está revocado). Cada challenge es de un solo uso.
- **Comandos:** cola por dominio de 256 peticiones; si se llena, el agente deja
  de leer del socket hasta que haya hueco. Frames de 4 MB como máximo y 16
  suscripciones por conexión.

## Panel web

Loopback, sin login, pero no abierto a cualquier web del navegador: exige
`Host` de loopback (DNS rebinding) y rechaza peticiones con
`Sec-Fetch-Site`/`Origin` de otro sitio (CSRF). `/ws` solo se sirve en el
puerto WSS.
