using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using PcRemote.Core.Plugins;
using PcRemote.Modules.Clipboard;
using PcRemote.Modules.Files;

namespace PcRemote.Tests;

public class PluginManifestTests
{
    private static PluginManifest Manifest(string json) =>
        JsonSerializer.Deserialize<PluginManifest>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;

    [Fact]
    public void A_valid_manifest_has_no_errors()
    {
        var m = Manifest("""
            { "actions": [ { "id": "ping", "run": "ping", "args": ["-n", "1", "{host}"],
                             "params": [ { "id": "host", "type": "string" } ] } ] }
            """);
        Assert.Empty(PluginCatalog.Validate(m, "net-tools", "."));
    }

    [Theory]
    [InlineData("""{ "id": "a", "run": "{prog}", "params": [ { "id": "prog" } ] }""")]   // a value would pick the program
    [InlineData("""{ "id": "a", "open": "https://x/{q}", "params": [ { "id": "q" } ] }""")]
    [InlineData("""{ "id": "a", "run": "x", "args": ["{nope}"] }""")]                   // undeclared placeholder
    [InlineData("""{ "id": "a" }""")]                                                   // neither run nor open
    [InlineData("""{ "id": "a", "run": "x", "open": "y" }""")]                          // both
    [InlineData("""{ "id": "Bad Id", "run": "x" }""")]
    [InlineData("""{ "id": "a", "run": "x", "params": [ { "id": "c", "type": "choice" } ] }""")] // choice without options
    public void Unsafe_or_broken_actions_are_rejected(string action)
    {
        var m = Manifest($$"""{ "actions": [ {{action}} ] }""");
        Assert.NotEmpty(PluginCatalog.Validate(m, "p", "."));
    }

    [Theory]
    [InlineData("My Plugin!", "my-plugin")]
    [InlineData("__", "plugin")]
    [InlineData("Docker", "docker")]
    public void Folder_names_become_valid_ids(string folder, string id)
    {
        Assert.Equal(id, PluginIds.FromFolder(folder));
        Assert.Matches(PluginIds.Valid(), PluginIds.FromFolder(folder));
    }
}

public class PluginParamTests
{
    private static JsonElement Json(string s) => JsonDocument.Parse(s).RootElement;

    private static PluginAction Action(params PluginParam[] ps) => new() { Id = "a", Run = "x", Params = ps.ToList() };

    [Fact]
    public void Values_fill_one_argument_each()
    {
        var action = Action(new PluginParam { Id = "host" });
        var bound = PluginRunner.BindParams(action, Json("""{ "host": "1.1.1.1 & calc" }"""));
        // No shell: "&" is just a character of the one argument, never a second command.
        Assert.Equal("1.1.1.1 & calc", PluginRunner.Fill("{host}", bound));
    }

    [Fact]
    public void Pattern_must_match_the_whole_value()
    {
        var action = Action(new PluginParam { Id = "n", Pattern = "[a-z]+" });
        Assert.Throws<PluginParamException>(() => PluginRunner.BindParams(action, Json("""{ "n": "abc;rm" }""")));
        Assert.Equal("abc", PluginRunner.BindParams(action, Json("""{ "n": "abc" }"""))["n"]);
    }

    [Fact]
    public void Control_characters_are_refused()
    {
        var action = Action(new PluginParam { Id = "n" });
        Assert.Throws<PluginParamException>(() => PluginRunner.BindParams(action, Json("""{ "n": "a\nb" }""")));
    }

    [Fact]
    public void Numbers_respect_min_and_max_and_use_invariant_format()
    {
        var action = Action(new PluginParam { Id = "v", Type = "number", Min = 0, Max = 100 });
        Assert.Throws<PluginParamException>(() => PluginRunner.BindParams(action, Json("""{ "v": 101 }""")));
        Assert.Equal("42.5", PluginRunner.BindParams(action, Json("""{ "v": "42.5" }"""))["v"]);
    }

    [Fact]
    public void Choice_only_accepts_listed_options()
    {
        var action = Action(new PluginParam { Id = "c", Type = "choice", Options = new() { "on", "off" } });
        Assert.Throws<PluginParamException>(() => PluginRunner.BindParams(action, Json("""{ "c": "maybe" }""")));
        Assert.Equal("off", PluginRunner.BindParams(action, Json("""{ "c": "off" }"""))["c"]);
    }

    [Fact]
    public void Missing_required_value_falls_back_to_default_or_fails()
    {
        var withDefault = Action(new PluginParam { Id = "h", Default = Json("\"localhost\"") });
        Assert.Equal("localhost", PluginRunner.BindParams(withDefault, null)["h"]);
        Assert.Throws<PluginParamException>(() => PluginRunner.BindParams(Action(new PluginParam { Id = "h" }), null));
    }

    [Fact]
    public void Scanning_skips_folders_without_manifest_and_reports_bad_json()
    {
        var root = Path.Combine(Path.GetTempPath(), "pcremote-plugins-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(Path.Combine(root, "empty"));
            Directory.CreateDirectory(Path.Combine(root, "broken"));
            File.WriteAllText(Path.Combine(root, "broken", "plugin.json"), "{ not json");
            Directory.CreateDirectory(Path.Combine(root, "Good One"));
            File.WriteAllText(Path.Combine(root, "Good One", "plugin.json"),
                """{ "name": "Good", "actions": [ { "id": "go", "run": "cmd" } ] }""");

            var list = new PluginCatalog(root, NullLogger<PluginCatalog>.Instance).Scan();

            Assert.Equal(2, list.Count);
            Assert.NotEmpty(list.Single(p => p.Id == "broken").Errors);
            var good = list.Single(p => p.Id == "good-one");
            Assert.Empty(good.Errors);
            Assert.Equal("Good", good.Manifest.Name);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}

public class BundledPluginTests
{
    private static readonly JsonSerializerOptions Read = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    private static PluginManifest Manifest(BundledPlugin p) =>
        JsonSerializer.Deserialize<PluginManifest>(p.Files["plugin.json"], Read)!;

    [Fact]
    public void The_agent_ships_the_plugin_collection()
    {
        Assert.True(BundledPlugins.All.Count >= 20, $"Solo hay {BundledPlugins.All.Count} plugins incluidos");
        Assert.Contains(BundledPlugins.All, p => p.Id == "utilidades-windows");
    }

    [Fact]
    public void Every_bundled_plugin_is_valid_and_ships_its_scripts()
    {
        foreach (var p in BundledPlugins.All)
        {
            var m = Manifest(p);
            var errors = PluginCatalog.Validate(m, p.Id, ".");
            Assert.True(errors.Count == 0, $"{p.Id}: {string.Join(" | ", errors)}");
            Assert.False(string.IsNullOrWhiteSpace(m.Name), $"{p.Id}: sin nombre");
            Assert.NotNull(p.Version);

            foreach (var a in m.Actions)
            {
                var file = a.Args.IndexOf("-File");
                if (file >= 0) Assert.True(p.Files.ContainsKey(a.Args[file + 1]), $"{p.Id}/{a.Id}: falta {a.Args[file + 1]}");

                // The app fills {param} in the confirmation text; an unknown one would show as is.
                foreach (System.Text.RegularExpressions.Match match in PluginIds.Placeholder().Matches(a.Confirm ?? ""))
                    Assert.Contains(a.Params, x => x.Id == match.Groups[1].Value);

                foreach (var param in a.Params.Where(x => x.Type == "choice" && x.Default is not null))
                    Assert.Contains(param.Default!.Value.GetString(), param.Options!);
            }
        }
    }

    [Fact]
    public void Scripts_are_utf8_with_bom()
    {
        // Windows PowerShell 5.1 reads a .ps1 without BOM as ANSI: every "ó" would break.
        foreach (var p in BundledPlugins.All)
        foreach (var (path, bytes) in p.Files.Where(f => f.Key.EndsWith(".ps1")))
            Assert.True(bytes is [0xEF, 0xBB, 0xBF, ..], $"{p.Id}/{path} sin BOM");
    }

    private static BundledPlugin Fake(string id, string version, string script) =>
        new(id, version, new Dictionary<string, byte[]>
        {
            ["plugin.json"] = System.Text.Encoding.UTF8.GetBytes($$"""{ "version": "{{version}}", "actions": [ { "id": "a", "run": "x" } ] }"""),
            ["x.ps1"] = System.Text.Encoding.UTF8.GetBytes(script),
        });

    private static (PluginCatalog Catalog, string Root) NewCatalog()
    {
        var root = Path.Combine(Path.GetTempPath(), "pcremote-bundled-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        return (new PluginCatalog(root, NullLogger<PluginCatalog>.Instance), root);
    }

    [Fact]
    public void Installs_once_and_updates_only_untouched_copies()
    {
        var (catalog, root) = NewCatalog();
        try
        {
            catalog.InstallBundled([Fake("uno", "1.0.0", "v1"), Fake("dos", "1.0.0", "v1")]);
            Assert.Equal("v1", File.ReadAllText(Path.Combine(root, "uno", "x.ps1")));

            // The owner edits "dos"; a newer agent updates "uno" and leaves "dos" alone.
            File.WriteAllText(Path.Combine(root, "dos", "x.ps1"), "mío");
            catalog.InstallBundled([Fake("uno", "1.1.0", "v2"), Fake("dos", "1.1.0", "v2")]);
            Assert.Equal("v2", File.ReadAllText(Path.Combine(root, "uno", "x.ps1")));
            Assert.Equal("mío", File.ReadAllText(Path.Combine(root, "dos", "x.ps1")));

            // Same version again: nothing to do.
            File.WriteAllText(Path.Combine(root, "uno", "x.ps1"), "v2");
            catalog.InstallBundled([Fake("uno", "1.1.0", "v3")]);
            Assert.Equal("v2", File.ReadAllText(Path.Combine(root, "uno", "x.ps1")));
        }
        finally { Directory.Delete(root, recursive: true); }
    }

    [Fact]
    public void A_deleted_or_preexisting_folder_is_left_alone()
    {
        var (catalog, root) = NewCatalog();
        try
        {
            Directory.CreateDirectory(Path.Combine(root, "propio"));
            File.WriteAllText(Path.Combine(root, "propio", "plugin.json"), "{}");
            catalog.InstallBundled([Fake("borrado", "1.0.0", "v1"), Fake("propio", "1.0.0", "v1")]);
            Assert.Equal("{}", File.ReadAllText(Path.Combine(root, "propio", "plugin.json")));

            Directory.Delete(Path.Combine(root, "borrado"), recursive: true);
            catalog.InstallBundled([Fake("borrado", "2.0.0", "v2"), Fake("propio", "2.0.0", "v2")]);
            Assert.False(Directory.Exists(Path.Combine(root, "borrado")));
            Assert.Equal("{}", File.ReadAllText(Path.Combine(root, "propio", "plugin.json")));
        }
        finally { Directory.Delete(root, recursive: true); }
    }
}

public class FeatureStoreTests
{
    [Fact]
    public void Flags_persist_and_default_when_unset()
    {
        var path = Path.Combine(Path.GetTempPath(), "pcremote-features-" + Guid.NewGuid().ToString("N") + ".json");
        try
        {
            var store = new FeatureStore(path, NullLogger<FeatureStore>.Instance);
            Assert.False(store.IsEnabled("terminal", false));
            Assert.True(store.IsEnabled("files", true));

            store.Set("terminal", true);
            store.Set("files", false);

            var reloaded = new FeatureStore(path, NullLogger<FeatureStore>.Instance);
            Assert.True(reloaded.IsEnabled("terminal", false));
            Assert.False(reloaded.IsEnabled("files", true));
        }
        finally
        {
            File.Delete(path);
        }
    }
}

public class FilesHelpersTests
{
    [Theory]
    [InlineData(@"\\server\share\x")]
    [InlineData(@"\\?\C:\Windows")]
    [InlineData(@"relative\path")]
    [InlineData("")]
    public void Only_absolute_local_paths_are_accepted(string path)
    {
        Assert.ThrowsAny<ArgumentException>(() => FilesModule.CheckPath(path));
    }

    [Fact]
    public void Paths_are_normalised()
    {
        Assert.Equal(@"C:\Windows", FilesModule.CheckPath(@"C:\Users\..\Windows"));
    }

    [Theory]
    [InlineData(@"..\..\evil.exe", "evil.exe")]
    [InlineData("photo:1?.jpg", "photo_1_.jpg")]
    [InlineData("a/b/c.txt", "c.txt")]
    [InlineData("..", null)]
    [InlineData("   ", null)]
    public void Uploaded_names_cannot_leave_the_folder(string name, string? expected)
    {
        Assert.Equal(expected, FilesModule.SanitizeFileName(name));
    }

    [Fact]
    public void Existing_names_get_a_counter()
    {
        var dir = Directory.CreateTempSubdirectory("pcremote-files-").FullName;
        try
        {
            File.WriteAllText(Path.Combine(dir, "a.txt"), "");
            File.WriteAllText(Path.Combine(dir, "a (1).txt"), "");
            Assert.Equal(Path.Combine(dir, "a (2).txt"), FilesModule.UniquePath(dir, "a.txt"));
            Assert.Equal(Path.Combine(dir, "b.txt"), FilesModule.UniquePath(dir, "b.txt"));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }
}

public class ClipboardHistoryTests
{
    [Fact]
    public void Copying_the_same_thing_again_moves_it_to_the_top()
    {
        var h = new ClipboardHistoryHarness(5);
        h.Add("a"); h.Add("b"); h.Add("a");
        Assert.Equal(new[] { "a", "b" }, h.Texts());
    }

    [Fact]
    public void Oldest_entries_fall_off_past_capacity()
    {
        var h = new ClipboardHistoryHarness(3);
        foreach (var s in new[] { "1", "2", "3", "4" }) h.Add(s);
        Assert.Equal(new[] { "4", "3", "2" }, h.Texts());
    }

    [Fact]
    public void Images_get_a_thumbnail_and_a_bounded_size()
    {
        var h = new ClipboardHistoryHarness(3);
        using var bmp = new System.Drawing.Bitmap(5000, 100);
        var entry = h.History.FromImage(bmp);
        Assert.Equal(ClipboardHistory.MaxImageSide, entry.Width);
        Assert.NotNull(entry.ThumbnailBase64);
        Assert.NotNull(entry.Png);
    }

    /// <summary>Drives Add directly; no watcher on the real clipboard.</summary>
    private sealed class ClipboardHistoryHarness(int capacity)
    {
        public ClipboardHistory History { get; } = new(capacity, watch: false);

        public void Add(string s) =>
            History.Add(new ClipEntry { Id = Guid.NewGuid().ToString("N")[..6], Kind = "text", Text = s, Hash = "test:" + s });

        public string[] Texts() => History.List().Select(e => e.Text!).ToArray();
    }
}
