using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Modules.Input.Win32;

namespace PcRemote.Modules.Input;

// ══════════════════════════════════════════════════════════════
// Input module — control de mouse y teclado vía SendInput.
//
// Diseño:
//   - Mouse move es RELATIVO por defecto (touchpad-style). Un flag
//     absolute:true acepta coordenadas normalizadas 0..1.
//   - Los deltas se clampean a ±2000px por evento para evitar
//     jumps accidentales por bugs del cliente.
//   - Los clicks toman un botón ("left"|"right"|"middle") + opcional
//     count (1..3) para doble/triple click.
//   - keyPress acepta expresiones tipo "ctrl+shift+esc". Se pulsan
//     modificadores → tecla → se sueltan en orden inverso.
//   - keyType acepta un string arbitrario y lo emite como Unicode
//     via SendInput con KEYEVENTF_UNICODE (no depende del layout).
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class InputModule : ICommandModule, IPluginMetadata, ISessionAware
{
    public string Domain => "input";

    public string DisplayName => "Ratón y teclado";
    public string Description => "Touchpad, clics, scroll, escritura y atajos de teclado.";
    public string Category => PluginCategories.Control;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("mouseMove",   "Mover el cursor (relativo o absoluto)"),
        new CommandDescriptor("mouseClick",  "Click de mouse (left/right/middle)"),
        new CommandDescriptor("mouseDown",   "Pulsar un botón sin soltarlo (arrastrar)"),
        new CommandDescriptor("mouseUp",     "Soltar un botón"),
        new CommandDescriptor("mouseScroll", "Scroll vertical u horizontal"),
        new CommandDescriptor("mousePos",    "Obtener posición del cursor"),
        new CommandDescriptor("keyPress",    "Combo de teclas (ej: ctrl+shift+esc)"),
        new CommandDescriptor("keyType",     "Escribir texto Unicode"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "mouseMove"   => HandleMouseMove(req),
                "mouseClick"  => HandleMouseClick(req, session),
                "mouseDown"   => HandleMouseButton(req, session, down: true),
                "mouseUp"     => HandleMouseButton(req, session, down: false),
                "mouseScroll" => HandleMouseScroll(req),
                "mousePos"    => HandleMousePos(req),
                "keyPress"    => HandleKeyPress(req),
                "keyType"     => HandleKeyType(req),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    // ── Mouse ────────────────────────────────────────────────

    private static CommandResponse HandleMouseMove(CommandRequest req)
    {
        var p = req.Params ?? default;
        bool absolute = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("absolute", out var a) && a.ValueKind == JsonValueKind.True;

        if (absolute)
        {
            var x = p.GetProperty("x").GetDouble();
            var y = p.GetProperty("y").GetDouble();
            if (x < 0 || x > 1 || y < 0 || y > 1)
                return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Absolute x,y must be in [0,1]");

            var screenW = InputNative.GetSystemMetrics(InputNative.SM_CXSCREEN);
            var screenH = InputNative.GetSystemMetrics(InputNative.SM_CYSCREEN);
            InputNative.SetCursorPos((int)Math.Round(x * screenW), (int)Math.Round(y * screenH));
            return CommandResponse.Ok(req.Id, new { moved = true });
        }
        else
        {
            var dx = ClampDelta((int)p.GetProperty("dx").GetDouble());
            var dy = ClampDelta((int)p.GetProperty("dy").GetDouble());
            var input = new InputNative.INPUT
            {
                type = InputNative.INPUT_MOUSE,
                U = new InputNative.INPUTUNION { mi = new InputNative.MOUSEINPUT { dx = dx, dy = dy, dwFlags = InputNative.MOUSEEVENTF_MOVE } },
            };
            if (!Send(input)) return Blocked(req.Id);
            return CommandResponse.Ok(req.Id, new { moved = true });
        }
    }

    private static (uint down, uint up) ButtonFlags(string button) => button.ToLowerInvariant() switch
    {
        "left"   => (InputNative.MOUSEEVENTF_LEFTDOWN,   InputNative.MOUSEEVENTF_LEFTUP),
        "right"  => (InputNative.MOUSEEVENTF_RIGHTDOWN,  InputNative.MOUSEEVENTF_RIGHTUP),
        "middle" => (InputNative.MOUSEEVENTF_MIDDLEDOWN, InputNative.MOUSEEVENTF_MIDDLEUP),
        _ => (0, 0),
    };

    private static string ButtonParam(JsonElement p) =>
        p.ValueKind == JsonValueKind.Object && p.TryGetProperty("button", out var b) ? b.GetString() ?? "left" : "left";

    /// <summary>
    /// Press or release without the other half. The touchpad uses it for drag:
    /// hold → mouseDown, move, lift → mouseUp. A client that dies mid-drag leaves
    /// the button held; the next click releases it, as with a real mouse.
    /// </summary>
    private CommandResponse HandleMouseButton(CommandRequest req, ClientSession session, bool down)
    {
        var button = ButtonParam(req.Params ?? default);
        var flags = ButtonFlags(button);
        if (flags.down == 0)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, $"Unknown button '{button}'");

        var input = new InputNative.INPUT
        {
            type = InputNative.INPUT_MOUSE,
            U = new InputNative.INPUTUNION { mi = new InputNative.MOUSEINPUT { dwFlags = down ? flags.down : flags.up } },
        };
        if (!Send(input)) return Blocked(req.Id);
        TrackHeld(session, button, down);
        return CommandResponse.Ok(req.Id, new { button, down });
    }

    private CommandResponse HandleMouseClick(CommandRequest req, ClientSession session)
    {
        var p = req.Params ?? default;
        var button = ButtonParam(p);
        var count  = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("count", out var c) ? Math.Clamp(c.GetInt32(), 1, 3) : 1;

        var flags = ButtonFlags(button);
        if (flags.down == 0)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, $"Unknown button '{button}'");

        var events = new List<InputNative.INPUT>(count * 2);
        for (int i = 0; i < count; i++)
        {
            events.Add(new InputNative.INPUT { type = InputNative.INPUT_MOUSE, U = new InputNative.INPUTUNION { mi = new InputNative.MOUSEINPUT { dwFlags = flags.down } } });
            events.Add(new InputNative.INPUT { type = InputNative.INPUT_MOUSE, U = new InputNative.INPUTUNION { mi = new InputNative.MOUSEINPUT { dwFlags = flags.up } } });
        }
        if (!Send(events.ToArray())) return Blocked(req.Id);
        // A full click also releases a button a dropped drag may have left held.
        TrackHeld(session, button, down: false);
        return CommandResponse.Ok(req.Id, new { clicked = button, count });
    }

    private static CommandResponse HandleMouseScroll(CommandRequest req)
    {
        var p = req.Params ?? default;
        var horizontal = p.TryGetProperty("horizontal", out var h) && h.ValueKind == JsonValueKind.True;

        // `delta` = raw wheel units (120 per notch), for smooth touchpad scrolling;
        // `amount` = whole notches. Precision touchpads send sub-notch deltas too.
        int wheel;
        if (p.TryGetProperty("delta", out var d))
            wheel = Math.Clamp((int)d.GetDouble(), -24_000, 24_000);
        else
            wheel = ClampDelta((int)p.GetProperty("amount").GetDouble()) * (int)InputNative.WHEEL_DELTA;
        var amount = wheel;

        var input = new InputNative.INPUT
        {
            type = InputNative.INPUT_MOUSE,
            U = new InputNative.INPUTUNION
            {
                mi = new InputNative.MOUSEINPUT
                {
                    dwFlags   = horizontal ? InputNative.MOUSEEVENTF_HWHEEL : InputNative.MOUSEEVENTF_WHEEL,
                    mouseData = unchecked((uint)amount),
                },
            },
        };
        if (!Send(input)) return Blocked(req.Id);
        return CommandResponse.Ok(req.Id, new { scrolled = amount });
    }

    private static CommandResponse HandleMousePos(CommandRequest req)
    {
        if (!InputNative.GetCursorPos(out var pt))
            return CommandResponse.Fail(req.Id, ErrorCodes.InternalError, "GetCursorPos failed");
        var w = InputNative.GetSystemMetrics(InputNative.SM_CXSCREEN);
        var h = InputNative.GetSystemMetrics(InputNative.SM_CYSCREEN);
        return CommandResponse.Ok(req.Id, new { x = pt.X, y = pt.Y, screenW = w, screenH = h });
    }

    // ── Keyboard ─────────────────────────────────────────────

    private static CommandResponse HandleKeyPress(CommandRequest req)
    {
        var p = req.Params ?? default;
        var keys = p.GetProperty("keys").GetString() ?? "";
        if (!VirtualKeys.TryResolve(keys, out var vks) || vks.Length == 0)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, $"Cannot resolve keys '{keys}'");

        var events = new List<InputNative.INPUT>(vks.Length * 2);
        // Down en orden, up en reverso
        for (int i = 0; i < vks.Length; i++)
            events.Add(KeyEvent(vks[i], 0));
        for (int i = vks.Length - 1; i >= 0; i--)
            events.Add(KeyEvent(vks[i], InputNative.KEYEVENTF_KEYUP));

        if (!Send(events.ToArray())) return Blocked(req.Id);
        return CommandResponse.Ok(req.Id, new { pressed = keys });
    }

    private static CommandResponse HandleKeyType(CommandRequest req)
    {
        var p = req.Params ?? default;
        var text = p.GetProperty("text").GetString() ?? "";
        if (text.Length > 4096)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Text too long (max 4096)");

        var events = new List<InputNative.INPUT>(text.Length * 2);
        foreach (var ch in text)
        {
            // A Unicode "\n" is ignored by most apps (no new line in Notepad, no
            // submit in a chat box). Line breaks and tabs go out as real keys.
            if (ch == '\r') continue;
            if (ch is '\n' or '\t')
            {
                ushort vk = ch == '\n' ? (ushort)0x0D : (ushort)0x09;
                events.Add(KeyEvent(vk, 0));
                events.Add(KeyEvent(vk, InputNative.KEYEVENTF_KEYUP));
                continue;
            }
            events.Add(UnicodeEvent(ch, 0));
            events.Add(UnicodeEvent(ch, InputNative.KEYEVENTF_KEYUP));
        }
        if (!Send(events.ToArray())) return Blocked(req.Id);
        return CommandResponse.Ok(req.Id, new { typed = text.Length });
    }

    // ── Helpers ──────────────────────────────────────────────

    /// <summary>
    /// SendInput returns how many events it inserted. 0 means Windows refused them:
    /// the secure desktop (UAC prompt, Ctrl+Alt+Del) or a locked workstation.
    /// It does NOT reliably report UIPI blocking: input aimed at a window running
    /// as administrator (e.g. Task Manager for an admin user) is silently dropped
    /// while SendInput still reports success. An agent without elevation cannot
    /// control those windows, and cannot even detect it.
    /// </summary>
    private static bool Send(params InputNative.INPUT[] events) =>
        events.Length == 0 ||
        InputNative.SendInput((uint)events.Length, events, System.Runtime.InteropServices.Marshal.SizeOf<InputNative.INPUT>()) == events.Length;

    private static CommandResponse Blocked(string id) =>
        CommandResponse.Fail(id, ErrorCodes.PermissionDenied,
            "Windows rejected the input (locked screen, UAC prompt or secure desktop).", recoverable: true);

    // Buttons pressed with mouseDown and not yet released, per session. If the
    // phone disconnects mid-drag (Wi-Fi drop, app killed), the button would stay
    // held in Windows and every later physical mouse move would drag something.
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, HashSet<string>> _held = new();

    private void TrackHeld(ClientSession session, string button, bool down)
    {
        var set = _held.GetOrAdd(session.SessionId, _ => new HashSet<string>(StringComparer.OrdinalIgnoreCase));
        lock (set)
        {
            if (down) set.Add(button); else set.Remove(button);
        }
    }

    public void OnSessionEnded(ClientSession session)
    {
        if (!_held.TryRemove(session.SessionId, out var set)) return;
        string[] buttons;
        lock (set) buttons = set.ToArray();
        foreach (var button in buttons)
        {
            var flags = ButtonFlags(button);
            if (flags.up == 0) continue;
            Send(new InputNative.INPUT
            {
                type = InputNative.INPUT_MOUSE,
                U = new InputNative.INPUTUNION { mi = new InputNative.MOUSEINPUT { dwFlags = flags.up } },
            });
        }
    }

    private static int ClampDelta(int v) => Math.Clamp(v, -2000, 2000);

    private static InputNative.INPUT KeyEvent(ushort vk, uint flags) =>
        new()
        {
            type = InputNative.INPUT_KEYBOARD,
            U = new InputNative.INPUTUNION { ki = new InputNative.KEYBDINPUT { wVk = vk, dwFlags = flags } },
        };

    private static InputNative.INPUT UnicodeEvent(char ch, uint extraFlags) =>
        new()
        {
            type = InputNative.INPUT_KEYBOARD,
            U = new InputNative.INPUTUNION { ki = new InputNative.KEYBDINPUT { wScan = ch, dwFlags = InputNative.KEYEVENTF_UNICODE | extraFlags } },
        };
}
