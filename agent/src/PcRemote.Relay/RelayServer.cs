using System.Threading.RateLimiting;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;

namespace PcRemote.Relay;

public static class RelayServer
{
    /// <summary>The relay app; <c>Program</c> runs it, the tests start it on a free port.</summary>
    public static WebApplication Build(string urls, RelayOptions? options = null)
    {
        options ??= FromEnvironment();
        var builder = WebApplication.CreateSlimBuilder();
        builder.WebHost.UseUrls(urls);
        // Kestrel's default accept queue (512) overflows when thousands of PCs reconnect at
        // once after a restart: the kernel then refuses them. On Linux the kernel also caps
        // it at net.core.somaxconn (raised in deploy/relay).
        builder.WebHost.UseSockets(o => o.Backlog = 8192);
        builder.WebHost.ConfigureKestrel(k =>
        {
            k.AddServerHeader = false;
            // Each tunnel is two upgraded connections (phone + PC), plus one control socket per PC.
            k.Limits.MaxConcurrentUpgradedConnections = options.MaxTunnels * 2L + options.MaxAgents;
            k.Limits.MaxConcurrentConnections = k.Limits.MaxConcurrentUpgradedConnections + 1_000;
            // Slow-header clients must not hold sockets open.
            k.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
        });
        // A line per request would cost more than the relaying itself at thousands of tunnels.
        builder.Logging.AddFilter("Microsoft.AspNetCore", LogLevel.Warning);
        builder.Services.AddSingleton(options);
        builder.Services.AddSingleton<RelayHub>();
        builder.Services.AddHostedService<StatsLogger>();

        // Behind Caddy, which terminates TLS. The relay port is published on the host's
        // loopback only (deploy/relay), so trusting X-Forwarded-For here is safe.
        builder.Services.Configure<ForwardedHeadersOptions>(o =>
        {
            o.ForwardedHeaders = ForwardedHeaders.XForwardedFor;
            o.KnownIPNetworks.Clear();
            o.KnownProxies.Clear();
        });

        // Per-IP windows. The built-in partitioned limiter drops idle IPs on its own,
        // so memory follows the IPs active right now, not every IP ever seen.
        builder.Services.AddRateLimiter(o =>
        {
            o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            o.AddPolicy("connect", ctx => PerIp(ctx, options.ConnectsPerMinute));
            o.AddPolicy("agent", ctx => PerIp(ctx, options.AgentLoginsPerMinute));
        });

        var app = builder.Build();
        app.UseForwardedHeaders();
        app.UseRateLimiter();
        app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(20),
            // A PC or phone that vanished without closing is dropped instead of kept forever.
            KeepAliveTimeout = TimeSpan.FromSeconds(20),
        });

        var hub = app.Services.GetRequiredService<RelayHub>();
        app.Map("/agent", hub.AgentAsync).RequireRateLimiting("agent");
        app.Map("/connect/{id}", (HttpContext ctx, string id) => hub.ConnectAsync(ctx, id)).RequireRateLimiting("connect");
        // Not limited: the token is single-use and only exists for 10 s after a limited /connect.
        app.Map("/accept/{token}", (HttpContext ctx, string token) => hub.AcceptAsync(ctx, token));
        app.MapGet("/healthz", () => "ok");
        return app;
    }

    private static RateLimitPartition<string> PerIp(HttpContext ctx, int perMinute) =>
        RateLimitPartition.GetFixedWindowLimiter(ctx.Connection.RemoteIpAddress?.ToString() ?? "", _ => new FixedWindowRateLimiterOptions
        {
            PermitLimit = perMinute,
            Window = TimeSpan.FromMinutes(1),
            QueueLimit = 0,
        });

    /// <summary>Limits from RELAY_* environment variables; unset ones keep their default.</summary>
    public static RelayOptions FromEnvironment()
    {
        var d = new RelayOptions();
        static long Env(string name, long fallback) =>
            long.TryParse(Environment.GetEnvironmentVariable(name), out var v) && v > 0 ? v : fallback;
        return d with
        {
            MaxTunnels = (int)Env("RELAY_MAX_TUNNELS", d.MaxTunnels),
            MaxAgents = (int)Env("RELAY_MAX_AGENTS", d.MaxAgents),
            MaxSessionsPerAgent = (int)Env("RELAY_MAX_SESSIONS_PER_PC", d.MaxSessionsPerAgent),
            TunnelBytesPerSecond = Env("RELAY_TUNNEL_BYTES_PER_SECOND", d.TunnelBytesPerSecond),
            TunnelBurstBytes = Env("RELAY_TUNNEL_BURST_BYTES", d.TunnelBurstBytes),
            ConnectsPerMinute = (int)Env("RELAY_CONNECTS_PER_MINUTE", d.ConnectsPerMinute),
            AgentLoginsPerMinute = (int)Env("RELAY_PC_LOGINS_PER_MINUTE", d.AgentLoginsPerMinute),
        };
    }

    /// <summary>One line a minute with the load, instead of a line per connection.</summary>
    private sealed class StatsLogger(RelayHub hub, ILogger<StatsLogger> logger) : BackgroundService
    {
        protected override async Task ExecuteAsync(CancellationToken ct)
        {
            using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1));
            long lastBytes = 0;
            while (await timer.WaitForNextTickAsync(ct))
            {
                var (agents, tunnels, bytes) = hub.Stats;
                logger.LogInformation("PCs {Agents} · túneles {Tunnels} · {Mbps:F1} Mbit/s",
                    agents, tunnels, (bytes - lastBytes) * 8 / 60.0 / 1_000_000);
                lastBytes = bytes;
            }
        }
    }
}
