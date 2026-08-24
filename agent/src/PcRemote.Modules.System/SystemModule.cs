using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.System;

/// <summary>
/// Skeleton — Phase 3 fills in shutdown/restart/sleep/hibernate/lock/logoff
/// using ExitWindowsEx + SetSuspendState + LockWorkStation.
/// </summary>
public sealed class SystemModule : ICommandModule
{
    public string Domain => "system";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("shutdown",  "Shutdown the PC",                 IsDestructive: true),
        new CommandDescriptor("restart",   "Restart the PC",                  IsDestructive: true),
        new CommandDescriptor("sleep",     "Suspend to memory"),
        new CommandDescriptor("hibernate", "Suspend to disk"),
        new CommandDescriptor("lock",      "Lock the workstation"),
        new CommandDescriptor("logoff",    "Log off the current user",        IsDestructive: true),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        // TODO Phase 3 — implement each action.
        return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InternalError, $"'{req.Action}' not implemented yet."));
    }
}
