using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using PcRemote.Modules.Input.Win32;

namespace PcRemote.Modules.Input;

/// <summary>
/// SendInput helpers for other modules (the remote screen). Every method returns
/// false when Windows refused the events: locked workstation, UAC prompt or
/// another secure desktop. Input aimed at an elevated window is dropped silently
/// (UIPI) and still reports success.
/// </summary>
[SupportedOSPlatform("windows")]
public static class InputInjector
{
    private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
    private const int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;

    /// <summary>
    /// Moves the cursor to a point of the virtual desktop, in physical pixels (the
    /// agent is per-monitor DPI aware, like the coordinates DXGI reports).
    /// </summary>
    public static bool MoveTo(int x, int y)
    {
        var vx = InputNative.GetSystemMetrics(SM_XVIRTUALSCREEN);
        var vy = InputNative.GetSystemMetrics(SM_YVIRTUALSCREEN);
        var vw = Math.Max(1, InputNative.GetSystemMetrics(SM_CXVIRTUALSCREEN));
        var vh = Math.Max(1, InputNative.GetSystemMetrics(SM_CYVIRTUALSCREEN));
        // Windows maps an absolute coordinate back with floor(a * size / 65536):
        // aiming at the pixel centre lands exactly on the pixel.
        var ax = (int)Math.Round((Math.Clamp(x - vx, 0, vw - 1) + 0.5) * 65536.0 / vw);
        var ay = (int)Math.Round((Math.Clamp(y - vy, 0, vh - 1) + 0.5) * 65536.0 / vh);
        return Send(Mouse(InputNative.MOUSEEVENTF_MOVE | InputNative.MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, ax, ay));
    }

    /// <summary>Presses or releases "left", "right" or "middle". False for an unknown button.</summary>
    public static bool Button(string button, bool down)
    {
        var flags = button.ToLowerInvariant() switch
        {
            "left"   => down ? InputNative.MOUSEEVENTF_LEFTDOWN   : InputNative.MOUSEEVENTF_LEFTUP,
            "right"  => down ? InputNative.MOUSEEVENTF_RIGHTDOWN  : InputNative.MOUSEEVENTF_RIGHTUP,
            "middle" => down ? InputNative.MOUSEEVENTF_MIDDLEDOWN : InputNative.MOUSEEVENTF_MIDDLEUP,
            _ => 0u,
        };
        return flags != 0 && Send(Mouse(flags, 0, 0));
    }

    /// <summary>Wheel in raw units (120 = one notch). Positive = up / right.</summary>
    public static bool Wheel(int delta, bool horizontal)
    {
        delta = Math.Clamp(delta, -24_000, 24_000);
        if (delta == 0) return true;
        var input = Mouse(horizontal ? InputNative.MOUSEEVENTF_HWHEEL : InputNative.MOUSEEVENTF_WHEEL, 0, 0);
        input.U.mi.mouseData = unchecked((uint)delta);
        return Send(input);
    }

    /// <summary>"ctrl+shift+esc": modifiers down in order, released in reverse.</summary>
    public static bool KeyCombo(string keys)
    {
        if (!VirtualKeys.TryResolve(keys, out var vks) || vks.Length == 0) return false;
        var events = new InputNative.INPUT[vks.Length * 2];
        for (int i = 0; i < vks.Length; i++) events[i] = Key(vks[i], 0);
        for (int i = 0; i < vks.Length; i++) events[vks.Length + i] = Key(vks[vks.Length - 1 - i], InputNative.KEYEVENTF_KEYUP);
        return Send(events);
    }

    /// <summary>One key down or up, for modifiers the app holds ("ctrl" while clicking).</summary>
    public static bool KeyState(string key, bool down)
    {
        if (!VirtualKeys.TryResolve(key, out var vks) || vks.Length != 1) return false;
        return Send(Key(vks[0], down ? 0 : InputNative.KEYEVENTF_KEYUP));
    }

    /// <summary>Types Unicode text; line breaks and tabs go out as real keys.</summary>
    public static bool Type(string text)
    {
        var events = new List<InputNative.INPUT>(text.Length * 2);
        foreach (var ch in text)
        {
            if (ch == '\r') continue;
            if (ch is '\n' or '\t' or '\b')
            {
                ushort vk = ch switch { '\n' => 0x0D, '\t' => 0x09, _ => 0x08 };
                events.Add(Key(vk, 0));
                events.Add(Key(vk, InputNative.KEYEVENTF_KEYUP));
                continue;
            }
            events.Add(Unicode(ch, 0));
            events.Add(Unicode(ch, InputNative.KEYEVENTF_KEYUP));
        }
        return Send(events.ToArray());
    }

    /// <summary>True if the name resolves to exactly one key ("ctrl", "a", "f5").</summary>
    public static bool IsKey(string key) => VirtualKeys.TryResolve(key, out var vks) && vks.Length == 1;

    private static InputNative.INPUT Mouse(uint flags, int dx, int dy) => new()
    {
        type = InputNative.INPUT_MOUSE,
        U = new InputNative.INPUTUNION { mi = new InputNative.MOUSEINPUT { dx = dx, dy = dy, dwFlags = flags } },
    };

    private static InputNative.INPUT Key(ushort vk, uint flags) => new()
    {
        type = InputNative.INPUT_KEYBOARD,
        U = new InputNative.INPUTUNION { ki = new InputNative.KEYBDINPUT { wVk = vk, dwFlags = flags | ExtendedFlag(vk) } },
    };

    private static InputNative.INPUT Unicode(char ch, uint flags) => new()
    {
        type = InputNative.INPUT_KEYBOARD,
        U = new InputNative.INPUTUNION { ki = new InputNative.KEYBDINPUT { wScan = ch, dwFlags = InputNative.KEYEVENTF_UNICODE | flags } },
    };

    /// <summary>
    /// Navigation keys live on the extended part of the keyboard. Without the flag
    /// some apps read "left" as numpad 4 while Num Lock is off.
    /// </summary>
    private static uint ExtendedFlag(ushort vk) => vk switch
    {
        0x21 or 0x22 or 0x23 or 0x24 or 0x25 or 0x26 or 0x27 or 0x28 or 0x2D or 0x2E
            or 0x5B or 0x5C or 0xA3 or 0xA5 or 0x6F => 0x0001, // KEYEVENTF_EXTENDEDKEY
        _ => 0,
    };

    private static bool Send(params InputNative.INPUT[] events) =>
        events.Length == 0 ||
        InputNative.SendInput((uint)events.Length, events, Marshal.SizeOf<InputNative.INPUT>()) == events.Length;
}
