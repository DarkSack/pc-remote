using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Applications;

/// <summary>
/// Skeleton — implemented in Phase 4 (post-MVP).
/// </summary>
public sealed class ApplicationsModule : ICommandModule
{
    public string Domain => "applications";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list","Enumerate installed apps"),new CommandDescriptor("launch","Launch app"),new CommandDescriptor("kill","Kill app"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InternalError, $"'{req.Action}' not implemented yet."));
    }
}
