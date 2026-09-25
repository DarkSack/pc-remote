using System.Text.Json;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Plugins;

/// <summary>
/// A module the PC owner switches on (or off) from the panel. Terminal, for
/// instance, runs arbitrary commands: it must never be on just because a phone
/// is paired.
///
/// Only the panel (loopback) changes these flags. The phone sees them but cannot
/// flip them: a stolen phone must not be able to turn the terminal on.
/// </summary>
public interface IOptionalModule
{
    /// <summary>Name shown in the panel and the app.</summary>
    string FeatureName { get; }

    string FeatureDescription { get; }

    /// <summary>Icon key the app maps to a Material icon (terminal, folder, extension…).</summary>
    string FeatureIcon { get; }

    bool EnabledByDefault { get; }
}

/// <summary>
/// Persists which optional features and plugins are enabled, in
/// <c>%LOCALAPPDATA%\PcRemote\features.json</c>. Keys are module domains
/// (<c>terminal</c>, <c>files</c>) and plugin ids prefixed with <c>plugin:</c>.
/// </summary>
public sealed class FeatureStore
{
    private readonly string _path;
    private readonly ILogger<FeatureStore> _logger;
    private readonly object _lock = new();
    private Dictionary<string, bool> _flags;

    public event EventHandler? Changed;

    public FeatureStore(AgentSettings settings, ILogger<FeatureStore> logger)
        : this(Path.Combine(settings.Storage.DataDirectory, "features.json"), logger) { }

    internal FeatureStore(string path, ILogger<FeatureStore> logger)
    {
        _path = path;
        _logger = logger;
        _flags = Load();
    }

    /// <summary>The stored flag, or <paramref name="defaultValue"/> when the owner never touched it.</summary>
    public bool IsEnabled(string id, bool defaultValue)
    {
        lock (_lock) return _flags.TryGetValue(id, out var v) ? v : defaultValue;
    }

    public void Set(string id, bool enabled)
    {
        lock (_lock)
        {
            if (_flags.TryGetValue(id, out var v) && v == enabled) return;
            _flags = new Dictionary<string, bool>(_flags, StringComparer.OrdinalIgnoreCase) { [id] = enabled };
            Save(_flags);
        }
        _logger.LogInformation("Feature {Id} {State}", id, enabled ? "enabled" : "disabled");
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private Dictionary<string, bool> Load()
    {
        try
        {
            if (File.Exists(_path))
            {
                var map = JsonSerializer.Deserialize<Dictionary<string, bool>>(File.ReadAllText(_path));
                if (map is not null) return new Dictionary<string, bool>(map, StringComparer.OrdinalIgnoreCase);
            }
        }
        catch (Exception ex)
        {
            // A broken file must not take the agent down; every feature falls back to its default.
            _logger.LogWarning(ex, "Could not read {Path}; using defaults", _path);
        }
        return new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
    }

    private void Save(Dictionary<string, bool> flags)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var tmp = _path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(flags, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(tmp, _path, overwrite: true);
    }
}
