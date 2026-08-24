using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Clipboard;

/// <summary>
/// Skeleton — implemented in Phase 4 (post-MVP).
/// </summary>
public sealed class ClipboardModule : ICommandModule
{
    public string Domain => "clipboard";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("get","Read clipboard"),new CommandDescriptor("set","Write clipboard"),new CommandDescriptor("watch","Subscribe to changes"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        var err = new ErrorInfo(ErrorCodes.InternalError, $"'{req.Action}' not implemented yet.");
        return Task.FromResult(new CommandResponse(req.Id, false, null, err, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }
}
