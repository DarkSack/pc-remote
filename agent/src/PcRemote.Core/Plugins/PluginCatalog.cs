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
    /// First run: creates the folder with a README and one example plugin (disabled,
    /// like every plugin until the owner enables it in the panel).
    /// </summary>
    public void EnsureFolder()
    {
        try
        {
            if (System.IO.Directory.Exists(Root)) return;
            System.IO.Directory.CreateDirectory(Root);
            File.WriteAllText(Path.Combine(Root, "LEEME.txt"), Readme);
            var example = Path.Combine(Root, "utilidades-windows");
            System.IO.Directory.CreateDirectory(example);
            File.WriteAllText(Path.Combine(example, "plugin.json"), ExamplePlugin);
            _logger.LogInformation("Created plugins folder at {Root}", Root);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not create the plugins folder {Root}", Root);
        }
    }

    private const string Readme = """
        PC Remote — plugins
        ===================

        Cada carpeta con un plugin.json es un plugin. Aparece en el panel
        (http://localhost:47810) y, cuando lo activas ahí, en la app del móvil.

        Un plugin es una lista de acciones: ejecutar un programa con argumentos
        fijos, o abrir una URL, archivo o carpeta. Los parámetros que se
        escriben en el móvil se validan y cada uno ocupa exactamente un
        argumento: nunca pasan por una consola.

        Mira utilidades-windows\plugin.json como ejemplo y la documentación
        completa en docs/PLUGINS.md del repositorio.
        """;

    private const string ExamplePlugin = """
        {
          "name": "Utilidades de Windows",
          "description": "Ejemplo de plugin: red, papelera y carpetas.",
          "icon": "build",
          "version": "1.0.0",
          "actions": [
            {
              "id": "ipconfig",
              "label": "Ver configuración de red",
              "icon": "lan",
              "run": "ipconfig",
              "args": ["/all"],
              "output": true
            },
            {
              "id": "flush-dns",
              "label": "Vaciar caché DNS",
              "icon": "dns",
              "run": "ipconfig",
              "args": ["/flushdns"],
              "output": true
            },
            {
              "id": "ping",
              "label": "Hacer ping",
              "icon": "network_ping",
              "run": "ping",
              "args": ["-n", "4", "{host}"],
              "params": [
                { "id": "host", "label": "Host o IP", "type": "string", "pattern": "^[A-Za-z0-9.:-]{1,253}$", "default": "1.1.1.1" }
              ],
              "output": true,
              "timeoutSec": 20
            },
            {
              "id": "empty-bin",
              "label": "Vaciar la papelera",
              "icon": "delete",
              "run": "powershell.exe",
              "args": ["-NoProfile", "-NonInteractive", "-Command", "Clear-RecycleBin -Force -ErrorAction SilentlyContinue"],
              "confirm": "¿Vaciar la papelera del PC? No se puede deshacer.",
              "output": true
            },
            {
              "id": "downloads",
              "label": "Abrir Descargas",
              "icon": "folder",
              "open": "%USERPROFILE%\\Downloads"
            },
            {
              "id": "task-manager",
              "label": "Administrador de tareas",
              "icon": "monitoring",
              "run": "taskmgr.exe"
            }
          ]
        }
        """;
}
