using System.Collections.Concurrent;
using System.Security.Cryptography;
using PcRemote.Core.Router;

namespace PcRemote.Core.Server;

/// <summary>
/// One-time passes for module sockets (<see cref="ISocketModule"/>). Issued over
/// the authenticated protocol socket, so a module socket inherits that session
/// without a second key exchange. A ticket works once, for one module, within
/// <see cref="Lifetime"/>.
/// </summary>
public sealed class SocketTickets
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromSeconds(30);

    /// <summary>Outstanding tickets per session; a looping client cannot fill memory.</summary>
    private const int MaxPerSession = 8;

    private readonly ConcurrentDictionary<string, Ticket> _tickets = new();
    private readonly TimeProvider _time;

    public SocketTickets(TimeProvider? time = null) => _time = time ?? TimeProvider.System;

    public sealed record Ticket(string Value, string SocketName, ClientSession Session, DateTimeOffset ExpiresAt);

    public string Issue(ClientSession session, string socketName)
    {
        Purge();
        var mine = _tickets.Values.Where(t => t.Session.SessionId == session.SessionId)
                                  .OrderBy(t => t.ExpiresAt).ToList();
        foreach (var old in mine.Take(Math.Max(0, mine.Count - MaxPerSession + 1)))
            _tickets.TryRemove(old.Value, out _);

        var value = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        _tickets[value] = new Ticket(value, socketName, session, _time.GetUtcNow() + Lifetime);
        return value;
    }

    /// <summary>The ticket, consumed; null if unknown, expired or meant for another module.</summary>
    public Ticket? Redeem(string? value, string socketName)
    {
        if (string.IsNullOrEmpty(value) || !_tickets.TryRemove(value, out var ticket)) return null;
        if (ticket.ExpiresAt < _time.GetUtcNow()) return null;
        return ticket.SocketName == socketName ? ticket : null;
    }

    private void Purge()
    {
        var now = _time.GetUtcNow();
        foreach (var t in _tickets.Values)
            if (t.ExpiresAt < now) _tickets.TryRemove(t.Value, out _);
    }
}
