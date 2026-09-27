using System.Text.Json;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Plugins;

/// <summary>A plugin folder after reading and validating its plugin.json.</summary>
public sealed record LoadedPlugin(
    string Id,
    string Directory,
    PluginManifest Manifest,
    /// <summary>Problems found; a plugin with errors is listed but cannot run.</summary>
    IReadOnlyList<string> Errors)
{
    public bool IsAssembly => !string.IsNullOrWhiteSpace(Manifest.Assembly);
    public string FeatureKey => "plugin:" + Id;
}

/// <summary>
/// Reads the plugins folder. Cheap (a handful of small JSON files), so it is
/// simply re-read whenever the list is asked for: dropping a folder in or
/// editing a plugin.json shows up without restarting the agent.
/// </summary>
public sealed class PluginCatalog
{
    private static readonly JsonSerializerOptions ReadOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    private readonly ILogger<PluginCatalog> _logger;

    public string Root { get; }

    public PluginCatalog(AgentSettings settings, ILogger<PluginCatalog> logger)
        : this(settings.Storage.ResolvedPluginsPath, logger) { }

    internal PluginCatalog(string root, ILogger<PluginCatalog> logger)
    {
        Root = root;
        _logger = logger;
    }

    public IReadOnlyList<LoadedPlugin> Scan()
    {
        if (!System.IO.Directory.Exists(Root)) return Array.Empty<LoadedPlugin>();

        var result = new List<LoadedPlugin>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var dir in System.IO.Directory.GetDirectories(Root).OrderBy(d => d, StringComparer.OrdinalIgnoreCase))
        {
            var file = Path.Combine(dir, "plugin.json");
            if (!File.Exists(file)) continue;

            var folderId = PluginIds.FromFolder(Path.GetFileName(dir));
            PluginManifest manifest;
            try
            {
                manifest = JsonSerializer.Deserialize<PluginManifest>(File.ReadAllText(file), ReadOptions) ?? new PluginManifest();
            }
            catch (Exception ex)
            {
                result.Add(new LoadedPlugin(folderId, dir, new PluginManifest { Name = Path.GetFileName(dir) },
                    new[] { $"plugin.json no es JSON válido: {ex.Message}" }));
                continue;
            }

            var id = string.IsNullOrWhiteSpace(manifest.Id) ? folderId : manifest.Id.Trim().ToLowerInvariant();
            var errors = Validate(manifest, id, dir);
            if (!seen.Add(id)) errors.Add($"Hay otro plugin con el id '{id}'.");
            manifest.Name = string.IsNullOrWhiteSpace(manifest.Name) ? Path.GetFileName(dir) : manifest.Name.Trim();
            result.Add(new LoadedPlugin(id, dir, manifest, errors));
        }
        return result;
    }

    public LoadedPlugin? Find(string id) =>
        Scan().FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase));

    internal static List<string> Validate(PluginManifest m, string id, string dir)
    {
        var errors = new List<string>();
        if (!PluginIds.Valid().IsMatch(id))
            errors.Add($"Id '{id}' no válido: minúsculas, números, '-' o '_' (máx. 40).");

        if (!string.IsNullOrWhiteSpace(m.Assembly))
        {
            var dll = Path.GetFullPath(Path.Combine(dir, m.Assembly));
            if (!dll.StartsWith(Path.GetFullPath(dir), StringComparison.OrdinalIgnoreCase) || !File.Exists(dll))
                errors.Add($"No se encuentra '{m.Assembly}' dentro de la carpeta del plugin.");
        }

        var actionIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var a in m.Actions)
        {
            var label = string.IsNullOrWhiteSpace(a.Id) ? "(sin id)" : a.Id;
            if (!PluginIds.Valid().IsMatch(a.Id ?? "")) errors.Add($"Acción {label}: id no válido.");
            else if (!actionIds.Add(a.Id!)) errors.Add($"Acción {label}: id repetido.");

            var hasRun = !string.IsNullOrWhiteSpace(a.Run);
            var hasOpen = !string.IsNullOrWhiteSpace(a.Open);
            if (hasRun == hasOpen) errors.Add($"Acción {label}: necesita \"run\" o \"open\" (solo uno).");

            var paramIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var p in a.Params)
            {
                if (!PluginIds.Placeholder().IsMatch("{" + p.Id + "}") || !paramIds.Add(p.Id))
                    errors.Add($"Acción {label}: parámetro '{p.Id}' con id no válido o repetido.");
                if (p.Type is not ("string" or "number" or "bool" or "choice"))
                    errors.Add($"Acción {label}: tipo '{p.Type}' desconocido (string, number, bool, choice).");
                if (p.Type == "choice" && (p.Options is null || p.Options.Count == 0))
                    errors.Add($"Acción {label}: el parámetro '{p.Id}' es choice y no tiene options.");
                if (p.Pattern is not null)
                {
                    try { _ = new System.Text.RegularExpressions.Regex(p.Pattern); }
                    catch (ArgumentException) { errors.Add($"Acción {label}: pattern de '{p.Id}' no es una regex válida."); }
                }
            }

            // Placeholders may only go in args (one value = one argument) and in the confirm text.
            // In "run" or "open" a phone-typed value would pick the program or the URL.
            foreach (var text in new[] { a.Run, a.Open })
            {
                if (text is not null && PluginIds.Placeholder().IsMatch(text))
                    errors.Add($"Acción {label}: los parámetros solo pueden ir en \"args\".");
            }
            foreach (var arg in a.Args)
            {
                foreach (System.Text.RegularExpressions.Match match in PluginIds.Placeholder().Matches(arg))
                {
                    if (!paramIds.Contains(match.Groups[1].Value))
                        errors.Add($"Acción {label}: {{{match.Groups[1].Value}}} no es un parámetro declarado.");
                }
            }
        }
        return errors;
    }

    /// <summary>
    /// Creates the folder (with a README) if needed and copies out the plugins that ship
    /// with the agent. They arrive disabled, like every plugin until the owner enables
    /// it in the panel.
    /// </summary>
    public void EnsureFolder()
    {
        try
        {
            System.IO.Directory.CreateDirectory(Root);
            var readme = Path.Combine(Root, "LEEME.txt");
            if (!File.Exists(readme)) File.WriteAllText(readme, Readme);
            InstallBundled(BundledPlugins.All);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not prepare the plugins folder {Root}", Root);
        }
    }

    private const string StateFile = ".incluidos.json";

    /// <summary>What was copied out of the agent, per plugin id. A null hash means "not ours: never touch".</summary>
    private sealed record InstalledBundle(string? Version, string? Hash);

    /// <summary>
    /// The folder belongs to the owner, so a bundled plugin is only written:
    ///  - the first time the agent sees it (and no folder with that name exists);
    ///  - on a newer version, if the installed files are still exactly what we wrote.
    /// Deleting a bundled plugin's folder is respected: it does not come back.
    /// </summary>
    internal void InstallBundled(IEnumerable<BundledPlugin> bundled)
    {
        var statePath = Path.Combine(Root, StateFile);
        Dictionary<string, InstalledBundle> state;
        try
        {
            state = File.Exists(statePath)
                ? JsonSerializer.Deserialize<Dictionary<string, InstalledBundle>>(File.ReadAllText(statePath), ReadOptions) ?? new()
                : new();
        }
        catch (JsonException) { state = new(); }
        state = new Dictionary<string, InstalledBundle>(state, StringComparer.OrdinalIgnoreCase);

        var changed = false;
        foreach (var plugin in bundled)
        {
            var dir = Path.Combine(Root, plugin.Id);
            if (!state.TryGetValue(plugin.Id, out var known))
            {
                if (System.IO.Directory.Exists(dir))
                {
                    // Already there before the agent tracked it (the owner's own, or an old example).
                    state[plugin.Id] = new InstalledBundle(null, null);
                }
                else
                {
                    Write(dir, plugin);
                    state[plugin.Id] = new InstalledBundle(plugin.Version, plugin.Hash);
                    _logger.LogInformation("Installed bundled plugin {Id} {Version}", plugin.Id, plugin.Version);
                }
                changed = true;
                continue;
            }

            if (known.Hash is null || !System.IO.Directory.Exists(dir) || !IsNewer(plugin.Version, known.Version)) continue;

            if (!IsUntouched(dir, known.Hash))
            {
                _logger.LogInformation("Bundled plugin {Id} was edited; keeping the owner's copy", plugin.Id);
                continue;
            }

            // Untouched means every file there is ours: the ones the new version dropped can go.
            foreach (var file in System.IO.Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
            {
                if (!plugin.Files.ContainsKey(Path.GetRelativePath(dir, file).Replace('\\', '/'))) File.Delete(file);
            }
            Write(dir, plugin);
            state[plugin.Id] = new InstalledBundle(plugin.Version, plugin.Hash);
            _logger.LogInformation("Updated bundled plugin {Id} {Old} → {New}", plugin.Id, known.Version, plugin.Version);
            changed = true;
        }

        if (changed)
            File.WriteAllText(statePath, JsonSerializer.Serialize(state, new JsonSerializerOptions { WriteIndented = true }));
    }

    /// <summary>
    /// The folder holds exactly the files we wrote, unchanged. Editing, adding or removing
    /// any file makes it the owner's.
    /// </summary>
    private static bool IsUntouched(string dir, string recorded)
    {
        var files = System.IO.Directory.GetFiles(dir, "*", SearchOption.AllDirectories)
            .Select(f => Path.GetRelativePath(dir, f).Replace('\\', '/'))
            .ToList();
        return string.Equals(BundledPlugin.HashOf(files, p => File.ReadAllBytes(Path.Combine(dir, p))), recorded, StringComparison.Ordinal);
    }

    private static void Write(string dir, BundledPlugin plugin)
    {
        foreach (var (path, bytes) in plugin.Files)
        {
            var file = Path.Combine(dir, path);
            System.IO.Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            File.WriteAllBytes(file, bytes);
        }
    }

    private static bool IsNewer(string? candidate, string? installed) =>
        Version.TryParse(candidate, out var c) && (!Version.TryParse(installed, out var i) || c > i);

    private const string Readme = """
        PC Remote — plugins
        ===================

        Cada carpeta con un plugin.json es un plugin. Aparece en el panel
        (http://localhost:47810) y, cuando lo activas ahí, en la app del móvil.

        Un plugin es una lista de acciones: ejecutar un programa con argumentos
        fijos, o abrir una URL, archivo o carpeta. Los parámetros que se
        escriben en el móvil se validan y cada uno ocupa exactamente un
        argumento: nunca pasan por una consola.

        PC Remote trae una colección de plugins (pantalla, energía, audio,
        red, limpieza, winget…) que se copian aquí desactivados. Puedes
        editarlos: una carpeta que hayas tocado ya no se actualiza sola. Si
        borras una, no vuelve a aparecer.

        Mira cualquiera de ellos como ejemplo y la documentación completa en
        docs/PLUGINS.md del repositorio.
        """;
}
