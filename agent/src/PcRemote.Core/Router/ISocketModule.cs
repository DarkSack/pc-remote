using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using PcRemote.Core.Server;

namespace PcRemote.Core.Router;

/// <summary>
/// A module that needs a socket of its own, next to the protocol socket. The
/// remote screen pushes megabits of video: on the protocol socket every command
/// response would queue behind a frame.
///
/// Flow: the phone asks the module for a ticket over the authenticated protocol
/// socket (see <see cref="SocketTickets"/>), opens <c>wss://pc:port/socket/{name}</c>
/// and sends <c>{"ticket":"…", …}</c> as its first message. The server checks the
/// ticket, the session and the feature switch, then hands the socket over.
/// </summary>
public interface ISocketModule
{
    /// <summary>Path segment: <c>/socket/{SocketName}</c>.</summary>
    string SocketName { get; }

    /// <summary>Runs until the socket closes or <paramref name="ct"/> is cancelled (session ended, revoke).</summary>
    Task RunSocketAsync(ModuleSocket socket, CancellationToken ct);
}

/// <summary>An authenticated module socket. Sends are serialised; reads belong to the module.</summary>
public sealed class ModuleSocket
{
    private readonly TrackedConnection _conn;

    internal ModuleSocket(TrackedConnection conn, ClientSession session, JsonElement hello)
    {
        _conn   = conn;
        Session = session;
        Hello   = hello;
    }

    public ClientSession Session { get; }

    /// <summary>The first message the phone sent (ticket plus the module's own options).</summary>
    public JsonElement Hello { get; }

    public string ClientIp => _conn.ClientIp;

    public bool IsOpen => _conn.Socket.State == WebSocketState.Open;

    public Task SendTextAsync(ReadOnlyMemory<byte> utf8, CancellationToken ct) => _conn.SendAsync(utf8, ct);

    public Task SendJsonAsync(object payload, CancellationToken ct) =>
        _conn.SendAsync(JsonSerializer.SerializeToUtf8Bytes(payload, SocketJson.Options), ct);

    public Task SendBinaryAsync(ReadOnlyMemory<byte> data, CancellationToken ct) => _conn.SendBinaryAsync(data, ct);

    public Task CloseAsync(string reason) => _conn.CloseAsync(WebSocketCloseStatus.NormalClosure, reason);

    /// <summary>Next text message, or null when the socket closed. Binary frames and oversize messages close it.</summary>
    public async Task<string?> ReceiveTextAsync(int maxBytes, CancellationToken ct)
    {
        var buffer = new byte[4096];
        using var ms = new MemoryStream();
        while (true)
        {
            var result = await _conn.Socket.ReceiveAsync(buffer, ct).ConfigureAwait(false);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                await _conn.CloseAsync(WebSocketCloseStatus.NormalClosure, "").ConfigureAwait(false);
                return null;
            }
            if (result.MessageType != WebSocketMessageType.Text || ms.Length + result.Count > maxBytes)
            {
                await _conn.CloseAsync(WebSocketCloseStatus.InvalidPayloadData, "Unexpected message").ConfigureAwait(false);
                return null;
            }
            ms.Write(buffer, 0, result.Count);
            if (result.EndOfMessage) return Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length);
        }
    }
}

internal static class SocketJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };
}
