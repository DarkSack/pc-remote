using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text.Json;
using System.Threading.Channels;
using PcRemote.Core.Activity;
using PcRemote.Core.Config;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using WFClipboard = System.Windows.Forms.Clipboard;

namespace PcRemote.Modules.Clipboard;

// ══════════════════════════════════════════════════════════════
// Clipboard — get/set/watch.
//
// El API de System.Windows.Forms.Clipboard requiere apartment STA.
// Como el server WS corre en threads MTA, marshalamos cada acceso
// a un thread STA on-demand.
//
// watch: cada 500 ms mira GetClipboardSequenceNumber y solo lee el
// texto cuando cambió. Un approach event-driven con
// WM_CLIPBOARDUPDATE requiere ventana oculta; esto basta.
//
// Historial (ClipboardHistory): texto, imágenes y archivos copiados en el
// PC desde que arrancó el agente, solo en memoria.
//   historyList / historyWatch → resúmenes (imágenes como miniatura)
//   historyGet {id}            → texto completo o PNG completo (base64)
//   historyRestore {id}        → lo vuelve a poner en el portapapeles del PC
//   historyDelete {id} / historyClear
//   setImage {data}            → imagen (PNG/JPEG base64) del móvil al PC
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ClipboardModule : ICommandModule, IStreamModule, IDisposable
{
    public string Domain => "clipboard";

    private readonly ClipboardHistory _history;
    private readonly ActivityLog? _activity;

    public ClipboardModule(AgentSettings settings, ActivityLog activity)
        : this(new ClipboardHistory(settings.Clipboard.HistorySize), activity) { }

    internal ClipboardModule(ClipboardHistory history, ActivityLog? activity)
    {
        _history = history;
        _activity = activity;
    }

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("get",   "Leer texto del portapapeles"),
        new CommandDescriptor("set",   "Escribir texto al portapapeles"),
        new CommandDescriptor("setImage", "Poner una imagen en el portapapeles"),
        new CommandDescriptor("clear", "Vaciar el portapapeles"),
        new CommandDescriptor("watch", "Stream: notifica cuando cambia (stream)"),
        new CommandDescriptor("historyList",    "Historial del portapapeles del PC"),
        new CommandDescriptor("historyGet",     "Contenido completo de una entrada del historial"),
        new CommandDescriptor("historyRestore", "Volver a copiar una entrada en el PC"),
        new CommandDescriptor("historyDelete",  "Borrar una entrada del historial"),
        new CommandDescriptor("historyClear",   "Vaciar el historial"),
        new CommandDescriptor("historyWatch",   "Stream: cambios del historial"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "watch", "historyWatch" };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "get"   => HandleGet(req),
                "set"   => HandleSet(req),
                "setImage" => SetImage(req),
                "clear" => Clear(req),
                "historyList" => CommandResponse.Ok(req.Id, new
                {
                    enabled = _history.Enabled,
                    items = _history.List().Select(e => e.Summary()),
                }),
                "historyGet" => HistoryGet(req),
                "historyRestore" => HistoryRestore(req),
                "historyDelete" => _history.Remove(ReadId(req))
                    ? CommandResponse.Ok(req.Id, new { deleted = true })
                    : CommandResponse.Fail(req.Id, ErrorCodes.NotFound, "Esa entrada ya no está en el historial."),
                "historyClear" => ClearHistory(req),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (FormatException ex) when (ex.StackTrace?.Contains("Convert.FromBase64") == true)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "data no es base64 válido."));
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    /// <summary>Same cap as set. A clipboard holding tens of MB of text would otherwise go out as one frame.</summary>
    private const int MaxTextChars = 1_000_000;

    private static CommandResponse HandleGet(CommandRequest req)
    {
        var text = RunSta(() => WFClipboard.ContainsText() ? WFClipboard.GetText() : "");
        var truncated = text.Length > MaxTextChars;
        return CommandResponse.Ok(req.Id, new
        {
            text = truncated ? SafeTruncate(text, MaxTextChars) : text,
            length = text.Length,
            truncated,
        });
    }

    private CommandResponse HandleSet(CommandRequest req)
    {
        var p = req.Params ?? default;
        var text = p.GetProperty("text").GetString() ?? "";
        if (text.Length > 1_000_000)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Text too long (max 1MB)");
        RunSta(() =>
        {
            if (text.Length == 0) WFClipboard.Clear();
            else WFClipboard.SetText(text);
            return 0;
        });
        if (text.Length > 0) _activity?.Add("clipboard", "Texto copiado desde el móvil", $"{text.Length} caracteres");
        return CommandResponse.Ok(req.Id, new { length = text.Length });
    }

    private static CommandResponse Clear(CommandRequest req)
    {
        RunSta(() => { WFClipboard.Clear(); return 0; });
        return CommandResponse.Ok(req.Id, new { cleared = true });
    }

    // ── History ──────────────────────────────────────────────
    private static string ReadId(CommandRequest req) => (req.Params ?? default).GetProperty("id").GetString() ?? "";

    private CommandResponse HistoryGet(CommandRequest req)
    {
        var e = _history.Get(ReadId(req));
        if (e is null) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, "Esa entrada ya no está en el historial.");
        return CommandResponse.Ok(req.Id, new
        {
            id = e.Id,
            kind = e.Kind,
            text = e.Text,
            width = e.Width,
            height = e.Height,
            png = e.Png is null ? null : Convert.ToBase64String(e.Png),
        });
    }

    private CommandResponse HistoryRestore(CommandRequest req)
    {
        var e = _history.Get(ReadId(req));
        if (e is null) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, "Esa entrada ya no está en el historial.");
        RunSta(() =>
        {
            switch (e.Kind)
            {
                case "image":
                    using (var ms = new MemoryStream(e.Png!))
                    using (var bmp = new System.Drawing.Bitmap(ms))
                    {
                        var obj = new System.Windows.Forms.DataObject();
                        obj.SetImage(bmp);
                        // Also as PNG, so apps that read it keep the transparency.
                        obj.SetData("PNG", false, new MemoryStream(e.Png!));
                        WFClipboard.SetDataObject(obj, copy: true);
                    }
                    break;
                case "files":
                    var list = new System.Collections.Specialized.StringCollection();
                    list.AddRange(e.Text!.Split('\n').Where(File.Exists).ToArray());
                    if (list.Count == 0) WFClipboard.SetText(e.Text!);
                    else WFClipboard.SetFileDropList(list);
                    break;
                default:
                    WFClipboard.SetText(e.Text!);
                    break;
            }
            return 0;
        });
        return CommandResponse.Ok(req.Id, new { restored = e.Id });
    }

    private CommandResponse ClearHistory(CommandRequest req)
    {
        _history.Clear();
        _activity?.Add("clipboard", "Historial del portapapeles vaciado");
        return CommandResponse.Ok(req.Id, new { cleared = true });
    }

    /// <summary>A photo or screenshot from the phone, ready to paste on the PC.</summary>
    private CommandResponse SetImage(CommandRequest req)
    {
        var bytes = Convert.FromBase64String((req.Params ?? default).GetProperty("data").GetString() ?? "");
        if (bytes.Length > 3 * 1024 * 1024)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Imagen demasiado grande (máx. 3 MB).");
        int w = 0, h = 0;
        RunSta(() =>
        {
            using var ms = new MemoryStream(bytes);
            using var bmp = new System.Drawing.Bitmap(ms);
            w = bmp.Width; h = bmp.Height;
            WFClipboard.SetImage(bmp);
            return 0;
        });
        _activity?.Add("clipboard", "Imagen copiada desde el móvil", $"{w}×{h}");
        return CommandResponse.Ok(req.Id, new { width = w, height = h });
    }

    private async IAsyncEnumerable<object> WatchHistoryAsync([EnumeratorCancellation] CancellationToken ct)
    {
        var queue = Channel.CreateBounded<ClipChange>(new BoundedChannelOptions(128) { FullMode = BoundedChannelFullMode.DropOldest });
        void OnChanged(ClipChange c) => queue.Writer.TryWrite(c);
        _history.Changed += OnChanged;
        try
        {
            yield return new { op = "snapshot", enabled = _history.Enabled, items = _history.List().Select(e => e.Summary()) };
            while (await queue.Reader.WaitToReadAsync(ct).ConfigureAwait(false))
            {
                while (queue.Reader.TryRead(out var c))
                {
                    yield return c.Op switch
                    {
                        "add" => new { op = "add", item = c.Entry!.Summary() },
                        "remove" => new { op = "remove", id = c.Id } as object,
                        _ => new { op = "clear" },
                    };
                }
            }
        }
        finally
        {
            _history.Changed -= OnChanged;
        }
    }

    public void Dispose() => _history.Dispose();

    // ── Stream: watch ────────────────────────────────────────
    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action == "historyWatch")
        {
            await foreach (var item in WatchHistoryAsync(ct).ConfigureAwait(false)) yield return item;
            yield break;
        }
        if (action != "watch") yield break;
        uint seq = GetClipboardSequenceNumber();
        string last = SafeRead();
        // Emit initial snapshot so el cliente sabe el estado actual.
        yield return new { text = TruncatePreview(last), length = last.Length };

        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(500, ct); } catch (TaskCanceledException) { yield break; }

            // GetClipboardSequenceNumber no necesita STA ni abrir el portapapeles:
            // antes cada tick creaba un hilo y copiaba el texto entero (hasta MB)
            // solo para compararlo. Ahora eso pasa únicamente cuando algo cambió.
            var curSeq = GetClipboardSequenceNumber();
            if (curSeq == seq) continue;
            seq = curSeq;

            var cur = SafeRead();
            if (!string.Equals(cur, last, StringComparison.Ordinal))
            {
                last = cur;
                yield return new { text = TruncatePreview(cur), length = cur.Length };
            }
        }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();

    private static string SafeRead()
    {
        try { return RunSta(() => WFClipboard.ContainsText() ? WFClipboard.GetText() : ""); }
        catch { return ""; }
    }

    private static string TruncatePreview(string s) => SafeTruncate(s, 4096);

    /// <summary>
    /// Cuts without splitting a surrogate pair. `s[..n]` could end in half an
    /// emoji; System.Text.Json refuses to write invalid UTF-16, so the send threw
    /// and the watch stream died silently the first time that happened.
    /// </summary>
    internal static string SafeTruncate(string s, int max)
    {
        if (s.Length <= max) return s;
        var cut = char.IsHighSurrogate(s[max - 1]) ? max - 1 : max;
        return s[..cut];
    }

    // ── STA marshaling ───────────────────────────────────────
    private static T RunSta<T>(Func<T> fn)
    {
        T result = default!;
        Exception? err = null;
        var t = new Thread(() =>
        {
            try { result = fn(); }
            catch (Exception ex) { err = ex; }
        });
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        t.Join();
        if (err != null) throw err;
        return result;
    }
}
