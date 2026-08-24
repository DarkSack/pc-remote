using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Processes;

/// <summary>
/// Skeleton — implemented in Phase 4 (post-MVP).
/// </summary>
public sealed class ProcessesModule : ICommandModule
{
    public string Domain => "processes";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list","List processes"),new CommandDescriptor("kill","Kill process by PID",IsDestructive:true),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        var err = new ErrorInfo(ErrorCodes.InternalError, $"'{req.Action}' not implemented yet.");
        return Task.FromResult(new CommandResponse(req.Id, false, null, err, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }
}
