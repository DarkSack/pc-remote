using System.Diagnostics;
using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace PcRemote.Modules.Screen.Encoding;

/// <summary>
/// H.264 through a Media Foundation transform: the GPU's encoder (AMD AMF,
/// NVIDIA NVENC, Intel Quick Sync) when there is one, Microsoft's software
/// encoder otherwise. Input is NV12 in system memory; output is Annex B, one
/// access unit per frame (low-latency mode, no B-frames).
///
/// Hardware encoders are asynchronous MFTs: they say when they want input
/// (METransformNeedInput) and when output is ready (METransformHaveOutput).
/// The software one is synchronous: ProcessInput, then ProcessOutput.
///
/// Not thread-safe: one thread drives it.
/// </summary>
internal sealed class H264Encoder : IDisposable
{
    private const int StreamId = 0;
    private const int MF_EVENT_FLAG_NO_WAIT = 1;
    private const int InputPoolSize = 3;

    /// <summary>Longest wait for an async encoder to ask for input or to hand back a frame.</summary>
    private static readonly TimeSpan InputWait = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan OutputWait = TimeSpan.FromMilliseconds(60);

    private readonly IMFTransform _mft;
    private readonly IMFMediaEventGenerator? _events;
    private readonly IMFSample[] _inputs = new IMFSample[InputPoolSize];
    private readonly IMFSample? _output;
    private readonly int _fps;
    private int _nextInput;
    private int _needInput;
    private bool _disposed;

    public string Name { get; }
    public bool Hardware { get; }
    public int Width { get; }
    public int Height { get; }
    public int Bitrate { get; private set; }

    /// <summary>Frame size in bytes of the NV12 the caller writes.</summary>
    public int FrameBytes => Width * Height * 3 / 2;

    private static int _started;

    /// <summary>MFStartup once per process. Lite: no network sources, which we never use.</summary>
    public static void EnsureStarted()
    {
        if (Interlocked.Exchange(ref _started, 1) == 0)
            MediaFactory.MFStartup(true).CheckError();
    }

    /// <summary>The first encoder that accepts this size: hardware first, then software.</summary>
    public static H264Encoder Create(int width, int height, int fps, int bitrate, Action<string>? log = null)
    {
        EnsureStarted();
        var errors = new List<string>();
        foreach (var hardware in new[] { true, false })
        {
            var flags = (uint)((hardware ? EnumFlag.EnumFlagHardware : EnumFlag.EnumFlagSyncmft) | EnumFlag.EnumFlagSortandfilter);
            var output = new RegisterTypeInfo { GuidMajorType = MfGuids.MediaTypeVideo, GuidSubtype = MfGuids.H264 };
            using var found = MediaFactory.MFTEnumEx(MfGuids.CategoryVideoEncoder, flags, null, output);
            foreach (var activate in found)
            {
                var name = "?";
                try
                {
                    name = SafeName(activate);
                    var mft = activate.ActivateObject<IMFTransform>();
                    try
                    {
                        return new H264Encoder(mft, name, hardware, width, height, fps, bitrate);
                    }
                    catch
                    {
                        mft.Dispose();
                        throw;
                    }
                }
                catch (Exception ex)
                {
                    errors.Add($"{name}: {ex.Message}");
                    log?.Invoke($"Encoder {name} unusable: {ex.Message}");
                }
                finally { activate.Dispose(); }
            }
        }
        throw new NotSupportedException(errors.Count == 0
            ? "Este PC no tiene codificador H.264 (¿Windows N sin el Media Feature Pack?)."
            : "Ningún codificador H.264 aceptó la pantalla: " + string.Join("; ", errors));
    }

    private static string SafeName(IMFActivate a)
    {
        try { return a.GetString(MfGuids.FriendlyName); } catch { return "H.264"; }
    }

    private H264Encoder(IMFTransform mft, string name, bool hardware, int width, int height, int fps, int bitrate)
    {
        _mft = mft;
        Name = name;
        Hardware = hardware;
        Width = width;
        Height = height;
        _fps = fps;
        Bitrate = bitrate;

        if (hardware)
        {
            _mft.Attributes.Set(MfGuids.AsyncUnlock, 1u).CheckError();
            _events = _mft.QueryInterface<IMFMediaEventGenerator>();
        }

        CodecApi.SetUInt32(_mft, MfGuids.LowLatency, 1);
        CodecApi.SetUInt32(_mft, MfGuids.RateControlMode, MfGuids.RateControlPeakConstrainedVbr);
        CodecApi.SetUInt32(_mft, MfGuids.MeanBitRate, (uint)bitrate);
        CodecApi.SetUInt32(_mft, MfGuids.MaxBitRate, (uint)(bitrate * 3L / 2));
        CodecApi.SetUInt32(_mft, MfGuids.BPictureCount, 0);
        // Over TCP nothing is lost, so key frames are only needed when the phone
        // asks (new decoder). A long GOP keeps static screens cheap.
        CodecApi.SetUInt32(_mft, MfGuids.GopSize, (uint)(fps * 120));
        CodecApi.SetUInt32(_mft, MfGuids.QualityVsSpeed, 60);

        // Output type first: encoders only list input types once they know the output.
        using (var type = MediaFactory.MFCreateMediaType())
        {
            SetVideoType(type, MfGuids.H264, width, height, fps);
            type.Set(MfGuids.AvgBitrate, (uint)bitrate);
            type.Set(MfGuids.Mpeg2Profile, MfGuids.ProfileMain);
            _mft.SetOutputType(StreamId, type, 0);
        }
        using (var type = MediaFactory.MFCreateMediaType())
        {
            SetVideoType(type, MfGuids.NV12, width, height, fps);
            // Tagged so the stream says BT.709 limited range, which is what Nv12 writes.
            type.Set(MfGuids.YuvMatrix, 1u);
            type.Set(MfGuids.NominalRange, 2u);
            type.Set(MfGuids.Primaries, 2u);
            type.Set(MfGuids.TransferFunction, 5u);
            _mft.SetInputType(StreamId, type, 0);
        }

        for (int i = 0; i < _inputs.Length; i++)
        {
            _inputs[i] = MediaFactory.MFCreateSample();
            using var buffer = MediaFactory.MFCreateMemoryBuffer(FrameBytes);
            _inputs[i].AddBuffer(buffer);
        }

        var info = _mft.GetOutputStreamInfo(StreamId);
        const int ProvidesSamples = 0x100, CanProvideSamples = 0x200;
        if ((info.Flags & (ProvidesSamples | CanProvideSamples)) == 0)
        {
            _output = MediaFactory.MFCreateSample();
            using var buffer = MediaFactory.MFCreateMemoryBuffer(Math.Max(info.Size, FrameBytes));
            _output.AddBuffer(buffer);
        }

        _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero);
        _mft.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
        _mft.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
    }

    private static void SetVideoType(IMFMediaType type, Guid subtype, int width, int height, int fps)
    {
        type.Set(MfGuids.MajorType, MfGuids.MediaTypeVideo);
        type.Set(MfGuids.Subtype, subtype);
        type.Set(MfGuids.FrameSize, ((ulong)width << 32) | (uint)height);
        type.Set(MfGuids.FrameRate, ((ulong)fps << 32) | 1u);
        type.Set(MfGuids.PixelAspect, (1ul << 32) | 1u);
        type.Set(MfGuids.InterlaceMode, 2u); // progressive
    }

    /// <summary>Changes the target bitrate on the fly. Encoders that refuse keep the old one.</summary>
    public bool SetBitrate(int bitrate)
    {
        if (bitrate == Bitrate) return true;
        var ok = CodecApi.SetUInt32(_mft, MfGuids.MeanBitRate, (uint)bitrate);
        CodecApi.SetUInt32(_mft, MfGuids.MaxBitRate, (uint)(bitrate * 3L / 2));
        if (ok) Bitrate = bitrate;
        return ok;
    }

    /// <summary>The next frame comes out as an IDR. False if the encoder does not support it.</summary>
    public bool RequestKeyFrame() => CodecApi.SetUInt32(_mft, MfGuids.ForceKeyFrame, 1);

    /// <summary>
    /// Encodes one frame: <paramref name="fill"/> writes NV12 (<see cref="FrameBytes"/>
    /// bytes) into the pointer it gets. Returns the access units that came out, usually
    /// exactly this frame; an async encoder that is late hands it over on the next call
    /// or in <see cref="Collect"/>.
    /// </summary>
    public unsafe List<byte[]> Encode(Action<IntPtr> fill, long timestamp100ns)
    {
        var results = new List<byte[]>(1);
        var sample = _inputs[_nextInput];
        _nextInput = (_nextInput + 1) % _inputs.Length;

        using (var buffer = sample.GetBufferByIndex(0))
        {
            buffer.Lock(out var ptr, out _, out _);
            try { fill(ptr); }
            finally { buffer.Unlock(); }
            buffer.CurrentLength = FrameBytes;
        }
        sample.SampleTime = timestamp100ns;
        sample.SampleDuration = 10_000_000 / _fps;

        if (_events is null)
        {
            _mft.ProcessInput(StreamId, sample, 0);
            DrainOutput(results);
            return results;
        }

        var deadline = Stopwatch.GetTimestamp() + (long)(InputWait.TotalSeconds * Stopwatch.Frequency);
        while (_needInput == 0)
        {
            if (!PumpEvent(results, deadline)) throw new TimeoutException($"{Name} no pidió más fotogramas.");
        }
        _needInput--;
        _mft.ProcessInput(StreamId, sample, 0);

        // Low-latency mode gives one frame out per frame in, a few ms later.
        var outDeadline = Stopwatch.GetTimestamp() + (long)(OutputWait.TotalSeconds * Stopwatch.Frequency);
        var before = results.Count;
        while (results.Count == before && PumpEvent(results, outDeadline)) { }
        return results;
    }

    /// <summary>Output an async encoder finished after <see cref="Encode"/> returned.</summary>
    public List<byte[]> Collect()
    {
        var results = new List<byte[]>();
        if (_events is null) return results;
        while (TryGetEvent(out var ev))
            using (ev) Handle(ev!, results);
        return results;
    }

    /// <summary>Handles one event, waiting for it until <paramref name="deadline"/>. False on timeout.</summary>
    private bool PumpEvent(List<byte[]> results, long deadline)
    {
        var spin = new SpinWait();
        while (true)
        {
            if (TryGetEvent(out var ev))
            {
                using (ev) Handle(ev!, results);
                return true;
            }
            if (Stopwatch.GetTimestamp() > deadline) return false;
            if (spin.Count < 20) spin.SpinOnce(); else Thread.Sleep(1);
        }
    }

    private bool TryGetEvent(out IMFMediaEvent? ev)
    {
        try
        {
            ev = _events!.GetEvent(MF_EVENT_FLAG_NO_WAIT);
            return true;
        }
        catch (SharpGenException e) when (e.HResult == MfGuids.MF_E_NO_EVENTS_AVAILABLE)
        {
            ev = null;
            return false;
        }
    }

    private void Handle(IMFMediaEvent ev, List<byte[]> results)
    {
        switch (ev.EventType)
        {
            case MediaEventTypes.TransformNeedInput:
                _needInput++;
                break;
            case MediaEventTypes.TransformHaveOutput:
                DrainOutput(results, single: true);
                break;
        }
    }

    private void DrainOutput(List<byte[]> results, bool single = false)
    {
        while (true)
        {
            var odb = new OutputDataBuffer { StreamID = StreamId, Sample = _output! };
            var hr = _mft.ProcessOutput(ProcessOutputFlags.None, 1, ref odb, out _);
            odb.Events?.Dispose();

            if (hr.Code == MfGuids.MF_E_TRANSFORM_NEED_MORE_INPUT) return;
            if (hr.Code == MfGuids.MF_E_TRANSFORM_STREAM_CHANGE)
            {
                using var type = _mft.GetOutputAvailableType(StreamId, 0);
                _mft.SetOutputType(StreamId, type, 0);
                continue;
            }
            hr.CheckError();

            var sample = odb.Sample;
            using (var buffer = sample.ConvertToContiguousBuffer())
            {
                buffer.Lock(out var ptr, out _, out var length);
                try
                {
                    var bytes = new byte[length];
                    Marshal.Copy(ptr, bytes, 0, length);
                    results.Add(bytes);
                }
                finally { buffer.Unlock(); }
                buffer.CurrentLength = 0;
            }
            // Our own reusable sample stays; one the encoder allocated is released.
            if (_output is null || sample.NativePointer != _output.NativePointer) sample.Dispose();
            if (single) return;
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { _mft.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero); } catch { }
        try { _mft.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero); } catch { }
        try { _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero); } catch { }
        foreach (var s in _inputs) s?.Dispose();
        _output?.Dispose();
        _events?.Dispose();
        // Hardware MFTs hold a GPU session until shut down; Release alone may not end it.
        try { MediaFactory.MFShutdownObject(_mft); } catch { }
        _mft.Dispose();
    }
}
