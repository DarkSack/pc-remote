using System.Collections.Specialized;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using WFClipboard = System.Windows.Forms.Clipboard;

namespace PcRemote.Modules.Clipboard;

/// <summary>One thing that was copied on the PC.</summary>
public sealed class ClipEntry
{
    public required string Id { get; init; }
    public long Ts { get; set; }
    /// <summary>text, image or files.</summary>
    public required string Kind { get; init; }
    /// <summary>Full text (text), or one path per line (files).</summary>
    public string? Text { get; init; }
    public byte[]? Png { get; init; }
    public int Width { get; init; }
    public int Height { get; init; }
    public string? ThumbnailBase64 { get; init; }
    public required string Hash { get; init; }

    public long Bytes => (Png?.LongLength ?? 0) + (Text?.Length ?? 0) * 2L;

    /// <summary>What goes over the wire in lists: no full text, no full image.</summary>
    public object Summary() => new
    {
        id = Id,
        ts = Ts,
        kind = Kind,
        preview = Text is null ? null : ClipboardModule.SafeTruncate(Text, 280),
        length = Text?.Length ?? 0,
        width = Width,
        height = Height,
        bytes = Png?.Length ?? 0,
        thumbnail = ThumbnailBase64,
        files = Kind == "files" ? Text!.Split('\n').Select(Path.GetFileName).ToArray() : null,
    };
}

/// <summary>add (entry, possibly an old one moved to the top), remove (id) or clear.</summary>
public sealed record ClipChange(string Op, ClipEntry? Entry, string? Id);

/// <summary>
/// History of the PC clipboard: text, images and copied files. Watches from the
/// moment the agent starts, so what was copied while the phone was away is there.
///
/// Memory only — it can hold passwords, so it never touches the disk. Content
/// that password managers mark as private (ExcludeClipboardContentFromMonitorProcessing,
/// CanIncludeInClipboardHistory = 0, the same flags Windows' own Win+V history
/// honours) is skipped.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class ClipboardHistory : IDisposable
{
    public const int MaxImageSide = 4096;
    public const int ThumbnailSide = 240;
    public const long MaxImageBytesTotal = 64L * 1024 * 1024;
    public const int MaxTextChars = 1_000_000;

    private readonly int _capacity;
    private readonly LinkedList<ClipEntry> _entries = new();
    private readonly object _lock = new();
    private readonly Thread? _thread;
    private volatile bool _disposed;
    private long _nextId;

    public event Action<ClipChange>? Changed;

    public ClipboardHistory(int capacity) : this(capacity, watch: true) { }

    /// <param name="watch">False in tests: no thread reading the real clipboard.</param>
    internal ClipboardHistory(int capacity, bool watch)
    {
        _capacity = Math.Clamp(capacity, 0, 500);
        if (_capacity == 0 || !watch) return;
        _thread = new Thread(WatchLoop) { IsBackground = true, Name = "ClipboardHistory" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
    }

    public bool Enabled => _capacity > 0;

    public IReadOnlyList<ClipEntry> List()
    {
        lock (_lock) return _entries.ToArray();
    }

    public ClipEntry? Get(string id)
    {
        lock (_lock) return _entries.FirstOrDefault(e => e.Id == id);
    }

    public bool Remove(string id)
    {
        lock (_lock)
        {
            var node = _entries.First;
            while (node is not null && node.Value.Id != id) node = node.Next;
            if (node is null) return false;
            _entries.Remove(node);
        }
        Changed?.Invoke(new ClipChange("remove", null, id));
        return true;
    }

    public void Clear()
    {
        lock (_lock) _entries.Clear();
        Changed?.Invoke(new ClipChange("clear", null, null));
    }

    // ── watching ─────────────────────────────────────────────
    private void WatchLoop()
    {
        uint seq = GetClipboardSequenceNumber();
        // What is on the clipboard when the agent starts is part of the history too.
        TryCapture();
        while (!_disposed)
        {
            Thread.Sleep(400);
            var cur = GetClipboardSequenceNumber();
            if (cur == seq) continue;
            seq = cur;
            TryCapture();
        }
    }

    private void TryCapture()
    {
        // Another app may hold the clipboard open for a moment.
        for (var attempt = 0; attempt < 4; attempt++)
        {
            try
            {
                var entry = Read();
                if (entry is not null) Add(entry);
                return;
            }
            catch (ExternalException)
            {
                Thread.Sleep(60);
            }
            catch
            {
                return; // unreadable content: skip it, keep watching
            }
        }
    }

    /// <summary>Reads the current clipboard. Must run on an STA thread.</summary>
    private ClipEntry? Read()
    {
        var data = WFClipboard.GetDataObject();
        if (data is null) return null;
        if (IsPrivate(data)) return null;

        if (data.GetDataPresent(System.Windows.Forms.DataFormats.FileDrop) &&
            WFClipboard.GetFileDropList() is { Count: > 0 } files)
        {
            var text = string.Join('\n', files.Cast<string>());
            return new ClipEntry { Id = NewId(), Kind = "files", Text = text, Hash = "f:" + Sha(text) };
        }

        if (WFClipboard.ContainsImage() || data.GetDataPresent("PNG"))
        {
            using var image = ReadImage(data);
            if (image is not null) return FromImage(image);
        }

        if (WFClipboard.ContainsText())
        {
            var text = WFClipboard.GetText();
            if (string.IsNullOrWhiteSpace(text)) return null;
            if (text.Length > MaxTextChars) text = ClipboardModule.SafeTruncate(text, MaxTextChars);
            return new ClipEntry { Id = NewId(), Kind = "text", Text = text, Hash = "t:" + Sha(text) };
        }
        return null;
    }

    /// <summary>The PNG format keeps transparency, CF_DIB does not: prefer it when an app offers it.</summary>
    private static Bitmap? ReadImage(System.Windows.Forms.IDataObject data)
    {
#pragma warning disable WFDEV005 // GetData(string): only a MemoryStream is read here, no deserialisation
        if (data.GetData("PNG") is MemoryStream png)
        {
            try { return new Bitmap(png); } catch { /* fall back to the bitmap */ }
        }
#pragma warning restore WFDEV005
        return WFClipboard.GetImage() is { } img ? new Bitmap(img) : null;
    }

    internal ClipEntry FromImage(Image source)
    {
        using var scaled = Fit(source, MaxImageSide);
        byte[] png;
        using (var ms = new MemoryStream())
        {
            scaled.Save(ms, ImageFormat.Png);
            png = ms.ToArray();
        }
        return new ClipEntry
        {
            Id = NewId(),
            Kind = "image",
            Png = png,
            Width = scaled.Width,
            Height = scaled.Height,
            ThumbnailBase64 = Thumbnail(scaled),
            Hash = "i:" + Convert.ToHexString(SHA256.HashData(png)),
        };
    }

    private static Bitmap Fit(Image src, int maxSide)
    {
        var k = Math.Min(1.0, (double)maxSide / Math.Max(src.Width, src.Height));
        var w = Math.Max(1, (int)Math.Round(src.Width * k));
        var h = Math.Max(1, (int)Math.Round(src.Height * k));
        var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bmp);
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.DrawImage(src, 0, 0, w, h);
        return bmp;
    }

    /// <summary>Small JPEG for the list (a few KB each; the full PNG is fetched on demand).</summary>
    private static string Thumbnail(Image img)
    {
        using var thumb = Fit(img, ThumbnailSide);
        using var flat = new Bitmap(thumb.Width, thumb.Height, PixelFormat.Format24bppRgb);
        using (var g = Graphics.FromImage(flat))
        {
            g.Clear(Color.White); // JPEG has no alpha
            g.DrawImage(thumb, 0, 0);
        }
        var jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        using var ms = new MemoryStream();
        using var p = new EncoderParameters(1);
        p.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 78L);
        flat.Save(ms, jpeg, p);
        return Convert.ToBase64String(ms.ToArray());
    }

    internal void Add(ClipEntry entry)
    {
        ClipEntry added;
        lock (_lock)
        {
            // Copying the same thing again moves it to the top instead of duplicating it.
            var existing = _entries.FirstOrDefault(e => e.Hash == entry.Hash);
            if (existing is not null)
            {
                if (_entries.First?.Value == existing) return; // same as the latest: nothing new
                _entries.Remove(existing);
                existing.Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                added = existing;
            }
            else
            {
                entry.Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                added = entry;
            }
            _entries.AddFirst(added);
            Evict();
        }
        Changed?.Invoke(new ClipChange("add", added, added.Id));
    }

    private void Evict()
    {
        var removed = new List<string>();
        while (_entries.Count > _capacity)
        {
            removed.Add(_entries.Last!.Value.Id);
            _entries.RemoveLast();
        }
        // Images are the heavy part: past the budget, drop the oldest ones.
        var imageBytes = _entries.Where(e => e.Png is not null).Sum(e => e.Png!.LongLength);
        for (var node = _entries.Last; node is not null && imageBytes > MaxImageBytesTotal;)
        {
            var prev = node.Previous;
            if (node.Value.Png is { } png && node != _entries.First)
            {
                imageBytes -= png.LongLength;
                removed.Add(node.Value.Id);
                _entries.Remove(node);
            }
            node = prev;
        }
        foreach (var id in removed) ThreadPool.QueueUserWorkItem(_ => Changed?.Invoke(new ClipChange("remove", null, id)));
    }

    private static bool IsPrivate(System.Windows.Forms.IDataObject data)
    {
        if (data.GetDataPresent("ExcludeClipboardContentFromMonitorProcessing")) return true;
        if (data.GetDataPresent("Clipboard Viewer Ignore")) return true;
        if (data.GetDataPresent("CanIncludeInClipboardHistory"))
        {
#pragma warning disable WFDEV005 // a MemoryStream with a DWORD, no deserialisation
            if (data.GetData("CanIncludeInClipboardHistory") is MemoryStream ms && ms.Length >= 4)
            {
                var buf = new byte[4];
                ms.Position = 0;
                ms.ReadExactly(buf);
                return BitConverter.ToInt32(buf) == 0;
            }
#pragma warning restore WFDEV005
        }
        return false;
    }

    private string NewId() => Interlocked.Increment(ref _nextId).ToString("x");

    private static string Sha(string s) => Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(s)));

    [DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();

    public void Dispose() => _disposed = true;
}
