using System.Buffers.Binary;
using System.Runtime.Versioning;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace PcRemote.Modules.Screen.Audio;

/// <summary>
/// What the PC is playing, for the phone watching the screen: WASAPI loopback of
/// the default output device, as 16-bit stereo PCM at the mix rate (usually
/// 48 kHz, about 1.5 Mbit/s, little next to the video on a LAN). No codec: the
/// phone plays it as it comes. Each packet goes to <c>send</c> already framed as
/// a screen-socket message: 0x04, then little-endian samples L R L R…
///
/// Silence is not sent (WASAPI keeps delivering silent packets while nothing plays).
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class LoopbackAudio : IDisposable
{
    public const byte MessageType = 0x04;

    private readonly WasapiRecorder _capture;
    private readonly Action<byte[]> _send;
    private readonly int _channels;
    private readonly int _bytesPerSample;

    public int SampleRate { get; }

    // shortcut: keeps the device that was the default when it started; a new default
    // output (headphones plugged in) needs the phone to switch the sound off and on.
    public LoopbackAudio(Action<byte[]> send)
    {
        _send = send;
        _capture = new WasapiRecorderBuilder().WithLoopbackCapture().Build();
        var format = _capture.WaveFormat;
        // Shared-mode mix formats are 32-bit float; 16-bit PCM shows up on a few drivers.
        _bytesPerSample = format.BitsPerSample / 8;
        if (_bytesPerSample is not (2 or 4) || format.Channels < 1)
        {
            _capture.Dispose();
            throw new NotSupportedException($"Formato de audio no compatible: {format}");
        }
        _channels = format.Channels;
        SampleRate = format.SampleRate;
        _capture.DataAvailable += OnData;
        _capture.StartRecording();
    }

    private void OnData(ReadOnlySpan<byte> buffer, AudioClientBufferFlags flags, long devicePosition, long qpcPosition)
    {
        if (flags.HasFlag(AudioClientBufferFlags.Silent)) return;
        var message = ToMessage(buffer, _channels, _bytesPerSample);
        if (message is not null) _send(message);
    }

    /// <summary>
    /// Interleaved float32 or int16 frames → [0x04][int16 L, R]…; extra channels are
    /// dropped, mono is doubled. Null when there is nothing to hear.
    /// </summary>
    internal static byte[]? ToMessage(ReadOnlySpan<byte> buffer, int channels, int bytesPerSample)
    {
        var frameBytes = channels * bytesPerSample;
        var frames = buffer.Length / frameBytes;
        if (frames == 0) return null;

        var message = new byte[1 + frames * 4];
        message[0] = MessageType;
        var silent = true;
        for (int f = 0; f < frames; f++)
        {
            var at = f * frameBytes;
            var left = Sample(buffer, at, bytesPerSample);
            var right = channels > 1 ? Sample(buffer, at + bytesPerSample, bytesPerSample) : left;
            silent &= left == 0 && right == 0;
            BinaryPrimitives.WriteInt16LittleEndian(message.AsSpan(1 + f * 4), left);
            BinaryPrimitives.WriteInt16LittleEndian(message.AsSpan(3 + f * 4), right);
        }
        return silent ? null : message;
    }

    private static short Sample(ReadOnlySpan<byte> s, int at, int bytes) => bytes == 2
        ? BinaryPrimitives.ReadInt16LittleEndian(s[at..])
        : (short)Math.Clamp(BinaryPrimitives.ReadSingleLittleEndian(s[at..]) * 32767f, short.MinValue, short.MaxValue);

    public void Dispose()
    {
        try { _capture.StopRecording(); } catch { /* device already gone */ }
        _capture.Dispose();
    }
}
