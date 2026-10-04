namespace PcRemote.Modules.Screen.Encoding;

/// <summary>
/// BGRA (what DXGI captures) to NV12 (what H.264 encoders take): BT.709,
/// limited range, as the encoder's input type declares. Optionally halves the
/// size (box filter) for screens wider than the phone could ever show.
/// About 2 ms for 1080p on a few cores.
/// </summary>
internal static class Nv12
{
    /// <summary>Output size for a source size and scale (1 or 2): even, as 4:2:0 needs.</summary>
    public static (int Width, int Height) OutputSize(int srcWidth, int srcHeight, int scale) =>
        ((srcWidth / scale) & ~1, (srcHeight / scale) & ~1);

    public static unsafe void Convert(byte* src, int srcPitch, int width, int height, int scale, byte* dst)
    {
        if (scale == 1) Convert1(src, srcPitch, width, height, dst);
        else Convert2(src, srcPitch, width, height, dst);
    }

    // Integer BT.709 limited range: Y = 16 + (47R + 157G + 16B) / 256,
    // U = 128 + (-26R - 87G + 112B) / 256, V = 128 + (112R - 102G - 10B) / 256.
    private static byte Y(int r, int g, int b) => (byte)(((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);

    /// <summary>U and V from the SUM of four pixels.</summary>
    private static byte U4(int r, int g, int b) => (byte)(((-26 * r - 87 * g + 112 * b + 512) >> 10) + 128);
    private static byte V4(int r, int g, int b) => (byte)(((112 * r - 102 * g - 10 * b + 512) >> 10) + 128);

    private static unsafe void Convert1(byte* src, int pitch, int width, int height, byte* dst)
    {
        var s = (nint)src;
        var d = (nint)dst;
        Parallel.For(0, height / 2, row =>
        {
            byte* s0 = (byte*)s + row * 2 * pitch;
            byte* s1 = s0 + pitch;
            byte* y0 = (byte*)d + row * 2 * width;
            byte* y1 = y0 + width;
            byte* uv = (byte*)d + width * height + row * width;
            for (int x = 0; x < width; x += 2)
            {
                int b00 = s0[0], g00 = s0[1], r00 = s0[2];
                int b01 = s0[4], g01 = s0[5], r01 = s0[6];
                int b10 = s1[0], g10 = s1[1], r10 = s1[2];
                int b11 = s1[4], g11 = s1[5], r11 = s1[6];
                y0[0] = Y(r00, g00, b00);
                y0[1] = Y(r01, g01, b01);
                y1[0] = Y(r10, g10, b10);
                y1[1] = Y(r11, g11, b11);
                int r = r00 + r01 + r10 + r11, g = g00 + g01 + g10 + g11, b = b00 + b01 + b10 + b11;
                uv[0] = U4(r, g, b);
                uv[1] = V4(r, g, b);
                s0 += 8; s1 += 8; y0 += 2; y1 += 2; uv += 2;
            }
        });
    }

    /// <summary>Half size: every output pixel is the mean of a 2×2 source block.</summary>
    private static unsafe void Convert2(byte* src, int pitch, int width, int height, byte* dst)
    {
        var s = (nint)src;
        var d = (nint)dst;
        Parallel.For(0, height / 2, row =>
        {
            byte* a = (byte*)s + row * 4 * pitch; // four source rows per output row pair
            byte* b = a + pitch;
            byte* c = b + pitch;
            byte* e = c + pitch;
            byte* y0 = (byte*)d + row * 2 * width;
            byte* y1 = y0 + width;
            byte* uv = (byte*)d + width * height + row * width;
            for (int x = 0; x < width; x += 2)
            {
                // Output (0,0) from rows a,b cols 0-1; (0,1) cols 2-3; (1,x) from rows c,e.
                int B00 = (a[0] + a[4] + b[0] + b[4] + 2) >> 2, G00 = (a[1] + a[5] + b[1] + b[5] + 2) >> 2, R00 = (a[2] + a[6] + b[2] + b[6] + 2) >> 2;
                int B01 = (a[8] + a[12] + b[8] + b[12] + 2) >> 2, G01 = (a[9] + a[13] + b[9] + b[13] + 2) >> 2, R01 = (a[10] + a[14] + b[10] + b[14] + 2) >> 2;
                int B10 = (c[0] + c[4] + e[0] + e[4] + 2) >> 2, G10 = (c[1] + c[5] + e[1] + e[5] + 2) >> 2, R10 = (c[2] + c[6] + e[2] + e[6] + 2) >> 2;
                int B11 = (c[8] + c[12] + e[8] + e[12] + 2) >> 2, G11 = (c[9] + c[13] + e[9] + e[13] + 2) >> 2, R11 = (c[10] + c[14] + e[10] + e[14] + 2) >> 2;
                y0[0] = Y(R00, G00, B00);
                y0[1] = Y(R01, G01, B01);
                y1[0] = Y(R10, G10, B10);
                y1[1] = Y(R11, G11, B11);
                int r = R00 + R01 + R10 + R11, g = G00 + G01 + G10 + G11, bl = B00 + B01 + B10 + B11;
                uv[0] = U4(r, g, bl);
                uv[1] = V4(r, g, bl);
                a += 16; b += 16; c += 16; e += 16; y0 += 2; y1 += 2; uv += 2;
            }
        });
    }
}

/// <summary>Annex B clean-up between the encoder and the phone.</summary>
internal static class AnnexB
{
    public const int NalIdr = 5, NalSps = 7, NalPps = 8, NalAud = 9, NalFiller = 12;

    /// <summary>
    /// Drops access unit delimiters and filler data (bytes the phone does not need),
    /// reports whether the unit is a key frame and remembers SPS/PPS so every key
    /// frame carries them: a decoder the phone recreates (rotation, back from the
    /// background) starts from any key frame, not only the first.
    /// </summary>
    public static byte[] Prepare(byte[] au, ref byte[]? parameterSets, out bool keyFrame)
    {
        keyFrame = false;
        bool hasSps = false;
        using var outStream = new MemoryStream(au.Length + 64);
        using var sets = new MemoryStream();
        foreach (var (start, end) in Units(au))
        {
            var type = au[start] & 0x1F;
            if (type is NalAud or NalFiller) continue;
            if (type == NalIdr) keyFrame = true;
            if (type is NalSps or NalPps)
            {
                hasSps |= type == NalSps;
                sets.Write(StartCode);
                sets.Write(au, start, end - start);
            }
            outStream.Write(StartCode);
            outStream.Write(au, start, end - start);
        }
        if (hasSps) parameterSets = sets.ToArray();
        if (keyFrame && !hasSps && parameterSets is not null)
        {
            var body = outStream.ToArray();
            var joined = new byte[parameterSets.Length + body.Length];
            parameterSets.CopyTo(joined, 0);
            body.CopyTo(joined, parameterSets.Length);
            return joined;
        }
        return outStream.ToArray();
    }

    private static readonly byte[] StartCode = [0, 0, 0, 1];

    /// <summary>NAL payload ranges (after the start code) in an Annex B buffer.</summary>
    public static IEnumerable<(int Start, int End)> Units(byte[] data)
    {
        int i = FindStart(data, 0, out var len);
        while (i >= 0)
        {
            var payload = i + len;
            var next = FindStart(data, payload, out var nextLen);
            var end = next < 0 ? data.Length : next;
            // A 4-byte start code looks like a 3-byte one after a trailing zero.
            while (end > payload && data[end - 1] == 0 && next >= 0) end--;
            if (end > payload) yield return (payload, end);
            i = next;
            len = nextLen;
        }
    }

    private static int FindStart(byte[] d, int from, out int length)
    {
        for (int i = from; i + 2 < d.Length; i++)
        {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1)
            {
                length = 3;
                return i;
            }
        }
        length = 0;
        return -1;
    }
}
