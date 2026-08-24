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
        if (_conns.TryRemove(id, out _))
            ConnectionsChanged?.Invoke(this, EventArgs.Empty);
    }
}

public sealed record TrackedConnection(
    Guid            Id,
    WebSocket       Socket,
    string          ClientIp,
    DateTimeOffset  ConnectedAt);
