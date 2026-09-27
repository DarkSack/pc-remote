using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace PcRemote.Core.Plugins;

/// <summary>A plugin that ships inside the agent: its folder's files, by path relative to the folder.</summary>
internal sealed record BundledPlugin(string Id, string? Version, IReadOnlyDictionary<string, byte[]> Files)
{
    /// <summary>Of exactly these files, to tell later whether the owner edited the installed copy.</summary>
    public string Hash => HashOf(Files.Keys, path => Files[path]);

    internal static string HashOf(IEnumerable<string> paths, Func<string, byte[]?> read)
    {
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var path in paths.OrderBy(p => p, StringComparer.Ordinal))
        {
            var bytes = read(path);
            if (bytes is null) return "";
            sha.AppendData(System.Text.Encoding.UTF8.GetBytes(path + "\0"));
            sha.AppendData(bytes);
        }
        return Convert.ToHexString(sha.GetHashAndReset());
    }
}

/// <summary>
/// The plugins in agent/plugins of the repository, embedded in this assembly as
/// "bundled/&lt;id&gt;/&lt;file&gt;" (see PcRemote.Core.csproj). The single-file exe
/// carries them; <see cref="PluginCatalog.EnsureFolder"/> copies them out.
/// </summary>
internal static class BundledPlugins
{
    private const string Prefix = "bundled/";

    public static IReadOnlyList<BundledPlugin> All { get; } = Load(typeof(BundledPlugins).Assembly);

    internal static IReadOnlyList<BundledPlugin> Load(Assembly assembly)
    {
        var byId = new Dictionary<string, Dictionary<string, byte[]>>(StringComparer.OrdinalIgnoreCase);
        foreach (var name in assembly.GetManifestResourceNames())
        {
            // %(RecursiveDir) keeps Windows separators; normalise them.
            var path = name.Replace('\\', '/');
            if (!path.StartsWith(Prefix, StringComparison.Ordinal)) continue;
            var rest = path[Prefix.Length..];
            var slash = rest.IndexOf('/');
            if (slash <= 0) continue;

            using var stream = assembly.GetManifestResourceStream(name)!;
            using var copy = new MemoryStream();
            stream.CopyTo(copy);

            var id = rest[..slash];
            if (!byId.TryGetValue(id, out var files)) byId[id] = files = new(StringComparer.Ordinal);
            files[rest[(slash + 1)..]] = copy.ToArray();
        }

        return byId
            .Where(kv => kv.Value.ContainsKey("plugin.json"))
            .Select(kv => new BundledPlugin(kv.Key, ReadVersion(kv.Value["plugin.json"]), kv.Value))
            .OrderBy(p => p.Id, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static string? ReadVersion(byte[] json)
    {
        try
        {
            var body = json.AsMemory();
            if (body.Span.StartsWith((ReadOnlySpan<byte>)[0xEF, 0xBB, 0xBF])) body = body[3..];
            using var doc = JsonDocument.Parse(body, new JsonDocumentOptions { CommentHandling = JsonCommentHandling.Skip, AllowTrailingCommas = true });
            return doc.RootElement.TryGetProperty("version", out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
        }
        catch (JsonException) { return null; }
    }
}
