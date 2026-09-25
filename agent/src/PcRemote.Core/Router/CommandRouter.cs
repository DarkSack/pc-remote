using Microsoft.Extensions.Logging;
using PcRemote.Core.Plugins;
using PcRemote.Core.Protocol;

namespace PcRemote.Core.Router;

/// <summary>
/// Routes commands to the module registered for their domain.
/// Modules are supplied via DI (see AgentHost.RegisterModules).
/// </summary>
public sealed class CommandRouter
{
    private readonly Dictionary<string, ICommandModule> _modules;
    private readonly ILogger<CommandRouter> _logger;
    private readonly PcRemote.Core.Storage.CommandAuditLog? _audit;
    private readonly FeatureStore? _features;

    public CommandRouter(
        IEnumerable<ICommandModule> modules,
        ILogger<CommandRouter> logger,
        PcRemote.Core.Storage.CommandAuditLog? audit = null,
        FeatureStore? features = null)
    {
        _modules = new Dictionary<string, ICommandModule>(StringComparer.OrdinalIgnoreCase);
        foreach (var m in modules)
        {
            // Two modules on one domain (a plugin reusing "system") used to throw from
            // ToDictionary and take the whole agent down at startup. First one wins.
            if (!_modules.TryAdd(m.Domain, m))
                logger.LogWarning("Ignoring {Type}: domain '{Domain}' is already taken by {Other}",
                    m.GetType().FullName, m.Domain, _modules[m.Domain].GetType().FullName);
        }
        _logger   = logger;
        _audit    = audit;
        _features = features;
        _logger.LogInformation("CommandRouter loaded {Count} modules: {Domains}",
            _modules.Count, string.Join(", ", _modules.Keys));
    }

    public IReadOnlyDictionary<string, ICommandModule> Modules => _modules;

    /// <summary>
    /// Optional features and assembly plugins answer only while enabled in the panel.
    /// Checked on every request and subscribe, so switching one off applies at once.
    /// </summary>
    public bool IsEnabled(ICommandModule module)
    {
        if (_features is null) return true;
        if (PluginAssemblies.PluginIdOf(module.GetType()) is { } pluginId)
            return _features.IsEnabled("plugin:" + pluginId, false);
        if (module is IOptionalModule optional)
            return _features.IsEnabled(module.Domain, optional.EnabledByDefault);
        return true;
    }

    public static string DisabledMessage(ICommandModule module) =>
        module is IOptionalModule o
            ? $"«{o.FeatureName}» está desactivado en este PC. Actívalo en el panel del agente."
            : $"El plugin de '{module.Domain}' está desactivado en este PC. Actívalo en el panel del agente.";

    public async Task<CommandResponse> DispatchAsync(
        CommandRequest req,
        ClientSession session,
        CancellationToken ct)
    {
        if (!_modules.TryGetValue(req.Domain, out var module))
        {
            _logger.LogWarning("Unknown domain: {Domain}", req.Domain);
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                $"Unknown domain '{req.Domain}'.");
        }

        if (!IsEnabled(module))
            return CommandResponse.Fail(req.Id, ErrorCodes.FeatureDisabled, DisabledMessage(module));

        CommandResponse response;
        try
        {
            response = await module.HandleAsync(req, session, ct);
        }
        catch (OperationCanceledException)
        {
            return CommandResponse.Fail(req.Id, ErrorCodes.Timeout, "Cancelled.");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Handler for {Domain}.{Action} threw", req.Domain, req.Action);
            response = CommandResponse.FromException(req.Id, ex);
        }

        // A touchpad sends ~60 moves a second: at Information they flooded the
        // log file and pushed everything else out of the panel's 500-line buffer.
        var level = PcRemote.Core.Storage.CommandAuditLog.IsHighFrequency(req.Domain, req.Action) && response.Success
            ? LogLevel.Debug
            : LogLevel.Information;
        _logger.Log(level, "[{Session}] {Domain}.{Action} → {Success}",
            session.SessionId[..8], req.Domain, req.Action, response.Success);

        _audit?.Record(session, req, response);
        return response;
    }
}
