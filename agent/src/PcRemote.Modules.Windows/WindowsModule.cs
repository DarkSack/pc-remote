using System.Diagnostics;
using System.Runtime.InteropServices;
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
        var foreground = WindowNative.GetForegroundWindow();
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
            string? description = null;
            // Disposed: each Process holds a handle, and every list leaked one per window.
            try
            {
                using var proc = Process.GetProcessById((int)pid);
                procName = proc.ProcessName;
                description = Describe(proc);
            }
            catch { }

            WindowNative.GetWindowRect(h, out var r);
            items.Add(new
            {
                hwnd      = h.ToInt64(),
                title,
                pid,
                process   = procName,
                // "Google Chrome" for chrome.exe: lets the app match windows to its app list.
                description,
                foreground = h == foreground,
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

    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, string?> Descriptions = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>FileDescription of the program (cached per path). Null for elevated processes we cannot open.</summary>
    private static string? Describe(Process proc)
    {
        string? path;
        try { path = proc.MainModule?.FileName; } catch { return null; }
        if (path is null) return null;
        return Descriptions.GetOrAdd(path, p =>
        {
            try
            {
                var info = FileVersionInfo.GetVersionInfo(p);
                return string.IsNullOrWhiteSpace(info.FileDescription) ? info.ProductName : info.FileDescription.Trim();
            }
            catch { return null; }
        });
    }

    /// <summary>
    /// Brings a window to the front from a background process.
    ///
    /// Windows only lets the process that received the last input change the
    /// foreground window. The agent never has focus, so plain SetForegroundWindow
    /// worked a couple of times and then only flashed the taskbar button (reproduced
    /// with focus.mjs: the third focus in a row returned false).
    ///   1. An empty mouse input from this process first counts as "last input"
    ///      (the trick PowerToys uses). It moves nothing and clicks nothing.
    ///   2. If Windows still refuses, attach to the foreground window's input queue
    ///      for the call, which the lock allows, and detach right after.
    /// A minimized window stays minimized after SetForegroundWindow; restore it first.
    /// Returns whether the window really ended up in front.
    /// </summary>
    private static bool Focus(IntPtr hwnd)
    {
        if (WindowNative.IsIconic(hwnd))
            WindowNative.ShowWindow(hwnd, WindowNative.SW_RESTORE);
        if (WindowNative.GetForegroundWindow() == hwnd) return true;

        var empty = new[] { new WindowNative.INPUT { type = WindowNative.INPUT_MOUSE } };
        WindowNative.SendInput(1, empty, Marshal.SizeOf<WindowNative.INPUT>());
        if (WindowNative.SetForegroundWindow(hwnd) && WindowNative.GetForegroundWindow() == hwnd) return true;

        var foregroundThread = WindowNative.GetWindowThreadProcessId(WindowNative.GetForegroundWindow(), out _);
        var ourThread = WindowNative.GetCurrentThreadId();
        var attached = foregroundThread != 0 && foregroundThread != ourThread &&
                       WindowNative.AttachThreadInput(ourThread, foregroundThread, true);
        try
        {
            WindowNative.BringWindowToTop(hwnd);
            WindowNative.SetForegroundWindow(hwnd);
        }
        finally
        {
            if (attached) WindowNative.AttachThreadInput(ourThread, foregroundThread, false);
        }
        return WindowNative.GetForegroundWindow() == hwnd;
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
