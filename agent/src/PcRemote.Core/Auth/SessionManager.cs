using System.Collections.Concurrent;
using PcRemote.Core.Router;

namespace PcRemote.Core.Auth;

/// <summary>
/// In-memory active sessions. A session ties a WebSocket connection to a paired device.
/// Sessions expire after TTL or when the socket closes.
/// </summary>
public sealed class SessionManager
{
    private readonly ConcurrentDictionary<string, ClientSession> _sessions = new();

    public ClientSession Create(string deviceId, string deviceName)
    {
        var session = new ClientSession
        {
            SessionId  = Guid.NewGuid().ToString("N"),
            DeviceId   = deviceId,
            DeviceName = deviceName,
            StartedAt  = DateTimeOffset.UtcNow,
        };
        _sessions[session.SessionId] = session;
        return session;
    }

    public ClientSession? Get(string sessionId) =>
        _sessions.TryGetValue(sessionId, out var s) ? s : null;

    public void End(string sessionId) => _sessions.TryRemove(sessionId, out _);

    /// <summary>Kills all active sessions of a device (used on revoke).</summary>
    public IReadOnlyList<ClientSession> EndAllForDevice(string deviceId)
    {
        var ended = new List<ClientSession>();
        foreach (var kvp in _sessions)
        {
            if (kvp.Value.DeviceId == deviceId)
            {
                _sessions.TryRemove(kvp.Key, out _);
                ended.Add(kvp.Value);
            }
        }
        return ended;
    }

    public IReadOnlyCollection<ClientSession> All => _sessions.Values.ToArray();
}
