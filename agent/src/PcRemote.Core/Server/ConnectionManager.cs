using System.Collections.Concurrent;
using System.Net.WebSockets;

namespace PcRemote.Core.Server;

/// <summary>
/// Tracks live WebSocket connections and exposes counts for the tray UI.
/// </summary>
public sealed class ConnectionManager
{
    private readonly ConcurrentDictionary<Guid, TrackedConnection> _conns = new();

    public event EventHandler? ConnectionsChanged;

    public int ActiveCount => _conns.Count;

    public IReadOnlyCollection<TrackedConnection> Active => _conns.Values.ToArray();

    public TrackedConnection Register(WebSocket socket, string clientIp)
    {
        var conn = new TrackedConnection(Guid.NewGuid(), socket, clientIp, DateTimeOffset.UtcNow);
        _conns[conn.Id] = conn;
        ConnectionsChanged?.Invoke(this, EventArgs.Empty);
        return conn;
    }

    public void Unregister(Guid id)
    {
        if (_conns.TryRemove(id, out var conn))
        {
            conn.Dispose();
            ConnectionsChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>Closes every live connection authenticated as <paramref name="deviceId"/>.</summary>
    public async Task<int> CloseDeviceAsync(string deviceId, WebSocketCloseStatus status, string reason)
    {
        var targets = _conns.Values.Where(c => c.DeviceId == deviceId).ToArray();
        await Task.WhenAll(targets.Select(c => c.CloseAsync(status, reason))).ConfigureAwait(false);
        return targets.Length;
    }
}

/// <summary>
/// One live WebSocket. Owns the send lock: the <see cref="WebSocket"/> contract
/// only allows one <c>SendAsync</c> at a time, and a stream pushing stats while a
/// command response goes out is exactly that. The managed implementation in
/// .NET 8 happens to serialise frames internally (a stress test with 16 streams
/// and 400 responses did not break the pre-lock code), but that is an
/// implementation detail, not a promise. Every write goes through
/// <see cref="SendAsync"/>, which also gives close a clean point to wait on.
/// </summary>
public sealed class TrackedConnection : IDisposable
{
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly CancellationTokenSource _closing = new();

    public TrackedConnection(Guid id, WebSocket socket, string clientIp, DateTimeOffset connectedAt)
    {
        Id          = id;
        Socket      = socket;
        ClientIp    = clientIp;
        ConnectedAt = connectedAt;
    }

    public Guid           Id          { get; }
    public WebSocket      Socket      { get; }
    public string         ClientIp    { get; }
    public DateTimeOffset ConnectedAt { get; }

    /// <summary>Set once the connection authenticates. Null while bootstrapping.</summary>
    public volatile string? DeviceId;

    /// <summary>Cancelled when the agent closes this connection (revoke, protocol error).</summary>
    public CancellationToken Closing => _closing.Token;

    public async Task SendAsync(ReadOnlyMemory<byte> utf8Json, CancellationToken ct)
    {
        await _sendLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (Socket.State != WebSocketState.Open) return;
            await Socket.SendAsync(utf8Json, WebSocketMessageType.Text, endOfMessage: true, ct).ConfigureAwait(false);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    /// <summary>How long the peer gets to answer our close frame before the socket is torn down.</summary>
    private static readonly TimeSpan CloseHandshakeTimeout = TimeSpan.FromSeconds(2);

    /// <summary>
    /// Sends a close frame, then cancels <see cref="Closing"/> if the peer has not
    /// completed the handshake within <see cref="CloseHandshakeTimeout"/>.
    ///
    /// The cancel is delayed on purpose. Cancelling right after CloseOutputAsync
    /// aborted the connection before the frame left the TLS stream: on .NET 10 the
    /// phone saw 1006 (abnormal) instead of 4001, and 4001 is what tells the app
    /// it was revoked and must stop reconnecting. Normally the peer answers, the
    /// receive loop sees its Close and ends by itself well before the timer.
    /// </summary>
    public async Task CloseAsync(WebSocketCloseStatus status, string reason)
    {
        try
        {
            using var timeout = new CancellationTokenSource(CloseHandshakeTimeout);
            await _sendLock.WaitAsync(timeout.Token).ConfigureAwait(false);
            try
            {
                if (Socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                    await Socket.CloseOutputAsync(status, reason, timeout.Token).ConfigureAwait(false);
            }
            finally
            {
                _sendLock.Release();
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or ObjectDisposedException)
        {
            // Best effort: the cancel below tears the connection down anyway.
        }
        finally
        {
            try { _closing.CancelAfter(CloseHandshakeTimeout); } catch (ObjectDisposedException) { }
        }
    }

    // The send lock is deliberately not disposed: a stream task that is still
    // finishing could be waiting on it, and SemaphoreSlim holds no OS handle unless
    // AvailableWaitHandle is touched.
    public void Dispose() => _closing.Dispose();
}
