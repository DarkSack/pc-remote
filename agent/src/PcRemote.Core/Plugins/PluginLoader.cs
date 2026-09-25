using System.Reflection;
using Microsoft.Extensions.Logging;

namespace PcRemote.Core.Plugins;

/// <summary>
/// Finds the DLLs in the plugins folder and loads the ones the user enabled.
/// Runs before the host is built, so it logs through a logger it is handed.
/// </summary>
internal static class PluginLoader
{
    public static PluginRegistry Discover(string directory, PluginState state, ILogger logger)
    {
        var registry = new PluginRegistry(directory);
        try { Directory.CreateDirectory(directory); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            logger.LogWarning("Plugins folder {Dir} is not usable: {Msg}", directory, ex.Message);
            return registry;
        }

        // One level deep: "plugins/Foo.dll" or "plugins/Foo/Foo.dll" (a plugin with
        // its own dependencies next to it). Only the DLL named like its folder is the
        // plugin; the others are its dependencies, resolved by LoadFrom.
        var candidates = Directory.GetFiles(directory, "*.dll")
            .Concat(Directory.GetDirectories(directory)
                .Select(d => Path.Combine(d, Path.GetFileName(d) + ".dll"))
                .Where(File.Exists));

        foreach (var path in candidates)
        {
            var name = Path.GetFileNameWithoutExtension(path);
            var plugin = new ExternalPlugin { Key = "ext:" + name, FileName = name, Path = path };
            registry.AddExternal(plugin);

            if (state.Get(plugin.Key) != true)
            {
                logger.LogInformation("External plugin {Name} found but disabled; not loaded", name);
                continue;
            }
            try
            {
                plugin.Assembly = Assembly.LoadFrom(path);
                plugin.Loaded = true;
                logger.LogInformation("Loaded external plugin {Name} from {Path}", name, path);
            }
            catch (Exception ex)
            {
                plugin.Error = $"No se pudo cargar: {ex.Message}";
                logger.LogWarning(ex, "Could not load external plugin {Path}", path);
            }
        }
        return registry;
    }
}
