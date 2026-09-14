using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Applications;

// ══════════════════════════════════════════════════════════════
// Applications — apps instaladas (menú Inicio + registro + Store) y abrirlas.
//
//   list    → request: la lista actual (refresh:true la recalcula ya)
//   watch   → subscribe: la lista al suscribirse y otra vez cada vez que
//             cambia (se instala o desinstala algo). Ver AppCatalog.
//   launch  → request: abre una app por id. Solo ids del catálogo: nunca se
//             ejecuta una ruta que mande el cliente.
//
// Los iconos van por su propio dominio (AppIconsModule): cada dominio tiene su
// cola, así cargar 50 iconos al hacer scroll no retrasa un "abrir".
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ApplicationsModule : ICommandModule, IStreamModule
{
    public string Domain => "applications";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list",   "Enumerar apps instaladas (Start Menu + Registry + UWP)"),
        new CommandDescriptor("watch",  "Stream: la lista, y de nuevo cada vez que cambia"),
        new CommandDescriptor("launch", "Ejecutar app por id"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "watch" };

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
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    private static CommandResponse List(CommandRequest req)
    {
        var p = req.Params ?? default;
        bool refresh = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("refresh", out var r) && r.ValueKind == JsonValueKind.True;
        string? filter = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("filter", out var f) ? f.GetString() : null;

        var all = AppCatalog.GetAll(refresh);
        IReadOnlyCollection<AppEntry> view = string.IsNullOrWhiteSpace(filter)
            ? all
            : all.Where(a => a.Name.Contains(filter, StringComparison.OrdinalIgnoreCase)).ToList();
        return CommandResponse.Ok(req.Id, Payload(view, AppCatalog.Version));
    }

    private static object Payload(IReadOnlyCollection<AppEntry> apps, long version) => new
    {
        version,
        count = apps.Count,
        applications = apps.Select(a => new { id = a.Id, name = a.Name, source = a.Source }).ToList(),
    };

    private static CommandResponse Launch(CommandRequest req)
    {
        var p = req.Params ?? default;
        var id = p.GetProperty("id").GetString() ?? "";
        var entry = AppCatalog.Find(id);
        if (entry == null)
            return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"No application with id '{id}'");

        var psi = entry.Source == "uwp"
            ? new ProcessStartInfo("explorer.exe", entry.Launch) { UseShellExecute = false, CreateNoWindow = true }
            : new ProcessStartInfo(entry.Launch) { UseShellExecute = true };

        try { Process.Start(psi)?.Dispose(); }
        catch (Exception ex) { return CommandResponse.Fail(req.Id, ErrorCodes.InternalError, $"Launch failed: {ex.Message}"); }

        return CommandResponse.Ok(req.Id, new { launched = entry.Name, id = entry.Id, source = entry.Source });
    }

    // ── Stream: watch ────────────────────────────────────────
    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "watch") yield break;

        // The first load can take a couple of seconds (PowerShell for Store apps).
        var apps = await Task.Run(() => AppCatalog.GetAll(), ct);
        var sent = AppCatalog.Version;
        yield return Payload(apps, sent);

        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(1000, ct); } catch (TaskCanceledException) { yield break; }
            var current = AppCatalog.Version;
            if (current == sent) continue;
            sent = current;
            yield return Payload(AppCatalog.GetAll(), current);
        }
    }
}

/// <summary>
/// `appicons.get { ids: [...] }` → `{ icons: { id: base64 PNG | null } }`, at most
/// 50 ids per call. A separate domain on purpose: requests of one domain run in
/// order, and icons loading while scrolling must not delay a launch.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class AppIconsModule : ICommandModule
{
    private const int MaxIdsPerRequest = 50;

    public string Domain => "appicons";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("get", "Iconos PNG 64×64 de apps del catálogo"),
    };

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            if (req.Action != "get")
                return CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'");

            var ids = (req.Params ?? default).GetProperty("ids").EnumerateArray()
                .Select(e => e.GetString())
                .Where(s => !string.IsNullOrEmpty(s))
                .Select(s => s!)
                .Distinct()
                .Take(MaxIdsPerRequest)
                .ToList();

            var icons = new Dictionary<string, string?>();
            foreach (var id in ids)
            {
                ct.ThrowIfCancellationRequested();
                var app = AppCatalog.Find(id);
                var png = app is null ? null : await AppIcons.GetPngAsync(app);
                icons[id] = png is null ? null : Convert.ToBase64String(png);
            }
            return CommandResponse.Ok(req.Id, new { icons });
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }
}
