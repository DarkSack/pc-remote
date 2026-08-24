using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace PcRemote.Modules.System.Win32;

/// <summary>
/// P/Invoke declarations for Win32 power/session APIs used by the System module.
/// Grouped in one place so they can be audited quickly.
/// </summary>
[SupportedOSPlatform("windows")]
internal static class NativeMethods
{
    // ── ExitWindowsEx flags ─────────────────────────────────────
    public const uint EWX_LOGOFF          = 0x00000000;
    public const uint EWX_SHUTDOWN        = 0x00000001;
    public const uint EWX_REBOOT          = 0x00000002;
    public const uint EWX_FORCE           = 0x00000004;
    public const uint EWX_POWEROFF        = 0x00000008;
    public const uint EWX_FORCEIFHUNG     = 0x00000010;

    public const uint SHTDN_REASON_MAJOR_APPLICATION = 0x00040000;
    public const uint SHTDN_REASON_MINOR_MAINTENANCE = 0x00000001;
    public const uint SHTDN_REASON_FLAG_PLANNED      = 0x80000000;

    public static uint PlannedReason =>
        SHTDN_REASON_MAJOR_APPLICATION | SHTDN_REASON_MINOR_MAINTENANCE | SHTDN_REASON_FLAG_PLANNED;

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool ExitWindowsEx(uint uFlags, uint dwReason);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool LockWorkStation();

    // ── SetSuspendState ─────────────────────────────────────────
    [DllImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

    // ── Token privileges (needed for shutdown / restart) ────────
    public const int SE_PRIVILEGE_ENABLED = 0x00000002;
    public const uint TOKEN_QUERY = 0x0008;
    public const uint TOKEN_ADJUST_PRIVILEGES = 0x0020;
    public const string SE_SHUTDOWN_NAME = "SeShutdownPrivilege";

    [StructLayout(LayoutKind.Sequential)]
    public struct LUID { public uint LowPart; public int HighPart; }

    [StructLayout(LayoutKind.Sequential)]
    public struct TOKEN_PRIVILEGES
    {
        public int PrivilegeCount;
        public LUID Luid;
        public int Attributes;
    }

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool OpenProcessToken(IntPtr processHandle, uint desiredAccess, out IntPtr tokenHandle);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool LookupPrivilegeValue(string? lpSystemName, string lpName, out LUID lpLuid);

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AdjustTokenPrivileges(
        IntPtr tokenHandle,
        [MarshalAs(UnmanagedType.Bool)] bool disableAllPrivileges,
        ref TOKEN_PRIVILEGES newState,
        int bufferLength,
        IntPtr previousState,
        IntPtr returnLength);

    [DllImport("kernel32.dll")]
    public static extern IntPtr GetCurrentProcess();

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool CloseHandle(IntPtr handle);

    /// <summary>Enables SE_SHUTDOWN_NAME on the current process token. Required before ExitWindowsEx.</summary>
    public static bool EnableShutdownPrivilege()
    {
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, out var token))
            return false;
        try
        {
            if (!LookupPrivilegeValue(null, SE_SHUTDOWN_NAME, out var luid))
                return false;

            var tp = new TOKEN_PRIVILEGES
            {
                PrivilegeCount = 1,
                Luid = luid,
                Attributes = SE_PRIVILEGE_ENABLED,
            };
            return AdjustTokenPrivileges(token, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero);
        }
        finally
        {
            CloseHandle(token);
        }
    }
}
