using System.Net;
using System.Security.Cryptography.X509Certificates;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Server;

/// <summary>
/// Hosts the WebSocket server on Kestrel with WSS. Exposes /ws for authenticated
/// clients. Pairing bootstrap is handled inline for now; full flow lands in Phase 2.
/// </summary>
public sealed class WebSocketServer : IHostedService, IAsyncDisposable
{
    private readonly AgentSettings _settings;
    private readonly X509Certificate2 _certificate;
    private readonly ConnectionManager _connections;
    private readonly ILogger<WebSocketServer> _logger;
    private WebApplication? _app;

    public WebSocketServer(
        AgentSettings settings,
        X509Certificate2 certificate,
        ConnectionManager connections,
        ILogger<WebSocketServer> logger)
    {
        _settings    = settings;
        _certificate = certificate;
        _connections = connections;
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

            kestrel.Listen(bindAddress, _settings.WebSocket.Port, listen =>
            {
                listen.UseHttps(new HttpsConnectionAdapterOptions
                {
                    ServerCertificate = _certificate,
                });
            });
        });

        builder.Services.AddSingleton(_connections);

        _app = builder.Build();
        _app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(_settings.Session.PingIntervalSeconds),
        });

        _app.Map("/ws", HandleWebSocket);
        _app.Map("/", () => Results.Text($"PC Remote agent · v{GetVersion()}", "text/plain"));

        await _app.StartAsync(cancellationToken);

        _logger.LogInformation(
            "WebSocket server listening on wss://{Address}:{Port}/ws",
            _settings.WebSocket.BindAddress,
            _settings.WebSocket.Port);
    }

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

        try
        {
            // Phase 1: echo skeleton so wscat can test the pipe. Phase 2 hooks up
            // pair_init / pair_confirm / auth handshake and the CommandRouter.
            var buffer = new byte[8 * 1024];
            while (socket.State == System.Net.WebSockets.WebSocketState.Open)
            {
                var received = await socket.ReceiveAsync(buffer, ctx.RequestAborted);
                if (received.MessageType == System.Net.WebSockets.WebSocketMessageType.Close)
                {
                    await socket.CloseAsync(System.Net.WebSockets.WebSocketCloseStatus.NormalClosure, null, CancellationToken.None);
                    break;
                }
                var text = System.Text.Encoding.UTF8.GetString(buffer, 0, received.Count);
                _logger.LogDebug("← {Ip}: {Message}", clientIp, text);
                var reply = System.Text.Encoding.UTF8.GetBytes($"{{\"kind\":\"echo\",\"data\":{System.Text.Json.JsonSerializer.Serialize(text)}}}");
                await socket.SendAsync(reply, System.Net.WebSockets.WebSocketMessageType.Text, true, CancellationToken.None);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or System.Net.WebSockets.WebSocketException)
        {
            _logger.LogInformation("Client {Ip} disconnected: {Message}", clientIp, ex.Message);
        }
        finally
        {
            _connections.Unregister(tracked.Id);
        }
    }

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
