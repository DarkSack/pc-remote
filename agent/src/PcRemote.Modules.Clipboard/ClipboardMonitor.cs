using System.Collections.Concurrent;
using System.Collections.Specialized;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Windows.Forms;
using WFClipboard = System.Windows.Forms.Clipboard;

namespace PcRemote.Modules.Clipboard;

// ══════════════════════════════════════════════════════════════
// One STA thread owns every clipboard access.
//
// The clipboard API needs STA; the server runs on MTA threads. Before,
// each read or write started a brand-new STA thread. Now one long-lived
// thread runs a small work queue and, between jobs, checks
// GetClipboardSequenceNumber every 400 ms. When the number moves it reads
// what was copied (text, image or files) once, updates Current, records
// it in the history and raises Changed. The watch stream and the history
// both feed from that single read.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal sealed class ClipboardMonitor
{
    private static readonly Lazy<ClipboardMonitor> _instance = new(() => new ClipboardMonitor());
    public static ClipboardMonitor Instance => _instance.Value;

    private const int PollMs = 400;

    private readonly BlockingCollection<Action> _work = new();
    private uint _lastSeq;

    public ClipboardHistory History { get; } = new();

    /// <summary>What is on the clipboard now. Replaced as a whole, never mutated.</summary>
    public ClipSnapshot Current { get; private set; } = ClipSnapshot.Empty;

    /// <summary>Raised on the monitor thread after Current changed.</summary>
    public event Action? Changed;

    /// <summary>Asked before recording; the history plugin can be switched off in the panel.</summary>
    public Func<bool> RecordHistory { get; set; } = () => true;

    private ClipboardMonitor()
    {
        var thread = new Thread(Loop) { IsBackground = true, Name = "clipboard-sta" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
    }

    /// <summary>Runs <paramref name="fn"/> on the STA thread.</summary>
    public Task<T> RunAsync<T>(Func<T> fn)
    {
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        _work.Add(() =>
        {
            try { tcs.SetResult(fn()); }
            catch (Exception ex) { tcs.SetException(ex); }
        });
        return tcs.Task;
    }

    public T Run<T>(Func<T> fn) => RunAsync(fn).GetAwaiter().GetResult();

    private void Loop()
    {
        while (true)
        {
            if (_work.TryTake(out var job, PollMs))
            {
                job();
                // A write from the phone changes the sequence number: pick it up now
                // instead of up to 400 ms later.
            }
            try { Poll(); }
            catch { /* the clipboard is shared with every app; a failed read is retried on the next change */ }
        }
    }

    private void Poll()
    {
        var seq = GetClipboardSequenceNumber();
        if (seq == _lastSeq) return;
        _lastSeq = seq;

        var read = ReadWithRetry();
        if (read is null) return;
        var (snapshot, entry) = read.Value;

        if (entry is not null)
        {
            if (RecordHistory()) History.Add(entry);
            else History.Clear();
        }
        Current = snapshot with { HistoryVersion = History.Version };
        Changed?.Invoke();
    }

    /// <summary>Another app may hold the clipboard open for a moment: a few short retries.</summary>
    private static (ClipSnapshot, ClipEntry?)? ReadWithRetry()
    {
        for (var attempt = 0; attempt < 4; attempt++)
        {
            try { return Read(); }
            catch (ExternalException) { Thread.Sleep(60); }
        }
        return null;
    }

    private static (ClipSnapshot, ClipEntry?) Read()
    {
        var data = WFClipboard.GetDataObject();
        if (data is null) return (ClipSnapshot.Empty, null);

        // Password managers mark what they copy so clipboard history skips it
        // (the same formats Windows' own Win+V history honours).
        var isPrivate = data.GetDataPresent("ExcludeClipboardContentFromMonitorProcessing") ||
                        HasZeroDword(data, "CanIncludeInClipboardHistory") ||
                        HasZeroDword(data, "CanUploadToCloudClipboard");

        if (data.GetDataPresent(DataFormats.UnicodeText) || data.GetDataPresent(DataFormats.Text))
        {
            var text = WFClipboard.GetText() ?? "";
            var snap = new ClipSnapshot(ClipTypes.Text, text, text.Length, null, null, 0, 0, isPrivate, 0);
            var entry = isPrivate || text.Length == 0 ? null : ClipEntry.ForText(text);
            return (snap, entry);
        }

        if (data.GetDataPresent(DataFormats.FileDrop))
        {
            var files = WFClipboard.GetFileDropList().Cast<string>().ToArray();
            var snap = new ClipSnapshot(ClipTypes.Files, "", 0, files, null, 0, 0, isPrivate, 0);
            return (snap, isPrivate || files.Length == 0 ? null : ClipEntry.ForFiles(files));
        }

        var png = ReadImageAsPng(data);
        if (png is not null)
        {
            var (bytes, w, h) = png.Value;
            var thumb = ClipImages.Thumbnail(bytes, ClipImages.ThumbSize);
            var snap = new ClipSnapshot(ClipTypes.Image, "", 0, null, thumb, w, h, isPrivate, 0);
            return (snap, isPrivate ? null : ClipEntry.ForImage(bytes, thumb, w, h));
        }

        return (ClipSnapshot.Empty with { IsPrivate = isPrivate }, null);
    }

    /// <summary>
    /// Prefers the "PNG" format (browsers, Snipping Tool) because it keeps
    /// transparency; the bitmap formats lose the alpha channel.
    /// </summary>
    private static (byte[] png, int w, int h)? ReadImageAsPng(IDataObject data)
    {
        if (data.GetDataPresent("PNG") && data.GetData("PNG") is MemoryStream ms && ms.Length > 0)
        {
            var bytes = ms.ToArray();
            try
            {
                using var probe = Image.FromStream(new MemoryStream(bytes), false, false);
                return ClipImages.Normalize(bytes, probe.Width, probe.Height);
            }
            catch (ArgumentException) { /* not a real PNG: fall back to the bitmap */ }
        }

        if (!WFClipboard.ContainsImage()) return null;
        using var img = WFClipboard.GetImage();
        if (img is null) return null;
        using var outMs = new MemoryStream();
        img.Save(outMs, ImageFormat.Png);
        return ClipImages.Normalize(outMs.ToArray(), img.Width, img.Height);
    }

    private static bool HasZeroDword(IDataObject data, string format)
    {
        try
        {
            return data.GetDataPresent(format) && data.GetData(format) is MemoryStream ms &&
                   ms.Length >= 4 && BitConverter.ToInt32(ms.ToArray(), 0) == 0;
        }
        catch { return false; }
    }

    // ── Writes (always through RunAsync) ─────────────────────────

    public static void SetText(string text)
    {
        if (text.Length == 0) WFClipboard.Clear();
        else WFClipboard.SetText(text);
    }

    /// <summary>Puts an image as both PNG (keeps alpha) and bitmap (for apps that only read bitmaps).</summary>
    public static void SetImage(byte[] encoded)
    {
        using var img = Image.FromStream(new MemoryStream(encoded));
        using var png = new MemoryStream();
        img.Save(png, ImageFormat.Png);
        var obj = new DataObject();
        obj.SetData("PNG", false, new MemoryStream(png.ToArray()));
        obj.SetData(DataFormats.Bitmap, true, new Bitmap(img));
        WFClipboard.SetDataObject(obj, copy: true);
    }

    public static void SetFiles(IEnumerable<string> paths)
    {
        var list = new StringCollection();
        list.AddRange(paths.Where(p => File.Exists(p) || Directory.Exists(p)).ToArray());
        if (list.Count == 0) throw new FileNotFoundException("None of those files exist any more.");
        WFClipboard.SetFileDropList(list);
    }

    [DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();
}

internal static class ClipTypes
{
    public const string Text  = "text";
    public const string Image = "image";
    public const string Files = "files";
    public const string Empty = "empty";
}

/// <summary>What is on the clipboard right now.</summary>
internal sealed record ClipSnapshot(
    string Type, string Text, int Length, string[]? Files, byte[]? Thumbnail,
    int Width, int Height, bool IsPrivate, long HistoryVersion)
{
    public static readonly ClipSnapshot Empty = new(ClipTypes.Empty, "", 0, null, null, 0, 0, false, 0);
}

/// <summary>One history entry. Immutable; the content hash deduplicates.</summary>
internal sealed class ClipEntry
{
    /// <summary>Longer texts are kept cut (same cap as clipboard.set).</summary>
    public const int MaxTextChars = 1_000_000;

    public long Id { get; set; }
    public long Ts { get; set; }
    public required string Type { get; init; }
    public required string Hash { get; init; }
    public string? Text { get; init; }
    public int Length { get; init; }
    public byte[]? Png { get; init; }
    public byte[]? Thumb { get; init; }
    public int Width { get; init; }
    public int Height { get; init; }
    public string[]? Files { get; init; }

    public long SizeBytes => (Png?.LongLength ?? 0) + (Thumb?.LongLength ?? 0) + (Text?.Length ?? 0) * 2L;

    public static ClipEntry ForText(string text) => new()
    {
        Type = ClipTypes.Text,
        Text = ClipboardModule.SafeTruncate(text, MaxTextChars),
        Length = text.Length,
        Hash = HashOf("t:" + text),
    };

    public static ClipEntry ForImage(byte[] png, byte[] thumb, int w, int h) => new()
    {
        Type = ClipTypes.Image, Png = png, Thumb = thumb, Width = w, Height = h,
        Hash = Convert.ToHexString(SHA256.HashData(png)),
    };

    public static ClipEntry ForFiles(string[] files) => new()
    {
        Type = ClipTypes.Files, Files = files, Length = files.Length,
        Hash = HashOf("f:" + string.Join('\n', files)),
    };

    private static string HashOf(string s) => Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(s)));
}
