using System.Text.Json;

namespace PcRemote.Core.Plugins;

/// <summary>
/// Which plugins the user turned on or off, in <c>plugins.json</c>. Only the
/// choices that differ from a plugin's default are meaningful, but every toggle
/// is stored so a later change of default does not flip a user's choice.
///
/// A plain JSON file rather than a SQLite table on purpose: external plugins are
/// decided before the host (and the database) exists — a disabled external DLL
/// must never be loaded at all.
/// </summary>
public sealed class PluginState
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly string _path;
    private readonly object _lock = new();
    private Dictionary<string, bool> _overrides;

    public PluginState(string path)
    {
        _path = path;
        _overrides = Load(path);
    }

    /// <summary>The user's choice for <paramref name="key"/>, or null if they never touched it.</summary>
    public bool? Get(string key)
    {
        lock (_lock) return _overrides.TryGetValue(key, out var v) ? v : null;
    }

    public void Set(string key, bool enabled)
    {
        lock (_lock)
        {
            _overrides[key] = enabled;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
                var tmp = _path + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(new StateFile { Overrides = _overrides }, Json));
                File.Move(tmp, _path, overwrite: true);
            }
            catch (IOException) { /* keeps working in memory; the panel still shows the change */ }
            catch (UnauthorizedAccessException) { }
        }
    }

    private static Dictionary<string, bool> Load(string path)
    {
        try
        {
            if (!File.Exists(path)) return new(StringComparer.OrdinalIgnoreCase);
            var file = JsonSerializer.Deserialize<StateFile>(File.ReadAllText(path));
            return new(file?.Overrides ?? new(), StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // A corrupt file must not stop the agent: back to defaults.
            return new(StringComparer.OrdinalIgnoreCase);
        }
    }

    private sealed class StateFile
    {
        public Dictionary<string, bool> Overrides { get; set; } = new();
    }
}
