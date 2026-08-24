using System.Runtime.Versioning;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;
using PcRemote.Core.Discovery;
using PcRemote.Core.Security;
using PcRemote.Core.Server;
using PcRemote.Core.Storage;

namespace PcRemote.Core;

/// <summary>
/// Boots the .NET generic host with all core services wired up.
/// Called from PcRemote.Agent.Program.
/// </summary>
public static class AgentHost
{
    [SupportedOSPlatform("windows")]
    public static IHost Build(Action<HostBuilderContext, IServiceCollection>? extraServices = null)
    {
        var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings
        {
            ContentRootPath = AppContext.BaseDirectory,
        });

        // Configuration — resolve relative to the executable so `dotnet run` works.
        builder.Configuration.AddJsonFile(
            Path.Combine(AppContext.BaseDirectory, "appsettings.json"),
            optional: false,
            reloadOnChange: true);

        var agentSettings = builder.Configuration.GetSection("Agent").Get<AgentSettings>() ?? new AgentSettings();
        builder.Services.AddSingleton(agentSettings);

        // Storage
        builder.Services.AddSingleton<AgentDatabase>();

        // Security / cert
        builder.Services.AddSingleton<CertificateProvider>();
        builder.Services.AddSingleton(sp =>
        {
            var provider = sp.GetRequiredService<CertificateProvider>();
            return provider.LoadOrCreate();
        });

        // Networking
        builder.Services.AddSingleton<ConnectionManager>();
        builder.Services.AddHostedService<WebSocketServer>();
        builder.Services.AddSingleton(sp => sp.GetServices<IHostedService>().OfType<WebSocketServer>().First());

        builder.Services.AddHostedService<MdnsPublisher>();

        // Startup task: ensure DB schema
        builder.Services.AddHostedService<DatabaseInitializer>();

        extraServices?.Invoke(new HostBuilderContext(new Dictionary<object, object>())
        {
            Configuration = builder.Configuration,
            HostingEnvironment = builder.Environment,
        }, builder.Services);

        return builder.Build();
    }
}

internal sealed class DatabaseInitializer(AgentDatabase db, ILogger<DatabaseInitializer> logger) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        db.EnsureSchema();
        logger.LogInformation("Database initialized");
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
