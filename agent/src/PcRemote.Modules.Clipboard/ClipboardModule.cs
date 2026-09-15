using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text.Json;
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
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ClipboardModule : ICommandModule, IStreamModule
{
    public string Domain => "clipboard";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("get",   "Leer texto del portapapeles"),
        new CommandDescriptor("set",   "Escribir texto al portapapeles"),
        new CommandDescriptor("clear", "Vaciar el portapapeles"),
        new CommandDescriptor("watch", "Stream: notifica cuando cambia (stream)"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "watch" };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "get"   => HandleGet(req),
                "set"   => HandleSet(req),
                "clear" => Clear(req),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
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

    private static CommandResponse HandleSet(CommandRequest req)
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
        return CommandResponse.Ok(req.Id, new { length = text.Length });
    }

    private static CommandResponse Clear(CommandRequest req)
    {
        RunSta(() => { WFClipboard.Clear(); return 0; });
        return CommandResponse.Ok(req.Id, new { cleared = true });
    }

    // ── Stream: watch ────────────────────────────────────────
    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
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
