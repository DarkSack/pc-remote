using System.Threading.Channels;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Core.Storage;

/// <summary>
/// Writes executed commands to <c>command_log</c> so the panel can answer
/// "what did the phone do, and when?".
///
/// - Only domain, action, device and result are stored — never params: they
///   can carry typed text or clipboard contents.
/// - High-frequency commands (pointer moves, scroll, clicks, icon batches, volume drags) are skipped.
///   A touchpad sends ~60 moves a second; logging those would bury the useful
///   rows and hammer the disk.
/// - Recording never blocks a command: rows go to a bounded queue that a
///   background writer flushes in batches. If the disk stalls and the queue
///   fills, the oldest pending rows are dropped instead of slowing input.
/// </summary>
public sealed class CommandAuditLog : BackgroundService
{
    private static readonly HashSet<string> Skipped = new(StringComparer.OrdinalIgnoreCase)
    {
        "input.mouseMove", "input.mouseScroll", "input.mousePos",
        "input.mouseClick", "input.mouseDown", "input.mouseUp",
        "ping.ping", "systeminfo.stats",
        // The phone asks for icons in batches while the app list scrolls, and sends
        // volume several times a second while the slider is dragged.
        "appicons.get", "media.volumeSet",
    };

    /// <summary>Rows older than this are deleted at startup.</summary>
    private static readonly TimeSpan Retention = TimeSpan.FromDays(30);

    private readonly AgentDatabase _db;
    private readonly ILogger<CommandAuditLog> _logger;
    private readonly Channel<AuditEntry> _queue = Channel.CreateBounded<AuditEntry>(
        new BoundedChannelOptions(10_000) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });

    public CommandAuditLog(AgentDatabase db, ILogger<CommandAuditLog> logger)
    {
        _db = db;
        _logger = logger;
    }

    /// <summary>Commands sent many times a second: not audited, logged at Debug.</summary>
    public static bool IsHighFrequency(string domain, string action) => Skipped.Contains($"{domain}.{action}");

    public void Record(ClientSession session, CommandRequest req, CommandResponse res)
    {
        if (IsHighFrequency(req.Domain, req.Action)) return;
        _queue.Writer.TryWrite(new AuditEntry(
            session.SessionId, session.DeviceId, req.Domain.ToLowerInvariant(), req.Action,
            res.Success, res.Error?.Code, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }

    /// <summary>Latest rows first, with the device name when the device still exists.</summary>
    public IReadOnlyList<AuditRow> Recent(int limit)
    {
        using var conn = _db.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            SELECT l.ts, l.device_id, d.name, l.domain, l.action, l.success, l.error_code
            FROM command_log l LEFT JOIN devices d ON d.id = l.device_id
            ORDER BY l.id DESC LIMIT $limit
            """;
        cmd.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 1000));
        var rows = new List<AuditRow>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            rows.Add(new AuditRow(
                DateTimeOffset.FromUnixTimeMilliseconds(r.GetInt64(0)),
                r.IsDBNull(1) ? null : r.GetString(1),
                r.IsDBNull(2) ? null : r.GetString(2),
                r.GetString(3),
                r.GetString(4),
                r.GetInt64(5) != 0,
                r.IsDBNull(6) ? null : r.GetString(6)));
        }
        return rows;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // The schema is created by DatabaseInitializer, registered before us.
        PurgeOld();

        var batch = new List<AuditEntry>(256);
        var reader = _queue.Reader;
        try
        {
            while (await reader.WaitToReadAsync(stoppingToken))
            {
                // Let a burst accumulate, then write it in one transaction.
                await Task.Delay(500, stoppingToken);
                while (batch.Count < 1000 && reader.TryRead(out var e)) batch.Add(e);
                Flush(batch);
            }
        }
        catch (OperationCanceledException)
        {
            // Shutting down: write what is still queued.
            while (reader.TryRead(out var e)) batch.Add(e);
            Flush(batch);
        }
    }

    private void Flush(List<AuditEntry> batch)
    {
        if (batch.Count == 0) return;
        try
        {
            using var conn = _db.Open();
            using var tx = conn.BeginTransaction();
            using var cmd = conn.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO command_log (session_id, device_id, domain, action, success, error_code, ts)
                VALUES ($s, $d, $dom, $a, $ok, $err, $ts)
                """;
            var pS = cmd.Parameters.Add("$s", Microsoft.Data.Sqlite.SqliteType.Text);
            var pD = cmd.Parameters.Add("$d", Microsoft.Data.Sqlite.SqliteType.Text);
            var pDom = cmd.Parameters.Add("$dom", Microsoft.Data.Sqlite.SqliteType.Text);
            var pA = cmd.Parameters.Add("$a", Microsoft.Data.Sqlite.SqliteType.Text);
            var pOk = cmd.Parameters.Add("$ok", Microsoft.Data.Sqlite.SqliteType.Integer);
            var pErr = cmd.Parameters.Add("$err", Microsoft.Data.Sqlite.SqliteType.Text);
            var pTs = cmd.Parameters.Add("$ts", Microsoft.Data.Sqlite.SqliteType.Integer);
            foreach (var e in batch)
            {
                pS.Value = e.SessionId;
                pD.Value = e.DeviceId;
                pDom.Value = e.Domain;
                pA.Value = e.Action;
                pOk.Value = e.Success ? 1 : 0;
                pErr.Value = (object?)e.ErrorCode ?? DBNull.Value;
                pTs.Value = e.Ts;
                cmd.ExecuteNonQuery();
            }
            tx.Commit();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not write {Count} audit rows", batch.Count);
        }
        finally
        {
            batch.Clear();
        }
    }

    private void PurgeOld()
    {
        try
        {
            using var conn = _db.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "DELETE FROM command_log WHERE ts < $cutoff";
            cmd.Parameters.AddWithValue("$cutoff", DateTimeOffset.UtcNow.Subtract(Retention).ToUnixTimeMilliseconds());
            var n = cmd.ExecuteNonQuery();
            if (n > 0) _logger.LogInformation("Purged {Count} audit rows older than {Days} days", n, Retention.TotalDays);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not purge old audit rows");
        }
    }

    private sealed record AuditEntry(
        string SessionId, string DeviceId, string Domain, string Action, bool Success, string? ErrorCode, long Ts);
}

public sealed record AuditRow(
    DateTimeOffset Ts, string? DeviceId, string? DeviceName, string Domain, string Action, bool Success, string? ErrorCode);
