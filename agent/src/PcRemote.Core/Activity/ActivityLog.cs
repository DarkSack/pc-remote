namespace PcRemote.Core.Activity;

/// <summary>One entry of the app's "Actividad" timeline.</summary>
/// <param name="Type">connection, power, app, process, plugin, terminal, clipboard, files, alert, agent.</param>
/// <param name="Severity">info, success, warning, error.</param>
public sealed record ActivityEvent(long Id, long Ts, string Type, string Title, string? Detail, string Severity);

/// <summary>
/// Recent events of the PC, in memory (the last <see cref="Capacity"/>). Modules
/// take it by constructor injection and call <see cref="Add"/>; the app reads
/// it with <c>activity.list</c> / <c>activity.watch</c>.
///
/// Not persisted on purpose: it is a "what just happened" view, and the audit
/// log in SQLite already keeps the durable record of commands.
/// </summary>
public sealed class ActivityLog
{
    public const int Capacity = 300;

    private readonly LinkedList<ActivityEvent> _events = new();
    private readonly object _lock = new();
    private long _nextId;

    public event Action<ActivityEvent>? Added;

    public ActivityEvent Add(string type, string title, string? detail = null, string severity = "info")
    {
        ActivityEvent ev;
        lock (_lock)
        {
            ev = new ActivityEvent(++_nextId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), type, title, detail, severity);
            _events.AddFirst(ev);
            while (_events.Count > Capacity) _events.RemoveLast();
        }
        Added?.Invoke(ev);
        return ev;
    }

    /// <summary>Newest first.</summary>
    public IReadOnlyList<ActivityEvent> Recent(int limit)
    {
        lock (_lock) return _events.Take(Math.Clamp(limit, 1, Capacity)).ToArray();
    }
}
