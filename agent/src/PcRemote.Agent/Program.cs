using System.Reflection;
using System.Runtime.Versioning;
using System.Windows.Forms;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using PcRemote.Agent.Tray;
using PcRemote.Core;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Panel;
using Serilog;

namespace PcRemote.Agent;

/// <summary>
/// Entry point of the PC Remote agent (Windows tray application).
///
/// Ships as ONE executable (see agent/publish.ps1): the default settings are
/// embedded, and appsettings.json is only an optional override, next to the
/// exe or in %LOCALAPPDATA%\PcRemote.
/// </summary>
[SupportedOSPlatform("windows")]
internal static class Program
{
    /// <summary>Per user: two agents of the same user would fight over the port.</summary>
    private const string SingleInstanceMutex = @"Local\PcRemote.Agent";

    [STAThread]
    private static int Main()
    {
        ApplicationConfiguration.Initialize();

        using var mutex = new Mutex(initiallyOwned: true, SingleInstanceMutex, out var firstInstance);
        var configuration = BuildConfiguration();

        if (!firstInstance)
        {
            // Double-clicking the exe again is the obvious "where is it?" gesture: show the panel.
            OpenPanel(configuration);
            return 0;
        }

        // Serilog first: AgentHost hands Log.Logger to the host's logging, so it has
        // to be the real logger before Build(), not the silent default.
        Log.Logger = new LoggerConfiguration()
            // Sinks named explicitly: a single-file build has no DLLs for Serilog to scan.
            .ReadFrom.Configuration(configuration, new Serilog.Settings.Configuration.ConfigurationReaderOptions(
                typeof(Serilog.FileLoggerConfigurationExtensions).Assembly,
                typeof(Serilog.ConsoleLoggerConfigurationExtensions).Assembly))
            .WriteTo.Sink(InMemoryLogSink.Instance)
            .CreateLogger();

        try
        {
            Log.Information("Agent starting…");

            var host = AgentHost.Build(configuration, ModuleAssemblies());

            // Start the .NET Host in the background; tray UI runs on the STA main thread.
            host.StartAsync().GetAwaiter().GetResult();

            // Nothing paired yet: this is the first run, so show where the QR is.
            if (host.Services.GetRequiredService<DeviceRepository>().List().Count == 0)
                OpenPanel(configuration);

            using var tray = new TrayApplicationContext(host.Services, () =>
            {
                // Task.Run on purpose. This runs on the UI thread, inside the WinForms
                // SynchronizationContext. Blocking it with GetResult() while the hosted
                // services await (WebSocketServer.StopAsync awaits Kestrel without
                // ConfigureAwait(false)) would post their continuations to the very
                // thread that is blocked waiting for them: "Exit" would hang forever.
                try { Task.Run(() => host.StopAsync(TimeSpan.FromSeconds(5))).GetAwaiter().GetResult(); }
                catch { /* best effort */ }
            });

            Application.Run(tray);
            return 0;
        }
        catch (Exception ex)
        {
            Log.Fatal(ex, "Agent crashed");
            MessageBox.Show(ex.Message, "PC Remote — error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        finally
        {
            Log.CloseAndFlush();
        }
    }

    /// <summary>
    /// Embedded defaults, then appsettings.json next to the exe, then the one in
    /// %LOCALAPPDATA%\PcRemote. Later files override earlier ones key by key.
    /// </summary>
    private static IConfiguration BuildConfiguration()
    {
        var builder = new ConfigurationBuilder();
        var embedded = typeof(Program).Assembly.GetManifestResourceStream("PcRemote.Agent.appsettings.json");
        if (embedded is not null) builder.AddJsonStream(embedded);
        builder.AddJsonFile(Path.Combine(AppContext.BaseDirectory, "appsettings.json"), optional: true, reloadOnChange: false);
        builder.AddJsonFile(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PcRemote", "appsettings.json"),
            optional: true, reloadOnChange: false);
        return builder.Build();
    }

    /// <summary>Built-in modules. Listed by type so a single-file build keeps them.</summary>
    private static IEnumerable<Assembly> ModuleAssemblies() => new[]
    {
        typeof(PcRemote.Modules.System.SystemModule).Assembly,
        typeof(PcRemote.Modules.SystemInfo.SystemInfoModule).Assembly,
        typeof(PcRemote.Modules.Input.InputModule).Assembly,
        typeof(PcRemote.Modules.Clipboard.ClipboardModule).Assembly,
        typeof(PcRemote.Modules.Applications.ApplicationsModule).Assembly,
        typeof(PcRemote.Modules.Processes.ProcessesModule).Assembly,
        typeof(PcRemote.Modules.Windows.WindowsModule).Assembly,
        typeof(PcRemote.Modules.Media.MediaModule).Assembly,
        typeof(PcRemote.Modules.Network.NetworkModule).Assembly,
        typeof(PcRemote.Modules.Files.FilesModule).Assembly,
        typeof(PcRemote.Modules.Terminal.TerminalModule).Assembly,
    };

    private static void OpenPanel(IConfiguration configuration)
    {
        var panel = configuration.GetSection("Agent:Panel").Get<PanelSettings>() ?? new PanelSettings();
        if (!panel.Enabled) return;
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = $"http://localhost:{panel.Port}/",
                UseShellExecute = true,
            })?.Dispose();
        }
        catch { /* no browser: the tray still works */ }
    }
}
