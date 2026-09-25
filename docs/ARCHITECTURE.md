# Arquitectura

## Vista general

```
┌──────────────────────────────────────┐        ┌──────────────────────────────────────┐
│              ANDROID                 │        │             WINDOWS PC               │
│      Kotlin + Jetpack Compose        │        │        .NET 10 / C# / WinRT          │
│                                      │        │                                      │
│  Discovery (NsdManager, mDNS)        │◄── LAN ┼─► mDNS publisher                    │
│  AgentClient (OkHttp, reconexión)    │        │   Kestrel + WebSockets (wss)         │
│  Ed25519 challenge-response          │  wss   │   Emparejamiento + sesiones          │
│  Pinning SHA-256 del certificado     │◄═══════╪═► Colas por dominio → CommandRouter │
│  Credenciales: AES-GCM con clave     │        │   SQLite (dispositivos)              │
│  en Android Keystore                 │        │                                      │
└──────────────────────────────────────┘        │   ┌────────────────────────────┐    │
                                                │   │      ICommandModule[]      │    │
                                                │   └──────────────┬─────────────┘    │
                                                │   System  SystemInfo  Input  Media   │
                                                │   Clipboard  Windows  Processes Apps │
                                                │                                      │
                                                │   Bandeja (WinForms NotifyIcon)      │
                                                │   Panel web (localhost, HTTP)        │
                                                └──────────────────────────────────────┘
```

## Componentes

### Agente (`agent/`)

- **PcRemote.Agent** — punto de entrada (`PcRemote.exe`, un solo archivo
  autocontenido). Una única instancia, configuración por defecto embebida
  (`Config/appsettings.defaults.json`, con `appsettings.json` opcionales al
  lado del exe o en `%LOCALAPPDATA%\PcRemote\`), Serilog, host y bandeja
  (Iniciar con Windows). Nombra explícitamente los módulos integrados: dentro
  del exe no hay DLL que escanear.
- **PcRemote.Core**
  - `Server/WebSocketServer` — Kestrel con dos puertos: WSS en la LAN para el
    móvil y HTTP en loopback para el panel.
  - `Auth/` — `PairingService` (códigos), `SessionManager`, `DeviceRepository`
    (SQLite), `DeviceAdmin` (revocar/borrar y cortar la conexión viva).
  - `Router/` — `CommandRouter` e interfaces `ICommandModule` / `IStreamModule`
    / `IPluginMetadata`.
  - `Plugins/` — `PluginManager` (activado o no, por dominio; el router
    responde `PLUGIN_DISABLED`), carga de DLL externas y el dominio `plugins`.
  - `Activity/` — eventos (sesiones, emparejamientos, alertas) y el dominio
    `activity`, que los mezcla con la auditoría.
  - `Panel/` — endpoints y HTML del panel, *ring buffer* de logs.
  - `Discovery/` — publicación mDNS y elección de la IP de la LAN.
  - `Security/` — certificado autofirmado (se genera una vez y se reutiliza).
  - `Storage/` — SQLite; `CommandAuditLog` escribe `command_log` en lotes desde
    una cola, para que auditar nunca frene un comando.
- **PcRemote.Modules.\*** — implementaciones de `ICommandModule`: System,
  SystemInfo (con un `HardwareSampler` compartido), Input, Clipboard (un hilo
  STA que lee cada cambio una vez y alimenta `watch` y el historial),
  Applications, Processes, Windows, Media, Terminal, Files y Network.

### Android (`android/`)

- `net/AgentClient` — conexión WSS, autenticación, peticiones, streams
  (`stream()` se re-suscribe tras cada reconexión), reconexión con backoff,
  `restart()` para descartar un socket zombi y `ping()`.
  `ConnectionProblem` traduce los errores a mensajes para personas.
  `PairingClient` para el primer emparejamiento.
- `session/PcSession` — todo lo de un PC abierto: conexión, info, métricas y
  su historial, latencia, plugins; ciclo de vida (primer plano / segundo
  plano) y búsqueda por mDNS si cambió de IP. Vive en `PcViewModel`.
- `net/Discovery` — mDNS con `NsdManager`.
- `net/Crypto` — Ed25519 con BouncyCastle.
- `data/CredentialsStore` — credenciales por PC.
- `net/QrPayload`, `net/WakeOnLan` — QR del panel y magic packet.
- `data/SettingsStore` — preferencias de la app (tema, háptica, bloqueo…).
- `ui/` — Compose + Material 3. `theme/` (paleta, tipografía, colores
  extendidos), `components/` (PcStatusCard, SystemMetricCard, gráficas en
  Canvas, estados vacío/error/skeleton, háptica), `devices/` (equipos y
  emparejamiento), `pc/` (Inicio, Control, Apps, Actividad, Ajustes y las
  herramientas) con `NavigationSuiteScaffold`: barra en móvil, rail en
  tablet. Todas las pantallas de un PC comparten **una sola conexión**.

## Extensibilidad

```csharp
public interface ICommandModule {
    string Domain { get; }                    // ej. "system"
    IReadOnlyList<CommandDescriptor> Commands { get; }
    Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct);
}

public interface IStreamModule {              // opcional, para subscribe
    IReadOnlySet<string> StreamActions { get; }
    IAsyncEnumerable<object> StartStreamAsync(string action, JsonElement? parameters,
                                              ClientSession session, CancellationToken ct);
}
```

`AgentHost` carga todos los `PcRemote.Modules.*.dll` junto al ejecutable y
registra cada tipo que implemente `ICommandModule`. Un dominio nuevo es un
proyecto nuevo referenciado desde `PcRemote.Agent.csproj`; Core no cambia.

Un handler no debería lanzar excepciones por fallos esperados: devuelve
`CommandResponse.Fail(...)`. Si lanza, `CommandResponse.FromException`
traduce los errores de lectura de parámetros a `INVALID_PARAMS` y el resto a
`INTERNAL_ERROR`.

## Concurrencia, por conexión

1. **Un bucle de lectura** recibe los frames en orden.
2. Cada `request` va a la **cola de su dominio** (256 plazas). Una tarea por
   dominio las procesa de una en una: el orden dentro de un dominio se
   respeta y los dominios corren en paralelo. Si una cola se llena, el bucle
   deja de leer hasta que haya hueco.
3. Antes de ejecutar cada comando se comprueba que la **sesión sigue viva**:
   una revocación no deja pasar lo que ya estaba en cola.
4. **Todos los envíos** pasan por `TrackedConnection.SendAsync`, que los
   serializa.

## Seguridad — capas

1. **Transporte**: `wss://` con certificado autofirmado. El móvil fija
   (*pins*) su SHA-256 al emparejar y rechaza cualquier otro después.
2. **Identidad**: una clave Ed25519 por dispositivo. El agente guarda la
   pública; la privada no sale del móvil.
3. **Autenticación**: challenge-response de un solo uso en cada conexión.
4. **Autorización**: rol único (dispositivo emparejado). Revocación desde la
   bandeja o el panel, efectiva al instante (cierre 4001).
5. **Validación**: cada handler valida sus parámetros. Ningún comando ejecuta
   texto arbitrario.
6. **Límites**: códigos de emparejamiento ligados a la IP, bloqueo tras 3
   fallos, tamaño y número de mensajes antes de autenticar. Ver
   [`PAIRING.md`](PAIRING.md).
7. **Panel web**: solo loopback, con comprobación de `Host` (DNS rebinding),
   de `Sec-Fetch-Site`/`Origin` (CSRF), CSP y todo el contenido escapado.

## Elevación

- El agente corre como el usuario (`asInvoker`).
- Nada de lo implementado requiere administrador. Matar procesos de otros
  usuarios o del sistema simplemente falla.
- Idea para más adelante: un helper `PcRemote.Elevated.exe` lanzado con
  `runas` (UAC visible) para operaciones de administrador. No existe todavía.

## Reconexión (Android)

```
DISCONNECTED → CONNECTING → AUTHENTICATING → CONNECTED
                   ↑                              ↓ (caída)
                   └──────── RECONNECTING ◄───────┘

FAILED (sin reintentos): auth rechazada, dispositivo revocado (4001) o
certificado distinto del emparejado.
```

Backoff: `1 → 2 → 4 → 8 → 15 → 20 s (tope)`. OkHttp envía ping cada 10 s.

Al volver a la app (`PcSession.onForeground`), según el tiempo fuera medido
con `elapsedRealtime` (Android puede congelar la app y el temporizador de
corte de 30 s no llega a saltar):

- ≥ 30 s → `restart()`: el socket no es de fiar, se abre uno nuevo ya.
- ≥ 5 s → `ping()`; si no vuelve en 2,5 s, `restart()`.
- `RECONNECTING` → reintento inmediato; `DISCONNECTED` → `connect()`.

Con la app abierta, un `ping.ping` cada 5 s mide la latencia; dos perdidos
seguidos → `restart()`. Tras dos fallos seguidos se busca el PC por mDNS (por
la huella del certificado) y, si cambió de IP, se guarda la nueva.

## Tipos de mensaje

Ver [`PROTOCOL.md`](PROTOCOL.md):

- **request/response** — comandos únicos (`system.shutdown`).
- **subscribe/stream/unsubscribe** — datos continuos (`systeminfo.stats`, `clipboard.watch`).
- **event** — reservado, sin uso todavía.
