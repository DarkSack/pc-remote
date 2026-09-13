using System.Collections.Concurrent;
using Serilog.Core;
using Serilog.Events;
using Serilog.Formatting.Display;

namespace PcRemote.Core.Panel;

// ══════════════════════════════════════════════════════════════
// Ring buffer de las últimas N líneas de log, servido al panel
// web vía /api/logs. Singleton porque tanto Serilog (durante boot)
// como los endpoints (durante request) necesitan la misma instancia.
// ══════════════════════════════════════════════════════════════
public sealed class InMemoryLogSink : ILogEventSink
{
    public static readonly InMemoryLogSink Instance = new();

    private const int Capacity = 500;
    private readonly ConcurrentQueue<LogLine> _log = new();

    public record LogLine(DateTime Ts, string Level, string Message);

    // :lj renders strings without the quotes RenderMessage() adds ("\"PC-01\"").
    private static readonly MessageTemplateTextFormatter Formatter = new("{Message:lj}");

    public void Emit(LogEvent evt)
    {
        using var writer = new StringWriter();
        Formatter.Format(evt, writer);
        var line = new LogLine(
            evt.Timestamp.UtcDateTime,
            evt.Level.ToString(),
            writer.ToString());

        _log.Enqueue(line);
        while (_log.Count > Capacity && _log.TryDequeue(out _)) { }
    }

    public IReadOnlyList<LogLine> Snapshot() => _log.ToArray();
}
