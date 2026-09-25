using Microsoft.Extensions.Configuration;

namespace PcRemote.Core.Config;

/// <summary>
/// Where the agent reads its settings, lowest priority first:
///   1. appsettings.defaults.json, embedded in the agent — the exe works alone;
///   2. appsettings.json next to the exe (optional);
///   3. %LOCALAPPDATA%\PcRemote\appsettings.json (optional; survives updates of the exe).
/// Each file only needs the keys it changes.
/// </summary>
public static class AgentConfiguration
{
    public static string UserSettingsPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PcRemote", "appsettings.json");

    public static IConfigurationBuilder AddSources(IConfigurationBuilder builder)
    {
        var asm = typeof(AgentConfiguration).Assembly;
        var name = asm.GetManifestResourceNames().Single(n => n.EndsWith("appsettings.defaults.json", StringComparison.Ordinal));
        // The stream must stay open until Build(); the configuration provider reads it then.
        builder.AddJsonStream(asm.GetManifestResourceStream(name)!);
        builder.AddJsonFile(Path.Combine(AppContext.BaseDirectory, "appsettings.json"), optional: true, reloadOnChange: false);
        builder.AddJsonFile(UserSettingsPath, optional: true, reloadOnChange: false);
        return builder;
    }

    public static IConfiguration Build() => AddSources(new ConfigurationBuilder()).Build();
}
