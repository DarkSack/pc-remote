using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Plugins;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Clipboard;

// ══════════════════════════════════════════════════════════════
// Clipboard history — everything copied on the PC (text, images, files),
// newest first, while the agent runs. A plugin of its own so it can be
// switched off in the panel; switched off, nothing is recorded and the
// current history is wiped.
//
//   list    { offset?, limit?, type? } → entries with a preview / thumbnail
//   get     { id }                     → the full text or the full image
//   restore { id }                     → put it back on the PC's clipboard
//   delete  { id } / clear
//
// Nothing is written to disk. Content that password managers mark as
// private (ExcludeClipboardContentFromMonitorProcessing and friends) is
// never recorded.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ClipboardHistoryModule : ICommandModule, IPluginMetadata
{
    public string Domain => "cliphistory";

    public string DisplayName => "Historial del portapapeles";
    public string Description => "Todo lo que copias en el PC (texto, imágenes y archivos), solo en memoria. Respeta lo que los gestores de contraseñas marcan como privado.";
    public string Category => PluginCategories.Tools;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list",    "Entradas del historial (con vista previa)"),
        new CommandDescriptor("get",     "Contenido completo de una entrada"),
        new CommandDescriptor("restore", "Volver a copiar una entrada en el PC"),
        new CommandDescriptor("delete",  "Borrar una entrada", IsDestructive: true),
        new CommandDescriptor("clear",   "Borrar todo el historial", IsDestructive: true),
    };

    private const int PreviewChars = 500;

    /// <summary>Full images above this go out scaled down (the phone shows them on a screen, not in print).</summary>
    private const int MaxSendSide = 2560;

    private readonly ClipboardMonitor _monitor = ClipboardMonitor.Instance;

    public ClipboardHistoryModule(PluginManager? plugins = null)
    {
        if (plugins is not null) _monitor.RecordHistory = () => plugins.IsEnabled(Domain);
    }

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            var p = req.Params is { ValueKind: JsonValueKind.Object } o ? o : default;
            return req.Action switch
            {
                "list"    => List(req.Id, p),
                "get"     => Get(req.Id, p),
                "restore" => await Restore(req.Id, p),
                "delete"  => _monitor.History.Delete(p.GetProperty("id").GetInt64())
                                 ? CommandResponse.Ok(req.Id, new { deleted = true, version = _monitor.History.Version })
                                 : NotFound(req.Id),
                "clear"   => ClearAll(req.Id),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }

    private CommandResponse List(string id, JsonElement p)
    {
        int offset = 0, limit = 50;
        string? type = null;
        if (p.ValueKind == JsonValueKind.Object)
        {
            if (p.TryGetProperty("offset", out var o)) offset = Math.Max(0, o.GetInt32());
            if (p.TryGetProperty("limit", out var l)) limit = Math.Clamp(l.GetInt32(), 1, 100);
            if (p.TryGetProperty("type", out var t)) type = t.GetString();
        }
        var history = _monitor.History;
        var items = history.List(offset, limit, type).Select(e => new
        {
            id = e.Id,
            type = e.Type,
            ts = e.Ts,
            preview = e.Text is null ? null : ClipboardModule.SafeTruncate(e.Text, PreviewChars),
            length = e.Length,
            width = e.Width,
            height = e.Height,
            sizeBytes = e.Png?.LongLength ?? (long)(e.Text?.Length ?? 0),
            files = e.Files?.Take(10).Select(Path.GetFileName).ToArray(),
            fileCount = e.Files?.Length ?? 0,
            thumbBase64 = e.Thumb is null ? null : Convert.ToBase64String(e.Thumb),
        }).ToList();
        return CommandResponse.Ok(id, new { version = history.Version, total = history.Count, items });
    }

    private CommandResponse Get(string id, JsonElement p)
    {
        var e = _monitor.History.Get(p.GetProperty("id").GetInt64());
        if (e is null) return NotFound(id);
        return e.Type switch
        {
            ClipTypes.Text => CommandResponse.Ok(id, new { id = e.Id, type = e.Type, text = e.Text, length = e.Length }),
            ClipTypes.Image => ImagePayload(id, e),
            _ => CommandResponse.Ok(id, new { id = e.Id, type = e.Type, files = e.Files }),
        };
    }

    private static CommandResponse ImagePayload(string reqId, ClipEntry e)
    {
        var png = e.Png!;
        int w = e.Width, h = e.Height;
        if (Math.Max(w, h) > MaxSendSide)
        {
            png = ClipImages.ScalePng(png, MaxSendSide);
            var scale = (double)MaxSendSide / Math.Max(w, h);
            w = Math.Max(1, (int)Math.Round(w * scale));
            h = Math.Max(1, (int)Math.Round(h * scale));
        }
        return CommandResponse.Ok(reqId, new
        {
            id = e.Id, type = e.Type, width = w, height = h,
            originalWidth = e.Width, originalHeight = e.Height,
            pngBase64 = Convert.ToBase64String(png),
        });
    }

    private async Task<CommandResponse> Restore(string id, JsonElement p)
    {
        var e = _monitor.History.Get(p.GetProperty("id").GetInt64());
        if (e is null) return NotFound(id);
        try
        {
            await _monitor.RunAsync(() =>
            {
                switch (e.Type)
                {
                    case ClipTypes.Text: ClipboardMonitor.SetText(e.Text ?? ""); break;
                    case ClipTypes.Image: ClipboardMonitor.SetImage(e.Png!); break;
                    case ClipTypes.Files: ClipboardMonitor.SetFiles(e.Files!); break;
                }
                return 0;
            });
        }
        catch (FileNotFoundException ex)
        {
            return CommandResponse.Fail(id, ErrorCodes.NotFound, ex.Message);
        }
        return CommandResponse.Ok(id, new { restored = e.Id, type = e.Type });
    }

    private CommandResponse ClearAll(string id)
    {
        _monitor.History.Clear();
        return CommandResponse.Ok(id, new { cleared = true, version = _monitor.History.Version });
    }

    private static CommandResponse NotFound(string id) =>
        CommandResponse.Fail(id, ErrorCodes.NotFound, "That entry is no longer in the history.");
}
