using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.Versioning;

namespace PcRemote.Modules.Clipboard;

/// <summary>Image helpers: thumbnails for the list, and a size cap for what is kept and sent.</summary>
[SupportedOSPlatform("windows")]
internal static class ClipImages
{
    /// <summary>Longest side of list thumbnails (JPEG, ~10–25 KB each).</summary>
    public const int ThumbSize = 320;

    /// <summary>Screenshots of several 4K monitors are huge; beyond this side they are scaled down.</summary>
    public const int MaxSide = 4096;

    /// <summary>Largest PNG kept as is; bigger ones are scaled down to fit.</summary>
    public const int MaxPngBytes = 12 * 1024 * 1024;

    public static (byte[] png, int w, int h) Normalize(byte[] png, int w, int h)
    {
        if (w <= MaxSide && h <= MaxSide && png.Length <= MaxPngBytes) return (png, w, h);
        var side = Math.Min(MaxSide, Math.Max(w, h));
        // Shrink more when the file is still too heavy (photos compress badly as PNG).
        while (true)
        {
            var scaled = Scale(png, side, out var nw, out var nh);
            if (scaled.Length <= MaxPngBytes || side <= 1024) return (scaled, nw, nh);
            side = side * 3 / 4;
        }
    }

    /// <summary>
    /// JPEG over white: a list of 50 PNG thumbnails of photos weighed several MB,
    /// JPEG keeps it to about a megabyte. Transparent areas show as white.
    /// </summary>
    /// <summary>PNG scaled so its longest side is at most <paramref name="maxSide"/>.</summary>
    public static byte[] ScalePng(byte[] encoded, int maxSide) => Scale(encoded, maxSide, out _, out _);

    public static byte[] Thumbnail(byte[] encoded, int maxSide) => Scale(encoded, maxSide, out _, out _, jpeg: true);

    private static byte[] Scale(byte[] encoded, int maxSide, out int width, out int height, bool jpeg = false)
    {
        using var src = Image.FromStream(new MemoryStream(encoded));
        var scale = Math.Min(1.0, (double)maxSide / Math.Max(src.Width, src.Height));
        width = Math.Max(1, (int)Math.Round(src.Width * scale));
        height = Math.Max(1, (int)Math.Round(src.Height * scale));
        using var dst = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(dst))
        {
            if (jpeg) g.Clear(Color.White);
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.CompositingQuality = CompositingQuality.HighQuality;
            g.DrawImage(src, 0, 0, width, height);
        }
        using var ms = new MemoryStream();
        if (jpeg)
        {
            var codec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
            using var args = new EncoderParameters(1);
            args.Param[0] = new EncoderParameter(Encoder.Quality, 80L);
            dst.Save(ms, codec, args);
        }
        else
        {
            dst.Save(ms, ImageFormat.Png);
        }
        return ms.ToArray();
    }
}
