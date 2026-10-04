using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using PcRemote.Core.Activity;
using PcRemote.Core.Router;
using PcRemote.Core.Server;
using PcRemote.Modules.Screen;
using PcRemote.Modules.Screen.Capture;
using PcRemote.Modules.Screen.Encoding;

namespace PcRemote.Tests;

public class SocketTicketTests
{
    private sealed class ManualTime : TimeProvider
    {
        public DateTimeOffset Now = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private static ClientSession Session(string id = "s1") => new()
    {
        SessionId = id, DeviceId = "d1", DeviceName = "Phone", StartedAt = DateTimeOffset.UtcNow,
    };

    [Fact]
    public void Ticket_works_once()
    {
        var tickets = new SocketTickets();
        var t = tickets.Issue(Session(), "screen");
        Assert.NotNull(tickets.Redeem(t, "screen"));
        Assert.Null(tickets.Redeem(t, "screen"));
    }

    [Fact]
    public void Ticket_is_bound_to_its_module()
    {
        var tickets = new SocketTickets();
        var t = tickets.Issue(Session(), "screen");
        Assert.Null(tickets.Redeem(t, "other"));
        // A wrong-module attempt still burns it: a ticket is never tried twice.
        Assert.Null(tickets.Redeem(t, "screen"));
    }

    [Fact]
    public void Ticket_expires()
    {
        var time = new ManualTime();
        var tickets = new SocketTickets(time);
        var t = tickets.Issue(Session(), "screen");
        time.Now += SocketTickets.Lifetime + TimeSpan.FromSeconds(1);
        Assert.Null(tickets.Redeem(t, "screen"));
    }

    [Fact]
    public void Unknown_or_empty_tickets_fail()
    {
        var tickets = new SocketTickets();
        Assert.Null(tickets.Redeem(null, "screen"));
        Assert.Null(tickets.Redeem("", "screen"));
        Assert.Null(tickets.Redeem("deadbeef", "screen"));
    }

    [Fact]
    public void Old_tickets_of_a_session_are_dropped_past_the_limit()
    {
        var tickets = new SocketTickets();
        var first = tickets.Issue(Session(), "screen");
        for (int i = 0; i < 10; i++) tickets.Issue(Session(), "screen");
        Assert.Null(tickets.Redeem(first, "screen"));
    }
}

public class Nv12Tests
{
    private static unsafe byte[] Convert(byte[] bgra, int srcW, int srcH, int scale)
    {
        var (w, h) = Nv12.OutputSize(srcW, srcH, scale);
        var dst = new byte[w * h * 3 / 2];
        fixed (byte* s = bgra) fixed (byte* d = dst) Nv12.Convert(s, srcW * 4, w, h, scale, d);
        return dst;
    }

    private static byte[] Solid(int w, int h, byte r, byte g, byte b)
    {
        var px = new byte[w * h * 4];
        for (int i = 0; i < px.Length; i += 4) { px[i] = b; px[i + 1] = g; px[i + 2] = r; px[i + 3] = 255; }
        return px;
    }

    [Theory]
    [InlineData(255, 255, 255, 235, 128, 128)]
    [InlineData(0, 0, 0, 16, 128, 128)]
    [InlineData(255, 0, 0, 63, 102, 240)]
    [InlineData(0, 0, 255, 32, 240, 118)]
    public void Solid_colours_match_bt709_limited(byte r, byte g, byte b, int y, int u, int v)
    {
        var nv12 = Convert(Solid(4, 4, r, g, b), 4, 4, 1);
        Assert.All(nv12.Take(16), luma => Assert.InRange(luma, y - 1, y + 1));
        Assert.InRange(nv12[16], u - 1, u + 1);
        Assert.InRange(nv12[17], v - 1, v + 1);
    }

    [Fact]
    public void Half_size_averages_blocks()
    {
        // 4x4 source: left half white, right half black → 2x2 output, columns 235 / 16.
        var src = Solid(4, 4, 0, 0, 0);
        for (int y = 0; y < 4; y++)
        for (int x = 0; x < 2; x++)
        {
            var i = (y * 4 + x) * 4;
            src[i] = src[i + 1] = src[i + 2] = 255;
        }
        var nv12 = Convert(src, 4, 4, 2);
        Assert.Equal(new byte[] { 235, 16, 235, 16 }, nv12.Take(4).ToArray());
    }

    [Fact]
    public void Output_size_is_even()
    {
        Assert.Equal((1366, 768), Nv12.OutputSize(1366, 768, 1));
        Assert.Equal((682, 384), Nv12.OutputSize(1366, 768, 2));
        Assert.Equal((1920, 1080), Nv12.OutputSize(3840, 2160, 2));
        Assert.Equal((1280, 1022), Nv12.OutputSize(1281, 1023, 1));
    }
}

public class AnnexBTests
{
    private static byte[] Nal(int type, params byte[] body) => [0, 0, 0, 1, (byte)(0x60 | type), .. body];

    private static int[] Types(byte[] au) => AnnexB.Units(au).Select(u => au[u.Start] & 0x1F).ToArray();

    [Fact]
    public void Drops_delimiters_and_filler()
    {
        byte[] au = [.. Nal(AnnexB.NalAud, 0xF0), .. Nal(1, 0xAA, 0xBB), .. Nal(AnnexB.NalFiller, 0xFF, 0xFF, 0x80)];
        byte[]? sets = null;
        var outAu = AnnexB.Prepare(au, ref sets, out var key);
        Assert.False(key);
        Assert.Equal([1], Types(outAu));
    }

    [Fact]
    public void Key_frames_always_carry_parameter_sets()
    {
        byte[]? sets = null;
        var first = AnnexB.Prepare([.. Nal(AnnexB.NalSps, 1, 2), .. Nal(AnnexB.NalPps, 3), .. Nal(AnnexB.NalIdr, 4)], ref sets, out var key1);
        Assert.True(key1);
        Assert.Equal([7, 8, 5], Types(first));

        // A later IDR without SPS/PPS gets them prepended.
        var later = AnnexB.Prepare(Nal(AnnexB.NalIdr, 9), ref sets, out var key2);
        Assert.True(key2);
        Assert.Equal([7, 8, 5], Types(later));

        // P-frames stay as they are.
        Assert.Equal([1], Types(AnnexB.Prepare(Nal(1, 5), ref sets, out _)));
    }

    [Fact]
    public void Splits_three_and_four_byte_start_codes()
    {
        byte[] au = [0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3];
        Assert.Equal([7, 8, 5], Types(au));
        var units = AnnexB.Units(au).ToArray();
        Assert.Equal(2, units[0].End - units[0].Start); // the 4-byte code's leading zero is not payload
    }
}

public class CursorShapeTests
{
    [Fact]
    public void Monochrome_text_cursor_gets_a_visible_outline()
    {
        // 8x2 monochrome: AND mask row(s) then XOR mask row(s); pitch 1 byte.
        // Pixel 3 of row 0 inverts (AND=1, XOR=1); everything else is transparent.
        var buf = new byte[] { 0xFF, 0xFF, 0x10, 0x00 };
        var info = new Vortice.DXGI.OutduplPointerShapeInfo { Type = 1, Width = 8, Height = 4, Pitch = 1 };
        var shape = CursorShapes.ToRgba(buf, info)!;
        Assert.Equal(8, shape.Width);
        Assert.Equal(2, shape.Height);
        Assert.Equal(255, shape.Rgba[3 * 4 + 3]);           // the inverting pixel is drawn (black)
        Assert.Equal(0, shape.Rgba[3 * 4]);
        Assert.Equal(255, shape.Rgba[2 * 4]);                 // neighbour: white outline
        Assert.Equal(0, shape.Rgba[7 * 4 + 3]);               // far pixel stays transparent
    }

    [Fact]
    public void Color_cursor_is_converted_to_rgba()
    {
        var buf = new byte[] { 10, 20, 30, 200 }; // BGRA
        var info = new Vortice.DXGI.OutduplPointerShapeInfo { Type = 2, Width = 1, Height = 1, Pitch = 4 };
        Assert.Equal(new byte[] { 30, 20, 10, 200 }, CursorShapes.ToRgba(buf, info)!.Rgba);
    }
}

/// <summary>
/// The real thing: captures this PC's screen, encodes it and reads it back as the
/// phone would. Needs a desktop session (skipped when there is no monitor).
/// </summary>
public class ScreenStreamTests
{
    [Fact]
    public async Task Streams_config_and_a_decodable_key_frame()
    {
        if (Displays.List().Count == 0) return;

        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port);
        using var serverTcp = await listener.AcceptTcpClientAsync();

        using var serverWs = WebSocket.CreateFromStream(serverTcp.GetStream(), new WebSocketCreationOptions { IsServer = true });
        using var phone = WebSocket.CreateFromStream(client.GetStream(), new WebSocketCreationOptions { IsServer = false });

        var session = new ClientSession { SessionId = "s", DeviceId = "d", DeviceName = "Test phone", StartedAt = DateTimeOffset.UtcNow };
        using var hello = JsonDocument.Parse("""{"ticket":"x","quality":"balanced"}""");
        var tracked = new TrackedConnection(Guid.NewGuid(), serverWs, "127.0.0.1", DateTimeOffset.UtcNow) { Purpose = "screen" };
        var socket = new ModuleSocket(tracked, session, hello.RootElement.Clone());

        var activity = new ActivityLog();
        var module = new ScreenModule(new SocketTickets(), activity, NullLogger<ScreenModule>.Instance);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var run = module.RunSocketAsync(socket, cts.Token);

        JsonElement? config = null;
        byte[]? keyFrame = null;
        bool gotPong = false;
        var buffer = new byte[4 * 1024 * 1024];

        await phone.SendAsync(Encoding.UTF8.GetBytes("""{"t":"ping","ts":42}"""), WebSocketMessageType.Text, true, cts.Token);

        while ((config is null || keyFrame is null || !gotPong) && !cts.IsCancellationRequested)
        {
            var (type, data) = await ReceiveAsync(phone, buffer, cts.Token);
            if (type == WebSocketMessageType.Text)
            {
                using var doc = JsonDocument.Parse(data);
                var kind = doc.RootElement.GetProperty("kind").GetString();
                Assert.NotEqual("error", kind);
                if (kind == "config") config = doc.RootElement.Clone();
                if (kind == "pong") gotPong = doc.RootElement.GetProperty("ts").GetInt64() == 42;
            }
            else if (data[0] == 0x01 && (data[1] & 1) == 1)
            {
                keyFrame = data;
            }
        }

        Assert.NotNull(config);
        Assert.Equal("h264", config.Value.GetProperty("codec").GetString());
        Assert.True(config.Value.GetProperty("width").GetInt32() > 0);
        Assert.True(gotPong);
        Assert.NotNull(keyFrame);

        // The first key frame must start a decoder on its own: SPS, PPS, IDR.
        var au = keyFrame![10..];
        var types = AnnexB.Units(au).Select(u => au[u.Start] & 0x1F).ToList();
        Assert.Contains(AnnexB.NalSps, types);
        Assert.Contains(AnnexB.NalPps, types);
        Assert.Contains(AnnexB.NalIdr, types);
        Assert.DoesNotContain(AnnexB.NalFiller, types);

        await phone.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None);
        await run.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Contains(activity.Recent(10), e => e.Type == "screen" && e.Title.Contains("dejó de ver"));
    }

    private static async Task<(WebSocketMessageType, byte[])> ReceiveAsync(WebSocket ws, byte[] buffer, CancellationToken ct)
    {
        int count = 0;
        ValueWebSocketReceiveResult r;
        do
        {
            r = await ws.ReceiveAsync(buffer.AsMemory(count), ct);
            count += r.Count;
        } while (!r.EndOfMessage);
        return (r.MessageType, buffer[..count]);
    }
}
