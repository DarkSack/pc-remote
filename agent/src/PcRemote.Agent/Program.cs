using System.Runtime.Versioning;
using System.Windows.Forms;
using Microsoft.Extensions.DependencyInjection;
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

        var host = AgentHost.Build();

        // Serilog is wired from appsettings.json (Serilog.Settings.Configuration).
        // Also add the in-memory ring buffer sink so the web panel can serve /api/logs.
        Log.Logger = new LoggerConfiguration()
            .ReadFrom.Configuration(host.Services.GetRequiredService<Microsoft.Extensions.Configuration.IConfiguration>())
            .WriteTo.Sink(InMemoryLogSink.Instance)
            .CreateLogger();

        try
        {
            Log.Information("Agent starting…");

            // Start the .NET Host in the background; tray UI runs on the STA main thread.
            var cts       = new CancellationTokenSource();
            var hostTask  = host.StartAsync(cts.Token);
            hostTask.GetAwaiter().GetResult();

            using var tray = new TrayApplicationContext(host.Services, () =>
            {
                cts.Cancel();
                try { host.StopAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult(); } catch { /* best effort */ }
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
