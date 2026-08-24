using System.Reflection;
using System.Runtime.Versioning;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Discovery;
using PcRemote.Core.Router;
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

        builder.Configuration.AddJsonFile(
            Path.Combine(AppContext.BaseDirectory, "appsettings.json"),
            optional: false,
            reloadOnChange: true);

        var agentSettings = builder.Configuration.GetSection("Agent").Get<AgentSettings>() ?? new AgentSettings();
        builder.Services.AddSingleton(agentSettings);

        // Storage
        builder.Services.AddSingleton<AgentDatabase>();
        builder.Services.AddHostedService<DatabaseInitializer>();

        // Security / cert
        builder.Services.AddSingleton<CertificateProvider>();
        builder.Services.AddSingleton(sp => sp.GetRequiredService<CertificateProvider>().LoadOrCreate());

        // Auth
        builder.Services.AddSingleton<DeviceRepository>();
        builder.Services.AddSingleton<PairingService>();
        builder.Services.AddSingleton<SessionManager>();

        // Router + modules (via reflection on loaded assemblies)
        RegisterModules(builder.Services);
        builder.Services.AddSingleton<CommandRouter>();

        // Networking
        builder.Services.AddSingleton<ConnectionManager>();
        builder.Services.AddSingleton<WebSocketServer>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<WebSocketServer>());
        builder.Services.AddHostedService<MdnsPublisher>();

        extraServices?.Invoke(new HostBuilderContext(new Dictionary<object, object>())
        {
            Configuration      = builder.Configuration,
            HostingEnvironment = builder.Environment,
        }, builder.Services);

        return builder.Build();
    }

    /// <summary>
    /// Discovers every non-abstract type implementing <see cref="ICommandModule"/> and
    /// registers it as singleton. Loads PcRemote.Modules.*.dll from the executable's
    /// directory first because .NET otherwise only loads them lazily on first use.
    /// </summary>
    private static void RegisterModules(IServiceCollection services)
    {
        // Force-load every PcRemote.Modules.* assembly next to the exe so reflection sees them.
        var baseDir = AppContext.BaseDirectory;
        foreach (var dll in Directory.GetFiles(baseDir, "PcRemote.Modules.*.dll"))
        {
            try { Assembly.LoadFrom(dll); }
            catch { /* skip broken */ }
        }

        var registered = new HashSet<Type>();

        foreach (var assembly in AppDomain.CurrentDomain.GetAssemblies())
        {
            if (assembly.IsDynamic) continue;
            if (!assembly.GetName().Name?.StartsWith("PcRemote.Modules.") ?? true) continue;

            Type[] types;
            try { types = assembly.GetTypes(); }
            catch (ReflectionTypeLoadException ex) { types = ex.Types.Where(t => t is not null).Cast<Type>().ToArray(); }

            foreach (var type in types)
            {
                if (type.IsAbstract || type.IsInterface) continue;
                if (!typeof(ICommandModule).IsAssignableFrom(type)) continue;
                if (!registered.Add(type)) continue;

                services.AddSingleton(typeof(ICommandModule), type);
            }
        }
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
