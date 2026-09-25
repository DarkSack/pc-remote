namespace PcRemote.Core.Router;

/// <summary>
/// Optional: how a module presents itself as a plugin (panel + phone). Modules
/// that do not implement it still work; they show up with their domain as name,
/// enabled by default.
/// </summary>
public interface IPluginMetadata
{
    /// <summary>Human name, e.g. "Portapapeles".</summary>
    string DisplayName { get; }

    /// <summary>One sentence: what the phone can do with it.</summary>
    string Description { get; }

    /// <summary>Grouping for the panel and the phone: see <see cref="PluginCategories"/>.</summary>
    string Category => PluginCategories.Other;

    /// <summary>State on a fresh install. Anything that runs arbitrary code should be false.</summary>
    bool EnabledByDefault => true;

    /// <summary>False for the protocol's own domains (ping, plugins).</summary>
    bool CanDisable => true;

    /// <summary>Shown with a warning in the panel (e.g. the terminal).</summary>
    bool Sensitive => false;
}

public static class PluginCategories
{
    public const string System   = "system";
    public const string Control  = "control";
    public const string Tools    = "tools";
    public const string Advanced = "advanced";
    public const string Other    = "other";
}
