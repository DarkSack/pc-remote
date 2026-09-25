using System.Reflection;
using System.Runtime.Versioning;
using System.Windows.Forms;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using PcRemote.Agent.Tray;
using PcRemote.Core;
using PcRemote.Core.Config;
using PcRemote.Core.Panel;
using Serilog;
using Serilog.Settings.Configuration;

namespace PcRemote.Agent;

/// <summary>Entry point of the PC Remote agent (Windows tray application).</summary>
[SupportedOSPlatform("windows")]
internal static class Program
{
    /// <summary>
    /// The built-in modules. Listed explicitly because the single-file exe keeps
    /// them inside itself: there are no PcRemote.Modules.*.dll files to scan.
    /// </summary>
    private static readonly Assembly[] BuiltInModules =
    {
        typeof(PcRemote.Modules.System.SystemModule).Assembly,
        typeof(PcRemote.Modules.SystemInfo.SystemInfoModule).Assembly,
        typeof(PcRemote.Modules.Input.InputModule).Assembly,
        typeof(PcRemote.Modules.Clipboard.ClipboardModule).Assembly,
        typeof(PcRemote.Modules.Applications.ApplicationsModule).Assembly,
        typeof(PcRemote.Modules.Processes.ProcessesModule).Assembly,
        typeof(PcRemote.Modules.Windows.WindowsModule).Assembly,
        typeof(PcRemote.Modules.Media.MediaModule).Assembly,
        typeof(PcRemote.Modules.Terminal.TerminalModule).Assembly,
        typeof(PcRemote.Modules.Files.FilesModule).Assembly,
        typeof(PcRemote.Modules.Network.NetworkModule).Assembly,
    };

    [STAThread]
    private static int Main(string[] args)
    {
        ApplicationConfiguration.Initialize();

        var configuration = AgentConfiguration.Build();

        // Double-clicking the exe while the agent already runs must not start a
        // second one (it would fail to bind the ports): open the panel instead.
        using var instance = new Mutex(initiallyOwned: true, @"Local\PcRemote.Agent", out var isFirst);
        if (!isFirst)
        {
            TrayApplicationContext.OpenPanel(configuration.GetSection("Agent:Panel:Port").Get<int?>() ?? 47810);
            return 0;
        }

        // Serilog first: AgentHost hands Log.Logger to the host's logging, so it has
        // to be the real logger before Build(), not the silent default. The sink
        // assemblies are named explicitly: in a single-file exe Serilog cannot find
        // them by scanning, and would silently log nowhere.
        Log.Logger = new LoggerConfiguration()
            .ReadFrom.Configuration(configuration, new ConfigurationReaderOptions(
                typeof(ConsoleLoggerConfigurationExtensions).Assembly,
                typeof(FileLoggerConfigurationExtensions).Assembly))
            .WriteTo.Sink(InMemoryLogSink.Instance)
            .CreateLogger();

        try
        {
            Log.Information("Agent {Version} starting from {Path}…",
                typeof(Program).Assembly.GetName().Version?.ToString(3), Environment.ProcessPath);

            var host = AgentHost.Build(BuiltInModules);

            // Start the .NET Host in the background; tray UI runs on the STA main thread.
            host.StartAsync().GetAwaiter().GetResult();

            using var tray = new TrayApplicationContext(host.Services, () =>
            {
                // Task.Run on purpose. This runs on the UI thread, inside the WinForms
                // SynchronizationContext. Blocking it with GetResult() while the hosted
                // services await (WebSocketServer.StopAsync awaits Kestrel without
                // ConfigureAwait(false)) would post their continuations to the very
                // thread that is blocked waiting for them: "Exit" would hang forever.
                try { Task.Run(() => host.StopAsync(TimeSpan.FromSeconds(5))).GetAwaiter().GetResult(); }
                catch { /* best effort */ }
            }, startedByWindows: args.Contains(Autostart.Flag));

            Application.Run(tray);
            return 0;
        }
        catch (Exception ex)
        {
            Log.Fatal(ex, "Agent crashed");
            var hint = ex is IOException or System.Net.Sockets.SocketException ||
                       ex.InnerException is System.Net.Sockets.SocketException
                ? "\n\n¿Hay otro programa usando el puerto 47820 o 47810?"
                : "";
            MessageBox.Show(ex.Message + hint, "PC Remote — error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        finally
        {
            Log.CloseAndFlush();
        }
    }
}
