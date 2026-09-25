using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text.Json;
using System.Threading.Channels;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using WFClipboard = System.Windows.Forms.Clipboard;

namespace PcRemote.Modules.Clipboard;

// ══════════════════════════════════════════════════════════════
// Clipboard — get / set / setImage / clear / watch.
//
// Every access goes through ClipboardMonitor's STA thread. watch no longer
// polls on its own: it forwards the monitor's Changed event, so any number
// of phones share one read per change.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ClipboardModule : ICommandModule, IStreamModule, IPluginMetadata
{
    public string Domain => "clipboard";

    public string DisplayName => "Portapapeles";
    public string Description => "Leer y escribir el portapapeles del PC (texto e imágenes) desde el móvil.";
    public string Category => PluginCategories.Tools;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("get",      "Leer texto del portapapeles"),
        new CommandDescriptor("set",      "Escribir texto al portapapeles"),
        new CommandDescriptor("setImage", "Poner una imagen en el portapapeles"),
        new CommandDescriptor("clear",    "Vaciar el portapapeles"),
        new CommandDescriptor("watch",    "Stream: notifica cuando cambia (stream)"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "watch" };

    /// <summary>Same cap as set. A clipboard holding tens of MB of text would otherwise go out as one frame.</summary>
    private const int MaxTextChars = 1_000_000;

    /// <summary>Base64 of the image sent by the phone (the socket allows 16 MB frames).</summary>
    private const int MaxImageBase64 = 14 * 1024 * 1024;

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return req.Action switch
            {
                "get"      => await HandleGet(req),
                "set"      => await HandleSet(req),
                "setImage" => await HandleSetImage(req),
                "clear"    => await Clear(req),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }

    private static async Task<CommandResponse> HandleGet(CommandRequest req)
    {
        var text = await ClipboardMonitor.Instance.RunAsync(() => WFClipboard.ContainsText() ? WFClipboard.GetText() : "");
        var truncated = text.Length > MaxTextChars;
        return CommandResponse.Ok(req.Id, new
        {
            text = truncated ? SafeTruncate(text, MaxTextChars) : text,
            length = text.Length,
            truncated,
        });
    }

    private static async Task<CommandResponse> HandleSet(CommandRequest req)
    {
        var p = req.Params ?? default;
        var text = p.GetProperty("text").GetString() ?? "";
        if (text.Length > MaxTextChars)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Text too long (max 1MB)");
        await ClipboardMonitor.Instance.RunAsync(() => { ClipboardMonitor.SetText(text); return 0; });
        return CommandResponse.Ok(req.Id, new { length = text.Length });
    }

    private static async Task<CommandResponse> HandleSetImage(CommandRequest req)
    {
        var p = req.Params ?? default;
        var b64 = p.GetProperty("imageBase64").GetString() ?? "";
        if (b64.Length == 0 || b64.Length > MaxImageBase64)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "imageBase64 is empty or over 14 MB");
        byte[] bytes;
        try { bytes = Convert.FromBase64String(b64); }
        catch (FormatException) { return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "imageBase64 is not base64"); }

        try
        {
            await ClipboardMonitor.Instance.RunAsync(() => { ClipboardMonitor.SetImage(bytes); return 0; });
        }
        catch (ArgumentException)
        {
            // Image.FromStream: not a format GDI+ reads (PNG, JPEG, GIF, BMP work).
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Not a PNG/JPEG/GIF/BMP image");
        }
        return CommandResponse.Ok(req.Id, new { bytes = bytes.Length });
    }

    private static async Task<CommandResponse> Clear(CommandRequest req)
    {
        await ClipboardMonitor.Instance.RunAsync(() => { WFClipboard.Clear(); return 0; });
        return CommandResponse.Ok(req.Id, new { cleared = true });
    }

    // ── Stream: watch ────────────────────────────────────────
    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "watch") yield break;

        var monitor = ClipboardMonitor.Instance;
        // Only the latest state matters: a burst of copies collapses into one message.
        var changes = Channel.CreateBounded<int>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropOldest });
        void OnChanged() => changes.Writer.TryWrite(0);
        monitor.Changed += OnChanged;
        try
        {
            yield return Payload(monitor.Current);
            while (true)
            {
                try { await changes.Reader.ReadAsync(ct); }
                catch (OperationCanceledException) { yield break; }
                yield return Payload(monitor.Current);
            }
        }
        finally
        {
            monitor.Changed -= OnChanged;
        }
    }

    /// <summary>
    /// <c>text</c>/<c>length</c> as before (text only for text; "" otherwise), plus
    /// what kind of content it is and the history version, so the phone knows
    /// when to reload the history list.
    /// </summary>
    private static object Payload(ClipSnapshot s) => new
    {
        type = s.Type,
        text = TruncatePreview(s.Text),
        length = s.Length,
        width = s.Width,
        height = s.Height,
        files = s.Files?.Take(20).Select(Path.GetFileName).ToArray(),
        fileCount = s.Files?.Length ?? 0,
        thumbBase64 = s.Thumbnail is null ? null : Convert.ToBase64String(s.Thumbnail),
        isPrivate = s.IsPrivate,
        historyVersion = s.HistoryVersion,
    };

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
}
