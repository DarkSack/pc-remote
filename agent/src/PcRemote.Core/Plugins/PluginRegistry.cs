using System.Reflection;
using PcRemote.Core.Router;

namespace PcRemote.Core.Plugins;

/// <summary>
/// Where every module came from, decided once at startup: built into the agent,
/// or an external DLL from the plugins folder. External DLLs that are disabled
/// are listed here too, but never loaded.
/// </summary>
public sealed class PluginRegistry
{
    private readonly Dictionary<Type, ExternalPlugin> _externalByType = new();
    private readonly List<ExternalPlugin> _external = new();

    public IReadOnlyList<ExternalPlugin> External => _external;

    public string PluginsDirectory { get; }

    public PluginRegistry(string pluginsDirectory) => PluginsDirectory = pluginsDirectory;

    internal void AddExternal(ExternalPlugin plugin) => _external.Add(plugin);

    internal void MapType(Type moduleType, ExternalPlugin plugin) => _externalByType[moduleType] = plugin;

    /// <summary>The external plugin a module type was loaded from; null for built-in modules.</summary>
    public ExternalPlugin? SourceOf(ICommandModule module) =>
        _externalByType.TryGetValue(module.GetType(), out var p) ? p : null;
}

/// <summary>One DLL in the plugins folder.</summary>
public sealed class ExternalPlugin
{
    public required string Key { get; init; }      // "ext:<file name>"
    public required string FileName { get; init; }
    public required string Path { get; init; }
    public bool Loaded { get; set; }
    public string? Error { get; set; }
    public Assembly? Assembly { get; set; }
    public string? Version => Assembly?.GetName().Version?.ToString(3);
}
