using System.Diagnostics;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using PcRemote.Core.Server;
using PcRemote.Relay;

namespace PcRemote.Tests;

/// <summary>The relay on a free local port, with a fake PC that logs in like RelayConnector.</summary>
public sealed class RelayTests : IAsyncLifetime
{
    private WebApplication? _app;
    private string _base = "";

    private static readonly RelayOptions Fast = new()
    {
        AcceptTimeout = TimeSpan.FromMilliseconds(500),
        HelloTimeout = TimeSpan.FromSeconds(2),
        ConnectsPerMinute = 1_000,
        AgentLoginsPerMinute = 1_000,
    };

    private async Task StartAsync(RelayOptions? options = null)
    {
        _app = RelayServer.Build("http://127.0.0.1:0", options ?? Fast);
        await _app.StartAsync();
        var address = _app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
        _base = address.Replace("http://", "ws://");
    }

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        if (_app is not null) await _app.DisposeAsync();
    }

    private static X509Certificate2 NewCert()
    {
        var rsa = RSA.Create(2048);
        var request = new CertificateRequest("CN=PC de prueba", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1));
    }

    private static async Task<JsonElement> ReadJsonAsync(WebSocket ws)
    {
        var buffer = new byte[8192];
        var r = await ws.ReceiveAsync(buffer, CancellationToken.None);
        if (r.MessageType == WebSocketMessageType.Close) throw new WebSocketException($"closed {ws.CloseStatus}");
        return JsonDocument.Parse(buffer.AsMemory(0, r.Count)).RootElement.Clone();
    }

    private static Task SendJsonAsync(WebSocket ws, object o) =>
        ws.SendAsync(JsonSerializer.SerializeToUtf8Bytes(o), WebSocketMessageType.Text, true, CancellationToken.None);

    /// <summary>A PC on the relay. <paramref name="serve"/> handles each tunnel (default: echo); null ignores them.</summary>
    private sealed record FakePc(ClientWebSocket Control, string Id, Task Loop);

    private async Task<FakePc> LogInAsync(X509Certificate2 cert, bool badSignature = false, Func<WebSocket, Task>? serve = null, bool answer = true)
    {
        var control = new ClientWebSocket();
        await control.ConnectAsync(new Uri($"{_base}/agent"), CancellationToken.None);
        var challenge = await ReadJsonAsync(control);
        var nonce = Convert.FromBase64String(challenge.GetProperty("nonce").GetString()!);
        var sig = cert.GetRSAPrivateKey()!.SignData([.. RelayConnector.SignaturePrefix, .. nonce], HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        if (badSignature) sig[0] ^= 0xFF;
        await SendJsonAsync(control, new { t = "hello", cert = Convert.ToBase64String(cert.RawData), sig = Convert.ToBase64String(sig) });
        if (badSignature) return new FakePc(control, "", Task.CompletedTask);

        var ready = await ReadJsonAsync(control);
        Assert.Equal("ready", ready.GetProperty("t").GetString());
        var id = ready.GetProperty("id").GetString()!;
        serve ??= EchoAsync;
        var loop = Task.Run(async () =>
        {
            try
            {
                while (true)
                {
                    var msg = await ReadJsonAsync(control);
                    if (!answer || msg.GetProperty("t").GetString() != "open") continue;
                    var data = new ClientWebSocket();
                    await data.ConnectAsync(new Uri($"{_base}/accept/{msg.GetProperty("token").GetString()}"), CancellationToken.None);
                    _ = serve(data);
                }
            }
            catch (WebSocketException) { }
        });
        return new FakePc(control, id, loop);
    }

    private static async Task EchoAsync(WebSocket ws)
    {
        var buffer = new byte[16 * 1024];
        try
        {
            while (true)
            {
                var r = await ws.ReceiveAsync(buffer, CancellationToken.None);
                if (r.MessageType == WebSocketMessageType.Close) return;
                await ws.SendAsync(buffer.AsMemory(0, r.Count), WebSocketMessageType.Binary, r.EndOfMessage, CancellationToken.None);
            }
        }
        catch (WebSocketException) { }
    }

    /// <summary>A phone's tunnel; throws with the HTTP status when the relay refuses it.</summary>
    private async Task<ClientWebSocket> PhoneAsync(string id)
    {
        var phone = new ClientWebSocket();
        phone.Options.CollectHttpResponseDetails = true;
        try
        {
            await phone.ConnectAsync(new Uri($"{_base}/connect/{id}"), CancellationToken.None);
            return phone;
        }
        catch (WebSocketException)
        {
            throw new HttpStatusException((int)phone.HttpStatusCode);
        }
    }

    private sealed class HttpStatusException(int status) : Exception($"HTTP {status}")
    {
        public int Status { get; } = status;
    }

    private static async Task<byte[]> ReceiveMessageAsync(WebSocket ws)
    {
        using var ms = new MemoryStream();
        var buffer = new byte[16 * 1024];
        while (true)
        {
            var r = await ws.ReceiveAsync(buffer, CancellationToken.None);
            ms.Write(buffer, 0, r.Count);
            if (r.EndOfMessage) return ms.ToArray();
        }
    }

    // ── Tests ───────────────────────────────────────────

    [Fact]
    public async Task A_tunnel_carries_bytes_both_ways_between_phone_and_pc()
    {
        await StartAsync();
        using var cert = NewCert();
        var pc = await LogInAsync(cert);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(cert.RawData)).ToLowerInvariant(), pc.Id);

        using var phone = await PhoneAsync(pc.Id);
        var payload = RandomNumberGenerator.GetBytes(100_000); // bigger than one 16 KB copy chunk
        await phone.SendAsync(payload, WebSocketMessageType.Binary, true, CancellationToken.None);
        Assert.Equal(payload, await ReceiveMessageAsync(phone));
    }

    [Fact]
    public async Task A_pc_that_cannot_prove_its_certificate_is_refused()
    {
        await StartAsync();
        using var cert = NewCert();
        var pc = await LogInAsync(cert, badSignature: true);
        await Assert.ThrowsAsync<WebSocketException>(() => ReadJsonAsync(pc.Control));
        Assert.Equal(RelayHub.BadHelloStatus, pc.Control.CloseStatus);

        var id = Convert.ToHexString(SHA256.HashData(cert.RawData)).ToLowerInvariant();
        var refused = await Assert.ThrowsAsync<HttpStatusException>(() => PhoneAsync(id));
        Assert.Equal(404, refused.Status);
    }

    [Fact]
    public async Task An_offline_pc_answers_404_and_a_bad_id_400()
    {
        await StartAsync();
        Assert.Equal(404, (await Assert.ThrowsAsync<HttpStatusException>(() => PhoneAsync(new string('a', 64)))).Status);
        Assert.Equal(400, (await Assert.ThrowsAsync<HttpStatusException>(() => PhoneAsync("not-an-id"))).Status);
    }

    [Fact]
    public async Task A_pc_that_does_not_answer_gives_the_phone_504()
    {
        await StartAsync();
        using var cert = NewCert();
        var pc = await LogInAsync(cert, answer: false);
        Assert.Equal(504, (await Assert.ThrowsAsync<HttpStatusException>(() => PhoneAsync(pc.Id))).Status);
    }

    [Fact]
    public async Task A_new_login_of_the_same_pc_replaces_the_old_one()
    {
        await StartAsync();
        using var cert = NewCert();
        var first = await LogInAsync(cert);
        var second = await LogInAsync(cert);
        await first.Loop.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(RelayHub.ReplacedStatus, first.Control.CloseStatus);

        using var phone = await PhoneAsync(second.Id);
        await phone.SendAsync("hola"u8.ToArray(), WebSocketMessageType.Binary, true, CancellationToken.None);
        Assert.Equal("hola", Encoding.UTF8.GetString(await ReceiveMessageAsync(phone)));
    }

    [Fact]
    public async Task Past_the_tunnel_limit_new_tunnels_get_503()
    {
        await StartAsync(Fast with { MaxTunnels = 1 });
        using var cert = NewCert();
        var pc = await LogInAsync(cert);
        using var first = await PhoneAsync(pc.Id);
        Assert.Equal(503, (await Assert.ThrowsAsync<HttpStatusException>(() => PhoneAsync(pc.Id))).Status);
    }

    [Fact]
    public async Task A_tunnel_is_held_to_its_byte_rate_after_the_burst()
    {
        var rate = new RelayHub.ByteRate(new RelayOptions { TunnelBytesPerSecond = 1_000_000, TunnelBurstBytes = 100_000 });
        var watch = Stopwatch.StartNew();
        for (var sent = 0; sent < 600_000; sent += 16_384) await rate.WaitAsync(16_384, CancellationToken.None);
        // 100 KB go at once, the other ~500 KB at 1 MB/s.
        Assert.InRange(watch.Elapsed.TotalSeconds, 0.4, 2);
    }

    [Fact]
    public void Pc_and_relay_sign_the_same_thing()
    {
        Assert.Equal(RelayHub.SignaturePrefix, RelayConnector.SignaturePrefix);
    }

    [Fact]
    public void The_relay_address_depends_only_on_the_first_digit_of_the_id()
    {
        Assert.Equal("wss://ra.relay.example", RelayAddress.For("wss://r{s}.relay.example", "ab" + new string('0', 62)));
        Assert.Equal("wss://relay.example", RelayAddress.For("wss://relay.example", "ab"));
    }

    [Fact]
    public async Task The_upgrade_request_is_read_up_to_its_blank_line_only()
    {
        var bytes = Encoding.ASCII.GetBytes(
            "GET /socket/screen?x=1 HTTP/1.1\r\nHost: pc\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: abc==\r\n\r\nFRAME");
        using var stream = new MemoryStream(bytes);
        var head = await RelayConnector.ReadRequestHeadAsync(stream, CancellationToken.None);
        Assert.Equal(("/socket/screen", "abc=="), head);
        Assert.Equal("FRAME", Encoding.ASCII.GetString(bytes, (int)stream.Position, bytes.Length - (int)stream.Position));

        using var notUpgrade = new MemoryStream(Encoding.ASCII.GetBytes("GET /ws HTTP/1.1\r\nHost: pc\r\n\r\n"));
        Assert.Null(await RelayConnector.ReadRequestHeadAsync(notUpgrade, CancellationToken.None));
    }
}
