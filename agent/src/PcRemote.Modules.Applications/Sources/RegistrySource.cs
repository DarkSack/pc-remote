using System.Runtime.Versioning;
using Microsoft.Win32;

namespace PcRemote.Modules.Applications.Sources;

// ══════════════════════════════════════════════════════════════
// Fuente Registry: enumera HKLM/HKCU\...\Uninstall.
// Devuelve DisplayName + DisplayIcon (path a exe) o InstallLocation.
// Usada como catálogo — el launch preferido es startmenu (.lnk),
// pero para apps sin acceso directo el DisplayIcon suele ser el .exe.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal static class RegistrySource
{
    private static readonly (RegistryKey Root, string Path)[] Locations =
    {
        (Registry.LocalMachine, @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
        (Registry.LocalMachine, @"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"),
        (Registry.CurrentUser,  @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
    };

    public static IEnumerable<AppEntry> Enumerate()
    {
        foreach (var (root, path) in Locations)
        {
            using var key = root.OpenSubKey(path);
            if (key == null) continue;

            foreach (var subName in key.GetSubKeyNames())
            {
                using var sub = key.OpenSubKey(subName);
                if (sub == null) continue;

                var name = sub.GetValue("DisplayName") as string;
                if (string.IsNullOrWhiteSpace(name)) continue;

                var systemComponent = sub.GetValue("SystemComponent") as int? ?? 0;
                if (systemComponent == 1) continue; // filtra runtimes/hotfixes

                // Only an existing .exe is launchable. DisplayIcon is often an .ico
                // or the uninstaller, and InstallLocation is a folder: "launching"
                // those opened an image viewer, an uninstaller or Explorer.
                var launch = ExtractPath(sub.GetValue("DisplayIcon") as string);
                if (launch is null ||
                    !launch.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
                    Path.GetFileName(launch).Contains("unins", StringComparison.OrdinalIgnoreCase) ||
                    !File.Exists(launch))
                    continue;

                yield return new AppEntry(
                    Id:     "registry:" + subName,
                    Name:   name,
                    Source: "registry",
                    Launch: launch);
            }
        }
    }

    private static string? ExtractPath(string? displayIcon)
    {
        if (string.IsNullOrWhiteSpace(displayIcon)) return null;
        // DisplayIcon puede venir como "C:\foo.exe,0" (path + icon index) o entre comillas.
        var p = displayIcon.Trim().Trim('"');
        var comma = p.LastIndexOf(',');
        if (comma > 3) p = p[..comma]; // preserva "C:\" (evita cortar la unidad)
        return p.Trim('"');
    }
}
