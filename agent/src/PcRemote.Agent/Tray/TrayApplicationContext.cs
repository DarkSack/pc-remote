using System.Windows.Forms;

namespace PcRemote.Agent.Tray;

/// <summary>
/// Skeleton for the tray icon. Fully implemented in Phase 1.
/// </summary>
internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly NotifyIcon _notifyIcon;

    public TrayApplicationContext()
    {
        _notifyIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,       // TODO: reemplazar por icono propio
            Visible = true,
            Text = "PC Remote — starting…",
            ContextMenuStrip = BuildMenu(),
        };
    }

    private ContextMenuStrip BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("Status: Starting", enabled: false);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Devices…", null, (_, _) => { /* TODO */ });
        menu.Items.Add("Pair new device…", null, (_, _) => { /* TODO */ });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Open logs folder", null, (_, _) => { /* TODO */ });
        menu.Items.Add("Restart agent", null, (_, _) => { /* TODO */ });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) =>
        {
            _notifyIcon.Visible = false;
            ExitThread();
        });
        return menu;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) _notifyIcon.Dispose();
        base.Dispose(disposing);
    }
}

file static class ToolStripItemCollectionExtensions
{
    public static ToolStripMenuItem Add(this ToolStripItemCollection col, string text, bool enabled)
    {
        var item = new ToolStripMenuItem(text) { Enabled = enabled };
        col.Add(item);
        return item;
    }
}
