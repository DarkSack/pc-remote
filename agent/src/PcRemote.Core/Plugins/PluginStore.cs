using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;

namespace PcRemote.Core.Plugins;

/// <summary>A plugin offered by the store, as the signed catalog lists it.</summary>
public sealed record StoreEntry(
    string Id, string Name, string? Description, string? Icon, string Version, string? Author,
    string? Changes, string File, string Sha256, long Bytes);

/// <summary>A store operation refused for a reason the owner should read (the panel shows it).</summary>
public sealed class StoreException(string message) : Exception(message);

/// <summary>
/// The plugin store: a catalog Sack publishes on the VPS (deploy/plugins/publicar.mjs),
/// browsed and installed from the panel. Nothing from it is trusted until checked: the
/// catalog must carry a valid Ed25519 signature by the store key (only its public half
/// is here) and every zip must match the SHA-256 the signed catalog gives. A plugin from
/// the store lands disabled, like any other, and the store only ever replaces or deletes
/// folders it wrote itself and nobody edited since (tracked in .tienda.json).
/// </summary>
public sealed class PluginStore
{
    /// <summary>Public half of the store key; the private one is %USERPROFILE%\.sackdesk\tienda-plugins.key on Sack's PC.</summary>
    internal static readonly byte[] StoreKey = Convert.FromBase64String("jfbpHOaGvZ59F5WidOplyPVYFN/owp/QI3zEe2bwy9I=");

    private const long MaxDownloadBytes = 20 * 1024 * 1024;
    private const long MaxUnpackedBytes = 50 * 1024 * 1024;
    private const int MaxFiles = 500;
    private const string StateFile = ".tienda.json";
    private static readonly TimeSpan CacheFor = TimeSpan.FromMinutes(5);

    private static readonly JsonSerializerOptions ReadOptions = new() { PropertyNameCaseInsensitive = true };

    private static readonly HttpClient Http = CreateHttp();

    private readonly Uri _catalogUrl;
    private readonly byte[] _publicKey;
    private readonly PluginCatalog _catalog;
    private readonly FeatureStore _features;
    private readonly ILogger<PluginStore> _logger;
    private readonly SemaphoreSlim _lock = new(1, 1);
    private (DateTime At, IReadOnlyList<StoreEntry> Entries)? _cache;

    public PluginStore(AgentSettings settings, PluginCatalog catalog, FeatureStore features, ILogger<PluginStore> logger)
        : this(new Uri(settings.PluginStore.Url), StoreKey, catalog, features, logger) { }

    internal PluginStore(Uri catalogUrl, byte[] publicKey, PluginCatalog catalog, FeatureStore features, ILogger<PluginStore> logger)
    {
        _catalogUrl = catalogUrl;
        _publicKey = publicKey;
        _catalog = catalog;
        _features = features;
        _logger = logger;
    }

    /// <summary>The catalog with what this PC has of each plugin (shape of the panel's /api/store).</summary>
    public async Task<object> DescribeAsync(bool refresh, CancellationToken ct)
    {
        var entries = await GetCatalogAsync(refresh, ct);
        var state = LoadState();
        var installed = _catalog.Scan();
        return new
        {
            plugins = entries.Select(e => new
            {
                id = e.Id,
                name = e.Name,
                description = e.Description,
                icon = e.Icon,
                version = e.Version,
                author = e.Author,
                changes = e.Changes,
                bytes = e.Bytes,
                installedVersion = state.GetValueOrDefault(e.Id)?.Version,
                state = StateOf(e, state, installed),
            }),
        };
    }

    /// <summary>Downloads and installs (or updates) one plugin of the catalog. True when it was an update.</summary>
    public async Task<(StoreEntry Entry, bool Updated)> InstallAsync(string id, CancellationToken ct)
    {
        // Always the current catalog: a cached one may name a zip that was published again since.
        var entry = (await GetCatalogAsync(refresh: true, ct)).FirstOrDefault(e => e.Id == id)
            ?? throw new StoreException($"La tienda no tiene ningún plugin «{id}».");
        var zip = await DownloadAsync(new Uri(_catalogUrl, entry.File), entry.Name, ct);

        await _lock.WaitAsync(ct);
        try
        {
            var updated = InstallZip(entry, zip);
            _logger.LogInformation("{Action} {Id} {Version} from the plugin store", updated ? "Updated" : "Installed", entry.Id, entry.Version);
            return (entry, updated);
        }
        finally { _lock.Release(); }
    }

    /// <summary>Removes a plugin the store installed (and only those). Returns its name.</summary>
    public string Uninstall(string id)
    {
        _lock.Wait();
        try
        {
            var state = LoadState();
            if (!state.Remove(id))
                throw new StoreException($"«{id}» no se instaló desde la tienda. Si quieres quitarlo, borra su carpeta en la carpeta de plugins.");
            var dir = Path.Combine(_catalog.Root, id);
            var name = Directory.Exists(dir) ? PluginCatalog.Read(dir).Manifest.Name ?? id : id;
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
            SaveState(state);
            _features.Set("plugin:" + id, false);
            _logger.LogInformation("Uninstalled {Id} (plugin store)", id);
            return name;
        }
        finally { _lock.Release(); }
    }

    private async Task<IReadOnlyList<StoreEntry>> GetCatalogAsync(bool refresh, CancellationToken ct)
    {
        if (!refresh && _cache is { } cached && DateTime.UtcNow - cached.At < CacheFor) return cached.Entries;
        var json = await DownloadAsync(_catalogUrl, "el catálogo", ct);
        var signature = await DownloadAsync(new Uri(_catalogUrl, "catalogo.sig"), "la firma del catálogo", ct);
        var entries = ParseCatalog(json, signature, _publicKey);
        _cache = (DateTime.UtcNow, entries);
        return entries;
    }

    /// <summary>Checks the signature over the exact bytes received, then reads the entries.</summary>
    internal static IReadOnlyList<StoreEntry> ParseCatalog(byte[] json, byte[] signatureBase64, byte[] publicKey)
    {
        byte[] signature;
        try { signature = Convert.FromBase64String(Encoding.ASCII.GetString(signatureBase64).Trim()); }
        catch (FormatException) { signature = []; }
        if (!Ed25519Signing.Verify(publicKey, json, signature))
            throw new StoreException("El catálogo de la tienda no tiene una firma válida: no se instala nada de él.");

        try
        {
            var doc = JsonSerializer.Deserialize<CatalogDocument>(json, ReadOptions);
            // The id becomes a folder name: never anything but a plain id.
            return (doc?.Plugins ?? []).Where(e => PluginIds.Valid().IsMatch(e.Id ?? "")).ToList();
        }
        catch (JsonException ex)
        {
            throw new StoreException($"El catálogo de la tienda no se puede leer: {ex.Message}");
        }
    }

    /// <summary>Verifies and writes one downloaded plugin. True when it replaced an older copy from the store.</summary>
    internal bool InstallZip(StoreEntry entry, byte[] zip)
    {
        if (!Convert.ToHexString(SHA256.HashData(zip)).Equals(entry.Sha256, StringComparison.OrdinalIgnoreCase))
            throw new StoreException($"La descarga de «{entry.Name}» no coincide con el catálogo: no se instala.");

        var state = LoadState();
        switch (StateOf(entry, state, _catalog.Scan()))
        {
            case "conflict":
                throw new StoreException($"Ya tienes un plugin «{entry.Id}» que no es de la tienda; no se toca.");
            case "edited":
                throw new StoreException($"Cambiaste «{entry.Name}» después de instalarlo y no se actualiza para no perder tus cambios. Desinstálalo y vuelve a instalarlo.");
        }

        Directory.CreateDirectory(_catalog.Root);
        // Unpacked next to its final place (same disk: the move is instant). Scan skips it: no plugin.json at its top.
        var temp = Path.Combine(_catalog.Root, ".tienda-" + Guid.NewGuid().ToString("N"));
        var unpacked = Path.Combine(temp, entry.Id);
        try
        {
            Unpack(zip, unpacked);
            var plugin = PluginCatalog.Read(unpacked);
            if (plugin.Id != entry.Id)
                throw new StoreException($"El paquete de «{entry.Name}» trae otro plugin («{plugin.Id}»).");
            // shortcut: actions only; a DLL plugin stays loaded and locked until restart, so it needs an install-on-restart step first.
            if (plugin.IsAssembly)
                throw new StoreException($"«{entry.Name}» trae código (.dll) y la tienda solo instala plugins de acciones.");
            if (plugin.Errors.Count > 0)
                throw new StoreException($"«{entry.Name}» tiene errores: {plugin.Errors[0]}");

            var dir = Path.Combine(_catalog.Root, entry.Id);
            var updated = state.ContainsKey(entry.Id) && Directory.Exists(dir);
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true); // ours and untouched: checked above
            Directory.Move(unpacked, dir);

            state[entry.Id] = new Installed(entry.Version, PluginCatalog.HashOfFolder(dir));
            SaveState(state);
            // New ones start off even if an old flag with this id was left on; an update keeps the owner's choice.
            if (!updated) _features.Set("plugin:" + entry.Id, false);
            return updated;
        }
        finally
        {
            if (Directory.Exists(temp)) Directory.Delete(temp, recursive: true);
        }
    }

    /// <summary>available, installed, update, edited (the owner changed the store's copy) or conflict (a folder that is not ours).</summary>
    private string StateOf(StoreEntry entry, Dictionary<string, Installed> state, IReadOnlyList<LoadedPlugin> installed)
    {
        var dir = Path.Combine(_catalog.Root, entry.Id);
        if (state.TryGetValue(entry.Id, out var mine) && Directory.Exists(dir))
        {
            if (!PluginCatalog.IsUntouched(dir, mine.Hash)) return "edited";
            return PluginCatalog.IsNewer(entry.Version, mine.Version) ? "update" : "installed";
        }
        return Directory.Exists(dir) || installed.Any(p => p.Id == entry.Id) ? "conflict" : "available";
    }

    /// <summary>Extracts into <paramref name="target"/>, refusing paths that leave it and archives that unpack too big.</summary>
    private static void Unpack(byte[] zip, string target)
    {
        var root = Path.GetFullPath(target) + Path.DirectorySeparatorChar;
        using var archive = new ZipArchive(new MemoryStream(zip), ZipArchiveMode.Read);
        if (archive.Entries.Count > MaxFiles) throw new StoreException("El paquete trae demasiados archivos.");

        long total = 0;
        var buffer = new byte[81920];
        foreach (var item in archive.Entries)
        {
            var path = Path.GetFullPath(Path.Combine(root, item.FullName));
            if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase) && path + Path.DirectorySeparatorChar != root)
                throw new StoreException("El paquete intenta escribir fuera de su carpeta: no se instala.");
            if (item.FullName.EndsWith('/') || item.FullName.EndsWith('\\'))
            {
                Directory.CreateDirectory(path);
                continue;
            }

            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            using var input = item.Open();
            using var output = File.Create(path);
            int read;
            // Counted while copying: the sizes in the zip header are the sender's word.
            while ((read = input.Read(buffer)) > 0)
            {
                total += read;
                if (total > MaxUnpackedBytes) throw new StoreException("El paquete descomprimido es demasiado grande.");
                output.Write(buffer, 0, read);
            }
        }
    }

    private static async Task<byte[]> DownloadAsync(Uri url, string what, CancellationToken ct)
    {
        try
        {
            return await Http.GetByteArrayAsync(url, ct);
        }
        catch (HttpRequestException ex)
        {
            throw new StoreException($"No se pudo descargar {what}: {ex.Message}");
        }
        catch (TaskCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new StoreException($"La descarga de {what} tardó demasiado.");
        }
    }

    private Dictionary<string, Installed> LoadState()
    {
        var path = Path.Combine(_catalog.Root, StateFile);
        try
        {
            var saved = File.Exists(path)
                ? JsonSerializer.Deserialize<Dictionary<string, Installed>>(File.ReadAllText(path), ReadOptions)
                : null;
            return new Dictionary<string, Installed>(saved ?? new(), StringComparer.OrdinalIgnoreCase);
        }
        catch (JsonException ex)
        {
            _logger.LogWarning(ex, "Ignoring an unreadable {File}", path);
            return new Dictionary<string, Installed>(StringComparer.OrdinalIgnoreCase);
        }
    }

    private void SaveState(Dictionary<string, Installed> state) =>
        File.WriteAllText(Path.Combine(_catalog.Root, StateFile),
            JsonSerializer.Serialize(state, new JsonSerializerOptions { WriteIndented = true }));

    private static HttpClient CreateHttp()
    {
        // The buffer limit caps every download (catalog and zips).
        var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60), MaxResponseContentBufferSize = MaxDownloadBytes };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("SackDesk/" + (typeof(PluginStore).Assembly.GetName().Version?.ToString(3) ?? "0"));
        return http;
    }

    private sealed record CatalogDocument(int Version, List<StoreEntry>? Plugins);

    private sealed record Installed(string Version, string Hash);
}
