using Microsoft.Extensions.Logging;
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

    public CommandRouter(
        IEnumerable<ICommandModule> modules,
        ILogger<CommandRouter> logger,
        PcRemote.Core.Storage.CommandAuditLog? audit = null)
    {
        _modules = modules.ToDictionary(m => m.Domain, StringComparer.OrdinalIgnoreCase);
        _logger  = logger;
        _audit   = audit;
        _logger.LogInformation("CommandRouter loaded {Count} modules: {Domains}",
            _modules.Count, string.Join(", ", _modules.Keys));
    }

    public IReadOnlyDictionary<string, ICommandModule> Modules => _modules;

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
