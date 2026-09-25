using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using PcRemote.Core.Activity;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Discovery;
using PcRemote.Core.Plugins;
using PcRemote.Core.Security;
using PcRemote.Core.Server;
using QRCoder;

namespace PcRemote.Core.Panel;

// ══════════════════════════════════════════════════════════════
// HTTP endpoints del panel de administración.
//
// Vive en un puerto loopback separado (por defecto 47810) — nunca
// accesible desde la red. Sin login porque:
//   1) Loopback-only: nadie externo puede alcanzarlo.
//   2) Si alguien tiene acceso local al PC, ya tiene el agente entero.
//
// PERO "loopback" no significa "solo yo": cualquier web abierta en el
// navegador de este PC puede lanzar peticiones a localhost. Por eso
// UsePanelGuard exige Host de loopback (corta el DNS rebinding) y
// rechaza peticiones cross-site (corta el CSRF).
//
// Endpoints:
//   GET  /              → HTML del panel (embedded resource)
//   GET  /api/status    → estado del agente
//   GET  /api/devices   → dispositivos emparejados (SQLite)
//   DELETE /api/devices/:id?hard=bool → revoca o borra, y corta la conexión viva
//   GET  /api/connections → sesiones WS activas
//   POST /api/pair/start  → genera código 6 dígitos + payload QR
//   GET  /api/pair/qr?data=... → PNG del QR
//   GET  /api/audit     → últimos comandos ejecutados (sin parámetros ni input continuo)
//   GET  /api/logs      → últimas líneas del ring buffer
//   GET  /api/activity  → línea de tiempo (la misma que ve la app)
//   GET  /api/plugins   → funciones opcionales y plugins instalados
//   POST /api/features/:key?enabled=bool → activa/desactiva (terminal, files, plugin:<id>)
//   POST /api/plugins/open-folder → abre la carpeta de plugins en el Explorador
//   GET  /favicon.svg
// ══════════════════════════════════════════════════════════════
public static class PanelEndpoints
{
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private static readonly HashSet<string> LoopbackHosts = new(StringComparer.OrdinalIgnoreCase)
    {
        "localhost", "127.0.0.1", "[::1]",
    };

    /// <summary>
    /// Rejects panel requests that did not come from the panel itself.
    ///
    /// - Host header: a page on evil.example whose DNS then flips to 127.0.0.1
    ///   (DNS rebinding) reaches us as same-origin for the browser, but its Host
    ///   still says evil.example. Only loopback names are accepted.
    /// - Sec-Fetch-Site / Origin: a cross-site page can still fire a blind
    ///   POST /api/pair/start or load resources. Browsers label those; requests
    ///   typed in the address bar or made by the panel are "none" / "same-origin".
    ///   Tools like curl send neither header and are allowed (they are local).
    /// </summary>
    public static void UsePanelGuard(this IApplicationBuilder app, int panelPort)
    {
        app.Use(async (ctx, next) =>
        {
            if (ctx.Connection.LocalPort != panelPort)
            {
                await next();
                return;
            }

            if (!LoopbackHosts.Contains(ctx.Request.Host.Host) ||
                (ctx.Request.Host.Port is { } port && port != panelPort))
            {
                ctx.Response.StatusCode = StatusCodes.Status403Forbidden;
                return;
            }

            var fetchSite = ctx.Request.Headers["Sec-Fetch-Site"].ToString();
            if (fetchSite.Length > 0 && fetchSite is not ("same-origin" or "none"))
            {
                ctx.Response.StatusCode = StatusCodes.Status403Forbidden;
                return;
            }

            var origin = ctx.Request.Headers.Origin.ToString();
            if (origin.Length > 0 &&
                !(Uri.TryCreate(origin, UriKind.Absolute, out var o) &&
                  LoopbackHosts.Contains(o.Host is "::1" ? "[::1]" : o.Host) &&
                  o.Port == panelPort))
            {
                ctx.Response.StatusCode = StatusCodes.Status403Forbidden;
                return;
            }

            ctx.Response.Headers["X-Frame-Options"] = "DENY";
            ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
            await next();
        });
    }

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
                certFingerprint = CertificateProvider.GetFingerprint(cert),
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

        // `hard` is optional: as a plain bool, a DELETE without ?hard= was a 400.
        app.MapDelete("/api/devices/{id}", async (HttpContext ctx, DeviceAdmin admin, string id, bool? hard) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var closed = hard == true
                ? await admin.DeleteAsync(id)
                : await admin.RevokeAsync(id);
            return Results.Ok(new { id, hardDeleted = hard == true, closedConnections = closed });
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
                deviceId    = c.DeviceId,
            });
            return Results.Json(items, Json);
        });

        app.MapPost("/api/pair/start", (HttpContext ctx, PairingService pairing, AgentSettings s, X509Certificate2 cert) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var code = pairing.IssuePanelCode();

            // Payload que va dentro del QR — el móvil lo parsea y auto-rellena.
            var payload = new
            {
                v    = 1,
                host = GuessLanIp(),
                port = s.WebSocket.Port,
                code = code.Code,
                fp   = CertificateProvider.GetFingerprint(cert),
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
            if (data.Length > 1024) return Results.BadRequest();
            using var gen = new QRCodeGenerator();
            using var qr  = gen.CreateQrCode(data, QRCodeGenerator.ECCLevel.M);
            using var png = new PngByteQRCode(qr);
            var bytes = png.GetGraphic(pixelsPerModule: 8);
            return Results.File(bytes, "image/png");
        });

        app.MapGet("/api/audit", (HttpContext ctx, PcRemote.Core.Storage.CommandAuditLog audit, int? limit) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            return Results.Json(audit.Recent(limit ?? 100), Json);
        });

        app.MapGet("/favicon.svg", (HttpContext ctx) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            return Results.Text(FaviconSvg, "image/svg+xml");
        });

        app.MapGet("/api/activity", (HttpContext ctx, ActivityLog activity, int? limit) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            return Results.Json(activity.Recent(limit ?? 60), Json);
        });

        app.MapGet("/api/plugins", (HttpContext ctx, PluginsModule plugins) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            return Results.Json(plugins.Describe(), Json);
        });

        // Only keys that exist: an optional module's domain, or plugin:<id> of a folder found now.
        app.MapPost("/api/features/{key}", (HttpContext ctx, string key, bool enabled,
            FeatureStore features, PluginsModule plugins, ActivityLog activity) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var label = plugins.FeatureLabel(key);
            if (label is null) return Results.NotFound();

            features.Set(key, enabled);
            activity.Add("plugin", $"{label} {(enabled ? "activado" : "desactivado")} en el panel", null, enabled ? "success" : "info");
            return Results.Ok(new { key, enabled });
        });

        app.MapPost("/api/plugins/open-folder", (HttpContext ctx, PluginCatalog catalog) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            catalog.EnsureFolder();
            Directory.CreateDirectory(catalog.Root);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = catalog.Root,
                UseShellExecute = true,
            })?.Dispose();
            return Results.Ok(new { folder = catalog.Root });
        });

        app.MapGet("/api/logs", (HttpContext ctx, int? limit) =>
        {
            if (!Match(ctx)) return Results.NotFound();
            var lines = InMemoryLogSink.Instance.Snapshot();
            var take = Math.Clamp(limit ?? 100, 1, 500);
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
        // Everything the panel needs is inline; nothing else may run in it.
        ctx.Response.Headers["Content-Security-Policy"] =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
        await stream.CopyToAsync(ctx.Response.Body);
    }

    private static string GuessLanIp() => LanAddress.Guess();

    /// <summary>Same mark as branding/logo.svg.</summary>
    private const string FaviconSvg =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 108 108'><rect x='4' y='4' width='100' height='100' rx='26' fill='#10161D'/><g fill='none' stroke='#57D9C3' stroke-linecap='round'><path stroke-width='5' d='M62.875 34.968 A21 21 0 0 1 74.92 55.831 M66.045 71.203 A21 21 0 0 1 41.955 71.203 M33.08 55.831 A21 21 0 0 1 45.125 34.968'/></g><g fill='#57D9C3'><circle cx='54' cy='54' r='8.5'/><circle cx='54' cy='33' r='5'/><circle cx='72.187' cy='64.5' r='5'/><circle cx='35.813' cy='64.5' r='5'/></g></svg>";

    private static string GetVersion() =>
        typeof(PanelEndpoints).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
}
