using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace PcRemote.Core.Plugins;

// ══════════════════════════════════════════════════════════════
// plugin.json — see docs/PLUGINS.md for the full format.
//
// A plugin is a folder under %LOCALAPPDATA%\PcRemote\plugins with a
// plugin.json. Two kinds:
//
//   - Actions (no code): a list of buttons. Each one runs a program with
//     fixed arguments, or opens a URL / file / folder. Parameters typed on
//     the phone are validated here and each one fills exactly one argument
//     (no shell in between), so a value can never become a second command.
//   - Assembly: "assembly": "MyPlugin.dll" with classes implementing
//     ICommandModule, loaded at startup when the plugin is enabled.
// ══════════════════════════════════════════════════════════════

public sealed class PluginManifest
{
    [JsonPropertyName("id")]          public string? Id { get; set; }
    [JsonPropertyName("name")]        public string? Name { get; set; }
    [JsonPropertyName("description")] public string? Description { get; set; }
    [JsonPropertyName("icon")]        public string? Icon { get; set; }
    [JsonPropertyName("version")]     public string? Version { get; set; }
    [JsonPropertyName("author")]      public string? Author { get; set; }
    /// <summary>DLL (relative to the plugin folder) with ICommandModule implementations.</summary>
    [JsonPropertyName("assembly")]    public string? Assembly { get; set; }
    [JsonPropertyName("actions")]     public List<PluginAction> Actions { get; set; } = new();
}

public sealed class PluginAction
{
    [JsonPropertyName("id")]          public string Id { get; set; } = "";
    [JsonPropertyName("label")]       public string? Label { get; set; }
    [JsonPropertyName("description")] public string? Description { get; set; }
    [JsonPropertyName("icon")]        public string? Icon { get; set; }

    /// <summary>Program to run (a path, a name on PATH, or a file in the plugin folder).</summary>
    [JsonPropertyName("run")]         public string? Run { get; set; }
    [JsonPropertyName("args")]        public List<string> Args { get; set; } = new();

    /// <summary>URL, file or folder to open with its default app (instead of <see cref="Run"/>).</summary>
    [JsonPropertyName("open")]        public string? Open { get; set; }

    [JsonPropertyName("params")]      public List<PluginParam> Params { get; set; } = new();

    /// <summary>Question the app asks before running. May use {param} placeholders.</summary>
    [JsonPropertyName("confirm")]     public string? Confirm { get; set; }

    /// <summary>Wait for the program and send its output back to the phone.</summary>
    [JsonPropertyName("output")]      public bool Output { get; set; }

    [JsonPropertyName("timeoutSec")]  public int TimeoutSec { get; set; } = 30;

    /// <summary>"oem" (console default) or "utf8".</summary>
    [JsonPropertyName("encoding")]    public string? Encoding { get; set; }
}

public sealed class PluginParam
{
    [JsonPropertyName("id")]       public string Id { get; set; } = "";
    [JsonPropertyName("label")]    public string? Label { get; set; }
    /// <summary>string, number, bool or choice.</summary>
    [JsonPropertyName("type")]     public string Type { get; set; } = "string";
    [JsonPropertyName("required")] public bool Required { get; set; } = true;
    [JsonPropertyName("default")]  public JsonElement? Default { get; set; }
    [JsonPropertyName("options")]  public List<string>? Options { get; set; }
    /// <summary>Regex the whole value must match (string).</summary>
    [JsonPropertyName("pattern")]  public string? Pattern { get; set; }
    [JsonPropertyName("min")]      public double? Min { get; set; }
    [JsonPropertyName("max")]      public double? Max { get; set; }
    [JsonPropertyName("maxLength")] public int MaxLength { get; set; } = 256;
    [JsonPropertyName("placeholder")] public string? Placeholder { get; set; }
}

public static partial class PluginIds
{
    [GeneratedRegex("^[a-z0-9][a-z0-9_-]{0,39}$")]
    public static partial Regex Valid();

    [GeneratedRegex(@"\{([A-Za-z0-9_-]+)\}")]
    public static partial Regex Placeholder();

    /// <summary>Folder name → id: lower case, anything odd becomes '-'.</summary>
    public static string FromFolder(string folder)
    {
        var chars = folder.ToLowerInvariant().Select(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' ? c : '-').ToArray();
        var id = new string(chars).Trim('-', '_');
        return id.Length == 0 ? "plugin" : id.Length > 40 ? id[..40] : id;
    }
}
