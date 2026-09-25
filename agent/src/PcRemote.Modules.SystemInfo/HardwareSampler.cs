using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Microsoft.Win32;

namespace PcRemote.Modules.SystemInfo;

// ══════════════════════════════════════════════════════════════
// Hardware sampling for systeminfo.stats.
//
// One shared sampler: two phones streaming stats (or a request next to a
// stream) reuse a sample younger than 700 ms instead of reading every
// counter twice. Everything beyond CPU and RAM is best effort and comes
// back null when Windows does not expose it to a non-admin process:
//
//   GPU      "GPU Engine" counters (what Task Manager uses), read in one
//            ReadCategory call; usage = busiest engine type of the busiest
//            adapter. VRAM from "GPU Adapter Memory", total and name from
//            the display adapter's registry key.
//   CPU MHz  "% Processor Performance" × the nominal clock (turbo shows >100%).
//   CPU °C   ACPI thermal zones ("Thermal Zone Information"). Not the
//            package sensor — that needs a kernel driver — but close, and
//            readable without admin. Null on PCs that expose no zone.
//   Disks    fixed drives (cached 10 s).
//   Network  bytes/s over the interfaces that are up (not loopback/tunnel).
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
internal sealed class HardwareSampler : IDisposable
{
    private static readonly TimeSpan Fresh = TimeSpan.FromMilliseconds(700);

    private readonly object _lock = new();
    private readonly PerformanceCounter _cpu = new("Processor", "% Processor Time", "_Total");
    private readonly PerformanceCounter? _cpuPerf = TryCounter("Processor Information", "% Processor Performance", "_Total");
    private readonly PerformanceCounterCategory? _gpuEngine = TryCategory("GPU Engine");
    private readonly PerformanceCounterCategory? _gpuMemory = TryCategory("GPU Adapter Memory");
    private readonly PerformanceCounterCategory? _thermal = TryCategory("Thermal Zone Information");
    private readonly int? _baseMHz = ReadBaseMHz();
    private readonly GpuAdapter? _adapter = GpuAdapter.Detect();

    private Dictionary<string, CounterSample> _gpuPrev = new();
    private long _netRx, _netTx;
    private long _netTicks;
    private (DateTime at, object[] list) _disks = (DateTime.MinValue, Array.Empty<object>());
    private DateTime _lastAt = DateTime.MinValue;
    private StatsSample? _last;
    private bool _primed;

    public GpuAdapter? Adapter => _adapter;

    public StatsSample Sample()
    {
        lock (_lock)
        {
            if (_last is not null && DateTime.UtcNow - _lastAt < Fresh) return _last;
            if (!_primed)
            {
                // Rate counters need two readings; the first one is always 0.
                _cpu.NextValue();
                _cpuPerf?.NextValue();
                ReadGpuUsage();
                ReadNet();
                Thread.Sleep(200);
                _primed = true;
            }

            var mem = Memory.Status();
            _last = new StatsSample(
                Cpu: Math.Round(_cpu.NextValue(), 1),
                CpuFreqMHz: CpuMHz(),
                CpuTempC: ThermalC(),
                RamPct: mem is null ? 0 : Math.Round(mem.Value.usedPct, 1),
                RamUsedMB: mem?.usedMB ?? 0,
                RamTotalMB: mem?.totalMB ?? 0,
                Gpu: Gpu(),
                Disks: Disks(),
                Net: ReadNet(),
                UptimeSec: Environment.TickCount64 / 1000,
                Ts: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            _lastAt = DateTime.UtcNow;
            return _last;
        }
    }

    private int? CpuMHz()
    {
        if (_cpuPerf is null || _baseMHz is null) return null;
        try { return (int)Math.Round(_cpuPerf.NextValue() / 100.0 * _baseMHz.Value); }
        catch { return null; }
    }

    private double? ThermalC()
    {
        if (_thermal is null) return null;
        try
        {
            var data = _thermal.ReadCategory();
            if (!data.Contains("temperature")) return null;
            double max = 0;
            foreach (InstanceData d in data["temperature"].Values) max = Math.Max(max, d.RawValue);
            // Kelvin. Zones that report nothing say 0; a real CPU is never under 5 °C.
            var c = max - 273.15;
            return c is > 5 and < 125 ? Math.Round(c, 1) : null;
        }
        catch { return null; }
    }

    private object? Gpu()
    {
        if (_adapter is null && _gpuEngine is null) return null;
        var usage = ReadGpuUsage();
        long? vramUsed = null;
        if (_gpuMemory is not null)
        {
            try
            {
                var data = _gpuMemory.ReadCategory();
                if (data.Contains("dedicated usage"))
                {
                    long max = 0;
                    foreach (InstanceData d in data["dedicated usage"].Values) max = Math.Max(max, d.RawValue);
                    vramUsed = max / (1024 * 1024);
                }
            }
            catch { /* counters vanish while a driver updates */ }
        }
        return new
        {
            name = _adapter?.Name,
            usage,
            vramUsedMB = vramUsed,
            vramTotalMB = _adapter?.MemoryMB,
            tempC = (double?)null,
        };
    }

    /// <summary>Utilisation of the busiest engine type ("3D", "VideoDecode"…) summed over processes, like Task Manager.</summary>
    private double? ReadGpuUsage()
    {
        if (_gpuEngine is null) return null;
        try
        {
            var data = _gpuEngine.ReadCategory();
            if (!data.Contains("utilization percentage")) return null;
            var next = new Dictionary<string, CounterSample>();
            var byEngine = new Dictionary<string, double>();
            foreach (InstanceData d in data["utilization percentage"].Values)
            {
                next[d.InstanceName] = d.Sample;
                if (!_gpuPrev.TryGetValue(d.InstanceName, out var prev)) continue;
                var value = CounterSample.Calculate(prev, d.Sample);
                // "pid_1234_luid_0x0_0x1_phys_0_eng_3_engtype_3D" → per adapter + engine type.
                var name = d.InstanceName;
                var luid = Between(name, "luid_", "_phys");
                var type = name[(name.IndexOf("engtype_", StringComparison.Ordinal) + 8)..];
                var key = luid + "|" + type;
                byEngine[key] = byEngine.GetValueOrDefault(key) + value;
            }
            _gpuPrev = next;
            return byEngine.Count == 0 ? 0 : Math.Round(Math.Min(100, byEngine.Values.Max()), 1);
        }
        catch { return null; }
    }

    private static string Between(string s, string a, string b)
    {
        var i = s.IndexOf(a, StringComparison.Ordinal);
        if (i < 0) return "";
        i += a.Length;
        var j = s.IndexOf(b, i, StringComparison.Ordinal);
        return j < 0 ? s[i..] : s[i..j];
    }

    private object[] Disks()
    {
        if (DateTime.UtcNow - _disks.at < TimeSpan.FromSeconds(10)) return _disks.list;
        var list = new List<object>();
        foreach (var d in DriveInfo.GetDrives())
        {
            try
            {
                if (d.DriveType != DriveType.Fixed || !d.IsReady || d.TotalSize == 0) continue;
                var used = d.TotalSize - d.TotalFreeSpace;
                list.Add(new
                {
                    name = d.Name.TrimEnd('\\'),
                    label = d.VolumeLabel,
                    totalGB = Math.Round(d.TotalSize / 1073741824.0, 1),
                    freeGB = Math.Round(d.TotalFreeSpace / 1073741824.0, 1),
                    usedPct = Math.Round(used * 100.0 / d.TotalSize, 1),
                });
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
        _disks = (DateTime.UtcNow, list.ToArray());
        return _disks.list;
    }

    private object ReadNet()
    {
        long rx = 0, tx = 0;
        string? primary = null;
        long speed = 0;
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            try
            {
                var st = ni.GetIPStatistics();
                rx += st.BytesReceived;
                tx += st.BytesSent;
                if (ni.Speed > speed && ni.GetIPProperties().GatewayAddresses.Count > 0)
                {
                    speed = ni.Speed;
                    primary = ni.Name;
                }
            }
            catch (NetworkInformationException) { }
        }
        var now = Stopwatch.GetTimestamp();
        double seconds = _netTicks == 0 ? 0 : (now - _netTicks) / (double)Stopwatch.Frequency;
        long rxBps = seconds > 0 ? (long)(Math.Max(0, rx - _netRx) / seconds) : 0;
        long txBps = seconds > 0 ? (long)(Math.Max(0, tx - _netTx) / seconds) : 0;
        _netRx = rx; _netTx = tx; _netTicks = now;
        return new { rxBps, txBps, iface = primary, linkMbps = speed > 0 ? speed / 1_000_000 : (long?)null };
    }

    private static int? ReadBaseMHz()
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

    private static PerformanceCounterCategory? TryCategory(string name)
    {
        try { return PerformanceCounterCategory.Exists(name) ? new PerformanceCounterCategory(name) : null; }
        catch { return null; }
    }

    public void Dispose()
    {
        _cpu.Dispose();
        _cpuPerf?.Dispose();
    }
}

internal sealed record StatsSample(
    double Cpu, int? CpuFreqMHz, double? CpuTempC,
    double RamPct, long RamUsedMB, long RamTotalMB,
    object? Gpu, object[] Disks, object Net, long UptimeSec, long Ts);

/// <summary>The display adapter with the most dedicated memory (the discrete GPU on a laptop).</summary>
[SupportedOSPlatform("windows")]
internal sealed record GpuAdapter(string Name, long? MemoryMB)
{
    private const string DisplayClass = @"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}";

    public static GpuAdapter? Detect()
    {
        try
        {
            using var cls = Registry.LocalMachine.OpenSubKey(DisplayClass);
            if (cls is null) return null;
            GpuAdapter? best = null;
            foreach (var sub in cls.GetSubKeyNames().Where(n => n.Length == 4 && n.All(char.IsDigit)))
            {
                using var k = cls.OpenSubKey(sub);
                var name = k?.GetValue("DriverDesc") as string;
                if (string.IsNullOrWhiteSpace(name) || name.Contains("Basic Display", StringComparison.OrdinalIgnoreCase)) continue;
                long? bytes = k!.GetValue("HardwareInformation.qwMemorySize") switch
                {
                    long l => l,
                    byte[] b when b.Length >= 8 => BitConverter.ToInt64(b, 0),
                    _ => k.GetValue("HardwareInformation.MemorySize") switch
                    {
                        int i => (uint)i,
                        byte[] b when b.Length >= 4 => BitConverter.ToUInt32(b, 0),
                        _ => null,
                    },
                };
                var candidate = new GpuAdapter(name.Trim(), bytes is > 0 ? bytes / (1024 * 1024) : null);
                if (best is null || (candidate.MemoryMB ?? 0) > (best.MemoryMB ?? 0)) best = candidate;
            }
            return best;
        }
        catch { return null; }
    }
}

[SupportedOSPlatform("windows")]
internal static class Memory
{
    public static (double usedPct, long usedMB, long totalMB)? Status()
    {
        var ms = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        if (!GlobalMemoryStatusEx(ref ms)) return null;
        var totalMB = (long)(ms.ullTotalPhys / (1024 * 1024));
        var availMB = (long)(ms.ullAvailPhys / (1024 * 1024));
        var usedMB  = totalMB - availMB;
        var pct     = totalMB == 0 ? 0 : (double)usedMB * 100 / totalMB;
        return (pct, usedMB, totalMB);
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MEMORYSTATUSEX
    {
        public uint  dwLength;
        public uint  dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);
}
