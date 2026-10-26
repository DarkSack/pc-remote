using System.Runtime.Versioning;
using Microsoft.Win32;

namespace PcRemote.Agent.Tray;

/// <summary>
/// "Start with Windows": a value under HKCU\...\Run pointing at this exe.
/// Per user and without admin, like the agent itself.
/// </summary>
[SupportedOSPlatform("windows")]
internal static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "SackDesk";

    private static string Command(string? exe = null) => $"\"{exe ?? Environment.ProcessPath}\"";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue(ValueName) is string v && string.Equals(v, Command(), StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>On for this exe, or for <paramref name="exe"/> (the installer points it at the installed copy).</summary>
    public static void Set(bool enabled, string? exe = null)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
        if (enabled) key.SetValue(ValueName, Command(exe));
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}
