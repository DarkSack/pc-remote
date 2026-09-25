using System.Diagnostics;
using System.Runtime.Versioning;
using System.Security.Cryptography.X509Certificates;
using System.Windows.Forms;
using Microsoft.Extensions.DependencyInjection;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;
using PcRemote.Core.Plugins;
using PcRemote.Core.Security;
using PcRemote.Core.Server;

namespace PcRemote.Agent.Tray;

/// <summary>
/// Tray icon. Double-click opens the panel (pairing QR, devices, plugins). The
/// menu shows the address, connections and devices, and has "Iniciar con
/// Windows". The first time (no device paired yet) the panel opens by itself,
/// so a new user goes from double-clicking the exe straight to the QR.
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly Action _onExit;
    private readonly NotifyIcon _notifyIcon;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _connectionsItem;
    private readonly ToolStripMenuItem _devicesItem;
    private readonly SynchronizationContext _uiCtx;
    private readonly AgentSettings _settings;

    public TrayApplicationContext(IServiceProvider services, Action onExit, bool startedByWindows)
    {
        _onExit   = onExit;
        _uiCtx    = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _settings = services.GetRequiredService<AgentSettings>();

        var certificate = services.GetRequiredService<X509Certificate2>();
        var connections = services.GetRequiredService<ConnectionManager>();
        var pairing     = services.GetRequiredService<PairingService>();
        var devices     = services.GetRequiredService<DeviceRepository>();
        var plugins     = services.GetRequiredService<PluginManager>();

        _statusItem      = new ToolStripMenuItem { Enabled = false };
        _connectionsItem = new ToolStripMenuItem { Enabled = false };
        _devicesItem     = new ToolStripMenuItem { Enabled = false };

        _notifyIcon = new NotifyIcon
        {
            Icon    = LoadIcon(),
            Visible = true,
            Text    = "PC Remote — iniciando…",
        };
        _notifyIcon.DoubleClick += (_, _) => OpenPanel(_settings.Panel.Port);

        RebuildStatus(certificate, connections, devices);
        _notifyIcon.ContextMenuStrip = BuildMenu(certificate, plugins);

        connections.ConnectionsChanged += (_, _) => Post(() => RebuildStatus(certificate, connections, devices));

        pairing.CodeIssued += (_, code) => Post(() => ShowPairCode(code));

        Autostart.RepairIfMoved();

        if (devices.List().All(d => d.Revoked) && _settings.Panel.Enabled && !startedByWindows)
        {
            // First run: straight to the QR.
            OpenPanel(_settings.Panel.Port);
        }
        else if (!startedByWindows)
        {
            _notifyIcon.ShowBalloonTip(3000, "PC Remote",
                $"Activo en {GetPrimaryIp()}. Doble clic en el icono para abrir el panel.", ToolTipIcon.Info);
        }
    }

    public static void OpenPanel(int port) => OpenShell($"http://localhost:{port}/");

    private static void OpenShell(string target)
    {
        try { Process.Start(new ProcessStartInfo { FileName = target, UseShellExecute = true })?.Dispose(); }
        catch { /* no browser / folder gone: nothing useful to do */ }
    }

    private static Icon LoadIcon()
    {
        using var stream = typeof(TrayApplicationContext).Assembly.GetManifestResourceStream("PcRemote.Agent.app.ico");
        return stream is null ? SystemIcons.Application : new Icon(stream, SystemInformation.SmallIconSize);
    }

    private void ShowPairCode(PairingCode code)
    {
        _notifyIcon.ShowBalloonTip(
            20_000,
            $"Código de emparejamiento: {code.Code}",
            $"Escríbelo en el móvil que lo pide desde {code.ClientIp}. Caduca en {(int)(code.ExpiresAt - DateTimeOffset.UtcNow).TotalSeconds} s.",
            ToolTipIcon.Warning);
    }

    private void RebuildStatus(X509Certificate2 cert, ConnectionManager cm, DeviceRepository devices)
    {
        var ip          = GetPrimaryIp();
        var active      = cm.ActiveCount;
        var deviceCount = devices.List().Count(d => !d.Revoked);

        _statusItem.Text      = $"Escuchando en {ip}:{_settings.WebSocket.Port}";
        _connectionsItem.Text = $"Conexiones activas: {active}";
        _devicesItem.Text     = $"Dispositivos emparejados: {deviceCount}";
        // NotifyIcon.Text is limited to 127 characters.
        _notifyIcon.Text      = $"PC Remote · {active} conexión(es) · {deviceCount} dispositivo(s)";
    }

    private ContextMenuStrip BuildMenu(X509Certificate2 cert, PluginManager plugins)
    {
        var menu = new ContextMenuStrip();

        if (_settings.Panel.Enabled)
        {
            var openPanel = new ToolStripMenuItem("Abrir panel") { Font = new Font(menu.Font, FontStyle.Bold) };
            openPanel.Click += (_, _) => OpenPanel(_settings.Panel.Port);
            menu.Items.Add(openPanel);
            menu.Items.Add(new ToolStripSeparator());
        }

        menu.Items.Add(_statusItem);
        menu.Items.Add(_connectionsItem);
        menu.Items.Add(_devicesItem);
        menu.Items.Add(new ToolStripSeparator());

        var autostart = new ToolStripMenuItem("Iniciar con Windows") { CheckOnClick = true, Checked = Autostart.IsEnabled };
        autostart.CheckedChanged += (_, _) =>
        {
            try { Autostart.Set(autostart.Checked); }
            catch (Exception ex)
            {
                MessageBox.Show($"No se pudo cambiar: {ex.Message}", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        };
        menu.Items.Add(autostart);

        var fingerprintItem = new ToolStripMenuItem("Copiar huella del certificado");
        fingerprintItem.Click += (_, _) =>
        {
            Clipboard.SetText(CertificateProvider.GetFingerprint(cert));
            _notifyIcon.ShowBalloonTip(2000, "Copiado", "Huella SHA-256 copiada al portapapeles.", ToolTipIcon.Info);
        };
        menu.Items.Add(fingerprintItem);

        var pluginsFolder = new ToolStripMenuItem("Abrir carpeta de plugins");
        pluginsFolder.Click += (_, _) =>
        {
            Directory.CreateDirectory(plugins.PluginsDirectory);
            OpenShell(plugins.PluginsDirectory);
        };
        menu.Items.Add(pluginsFolder);

        var openLogs = new ToolStripMenuItem("Abrir carpeta de registros");
        openLogs.Click += (_, _) =>
        {
            var logsPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PcRemote", "logs");
            Directory.CreateDirectory(logsPath);
            OpenShell(logsPath);
        };
        menu.Items.Add(openLogs);

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Salir", null, (_, _) =>
        {
            _notifyIcon.Visible = false;
            _onExit();
            ExitThread();
        });

        return menu;
    }

    private void Post(Action a) => _uiCtx.Post(_ => a(), null);

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
