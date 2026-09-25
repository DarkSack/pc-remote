using System.Runtime.Versioning;
using System.Security.Cryptography.X509Certificates;
using System.Windows.Forms;
using Microsoft.Extensions.DependencyInjection;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Security;
using PcRemote.Core.Server;

namespace PcRemote.Agent.Tray;

/// <summary>
/// Tray icon: listening URL, LAN IP, cert fingerprint, active connections,
/// pair code notifications, and Manage devices dialog.
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly IServiceProvider _services;
    private readonly Action _onExit;
    private readonly NotifyIcon _notifyIcon;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _connectionsItem;
    private readonly ToolStripMenuItem _devicesItem;
    private readonly SynchronizationContext _uiCtx;

    public TrayApplicationContext(IServiceProvider services, Action onExit)
    {
        _services = services;
        _onExit   = onExit;
        _uiCtx    = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();

        var settings    = services.GetRequiredService<AgentSettings>();
        var certificate = services.GetRequiredService<X509Certificate2>();
        var connections = services.GetRequiredService<ConnectionManager>();
        var pairing     = services.GetRequiredService<PairingService>();
        var devices     = services.GetRequiredService<DeviceRepository>();

        _statusItem      = new ToolStripMenuItem { Enabled = false };
        _connectionsItem = new ToolStripMenuItem { Enabled = false };
        _devicesItem     = new ToolStripMenuItem { Enabled = false };

        _notifyIcon = new NotifyIcon
        {
            Icon    = LoadIcon(),
            Visible = true,
            Text    = "PC Remote — starting…",
        };

        RebuildStatus(settings, certificate, connections, devices);
        _notifyIcon.ContextMenuStrip = BuildMenu(settings, certificate, devices);

        connections.ConnectionsChanged += (_, _) => Post(() =>
            RebuildStatus(settings, certificate, connections, devices));

        pairing.CodeIssued += (_, code) => Post(() => ShowPairCode(code));

        _notifyIcon.ShowBalloonTip(
            3000,
            "PC Remote",
            $"Listening on wss://{GetPrimaryIp()}:{settings.WebSocket.Port}",
            ToolTipIcon.Info);
    }

    private void ShowPairCode(PairingCode code)
    {
        _notifyIcon.ShowBalloonTip(
            code.ClientIp.Length > 0 ? 20_000 : 10_000,
            $"Pair code: {code.Code}",
            $"Enter this code on the device requesting pairing from {code.ClientIp}. Expires in {(int)(code.ExpiresAt - DateTimeOffset.UtcNow).TotalSeconds}s.",
            ToolTipIcon.Warning);
    }

    private void RebuildStatus(AgentSettings s, X509Certificate2 cert, ConnectionManager cm, DeviceRepository devices)
    {
        var ip          = GetPrimaryIp();
        var fingerprint = CertificateProvider.GetFingerprint(cert);
        var active      = cm.ActiveCount;
        var shortPrint  = fingerprint.Length >= 16 ? fingerprint[..16] : fingerprint;
        var deviceCount = devices.List().Count(d => !d.Revoked);

        _statusItem.Text      = $"Listening: wss://{ip}:{s.WebSocket.Port}";
        _connectionsItem.Text = $"Active connections: {active}";
        _devicesItem.Text     = $"Paired devices: {deviceCount}";
        _notifyIcon.Text      = $"PC Remote · {active} conn · {deviceCount} devices · cert {shortPrint}…";
    }

    private ContextMenuStrip BuildMenu(AgentSettings settings, X509Certificate2 cert, DeviceRepository devices)
    {
        var menu = new ContextMenuStrip();

        menu.Items.Add(_statusItem);
        menu.Items.Add(_connectionsItem);
        menu.Items.Add(_devicesItem);
        menu.Items.Add(new ToolStripSeparator());

        var fingerprintItem = new ToolStripMenuItem("Copy cert fingerprint");
        fingerprintItem.Click += (_, _) =>
        {
            Clipboard.SetText(CertificateProvider.GetFingerprint(cert));
            _notifyIcon.ShowBalloonTip(2000, "Copied", "SHA-256 fingerprint copied to clipboard.", ToolTipIcon.Info);
        };
        menu.Items.Add(fingerprintItem);

        if (settings.Panel.Enabled)
        {
            var openPanel = new ToolStripMenuItem("Open web panel…") { Font = new Font(menu.Font, FontStyle.Bold) };
            openPanel.Click += (_, _) =>
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                {
                    FileName        = $"http://localhost:{settings.Panel.Port}/",
                    UseShellExecute = true,
                });
            };
            menu.Items.Add(openPanel);
        }

        var manageDevices = new ToolStripMenuItem("Manage devices…");
        manageDevices.Click += (_, _) => ShowDevicesDialog(devices);
        menu.Items.Add(manageDevices);

        var plugins = new ToolStripMenuItem("Open plugins folder");
        plugins.Click += (_, _) =>
        {
            var path = settings.Storage.ResolvedPluginsPath;
            Directory.CreateDirectory(path);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo { FileName = path, UseShellExecute = true });
        };
        menu.Items.Add(plugins);

        var autostart = new ToolStripMenuItem("Start with Windows") { Checked = Autostart.IsEnabled(), CheckOnClick = true };
        autostart.CheckedChanged += (_, _) =>
        {
            try { Autostart.Set(autostart.Checked); }
            catch (Exception ex)
            {
                MessageBox.Show($"Could not change it: {ex.Message}", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                autostart.Checked = Autostart.IsEnabled();
            }
        };
        menu.Items.Add(autostart);

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
        menu.Items.Add("Exit", null, (_, _) =>
        {
            _notifyIcon.Visible = false;
            _onExit();
            ExitThread();
        });

        return menu;
    }

    private async void ShowDevicesDialog(DeviceRepository devices)
    {
        var admin = _services.GetRequiredService<DeviceAdmin>();
        var list = devices.List();
        if (list.Count == 0)
        {
            MessageBox.Show(
                "No devices paired yet. Open the mobile app and follow the pairing flow.",
                "PC Remote — devices",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        var lines = list.Select(d =>
        {
            var last = d.LastSeenAt is null ? "never" : d.LastSeenAt.Value.LocalDateTime.ToString("yyyy-MM-dd HH:mm");
            var status = d.Revoked ? "REVOKED" : "active";
            return $"[{status}] {d.Name}\n  id={d.Id}\n  paired={d.PairedAt.LocalDateTime:yyyy-MM-dd HH:mm}\n  lastSeen={last}";
        });

        var body = string.Join("\n\n", lines);
        body += "\n\nCopy the ID of the device you want to revoke and press OK to enter it.";

        var result = MessageBox.Show(body, "PC Remote — devices", MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
        if (result != DialogResult.OK) return;

        var idToRevoke = Microsoft.VisualBasic.Interaction.InputBox(
            "Device ID to revoke (leave empty to cancel):",
            "PC Remote — revoke device",
            "");

        if (string.IsNullOrWhiteSpace(idToRevoke)) return;
        var d = devices.Get(idToRevoke.Trim());
        if (d is null)
        {
            MessageBox.Show("No device with that ID.", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        // Revoke also closes the device's live connection; before, the row was
        // marked revoked but an already-authenticated phone kept working.
        int closed;
        try
        {
            closed = await admin.RevokeAsync(d.Id);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Could not revoke '{d.Name}': {ex.Message}", "PC Remote",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        MessageBox.Show(
            $"Revoked '{d.Name}'. Closed {closed} live connection(s).",
            "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void Post(Action a) => _uiCtx.Post(_ => a(), null);

    /// <summary>The brand icon embedded in the exe, at the tray's size (16 px at 100 %, more with DPI scaling).</summary>
    private static Icon LoadIcon()
    {
        using var stream = typeof(TrayApplicationContext).Assembly.GetManifestResourceStream("PcRemote.Agent.pcremote.ico");
        return stream is null ? SystemIcons.Application : new Icon(stream, SystemInformation.SmallIconSize);
    }

    // Same pick as the panel's QR, so the tray never advertises a WSL/Hyper-V address.
    private static string GetPrimaryIp() => PcRemote.Core.Discovery.LanAddress.Guess();

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
