using System.Runtime.Versioning;
using System.Text.RegularExpressions;
using PcRemote.Modules.Applications.Sources;

namespace PcRemote.Modules.Applications;

// ══════════════════════════════════════════════════════════════
// Catálogo de apps instaladas, compartido por ApplicationsModule (lista,
// lanzar, stream) y AppIconsModule (iconos).
//
// Se mantiene al día solo:
//   - FileSystemWatcher sobre las carpetas del menú Inicio: instalar o
//     desinstalar casi cualquier app crea o borra un acceso directo ahí. Los
//     eventos llegan en ráfagas (un instalador toca decenas de ficheros), así
//     que se espera a 3 s de calma y se recalcula una vez.
//   - Recalculado periódico cada 10 min para lo que no deja acceso directo:
//     entradas de registro y apps de la Store.
//
// Cada recálculo que cambia algo sube `Version`; los streams comparan esa
// versión para saber si tienen que empujar una lista nueva.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal static class AppCatalog
{
    private static readonly TimeSpan Debounce = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan Periodic = TimeSpan.FromMinutes(10);

    /// <summary>
    /// Uninstallers are not apps. Checked on the final name, whatever the source:
    /// Get-StartApps lists the same "Uninstall X" shortcuts the Start Menu source
    /// skips, so a per-source filter let them back in through the Store source.
    /// </summary>
    // Anchored on purpose: "Uninstall X" / "Desinstalar X" / "X Uninstall" are
    // shortcuts to an uninstaller, while "Revo Uninstaller" is an app someone wants.
    private static readonly Regex Uninstaller = new(@"^(uninstall|desinstalar)\b|\b(uninstall|desinstalar)$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private static readonly object Gate = new();
    private static List<AppEntry> _apps = new();
    private static Dictionary<string, AppEntry> _byId = new();
    private static long _version;
    private static bool _loaded;
    private static bool _watching;

    private static readonly List<FileSystemWatcher> Watchers = new();
    private static System.Threading.Timer? _debounceTimer;
    private static System.Threading.Timer? _periodicTimer;

    // Serialises rescans. Kept apart from Gate: a rescan runs PowerShell (~2 s),
    // and holding Gate that long would make every launch wait behind it.
    private static readonly SemaphoreSlim ScanGate = new(1, 1);

    /// <summary>Set when a rescan is wanted; a scan already running picks it up when it finishes.</summary>
    private static int _dirty;

    /// <summary>Last successful result of each source. Guarded by ScanGate.</summary>
    private static readonly Dictionary<string, List<AppEntry>> LastGood = new();

    /// <summary>Increments whenever the set of apps (ids or names) changes.</summary>
    public static long Version => Interlocked.Read(ref _version);

    public static IReadOnlyList<AppEntry> GetAll(bool refresh = false) => Snapshot(refresh).Apps;

    /// <summary>
    /// The list and the version it belongs to, read together. Reading them apart let
    /// a stream pair an old list with the new version and never send the new list.
    /// </summary>
    public static (IReadOnlyList<AppEntry> Apps, long Version) Snapshot(bool refresh = false)
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

            // A watcher event that arrived while we held the gate was skipped by the
            // background refresh; run it now instead of waiting for the next change.
            if (Volatile.Read(ref _dirty) == 1)
                ThreadPool.QueueUserWorkItem(_ => RefreshInBackground());
        }
        lock (Gate) return (_apps, _version);
    }

    public static AppEntry? Find(string id)
    {
        GetAll();
        lock (Gate) return _byId.GetValueOrDefault(id);
    }

    /// <summary>Caller holds ScanGate.</summary>
    private static List<AppEntry> Scan()
    {
        var all = new List<AppEntry>();
        all.AddRange(FromSource("startmenu", StartMenuSource.Enumerate));
        all.AddRange(FromSource("registry", RegistrySource.Enumerate));
        all.AddRange(FromSource("uwp", UwpSource.Enumerate));

        // Preference: startmenu > registry > uwp (the order they are added in).
        // Ids are deduplicated too, not only names: the phone keys its list by id,
        // and two rows with the same key crash a Compose LazyColumn.
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var fresh = new List<AppEntry>();
        foreach (var a in all)
        {
            var name = a.Name.Trim();
            if (name.Length == 0 || Uninstaller.IsMatch(name)) continue;
            if (names.Add(name) && ids.Add(a.Id)) fresh.Add(a);
        }
        fresh.Sort((x, y) => string.Compare(x.Name, y.Name, StringComparison.OrdinalIgnoreCase));
        return fresh;
    }

    /// <summary>
    /// A source that fails keeps its previous result. Before, a PowerShell hiccup
    /// during the periodic rescan dropped every Store app from the catalog, and the
    /// phone, seeing them "uninstalled", deleted them from favorites and recents.
    /// </summary>
    private static List<AppEntry> FromSource(string name, Func<IEnumerable<AppEntry>> source)
    {
        try
        {
            var list = source().ToList();
            LastGood[name] = list;
            return list;
        }
        catch
        {
            return LastGood.GetValueOrDefault(name) ?? new List<AppEntry>();
        }
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
            _byId = byId = fresh.ToDictionary(a => a.Id);
            _loaded = true;
            if (!changed) return;
            Interlocked.Increment(ref _version);
        }
        AppIcons.Forget(id => !byId.ContainsKey(id));
    }

    private static void EnsureWatching()
    {
        lock (Gate)
        {
            if (_watching) return;
            _watching = true;
        }

        // Timers before watchers: an event raised in between would find no timer and be lost.
        _debounceTimer = new System.Threading.Timer(_ => RefreshInBackground(), null, Timeout.Infinite, Timeout.Infinite);
        _periodicTimer = new System.Threading.Timer(_ => RefreshInBackground(), null, Periodic, Periodic);

        foreach (var root in StartMenuSource.Roots())
        {
            if (!Directory.Exists(root)) continue;
            try
            {
                // No "*.lnk" filter: an uninstaller that deletes a whole folder raises
                // a single directory event, which that filter never matched.
                var w = new FileSystemWatcher(root)
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
    }

    private static void ScheduleRefresh() =>
        _debounceTimer?.Change(Debounce, Timeout.InfiniteTimeSpan);

    private static void RefreshInBackground()
    {
        Interlocked.Exchange(ref _dirty, 1);
        while (true)
        {
            // Never stack two rescans. The one running re-checks _dirty before it ends,
            // so a change that arrives mid-scan (after it already read the Start Menu)
            // is not lost until the periodic rescan 10 minutes later.
            if (!ScanGate.Wait(0)) return;
            try
            {
                while (Interlocked.Exchange(ref _dirty, 0) == 1)
                {
                    try { Swap(Scan()); }
                    catch { /* keep the previous list */ }
                }
            }
            finally
            {
                ScanGate.Release();
            }
            // Set between our last check and Release, by a caller that found the gate taken.
            if (Volatile.Read(ref _dirty) == 0) return;
        }
    }
}
