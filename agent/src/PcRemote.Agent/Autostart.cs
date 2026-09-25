using System.Runtime.Versioning;
using Microsoft.Win32;

namespace PcRemote.Agent;

/// <summary>
/// "Iniciar con Windows": a value under HKCU\…\Run pointing at this exe. Per user,
/// no admin needed. If the exe was moved, the entry is repointed on the next start.
/// </summary>
[SupportedOSPlatform("windows")]
internal static class Autostart
{
    public const string Flag = "--autostart";

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "PcRemote";

    private static string Command => $"\"{Environment.ProcessPath}\" {Flag}";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue(ValueName, Command);
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }

    /// <summary>Enabled but pointing elsewhere (the exe was moved or updated in a new folder): fix it.</summary>
    public static void RepairIfMoved()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            if (key?.GetValue(ValueName) is string current && !string.Equals(current, Command, StringComparison.OrdinalIgnoreCase))
                key.SetValue(ValueName, Command);
        }
        catch { /* not worth failing the start over */ }
    }
}
