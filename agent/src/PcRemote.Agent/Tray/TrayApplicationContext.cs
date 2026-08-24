using System.Net;
using System.Net.NetworkInformation;
using System.Runtime.Versioning;
using System.Security.Cryptography.X509Certificates;
using System.Windows.Forms;
using Microsoft.Extensions.DependencyInjection;
using PcRemote.Core.Config;
using PcRemote.Core.Security;
using PcRemote.Core.Server;

namespace PcRemote.Agent.Tray;

/// <summary>
/// Tray icon showing live agent status: listening port, LAN IP(s), cert
/// fingerprint, active connections. Full "Manage devices" UI lands in Phase 2.
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly IServiceProvider _services;
    private readonly Action _onExit;
    private readonly NotifyIcon _notifyIcon;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _connectionsItem;

    public TrayApplicationContext(IServiceProvider services, Action onExit)
    {
        _services = services;
        _onExit   = onExit;

        var settings    = services.GetRequiredService<AgentSettings>();
        var certificate = services.GetRequiredService<X509Certificate2>();
        var connections = services.GetRequiredService<ConnectionManager>();

        _statusItem      = new ToolStripMenuItem { Enabled = false };
        _connectionsItem = new ToolStripMenuItem { Enabled = false };

        _notifyIcon = new NotifyIcon
        {
            Icon    = SystemIcons.Information,
            Visible = true,
            Text    = "PC Remote — starting…",
        };

        RebuildStatus(settings, certificate, connections);
        _notifyIcon.ContextMenuStrip = BuildMenu(settings, certificate);

        connections.ConnectionsChanged += (_, _) => SyncFromUiThread(() =>
            RebuildStatus(settings, certificate, connections));

        _notifyIcon.ShowBalloonTip(
            3000,
            "PC Remote",
            $"Listening on wss://{GetPrimaryIp()}:{settings.WebSocket.Port}",
            ToolTipIcon.Info);
    }

    private void RebuildStatus(AgentSettings settings, X509Certificate2 cert, ConnectionManager cm)
    {
        var ip           = GetPrimaryIp();
        var fingerprint  = CertificateProvider.GetFingerprint(cert);
        var active       = cm.ActiveCount;
        var shortPrint   = fingerprint.Length >= 16 ? fingerprint[..16] : fingerprint;

        _statusItem.Text      = $"Listening: wss://{ip}:{settings.WebSocket.Port}";
        _connectionsItem.Text = $"Active connections: {active}";
        _notifyIcon.Text      = $"PC Remote · {active} conn · cert {shortPrint}…";
    }

    private ContextMenuStrip BuildMenu(AgentSettings settings, X509Certificate2 cert)
    {
        var menu = new ContextMenuStrip();

        menu.Items.Add(_statusItem);
        menu.Items.Add(_connectionsItem);
        menu.Items.Add(new ToolStripSeparator());

        var fingerprintItem = new ToolStripMenuItem("Copy cert fingerprint");
        fingerprintItem.Click += (_, _) =>
        {
            Clipboard.SetText(CertificateProvider.GetFingerprint(cert));
            _notifyIcon.ShowBalloonTip(2000, "Copied", "SHA-256 fingerprint copied to clipboard.", ToolTipIcon.Info);
        };
        menu.Items.Add(fingerprintItem);

        var openLogs = new ToolStripMenuItem("Open logs folder");
        openLogs.Click += (_, _) =>
        {
            var logsPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "PcRemote", "logs");
            if (!Directory.Exists(logsPath)) Directory.CreateDirectory(logsPath);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName        = logsPath,
                UseShellExecute = true,
            });
        };
        menu.Items.Add(openLogs);

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Pair new device…", null, (_, _) =>
        {
            MessageBox.Show(
                "Pairing UI ships in Phase 2. For now use wscat to test the /ws echo endpoint.",
                "PC Remote",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        });
        menu.Items.Add("Manage devices…", null, (_, _) =>
        {
            MessageBox.Show("Device management ships in Phase 2.", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
        });

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) =>
        {
            _notifyIcon.Visible = false;
            _onExit();
            ExitThread();
        });

        return menu;
    }

    private void SyncFromUiThread(Action action)
    {
        // NotifyIcon runs on the STA main thread; posts from other threads must marshal.
        var ctx = SynchronizationContext.Current;
        if (ctx is not null) ctx.Post(_ => action(), null);
        else action();
    }

    private static string GetPrimaryIp()
    {
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var addr in nic.GetIPProperties().UnicastAddresses)
                {
                    if (addr.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                        !IPAddress.IsLoopback(addr.Address))
                        return addr.Address.ToString();
                }
            }
        }
        catch { /* ignore */ }
        return "0.0.0.0";
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
        }
        base.Dispose(disposing);
    }
}
