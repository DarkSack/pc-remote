using System.Buffers;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PcRemote.Relay;

/// <summary>Timeouts and limits of the relay. Every one has an environment variable (see RelayServer).</summary>
public sealed record RelayOptions
{
    /// <summary>How long a phone waits for its PC to answer an "open".</summary>
    public TimeSpan AcceptTimeout { get; init; } = TimeSpan.FromSeconds(10);
    /// <summary>How long a PC has to answer the challenge.</summary>
    public TimeSpan HelloTimeout { get; init; } = TimeSpan.FromSeconds(10);
    /// <summary>Tunnels open at once to one PC (protocol socket, remote screen, a phone or two more).</summary>
    public int MaxSessionsPerAgent { get; init; } = 8;
    /// <summary>Tunnels open at once on this relay: past it new ones get 503 instead of eating the memory.</summary>
    public int MaxTunnels { get; init; } = 20_000;
    /// <summary>PCs registered at once on this relay.</summary>
    public int MaxAgents { get; init; } = 200_000;
    /// <summary>Bytes per second per tunnel and direction (~24 Mbit/s: a 1080p60 screen peaks near 9).</summary>
    public long TunnelBytesPerSecond { get; init; } = 3_000_000;
    /// <summary>Burst a tunnel may send above its rate (a key frame, a file chunk).</summary>
    public long TunnelBurstBytes { get; init; } = 1_000_000;
    /// <summary>Tunnel requests per minute from one IP.</summary>
    public int ConnectsPerMinute { get; init; } = 60;
    /// <summary>PC logins per minute from one IP (each one costs an RSA verify).</summary>
    public int AgentLoginsPerMinute { get; init; } = 10;
}

/// <summary>
/// Joins a phone and its PC when neither can reach the other directly (antivirus
/// firewall, two routers, different networks). Both only make outgoing connections:
///
///   PC     → /agent          control socket; proves it owns its certificate
///   phone  → /connect/{id}   id = SHA-256 of the PC's certificate (what the phone pins)
///   relay  → PC: {"t":"open","token"}   →   PC → /accept/{token}
///
/// then the relay copies bytes between the two sockets. Inside runs the usual
/// TLS to the PC, pinned by the phone: the relay sees only ciphertext and keeps
/// no accounts, keys or record of what passes.
///
/// One instance keeps its PCs in memory. To grow past one machine, PCs and phones
/// both pick the relay from the first hex digit of the PC's id (RelayAddress on
/// each side), so a group of ids can move to another server by changing DNS only.
/// </summary>
public sealed partial class RelayHub(ILogger<RelayHub> logger, RelayOptions options)
{
    /// <summary>Prefix of what the PC signs, so its key's signature is useless anywhere else.</summary>
    public static readonly byte[] SignaturePrefix = "pcremote-relay:"u8.ToArray();

    public const WebSocketCloseStatus BadHelloStatus = (WebSocketCloseStatus)4401;
    public const WebSocketCloseStatus ReplacedStatus = (WebSocketCloseStatus)4003;

    /// <summary>Copy buffer; also the most a tunnel asks its rate limiter for at once.</summary>
    private const int ChunkBytes = 16 * 1024;

    private sealed class Agent(WebSocket control)
    {
        public WebSocket Control { get; } = control;
        public SemaphoreSlim SendLock { get; } = new(1, 1);
        public int Sessions;
    }

    private sealed class Pending
    {
        public TaskCompletionSource<WebSocket> AgentSocket { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource Done { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private readonly ConcurrentDictionary<string, Agent> _agents = new();
    private readonly ConcurrentDictionary<string, Pending> _pending = new();
    private int _tunnels;
    private long _bytes;

    /// <summary>For the per-minute log line and the tests.</summary>
    public (int Agents, int Tunnels, long Bytes) Stats => (_agents.Count, Volatile.Read(ref _tunnels), Interlocked.Read(ref _bytes));

    [GeneratedRegex("^[0-9a-f]{64}$")]
    private static partial Regex AgentId();

    [GeneratedRegex("^[0-9a-f]{32}$")]
    private static partial Regex Token();

    // ── PC side ─────────────────────────────────────────

    public async Task AgentAsync(HttpContext ctx)
    {
        if (!ctx.WebSockets.IsWebSocketRequest) { ctx.Response.StatusCode = 400; return; }
        if (_agents.Count >= options.MaxAgents) { ctx.Response.StatusCode = 503; return; }
        using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
        var ct = ctx.RequestAborted;

        var nonce = RandomNumberGenerator.GetBytes(32);
        string? id;
        try
        {
            await SendJsonAsync(ws, new { t = "challenge", nonce = Convert.ToBase64String(nonce) }, ct);
            using var helloCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            helloCts.CancelAfter(options.HelloTimeout);
            id = await ReadHelloAsync(ws, nonce, helloCts.Token);
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException) { return; }
        if (id is null)
        {
            await CloseQuietlyAsync(ws, BadHelloStatus, "Bad hello");
            return;
        }

        var agent = new Agent(ws);
        if (_agents.TryGetValue(id, out var old)) await CloseQuietlyAsync(old.Control, ReplacedStatus, "Replaced");
        _agents[id] = agent;
        logger.LogDebug("PC {Id} online", id[..8]);
        try
        {
            await SendAsync(agent, new { t = "ready", id }, ct);
            // Nothing else comes from the PC; reading keeps pings and the close handshake going.
            var buffer = new byte[256];
            while (ws.State == WebSocketState.Open)
            {
                var r = await ws.ReceiveAsync(buffer.AsMemory(), ct);
                if (r.MessageType == WebSocketMessageType.Close) break;
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException) { }
        finally
        {
            _agents.TryRemove(new KeyValuePair<string, Agent>(id, agent));
            logger.LogDebug("PC {Id} offline", id[..8]);
        }
    }

    /// <summary>The PC's certificate id if the hello proves it holds that certificate's key.</summary>
    private static async Task<string?> ReadHelloAsync(WebSocket ws, byte[] nonce, CancellationToken ct)
    {
        var text = await ReceiveTextAsync(ws, 16 * 1024, ct);
        if (text is null) return null;
        try
        {
            using var doc = JsonDocument.Parse(text);
            var root = doc.RootElement;
            if (root.GetProperty("t").GetString() != "hello") return null;
            var der = Convert.FromBase64String(root.GetProperty("cert").GetString() ?? "");
            var sig = Convert.FromBase64String(root.GetProperty("sig").GetString() ?? "");
            return Verify(der, nonce, sig);
        }
        catch (Exception ex) when (ex is JsonException or FormatException or KeyNotFoundException
                                       or InvalidOperationException or CryptographicException)
        {
            return null;
        }
    }

    /// <summary>SHA-256 of <paramref name="der"/> (hex) if <paramref name="sig"/> signs the nonce with its RSA key.</summary>
    public static string? Verify(byte[] der, byte[] nonce, byte[] sig)
    {
        using var cert = X509CertificateLoader.LoadCertificate(der);
        using var rsa = cert.GetRSAPublicKey();
        if (rsa is null) return null;
        byte[] signed = [.. SignaturePrefix, .. nonce];
        return rsa.VerifyData(signed, sig, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)
            ? Convert.ToHexString(SHA256.HashData(der)).ToLowerInvariant()
            : null;
    }

    public async Task AcceptAsync(HttpContext ctx, string token)
    {
        if (!ctx.WebSockets.IsWebSocketRequest || !Token().IsMatch(token) || !_pending.TryRemove(token, out var pending))
        {
            ctx.Response.StatusCode = 404;
            return;
        }
        using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
        if (!pending.AgentSocket.TrySetResult(ws)) return; // the phone gave up meanwhile
        // The phone's request copies the bytes; this one only has to stay open until it ends.
        try { await pending.Done.Task.WaitAsync(ctx.RequestAborted); }
        catch (OperationCanceledException) { }
    }

    // ── Phone side ──────────────────────────────────────

    public async Task ConnectAsync(HttpContext ctx, string id)
    {
        if (!ctx.WebSockets.IsWebSocketRequest || !AgentId().IsMatch(id)) { ctx.Response.StatusCode = 400; return; }
        if (!_agents.TryGetValue(id, out var agent)) { ctx.Response.StatusCode = 404; return; } // PC offline
        if (Volatile.Read(ref agent.Sessions) >= options.MaxSessionsPerAgent) { ctx.Response.StatusCode = 429; return; }
        if (Interlocked.Increment(ref _tunnels) > options.MaxTunnels)
        {
            Interlocked.Decrement(ref _tunnels);
            ctx.Response.StatusCode = 503;
            return;
        }
        Interlocked.Increment(ref agent.Sessions);

        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        var pending = new Pending();
        _pending[token] = pending;
        try
        {
            WebSocket agentSocket;
            try
            {
                await SendAsync(agent, new { t = "open", token, ip = ctx.Connection.RemoteIpAddress?.ToString() ?? "" }, ctx.RequestAborted);
                agentSocket = await pending.AgentSocket.Task.WaitAsync(options.AcceptTimeout, ctx.RequestAborted);
            }
            catch (Exception ex) when (ex is TimeoutException or OperationCanceledException or WebSocketException)
            {
                _pending.TryRemove(token, out _);
                pending.AgentSocket.TrySetCanceled();
                if (!ctx.RequestAborted.IsCancellationRequested) ctx.Response.StatusCode = 504;
                return;
            }

            using var phone = await ctx.WebSockets.AcceptWebSocketAsync();
            await PipeAsync(phone, agentSocket, options, ctx.RequestAborted);
        }
        finally
        {
            Interlocked.Decrement(ref agent.Sessions);
            Interlocked.Decrement(ref _tunnels);
            pending.Done.TrySetResult();
        }
    }

    // ── Copying ─────────────────────────────────────────

    /// <summary>Copies both ways until one side closes, then closes the other.</summary>
    internal async Task PipeAsync(WebSocket a, WebSocket b, RelayOptions limits, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var ab = CopyAsync(a, b, new ByteRate(limits), cts.Token);
        var ba = CopyAsync(b, a, new ByteRate(limits), cts.Token);
        await Task.WhenAny(ab, ba);
        cts.Cancel();
        try { await Task.WhenAll(ab, ba); } catch { /* the other direction was cut on purpose */ }
        await Task.WhenAll(
            CloseQuietlyAsync(a, a.CloseStatus ?? b.CloseStatus ?? WebSocketCloseStatus.NormalClosure, ""),
            CloseQuietlyAsync(b, b.CloseStatus ?? a.CloseStatus ?? WebSocketCloseStatus.NormalClosure, ""));
    }

    private async Task CopyAsync(WebSocket from, WebSocket to, ByteRate rate, CancellationToken ct)
    {
        // Pooled: thousands of tunnels would otherwise each pin two fresh 16 KB arrays.
        var buffer = ArrayPool<byte>.Shared.Rent(ChunkBytes);
        try
        {
            while (true)
            {
                var r = await from.ReceiveAsync(buffer.AsMemory(0, ChunkBytes), ct);
                if (r.MessageType == WebSocketMessageType.Close) return;
                await rate.WaitAsync(r.Count, ct);
                // Awaiting the send is the backpressure: a slow reader slows its writer, nothing piles up here.
                await to.SendAsync(buffer.AsMemory(0, r.Count), WebSocketMessageType.Binary, r.EndOfMessage, ct);
                Interlocked.Add(ref _bytes, r.Count);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException) { }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    /// <summary>
    /// Token bucket for one direction of one tunnel, computed from timestamps: no timer
    /// per tunnel (20 000 tunnels × 2 timers would cost more than the copying).
    /// </summary>
    internal sealed class ByteRate(RelayOptions limits)
    {
        private double _tokens = limits.TunnelBurstBytes;
        private long _last = Stopwatch.GetTimestamp();

        public Task WaitAsync(int bytes, CancellationToken ct)
        {
            var now = Stopwatch.GetTimestamp();
            var elapsed = (double)(now - _last) / Stopwatch.Frequency;
            _last = now;
            _tokens = Math.Min(limits.TunnelBurstBytes, _tokens + elapsed * limits.TunnelBytesPerSecond) - bytes;
            if (_tokens >= 0) return Task.CompletedTask;
            // In debt: wait until the rate has paid it back.
            var wait = TimeSpan.FromSeconds(-_tokens / limits.TunnelBytesPerSecond);
            return Task.Delay(wait, ct);
        }
    }

    // ── Helpers ─────────────────────────────────────────

    private static async Task SendAsync(Agent agent, object message, CancellationToken ct)
    {
        await agent.SendLock.WaitAsync(ct);
        try { await SendJsonAsync(agent.Control, message, ct); }
        finally { agent.SendLock.Release(); }
    }

    private static Task SendJsonAsync(WebSocket ws, object message, CancellationToken ct) =>
        ws.SendAsync(JsonSerializer.SerializeToUtf8Bytes(message), WebSocketMessageType.Text, true, ct);

    private static async Task<string?> ReceiveTextAsync(WebSocket ws, int max, CancellationToken ct)
    {
        var buffer = new byte[max];
        var count = 0;
        while (true)
        {
            if (count == max) return null;
            var r = await ws.ReceiveAsync(buffer.AsMemory(count), ct);
            if (r.MessageType != WebSocketMessageType.Text) return null;
            count += r.Count;
            if (r.EndOfMessage) return Encoding.UTF8.GetString(buffer, 0, count);
        }
    }

    /// <summary>Close handshake (at most 2 s), so the other side reads the reason before the socket goes.</summary>
    private static async Task CloseQuietlyAsync(WebSocket ws, WebSocketCloseStatus status, string reason)
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            if (ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
                await ws.CloseAsync(status, reason, cts.Token);
        }
        catch { /* already gone */ }
        ws.Abort();
    }
}
