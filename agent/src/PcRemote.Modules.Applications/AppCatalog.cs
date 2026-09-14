using System.Runtime.Versioning;
using PcRemote.Modules.Applications.Sources;

namespace PcRemote.Modules.Applications;

// ══════════════════════════════════════════════════════════════
// Catálogo de apps instaladas, compartido por ApplicationsModule (lista,
// lanzar, stream) y AppIconsModule (iconos).
//
// Se mantiene al día solo:
//   - FileSystemWatcher sobre las carpetas del menú Inicio: instalar o
//     desinstalar casi cualquier app crea o borra un .lnk ahí. Los eventos
//     llegan en ráfagas (un instalador toca decenas de ficheros), así que se
//     espera a 3 s de calma y se recalcula una vez.
//   - Recalculado periódico cada 10 min para lo que no deja .lnk: entradas
//     de registro sin acceso directo y apps de la Store.
//
// Cada recálculo que cambia algo sube `Version`; los streams comparan esa
// versión para saber si tienen que empujar una lista nueva.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal static class AppCatalog
{
    private static readonly TimeSpan Debounce = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan Periodic = TimeSpan.FromMinutes(10);

    private static readonly object Gate = new();
    private static List<AppEntry> _apps = new();
    private static Dictionary<string, AppEntry> _byId = new();
    private static long _version;
    private static bool _loaded;
    private static bool _watching;

    private static readonly List<FileSystemWatcher> Watchers = new();
    private static System.Threading.Timer? _debounceTimer;
    private static System.Threading.Timer? _periodicTimer;

    /// <summary>Increments whenever the set of apps (ids or names) changes.</summary>
    public static long Version => Interlocked.Read(ref _version);

    // Serialises rescans. Kept apart from Gate: a rescan runs PowerShell (~2 s),
    // and holding Gate that long would make every launch wait behind it.
    private static readonly SemaphoreSlim ScanGate = new(1, 1);

    public static IReadOnlyList<AppEntry> GetAll(bool refresh = false)
    {
        EnsureWatching();
        if (!_loaded || refresh)
        {
            ScanGate.Wait();
            try
            {
                // Another caller may have finished the first load while we waited.
                if (!_loaded || refresh) Swap(Scan());
            }
            finally { ScanGate.Release(); }
        }
        lock (Gate) return _apps;
    }

    public static AppEntry? Find(string id)
    {
        GetAll();
        lock (Gate) return _byId.GetValueOrDefault(id);
    }

    private static List<AppEntry> Scan()
    {
        var all = new List<AppEntry>();
        SafeAppend(all, StartMenuSource.Enumerate);
        SafeAppend(all, RegistrySource.Enumerate);
        SafeAppend(all, UwpSource.Enumerate);

        // Preference: startmenu > registry > uwp (the order they are added in).
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var fresh = new List<AppEntry>();
        foreach (var a in all)
            if (seen.Add(a.Name.Trim())) fresh.Add(a);
        fresh.Sort((x, y) => string.Compare(x.Name, y.Name, StringComparison.OrdinalIgnoreCase));
        return fresh;
    }

    private static void Swap(List<AppEntry> fresh)
    {
        Dictionary<string, AppEntry> byId;
        lock (Gate)
        {
            var changed = !_loaded ||
                fresh.Count != _apps.Count ||
                fresh.Where((a, i) => a.Id != _apps[i].Id || a.Name != _apps[i].Name).Any();

            _apps = fresh;
            _byId = byId = fresh.GroupBy(a => a.Id).ToDictionary(g => g.Key, g => g.First());
            _loaded = true;
            if (!changed) return;
            Interlocked.Increment(ref _version);
        }
        AppIcons.Forget(id => !byId.ContainsKey(id));
    }

    private static void SafeAppend(List<AppEntry> list, Func<IEnumerable<AppEntry>> source)
    {
        try { list.AddRange(source()); } catch { /* una fuente rota no debe tirar todo */ }
    }

    private static void EnsureWatching()
    {
        lock (Gate)
        {
            if (_watching) return;
            _watching = true;
        }

        foreach (var root in StartMenuSource.Roots())
        {
            if (!Directory.Exists(root)) continue;
            try
            {
                var w = new FileSystemWatcher(root, "*.lnk")
                {
                    IncludeSubdirectories = true,
                    NotifyFilter = NotifyFilters.FileName | NotifyFilters.DirectoryName | NotifyFilters.LastWrite,
                    // Installers touch many files at once; the default 8 KB buffer overflows.
                    InternalBufferSize = 64 * 1024,
                };
                w.Created += (_, _) => ScheduleRefresh();
                w.Deleted += (_, _) => ScheduleRefresh();
                w.Renamed += (_, _) => ScheduleRefresh();
                w.Changed += (_, _) => ScheduleRefresh();
                w.Error   += (_, _) => ScheduleRefresh(); // buffer overflow: just rescan
                w.EnableRaisingEvents = true;
                Watchers.Add(w);
            }
            catch
            {
                // No watcher (e.g. folder permissions): the periodic rescan still covers it.
            }
        }

        _debounceTimer = new System.Threading.Timer(_ => RefreshInBackground(), null, Timeout.Infinite, Timeout.Infinite);
        _periodicTimer = new System.Threading.Timer(_ => RefreshInBackground(), null, Periodic, Periodic);
    }

    private static void ScheduleRefresh() =>
        _debounceTimer?.Change(Debounce, Timeout.InfiniteTimeSpan);

    private static void RefreshInBackground()
    {
        // Never stack two rescans; if one is already running it will see the change.
        if (!ScanGate.Wait(0)) return;
        try
        {
            Swap(Scan());
        }
        catch
        {
            // Keep the previous list.
        }
        finally
        {
            ScanGate.Release();
        }
    }
}
