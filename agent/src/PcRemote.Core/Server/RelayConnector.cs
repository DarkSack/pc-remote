using System.Net.Security;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;
using PcRemote.Core.Security;

namespace PcRemote.Core.Server;

/// <summary>Where this PC meets phones that cannot reach it directly.</summary>
public static class RelayAddress
{
    /// <summary>
    /// <c>{s}</c> in the template becomes the first hex digit of the PC's id. Phones compute
    /// the same, so a group of PCs can move to another relay server by changing DNS only.
    /// </summary>
    public static string For(string template, string agentId) => template.Replace("{s}", agentId[..1]);
}

/// <summary>
/// Keeps an outgoing control socket to the relay. An outgoing connection gets through
/// antivirus firewalls, routers and other networks that block a phone coming in. When a
/// phone asks for this PC there, the relay sends "open" and this PC dials back a data
/// socket, puts its own TLS on it (the same certificate the phone pinned) and serves it
/// with the same handlers as a direct connection. The relay only sees ciphertext.
/// </summary>
public sealed class RelayConnector(
    AgentSettings settings,
    X509Certificate2 certificate,
    WebSocketServer server,
    ILogger<RelayConnector> logger) : BackgroundService
{
    /// <summary>Same prefix as PcRemote.Relay.RelayHub.SignaturePrefix (a test checks both sides agree).</summary>
    public static readonly byte[] SignaturePrefix = "pcremote-relay:"u8.ToArray();

    private static readonly TimeSpan[] Backoff =
        [TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(60)];

    private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(15);

    /// <summary>Connected to the relay right now (for the panel).</summary>
    public bool Online { get; private set; }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        if (!settings.Relay.Enabled) return;
        var baseUrl = RelayAddress.For(settings.Relay.Url, CertificateProvider.GetFingerprint(certificate)).TrimEnd('/');
        var attempt = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await RunControlAsync(baseUrl, () => attempt = 0, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                // No internet, relay down or restarting: keep the log quiet, try again later.
                logger.LogDebug("Relay {Url}: {Message}", baseUrl, ex.Message);
            }
            if (Online) logger.LogInformation("Relay connection lost");
            Online = false;

            // Jitter: after a relay restart, thousands of PCs must not all come back in the same second.
            var delay = Backoff[Math.Min(attempt++, Backoff.Length - 1)] * (0.5 + Random.Shared.NextDouble());
            try { await Task.Delay(delay, ct); }
            catch (OperationCanceledException) { return; }
        }
    }

    private async Task RunControlAsync(string baseUrl, Action connected, CancellationToken ct)
    {
        using var ws = NewSocket();
        await ConnectAsync(ws, $"{baseUrl}/agent", ct);

        var challenge = await ReadJsonAsync(ws, ct);
        if (challenge.GetProperty("t").GetString() != "challenge") throw new InvalidDataException("Expected a challenge");
        var nonce = Convert.FromBase64String(challenge.GetProperty("nonce").GetString() ?? "");
        using var rsa = certificate.GetRSAPrivateKey() ?? throw new InvalidOperationException("The certificate has no RSA key");
        var sig = rsa.SignData([.. SignaturePrefix, .. nonce], HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        await SendJsonAsync(ws, new { t = "hello", cert = Convert.ToBase64String(certificate.RawData), sig = Convert.ToBase64String(sig) }, ct);

        var ready = await ReadJsonAsync(ws, ct);
        if (ready.GetProperty("t").GetString() != "ready") throw new InvalidDataException("Relay refused this PC");
        Online = true;
        connected();
        logger.LogInformation("Reachable through the relay ({Url})", baseUrl);

        while (true)
        {
            var msg = await ReadJsonAsync(ws, ct);
            if (msg.GetProperty("t").GetString() != "open") continue;
            var token = msg.GetProperty("token").GetString() ?? "";
            var ip = msg.TryGetProperty("ip", out var i) ? i.GetString() : null;
            _ = ServeAsync(baseUrl, token, string.IsNullOrEmpty(ip) ? "relay" : ip, ct);
        }
    }

    /// <summary>One phone through the relay: TLS with this PC's certificate, then a normal WebSocket.</summary>
    private async Task ServeAsync(string baseUrl, string token, string clientIp, CancellationToken ct)
    {
        try
        {
            using var data = NewSocket();
            await ConnectAsync(data, $"{baseUrl}/accept/{Uri.EscapeDataString(token)}", ct);
            await using var tls = new SslStream(WebSocketStream.Create(data, WebSocketMessageType.Binary, ownsWebSocket: false));
            using (var hs = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                hs.CancelAfter(ConnectTimeout);
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions { ServerCertificate = certificate }, hs.Token);
            }

            var head = await ReadRequestHeadAsync(tls, ct);
            var handler = head is null ? null : server.RelayedHandler(head.Value.Path);
            if (handler is null)
            {
                await tls.WriteAsync("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"u8.ToArray(), ct);
                return;
            }
            var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(head.Value.Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
            await tls.WriteAsync(Encoding.ASCII.GetBytes(
                $"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"), ct);
            await tls.FlushAsync(ct);

            var ping = TimeSpan.FromSeconds(settings.Session.PingIntervalSeconds);
            using var socket = WebSocket.CreateFromStream(tls, new WebSocketCreationOptions
            {
                IsServer = true,
                KeepAliveInterval = ping,
                KeepAliveTimeout = ping,
            });
            await handler(socket, clientIp, ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            logger.LogDebug("Relayed connection ended: {Message}", ex.Message);
        }
    }

    /// <summary>
    /// The HTTP upgrade request of the phone's WebSocket: its path and Sec-WebSocket-Key.
    /// Read a byte at a time so nothing after the head is swallowed; at most 8 KB.
    /// </summary>
    internal static async Task<(string Path, string Key)?> ReadRequestHeadAsync(Stream stream, CancellationToken ct)
    {
        var buffer = new byte[8192];
        var n = 0;
        while (true)
        {
            if (n == buffer.Length) return null;
            if (await stream.ReadAsync(buffer.AsMemory(n, 1), ct) == 0) return null;
            n++;
            if (n >= 4 && buffer[n - 4] == '\r' && buffer[n - 3] == '\n' && buffer[n - 2] == '\r' && buffer[n - 1] == '\n') break;
        }
        var lines = Encoding.ASCII.GetString(buffer, 0, n - 4).Split("\r\n");
        var request = lines[0].Split(' ');
        if (request.Length != 3 || request[0] != "GET") return null;
        string? key = null;
        var upgrade = false;
        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon <= 0) continue;
            var name = line[..colon].Trim();
            var value = line[(colon + 1)..].Trim();
            if (name.Equals("Sec-WebSocket-Key", StringComparison.OrdinalIgnoreCase)) key = value;
            if (name.Equals("Upgrade", StringComparison.OrdinalIgnoreCase)) upgrade = value.Equals("websocket", StringComparison.OrdinalIgnoreCase);
        }
        if (key is null || !upgrade) return null;
        var path = request[1];
        var query = path.IndexOf('?');
        return (query >= 0 ? path[..query] : path, key);
    }

    private ClientWebSocket NewSocket()
    {
        var ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
        // A relay that vanished without closing is noticed instead of waited on forever.
        ws.Options.KeepAliveTimeout = TimeSpan.FromSeconds(20);
        return ws;
    }

    private static async Task ConnectAsync(ClientWebSocket ws, string url, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(ConnectTimeout);
        await ws.ConnectAsync(new Uri(url), timeout.Token);
    }

    private static Task SendJsonAsync(WebSocket ws, object message, CancellationToken ct) =>
        ws.SendAsync(JsonSerializer.SerializeToUtf8Bytes(message), WebSocketMessageType.Text, true, ct);

    private static async Task<JsonElement> ReadJsonAsync(WebSocket ws, CancellationToken ct)
    {
        var buffer = new byte[4096];
        var count = 0;
        while (true)
        {
            if (count == buffer.Length) throw new InvalidDataException("Relay message too long");
            var r = await ws.ReceiveAsync(buffer.AsMemory(count), ct);
            if (r.MessageType == WebSocketMessageType.Close) throw new WebSocketException("Relay closed the connection");
            count += r.Count;
            if (r.EndOfMessage) return JsonDocument.Parse(buffer.AsMemory(0, count)).RootElement.Clone();
        }
    }
}
