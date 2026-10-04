using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text.Json;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Router;
using PcRemote.Modules.Input;
using PcRemote.Modules.Screen.Capture;
using PcRemote.Modules.Screen.Encoding;

namespace PcRemote.Modules.Screen;

/// <summary>
/// One phone watching and driving one monitor, over its own socket.
///
/// Three loops:
///   - capture/encode on a dedicated thread (D3D and the encoder want one thread);
///   - a sender that writes queued messages to the socket in order;
///   - the socket reader, which injects input as it arrives.
///
/// Flow control: at most <see cref="MaxQueuedFrames"/> encoded frames wait for the
/// socket. When the phone's Wi-Fi cannot keep up, the capture loop skips frames
/// (the newest image is encoded once there is room) and lowers the bitrate; it
/// climbs back while the link keeps up. Frames are never dropped after encoding:
/// every P-frame depends on the one before.
///
/// Wire format (binary frames, big-endian):
///   0x01 video   [1]=flags (bit0 key frame) [2..9]=capture time µs, then Annex B H.264
///   0x02 cursor  [1]=visible [2..3]=x [4..5]=y (source pixels of the monitor)
///   0x03 shape   [2..3]=w [4..5]=h [6..7]=hot x [8..9]=hot y, then w*h RGBA
/// Text frames carry JSON: config, status, stats, pong, error.
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class ScreenSession
{
    private const int MaxQueuedFrames = 2;
    private const int MaxInputMessageBytes = 16 * 1024;
    private const int MinBitrate = 800_000;

    private readonly ModuleSocket _socket;
    private readonly ILogger _logger;
    private readonly Channel<Outgoing> _outgoing = Channel.CreateUnbounded<Outgoing>(new UnboundedChannelOptions { SingleReader = true });
    private readonly ConcurrentQueue<Action> _pipelineCommands = new();
    private readonly HashSet<string> _heldButtons = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _heldKeys = new(StringComparer.OrdinalIgnoreCase);

    private int _queuedFrames;
    private volatile DisplayInfo? _display;
    private volatile bool _inputBlocked;
    private Quality _quality;
    private int _requestedDisplay;
    private bool _displayChanged = true;
    private bool _keyFrameWanted;

    private readonly record struct Outgoing(byte[] Data, bool Binary, bool IsFrame);

    public ScreenSession(ModuleSocket socket, ILogger logger)
    {
        _socket = socket;
        _logger = logger;
        var hello = socket.Hello;
        _requestedDisplay = hello.TryGetProperty("display", out var d) && d.ValueKind == JsonValueKind.Number ? d.GetInt32() : -1;
        _quality = Quality.Parse(hello.TryGetProperty("quality", out var q) ? q.GetString() : null);
    }

    /// <summary>Total time someone watched, for the activity entry.</summary>
    public TimeSpan Duration => _clock.Elapsed;
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    public async Task RunAsync(CancellationToken ct)
    {
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var sender = Task.Run(() => SendLoopAsync(stop.Token), CancellationToken.None);
        var pipeline = new Thread(() => Pipeline(stop.Token)) { IsBackground = true, Name = "PcRemote screen" };
        pipeline.Start();
        try
        {
            await ReceiveLoopAsync(stop.Token);
        }
        finally
        {
            stop.Cancel();
            _outgoing.Writer.TryComplete();
            ReleaseHeld();
            await Task.Run(() => pipeline.Join(TimeSpan.FromSeconds(3)), CancellationToken.None);
            try { await sender; } catch { /* socket gone */ }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Capture → encode
    // ══════════════════════════════════════════════════════════════

    private void Pipeline(CancellationToken ct)
    {
        DesktopDuplicator? dup = null;
        H264Encoder? encoder = null;
        byte[]? parameterSets = null;
        var bitrate = 0;
        var dirty = false;
        long lastEncode = 0;
        var blockedSince = 0L;
        var stats = new Stats();
        var rate = new RateControl();

        try
        {
            while (!ct.IsCancellationRequested)
            {
                while (_pipelineCommands.TryDequeue(out var command)) command();

                if (_displayChanged)
                {
                    _displayChanged = false;
                    dup?.Dispose(); dup = null;
                    encoder?.Dispose(); encoder = null;
                }

                if (dup is null)
                {
                    try
                    {
                        dup = OpenDisplay();
                        blockedSince = 0;
                        dirty = false;
                        _inputBlocked = false;
                        Send(Json(new { kind = "status", state = "ok" }));
                    }
                    catch (Exception ex) when (ex is CaptureLostException or InvalidOperationException)
                    {
                        if (blockedSince == 0)
                        {
                            blockedSince = Stopwatch.GetTimestamp();
                            _inputBlocked = true;
                            _logger.LogInformation("Screen capture unavailable: {Message}", ex.Message);
                            Send(Json(new
                            {
                                kind = "status", state = "blocked",
                                message = "Windows muestra una pantalla protegida (UAC, bloqueo o Ctrl+Alt+Supr). Se reanudará al volver al escritorio.",
                            }));
                        }
                        ct.WaitHandle.WaitOne(500);
                        continue;
                    }
                }

                if (encoder is null || (encoder.Width, encoder.Height) != Nv12.OutputSize(dup.Width, dup.Height, _quality.ScaleFor(dup.Width)))
                {
                    encoder?.Dispose();
                    var scale = _quality.ScaleFor(dup.Width);
                    var (w, h) = Nv12.OutputSize(dup.Width, dup.Height, scale);
                    bitrate = _quality.BitrateFor(w, h);
                    rate.Reset(bitrate);
                    try
                    {
                        encoder = H264Encoder.Create(w, h, _quality.Fps, bitrate, m => _logger.LogInformation("{Message}", m));
                    }
                    catch (Exception ex)
                    {
                        _logger.LogWarning(ex, "No H.264 encoder");
                        Send(Json(new { kind = "error", message = ex.Message }));
                        return;
                    }
                    parameterSets = null;
                    dirty = dup.HasImage;
                    _logger.LogInformation("Screen: {Display} {SrcW}x{SrcH} → {W}x{H} @{Fps} with {Encoder} ({Kind}), {Kbps} kbps",
                        dup.Display.Name, dup.Width, dup.Height, w, h, _quality.Fps, encoder.Name,
                        encoder.Hardware ? "GPU" : "software", bitrate / 1000);
                    Send(Json(new
                    {
                        kind = "config",
                        codec = "h264",
                        width = w, height = h,
                        sourceWidth = dup.Width, sourceHeight = dup.Height,
                        display = dup.Display,
                        displays = Displays.List(),
                        encoder = encoder.Name,
                        hardware = encoder.Hardware,
                        fps = _quality.Fps,
                        quality = _quality.Name,
                    }));
                }

                if (_keyFrameWanted)
                {
                    _keyFrameWanted = false;
                    encoder.RequestKeyFrame();
                    dirty = dup.HasImage;
                }

                var interval = Stopwatch.Frequency / _quality.Fps;
                var sinceLast = Stopwatch.GetTimestamp() - lastEncode;
                var wait = dirty ? (int)Math.Max(0, (interval - sinceLast) * 1000 / Stopwatch.Frequency) : 100;

                CaptureEvent ev;
                try { ev = dup.Acquire(wait); }
                catch (CaptureLostException ex)
                {
                    _logger.LogInformation("Screen capture lost: {Message}", ex.Message);
                    dup.Dispose(); dup = null;
                    continue;
                }

                if (ev.Shape is { } shape) Send(ShapeMessage(shape));
                if (ev.PointerMoved) Send(CursorMessage(ev.PointerVisible, ev.PointerX, ev.PointerY));
                if (ev.NewImage) dirty = true;

                foreach (var late in encoder.Collect()) QueueFrame(late, ref parameterSets, stats);

                if (!dirty || Stopwatch.GetTimestamp() - lastEncode < interval) goto Tick;

                if (Volatile.Read(ref _queuedFrames) >= MaxQueuedFrames)
                {
                    // The socket is behind: keep the image dirty and try again shortly.
                    rate.Congested();
                    ct.WaitHandle.WaitOne(4);
                    goto Tick;
                }

                lastEncode = Stopwatch.GetTimestamp();
                var started = Stopwatch.GetTimestamp();
                var units = EncodeLatest(dup, encoder);
                stats.EncodeTicks += Stopwatch.GetTimestamp() - started;
                foreach (var au in units) QueueFrame(au, ref parameterSets, stats);
                dirty = false;

            Tick:
                if (rate.Tick(encoder.Bitrate, _quality.BitrateFor(encoder.Width, encoder.Height)) is { } newRate &&
                    encoder.SetBitrate(newRate))
                {
                    _logger.LogDebug("Screen bitrate → {Kbps} kbps", newRate / 1000);
                }
                if (stats.Due())
                    Send(Json(stats.Snapshot(encoder, Volatile.Read(ref _queuedFrames))));
            }
        }
        catch (Exception ex) when (!ct.IsCancellationRequested)
        {
            _logger.LogWarning(ex, "Screen pipeline failed");
            Send(Json(new { kind = "error", message = "La captura falló: " + ex.Message }));
        }
        finally
        {
            encoder?.Dispose();
            dup?.Dispose();
            _outgoing.Writer.TryComplete();
        }
    }

    private DesktopDuplicator OpenDisplay()
    {
        var displays = Displays.List();
        if (displays.Count == 0) throw new CaptureLostException("No hay pantallas.");
        var display = _requestedDisplay >= 0 && _requestedDisplay < displays.Count
            ? displays[_requestedDisplay]
            : displays.FirstOrDefault(d => d.Primary) ?? displays[0];
        var dup = new DesktopDuplicator(display);
        _display = display;
        return dup;
    }

    private unsafe List<byte[]> EncodeLatest(DesktopDuplicator dup, H264Encoder encoder)
    {
        var scale = dup.Width / encoder.Width >= 2 ? 2 : 1;
        List<byte[]> units = [];
        dup.ReadLatest((src, pitch) =>
        {
            var ts = (long)(_clock.Elapsed.TotalMilliseconds * 10_000);
            units = encoder.Encode(dst => Nv12.Convert((byte*)src, pitch, encoder.Width, encoder.Height, scale, (byte*)dst), ts);
        });
        return units;
    }

    private void QueueFrame(byte[] au, ref byte[]? parameterSets, Stats stats)
    {
        var payload = AnnexB.Prepare(au, ref parameterSets, out var key);
        if (payload.Length == 0) return;
        var msg = new byte[10 + payload.Length];
        msg[0] = 0x01;
        msg[1] = (byte)(key ? 1 : 0);
        BinaryPrimitives.WriteInt64BigEndian(msg.AsSpan(2), (long)_clock.Elapsed.TotalMicroseconds);
        payload.CopyTo(msg, 10);
        stats.Frames++;
        stats.Bytes += msg.Length;
        Interlocked.Increment(ref _queuedFrames);
        if (!_outgoing.Writer.TryWrite(new Outgoing(msg, true, true))) Interlocked.Decrement(ref _queuedFrames);
    }

    private static byte[] CursorMessage(bool visible, int x, int y)
    {
        var msg = new byte[6];
        msg[0] = 0x02;
        msg[1] = (byte)(visible ? 1 : 0);
        BinaryPrimitives.WriteInt16BigEndian(msg.AsSpan(2), (short)Math.Clamp(x, short.MinValue, short.MaxValue));
        BinaryPrimitives.WriteInt16BigEndian(msg.AsSpan(4), (short)Math.Clamp(y, short.MinValue, short.MaxValue));
        return msg;
    }

    private static byte[] ShapeMessage(CursorShape shape)
    {
        var msg = new byte[10 + shape.Rgba.Length];
        msg[0] = 0x03;
        BinaryPrimitives.WriteUInt16BigEndian(msg.AsSpan(2), (ushort)shape.Width);
        BinaryPrimitives.WriteUInt16BigEndian(msg.AsSpan(4), (ushort)shape.Height);
        BinaryPrimitives.WriteUInt16BigEndian(msg.AsSpan(6), (ushort)Math.Clamp(shape.HotX, 0, shape.Width));
        BinaryPrimitives.WriteUInt16BigEndian(msg.AsSpan(8), (ushort)Math.Clamp(shape.HotY, 0, shape.Height));
        shape.Rgba.CopyTo(msg, 10);
        return msg;
    }

    // ══════════════════════════════════════════════════════════════
    // Socket I/O
    // ══════════════════════════════════════════════════════════════

    private static Outgoing Json(object payload) =>
        new(JsonSerializer.SerializeToUtf8Bytes(payload, JsonOpts), false, false);

    private void Send(Outgoing message) => _outgoing.Writer.TryWrite(message);
    private void Send(byte[] binary) => _outgoing.Writer.TryWrite(new Outgoing(binary, true, false));

    private async Task SendLoopAsync(CancellationToken ct)
    {
        try
        {
            await foreach (var msg in _outgoing.Reader.ReadAllAsync(ct))
            {
                try
                {
                    if (msg.Binary) await _socket.SendBinaryAsync(msg.Data, ct);
                    else await _socket.SendTextAsync(msg.Data, ct);
                }
                finally
                {
                    if (msg.IsFrame) Interlocked.Decrement(ref _queuedFrames);
                }
            }
            // The pipeline ended (no encoder, capture failed) or the session is closing.
            await _socket.CloseAsync("Screen ended");
        }
        catch (Exception ex) when (ex is OperationCanceledException or System.Net.WebSockets.WebSocketException or ObjectDisposedException) { }
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var raw = await _socket.ReceiveTextAsync(MaxInputMessageBytes, ct);
            if (raw is null) return;
            try
            {
                using var doc = JsonDocument.Parse(raw);
                await HandleAsync(doc.RootElement, ct);
            }
            catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException or FormatException)
            {
                _logger.LogDebug("Bad screen message: {Message}", ex.Message);
            }
        }
    }

    private async Task HandleAsync(JsonElement m, CancellationToken ct)
    {
        switch (m.GetProperty("t").GetString())
        {
            case "mv":
                if (_display is { } d && !_inputBlocked)
                {
                    var x = Math.Clamp(m.GetProperty("x").GetDouble(), 0, 1);
                    var y = Math.Clamp(m.GetProperty("y").GetDouble(), 0, 1);
                    InputInjector.MoveTo(d.X + (int)Math.Min(d.Width - 1, x * d.Width), d.Y + (int)Math.Min(d.Height - 1, y * d.Height));
                }
                break;

            case "btn":
            {
                var button = m.GetProperty("b").GetString() ?? "left";
                var down = m.GetProperty("d").GetBoolean();
                if (InputInjector.Button(button, down))
                {
                    if (down) _heldButtons.Add(button); else _heldButtons.Remove(button);
                }
                break;
            }

            case "wheel":
                InputInjector.Wheel(m.TryGetProperty("dy", out var dy) ? (int)dy.GetDouble() : 0, horizontal: false);
                InputInjector.Wheel(m.TryGetProperty("dx", out var dx) ? (int)dx.GetDouble() : 0, horizontal: true);
                break;

            case "keys":
                InputInjector.KeyCombo(m.GetProperty("k").GetString() ?? "");
                break;

            case "key":
            {
                var key = m.GetProperty("k").GetString() ?? "";
                var down = m.GetProperty("d").GetBoolean();
                if (InputInjector.KeyState(key, down))
                {
                    if (down) _heldKeys.Add(key); else _heldKeys.Remove(key);
                }
                break;
            }

            case "text":
            {
                var text = m.GetProperty("s").GetString() ?? "";
                if (text.Length <= 4096) InputInjector.Type(text);
                break;
            }

            case "kf":
                _keyFrameWanted = true;
                break;

            case "display":
            {
                var index = m.GetProperty("i").GetInt32();
                _pipelineCommands.Enqueue(() => { _requestedDisplay = index; _displayChanged = true; });
                break;
            }

            case "quality":
            {
                var quality = Quality.Parse(m.GetProperty("q").GetString());
                _pipelineCommands.Enqueue(() => { _quality = quality; _displayChanged = true; });
                break;
            }

            case "ping":
                await _socket.SendJsonAsync(new { kind = "pong", ts = m.GetProperty("ts").GetInt64() }, ct);
                break;
        }
    }

    /// <summary>A phone that vanished mid-drag must not leave a button or Ctrl held on the PC.</summary>
    private void ReleaseHeld()
    {
        foreach (var b in _heldButtons) InputInjector.Button(b, false);
        foreach (var k in _heldKeys) InputInjector.KeyState(k, false);
        _heldButtons.Clear();
        _heldKeys.Clear();
    }

    private static readonly JsonSerializerOptions JsonOpts = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    // ══════════════════════════════════════════════════════════════
    // Quality, bitrate and stats
    // ══════════════════════════════════════════════════════════════

    /// <summary>Presets the phone picks from. Bitrates are for 1080p and scale with the pixel count.</summary>
    internal sealed record Quality(string Name, int Fps, int Bitrate1080p, int MaxWidth)
    {
        public static readonly Quality Speed    = new("speed",    30,  3_000_000, 1920);
        public static readonly Quality Balanced = new("balanced", 60,  6_000_000, 2560);
        public static readonly Quality Best     = new("quality",  60, 12_000_000, 4096);

        public static Quality Parse(string? name) => name switch
        {
            "speed" => Speed,
            "quality" => Best,
            _ => Balanced,
        };

        /// <summary>Halve screens wider than this preset sends (a 4K monitor on "balanced" goes out at 1080p).</summary>
        public int ScaleFor(int width) => width > MaxWidth ? 2 : 1;

        public int BitrateFor(int width, int height) =>
            (int)Math.Clamp(Bitrate1080p * (width * (double)height) / (1920 * 1080), 1_500_000, 40_000_000);
    }

    /// <summary>
    /// Lowers the bitrate by 30 % when the socket kept falling behind during the
    /// last second; raises it 15 % after 4 s without trouble, up to the preset.
    /// </summary>
    private sealed class RateControl
    {
        private long _windowStart = Stopwatch.GetTimestamp();
        private int _congested;
        private int _calmWindows;

        public void Reset(int bitrate)
        {
            _windowStart = Stopwatch.GetTimestamp();
            _congested = 0;
            _calmWindows = 0;
        }

        public void Congested() => _congested++;

        public int? Tick(int current, int target)
        {
            if (Stopwatch.GetTimestamp() - _windowStart < Stopwatch.Frequency) return null;
            _windowStart = Stopwatch.GetTimestamp();
            var congested = _congested;
            _congested = 0;

            if (congested > 3)
            {
                _calmWindows = 0;
                var lower = Math.Max(MinBitrate, (int)(current * 0.7));
                return lower < current ? lower : null;
            }
            if (++_calmWindows >= 4 && current < target)
            {
                _calmWindows = 0;
                return Math.Min(target, (int)(current * 1.15));
            }
            return null;
        }
    }

    private sealed class Stats
    {
        private long _since = Stopwatch.GetTimestamp();
        public int Frames;
        public long Bytes;
        public long EncodeTicks;

        public bool Due() => Stopwatch.GetTimestamp() - _since >= Stopwatch.Frequency;

        public object Snapshot(H264Encoder encoder, int queued)
        {
            var seconds = (Stopwatch.GetTimestamp() - _since) / (double)Stopwatch.Frequency;
            var snap = new
            {
                kind = "stats",
                fps = Math.Round(Frames / seconds, 1),
                kbps = (int)(Bytes * 8 / 1000 / seconds),
                bitrateKbps = encoder.Bitrate / 1000,
                encodeMs = Frames == 0 ? 0 : Math.Round(EncodeTicks * 1000.0 / Stopwatch.Frequency / Frames, 1),
                queued,
            };
            _since = Stopwatch.GetTimestamp();
            Frames = 0;
            Bytes = 0;
            EncodeTicks = 0;
            return snap;
        }
    }
}
