# Arquitectura

## Vista general

```
┌──────────────────────────────────────┐        ┌──────────────────────────────────────┐
│              ANDROID                 │        │             WINDOWS PC               │
│   React Native + Expo + TypeScript   │        │         .NET 8 / C# / WinRT          │
│                                      │        │                                      │
│  Discovery (mDNS + UDP fallback)     │◄── LAN ┼─► mDNS Publisher                    │
│  Connection FSM                      │        │   Kestrel + WebSockets (wss)         │
│  Ed25519 challenge-response          │  wss   │   Session Manager                    │
│  zustand stores                      │◄═══════╪═► Command Router                    │
│  expo-router views                   │        │   Audit log (SQLite)                 │
│  expo-secure-store (private key)     │        │                                      │
└──────────────────────────────────────┘        │   ┌────────────────────────────┐    │
                                                │   │      ICommandModule[]      │    │
                                                │   └──────────────┬─────────────┘    │
                                                │   ▼   ▼   ▼   ▼   ▼   ▼   ▼   ▼    │
                                                │   System  Input  Clipboard  Apps    │
                                                │   SysInfo  Windows  Processes Media │
                                                │                                      │
                                                │   Tray UI (WinForms NotifyIcon)      │
                                                │   Elevation helper (on-demand UAC)   │
                                                └──────────────────────────────────────┘
```

## Componentes

### Agent (`agent/`)

- **PcRemote.Agent** — entrypoint. WinForms tray, DI/hosting, Serilog.
- **PcRemote.Core** — framework: WebSocket server (Kestrel), pairing, sesión,
  router, protocolo, discovery (mDNS), storage (SQLite).
- **PcRemote.Modules.\*** — implementaciones de `ICommandModule`. El router
  las descubre por reflection al arrancar. Añadir un módulo = añadir un
  `.csproj` que implemente la interfaz.

### Mobile (`mobile/`)

- **app/** — vistas con `expo-router` (file-based).
- **src/net/** — capa de red: discovery, connection FSM, protocolo tipado,
  crypto (Ed25519 con `@noble/ed25519`).
- **src/stores/** — zustand para estado global (dispositivos, conexión, stats).
- **src/storage/** — SQLite (agents cache) y `expo-secure-store`
  (private keys en Android Keystore).

## Extensibilidad

Cada módulo implementa:

```csharp
public interface ICommandModule {
    string Domain { get; }                    // ej. "system"
    IReadOnlyList<CommandDescriptor> Commands { get; }
    Task<CommandResponse> HandleAsync(
        CommandRequest req,
        ClientSession session,
        CancellationToken ct);
}
```

El `CommandRouter` mantiene un `Dictionary<string, ICommandModule>` populado
por reflection sobre los assemblies referenciados. Un nuevo dominio (ej.
`gaming`) = nuevo proyecto + `dotnet add reference`. Sin tocar Core.

## Seguridad — capas

1. **Transporte**: `wss://` con cert autofirmado por dispositivo. Cliente
   pinnea fingerprint SHA-256 tras el pairing.
2. **Identidad**: Ed25519 keypair por dispositivo. `deviceId` (ULID) +
   `publicKey` almacenados en el agente. Private key en Android Keystore.
3. **Autenticación**: challenge-response en cada reconexión. El agente
   envía 32 bytes random; el cliente firma con su private key.
4. **Autorización**: rol único ("paired device"). Revocación por
   `deviceId` desde el tray.
5. **Validación de comandos**: cada handler valida sus parámetros
   contra un esquema. Nunca se ejecuta un comando arbitrario (`system.*`
   no acepta strings crudos de shell).
6. **Rate limiting**: por sesión, tanto para pair attempts como para
   comandos de alto impacto.

## Elevación

- Agente corre como usuario logeado (`asInvoker` en el manifest).
- Operaciones que requieren admin (kill de proceso de sistema, servicios,
  registry HKLM en apps) delegan a un helper `PcRemote.Elevated.exe`
  lanzado con `runas` → UAC visible.
- No hay servicio Windows persistente elevado.

## Reconexión

Máquina de estados en el mobile:

```
DISCONNECTED → DISCOVERING → PAIRING → CONNECTING → CONNECTED
                                             ↓
                                       RECONNECTING → CONNECTED
                                             ↓
                                          FAILED
```

Backoff exponencial: `1 → 2 → 4 → 8 → 16 → 30s (tope)`.
Ping cada 15 s; timeout 5 s → RECONNECTING.

## Streaming vs request/response

Ver [`PROTOCOL.md`](PROTOCOL.md) — hay tres tipos de mensaje:

- **request/response** — comandos únicos (`system.shutdown`).
- **subscribe/stream/unsubscribe** — datos periódicos (`systeminfo.stats`).
- **event** — push del agente sin request previo (`clipboard.changed`).
