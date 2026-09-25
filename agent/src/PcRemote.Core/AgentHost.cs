using System.Reflection;
using System.Runtime.Versioning;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Activity;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Discovery;
using PcRemote.Core.Plugins;
using PcRemote.Core.Router;
using PcRemote.Core.Security;
using PcRemote.Core.Server;
using PcRemote.Core.Storage;
using Serilog;

namespace PcRemote.Core;

/// <summary>
/// Boots the .NET generic host with all core services wired up.
/// Called from PcRemote.Agent.Program.
/// </summary>
public static class AgentHost
{
    /// <param name="configuration">Built by the caller (Program): embedded defaults plus optional overrides.</param>
    /// <param name="moduleAssemblies">
    /// Assemblies with the built-in modules. Passed explicitly because in a
    /// single-file build there are no PcRemote.Modules.*.dll files to scan.
    /// </param>
    [SupportedOSPlatform("windows")]
    public static IHost Build(
        IConfiguration configuration,
        IEnumerable<Assembly> moduleAssemblies,
        Action<HostBuilderContext, IServiceCollection>? extraServices = null)
    {
        var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings
        {
            ContentRootPath = AppContext.BaseDirectory,
            // Only what the caller built: no appsettings.{Environment}.json, no env vars surprises.
            DisableDefaults = true,
        });

        builder.Configuration.AddConfiguration(configuration);

        // Every ILogger<T> goes to Serilog (file + the panel's ring buffer). Without
        // this the host kept its default console/debug providers: a WinExe has no
        // console, so the file and /api/logs only ever saw what Program logged itself.
        // Log.Logger must be configured before Build() — see Program.
        builder.Logging.ClearProviders();
        builder.Logging.AddSerilog(Log.Logger, dispose: false);

        var agentSettings = builder.Configuration.GetSection("Agent").Get<AgentSettings>() ?? new AgentSettings();
        builder.Services.AddSingleton(agentSettings);

        // Storage
        builder.Services.AddSingleton<AgentDatabase>();
        builder.Services.AddHostedService<DatabaseInitializer>();
        // After DatabaseInitializer: hosted services start in registration order,
        // and the audit writer purges old rows as soon as it starts.
        builder.Services.AddSingleton<CommandAuditLog>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<CommandAuditLog>());

        // Security / cert
        builder.Services.AddSingleton<CertificateProvider>();
        builder.Services.AddSingleton(sp => sp.GetRequiredService<CertificateProvider>().LoadOrCreate());

        // Auth
        builder.Services.AddSingleton<DeviceRepository>();
        builder.Services.AddSingleton<PairingService>();
        builder.Services.AddSingleton<SessionManager>();
        builder.Services.AddSingleton<DeviceAdmin>();

        // Activity timeline, optional features and plugins
        builder.Services.AddSingleton<ActivityLog>();
        builder.Services.AddSingleton<FeatureStore>();
        builder.Services.AddSingleton<PluginCatalog>();
        builder.Services.AddSingleton<PluginsModule>();
        builder.Services.AddSingleton<ICommandModule>(sp => sp.GetRequiredService<PluginsModule>());
        builder.Services.AddSingleton<ICommandModule, ActivityModule>();
        builder.Services.AddHostedService<StartupEvents>();

        // Router + modules (built-in assemblies, dev folder scan, enabled assembly plugins)
        RegisterModules(builder.Services, moduleAssemblies, agentSettings);
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
    /// Registers every non-abstract <see cref="ICommandModule"/> as a singleton, from:
    ///   1. the built-in module assemblies the caller passes in;
    ///   2. any PcRemote.Modules.*.dll next to the executable (dev builds, extra modules);
    ///   3. assembly plugins (plugin.json with "assembly") that are enabled in the panel.
    /// Disabled plugins are not even loaded: their code never runs.
    /// </summary>
    private static void RegisterModules(IServiceCollection services, IEnumerable<Assembly> builtIn, AgentSettings settings)
    {
        var assemblies = new List<Assembly>(builtIn);

        foreach (var dll in Directory.GetFiles(AppContext.BaseDirectory, "PcRemote.Modules.*.dll"))
        {
            try { assemblies.Add(Assembly.LoadFrom(dll)); }
            catch (Exception ex) { Log.Warning(ex, "Could not load {Dll}", dll); }
        }

        var features = new FeatureStore(settings, Microsoft.Extensions.Logging.Abstractions.NullLogger<FeatureStore>.Instance);
        var catalog  = new PluginCatalog(settings, Microsoft.Extensions.Logging.Abstractions.NullLogger<PluginCatalog>.Instance);
        catalog.EnsureFolder();
        foreach (var plugin in catalog.Scan().Where(p => p.IsAssembly && p.Errors.Count == 0))
        {
            if (!features.IsEnabled(plugin.FeatureKey, false)) continue;
            var dll = Path.GetFullPath(Path.Combine(plugin.Directory, plugin.Manifest.Assembly!));
            try
            {
                var asm = Assembly.LoadFrom(dll);
                PluginAssemblies.Register(asm, plugin.Id);
                assemblies.Add(asm);
                Log.Information("Loaded plugin assembly {Plugin} from {Dll}", plugin.Id, dll);
            }
            catch (Exception ex)
            {
                Log.Warning(ex, "Could not load plugin {Plugin} ({Dll})", plugin.Id, dll);
            }
        }

        var registered = new HashSet<Type>();
        foreach (var assembly in assemblies.Distinct())
        {
            if (assembly.IsDynamic) continue;

            Type[] types;
            try { types = assembly.GetTypes(); }
            catch (ReflectionTypeLoadException ex) { types = ex.Types.Where(t => t is not null).Cast<Type>().ToArray(); }

            foreach (var type in types)
            {
                if (type.IsAbstract || type.IsInterface || !type.IsPublic) continue;
                if (!typeof(ICommandModule).IsAssignableFrom(type)) continue;
                if (!registered.Add(type)) continue;

                services.AddSingleton(typeof(ICommandModule), type);
            }
        }
    }
}

/// <summary>First entries of the activity timeline.</summary>
internal sealed class StartupEvents(ActivityLog activity) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        var uptime = TimeSpan.FromMilliseconds(Environment.TickCount64);
        if (uptime < TimeSpan.FromMinutes(10))
            activity.Add("power", "PC encendido", $"Hace {Math.Max(1, (int)uptime.TotalMinutes)} min", "success");
        activity.Add("agent", "Agente de PC Remote iniciado", $"v{typeof(AgentHost).Assembly.GetName().Version?.ToString(3)}");
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
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
