using System.Reflection;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Activity;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Core.Plugins;

/// <summary>
/// Which assembly came from which plugin, so the router can gate the modules of
/// an assembly plugin behind its enabled flag.
/// </summary>
public static class PluginAssemblies
{
    private static readonly Dictionary<Assembly, string> Owners = new();

    internal static void Register(Assembly assembly, string pluginId)
    {
        lock (Owners) Owners[assembly] = pluginId;
    }

    public static string? PluginIdOf(Type type)
    {
        lock (Owners) return Owners.TryGetValue(type.Assembly, out var id) ? id : null;
    }
}

/// <summary>
/// <c>plugins.list</c> → optional features (terminal, files…) and the plugins
/// found in the plugins folder, with their actions.
/// <c>plugins.run</c> <c>{ plugin, action, params }</c> → runs one action of an enabled plugin.
///
/// Neither lets the phone enable anything: that is done in the panel.
/// </summary>
public sealed class PluginsModule(
    PluginCatalog catalog,
    FeatureStore features,
    ActivityLog activity,
    IServiceProvider services,
    ILogger<PluginsModule> logger) : ICommandModule
{
    public string Domain => "plugins";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list", "Funciones opcionales y plugins instalados"),
        new CommandDescriptor("run",  "Ejecutar una acción de un plugin"),
    };

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return req.Action switch
            {
                "list" => CommandResponse.Ok(req.Id, Describe()),
                "run"  => await RunAsync(req, session, ct),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (PluginParamException ex)
        {
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, ex.Message);
        }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }

    /// <summary>Display name of a feature key (a module domain or plugin:&lt;id&gt;); null when nothing has that key.</summary>
    public string? FeatureLabel(string key)
    {
        if (key.StartsWith("plugin:", StringComparison.OrdinalIgnoreCase))
            return catalog.Find(key["plugin:".Length..])?.Manifest.Name;
        var router = services.GetRequiredService<CommandRouter>();
        return router.Modules.TryGetValue(key, out var module) && module is IOptionalModule optional
            ? optional.FeatureName
            : null;
    }

    /// <summary>Shape shared with the panel's /api/plugins.</summary>
    public object Describe()
    {
        var router = services.GetRequiredService<CommandRouter>();
        return new
        {
            folder = catalog.Root,
            features = router.Modules.Values
                .OfType<IOptionalModule>()
                .Select(m => new
                {
                    id = ((ICommandModule)m).Domain,
                    name = m.FeatureName,
                    description = m.FeatureDescription,
                    icon = m.FeatureIcon,
                    enabled = features.IsEnabled(((ICommandModule)m).Domain, m.EnabledByDefault),
                })
                .OrderBy(f => f.name)
                .ToArray(),
            plugins = catalog.Scan().Select(p => new
            {
                id = p.Id,
                name = p.Manifest.Name,
                description = p.Manifest.Description,
                icon = p.Manifest.Icon,
                version = p.Manifest.Version,
                author = p.Manifest.Author,
                kind = p.IsAssembly ? "assembly" : "actions",
                enabled = features.IsEnabled(p.FeatureKey, false),
                // An assembly plugin is loaded at startup: enabling it takes a restart.
                loaded = !p.IsAssembly || router.Modules.Values.Any(m => PluginAssemblies.PluginIdOf(m.GetType()) == p.Id),
                errors = p.Errors,
                actions = p.Manifest.Actions.Select(a => new
                {
                    id = a.Id,
                    label = a.Label ?? a.Id,
                    description = a.Description,
                    icon = a.Icon,
                    confirm = a.Confirm,
                    output = a.Output,
                    timeoutSec = a.Output ? Math.Clamp(a.TimeoutSec, 1, 600) : 0,
                    @params = a.Params.Select(x => new
                    {
                        id = x.Id,
                        label = x.Label ?? x.Id,
                        type = x.Type,
                        required = x.Required,
                        @default = x.Default,
                        options = x.Options,
                        min = x.Min,
                        max = x.Max,
                        placeholder = x.Placeholder,
                    }),
                }),
                // Modules of an assembly plugin, for the app to show what it adds.
                domains = router.Modules.Values
                    .Where(m => PluginAssemblies.PluginIdOf(m.GetType()) == p.Id)
                    .Select(m => new { domain = m.Domain, actions = m.Commands.Select(c => c.Action) }),
            }).ToArray(),
        };
    }

    private async Task<CommandResponse> RunAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        var p = req.Params ?? default;
        var pluginId = p.GetProperty("plugin").GetString() ?? "";
        var actionId = p.GetProperty("action").GetString() ?? "";

        var plugin = catalog.Find(pluginId);
        if (plugin is null) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"No hay ningún plugin '{pluginId}'.");
        if (!features.IsEnabled(plugin.FeatureKey, false))
            return CommandResponse.Fail(req.Id, ErrorCodes.FeatureDisabled, $"El plugin «{plugin.Manifest.Name}» está desactivado. Actívalo en el panel del PC.");
        if (plugin.Errors.Count > 0)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"El plugin tiene errores: {plugin.Errors[0]}");

        var action = plugin.Manifest.Actions.FirstOrDefault(a => string.Equals(a.Id, actionId, StringComparison.OrdinalIgnoreCase));
        if (action is null) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"El plugin no tiene la acción '{actionId}'.");

        var values = PluginRunner.BindParams(action, p.TryGetProperty("params", out var v) ? v : null);
        var label = action.Label ?? action.Id;
        logger.LogInformation("[{Sess}] plugin {Plugin}.{Action}", session.SessionId[..8], plugin.Id, action.Id);

        PluginRunResult result;
        try
        {
            result = await PluginRunner.RunAsync(plugin, action, values, ct);
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            activity.Add("plugin", $"{plugin.Manifest.Name}: {label}", ex.Message, "error");
            return CommandResponse.Fail(req.Id, ErrorCodes.InternalError, $"No se pudo ejecutar: {ex.Message}");
        }

        var ok = result.ExitCode is null or 0 && !result.TimedOut;
        activity.Add("plugin", $"{plugin.Manifest.Name}: {label}",
            result.TimedOut ? "Tiempo agotado" : result.ExitCode is { } code ? $"Código de salida {code}" : "Iniciado",
            ok ? "success" : "warning");

        return CommandResponse.Ok(req.Id, new
        {
            started = result.Started,
            exitCode = result.ExitCode,
            stdout = result.Stdout,
            stderr = result.Stderr,
            timedOut = result.TimedOut,
            truncated = result.Truncated,
        });
    }
}
