using System.Runtime.Versioning;
using System.Windows.Forms;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using PcRemote.Agent.Tray;
using PcRemote.Core;
using PcRemote.Core.Panel;
using Serilog;

namespace PcRemote.Agent;

/// <summary>Entry point of the PC Remote agent (Windows tray application).</summary>
[SupportedOSPlatform("windows")]
internal static class Program
{
    [STAThread]
    private static int Main()
    {
        ApplicationConfiguration.Initialize();
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // Serilog first: AgentHost hands Log.Logger to the host's logging, so it has
        // to be the real logger before Build(), not the silent default.
        // Wired from appsettings.json, plus the ring buffer the panel serves at /api/logs.
        var configuration = new ConfigurationBuilder()
            .AddJsonFile(Path.Combine(AppContext.BaseDirectory, "appsettings.json"), optional: false)
            .Build();

        Log.Logger = new LoggerConfiguration()
            .ReadFrom.Configuration(configuration)
            .WriteTo.Sink(InMemoryLogSink.Instance)
            .CreateLogger();

        try
        {
            Log.Information("Agent starting…");

            var host = AgentHost.Build();

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
            });

            Application.Run(tray);
            return 0;
        }
        catch (Exception ex)
        {
            Log.Fatal(ex, "Agent crashed");
            MessageBox.Show(ex.Message, "PC Remote — fatal error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        finally
        {
            Log.CloseAndFlush();
        }
    }
}
