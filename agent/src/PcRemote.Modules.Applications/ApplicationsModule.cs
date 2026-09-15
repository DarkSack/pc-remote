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

        var psi = entry.Source switch
        {
            // Unquoted on purpose and verified: explorer takes its whole command line,
            // so ids with spaces ("{…}\DB Browser for SQLite\…exe") already open fine.
            "uwp" => new ProcessStartInfo("explorer.exe", entry.Launch) { UseShellExecute = false, CreateNoWindow = true },
            // A .exe from the registry inherited the agent's working directory. Apps
            // that read config or data files relative to it failed to start or
            // started without their settings; a shortcut would have set it.
            "registry" => new ProcessStartInfo(entry.Launch)
            {
                UseShellExecute = true,
                WorkingDirectory = Path.GetDirectoryName(entry.Launch) ?? "",
            },
            // A .lnk carries its own working directory; the shell applies it.
            _ => new ProcessStartInfo(entry.Launch) { UseShellExecute = true },
        };

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
        var (apps, sent) = await Task.Run(() => AppCatalog.Snapshot(), ct);
        yield return Payload(apps, sent);

        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(1000, ct); } catch (TaskCanceledException) { yield break; }
            if (AppCatalog.Version == sent) continue;
            // List and version from one read: taken separately, a rescan finishing in
            // between paired the old list with the new version, and the new list was
            // never sent.
            (apps, sent) = AppCatalog.Snapshot();
            yield return Payload(apps, sent);
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
    private static readonly TimeSpan BatchTimeout = TimeSpan.FromSeconds(5);

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

            var lookups = ids
                .Select(id => (Id: id, Png: AppCatalog.Find(id) is { } app
                    ? AppIcons.GetPngAsync(app)
                    : Task.FromResult<byte[]?>(null)))
                .ToList();

            // One time budget for the whole batch, and cancellable. Shell extensions can
            // hang while drawing an icon; an unbounded await held this domain's lane,
            // and on disconnect the server waits for every lane, so the connection was
            // never released.
            try { await Task.WhenAll(lookups.Select(l => l.Png)).WaitAsync(BatchTimeout, ct); }
            catch (TimeoutException) { /* answer with what is ready */ }

            // Ids still being drawn are left out (not null): null means "this app has
            // no icon", while a missing id tells the client to ask again later. The
            // extraction keeps running and fills the cache for that next request.
            var icons = new Dictionary<string, string?>();
            foreach (var (id, png) in lookups)
            {
                if (!png.IsCompleted) continue;
                icons[id] = png.IsCompletedSuccessfully && png.Result is { } bytes ? Convert.ToBase64String(bytes) : null;
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
