using System.Diagnostics;
using System.Globalization;
using System.Net.NetworkInformation;
using System.Runtime.Versioning;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace PcRemote.Modules.SystemInfo;

public sealed record GpuStats(string? Name, double? Usage, double? TempC, long? VramUsedMB, long? VramTotalMB, int? ClockMHz);
public sealed record DiskStats(string Name, string? Label, double TotalGB, double FreeGB, double UsedPct);
public sealed record NetStats(long DownBps, long UpBps);

/// <summary>
/// Everything beyond CPU % and RAM, without admin rights and without vendor SDKs:
///
/// - GPU usage: "GPU Engine" performance counters, read as one category snapshot
///   per sample (one counter per process × engine would be hundreds of reads).
///   Usage = busiest engine, summed over processes — what Task Manager shows.
/// - VRAM: "GPU Adapter Memory\Dedicated Usage"; total and name from the display
///   adapter's registry key.
/// - NVIDIA: nvidia-smi, if installed, adds temperature and clock (every 3 s, cached).
/// - CPU temperature: "Thermal Zone Information" (ACPI; no admin, not on every PC).
/// - CPU clock: "% Processor Performance" × nominal MHz.
/// - Disks: fixed drives. Network: bytes/s over the interfaces that are up.
///
/// Anything a PC does not expose comes back null and the app shows "—".
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed partial class HardwareSampler : IDisposable
{
    private readonly object _lock = new();

    // GPU Engine
    private readonly bool _hasGpuEngine = PerformanceCounterCategory.Exists("GPU Engine");
    private readonly bool _hasGpuMemory = PerformanceCounterCategory.Exists("GPU Adapter Memory");
    private Dictionary<string, CounterSample> _prevEngine = new();
    private readonly (string? Name, long? TotalMB) _adapter = ReadAdapter();

    // CPU clock / temperature
    private readonly PerformanceCounter? _cpuPerf = TryCounter("Processor Information", "% Processor Performance", "_Total");
    private readonly double? _nominalMHz = ReadNominalMHz();
    private readonly bool _hasThermal = PerformanceCounterCategory.Exists("Thermal Zone Information");

    // Network
    private long _lastRx, _lastTx;
    private long _lastNetTicks;

    // nvidia-smi
    private readonly string? _nvidiaSmi = FindNvidiaSmi();
    private (double? Temp, int? Clock, double? Usage, long? UsedMB, long? TotalMB, string? Name) _nvidia;
    private long _nvidiaAt;
    private int _nvidiaBusy;

    public double? CpuFrequencyMHz()
    {
        if (_cpuPerf is null || _nominalMHz is null) return null;
        try
        {
            lock (_lock) return Math.Round(_cpuPerf.NextValue() / 100.0 * _nominalMHz.Value);
        }
        catch { return null; }
    }

    public double? CpuTemperatureC()
    {
        if (!_hasThermal) return null;
        try
        {
            var cat = new PerformanceCounterCategory("Thermal Zone Information");
            var data = cat.ReadCategory();
            // "High Precision Temperature" is in tenths of kelvin; "Temperature" in kelvin.
            double? best = null;
            if (data.Contains("high precision temperature"))
            {
                foreach (InstanceData d in data["high precision temperature"].Values)
                    if (d.RawValue > 0) best = Math.Max(best ?? 0, d.RawValue / 10.0 - 273.15);
            }
            else if (data.Contains("temperature"))
            {
                foreach (InstanceData d in data["temperature"].Values)
                    if (d.RawValue > 0) best = Math.Max(best ?? 0, d.RawValue - 273.15);
            }
            // Many firmwares report a fixed 27.8 °C placeholder or nonsense: drop it.
            return best is > 1 and < 130 ? Math.Round(best.Value, 1) : null;
        }
        catch { return null; }
    }

    public GpuStats? Gpu()
    {
        double? usage = null;
        long? used = null;
        if (_hasGpuEngine)
        {
            try { usage = GpuUsage(); } catch { /* counters can vanish with a driver update */ }
        }
        if (_hasGpuMemory)
        {
            try { used = VramUsedMB(); } catch { }
        }

        var nv = Nvidia();
        var name = nv.Name ?? _adapter.Name;
        if (name is null && usage is null && nv.Temp is null) return null;
        return new GpuStats(
            name,
            nv.Usage ?? usage,
            nv.Temp,
            nv.UsedMB ?? used,
            nv.TotalMB ?? _adapter.TotalMB,
            nv.Clock);
    }

    private double? GpuUsage()
    {
        var data = new PerformanceCounterCategory("GPU Engine").ReadCategory();
        if (!data.Contains("utilization percentage")) return null;
        var current = new Dictionary<string, CounterSample>();
        var perEngine = new Dictionary<string, double>();
        foreach (InstanceData d in data["utilization percentage"].Values)
        {
            current[d.InstanceName] = d.Sample;
            if (!_prevEngine.TryGetValue(d.InstanceName, out var prev)) continue;
            var v = CounterSample.Calculate(prev, d.Sample);
            if (float.IsNaN(v) || v <= 0) continue;
            // pid_1234_luid_0x..._phys_0_eng_3_engtype_3D → group by adapter + engine.
            var m = EngineKey().Match(d.InstanceName);
            var key = m.Success ? m.Value : d.InstanceName;
            perEngine[key] = perEngine.GetValueOrDefault(key) + v;
        }
        bool first;
        lock (_lock)
        {
            first = _prevEngine.Count == 0;
            _prevEngine = current;
        }
        if (first) return null;
        return Math.Round(Math.Min(100, perEngine.Count == 0 ? 0 : perEngine.Values.Max()), 1);
    }

    private static long? VramUsedMB()
    {
        var data = new PerformanceCounterCategory("GPU Adapter Memory").ReadCategory();
        if (!data.Contains("dedicated usage")) return null;
        long max = 0;
        foreach (InstanceData d in data["dedicated usage"].Values) max = Math.Max(max, d.RawValue);
        return max / (1024 * 1024);
    }

    public IReadOnlyList<DiskStats> Disks()
    {
        var list = new List<DiskStats>();
        foreach (var d in DriveInfo.GetDrives())
        {
            try
            {
                if (d.DriveType != DriveType.Fixed || !d.IsReady || d.TotalSize == 0) continue;
                var total = d.TotalSize / 1024.0 / 1024 / 1024;
                var free = d.AvailableFreeSpace / 1024.0 / 1024 / 1024;
                list.Add(new DiskStats(d.Name.TrimEnd('\\'), string.IsNullOrEmpty(d.VolumeLabel) ? null : d.VolumeLabel,
                    Math.Round(total, 1), Math.Round(free, 1), Math.Round((total - free) / total * 100, 1)));
            }
            catch { /* drive went away */ }
        }
        return list;
    }

    public NetStats Network()
    {
        long rx = 0, tx = 0;
        foreach (var n in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (n.OperationalStatus != OperationalStatus.Up ||
                n.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            try
            {
                var s = n.GetIPStatistics();
                rx += s.BytesReceived;
                tx += s.BytesSent;
            }
            catch { }
        }
        lock (_lock)
        {
            var now = Stopwatch.GetTimestamp();
            NetStats result = new(0, 0);
            if (_lastNetTicks != 0)
            {
                var secs = Stopwatch.GetElapsedTime(_lastNetTicks, now).TotalSeconds;
                if (secs > 0.05)
                    result = new NetStats((long)(Math.Max(0, rx - _lastRx) / secs), (long)(Math.Max(0, tx - _lastTx) / secs));
            }
            _lastRx = rx; _lastTx = tx; _lastNetTicks = now;
            return result;
        }
    }

    // ── nvidia-smi ──────────────────────────────────────────
    private (double? Temp, int? Clock, double? Usage, long? UsedMB, long? TotalMB, string? Name) Nvidia()
    {
        if (_nvidiaSmi is null) return default;
        var age = Environment.TickCount64 - Interlocked.Read(ref _nvidiaAt);
        if (age > 3000 && Interlocked.Exchange(ref _nvidiaBusy, 1) == 0)
        {
            // Refresh in the background; the sample returns the cached values meanwhile.
            _ = Task.Run(() =>
            {
                try { _nvidia = QueryNvidia(_nvidiaSmi) ?? _nvidia; }
                finally
                {
                    Interlocked.Exchange(ref _nvidiaAt, Environment.TickCount64);
                    Interlocked.Exchange(ref _nvidiaBusy, 0);
                }
            });
        }
        return _nvidia;
    }

    private static (double?, int?, double?, long?, long?, string?)? QueryNvidia(string exe)
    {
        try
        {
            var psi = new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                ArgumentList =
                {
                    "--query-gpu=temperature.gpu,clocks.gr,utilization.gpu,memory.used,memory.total,name",
                    "--format=csv,noheader,nounits",
                },
            };
            using var p = Process.Start(psi);
            if (p is null) return null;
            var line = p.StandardOutput.ReadLine();
            if (!p.WaitForExit(2000)) { try { p.Kill(); } catch { } return null; }
            if (string.IsNullOrWhiteSpace(line)) return null;
            var f = line.Split(',', StringSplitOptions.TrimEntries);
            if (f.Length < 6) return null;
            static double? D(string s) => double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : null;
            return (D(f[0]), (int?)D(f[1]), D(f[2]), (long?)D(f[3]), (long?)D(f[4]), f[5]);
        }
        catch { return null; }
    }

    private static string? FindNvidiaSmi()
    {
        foreach (var path in new[]
                 {
                     Path.Combine(Environment.SystemDirectory, "nvidia-smi.exe"),
                     Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "NVIDIA Corporation", "NVSMI", "nvidia-smi.exe"),
                 })
        {
            if (File.Exists(path)) return path;
        }
        return null;
    }

    // ── static info ─────────────────────────────────────────
    /// <summary>The display adapter with the most memory: the discrete GPU on a laptop with two.</summary>
    private static (string? Name, long? TotalMB) ReadAdapter()
    {
        try
        {
            using var cls = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}");
            if (cls is null) return default;
            (string? Name, long? TotalMB) best = default;
            foreach (var sub in cls.GetSubKeyNames().Where(n => n.All(char.IsDigit)))
            {
                using var k = cls.OpenSubKey(sub);
                var name = k?.GetValue("DriverDesc") as string;
                if (name is null) continue;
                long? bytes = k!.GetValue("HardwareInformation.qwMemorySize") switch
                {
                    long l => l,
                    byte[] b when b.Length >= 8 => BitConverter.ToInt64(b),
                    _ => k.GetValue("HardwareInformation.MemorySize") switch
                    {
                        int i => (uint)i,
                        byte[] b when b.Length >= 4 => BitConverter.ToUInt32(b),
                        _ => null,
                    },
                };
                var mb = bytes / (1024 * 1024);
                if (best.Name is null || (mb ?? 0) > (best.TotalMB ?? 0)) best = (name, mb);
            }
            return best;
        }
        catch { return default; }
    }

    private static double? ReadNominalMHz()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            return key?.GetValue("~MHz") is int mhz && mhz > 0 ? mhz : null;
        }
        catch { return null; }
    }

    private static PerformanceCounter? TryCounter(string category, string counter, string instance)
    {
        try
        {
            if (!PerformanceCounterCategory.Exists(category)) return null;
            var c = new PerformanceCounter(category, counter, instance, readOnly: true);
            c.NextValue();
            return c;
        }
        catch { return null; }
    }

    [GeneratedRegex(@"luid_0x[0-9a-fA-F]+_0x[0-9a-fA-F]+_phys_\d+_eng_\d+")]
    private static partial Regex EngineKey();

    public void Dispose() => _cpuPerf?.Dispose();
}
