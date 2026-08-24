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

Desde el tray → "Manage devices…" → seleccionar → "Revoke".
El agente:

1. `UPDATE devices SET revoked=1 WHERE id=?`
2. Busca sesiones activas de ese `deviceId` y cierra los sockets con
   código `4001 Device revoked`.
3. Emite evento `event { domain:"device", action:"revoked" }` a otras
   sesiones (opcional, para auditoría en múltiples clientes).

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

- Pairing: 3 intentos de código antes de bloqueo de 5 min por IP+deviceName.
- Comandos: 100 requests/segundo por sesión.
- Auth challenge: 10 intentos/minuto por IP.
