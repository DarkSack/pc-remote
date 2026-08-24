using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.System;

/// <summary>
/// Trivial module for Phase 2 verification: responds with pong + agent version.
/// Kept alongside the real System module while other domains are wired up.
/// </summary>
public sealed class PingModule : ICommandModule
{
    public string Domain => "ping";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("ping", "Round-trip health check"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        if (req.Action != "ping")
        {
            return Task.FromResult(CommandResponse.Fail(
                req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}' in ping domain."));
        }

        var payload = new
        {
            pong        = true,
            agentVersion= typeof(PingModule).Assembly.GetName().Version?.ToString(3) ?? "0.1.0",
            sessionId   = session.SessionId,
            device      = session.DeviceName,
            serverTime  = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
        return Task.FromResult(CommandResponse.Ok(req.Id, payload));
    }
}
