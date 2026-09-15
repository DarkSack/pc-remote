using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Modules.Windows.Win32;

namespace PcRemote.Modules.Windows;

// ══════════════════════════════════════════════════════════════
// Windows — enumerar y manipular ventanas top-level.
//
// Handles se identifican por hwnd (long, cast desde IntPtr). El
// cliente los recibe en `list` y los reenvía en `focus/minimize/
// maximize/restore/close`. Validamos que el hwnd siga siendo válido
// antes de cada operación (IsWindowVisible).
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class WindowsModule : ICommandModule
{
    public string Domain => "windows";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list",     "Enumerar ventanas visibles"),
        new CommandDescriptor("focus",    "Traer ventana al frente"),
        new CommandDescriptor("minimize", "Minimizar ventana"),
        new CommandDescriptor("maximize", "Maximizar ventana"),
        new CommandDescriptor("restore",  "Restaurar ventana"),
        new CommandDescriptor("close",    "Cerrar ventana (WM_CLOSE)"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "list"     => List(req),
                "focus"    => Op(req, Focus),
                "minimize" => Op(req, h => WindowNative.ShowWindow(h, WindowNative.SW_MINIMIZE)),
                "maximize" => Op(req, h => WindowNative.ShowWindow(h, WindowNative.SW_MAXIMIZE)),
                "restore"  => Op(req, h => WindowNative.ShowWindow(h, WindowNative.SW_RESTORE)),
                // PostMessage, not SendMessage: SendMessage waits until the window
                // has handled WM_CLOSE, and an app with unsaved changes handles it by
                // showing "Save changes?" — the agent then hung until someone
                // answered at the PC. A hung window blocked it forever.
                "close"    => Op(req, h => WindowNative.PostMessage(h, WindowNative.WM_CLOSE, IntPtr.Zero, IntPtr.Zero)),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    private static CommandResponse List(CommandRequest req)
    {
        var items = new List<object>();
        WindowNative.EnumWindows((h, _) =>
        {
            if (!WindowNative.IsWindowVisible(h)) return true;
            int len = WindowNative.GetWindowTextLength(h);
            if (len == 0) return true; // ventanas sin título → filtrar (tray, etc.)

            var sb = new StringBuilder(len + 1);
            WindowNative.GetWindowText(h, sb, sb.Capacity);
            var title = sb.ToString();

            WindowNative.GetWindowThreadProcessId(h, out var pid);
            string procName = "";
            // Disposed: each Process holds a handle, and every list leaked one per window.
            try { using var proc = Process.GetProcessById((int)pid); procName = proc.ProcessName; } catch { }

            WindowNative.GetWindowRect(h, out var r);
            items.Add(new
            {
                hwnd      = h.ToInt64(),
                title,
                pid,
                process   = procName,
                minimized = WindowNative.IsIconic(h),
                maximized = WindowNative.IsZoomed(h),
                x = r.Left, y = r.Top,
                width  = r.Right - r.Left,
                height = r.Bottom - r.Top,
            });
            return true;
        }, IntPtr.Zero);
        return CommandResponse.Ok(req.Id, new { windows = items });
    }

    /// <summary>A minimized window stays minimized after SetForegroundWindow; restore it first.</summary>
    private static bool Focus(IntPtr hwnd)
    {
        if (WindowNative.IsIconic(hwnd))
            WindowNative.ShowWindow(hwnd, WindowNative.SW_RESTORE);
        return WindowNative.SetForegroundWindow(hwnd);
    }

    private static CommandResponse Op(CommandRequest req, Func<IntPtr, bool> fn)
    {
        var p = req.Params ?? default;
        var hwndL = p.GetProperty("hwnd").GetInt64();
        var hwnd = new IntPtr(hwndL);
        if (!WindowNative.IsWindowVisible(hwnd))
            return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, "Window not found or hidden");
        var ok = fn(hwnd);
        return CommandResponse.Ok(req.Id, new { ok });
    }
}
