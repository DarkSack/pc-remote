using System.Windows.Forms;
using PcRemote.Agent.Tray;

namespace PcRemote.Agent;

/// <summary>
/// Entry point of the PC Remote agent (Windows tray application).
/// Does not open a visible window; lives in the system tray.
/// </summary>
internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // TODO F1: wire Serilog + Microsoft.Extensions.Hosting
        // TODO F1: bootstrap PcRemote.Core.Server (Kestrel + wss) as hosted service
        // TODO F1: load configured modules via reflection

        Application.Run(new TrayApplicationContext());
    }
}
