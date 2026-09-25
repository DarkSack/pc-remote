#:package System.Drawing.Common@10.0.0
#:property TargetFramework=net10.0-windows
#:property PublishAot=false

// Renders the PC Remote mark (branding/logo.svg) to the agent's .ico and to PNG previews.
//   dotnet run branding/render.cs
// Geometry is the SVG's, in its 108×108 viewBox. Small sizes zoom into the mark
// and drop the spokes so the ring and core stay readable at 16 px.

using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

var root = Path.GetFullPath(Path.Combine(AppContext.GetData("EntryPointFileDirectoryPath") as string ?? ".", ".."));
var bg = ColorTranslator.FromHtml("#10161D");
var mark = ColorTranslator.FromHtml("#57D9C3");

Bitmap Render(int size)
{
    var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
    using var g = Graphics.FromImage(bmp);
    g.SmoothingMode = SmoothingMode.AntiAlias;
    g.PixelOffsetMode = PixelOffsetMode.HighQuality;
    g.Clear(Color.Transparent);

    // Background squircle fills the icon.
    float r = size * 0.26f;
    using (var path = new GraphicsPath())
    {
        var s = size - 0.5f;
        path.AddArc(0, 0, r * 2, r * 2, 180, 90);
        path.AddArc(s - r * 2, 0, r * 2, r * 2, 270, 90);
        path.AddArc(s - r * 2, s - r * 2, r * 2, r * 2, 0, 90);
        path.AddArc(0, s - r * 2, r * 2, r * 2, 90, 90);
        path.CloseFigure();
        using var b = new SolidBrush(bg);
        g.FillPath(b, path);
    }

    // Mark: map a window of the viewBox centred on (54,54) onto the icon.
    bool small = size <= 24;
    float window = small ? 62f : size <= 48 ? 70f : 78f;
    float k = size / window;
    PointF P(float x, float y) => new((x - 54f) * k + size / 2f, (y - 54f) * k + size / 2f);

    using var pen = new Pen(mark, (small ? 5.2f : 4.5f) * k) { StartCap = LineCap.Round, EndCap = LineCap.Round };
    var c = P(54, 54);
    float R = 21f * k;
    // Arcs between the nodes (angles in degrees, clockwise from +x like SVG).
    foreach (var (from, sweep) in new[] { (-65f, 70f), (55f, 70f), (175f, 70f) })
        g.DrawArc(pen, c.X - R, c.Y - R, 2 * R, 2 * R, from, sweep);

    if (!small)
    {
        using var spoke = new Pen(mark, 3f * k) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        g.DrawLine(spoke, P(54, 43.5f), P(54, 38));
        g.DrawLine(spoke, P(63.093f, 59.25f), P(67.856f, 62));
        g.DrawLine(spoke, P(44.907f, 59.25f), P(40.144f, 62));
    }

    using var fill = new SolidBrush(mark);
    void Dot(float x, float y, float rad) { var p = P(x, y); var rr = rad * k; g.FillEllipse(fill, p.X - rr, p.Y - rr, rr * 2, rr * 2); }
    Dot(54, 54, small ? 8.5f : 7.5f);
    float node = small ? 5.2f : 4.5f;
    Dot(54, 33, node);
    Dot(72.187f, 64.5f, node);
    Dot(35.813f, 64.5f, node);
    return bmp;
}

byte[] Png(Bitmap b) { using var ms = new MemoryStream(); b.Save(ms, ImageFormat.Png); return ms.ToArray(); }

// ICO with PNG-compressed entries (Vista+).
int[] sizes = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };
var images = sizes.Select(s => { using var b = Render(s); return Png(b); }).ToArray();
var icoPath = Path.Combine(root, "agent", "src", "PcRemote.Agent", "Assets", "pcremote.ico");
Directory.CreateDirectory(Path.GetDirectoryName(icoPath)!);
using (var fs = File.Create(icoPath))
using (var w = new BinaryWriter(fs))
{
    w.Write((short)0); w.Write((short)1); w.Write((short)sizes.Length);
    int offset = 6 + 16 * sizes.Length;
    for (int i = 0; i < sizes.Length; i++)
    {
        w.Write((byte)(sizes[i] >= 256 ? 0 : sizes[i]));
        w.Write((byte)(sizes[i] >= 256 ? 0 : sizes[i]));
        w.Write((byte)0); w.Write((byte)0);
        w.Write((short)1); w.Write((short)32);
        w.Write(images[i].Length); w.Write(offset);
        offset += images[i].Length;
    }
    foreach (var img in images) w.Write(img);
}
Console.WriteLine($"Wrote {icoPath}");

foreach (var s in new[] { 32, 512 })
{
    using var b = Render(s);
    var png = Path.Combine(root, "branding", $"logo-{s}.png");
    b.Save(png, ImageFormat.Png);
    Console.WriteLine($"Wrote {png}");
}
