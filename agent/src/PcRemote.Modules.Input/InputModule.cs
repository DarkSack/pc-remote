using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Input;

/// <summary>
/// Skeleton — implemented in Phase 4 (post-MVP).
/// </summary>
public sealed class InputModule : ICommandModule
{
    public string Domain => "input";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("mouseMove","Move mouse relative"),new CommandDescriptor("mouseClick","Click"),new CommandDescriptor("keyPress","Key press"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        var err = new ErrorInfo(ErrorCodes.InternalError, $"'{req.Action}' not implemented yet.");
        return Task.FromResult(new CommandResponse(req.Id, false, null, err, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }
}
