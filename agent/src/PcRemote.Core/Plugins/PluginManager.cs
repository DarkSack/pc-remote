using Microsoft.Extensions.Logging;
using PcRemote.Core.Router;

namespace PcRemote.Core.Plugins;

// ══════════════════════════════════════════════════════════════
// Plugins: every domain the agent serves is a plugin that can be
// switched on and off from the panel (never from the phone: turning
// on the terminal must take someone sitting at the PC).
//
//   - Built-in modules toggle live. Their key is the domain.
//   - External DLLs (plugins folder) are keyed "ext:<file>", are off
//     until enabled in the panel, and load on the next start: a DLL
//     that is off is never loaded, so none of its code runs.
//
// The router asks IsEnabled before every command; disabled domains
// answer PLUGIN_DISABLED.
// ══════════════════════════════════════════════════════════════
public sealed class PluginManager
{
    /// <summary>The protocol cannot work without these.</summary>
    private static readonly HashSet<string> Required = new(StringComparer.OrdinalIgnoreCase) { "ping", "plugins" };

    private readonly PluginState _state;
    private readonly PluginRegistry _registry;
    private readonly ILogger<PluginManager> _logger;

    /// <summary>domain → module, filled by the router once modules exist.</summary>
    private IReadOnlyDictionary<string, ICommandModule> _modules = new Dictionary<string, ICommandModule>();

    public event EventHandler? Changed;

    public PluginManager(PluginState state, PluginRegistry registry, ILogger<PluginManager> logger)
    {
        _state = state;
        _registry = registry;
        _logger = logger;
    }

    internal void Attach(IReadOnlyDictionary<string, ICommandModule> modules) => _modules = modules;

    public bool IsEnabled(string domain)
    {
        if (Required.Contains(domain)) return true;
        if (!_modules.TryGetValue(domain, out var module)) return false;
        return IsEnabled(module);
    }

    private bool IsEnabled(ICommandModule module)
    {
        if (Required.Contains(module.Domain)) return true;
        var meta = module as IPluginMetadata;
        if (meta is { CanDisable: false }) return true;
        return _state.Get(KeyOf(module)) ?? meta?.EnabledByDefault ?? true;
    }

    private string KeyOf(ICommandModule module) => _registry.SourceOf(module)?.Key ?? module.Domain.ToLowerInvariant();

    /// <summary>
    /// Turns a plugin on or off. <paramref name="id"/> is a domain or an "ext:" key.
    /// Returns false when the id is unknown or cannot be disabled.
    /// </summary>
    public bool SetEnabled(string id, bool enabled)
    {
        if (id.StartsWith("ext:", StringComparison.OrdinalIgnoreCase))
        {
            if (_registry.External.All(p => !p.Key.Equals(id, StringComparison.OrdinalIgnoreCase))) return false;
            _state.Set(id, enabled);
        }
        else
        {
            if (!_modules.TryGetValue(id, out var module)) return false;
            if (Required.Contains(module.Domain) || module is IPluginMetadata { CanDisable: false }) return false;
            _state.Set(KeyOf(module), enabled);
        }
        _logger.LogInformation("Plugin {Id} {State}", id, enabled ? "enabled" : "disabled");
        Changed?.Invoke(this, EventArgs.Empty);
        return true;
    }

    /// <summary>Everything the agent knows about, for the panel and <c>plugins.list</c>.</summary>
    public IReadOnlyList<PluginDescriptor> Describe()
    {
        var list = new List<PluginDescriptor>();
        foreach (var module in _modules.Values.OrderBy(m => m.Domain, StringComparer.OrdinalIgnoreCase))
        {
            var meta = module as IPluginMetadata;
            var ext = _registry.SourceOf(module);
            list.Add(new PluginDescriptor(
                Id:          ext?.Key ?? module.Domain.ToLowerInvariant(),
                Domain:      module.Domain,
                Name:        meta?.DisplayName ?? module.Domain,
                Description: meta?.Description ?? "",
                Category:    meta?.Category ?? PluginCategories.Other,
                Version:     ext?.Version ?? module.GetType().Assembly.GetName().Version?.ToString(3) ?? "",
                BuiltIn:     ext is null,
                Enabled:     IsEnabled(module),
                CanDisable:  !Required.Contains(module.Domain) && meta?.CanDisable != false,
                Sensitive:   meta?.Sensitive ?? false,
                Loaded:      true,
                RestartRequired: ext is not null && _state.Get(ext.Key) == false,
                Actions:     module.Commands.Select(c => c.Action).ToList(),
                Error:       null));
        }

        // External DLLs that are off (not loaded) or failed to load.
        foreach (var ext in _registry.External.Where(e => !e.Loaded))
        {
            var wanted = _state.Get(ext.Key) ?? false;
            list.Add(new PluginDescriptor(
                Id: ext.Key, Domain: null, Name: ext.FileName,
                Description: ext.Error ?? "Plugin externo. Actívalo y reinicia el agente para cargarlo.",
                Category: PluginCategories.Other, Version: "", BuiltIn: false,
                Enabled: wanted, CanDisable: true, Sensitive: true, Loaded: false,
                RestartRequired: wanted, Actions: Array.Empty<string>(), Error: ext.Error));
        }
        return list;
    }

    public string PluginsDirectory => _registry.PluginsDirectory;
}

public sealed record PluginDescriptor(
    string Id,
    string? Domain,
    string Name,
    string Description,
    string Category,
    string Version,
    bool BuiltIn,
    bool Enabled,
    bool CanDisable,
    bool Sensitive,
    bool Loaded,
    bool RestartRequired,
    IReadOnlyList<string> Actions,
    string? Error);
