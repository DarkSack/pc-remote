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
    /// <param name="moduleAssemblies">
    /// The built-in PcRemote.Modules.* assemblies, named explicitly by the exe. In a
    /// single-file build they live inside the exe, so scanning the folder for DLLs
    /// finds nothing and the agent would start with no modules at all.
    /// </param>
    [SupportedOSPlatform("windows")]
    public static IHost Build(
        IEnumerable<Assembly>? moduleAssemblies = null,
        Action<HostBuilderContext, IServiceCollection>? extraServices = null)
    {
        var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings
        {
            ContentRootPath = AppContext.BaseDirectory,
        });

        // Settings are bound once at startup; watching the files only cost a
        // FileSystemWatcher and suggested that edits apply live. They do not.
        AgentConfiguration.AddSources(builder.Configuration);

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

        // Activity timeline (sessions, pairings, alerts) for the phone.
        builder.Services.AddSingleton<ActivityLog>();

        // Plugins: which modules exist, where they came from, which are on.
        var pluginState = new PluginState(agentSettings.Storage.ResolvedPluginsStatePath);
        var pluginLog = new Serilog.Extensions.Logging.SerilogLoggerFactory(Log.Logger).CreateLogger("Plugins");
        var registry = PluginLoader.Discover(agentSettings.Storage.ResolvedPluginsDirectory, pluginState, pluginLog);
        builder.Services.AddSingleton(pluginState);
        builder.Services.AddSingleton(registry);
        builder.Services.AddSingleton<PluginManager>();

        // Router + modules (built-in, core and enabled external plugins)
        RegisterModules(builder.Services, moduleAssemblies ?? Array.Empty<Assembly>(), registry);
        builder.Services.AddSingleton<ICommandModule, PluginsModule>();
        builder.Services.AddSingleton<ICommandModule, ActivityModule>();
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
    /// Registers every non-abstract <see cref="ICommandModule"/> as a singleton, from
    /// the built-in assemblies, any PcRemote.Modules.*.dll next to the exe (a
    /// regular, non-single-file build) and the external plugins that loaded.
    /// </summary>
    private static void RegisterModules(IServiceCollection services, IEnumerable<Assembly> builtIn, PluginRegistry registry)
    {
        var assemblies = new List<Assembly>(builtIn);
        foreach (var dll in Directory.GetFiles(AppContext.BaseDirectory, "PcRemote.Modules.*.dll"))
        {
            try { assemblies.Add(Assembly.LoadFrom(dll)); }
            catch { /* skip broken */ }
        }

        var registered = new HashSet<Type>();
        foreach (var assembly in assemblies.Distinct())
            RegisterFrom(assembly, services, registered, external: null, registry);

        foreach (var plugin in registry.External.Where(p => p.Loaded && p.Assembly is not null))
        {
            var before = registered.Count;
            RegisterFrom(plugin.Assembly!, services, registered, plugin, registry);
            if (registered.Count == before) plugin.Error = "La DLL no contiene ningún ICommandModule.";
        }
    }

    private static void RegisterFrom(Assembly assembly, IServiceCollection services, HashSet<Type> registered,
        ExternalPlugin? external, PluginRegistry registry)
    {
        Type[] types;
        try { types = assembly.GetTypes(); }
        catch (ReflectionTypeLoadException ex) { types = ex.Types.Where(t => t is not null).Cast<Type>().ToArray(); }

        foreach (var type in types)
        {
            if (type.IsAbstract || type.IsInterface) continue;
            if (!typeof(ICommandModule).IsAssignableFrom(type)) continue;
            if (!registered.Add(type)) continue;

            services.AddSingleton(typeof(ICommandModule), type);
            if (external is not null) registry.MapType(type, external);
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
