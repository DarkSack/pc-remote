using System.Text;
using System.Xml;
using System.Xml.Linq;
using Microsoft.Data.Sqlite;

namespace PcRemote.Modules.Notifications;

/// <summary>One Windows notification as the phone gets it.</summary>
public sealed record PcNotification(long Id, string App, string AppId, string Title, string Body, long Ts);

/// <summary>
/// Reads the notifications Windows itself keeps for the user, in
/// <c>%LOCALAPPDATA%\Microsoft\Windows\Notifications\wpndatabase.db</c> (SQLite,
/// WAL): the same toasts the Action Center lists. Read-only, never written.
///
/// Why not UserNotificationListener: that WinRT API only answers apps with
/// package identity, and the agent is a plain exe. The database is not a
/// documented API, so every read is defensive: a schema it does not know gives
/// an error to show, not a crash.
/// </summary>
internal static class NotificationDatabase
{
    public const int MaxTitle = 200;
    public const int MaxBody = 1000;

    public static string DefaultPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Microsoft", "Windows", "Notifications", "wpndatabase.db");

    /// <summary>The newest <paramref name="limit"/> toasts, newest first.</summary>
    public static List<PcNotification> Latest(string path, int limit, out long lastId) =>
        Query(path, "n.Id > 0 ORDER BY n.Id DESC", limit, 0, out lastId);

    /// <summary>
    /// Toasts that arrived after <paramref name="afterId"/>, oldest first. Windows
    /// numbers notifications with a counter that only goes up, so Id works as a cursor.
    /// </summary>
    public static List<PcNotification> After(string path, long afterId, out long lastId, int limit = 100) =>
        Query(path, "n.Id > $after ORDER BY n.Id", limit, afterId, out lastId);

    /// <param name="lastId">Highest Id read, counting toasts without text, so they are not read again.</param>
    private static List<PcNotification> Query(string path, string whereAndOrder, int limit, long afterId, out long lastId)
    {
        if (!File.Exists(path)) throw new FileNotFoundException("Este Windows no guarda las notificaciones donde PC Remote las busca.", path);

        var cs = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadOnly,
            Pooling = false, // do not keep Windows' file open between polls
        }.ToString();
        using var conn = new SqliteConnection(cs);
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $"""
            SELECT n.Id, n.ArrivalTime, n.Payload, h.PrimaryId
            FROM Notification n JOIN NotificationHandler h ON h.RecordId = n.HandlerId
            WHERE n.Type = 'toast' AND {whereAndOrder}
            LIMIT $limit
            """;
        cmd.Parameters.AddWithValue("$after", afterId);
        cmd.Parameters.AddWithValue("$limit", limit);

        var result = new List<PcNotification>();
        lastId = afterId;
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            lastId = Math.Max(lastId, reader.GetInt64(0));
            if (reader.IsDBNull(2)) continue;
            var payload = reader.GetFieldValue<byte[]>(2);
            if (Parse(payload) is not var (title, body)) continue;
            var appId = reader.GetString(3);
            result.Add(new PcNotification(
                Id: reader.GetInt64(0),
                App: AppName(appId),
                AppId: appId,
                Title: title,
                Body: body,
                Ts: ToUnixMs(reader.IsDBNull(1) ? 0 : reader.GetInt64(1))));
        }
        return result;
    }

    /// <summary>The visible texts of a toast: the first is the title, the rest the body. Null if it has none.</summary>
    internal static (string Title, string Body)? Parse(byte[] payload)
    {
        try
        {
            var xml = Encoding.UTF8.GetString(payload).TrimStart('\uFEFF');
            using var reader = XmlReader.Create(new StringReader(xml), new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit });
            var doc = XDocument.Load(reader);
            var bindings = doc.Descendants("binding").ToList();
            var binding = bindings.FirstOrDefault(b => (string?)b.Attribute("template") == "ToastGeneric") ?? bindings.FirstOrDefault();
            var lines = (binding?.Elements("text") ?? [])
                .Select(t => t.Value.Trim())
                .Where(s => s.Length > 0)
                .ToList();
            if (lines.Count == 0) return null;
            return (Cap(lines[0], MaxTitle), Cap(string.Join("\n", lines.Skip(1)), MaxBody));
        }
        catch (XmlException)
        {
            return null;
        }
    }

    /// <summary>Start-menu name of the app, or a readable guess from its id.</summary>
    private static string AppName(string appId)
    {
        try { if (Applications.InstalledApps.NameOf(appId) is { } name) return name; }
        catch { /* catalog unavailable: guess below */ }
        return GuessName(appId);
    }

    /// <summary>
    /// "Microsoft.WindowsStore_8wekyb3d8bbwe!App" → "WindowsStore",
    /// "C:\…\Teams.exe" → "Teams", "com.squirrel.Discord.Discord" → "Discord",
    /// "Windows.SystemToast.Calling" → "Windows".
    /// </summary>
    internal static string GuessName(string appId)
    {
        if (appId.StartsWith("Windows.SystemToast", StringComparison.OrdinalIgnoreCase)) return "Windows";
        if (appId.Contains('\\') || appId.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            return Path.GetFileNameWithoutExtension(appId);
        var family = appId.Split('!')[0].Split('_')[0];
        var last = family.Split('.', StringSplitOptions.RemoveEmptyEntries).LastOrDefault();
        return string.IsNullOrEmpty(last) ? appId : last;
    }

    /// <summary>ArrivalTime is a Windows FILETIME (100 ns ticks since 1601, UTC).</summary>
    private static long ToUnixMs(long fileTime) =>
        fileTime <= 0 ? 0 : DateTimeOffset.FromFileTime(fileTime).ToUnixTimeMilliseconds();

    private static string Cap(string s, int max) => s.Length <= max ? s : s[..(max - 1)] + "…";
}
