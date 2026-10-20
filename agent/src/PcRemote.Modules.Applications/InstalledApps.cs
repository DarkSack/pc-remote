using System.Runtime.Versioning;

namespace PcRemote.Modules.Applications;

/// <summary>Names of the installed apps, for other modules (notifications say which app spoke).</summary>
[SupportedOSPlatform("windows")]
public static class InstalledApps
{
    /// <summary>
    /// Start-menu name of the app with this AppUserModelID or shell:AppsFolder id
    /// ("Microsoft.WindowsStore_8wekyb3d8bbwe!App", "Chrome"…), or null if it has none.
    /// </summary>
    public static string? NameOf(string appId) =>
        AppCatalog.Find("uwp:" + appId)?.Name ?? AppCatalog.Find("startmenu:" + appId)?.Name;
}
