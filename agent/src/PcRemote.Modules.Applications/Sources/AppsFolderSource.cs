using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace PcRemote.Modules.Applications.Sources;

// ══════════════════════════════════════════════════════════════
// Fuente principal: la carpeta virtual "Aplicaciones" de Windows
// (shell:AppsFolder), la misma lista que "Todas las apps" del menú Inicio.
//
// Trae en una sola pasada lo que antes pedía dos fuentes y un PowerShell:
//   - accesos directos del menú Inicio (id = ruta resuelta, "{GUID}\…\app.exe")
//   - apps de la Store (id = AUMID, "Paquete_hash!App")
//   - accesos a URL, p. ej. juegos de Steam (id = "steam://rungameid/…")
// Es lo que hacía Get-StartApps por dentro, sin arrancar powershell.exe (~2 s).
//
// Todo se abre y se dibuja con "shell:AppsFolder\<id>", así que lanzar y el
// icono funcionan igual para los tres tipos.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal static class AppsFolderSource
{
    public const string LaunchPrefix = @"shell:AppsFolder\";

    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(20);

    public static IEnumerable<AppEntry> Enumerate()
    {
        // Shell COM objects want an STA thread. A short-lived one per scan: scans
        // are rare, and sharing the icon thread would stall icons during a scan.
        List<(string Name, string AppId)>? items = null;
        Exception? error = null;
        var thread = new Thread(() =>
        {
            try { items = Read(); }
            catch (Exception ex) { error = ex; }
        }) { IsBackground = true, Name = "AppsFolder scan" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        if (!thread.Join(Timeout))
            throw new TimeoutException($"Enumerating shell:AppsFolder took longer than {Timeout.TotalSeconds}s");
        if (error is not null) throw error;

        foreach (var (name, appId) in items!)
        {
            // A Store app id is an AUMID ("Family_hash!App"); everything else is a
            // desktop shortcut or a URL shortcut.
            var store = appId.Contains('!') && !appId.Contains('\\') && !appId.Contains("://");
            yield return new AppEntry(
                Id:     (store ? "uwp:" : "startmenu:") + appId,
                Name:   name,
                Source: store ? "uwp" : "startmenu",
                Launch: LaunchPrefix + appId);
        }
    }

    private static List<(string, string)> Read()
    {
        var folderId = FolderIdAppsFolder;
        var itemIid = typeof(IShellItem).GUID;
        Marshal.ThrowExceptionForHR(SHGetKnownFolderItem(ref folderId, 0, IntPtr.Zero, ref itemIid, out var folder));

        var result = new List<(string, string)>();
        IEnumShellItems? items = null;
        try
        {
            var bhid = BhidEnumItems;
            var enumIid = typeof(IEnumShellItems).GUID;
            Marshal.ThrowExceptionForHR(folder.BindToHandler(IntPtr.Zero, ref bhid, ref enumIid, out var enumPtr));
            items = (IEnumShellItems)Marshal.GetObjectForIUnknown(enumPtr);
            Marshal.Release(enumPtr);

            while (items.Next(1, out var item, out var fetched) == 0 && fetched == 1)
            {
                try
                {
                    var name = DisplayName(item, SIGDN.NormalDisplay);
                    var appId = DisplayName(item, SIGDN.ParentRelativeParsing);
                    if (!string.IsNullOrWhiteSpace(name) && !string.IsNullOrWhiteSpace(appId))
                        result.Add((name!, appId!));
                }
                finally { Marshal.ReleaseComObject(item); }
            }
        }
        finally
        {
            if (items is not null) Marshal.ReleaseComObject(items);
            Marshal.ReleaseComObject(folder);
        }
        return result;
    }

    private static string? DisplayName(IShellItem item, SIGDN kind)
    {
        if (item.GetDisplayName(kind, out var ptr) != 0 || ptr == IntPtr.Zero) return null;
        try { return Marshal.PtrToStringUni(ptr); }
        finally { Marshal.FreeCoTaskMem(ptr); }
    }

    // ── COM ──────────────────────────────────────────────────

    private static readonly Guid FolderIdAppsFolder = new("1e87508d-89c2-42f0-8a7e-645a0f50ca58");
    private static readonly Guid BhidEnumItems      = new("94f60519-2850-4924-aa5a-d15e84868039");

    private enum SIGDN : uint
    {
        NormalDisplay         = 0x00000000,
        ParentRelativeParsing = 0x80018001,
    }

    [ComImport]
    [Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem
    {
        [PreserveSig] int BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
        [PreserveSig] int GetParent(out IShellItem ppsi);
        [PreserveSig] int GetDisplayName(SIGDN sigdnName, out IntPtr ppszName);
        [PreserveSig] int GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
        [PreserveSig] int Compare(IShellItem psi, uint hint, out int piOrder);
    }

    [ComImport]
    [Guid("70629033-e363-4a28-a567-0db78006e6d7")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IEnumShellItems
    {
        [PreserveSig] int Next(uint celt, out IShellItem rgelt, out uint pceltFetched);
        [PreserveSig] int Skip(uint celt);
        [PreserveSig] int Reset();
        [PreserveSig] int Clone(out IEnumShellItems ppenum);
    }

    [DllImport("shell32.dll")]
    private static extern int SHGetKnownFolderItem(
        ref Guid rfid, uint flags, IntPtr hToken, ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out IShellItem ppv);
}
