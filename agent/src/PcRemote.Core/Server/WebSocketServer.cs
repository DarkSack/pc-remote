using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Panel;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Core.Security;

namespace PcRemote.Core.Server;

/// <summary>
/// Hosts the WebSocket server on Kestrel with WSS. Handles bootstrap
/// (pair_init / pair_confirm) and authenticated sessions (auth_challenge / auth)
/// then routes command requests through CommandRouter.
/// </summary>
public sealed class WebSocketServer : IHostedService, IAsyncDisposable
{
    private readonly AgentSettings     _settings;
    private readonly X509Certificate2  _certificate;
    private readonly ConnectionManager _connections;
    private readonly PairingService    _pairing;
    private readonly SessionManager    _sessions;
    private readonly DeviceRepository  _devices;
    private readonly CommandRouter     _router;
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
        CommandRouter router,
        ILogger<WebSocketServer> logger)
    {
        _settings    = settings;
        _certificate = certificate;
        _connections = connections;
        _pairing     = pairing;
        _sessions    = sessions;
        _devices     = devices;
        _router      = router;
        _logger      = logger;
    }

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateBuilder();

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

        _app = builder.Build();
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
    private async Task HandleWebSocket(HttpContext ctx)
    {
        if (!ctx.WebSockets.IsWebSocketRequest)
        {
            ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        var socket   = await ctx.WebSockets.AcceptWebSocketAsync();
        var clientIp = ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        var tracked  = _connections.Register(socket, clientIp);
        _logger.LogInformation("Client connected from {Ip} (id={Id})", clientIp, tracked.Id);

        ClientSession? session = null;
        try
        {
            await HandleMessagesAsync(socket, clientIp, s => session = s, ctx.RequestAborted);
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException)
        {
            _logger.LogInformation("Client {Ip} disconnected: {Message}", clientIp, ex.Message);
        }
        finally
        {
            if (session is not null)
            {
                _sessions.End(session.SessionId);
                _logger.LogInformation("Session {Session} ended", session.SessionId[..8]);
            }
            _connections.Unregister(tracked.Id);
        }
    }

    private async Task HandleMessagesAsync(
        WebSocket socket,
        string clientIp,
        Action<ClientSession> onSessionEstablished,
        CancellationToken ct)
    {
        ClientSession? session = null;
        byte[] pendingNonce = Array.Empty<byte>();
        var subscriptions = new ConcurrentDictionary<string, CancellationTokenSource>();

        try
        {
            while (socket.State == WebSocketState.Open)
            {
                var raw = await ReceiveTextAsync(socket, ct);
                if (raw is null) break;

                MessageHeader? header;
                try { header = JsonSerializer.Deserialize<MessageHeader>(raw, JsonOpts); }
                catch (JsonException ex)
                {
                    _logger.LogWarning("Malformed JSON from {Ip}: {Msg}", clientIp, ex.Message);
                    continue;
                }
                if (header is null) continue;

                switch (header.Kind)
                {
                    case MessageKinds.PairInit:
                        await OnPairInit(socket, clientIp, ct);
                        break;

                    case MessageKinds.PairConfirm:
                        await OnPairConfirm(socket, clientIp, raw, ct);
                        break;

                    case MessageKinds.Auth:
                        session = await OnAuth(socket, clientIp, raw, pendingNonce, ct);
                        if (session is not null) onSessionEstablished(session);
                        break;

                    case MessageKinds.Ping:
                        await SendAsync(socket, new { kind = MessageKinds.Pong, ts = Now() }, ct);
                        break;

                    case MessageKinds.Request:
                        if (session is null)
                        {
                            pendingNonce = IssueChallenge(socket, ct);
                            continue;
                        }
                        await OnRequest(socket, raw, session, ct);
                        break;

                    case MessageKinds.Subscribe:
                        if (session is null)
                        {
                            pendingNonce = IssueChallenge(socket, ct);
                            continue;
                        }
                        await OnSubscribe(socket, raw, session, subscriptions, ct);
                        break;

                    case MessageKinds.Unsubscribe:
                        OnUnsubscribe(raw, subscriptions);
                        break;

                    default:
                        if (session is null)
                            pendingNonce = IssueChallenge(socket, ct);
                        else
                            _logger.LogDebug("Ignoring unknown kind '{Kind}' from session {Sess}",
                                header.Kind, session.SessionId[..8]);
                        break;
                }
            }
        }
        finally
        {
            foreach (var kv in subscriptions) kv.Value.Cancel();
            subscriptions.Clear();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Pairing (bootstrap)
    // ══════════════════════════════════════════════════════════════
    private async Task OnPairInit(WebSocket socket, string clientIp, CancellationToken ct)
    {
        var code = _pairing.IssueCode(clientIp);
        // Ack: no payload — client already knows we're waiting for pair_confirm.
        await SendAsync(socket, new
        {
            kind    = "pair_init_ack",
            ttlSec  = _settings.Pairing.CodeTtlSeconds,
            ts      = Now(),
        }, ct);
    }

    private async Task OnPairConfirm(WebSocket socket, string clientIp, string raw, CancellationToken ct)
    {
        var msg = JsonSerializer.Deserialize<PairConfirmMessage>(raw, JsonOpts);
        if (msg is null)
        {
            await SendAsync(socket, PairFail(ErrorCodes.InvalidParams, "Malformed pair_confirm"), ct);
            return;
        }

        var result = _pairing.Validate(msg.Code, clientIp);
        switch (result)
        {
            case PairingValidationResult.Locked l:
                await SendAsync(socket, PairFail(ErrorCodes.RateLimited,
                    $"Too many failed attempts. Try again in {l.RemainingSeconds}s."), ct);
                return;

            case PairingValidationResult.InvalidCode:
                await SendAsync(socket, PairFail(ErrorCodes.PairingFailed, "Invalid or expired code."), ct);
                return;

            case PairingValidationResult.Ok:
                // Continue below.
                break;
        }

        byte[] publicKey;
        try { publicKey = Convert.FromBase64String(msg.PublicKey); }
        catch { await SendAsync(socket, PairFail(ErrorCodes.InvalidParams, "publicKey must be base64.")); return; }

        if (publicKey.Length != 32)
        {
            await SendAsync(socket, PairFail(ErrorCodes.InvalidParams, "publicKey must be 32 bytes (Ed25519)."), ct);
            return;
        }

        var deviceId = GenerateUlid();
        var device = new Device(deviceId, msg.DeviceName, publicKey, DateTimeOffset.UtcNow, null, false);
        _devices.Insert(device);

        _logger.LogInformation("Paired new device: {Name} ({Id})", device.Name, device.Id);

        await SendAsync(socket, new PairResultMessage(
            Kind:            MessageKinds.PairResult,
            Success:         true,
            DeviceId:        deviceId,
            AgentPublicKey:  null, // Fase 5+: si necesitamos que el cliente verifique al server
            CertFingerprint: CertificateProvider.GetFingerprint(_certificate),
            Error:           null), ct);
    }

    // ══════════════════════════════════════════════════════════════
    // Auth (each reconnect)
    // ══════════════════════════════════════════════════════════════
    private async Task<byte[]> OnFirstContactAsync(WebSocket socket, CancellationToken ct)
    {
        return IssueChallenge(socket, ct);
    }

    private byte[] IssueChallenge(WebSocket socket, CancellationToken ct)
    {
        var nonce = RandomNumberGenerator.GetBytes(32);
        _ = SendAsync(socket, new AuthChallengeMessage(
            Kind: MessageKinds.AuthChallenge,
            Nonce: Convert.ToHexString(nonce).ToLowerInvariant()), ct);
        return nonce;
    }

    private async Task<ClientSession?> OnAuth(
        WebSocket socket, string clientIp, string raw, byte[] pendingNonce, CancellationToken ct)
    {
        var msg = JsonSerializer.Deserialize<AuthMessage>(raw, JsonOpts);
        if (msg is null || pendingNonce.Length != 32)
        {
            await SendAsync(socket, AuthFail("Missing challenge or malformed auth."), ct);
            return null;
        }

        var device = _devices.Get(msg.DeviceId);
        if (device is null || device.Revoked)
        {
            await SendAsync(socket, AuthFail("Device not paired or revoked."), ct);
            return null;
        }

        byte[] signature;
        try { signature = Convert.FromBase64String(msg.Signature); }
        catch { await SendAsync(socket, AuthFail("Signature must be base64.")); return null; }

        if (!Ed25519Signing.Verify(device.PublicKey, pendingNonce, signature))
        {
            await SendAsync(socket, AuthFail("Invalid signature."), ct);
            return null;
        }

        _devices.TouchLastSeen(device.Id);
        var session = _sessions.Create(device.Id, device.Name);
        _logger.LogInformation("Authenticated {Name} from {Ip} → session {Sess}",
            device.Name, clientIp, session.SessionId[..8]);

        await SendAsync(socket, new AuthResultMessage(
            Kind:      MessageKinds.AuthResult,
            Success:   true,
            SessionId: session.SessionId,
            Error:     null), ct);
        return session;
    }

    // ══════════════════════════════════════════════════════════════
    // Command dispatch
    // ══════════════════════════════════════════════════════════════
    private async Task OnRequest(WebSocket socket, string raw, ClientSession session, CancellationToken ct)
    {
        var req = JsonSerializer.Deserialize<CommandRequest>(raw, JsonOpts);
        if (req is null)
        {
            _logger.LogWarning("Failed to parse request from {Sess}", session.SessionId[..8]);
            return;
        }
        var response = await _router.DispatchAsync(req, session, ct);
        await SendAsync(socket, response, ct);
    }

    // ══════════════════════════════════════════════════════════════
    // Streams (subscribe / unsubscribe)
    // ══════════════════════════════════════════════════════════════
    private async Task OnSubscribe(
        WebSocket socket,
        string raw,
        ClientSession session,
        ConcurrentDictionary<string, CancellationTokenSource> subs,
        CancellationToken outerCt)
    {
        var req = JsonSerializer.Deserialize<CommandRequest>(raw, JsonOpts);
        if (req is null) return;

        if (!_router.Modules.TryGetValue(req.Domain, out var module))
        {
            await SendAsync(socket, CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                $"Unknown domain '{req.Domain}'."), outerCt);
            return;
        }

        if (module is not IStreamModule streamer || !streamer.StreamActions.Contains(req.Action))
        {
            await SendAsync(socket, CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                $"Action '{req.Domain}.{req.Action}' is not streamable."), outerCt);
            return;
        }

        // Cancel previous subscription with same id, if any.
        if (subs.TryRemove(req.Id, out var prev)) prev.Cancel();

        var cts = CancellationTokenSource.CreateLinkedTokenSource(outerCt);
        subs[req.Id] = cts;
        await SendAsync(socket, CommandResponse.Ok(req.Id, new { subscribed = true }), outerCt);
        _logger.LogInformation("[{Sess}] subscribed {Domain}.{Action} (id={Id})",
            session.SessionId[..8], req.Domain, req.Action, req.Id);

        _ = Task.Run(async () =>
        {
            try
            {
                await foreach (var item in streamer.StartStreamAsync(req.Action, req.Params, session, cts.Token))
                {
                    if (cts.IsCancellationRequested) break;
                    await SendAsync(socket, new
                    {
                        kind = MessageKinds.Stream,
                        id   = req.Id,
                        data = item,
                        ts   = Now(),
                    }, cts.Token);
                }
            }
            catch (OperationCanceledException) { /* normal on unsubscribe */ }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Stream {Domain}.{Action} (id={Id}) errored", req.Domain, req.Action, req.Id);
            }
            finally
            {
                subs.TryRemove(req.Id, out _);
                _logger.LogInformation("[{Sess}] stream {Id} ended", session.SessionId[..8], req.Id);
            }
        }, outerCt);
    }

    private void OnUnsubscribe(string raw, ConcurrentDictionary<string, CancellationTokenSource> subs)
    {
        var header = JsonSerializer.Deserialize<MessageHeader>(raw, JsonOpts);
        if (header?.Id is null) return;
        if (subs.TryRemove(header.Id, out var cts))
        {
            cts.Cancel();
            _logger.LogInformation("Unsubscribed {Id}", header.Id);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════
    private static async Task<string?> ReceiveTextAsync(WebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[8 * 1024];
        var ms = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await socket.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None);
                return null;
            }
            ms.Write(buffer, 0, result.Count);
        } while (!result.EndOfMessage);
        return Encoding.UTF8.GetString(ms.ToArray());
    }

    private static Task SendAsync(WebSocket socket, object payload, CancellationToken ct = default)
    {
        var json = JsonSerializer.Serialize(payload, JsonOpts);
        var bytes = Encoding.UTF8.GetBytes(json);
        return socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, ct);
    }

    private static PairResultMessage PairFail(string code, string msg) =>
        new(MessageKinds.PairResult, false, null, null, null, new ErrorInfo(code, msg));

    private static AuthResultMessage AuthFail(string message) =>
        new(MessageKinds.AuthResult, false, null, new ErrorInfo(ErrorCodes.NotAuthenticated, message));

    private static string GenerateUlid()
    {
        // Simplified ULID: 26-char Crockford base32 of time+random.
        var bytes = new byte[16];
        var msPart = BitConverter.GetBytes(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        Buffer.BlockCopy(msPart, 0, bytes, 0, 8);
        RandomNumberGenerator.Fill(bytes.AsSpan(8, 8));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

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
