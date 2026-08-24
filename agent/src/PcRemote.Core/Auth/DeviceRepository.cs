using Microsoft.Data.Sqlite;
using PcRemote.Core.Storage;

namespace PcRemote.Core.Auth;

public sealed record Device(
    string   Id,
    string   Name,
    byte[]   PublicKey,
    DateTimeOffset PairedAt,
    DateTimeOffset? LastSeenAt,
    bool     Revoked);

/// <summary>SQLite-backed CRUD for paired devices.</summary>
public sealed class DeviceRepository
{
    private readonly AgentDatabase _db;

    public DeviceRepository(AgentDatabase db) => _db = db;

    public void Insert(Device device)
    {
        using var conn = _db.Open();
        using var cmd  = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO devices (id, name, public_key, paired_at, revoked)
            VALUES ($id, $name, $pk, $paired, 0)
            """;
        cmd.Parameters.AddWithValue("$id", device.Id);
        cmd.Parameters.AddWithValue("$name", device.Name);
        cmd.Parameters.AddWithValue("$pk", device.PublicKey);
        cmd.Parameters.AddWithValue("$paired", device.PairedAt.ToUnixTimeMilliseconds());
        cmd.ExecuteNonQuery();
    }

    public Device? Get(string id)
    {
        using var conn = _db.Open();
        using var cmd  = conn.CreateCommand();
        cmd.CommandText = "SELECT id, name, public_key, paired_at, last_seen_at, revoked FROM devices WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        using var reader = cmd.ExecuteReader();
        return reader.Read() ? Map(reader) : null;
    }

    public IReadOnlyList<Device> List()
    {
        using var conn = _db.Open();
        using var cmd  = conn.CreateCommand();
        cmd.CommandText = "SELECT id, name, public_key, paired_at, last_seen_at, revoked FROM devices ORDER BY paired_at DESC";
        var results = new List<Device>();
        using var reader = cmd.ExecuteReader();
        while (reader.Read()) results.Add(Map(reader));
        return results;
    }

    public void Revoke(string id)
    {
        using var conn = _db.Open();
        using var cmd  = conn.CreateCommand();
        cmd.CommandText = "UPDATE devices SET revoked = 1 WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public void Delete(string id)
    {
        using var conn = _db.Open();
        using var cmd  = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM devices WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public void TouchLastSeen(string id)
    {
        using var conn = _db.Open();
        using var cmd  = conn.CreateCommand();
        cmd.CommandText = "UPDATE devices SET last_seen_at = $ts WHERE id = $id";
        cmd.Parameters.AddWithValue("$ts", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    private static Device Map(SqliteDataReader r) => new(
        Id:          r.GetString(0),
        Name:        r.GetString(1),
        PublicKey:   (byte[])r["public_key"],
        PairedAt:    DateTimeOffset.FromUnixTimeMilliseconds(r.GetInt64(3)),
        LastSeenAt:  r.IsDBNull(4) ? null : DateTimeOffset.FromUnixTimeMilliseconds(r.GetInt64(4)),
        Revoked:     r.GetInt64(5) != 0);
}
