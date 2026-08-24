using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Windows;

/// <summary>
/// Skeleton — implemented in Phase 4 (post-MVP).
/// </summary>
public sealed class WindowsModule : ICommandModule
{
    public string Domain => "windows";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list","Enumerate top-level windows"),new CommandDescriptor("focus","Bring to front"),new CommandDescriptor("minimize","Minimize"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        var err = new ErrorInfo(ErrorCodes.InternalError, $"'{req.Action}' not implemented yet.");
        return Task.FromResult(new CommandResponse(req.Id, false, null, err, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }
}
