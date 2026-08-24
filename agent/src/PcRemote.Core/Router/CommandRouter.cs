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

    public CommandRouter(IEnumerable<ICommandModule> modules, ILogger<CommandRouter> logger)
    {
        _modules = modules.ToDictionary(m => m.Domain, StringComparer.OrdinalIgnoreCase);
        _logger  = logger;
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

        try
        {
            var response = await module.HandleAsync(req, session, ct);
            _logger.LogInformation("[{Session}] {Domain}.{Action} → {Success}",
                session.SessionId[..8], req.Domain, req.Action, response.Success);
            return response;
        }
        catch (OperationCanceledException)
        {
            return CommandResponse.Fail(req.Id, ErrorCodes.Timeout, "Cancelled.");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Handler for {Domain}.{Action} threw", req.Domain, req.Action);
            return CommandResponse.Fail(req.Id, ErrorCodes.InternalError, ex.Message);
        }
    }
}
