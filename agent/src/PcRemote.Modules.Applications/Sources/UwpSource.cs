using System.Diagnostics;
using System.Runtime.Versioning;

namespace PcRemote.Modules.Applications.Sources;

// ══════════════════════════════════════════════════════════════
// Fuente UWP: enumera apps modernas vía PowerShell Get-StartApps
// (más rápido y estable que enganchar Windows.Management.Deployment).
// El launch se hace con `explorer.exe shell:AppsFolder\<AppID>`.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal static class UwpSource
{
    public static IEnumerable<AppEntry> Enumerate()
    {
        var psi = new ProcessStartInfo
        {
            FileName  = "powershell.exe",
            // Windows PowerShell writes to a pipe in the OEM code page by default, so
            // "Configuración" arrived as "Configuraci¢n". Force UTF-8 on both ends.
            Arguments = "-NoProfile -NonInteractive -Command \"[Console]::OutputEncoding = [Text.Encoding]::UTF8; Get-StartApps | ConvertTo-Csv -NoTypeInformation\"",
            RedirectStandardOutput = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            // stderr is not read; redirecting it without draining can block the child.
            RedirectStandardError  = false,
            UseShellExecute        = false,
            CreateNoWindow         = true,
        };
        Process proc;
        try { proc = Process.Start(psi)!; }
        catch { yield break; }

        string output;
        try
        {
            output = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(10_000);
        }
        finally { proc.Dispose(); }

        var lines = output.Split('\n', StringSplitOptions.RemoveEmptyEntries);
        if (lines.Length < 2) yield break;

        // Skip header
        foreach (var raw in lines.Skip(1))
        {
            var line = raw.TrimEnd('\r');
            if (string.IsNullOrWhiteSpace(line)) continue;
            var parts = ParseCsvLine(line);
            if (parts.Length < 2) continue;

            var name  = parts[0];
            var appId = parts[1];
            if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(appId)) continue;

            yield return new AppEntry(
                Id:     "uwp:" + appId,
                Name:   name,
                Source: "uwp",
                Launch: "shell:AppsFolder\\" + appId);
        }
    }

    private static string[] ParseCsvLine(string line)
    {
        var result = new List<string>();
        var sb = new System.Text.StringBuilder();
        bool inQuotes = false;
        for (int i = 0; i < line.Length; i++)
        {
            var ch = line[i];
            if (ch == '"')
            {
                if (inQuotes && i + 1 < line.Length && line[i + 1] == '"') { sb.Append('"'); i++; }
                else inQuotes = !inQuotes;
            }
            else if (ch == ',' && !inQuotes)
            {
                result.Add(sb.ToString());
                sb.Clear();
            }
            else sb.Append(ch);
        }
        result.Add(sb.ToString());
        return result.ToArray();
    }
}
