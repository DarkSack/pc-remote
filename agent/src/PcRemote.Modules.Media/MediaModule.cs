using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Media;

/// <summary>
/// Skeleton — implemented in Phase 4 (post-MVP).
/// </summary>
public sealed class MediaModule : ICommandModule
{
    public string Domain => "media";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("play","Play"),new CommandDescriptor("pause","Pause"),new CommandDescriptor("next","Next"),new CommandDescriptor("previous","Previous"),new CommandDescriptor("volumeSet","Set volume 0-100"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        var err = new ErrorInfo(ErrorCodes.InternalError, $"'{req.Action}' not implemented yet.");
        return Task.FromResult(new CommandResponse(req.Id, false, null, err, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }
}
