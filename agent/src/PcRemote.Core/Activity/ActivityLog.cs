namespace PcRemote.Core.Activity;

/// <summary>
/// Agent events for the phone's Activity timeline: sessions, pairings, alerts.
/// In memory only (the last 500): commands already persist in the audit log,
/// and these events are only interesting while they are recent.
/// </summary>
public sealed class ActivityLog
{
    private const int Capacity = 500;
    private readonly LinkedList<ActivityEvent> _events = new();
    private readonly object _lock = new();
    private readonly Dictionary<string, DateTimeOffset> _lastAlert = new();

    public ActivityLog() => Add(ActivityKinds.Agent, "Agente iniciado", Environment.MachineName);

    public void Add(string kind, string title, string? detail = null, string level = ActivityLevels.Info, string? device = null)
    {
        var e = new ActivityEvent(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), kind, title, detail, level, device);
        lock (_lock)
        {
            _events.AddFirst(e);
            while (_events.Count > Capacity) _events.RemoveLast();
        }
    }

    /// <summary>
    /// An alert at most once per <paramref name="cooldown"/> for the same key, so a
    /// PC that sits at 95% RAM does not fill the timeline.
    /// </summary>
    public void Alert(string key, string title, string? detail, TimeSpan cooldown)
    {
        var now = DateTimeOffset.UtcNow;
        lock (_lock)
        {
            if (_lastAlert.TryGetValue(key, out var last) && now - last < cooldown) return;
            _lastAlert[key] = now;
        }
        Add(ActivityKinds.Alert, title, detail, ActivityLevels.Warning);
    }

    public IReadOnlyList<ActivityEvent> Snapshot(int limit)
    {
        lock (_lock) return _events.Take(limit).ToList();
    }
}

public sealed record ActivityEvent(long Ts, string Kind, string Title, string? Detail, string Level, string? Device);

public static class ActivityKinds
{
    public const string Agent   = "agent";
    public const string Session = "session";
    public const string Pairing = "pairing";
    public const string Command = "command";
    public const string Alert   = "alert";
}

public static class ActivityLevels
{
    public const string Info    = "info";
    public const string Warning = "warning";
    public const string Error   = "error";
}
