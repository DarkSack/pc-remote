namespace PcRemote.Modules.Input.Win32;

// ══════════════════════════════════════════════════════════════
// Mapeo de nombres de teclas a Virtual-Key codes.
// El cliente móvil manda nombres canónicos ("enter", "esc", "f5",
// "ctrl+shift+esc"). Aquí resolvemos.
// Ver https://learn.microsoft.com/en-us/windows/win32/inputdev/virtual-key-codes
// ══════════════════════════════════════════════════════════════
internal static class VirtualKeys
{
    public static readonly IReadOnlyDictionary<string, ushort> Map = new Dictionary<string, ushort>(StringComparer.OrdinalIgnoreCase)
    {
        // Modifiers
        ["shift"]  = 0x10, ["ctrl"] = 0x11, ["control"] = 0x11, ["alt"] = 0x12,
        ["lshift"] = 0xA0, ["rshift"] = 0xA1,
        ["lctrl"]  = 0xA2, ["rctrl"] = 0xA3,
        ["lalt"]   = 0xA4, ["ralt"]  = 0xA5,
        ["win"]    = 0x5B, ["lwin"] = 0x5B, ["rwin"] = 0x5C, ["meta"] = 0x5B,

        // Whitespace / control
        ["enter"] = 0x0D, ["return"] = 0x0D,
        ["tab"] = 0x09, ["backspace"] = 0x08, ["bksp"] = 0x08,
        ["esc"] = 0x1B, ["escape"] = 0x1B,
        ["space"] = 0x20, ["spacebar"] = 0x20,
        ["capslock"] = 0x14, ["numlock"] = 0x90, ["scrolllock"] = 0x91,
        ["printscreen"] = 0x2C, ["prtsc"] = 0x2C,
        ["pause"] = 0x13, ["break"] = 0x13,

        // Navigation
        ["insert"] = 0x2D, ["ins"] = 0x2D,
        ["delete"] = 0x2E, ["del"] = 0x2E,
        ["home"] = 0x24, ["end"] = 0x23,
        ["pageup"] = 0x21, ["pgup"] = 0x21,
        ["pagedown"] = 0x22, ["pgdn"] = 0x22,
        ["left"] = 0x25, ["up"] = 0x26, ["right"] = 0x27, ["down"] = 0x28,

        // Function keys
        ["f1"]  = 0x70, ["f2"] = 0x71, ["f3"] = 0x72, ["f4"] = 0x73,
        ["f5"]  = 0x74, ["f6"] = 0x75, ["f7"] = 0x76, ["f8"] = 0x77,
        ["f9"]  = 0x78, ["f10"] = 0x79, ["f11"] = 0x7A, ["f12"] = 0x7B,

        // Media
        ["volumemute"] = 0xAD, ["volumedown"] = 0xAE, ["volumeup"] = 0xAF,
        ["medianexttrack"] = 0xB0, ["mediaprevtrack"] = 0xB1,
        ["mediastop"] = 0xB2, ["mediaplaypause"] = 0xB3,

        // Numpad
        ["numpad0"] = 0x60, ["numpad1"] = 0x61, ["numpad2"] = 0x62, ["numpad3"] = 0x63,
        ["numpad4"] = 0x64, ["numpad5"] = 0x65, ["numpad6"] = 0x66, ["numpad7"] = 0x67,
        ["numpad8"] = 0x68, ["numpad9"] = 0x69,
        ["multiply"] = 0x6A, ["add"] = 0x6B, ["subtract"] = 0x6D, ["decimal"] = 0x6E, ["divide"] = 0x6F,
    };

    /// <summary>Resuelve una cadena tipo "a", "f5", "ctrl+shift+esc" a la lista de VKs.</summary>
    public static bool TryResolve(string keyExpr, out ushort[] vks)
    {
        var parts = keyExpr.Split('+', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var result = new ushort[parts.Length];
        for (int i = 0; i < parts.Length; i++)
        {
            var p = parts[i];
            if (Map.TryGetValue(p, out var vk))
            {
                result[i] = vk;
            }
            else if (p.Length == 1)
            {
                var ch = char.ToUpperInvariant(p[0]);
                if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9'))
                {
                    result[i] = (ushort)ch;
                }
                else { vks = Array.Empty<ushort>(); return false; }
            }
            else { vks = Array.Empty<ushort>(); return false; }
        }
        vks = result;
        return true;
    }
}
