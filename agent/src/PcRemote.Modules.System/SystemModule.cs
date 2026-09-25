using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Modules.System.Win32;

namespace PcRemote.Modules.System;

/// <summary>
/// Shutdown / restart / sleep / hibernate / lock / logoff via Win32.
/// Runs in the user session (no admin needed for these actions).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class SystemModule : ICommandModule, IPluginMetadata
{
    public string Domain => "system";

    public string DisplayName => "Energía";
    public string Description => "Apagar, reiniciar, suspender, hibernar, bloquear y cerrar sesión.";
    public string Category => PluginCategories.System;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("shutdown",  "Shutdown the PC",           IsDestructive: true),
        new CommandDescriptor("restart",   "Restart the PC",            IsDestructive: true),
        new CommandDescriptor("sleep",     "Suspend to memory"),
        new CommandDescriptor("hibernate", "Suspend to disk"),
        new CommandDescriptor("lock",      "Lock the workstation"),
        new CommandDescriptor("logoff",    "Log off the current user",  IsDestructive: true),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "shutdown"  => DoExitWindows(req.Id, NativeMethods.EWX_POWEROFF, "shutdown"),
                "restart"   => DoExitWindows(req.Id, NativeMethods.EWX_REBOOT, "restart"),
                "logoff"    => DoExitWindows(req.Id, NativeMethods.EWX_LOGOFF, "logoff"),
                "sleep"     => DoSuspend(req.Id, hibernate: false),
                "hibernate" => DoSuspend(req.Id, hibernate: true),
                "lock"      => DoLock(req.Id),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                        $"Unknown action '{req.Action}' in system domain."),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    private static CommandResponse DoExitWindows(string id, uint action, string label)
    {
        if (!NativeMethods.EnableShutdownPrivilege())
        {
            return CommandResponse.Fail(id, ErrorCodes.PermissionDenied,
                "Failed to enable SeShutdownPrivilege.");
        }
        var flags = action | NativeMethods.EWX_FORCEIFHUNG;
        if (!NativeMethods.ExitWindowsEx(flags, NativeMethods.PlannedReason))
        {
            var err = Marshal.GetLastWin32Error();
            return CommandResponse.Fail(id, ErrorCodes.InternalError,
                $"ExitWindowsEx failed: {new Win32Exception(err).Message}");
        }
        return CommandResponse.Ok(id, new { action = label, initiated = true });
    }

    private static CommandResponse DoSuspend(string id, bool hibernate)
    {
        if (!NativeMethods.SetSuspendState(hibernate, false, false))
        {
            var err = Marshal.GetLastWin32Error();
            return CommandResponse.Fail(id, ErrorCodes.InternalError,
                $"SetSuspendState failed: {new Win32Exception(err).Message}");
        }
        return CommandResponse.Ok(id, new { action = hibernate ? "hibernate" : "sleep", initiated = true });
    }

    private static CommandResponse DoLock(string id)
    {
        if (!NativeMethods.LockWorkStation())
        {
            var err = Marshal.GetLastWin32Error();
            return CommandResponse.Fail(id, ErrorCodes.InternalError,
                $"LockWorkStation failed: {new Win32Exception(err).Message}");
        }
        return CommandResponse.Ok(id, new { action = "lock", initiated = true });
    }
}
