using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging.Abstractions;
using PcRemote.Core.Plugins;
using PcRemote.Core.Router;
using PcRemote.Modules.Notifications;

namespace PcRemote.Tests;

public class NotificationsTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "pcremote-notif-" + Guid.NewGuid().ToString("N"));
    private string Db => Path.Combine(_dir, "wpndatabase.db");

    public NotificationsTests()
    {
        Directory.CreateDirectory(_dir);
        // The two tables SackDesk reads, with the columns Windows 11 uses.
        Exec("""
            CREATE TABLE NotificationHandler(RecordId INTEGER PRIMARY KEY, PrimaryId TEXT NOT NULL COLLATE NOCASE, HandlerType TEXT);
            CREATE TABLE Notification([Order] INTEGER NOT NULL PRIMARY KEY, Id INTEGER NOT NULL UNIQUE, HandlerId INTEGER,
              Type TEXT NOT NULL, Payload BLOB, ArrivalTime INT64, PayloadType TEXT NOT NULL);
            INSERT INTO NotificationHandler VALUES (1, 'PcRemote.Test.Chat_abc123!App', 'app:immersive');
            """);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        try { Directory.Delete(_dir, recursive: true); } catch { }
    }

    private void Exec(string sql, params (string, object)[] args)
    {
        using var conn = new SqliteConnection($"Data Source={Db};Pooling=False");
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        foreach (var (k, v) in args) cmd.Parameters.AddWithValue(k, v);
        cmd.ExecuteNonQuery();
    }

    private void Add(long id, string type, string? xml) => Exec(
        "INSERT INTO Notification(Id, HandlerId, Type, Payload, ArrivalTime, PayloadType) VALUES ($id, 1, $type, $p, $t, 'Xml')",
        ("$id", id), ("$type", type), ("$p", xml is null ? DBNull.Value : Encoding.UTF8.GetBytes(xml)),
        ("$t", new DateTimeOffset(2026, 10, 20, 12, 0, 0, TimeSpan.Zero).ToFileTime()));

    private static string Toast(params string[] texts) =>
        $"<toast><visual><binding template=\"ToastGeneric\">{string.Concat(texts.Select(t => $"<text>{t}</text>"))}</binding></visual></toast>";

    [Fact]
    public void Parse_takes_title_and_body_from_the_texts()
    {
        Assert.Equal(("Ana", "Hola\n¿Vienes?"), NotificationDatabase.Parse(Encoding.UTF8.GetBytes(Toast("Ana", "Hola", "¿Vienes?"))));
        Assert.Equal(("Solo título", ""), NotificationDatabase.Parse(Encoding.UTF8.GetBytes(
            "<?xml version=\"1.0\"?><toast><visual><binding template=\"ToastText01\"><text id=\"1\">Solo título</text></binding></visual></toast>")));
    }

    [Fact]
    public void Parse_gives_null_for_toasts_without_text_or_broken_xml()
    {
        Assert.Null(NotificationDatabase.Parse(Encoding.UTF8.GetBytes(Toast(" "))));
        Assert.Null(NotificationDatabase.Parse(Encoding.UTF8.GetBytes("<toast><visual>")));
    }

    [Fact]
    public void Parse_caps_long_texts()
    {
        var parsed = NotificationDatabase.Parse(Encoding.UTF8.GetBytes(Toast(new string('a', 500), new string('b', 5000))))!.Value;
        Assert.Equal(NotificationDatabase.MaxTitle, parsed.Title.Length);
        Assert.Equal(NotificationDatabase.MaxBody, parsed.Body.Length);
    }

    [Theory]
    [InlineData("Microsoft.WindowsStore_8wekyb3d8bbwe!App", "WindowsStore")]
    [InlineData(@"C:\Users\x\AppData\Local\Programs\Teams\Teams.exe", "Teams")]
    [InlineData("com.squirrel.Discord.Discord", "Discord")]
    [InlineData("Windows.SystemToast.SecurityAndMaintenance", "Windows")]
    [InlineData("Chrome", "Chrome")]
    public void GuessName_turns_app_ids_into_names(string appId, string name) =>
        Assert.Equal(name, NotificationDatabase.GuessName(appId));

    [Fact]
    public void Reads_only_toasts_and_moves_the_cursor_past_empty_ones()
    {
        Add(10, "toast", Toast("Uno"));
        Add(11, "badge", "<badge value=\"3\"/>");
        Add(12, "toast", Toast(" ")); // no text: skipped, but the cursor moves past it

        var latest = NotificationDatabase.Latest(Db, 50, out var lastId);
        var only = Assert.Single(latest);
        Assert.Equal(("Uno", "Chat", "PcRemote.Test.Chat_abc123!App"), (only.Title, only.App, only.AppId));
        Assert.Equal(new DateTimeOffset(2026, 10, 20, 12, 0, 0, TimeSpan.Zero).ToUnixTimeMilliseconds(), only.Ts);
        Assert.Equal(12, lastId);

        Add(13, "toast", Toast("Dos", "cuerpo"));
        var after = NotificationDatabase.After(Db, lastId, out lastId);
        Assert.Equal(("Dos", "cuerpo"), (Assert.Single(after).Title, after[0].Body));
        Assert.Equal(13, lastId);
        Assert.Empty(NotificationDatabase.After(Db, lastId, out _));
    }

    [Fact]
    public async Task Watch_sends_new_toasts_and_ends_when_the_feature_is_turned_off()
    {
        Add(1, "toast", Toast("Vieja"));
        var features = new FeatureStore(Path.Combine(_dir, "features.json"), NullLogger<FeatureStore>.Instance);
        features.Set("notifications", true);
        var module = new NotificationsModule(features) { DatabasePath = Db };
        var session = new ClientSession { SessionId = "s", DeviceId = "d", DeviceName = "Test", StartedAt = DateTimeOffset.UtcNow };

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        await using var stream = module.StartStreamAsync("watch", null, session, cts.Token).GetAsyncEnumerator(cts.Token);

        Assert.True(await stream.MoveNextAsync());
        var snapshot = JsonSerializer.SerializeToElement(stream.Current);
        Assert.Equal("snapshot", snapshot.GetProperty("op").GetString());
        Assert.Equal(1, snapshot.GetProperty("items").GetArrayLength());

        Add(2, "toast", Toast("Nueva"));
        Assert.True(await stream.MoveNextAsync());
        var add = JsonSerializer.SerializeToElement(stream.Current);
        Assert.Equal("add", add.GetProperty("op").GetString());
        Assert.Equal("Nueva", add.GetProperty("item").GetProperty("Title").GetString());

        features.Set("notifications", false);
        Assert.True(await stream.MoveNextAsync());
        Assert.Equal("disabled", JsonSerializer.SerializeToElement(stream.Current).GetProperty("op").GetString());
        Assert.False(await stream.MoveNextAsync());
    }

    [Fact]
    public void A_missing_database_is_an_error_to_show()
    {
        var ex = Assert.Throws<FileNotFoundException>(() => NotificationDatabase.Latest(Path.Combine(_dir, "nope.db"), 5, out _));
        Assert.Contains("notificaciones", ex.Message);
    }
}
