using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using NSec.Cryptography;
using PcRemote.Core.Plugins;

namespace PcRemote.Tests;

public class PluginStoreTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "sackdesk-tienda-" + Guid.NewGuid().ToString("N"));
    private readonly PluginCatalog _catalog;
    private readonly FeatureStore _features;
    private readonly PluginStore _store;

    public PluginStoreTests()
    {
        Directory.CreateDirectory(_root);
        _catalog = new PluginCatalog(Path.Combine(_root, "plugins"), NullLogger<PluginCatalog>.Instance);
        _features = new FeatureStore(Path.Combine(_root, "features.json"), NullLogger<FeatureStore>.Instance);
        _store = new PluginStore(new Uri("http://localhost/catalogo.json"), PluginStore.StoreKey, _catalog, _features, NullLogger<PluginStore>.Instance);
    }

    public void Dispose() => Directory.Delete(_root, recursive: true);

    private static byte[] Zip(params (string Path, string Text)[] files)
    {
        using var ms = new MemoryStream();
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Create))
            foreach (var (path, text) in files)
                using (var w = new StreamWriter(zip.CreateEntry(path).Open())) w.Write(text);
        return ms.ToArray();
    }

    private static byte[] Plugin(string version, string run = "x") =>
        Zip(("plugin.json", $$"""{ "name": "Prueba", "version": "{{version}}", "actions": [ { "id": "a", "run": "{{run}}" } ] }"""));

    private static StoreEntry Entry(string id, string version, byte[] zip) =>
        new(id, "Prueba", null, null, version, null, null, $"{id}-{version}.zip", Convert.ToHexString(SHA256.HashData(zip)), zip.Length);

    private string Folder(string id) => Path.Combine(_catalog.Root, id);

    [Fact]
    public void Only_a_catalog_signed_with_the_key_is_accepted()
    {
        var alg = SignatureAlgorithm.Ed25519;
        using var key = Key.Create(alg);
        var pub = key.PublicKey.Export(KeyBlobFormat.RawPublicKey);
        var json = Encoding.UTF8.GetBytes("""{ "version": 1, "plugins": [ { "id": "docker", "name": "Docker", "version": "1.0.0", "file": "docker-1.0.0.zip", "sha256": "AB", "bytes": 2 }, { "id": "../fuera", "name": "x", "version": "1", "file": "x", "sha256": "", "bytes": 0 } ] }""");
        var sig = Encoding.ASCII.GetBytes(Convert.ToBase64String(alg.Sign(key, json)));

        var entries = PluginStore.ParseCatalog(json, sig, pub);
        Assert.Equal("docker", Assert.Single(entries).Id); // the id that is not a plain id is dropped

        var tampered = json.ToArray();
        tampered[^5] ^= 1;
        Assert.Throws<StoreException>(() => PluginStore.ParseCatalog(tampered, sig, pub));
        Assert.Throws<StoreException>(() => PluginStore.ParseCatalog(json, sig, PluginStore.StoreKey));
        Assert.Throws<StoreException>(() => PluginStore.ParseCatalog(json, "no es base64"u8.ToArray(), pub));
    }

    [Fact]
    public void A_download_that_does_not_match_the_catalog_is_refused()
    {
        var zip = Plugin("1.0.0");
        var entry = Entry("prueba", "1.0.0", Plugin("6.6.6"));
        Assert.Throws<StoreException>(() => _store.InstallZip(entry, zip));
        Assert.False(Directory.Exists(Folder("prueba")));
    }

    [Fact]
    public void A_zip_that_writes_outside_its_folder_is_refused()
    {
        var zip = Zip(("plugin.json", """{ "version": "1.0.0", "actions": [ { "id": "a", "run": "x" } ] }"""), ("../../fuera.txt", "mal"));
        Assert.Throws<StoreException>(() => _store.InstallZip(Entry("prueba", "1.0.0", zip), zip));
        Assert.False(File.Exists(Path.Combine(_root, "fuera.txt")));
        Assert.False(Directory.Exists(Folder("prueba")));
        Assert.Empty(Directory.GetDirectories(_catalog.Root)); // no leftover temp folder
    }

    [Fact]
    public void Installs_disabled_updates_untouched_copies_and_keeps_edited_ones()
    {
        _features.Set("plugin:prueba", true); // an old flag with the same id must not switch the new one on
        var v1 = Plugin("1.0.0", "uno");
        Assert.False(_store.InstallZip(Entry("prueba", "1.0.0", v1), v1));
        Assert.Contains("uno", File.ReadAllText(Path.Combine(Folder("prueba"), "plugin.json")));
        Assert.False(_features.IsEnabled("plugin:prueba", false));

        // The owner enables it; an update keeps that choice.
        _features.Set("plugin:prueba", true);
        var v2 = Plugin("1.1.0", "dos");
        Assert.True(_store.InstallZip(Entry("prueba", "1.1.0", v2), v2));
        Assert.Contains("dos", File.ReadAllText(Path.Combine(Folder("prueba"), "plugin.json")));
        Assert.True(_features.IsEnabled("plugin:prueba", false));

        // Edited by the owner: the next update is refused and the edit survives.
        File.WriteAllText(Path.Combine(Folder("prueba"), "notas.txt"), "mío");
        var v3 = Plugin("1.2.0", "tres");
        Assert.Throws<StoreException>(() => _store.InstallZip(Entry("prueba", "1.2.0", v3), v3));
        Assert.True(File.Exists(Path.Combine(Folder("prueba"), "notas.txt")));
        Assert.Contains("dos", File.ReadAllText(Path.Combine(Folder("prueba"), "plugin.json")));
    }

    [Fact]
    public void Folders_that_are_not_from_the_store_are_never_touched()
    {
        Directory.CreateDirectory(Folder("propio"));
        File.WriteAllText(Path.Combine(Folder("propio"), "plugin.json"), """{ "actions": [ { "id": "a", "run": "mio" } ] }""");

        var zip = Plugin("1.0.0");
        Assert.Throws<StoreException>(() => _store.InstallZip(Entry("propio", "1.0.0", zip), zip));
        Assert.Throws<StoreException>(() => _store.Uninstall("propio"));
        Assert.Contains("mio", File.ReadAllText(Path.Combine(Folder("propio"), "plugin.json")));
    }

    [Fact]
    public void Uninstall_removes_the_folder_and_the_flag()
    {
        var zip = Plugin("1.0.0");
        _store.InstallZip(Entry("prueba", "1.0.0", zip), zip);
        _features.Set("plugin:prueba", true);

        Assert.Equal("Prueba", _store.Uninstall("prueba"));
        Assert.False(Directory.Exists(Folder("prueba")));
        Assert.False(_features.IsEnabled("plugin:prueba", false));
        Assert.Throws<StoreException>(() => _store.Uninstall("prueba"));
    }

    [Fact]
    public void Plugins_with_code_or_errors_or_another_id_are_refused()
    {
        var dll = Zip(("plugin.json", """{ "version": "1.0.0", "assembly": "x.dll" }"""), ("x.dll", "MZ"));
        Assert.Throws<StoreException>(() => _store.InstallZip(Entry("codigo", "1.0.0", dll), dll));

        var broken = Zip(("plugin.json", """{ "version": "1.0.0", "actions": [ { "id": "a" } ] }"""));
        Assert.Throws<StoreException>(() => _store.InstallZip(Entry("roto", "1.0.0", broken), broken));

        var other = Zip(("plugin.json", """{ "id": "otro", "version": "1.0.0", "actions": [ { "id": "a", "run": "x" } ] }"""));
        Assert.Throws<StoreException>(() => _store.InstallZip(Entry("prueba", "1.0.0", other), other));
        Assert.Empty(Directory.GetDirectories(_catalog.Root));
    }

    [Fact]
    public void The_store_plugins_in_the_repository_are_valid()
    {
        var dir = AppContext.BaseDirectory;
        while (!Directory.Exists(Path.Combine(dir, "plugins-tienda"))) dir = Path.GetDirectoryName(dir) ?? throw new DirectoryNotFoundException("agent/plugins-tienda");
        var folders = Directory.GetDirectories(Path.Combine(dir, "plugins-tienda"));
        Assert.NotEmpty(folders);

        foreach (var folder in folders)
        {
            var p = PluginCatalog.Read(folder);
            Assert.True(p.Errors.Count == 0, $"{p.Id}: {string.Join(" | ", p.Errors)}");
            Assert.False(p.IsAssembly, $"{p.Id}: la tienda solo admite plugins de acciones");
            Assert.False(string.IsNullOrWhiteSpace(p.Manifest.Version), $"{p.Id}: sin versión");
            foreach (var a in p.Manifest.Actions)
            {
                var file = a.Args.IndexOf("-File");
                if (file >= 0) Assert.True(File.Exists(Path.Combine(folder, a.Args[file + 1])), $"{p.Id}/{a.Id}: falta {a.Args[file + 1]}");
                foreach (System.Text.RegularExpressions.Match match in PluginIds.Placeholder().Matches(a.Confirm ?? ""))
                    Assert.Contains(a.Params, x => x.Id == match.Groups[1].Value);
            }
            foreach (var script in Directory.GetFiles(folder, "*.ps1"))
                Assert.True(File.ReadAllBytes(script) is [0xEF, 0xBB, 0xBF, ..], $"{script} sin BOM");
        }
    }
}
