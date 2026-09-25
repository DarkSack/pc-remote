namespace PcRemote.Modules.Clipboard;

/// <summary>
/// What was copied on the PC, newest first. In memory only: nothing is written
/// to disk, and restarting the agent starts an empty history.
///
/// Copying something already in the history moves it to the top instead of
/// adding a duplicate. Oldest entries go first when the history holds more than
/// <see cref="MaxItems"/> entries or <see cref="MaxBytes"/> of content.
/// </summary>
internal sealed class ClipboardHistory
{
    public const int MaxItems = 200;
    public const long MaxBytes = 256L * 1024 * 1024;

    private readonly LinkedList<ClipEntry> _items = new();
    private readonly object _lock = new();
    private long _nextId = 1;
    private long _bytes;

    /// <summary>Bumps on every change, so the phone knows when to reload the list.</summary>
    public long Version { get; private set; }

    public void Add(ClipEntry entry)
    {
        lock (_lock)
        {
            var existing = _items.FirstOrDefault(e => e.Hash == entry.Hash);
            if (existing is not null)
            {
                // Same content again: move it to the top, keep its id (the phone may show it).
                _items.Remove(existing);
                existing.Ts = Now();
                _items.AddFirst(existing);
            }
            else
            {
                entry.Id = _nextId++;
                entry.Ts = Now();
                _items.AddFirst(entry);
                _bytes += entry.SizeBytes;
                while (_items.Count > MaxItems || (_bytes > MaxBytes && _items.Count > 1))
                {
                    _bytes -= _items.Last!.Value.SizeBytes;
                    _items.RemoveLast();
                }
            }
            Version++;
        }
    }

    public IReadOnlyList<ClipEntry> List(int offset, int limit, string? type)
    {
        lock (_lock)
        {
            IEnumerable<ClipEntry> q = _items;
            if (!string.IsNullOrEmpty(type)) q = q.Where(e => e.Type == type);
            return q.Skip(offset).Take(limit).ToList();
        }
    }

    public int Count { get { lock (_lock) return _items.Count; } }

    public ClipEntry? Get(long id)
    {
        lock (_lock) return _items.FirstOrDefault(e => e.Id == id);
    }

    public bool Delete(long id)
    {
        lock (_lock)
        {
            var e = _items.FirstOrDefault(x => x.Id == id);
            if (e is null) return false;
            _items.Remove(e);
            _bytes -= e.SizeBytes;
            Version++;
            return true;
        }
    }

    public void Clear()
    {
        lock (_lock)
        {
            if (_items.Count == 0) return;
            _items.Clear();
            _bytes = 0;
            Version++;
        }
    }

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
