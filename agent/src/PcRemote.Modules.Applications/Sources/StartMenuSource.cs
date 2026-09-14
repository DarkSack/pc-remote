namespace PcRemote.Modules.Applications.Sources;

// ══════════════════════════════════════════════════════════════
// Fuente Start Menu: enumera .lnk en las carpetas del menú Inicio
// del usuario y del sistema. El launch abre el .lnk directamente
// (Windows resuelve el target).
// ══════════════════════════════════════════════════════════════
internal static class StartMenuSource
{
    public static IEnumerable<AppEntry> Enumerate()
    {
        foreach (var root in Roots())
        {
            if (!Directory.Exists(root)) continue;
            IEnumerable<string> shortcuts;
            try { shortcuts = Directory.EnumerateFiles(root, "*.lnk", SearchOption.AllDirectories); }
            catch { continue; }

            foreach (var lnk in shortcuts)
            {
                string name;
                try { name = Path.GetFileNameWithoutExtension(lnk); }
                catch { continue; }
                if (string.IsNullOrWhiteSpace(name)) continue;
                if (name.Contains("Uninstall", StringComparison.OrdinalIgnoreCase)) continue;

                yield return new AppEntry(
                    Id:     "startmenu:" + lnk,
                    Name:   name,
                    Source: "startmenu",
                    Launch: lnk);
            }
        }
    }

    public static IEnumerable<string> Roots()
    {
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs");
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs");
    }
}
