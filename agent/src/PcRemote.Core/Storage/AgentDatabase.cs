using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Storage;

/// <summary>
/// SQLite wrapper. Ensures schema is up-to-date on startup (idempotent DDL).
/// </summary>
public sealed class AgentDatabase
{
    private readonly AgentSettings _settings;
    private readonly ILogger<AgentDatabase> _logger;

    public string ConnectionString { get; }

    public AgentDatabase(AgentSettings settings, ILogger<AgentDatabase> logger)
    {
        _settings = settings;
        _logger   = logger;
        var path  = _settings.Storage.ResolvedDatabasePath;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        ConnectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode       = SqliteOpenMode.ReadWriteCreate,
            Cache      = SqliteCacheMode.Shared,
        }.ToString();
    }

    public SqliteConnection Open()
    {
        var conn = new SqliteConnection(ConnectionString);
        conn.Open();
        return conn;
    }

    public void EnsureSchema()
    {
        using var conn = Open();
        using var cmd  = conn.CreateCommand();
        cmd.CommandText = """
            PRAGMA journal_mode = WAL;
            PRAGMA foreign_keys = ON;

            CREATE TABLE IF NOT EXISTS devices (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                public_key    BLOB NOT NULL,
                paired_at     INTEGER NOT NULL,
                last_seen_at  INTEGER,
                revoked       INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS sessions (
                id            TEXT PRIMARY KEY,
                device_id     TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                started_at    INTEGER NOT NULL,
                ended_at      INTEGER,
                client_ip     TEXT
            );

            CREATE TABLE IF NOT EXISTS command_log (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id    TEXT,
                device_id     TEXT,
                domain        TEXT NOT NULL,
                action        TEXT NOT NULL,
                success       INTEGER NOT NULL,
                error_code    TEXT,
                ts            INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_cmdlog_ts ON command_log(ts);
            """;
        cmd.ExecuteNonQuery();
        _logger.LogInformation("Database schema ensured at {Path}", _settings.Storage.ResolvedDatabasePath);
    }
}
