using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Modules.Applications.Sources;

namespace PcRemote.Modules.Applications;

// ══════════════════════════════════════════════════════════════
// Applications — enumerar apps instaladas (3 fuentes) + launch.
//
// Fuentes:
//   - Start Menu: .lnk en carpetas Programs (rápido, cubre 95%)
//   - Registry: HKLM/HKCU\...\Uninstall (apps sin shortcut)
//   - UWP: Get-StartApps vía PowerShell
//
// Cache en memoria con TTL 5 min. list?refresh=true fuerza recomputo.
// Deduplicación por nombre normalizado (case-insensitive, trim).
//
// Launch: startmenu → Process.Start(lnk); registry → Start exe;
// uwp → explorer.exe shell:AppsFolder\<id>.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ApplicationsModule : ICommandModule
{
    public string Domain => "applications";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list",   "Enumerar apps instaladas (Start Menu + Registry + UWP)"),
        new CommandDescriptor("launch", "Ejecutar app por id"),
    };

    private static readonly TimeSpan CacheTtl = TimeSpan.FromMinutes(5);
    private static readonly object CacheLock = new();
    private static List<AppEntry>? _cache;
    private static DateTime _cachedAt;

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "list"   => List(req),
                "launch" => Launch(req),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InternalError, ex.Message));
        }
    }

    private static CommandResponse List(CommandRequest req)
    {
        var p = req.Params ?? default;
        bool refresh = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("refresh", out var r) && r.ValueKind == JsonValueKind.True;
        string? filter = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("filter", out var f) ? f.GetString() : null;

        var all = GetCached(refresh);
        var view = all.AsEnumerable();
        if (!string.IsNullOrWhiteSpace(filter))
            view = view.Where(a => a.Name.Contains(filter, StringComparison.OrdinalIgnoreCase));

        var items = view
            .Select(a => new { id = a.Id, name = a.Name, source = a.Source })
            .OrderBy(a => a.name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        return CommandResponse.Ok(req.Id, new { count = items.Count, applications = items });
    }

    private static CommandResponse Launch(CommandRequest req)
    {
        var p = req.Params ?? default;
        var id = p.GetProperty("id").GetString() ?? "";
        var all = GetCached(refresh: false);
        var entry = all.FirstOrDefault(a => a.Id == id);
        if (entry == null)
            return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"No application with id '{id}'");

        var psi = entry.Source == "uwp"
            ? new ProcessStartInfo("explorer.exe", entry.Launch) { UseShellExecute = false, CreateNoWindow = true }
            : new ProcessStartInfo(entry.Launch) { UseShellExecute = true };

        try { Process.Start(psi)?.Dispose(); }
        catch (Exception ex) { return CommandResponse.Fail(req.Id, ErrorCodes.InternalError, $"Launch failed: {ex.Message}"); }

        return CommandResponse.Ok(req.Id, new { launched = entry.Name, source = entry.Source });
    }

    private static List<AppEntry> GetCached(bool refresh)
    {
        lock (CacheLock)
        {
            if (!refresh && _cache != null && DateTime.UtcNow - _cachedAt < CacheTtl)
                return _cache;

            var all = new List<AppEntry>();
            SafeAppend(all, StartMenuSource.Enumerate);
            SafeAppend(all, RegistrySource.Enumerate);
            SafeAppend(all, UwpSource.Enumerate);

            _cache = Dedup(all);
            _cachedAt = DateTime.UtcNow;
            return _cache;
        }
    }

    private static void SafeAppend(List<AppEntry> list, Func<IEnumerable<AppEntry>> source)
    {
        try { list.AddRange(source()); } catch { /* una fuente rota no debe tirar todo */ }
    }

    private static List<AppEntry> Dedup(IEnumerable<AppEntry> all)
    {
        // Preferencia: startmenu > registry > uwp (el orden en que se agregan es ese).
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var result = new List<AppEntry>();
        foreach (var a in all)
        {
            var key = a.Name.Trim().ToLowerInvariant();
            if (seen.Add(key)) result.Add(a);
        }
        return result;
    }
}
