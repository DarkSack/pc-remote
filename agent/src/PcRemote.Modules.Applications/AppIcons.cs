using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace PcRemote.Modules.Applications;

// ══════════════════════════════════════════════════════════════
// Iconos de apps como PNG de 64×64 con transparencia.
//
// Una sola vía para las tres fuentes: IShellItemImageFactory sobre el
// "parsing name" del elemento. Para un .lnk o un .exe es su ruta; para una
// app de la Store es "shell:AppsFolder\<AppID>". Es lo mismo que usa el
// Explorador, así que el icono coincide con el del menú Inicio.
//
// Los objetos del Shell son COM de apartamento STA: todo pasa por un único
// hilo STA dedicado con una cola, en vez de crear un hilo por icono.
// Los resultados (también "no tiene icono") se guardan en memoria.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal static class AppIcons
{
    private const int Size = 64;

    private static readonly ConcurrentDictionary<string, byte[]?> Cache = new();
    private static readonly BlockingCollection<(string Path, TaskCompletionSource<byte[]?> Done)> Queue = new();
    private static readonly Lazy<Thread> Worker = new(() =>
    {
        var t = new Thread(Loop) { IsBackground = true, Name = "AppIcons STA" };
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        return t;
    });

    public static async Task<byte[]?> GetPngAsync(AppEntry app)
    {
        if (Cache.TryGetValue(app.Id, out var cached)) return cached;

        _ = Worker.Value;
        var done = new TaskCompletionSource<byte[]?>(TaskCreationOptions.RunContinuationsAsynchronously);
        Queue.Add((ParsingName(app), done));
        var png = await done.Task.ConfigureAwait(false);
        Cache[app.Id] = png;
        return png;
    }

    /// <summary>Drops cached icons of apps that no longer exist.</summary>
    public static void Forget(Func<string, bool> gone)
    {
        foreach (var id in Cache.Keys)
            if (gone(id)) Cache.TryRemove(id, out _);
    }

    private static string ParsingName(AppEntry app) =>
        app.Source == "uwp" ? app.Launch /* shell:AppsFolder\<AppID> */ : app.Launch;

    private static void Loop()
    {
        foreach (var (path, done) in Queue.GetConsumingEnumerable())
        {
            try { done.SetResult(Extract(path)); }
            catch { done.SetResult(null); }
        }
    }

    private static byte[]? Extract(string parsingName)
    {
        var iid = typeof(IShellItemImageFactory).GUID;
        if (SHCreateItemFromParsingName(parsingName, IntPtr.Zero, ref iid, out var factory) != 0 || factory is null)
            return null;

        IntPtr hbitmap = IntPtr.Zero;
        try
        {
            var hr = factory.GetImage(new NativeSize { cx = Size, cy = Size },
                SIIGBF.BiggerSizeOk | SIIGBF.IconOnly, out hbitmap);
            if (hr != 0 || hbitmap == IntPtr.Zero) return null;
            return ToPng(hbitmap);
        }
        finally
        {
            if (hbitmap != IntPtr.Zero) DeleteObject(hbitmap);
            Marshal.ReleaseComObject(factory);
        }
    }

    /// <summary>
    /// The shell hands back a 32-bpp DIB section with PREMULTIPLIED alpha, and
    /// usually bottom-up. Image.FromHbitmap ignores both: the first version of this
    /// produced near-empty images (verified by looking at the PNG, not just its
    /// header). So the raw pixels are read from the DIB and written into a
    /// Format32bppPArgb bitmap, flipping rows when the DIB is bottom-up; GDI+ then
    /// un-premultiplies when encoding the PNG.
    /// </summary>
    private static byte[]? ToPng(IntPtr hbitmap)
    {
        if (GetObject(hbitmap, Marshal.SizeOf<DIBSECTION>(), out var ds) == 0) return null;
        int w = ds.dsBm.bmWidth, h = Math.Abs(ds.dsBm.bmHeight);
        if (ds.dsBm.bmBits == IntPtr.Zero || ds.dsBm.bmBitsPixel != 32 || w <= 0 || h <= 0) return null;

        int stride = ds.dsBm.bmWidthBytes;
        var pixels = new byte[stride * h];
        Marshal.Copy(ds.dsBm.bmBits, pixels, 0, pixels.Length);
        bool bottomUp = ds.dsBmih.biHeight > 0;

        // Old-style icons carry no alpha at all; treated as premultiplied they would vanish.
        var hasAlpha = false;
        for (int i = 3; i < pixels.Length; i += 4)
            if (pixels[i] != 0) { hasAlpha = true; break; }
        if (!hasAlpha)
            for (int i = 3; i < pixels.Length; i += 4) pixels[i] = 255;

        using var bmp = new Bitmap(w, h, PixelFormat.Format32bppPArgb);
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppPArgb);
        try
        {
            for (int y = 0; y < h; y++)
            {
                int srcRow = bottomUp ? h - 1 - y : y;
                Marshal.Copy(pixels, srcRow * stride, data.Scan0 + y * data.Stride, Math.Min(stride, data.Stride));
            }
        }
        finally
        {
            bmp.UnlockBits(data);
        }

        using var ms = new MemoryStream();
        bmp.Save(ms, ImageFormat.Png);
        return ms.ToArray();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAP
    {
        public int bmType, bmWidth, bmHeight, bmWidthBytes;
        public ushort bmPlanes, bmBitsPixel;
        public IntPtr bmBits;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFOHEADER
    {
        public uint biSize;
        public int biWidth, biHeight;
        public ushort biPlanes, biBitCount;
        public uint biCompression, biSizeImage;
        public int biXPelsPerMeter, biYPelsPerMeter;
        public uint biClrUsed, biClrImportant;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DIBSECTION
    {
        public BITMAP dsBm;
        public BITMAPINFOHEADER dsBmih;
        public uint dsBitfields0, dsBitfields1, dsBitfields2;
        public IntPtr dshSection;
        public uint dsOffset;
    }

    [DllImport("gdi32.dll")]
    private static extern int GetObject(IntPtr hgdiobj, int cbBuffer, out DIBSECTION lpvObject);

    // ── COM / Win32 ──────────────────────────────────────────

    [Flags]
    private enum SIIGBF
    {
        BiggerSizeOk = 0x1,
        IconOnly     = 0x4,
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeSize { public int cx; public int cy; }

    [ComImport]
    [Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemImageFactory
    {
        [PreserveSig]
        int GetImage(NativeSize size, SIIGBF flags, out IntPtr phbm);
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHCreateItemFromParsingName(
        string pszPath, IntPtr pbc, ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory? ppv);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr hObject);
}
