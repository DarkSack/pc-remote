using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Server;
using QRCoder;

namespace PcRemote.Core.Panel;

// ══════════════════════════════════════════════════════════════
// HTTP endpoints del panel de administración.
//
// Vive en un puerto loopback separado (por defecto 47810) — nunca
// accesible desde la red. Sin auth porque:
//   1) Loopback-only: nadie externo puede alcanzarlo.
//   2) Si alguien tiene acceso local al PC, ya tiene el agente entero.
//
// Endpoints:
//   GET  /              → HTML del panel (embedded resource)
//   GET  /api/status    → estado del agente
//   GET  /api/devices   → dispositivos emparejados (SQLite)
//   DELETE /api/devices/:id → revoca (soft delete)
//   GET  /api/connections → sesiones WS activas
//   POST /api/pair/start  → genera código 6 dígitos + payload QR
//   GET  /api/pair/qr?data=... → PNG del QR (base64 data URL)
//   GET  /api/logs      → últimas líneas del ring buffer
// ══════════════════════════════════════════════════════════════
public static class PanelEndpoints
{
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static void MapPanel(this IEndpointRouteBuilder app, int panelPort)
    {
        // Solo respondemos en el puerto del panel (loopback).
        bool Match(HttpContext ctx) => ctx.Connection.LocalPort == panelPort;

        app.MapGet("/", async (HttpContext ctx) =>
        {
            if (!Match(ctx)) { ctx.Response.StatusCode = 404; return; }
            await ServeHtml(ctx);
        });

        app.MapGet("/api/status", (HttpContext ctx, AgentSettings s, X509Certificate2 cert) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            return Results.Json(new
            {
                hostname       = Environment.MachineName,
                username       = Environment.UserName,
                lanIp          = GuessLanIp(),
                wsPort         = s.WebSocket.Port,
                wsBindAddress  = s.WebSocket.BindAddress,
                mdnsService    = s.Discovery.MdnsServiceType,
                certFingerprint = cert.GetCertHashString(System.Security.Cryptography.HashAlgorithmName.SHA256).ToLowerInvariant(),
                pairing        = new
                {
                    codeTtlSec    = s.Pairing.CodeTtlSeconds,
                    maxAttempts   = s.Pairing.MaxAttempts,
                    lockoutSec    = s.Pairing.LockoutSeconds,
                },
                uptimeSec      = (int)(Environment.TickCount64 / 1000),
                version        = GetVersion(),
            }, Json);
        });

        app.MapGet("/api/devices", (HttpContext ctx, DeviceRepository repo) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var devices = repo.List().Select(d => new
            {
                id         = d.Id,
                name       = d.Name,
                pairedAt   = d.PairedAt,
                lastSeenAt = d.LastSeenAt,
                revoked    = d.Revoked,
            });
            return Results.Json(devices, Json);
        });

        app.MapDelete("/api/devices/{id}", (HttpContext ctx, DeviceRepository repo, string id, bool hard) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            if (hard) repo.Delete(id);
            else repo.Revoke(id);
            return Results.Ok(new { id, hardDeleted = hard });
        });

        app.MapGet("/api/connections", (HttpContext ctx, ConnectionManager conns) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var items = conns.Active.Select(c => new
            {
                id          = c.Id,
                clientIp    = c.ClientIp,
                connectedAt = c.ConnectedAt,
                state       = c.Socket.State.ToString(),
            });
            return Results.Json(items, Json);
        });

        app.MapPost("/api/pair/start", (HttpContext ctx, PairingService pairing, AgentSettings s, X509Certificate2 cert) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var code = pairing.IssueCode("panel");

            // Payload que va dentro del QR — el móvil lo parsea y auto-rellena.
            var payload = new
            {
                v    = 1,
                host = GuessLanIp(),
                port = s.WebSocket.Port,
                code = code.Code,
                fp   = cert.GetCertHashString(System.Security.Cryptography.HashAlgorithmName.SHA256).ToLowerInvariant(),
                name = Environment.MachineName,
            };
            var qrData = "pcremote://pair?" + JsonSerializer.Serialize(payload, Json);

            return Results.Json(new
            {
                code       = code.Code,
                issuedAt   = code.IssuedAt,
                expiresAt  = code.ExpiresAt,
                ttlSec     = s.Pairing.CodeTtlSeconds,
                qrData,
            }, Json);
        });

        app.MapGet("/api/pair/qr", (HttpContext ctx, string data) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            using var gen = new QRCodeGenerator();
            using var qr  = gen.CreateQrCode(data, QRCodeGenerator.ECCLevel.M);
            using var png = new PngByteQRCode(qr);
            var bytes = png.GetGraphic(pixelsPerModule: 8);
            return Results.File(bytes, "image/png");
        });

        app.MapGet("/api/logs", (HttpContext ctx, int? limit) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var lines = InMemoryLogSink.Instance.Snapshot();
            var take = limit ?? 100;
            var slice = lines.Count > take ? lines.Skip(lines.Count - take).ToArray() : lines.ToArray();
            return Results.Json(slice, Json);
        });
    }

    // ── Helpers ─────────────────────────────────────────────

    private static async Task ServeHtml(HttpContext ctx)
    {
        var asm = typeof(PanelEndpoints).Assembly;
        var resource = asm.GetManifestResourceNames()
            .FirstOrDefault(n => n.EndsWith("panel.html", StringComparison.Ordinal));
        if (resource == null)
        {
            ctx.Response.StatusCode = 500;
            await ctx.Response.WriteAsync("panel.html embedded resource not found");
            return;
        }
        using var stream = asm.GetManifestResourceStream(resource)!;
        ctx.Response.ContentType = "text/html; charset=utf-8";
        await stream.CopyToAsync(ctx.Response.Body);
    }

    private static string GuessLanIp()
    {
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var ip in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ip.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(ip.Address)) continue;
                    var b = ip.Address.GetAddressBytes();
                    // Preferir rangos privados típicos de LAN
                    if (b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] >= 16 && b[1] <= 31))
                        return ip.Address.ToString();
                }
            }
        }
        catch { }
        return "127.0.0.1";
    }

    private static string GetVersion() =>
        typeof(PanelEndpoints).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
}
