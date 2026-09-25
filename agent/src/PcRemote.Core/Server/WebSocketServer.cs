using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Activity;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Panel;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Core.Security;
using Serilog;

namespace PcRemote.Core.Server;

/// <summary>
/// Hosts the WebSocket server on Kestrel with WSS. Handles bootstrap
/// (pair_init / pair_confirm) and authenticated sessions (auth_challenge / auth)
/// then routes command requests through CommandRouter.
///
/// Concurrency model, per connection:
///   - ONE receive loop reads frames in order.
///   - Requests go to a lane per domain: commands of the same domain keep their
///     order (mouse moves must not overtake each other), different domains run
///     in parallel (a slow <c>applications.list</c> no longer freezes the mouse).
///   - Every write goes through <see cref="TrackedConnection.SendAsync"/>, which
///     serialises sends, as the WebSocket contract requires.
/// </summary>
public sealed class WebSocketServer : IHostedService, IAsyncDisposable
{
    /// <summary>Largest frame accepted before authenticating. Bootstrap messages are tiny.</summary>
    private const int MaxUnauthenticatedMessageBytes = 16 * 1024;

    /// <summary>
    /// Largest frame once authenticated. clipboard.set allows 1M chars (up to ~3 MB
    /// of UTF-8 + escaping); clipboard.setImage carries up to 14 MB of base64.
    /// </summary>
    private const int MaxMessageBytes = 16 * 1024 * 1024;

    /// <summary>Messages tolerated without authenticating before the socket is dropped.</summary>
    private const int MaxUnauthenticatedMessages = 20;

    /// <summary>Queued requests per domain; beyond this the receive loop waits (backpressure).</summary>
    private const int LaneCapacity = 256;

    private const int MaxSubscriptionsPerConnection = 16;

    private readonly AgentSettings     _settings;
    private readonly X509Certificate2  _certificate;
    private readonly ConnectionManager _connections;
    private readonly PairingService    _pairing;
    private readonly SessionManager    _sessions;
    private readonly DeviceRepository  _devices;
    private readonly DeviceAdmin       _deviceAdmin;
    private readonly CommandRouter     _router;
    private readonly PcRemote.Core.Storage.CommandAuditLog _audit;
    private readonly ActivityLog _activity;
    private readonly PcRemote.Core.Plugins.PluginManager _plugins;
    private readonly ILogger<WebSocketServer> _logger;

    private WebApplication? _app;

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    public WebSocketServer(
        AgentSettings settings,
        X509Certificate2 certificate,
        ConnectionManager connections,
        PairingService pairing,
        SessionManager sessions,
        DeviceRepository devices,
        DeviceAdmin deviceAdmin,
        CommandRouter router,
        PcRemote.Core.Storage.CommandAuditLog audit,
        ActivityLog activity,
        PcRemote.Core.Plugins.PluginManager plugins,
        ILogger<WebSocketServer> logger)
    {
        _settings    = settings;
        _certificate = certificate;
        _connections = connections;
        _pairing     = pairing;
        _sessions    = sessions;
        _devices     = devices;
        _deviceAdmin = deviceAdmin;
        _router      = router;
        _audit       = audit;
        _activity    = activity;
        _plugins     = plugins;
        _logger      = logger;
    }

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateBuilder();

        // This is a second host inside the agent. Without this its logs (Kestrel,
        // ASP.NET) went to the default console provider, which a WinExe never shows.
        builder.Logging.ClearProviders();
        builder.Logging.AddSerilog(Log.Logger, dispose: false);

        builder.WebHost.ConfigureKestrel(kestrel =>
        {
            var bindAddress = _settings.WebSocket.BindAddress == "0.0.0.0"
                ? IPAddress.Any
                : IPAddress.Parse(_settings.WebSocket.BindAddress);

            // Puerto WSS público (protocolo del móvil).
            kestrel.Listen(bindAddress, _settings.WebSocket.Port, listen =>
            {
                listen.UseHttps(new HttpsConnectionAdapterOptions
                {
                    ServerCertificate = _certificate,
                });
            });

            // Panel web: HTTP loopback-only, nunca accesible desde la red.
            if (_settings.Panel.Enabled)
            {
                kestrel.Listen(IPAddress.Loopback, _settings.Panel.Port);
            }
        });

        // El panel necesita algunos servicios en DI para sus endpoints.
        builder.Services.AddSingleton(_settings);
        builder.Services.AddSingleton(_certificate);
        builder.Services.AddSingleton(_connections);
        builder.Services.AddSingleton(_pairing);
        builder.Services.AddSingleton(_devices);
        builder.Services.AddSingleton(_deviceAdmin);
        builder.Services.AddSingleton(_audit);
        builder.Services.AddSingleton(_plugins);
        builder.Services.AddSingleton(_activity);

        _app = builder.Build();

        if (_settings.Panel.Enabled)
        {
            _app.UsePanelGuard(_settings.Panel.Port);
        }

        _app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(_settings.Session.PingIntervalSeconds),
        });

        _app.Map("/ws", HandleWebSocket);

        if (_settings.Panel.Enabled)
        {
            _app.MapPanel(_settings.Panel.Port);
        }
        else
        {
            _app.Map("/", () => Results.Text($"PC Remote agent · v{GetVersion()}", "text/plain"));
        }

        await _app.StartAsync(cancellationToken);

        _logger.LogInformation(
            "WebSocket server listening on wss://{Address}:{Port}/ws",
            _settings.WebSocket.BindAddress,
            _settings.WebSocket.Port);

        if (_settings.Panel.Enabled)
        {
            _logger.LogInformation("Panel web available at http://localhost:{Port}/", _settings.Panel.Port);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Connection lifecycle
    // ══════════════════════════════════════════════════════════════

    /// <summary>Everything that belongs to one socket.</summary>
    private sealed class Connection(TrackedConnection tracked)
    {
        public TrackedConnection Tracked { get; } = tracked;
        public string ClientIp => Tracked.ClientIp;

        public ClientSession? Session;
        public byte[]? PendingNonce;
        public int UnauthenticatedMessages;

        public readonly ConcurrentDictionary<string, CancellationTokenSource> Subscriptions = new();

        // Only touched by the receive loop, so no locking.
        public readonly Dictionary<string, Channel<CommandRequest>> Lanes = new(StringComparer.OrdinalIgnoreCase);
        public readonly List<Task> Workers = new();
    }

    private async Task HandleWebSocket(HttpContext ctx)
    {
        // The route is mapped on the app, so it would also answer on the loopback
        // panel port — as plain ws:// that any web page in the browser may open.
        // The phone protocol lives on the TLS port only.
        if (ctx.Connection.LocalPort != _settings.WebSocket.Port)
        {
            ctx.Response.StatusCode = StatusCodes.Status404NotFound;
            return;
        }

        if (!ctx.WebSockets.IsWebSocketRequest)
        {
            ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        var socket   = await ctx.WebSockets.AcceptWebSocketAsync();
        var clientIp = ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        var tracked  = _connections.Register(socket, clientIp);
        var conn     = new Connection(tracked);
        _logger.LogInformation("Client connected from {Ip} (id={Id})", clientIp, tracked.Id);

        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted, tracked.Closing);
        _ = CloseIfNotAuthenticatedAsync(conn, lifetime.Token);
        try
        {
            await HandleMessagesAsync(conn, lifetime.Token);
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException)
        {
            _logger.LogInformation("Client {Ip} disconnected: {Message}", clientIp, ex.Message);
        }
        finally
        {
            lifetime.Cancel();

            foreach (var lane in conn.Lanes.Values) lane.Writer.TryComplete();
            // Quietly: a stream that finished may already have disposed its CTS, and a
            // throw here would skip the session and connection cleanup below.
            foreach (var kv in conn.Subscriptions) CancelQuietly(kv.Value);
            try { await Task.WhenAll(conn.Workers); } catch { /* already logged per request */ }

            if (conn.Session is not null)
            {
                _sessions.End(conn.Session.SessionId);
                foreach (var aware in _router.Modules.Values.OfType<ISessionAware>())
                {
                    try { aware.OnSessionEnded(conn.Session); }
                    catch (Exception ex) { _logger.LogWarning(ex, "{Module} failed to clean up a session", aware.GetType().Name); }
                }
                _logger.LogInformation("Session {Session} ended", Short(conn.Session.SessionId));
                _activity.Add(ActivityKinds.Session, "Dispositivo desconectado", conn.ClientIp,
                    device: conn.Session.DeviceName);
            }
            _connections.Unregister(tracked.Id);
        }
    }

    /// <summary>
    /// A socket that never authenticates is dropped. Without this, any host on the
    /// LAN could open connections and leave them idle forever, each holding a
    /// socket and a buffer. The window covers manual pairing: the user has to read
    /// the code on the PC and type it, which the code TTL already bounds.
    /// </summary>
    private async Task CloseIfNotAuthenticatedAsync(Connection conn, CancellationToken ct)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(_settings.Pairing.CodeTtlSeconds + 60), ct);
            if (conn.Session is null)
            {
                _logger.LogInformation("Closing {Ip}: not authenticated in time", conn.ClientIp);
                await conn.Tracked.CloseAsync(WebSocketCloseStatus.PolicyViolation, "Authentication timeout");
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task HandleMessagesAsync(Connection conn, CancellationToken ct)
    {
        var socket = conn.Tracked.Socket;
        var buffer = new byte[8 * 1024];

        while (socket.State == WebSocketState.Open)
        {
            var limit = conn.Session is null ? MaxUnauthenticatedMessageBytes : MaxMessageBytes;
            var raw = await ReceiveTextAsync(conn.Tracked, buffer, limit, ct);
            if (raw is null) break;

            if (conn.Session is null && ++conn.UnauthenticatedMessages > MaxUnauthenticatedMessages)
            {
                _logger.LogWarning("Dropping {Ip}: too many messages without authenticating", conn.ClientIp);
                await conn.Tracked.CloseAsync(WebSocketCloseStatus.PolicyViolation, "Authenticate first");
                break;
            }

            try
            {
                await HandleMessageAsync(conn, raw, ct);
            }
            catch (JsonException ex)
            {
                // Wrong types in an otherwise valid JSON (e.g. "code": 123) used to
                // escape the loop and drop the connection without a word.
                _logger.LogWarning("Malformed message from {Ip}: {Msg}", conn.ClientIp, ex.Message);
            }
        }
    }

    private async Task HandleMessageAsync(Connection conn, string raw, CancellationToken ct)
    {
        var header = JsonSerializer.Deserialize<MessageHeader>(raw, JsonOpts);
        if (header?.Kind is null) return;

        switch (header.Kind)
        {
            case MessageKinds.PairInit:
                await OnPairInit(conn, ct);
                break;

            case MessageKinds.PairConfirm:
                await OnPairConfirm(conn, raw, ct);
                break;

            case MessageKinds.Auth:
                await OnAuth(conn, raw, ct);
                break;

            case MessageKinds.Ping:
                await SendAsync(conn, new { kind = MessageKinds.Pong, ts = Now() }, ct);
                break;

            case MessageKinds.Request:
                if (conn.Session is null) { await ChallengeAsync(conn, ct); return; }
                await OnRequest(conn, raw, ct);
                break;

            case MessageKinds.Subscribe:
                if (conn.Session is null) { await ChallengeAsync(conn, ct); return; }
                await OnSubscribe(conn, raw, ct);
                break;

            case MessageKinds.Unsubscribe:
                if (conn.Session is null) return;
                OnUnsubscribe(conn, header.Id);
                break;

            default:
                if (conn.Session is null)
                    await ChallengeAsync(conn, ct);
                else
                    _logger.LogDebug("Ignoring unknown kind '{Kind}' from session {Sess}",
                        header.Kind, Short(conn.Session.SessionId));
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Pairing (bootstrap)
    // ══════════════════════════════════════════════════════════════
    private async Task OnPairInit(Connection conn, CancellationToken ct)
    {
        switch (_pairing.RequestCode(conn.ClientIp))
        {
            case PairingRequestResult.Locked l:
                await SendAsync(conn, PairFail(ErrorCodes.RateLimited,
                    $"Too many failed attempts. Try again in {l.RemainingSeconds}s."), ct);
                return;

            case PairingRequestResult.Busy:
                await SendAsync(conn, PairFail(ErrorCodes.RateLimited,
                    "Too many pairings in progress. Try again in a couple of minutes."), ct);
                return;
        }

        // Ack: no code in it — the user reads the code on the PC.
        await SendAsync(conn, new
        {
            kind   = "pair_init_ack",
            ttlSec = _settings.Pairing.CodeTtlSeconds,
            ts     = Now(),
        }, ct);
    }

    private async Task OnPairConfirm(Connection conn, string raw, CancellationToken ct)
    {
        var msg = JsonSerializer.Deserialize<PairConfirmMessage>(raw, JsonOpts);
        if (msg is null || string.IsNullOrEmpty(msg.Code) || msg.PublicKey is null)
        {
            await SendAsync(conn, PairFail(ErrorCodes.InvalidParams, "Malformed pair_confirm"), ct);
            return;
        }

        // Validate the payload BEFORE consuming the code, so a malformed request
        // does not burn a code the user is still reading.
        var deviceName = SanitizeDeviceName(msg.DeviceName);
        if (deviceName is null)
        {
            await SendAsync(conn, PairFail(ErrorCodes.InvalidParams, "deviceName is required."), ct);
            return;
        }

        byte[] publicKey;
        try { publicKey = Convert.FromBase64String(msg.PublicKey); }
        catch (FormatException)
        {
            await SendAsync(conn, PairFail(ErrorCodes.InvalidParams, "publicKey must be base64."), ct);
            return;
        }

        if (publicKey.Length != 32)
        {
            await SendAsync(conn, PairFail(ErrorCodes.InvalidParams, "publicKey must be 32 bytes (Ed25519)."), ct);
            return;
        }

        switch (_pairing.Validate(msg.Code, conn.ClientIp))
        {
            case PairingValidationResult.Locked l:
                await SendAsync(conn, PairFail(ErrorCodes.RateLimited,
                    $"Too many failed attempts. Try again in {l.RemainingSeconds}s."), ct);
                return;

            case PairingValidationResult.InvalidCode:
                await SendAsync(conn, PairFail(ErrorCodes.PairingFailed, "Invalid or expired code."), ct);
                return;
        }

        var deviceId = GenerateDeviceId();
        var device = new Device(deviceId, deviceName, publicKey, DateTimeOffset.UtcNow, null, false);
        _devices.Insert(device);

        _logger.LogInformation("Paired new device: {Name} ({Id}) from {Ip}", device.Name, device.Id, conn.ClientIp);
        _activity.Add(ActivityKinds.Pairing, "Nuevo dispositivo emparejado", conn.ClientIp, device: device.Name);

        await SendAsync(conn, new PairResultMessage(
            Kind:            MessageKinds.PairResult,
            Success:         true,
            DeviceId:        deviceId,
            AgentPublicKey:  null, // Fase 5+: si necesitamos que el cliente verifique al server
            CertFingerprint: CertificateProvider.GetFingerprint(_certificate),
            Error:           null), ct);
    }

    /// <summary>Trim, drop control characters, cap at 64. Null when nothing usable is left.</summary>
    private static string? SanitizeDeviceName(string? name)
    {
        if (name is null) return null;
        var clean = new string(name.Where(c => !char.IsControl(c)).ToArray()).Trim();
        if (clean.Length > 64) clean = clean[..64].TrimEnd();
        return clean.Length == 0 ? null : clean;
    }

    // ══════════════════════════════════════════════════════════════
    // Auth (each reconnect)
    // ══════════════════════════════════════════════════════════════

    /// <summary>
    /// Sends a challenge unless one is already pending. Re-issuing on every
    /// unauthenticated message would swap the nonce under a client that is
    /// signing the previous one, and its auth would then always fail.
    /// </summary>
    private async Task ChallengeAsync(Connection conn, CancellationToken ct)
    {
        if (conn.PendingNonce is not null) return;

        var nonce = RandomNumberGenerator.GetBytes(32);
        conn.PendingNonce = nonce;
        await SendAsync(conn, new AuthChallengeMessage(
            Kind:  MessageKinds.AuthChallenge,
            Nonce: Convert.ToHexString(nonce).ToLowerInvariant()), ct);
    }

    private async Task OnAuth(Connection conn, string raw, CancellationToken ct)
    {
        if (conn.Session is not null) return; // already authenticated; nothing to do

        // Single use: whatever happens below, this nonce is spent.
        var nonce = conn.PendingNonce;
        conn.PendingNonce = null;

        var msg = JsonSerializer.Deserialize<AuthMessage>(raw, JsonOpts);
        if (msg?.DeviceId is null || msg.Signature is null || nonce is null)
        {
            await RejectAuthAsync(conn, "Missing challenge or malformed auth.", ct);
            return;
        }

        var device = _devices.Get(msg.DeviceId);
        if (device is null || device.Revoked)
        {
            await RejectAuthAsync(conn, "Device not paired or revoked.", ct, DeviceAdmin.RevokedCloseStatus);
            return;
        }

        byte[] signature;
        try { signature = Convert.FromBase64String(msg.Signature); }
        catch (FormatException)
        {
            await RejectAuthAsync(conn, "Signature must be base64.", ct);
            return;
        }

        if (!Ed25519Signing.Verify(device.PublicKey, nonce, signature))
        {
            await RejectAuthAsync(conn, "Invalid signature.", ct);
            return;
        }

        // Claim the connection and the session BEFORE re-reading the device. A revoke
        // that landed after the check above used to find neither (no session yet, no
        // DeviceId on the socket) and the device authenticated anyway. DeviceAdmin
        // writes the row first and then scans sessions and sockets, so either that
        // scan sees what we just set, or the re-read below sees the revocation.
        conn.Tracked.DeviceId = device.Id;
        var session = _sessions.Create(device.Id, device.Name);
        if (_devices.Get(device.Id) is not { Revoked: false })
        {
            _sessions.End(session.SessionId);
            conn.Tracked.DeviceId = null;
            await RejectAuthAsync(conn, "Device not paired or revoked.", ct, DeviceAdmin.RevokedCloseStatus);
            return;
        }
        conn.Session = session;
        _devices.TouchLastSeen(device.Id);
        _logger.LogInformation("Authenticated {Name} from {Ip} → session {Sess}",
            device.Name, conn.ClientIp, Short(session.SessionId));
        _activity.Add(ActivityKinds.Session, "Dispositivo conectado", conn.ClientIp, device: device.Name);

        await SendAsync(conn, new AuthResultMessage(
            Kind:      MessageKinds.AuthResult,
            Success:   true,
            SessionId: session.SessionId,
            Error:     null), ct);
    }

    private async Task RejectAuthAsync(
        Connection conn, string message, CancellationToken ct,
        WebSocketCloseStatus close = WebSocketCloseStatus.PolicyViolation)
    {
        _logger.LogWarning("Auth rejected for {Ip}: {Reason}", conn.ClientIp, message);
        await SendAsync(conn, AuthFail(message), ct);
        await conn.Tracked.CloseAsync(close, "Authentication failed");
    }

    /// <summary>
    /// The session can be ended from outside (revoke from tray / panel) while the
    /// socket is still open. Checked before every command, not only at auth time.
    /// </summary>
    private bool SessionAlive(Connection conn) =>
        conn.Session is not null && _sessions.Get(conn.Session.SessionId) is not null;

    // ══════════════════════════════════════════════════════════════
    // Command dispatch
    // ══════════════════════════════════════════════════════════════
    private async Task OnRequest(Connection conn, string raw, CancellationToken ct)
    {
        var req = JsonSerializer.Deserialize<CommandRequest>(raw, JsonOpts);
        if (!IsWellFormed(req))
        {
            await SendAsync(conn, CommandResponse.Fail(req?.Id ?? "", ErrorCodes.InvalidParams,
                "Request needs id, domain and action."), ct);
            return;
        }

        // Unknown domains fail immediately; no lane (and no worker) for garbage.
        if (!_router.Modules.ContainsKey(req!.Domain))
        {
            await SendAsync(conn, CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                $"Unknown domain '{req.Domain}'."), ct);
            return;
        }

        if (!conn.Lanes.TryGetValue(req.Domain, out var lane))
        {
            lane = Channel.CreateBounded<CommandRequest>(new BoundedChannelOptions(LaneCapacity)
            {
                SingleReader = true,
                SingleWriter = true,
                FullMode     = BoundedChannelFullMode.Wait,
            });
            conn.Lanes[req.Domain] = lane;
            AddWorker(conn, Task.Run(() => RunLaneAsync(conn, lane.Reader, ct), CancellationToken.None));
        }

        await lane.Writer.WriteAsync(req, ct);
    }

    private async Task RunLaneAsync(Connection conn, ChannelReader<CommandRequest> reader, CancellationToken ct)
    {
        try
        {
            await foreach (var req in reader.ReadAllAsync(ct))
            {
                if (!SessionAlive(conn))
                {
                    await SendAsync(conn, CommandResponse.Fail(req.Id, ErrorCodes.NotAuthenticated,
                        "Session ended."), ct);
                    await conn.Tracked.CloseAsync(DeviceAdmin.RevokedCloseStatus, "Session ended");
                    return;
                }

                var response = await _router.DispatchAsync(req, conn.Session!, ct);
                await SendAsync(conn, response, ct);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or ObjectDisposedException)
        {
            // Connection going away; nothing left to answer to.
        }
    }

    /// <summary>Tracks a background task so disconnect can wait for it. Finished ones are dropped as we go.</summary>
    private static void AddWorker(Connection conn, Task worker)
    {
        conn.Workers.RemoveAll(t => t.IsCompleted);
        conn.Workers.Add(worker);
    }

    private static bool IsWellFormed(CommandRequest? req) =>
        req is not null &&
        !string.IsNullOrEmpty(req.Id) &&
        !string.IsNullOrEmpty(req.Domain) &&
        !string.IsNullOrEmpty(req.Action);

    // ══════════════════════════════════════════════════════════════
    // Streams (subscribe / unsubscribe)
    // ══════════════════════════════════════════════════════════════
    private async Task OnSubscribe(Connection conn, string raw, CancellationToken outerCt)
    {
        var req = JsonSerializer.Deserialize<CommandRequest>(raw, JsonOpts);
        if (!IsWellFormed(req))
        {
            await SendAsync(conn, CommandResponse.Fail(req?.Id ?? "", ErrorCodes.InvalidParams,
                "Subscribe needs id, domain and action."), outerCt);
            return;
        }

        if (!_router.Modules.TryGetValue(req!.Domain, out var module))
        {
            await SendAsync(conn, CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                $"Unknown domain '{req.Domain}'."), outerCt);
            return;
        }

        if (!_plugins.IsEnabled(req.Domain))
        {
            await SendAsync(conn, CommandResponse.Fail(req.Id, ErrorCodes.PluginDisabled,
                $"The '{req.Domain}' plugin is disabled on this PC. Enable it in the agent's panel."), outerCt);
            return;
        }

        if (module is not IStreamModule streamer || !streamer.StreamActions.Contains(req.Action))
        {
            await SendAsync(conn, CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                $"Action '{req.Domain}.{req.Action}' is not streamable."), outerCt);
            return;
        }

        var subs = conn.Subscriptions;

        // Cancel previous subscription with same id, if any.
        if (subs.TryRemove(req.Id, out var prev)) CancelQuietly(prev);

        if (subs.Count >= MaxSubscriptionsPerConnection)
        {
            await SendAsync(conn, CommandResponse.Fail(req.Id, ErrorCodes.RateLimited,
                $"At most {MaxSubscriptionsPerConnection} subscriptions per connection."), outerCt);
            return;
        }

        var session = conn.Session!;
        var cts = CancellationTokenSource.CreateLinkedTokenSource(outerCt);
        subs[req.Id] = cts;
        await SendAsync(conn, CommandResponse.Ok(req.Id, new { subscribed = true }), outerCt);
        _logger.LogInformation("[{Sess}] subscribed {Domain}.{Action} (id={Id})",
            Short(session.SessionId), req.Domain, req.Action, req.Id);

        AddWorker(conn, Task.Run(async () =>
        {
            try
            {
                await foreach (var item in streamer.StartStreamAsync(req.Action, req.Params, session, cts.Token))
                {
                    // A plugin switched off in the panel stops its live streams too.
                    if (cts.IsCancellationRequested || !SessionAlive(conn) || !_plugins.IsEnabled(req.Domain)) break;
                    await SendAsync(conn, new
                    {
                        kind = MessageKinds.Stream,
                        id   = req.Id,
                        data = item,
                        ts   = Now(),
                    }, cts.Token);
                }
            }
            catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or ObjectDisposedException)
            {
                // Normal on unsubscribe or disconnect.
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Stream {Domain}.{Action} (id={Id}) errored", req.Domain, req.Action, req.Id);
            }
            finally
            {
                // Only remove our own entry: a re-subscribe with the same id may
                // already have replaced it.
                subs.TryRemove(new KeyValuePair<string, CancellationTokenSource>(req.Id, cts));
                cts.Dispose();
                _logger.LogInformation("[{Sess}] stream {Id} ended", Short(session.SessionId), req.Id);
            }
        }, CancellationToken.None));
    }

    private void OnUnsubscribe(Connection conn, string? id)
    {
        if (id is null) return;
        if (conn.Subscriptions.TryRemove(id, out var cts))
        {
            CancelQuietly(cts);
            _logger.LogInformation("Unsubscribed {Id}", id);
        }
    }

    /// <summary>
    /// A stream that just ended disposes its CTS on its own thread. Cancelling it
    /// then throws ObjectDisposedException, which escaped the receive loop and
    /// dropped the whole connection over an unsubscribe.
    /// </summary>
    private static void CancelQuietly(CancellationTokenSource cts)
    {
        try { cts.Cancel(); } catch (ObjectDisposedException) { }
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    /// <summary>Reads one text message. Null on close; closes with 1009 if it exceeds <paramref name="maxBytes"/>.</summary>
    private async Task<string?> ReceiveTextAsync(
        TrackedConnection conn, byte[] buffer, int maxBytes, CancellationToken ct)
    {
        using var ms = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await conn.Socket.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                await conn.CloseAsync(WebSocketCloseStatus.NormalClosure, "");
                return null;
            }
            if (ms.Length + result.Count > maxBytes)
            {
                _logger.LogWarning("Message from {Ip} over {Max} bytes; closing", conn.ClientIp, maxBytes);
                await conn.CloseAsync(WebSocketCloseStatus.MessageTooBig, "Message too big");
                return null;
            }
            ms.Write(buffer, 0, result.Count);
        } while (!result.EndOfMessage);
        return Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length);
    }

    private static Task SendAsync(Connection conn, object payload, CancellationToken ct) =>
        conn.Tracked.SendAsync(JsonSerializer.SerializeToUtf8Bytes(payload, JsonOpts), ct);

    private static PairResultMessage PairFail(string code, string msg) =>
        new(MessageKinds.PairResult, false, null, null, null, new ErrorInfo(code, msg));

    private static AuthResultMessage AuthFail(string message) =>
        new(MessageKinds.AuthResult, false, null, new ErrorInfo(ErrorCodes.NotAuthenticated, message));

    /// <summary>
    /// 32 hex chars: 8 bytes of Unix ms + 8 random bytes. Not a ULID (the docs
    /// used to say so); the format is kept because existing devices use it.
    /// </summary>
    private static string GenerateDeviceId()
    {
        var bytes = new byte[16];
        BitConverter.TryWriteBytes(bytes.AsSpan(0, 8), DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        RandomNumberGenerator.Fill(bytes.AsSpan(8, 8));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    private static string Short(string id) => id.Length > 8 ? id[..8] : id;

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_app is not null)
            await _app.StopAsync(cancellationToken);
        _logger.LogInformation("WebSocket server stopped");
    }

    public async ValueTask DisposeAsync()
    {
        if (_app is not null)
            await _app.DisposeAsync();
    }

    private static string GetVersion() =>
        typeof(WebSocketServer).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";
}
