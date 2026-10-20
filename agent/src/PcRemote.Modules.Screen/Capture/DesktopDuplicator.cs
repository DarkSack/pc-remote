using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace PcRemote.Modules.Screen.Capture;

/// <summary>A monitor as the phone sees it. Coordinates are physical pixels of the virtual desktop.</summary>
public sealed record DisplayInfo(int Index, string Id, string Name, int X, int Y, int Width, int Height, bool Primary);

internal static class Displays
{
    /// <summary>Every monitor attached to the desktop, left to right.</summary>
    public static List<DisplayInfo> List()
    {
        var found = new List<(string Id, Vortice.RawRect Rect)>();
        using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
        for (uint a = 0; factory.EnumAdapters1(a, out var adapter).Success; a++)
        {
            using (adapter)
            {
                for (uint o = 0; adapter.EnumOutputs(o, out var output).Success; o++)
                {
                    using (output)
                    {
                        var d = output.Description;
                        if (d.AttachedToDesktop) found.Add((d.DeviceName, d.DesktopCoordinates));
                    }
                }
            }
        }
        return found
            .OrderBy(f => f.Rect.Left).ThenBy(f => f.Rect.Top)
            .Select((f, i) =>
            {
                var w = f.Rect.Right - f.Rect.Left;
                var h = f.Rect.Bottom - f.Rect.Top;
                var primary = f.Rect.Left == 0 && f.Rect.Top == 0;
                return new DisplayInfo(i, f.Id, $"Pantalla {i + 1}{(primary ? " (principal)" : "")}",
                    f.Rect.Left, f.Rect.Top, w, h, primary);
            })
            .ToList();
    }
}

/// <summary>What one call to <see cref="DesktopDuplicator.Acquire"/> brought.</summary>
internal struct CaptureEvent
{
    public bool NewImage;
    public bool PointerMoved;
    public bool PointerVisible;
    public int PointerX, PointerY;
    /// <summary>Set when the cursor changed shape: RGBA, row-major.</summary>
    public CursorShape? Shape;
}

internal sealed record CursorShape(int Width, int Height, int HotX, int HotY, byte[] Rgba);

/// <summary>
/// DXGI Desktop Duplication of one monitor. Every new image is copied to a GPU
/// texture right away (cheap); the CPU copy happens only when a frame is about
/// to be encoded (<see cref="ReadLatest"/>), so a capped frame rate costs no
/// readbacks for the frames it skips.
///
/// Throws <see cref="CaptureLostException"/> when Windows takes the desktop away
/// (UAC prompt, lock screen, resolution change, full-screen game switching mode):
/// the caller recreates it, which fails until the normal desktop is back.
/// </summary>
internal sealed class DesktopDuplicator : IDisposable
{
    private const int DXGI_ERROR_WAIT_TIMEOUT = unchecked((int)0x887A0027);
    private const int DXGI_ERROR_ACCESS_LOST  = unchecked((int)0x887A0026);
    private const int DXGI_ERROR_INVALID_CALL = unchecked((int)0x887A0001);

    private readonly ID3D11Device _device;
    private readonly ID3D11DeviceContext _context;
    private readonly IDXGIOutputDuplication _duplication;
    private readonly ModeRotation _rotation;
    private ID3D11Texture2D _latest = null!;
    private ID3D11Texture2D _staging = null!;
    private byte[] _shapeBuffer = new byte[64 * 64 * 4];
    private byte[] _rotated = [];

    /// <summary>Size of the image DXGI hands over: always unrotated, even on a portrait monitor.</summary>
    private int _texWidth, _texHeight;

    public DisplayInfo Display { get; }

    /// <summary>Size of the monitor as its user sees it (rotation applied): what the phone gets.</summary>
    public int Width { get; private set; }
    public int Height { get; private set; }

    /// <summary>Monitor turned in Windows' display settings: the image is rotated before encoding.</summary>
    private bool Rotated => _rotation is ModeRotation.Rotate90 or ModeRotation.Rotate180 or ModeRotation.Rotate270;

    /// <summary>True once a full image is in the GPU copy.</summary>
    public bool HasImage { get; private set; }

    public DesktopDuplicator(DisplayInfo display)
    {
        Display = display;
        using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
        IDXGIAdapter1? adapter = null;
        IDXGIOutput? output = null;
        for (uint a = 0; output is null && factory.EnumAdapters1(a, out var ad).Success; a++)
        {
            for (uint o = 0; ad.EnumOutputs(o, out var candidate).Success; o++)
            {
                if (candidate.Description.DeviceName == display.Id) { output = candidate; break; }
                candidate.Dispose();
            }
            if (output is null) ad.Dispose(); else adapter = ad;
        }
        if (output is null || adapter is null)
            throw new CaptureLostException($"{display.Name} ya no está conectada.");

        try
        {
            // The device must live on the adapter that drives the monitor.
            D3D11.D3D11CreateDevice(adapter, DriverType.Unknown, DeviceCreationFlags.BgraSupport,
                [FeatureLevel.Level_11_1, FeatureLevel.Level_11_0, FeatureLevel.Level_10_1, FeatureLevel.Level_10_0],
                out _device!, out _context!).CheckError();
            using var output1 = output.QueryInterface<IDXGIOutput1>();
            try { _duplication = output1.DuplicateOutput(_device); }
            catch (SharpGenException ex)
            {
                _context.Dispose();
                _device.Dispose();
                // E_ACCESSDENIED on the secure desktop / lock screen; UNSUPPORTED or
                // NOT_CURRENTLY_AVAILABLE when another app holds too many duplications.
                throw new CaptureLostException(ex.Message, ex);
            }
        }
        finally
        {
            output.Dispose();
            adapter.Dispose();
        }

        var dd = _duplication.Description;
        _rotation = dd.Rotation;
        CreateTextures((int)dd.ModeDescription.Width, (int)dd.ModeDescription.Height);
    }

    /// <summary>GPU copy and CPU readback textures for an image of this (unrotated) size.</summary>
    private void CreateTextures(int width, int height)
    {
        _staging?.Dispose();
        _latest?.Dispose();
        _texWidth = width;
        _texHeight = height;
        var sideways = _rotation is ModeRotation.Rotate90 or ModeRotation.Rotate270;
        (Width, Height) = sideways ? (height, width) : (width, height);
        HasImage = false;

        var desc = new Texture2DDescription
        {
            Width = (uint)width,
            Height = (uint)height,
            MipLevels = 1,
            ArraySize = 1,
            Format = Format.B8G8R8A8_UNorm,
            SampleDescription = new SampleDescription(1, 0),
            Usage = ResourceUsage.Default,
        };
        _latest = _device.CreateTexture2D(desc);
        desc.Usage = ResourceUsage.Staging;
        desc.CPUAccessFlags = CpuAccessFlags.Read;
        _staging = _device.CreateTexture2D(desc);
    }

    /// <summary>Waits up to <paramref name="timeoutMs"/> for a desktop or pointer update.</summary>
    public CaptureEvent Acquire(int timeoutMs)
    {
        var ev = new CaptureEvent();
        var hr = _duplication.AcquireNextFrame((uint)Math.Max(0, timeoutMs), out var info, out var resource);
        if (hr.Code == DXGI_ERROR_WAIT_TIMEOUT) return ev;
        if (hr.Code is DXGI_ERROR_ACCESS_LOST or DXGI_ERROR_INVALID_CALL || hr.Failure)
            throw new CaptureLostException(hr.Description ?? hr.ToString());

        try
        {
            if (info.LastPresentTime != 0)
            {
                using var texture = resource.QueryInterface<ID3D11Texture2D>();
                // The real image size wins over the mode's: CopyResource needs both equal.
                var td = texture.Description;
                if ((int)td.Width != _texWidth || (int)td.Height != _texHeight) CreateTextures((int)td.Width, (int)td.Height);
                _context.CopyResource(_latest, texture);
                HasImage = true;
                ev.NewImage = true;
            }
            if (info.LastMouseUpdateTime != 0)
            {
                ev.PointerMoved = true;
                ev.PointerVisible = info.PointerPosition.Visible;
                ev.PointerX = info.PointerPosition.Position.X;
                ev.PointerY = info.PointerPosition.Position.Y;
                // On a turned monitor DXGI does not document the space of that position;
                // the desktop position of the cursor is unambiguous (the agent is DPI aware).
                if (Rotated && GetCursorPos(out var pt))
                {
                    ev.PointerX = pt.X - Display.X;
                    ev.PointerY = pt.Y - Display.Y;
                }
            }
            if (info.PointerShapeBufferSize > 0)
                ev.Shape = ReadShape(info.PointerShapeBufferSize);
        }
        finally
        {
            resource?.Dispose();
            _duplication.ReleaseFrame();
        }
        return ev;
    }

    /// <summary>Maps the latest image and hands its pixels (BGRA, <c>pitch</c> bytes per row) to <paramref name="use"/>.</summary>
    public unsafe void ReadLatest(Action<IntPtr, int> use)
    {
        _context.CopyResource(_staging, _latest);
        var map = _context.Map(_staging, 0, MapMode.Read, Vortice.Direct3D11.MapFlags.None);
        try
        {
            if (!Rotated)
            {
                use(map.DataPointer, (int)map.RowPitch);
                return;
            }
            if (_rotated.Length != Width * Height * 4) _rotated = new byte[Width * Height * 4];
            fixed (byte* dst = _rotated)
            {
                ImageRotation.Apply((uint*)map.DataPointer, (int)map.RowPitch / 4, _texWidth, _texHeight, _rotation, (uint*)dst);
                use((IntPtr)dst, Width * 4);
            }
        }
        finally { _context.Unmap(_staging, 0); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X, Y; }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out POINT point);

    private unsafe CursorShape? ReadShape(uint size)
    {
        if (_shapeBuffer.Length < size) _shapeBuffer = new byte[size];
        fixed (byte* p = _shapeBuffer)
        {
            var hr = _duplication.GetFramePointerShape(size, (IntPtr)p, out _, out var shape);
            if (hr.Failure) return null;
            return CursorShapes.ToRgba(_shapeBuffer, shape);
        }
    }

    public void Dispose()
    {
        _staging.Dispose();
        _latest.Dispose();
        _duplication.Dispose();
        _context.Dispose();
        _device.Dispose();
    }
}

internal sealed class CaptureLostException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>
/// Turns DXGI's unrotated image of a rotated monitor into what its user sees,
/// with the same mapping as Microsoft's Desktop Duplication sample:
/// Rotate90 puts source (x, y) at (srcHeight-1-y, x), Rotate270 at (y, srcWidth-1-x).
/// </summary>
internal static class ImageRotation
{
    /// <param name="srcStride">Source row length in pixels (row pitch / 4).</param>
    /// <param name="dst">Packed output, (srcHeight × srcWidth) when sideways.</param>
    // shortcut: walks source columns (cache-unfriendly, ~10 ms for 1080×1920); a tiled copy or a GPU pass if it shows in encodeMs.
    public static unsafe void Apply(uint* src, int srcStride, int srcWidth, int srcHeight, ModeRotation rotation, uint* dst)
    {
        var sideways = rotation is ModeRotation.Rotate90 or ModeRotation.Rotate270;
        int outW = sideways ? srcHeight : srcWidth, outH = sideways ? srcWidth : srcHeight;
        var s = (nint)src;
        var d = (nint)dst;
        Parallel.For(0, outH, y =>
        {
            uint* row = (uint*)d + (long)y * outW;
            uint* p;
            long step;
            switch (rotation)
            {
                case ModeRotation.Rotate90:  // column y of the source, bottom to top
                    p = (uint*)s + (long)(srcHeight - 1) * srcStride + y; step = -srcStride; break;
                case ModeRotation.Rotate270: // column srcWidth-1-y, top to bottom
                    p = (uint*)s + (srcWidth - 1 - y); step = srcStride; break;
                case ModeRotation.Rotate180: // row srcHeight-1-y, right to left
                    p = (uint*)s + (long)(srcHeight - 1 - y) * srcStride + srcWidth - 1; step = -1; break;
                default:
                    p = (uint*)s + (long)y * srcStride; step = 1; break;
            }
            for (int x = 0; x < outW; x++, p += step) row[x] = *p;
        });
    }
}

/// <summary>DXGI pointer shapes (monochrome, color, masked color) as plain RGBA.</summary>
internal static class CursorShapes
{
    private const uint Monochrome = 1, Color = 2, MaskedColor = 4;

    public static CursorShape? ToRgba(byte[] buf, OutduplPointerShapeInfo info)
    {
        int w = (int)info.Width, pitch = (int)info.Pitch;
        int h = info.Type == Monochrome ? (int)info.Height / 2 : (int)info.Height;
        if (w <= 0 || h <= 0 || w > 256 || h > 256) return null;
        var rgba = new byte[w * h * 4];

        switch (info.Type)
        {
            case Color:
                for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                {
                    int s = y * pitch + x * 4, d = (y * w + x) * 4;
                    rgba[d] = buf[s + 2]; rgba[d + 1] = buf[s + 1]; rgba[d + 2] = buf[s]; rgba[d + 3] = buf[s + 3];
                }
                break;

            case MaskedColor:
                // Alpha 0: draw the color. Alpha 0xFF: XOR with the screen; black XOR is a
                // no-op (transparent), anything else is drawn as an inverting pixel (black).
                for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                {
                    int s = y * pitch + x * 4, d = (y * w + x) * 4;
                    bool xor = buf[s + 3] != 0;
                    bool black = buf[s] == 0 && buf[s + 1] == 0 && buf[s + 2] == 0;
                    if (xor && black) continue;
                    rgba[d] = xor ? (byte)0 : buf[s + 2];
                    rgba[d + 1] = xor ? (byte)0 : buf[s + 1];
                    rgba[d + 2] = xor ? (byte)0 : buf[s];
                    rgba[d + 3] = 255;
                }
                break;

            case Monochrome:
                // AND mask then XOR mask, 1 bit per pixel. AND=1,XOR=0 transparent;
                // 0,0 black; 0,1 white; 1,1 inverts the screen (the text cursor).
                for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                {
                    int bit = 0x80 >> (x & 7);
                    bool and = (buf[y * pitch + x / 8] & bit) != 0;
                    bool xor = (buf[(y + h) * pitch + x / 8] & bit) != 0;
                    int d = (y * w + x) * 4;
                    if (and && !xor) continue;
                    byte v = !and && xor ? (byte)255 : (byte)0;
                    rgba[d] = v; rgba[d + 1] = v; rgba[d + 2] = v; rgba[d + 3] = 255;
                }
                Outline(rgba, w, h);
                break;

            default:
                return null;
        }
        return new CursorShape(w, h, info.HotSpot.X, info.HotSpot.Y, rgba);
    }

    /// <summary>
    /// The text cursor inverts what is under it, which a phone cannot do: drawn
    /// black it vanishes on dark windows. A white outline keeps it visible on both.
    /// </summary>
    private static void Outline(byte[] rgba, int w, int h)
    {
        var copy = (byte[])rgba.Clone();
        for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            int d = (y * w + x) * 4;
            if (copy[d + 3] != 0) continue;
            bool near = false;
            for (int dy = -1; dy <= 1 && !near; dy++)
            for (int dx = -1; dx <= 1 && !near; dx++)
            {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int n = (ny * w + nx) * 4;
                near = copy[n + 3] != 0 && copy[n] == 0;
            }
            if (near) { rgba[d] = 255; rgba[d + 1] = 255; rgba[d + 2] = 255; rgba[d + 3] = 255; }
        }
    }
}
