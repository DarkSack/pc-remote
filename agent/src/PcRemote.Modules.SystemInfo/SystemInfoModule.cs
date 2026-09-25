using System.Diagnostics;
using System.Management;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text.Json;
using Microsoft.Win32;
using PcRemote.Core.Activity;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.SystemInfo;

/// <summary>
/// System info + realtime stats.
///   - "info"  → request/response: static host info.
///   - "stats" → request/response: single snapshot.
///   - "stats" → subscribe:       stream of snapshots every params.intervalMs (default 1000).
/// A snapshot has CPU (%, clock, temperature), RAM, GPU (usage, temperature,
/// VRAM, clock), fixed disks and network throughput; see HardwareSampler.
///
/// It also watches thresholds in the background (every 15 s, with or without a
/// phone connected) and writes alerts to the activity timeline.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class SystemInfoModule : ICommandModule, IStreamModule, IDisposable
{
    public string Domain => "systeminfo";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info",  "Static system information"),
        new CommandDescriptor("stats", "Snapshot or stream of CPU / RAM usage"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "stats" };

    private readonly PerformanceCounter _cpuCounter = new("Processor", "% Processor Time", "_Total");
    private bool _cpuCounterPrimed;

    private readonly HardwareSampler _hw = new();
    private readonly ActivityLog? _activity;
    private readonly Timer? _alerts;

    // Several phones (or a stream and a request) share one sample per ~0.8 s.
    private object? _lastSample;
    private long _lastSampleAt;

    public SystemInfoModule(ActivityLog activity)
    {
        _activity = activity;
        _alerts = new Timer(_ => CheckAlerts(), null, TimeSpan.FromSeconds(20), TimeSpan.FromSeconds(15));
    }

    /// <summary>For tests: no background alerts.</summary>
    internal SystemInfoModule() { }

    // PerformanceCounter is not thread-safe, and two phones (or a request next to
    // a stream) sample it from different threads.
    private readonly object _cpuLock = new();

    // ── request/response ──────────────────────────────────
    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "info"  => GetInfo(req.Id),
                "stats" => CommandResponse.Ok(req.Id, SampleStats()),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                        $"Unknown action '{req.Action}' in systeminfo domain."),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    // ── subscribe (stream) ─────────────────────────────────
    public async IAsyncEnumerable<object> StartStreamAsync(
        string action,
        JsonElement? parameters,
        ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "stats")
            yield break;

        var intervalMs = 1000;
        if (parameters is { ValueKind: JsonValueKind.Object } p &&
            p.TryGetProperty("intervalMs", out var el) &&
            el.TryGetInt32(out var v))
        {
            intervalMs = Math.Clamp(v, 250, 60_000);
        }

        // Prime counter so the first value isn't 0.
        bool prime;
        lock (_cpuLock) prime = !_cpuCounterPrimed;
        if (prime)
        {
            lock (_cpuLock) _cpuCounter.NextValue();
            await Task.Delay(150, ct);
            lock (_cpuLock) _cpuCounterPrimed = true;
        }

        while (!ct.IsCancellationRequested)
        {
            yield return SampleStats();
            try { await Task.Delay(intervalMs, ct); } catch (TaskCanceledException) { yield break; }
        }
    }

    // ── info ────────────────────────────────────────────
    private CommandResponse GetInfo(string id)
    {
        var mem = GetMemoryStatus();
        var lan = PcRemote.Core.Discovery.LanAddress.GuessInterface();
        return CommandResponse.Ok(id, new
        {
            // For Wake-on-LAN: the phone keeps these so it can wake the PC later,
            // when there is no agent to ask.
            macAddress = lan.MacAddress,
            broadcast  = lan.Broadcast,
            lanIp      = lan.Address,
            hostname   = Environment.MachineName,
            username   = Environment.UserName,
            os         = GetOsName(),
            osBuild    = Environment.OSVersion.Version.ToString(),
            is64Bit    = Environment.Is64BitOperatingSystem,
            cpuModel   = GetCpuModel(),
            cpuCores   = Environment.ProcessorCount,
            gpuModel   = _hw.Gpu()?.Name,
            ramTotalMB = mem?.totalMB ?? 0,
            uptimeSec  = Environment.TickCount64 / 1000,
            timezone   = TimeZoneInfo.Local.Id,
        });
    }

    // ── stats snapshot (shared by request/response and stream) ──
    private object SampleStats()
    {
        lock (_cpuLock)
        {
            if (_lastSample is not null && Environment.TickCount64 - _lastSampleAt < 800) return _lastSample;
        }
        var sample = TakeSample();
        lock (_cpuLock)
        {
            _lastSample = sample;
            _lastSampleAt = Environment.TickCount64;
        }
        return sample;
    }

    private Snapshot TakeSample()
    {
        double cpu;
        lock (_cpuLock)
        {
            if (!_cpuCounterPrimed)
            {
                _cpuCounter.NextValue();
                Thread.Sleep(150);
                _cpuCounterPrimed = true;
            }
            cpu = Math.Round(_cpuCounter.NextValue(), 1);
        }
        var mem = GetMemoryStatus();
        return new Snapshot(
            Cpu: cpu,
            CpuFreqMHz: _hw.CpuFrequencyMHz(),
            CpuTempC: _hw.CpuTemperatureC(),
            RamPct: mem is null ? 0 : Math.Round(mem.Value.usedPct, 1),
            RamUsedMB: mem?.usedMB ?? 0,
            RamTotalMB: mem?.totalMB ?? 0,
            Gpu: _hw.Gpu(),
            Disks: _hw.Disks(),
            Net: _hw.Network(),
            UptimeSec: Environment.TickCount64 / 1000,
            Ts: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    /// <summary>Serialised camelCase: cpu, cpuFreqMHz, cpuTempC, ramPct, …, gpu{…}, disks[…], net{downBps, upBps}.</summary>
    public sealed record Snapshot(
        double Cpu, double? CpuFreqMHz, double? CpuTempC,
        double RamPct, long RamUsedMB, long RamTotalMB,
        GpuStats? Gpu, IReadOnlyList<DiskStats> Disks, NetStats Net,
        long UptimeSec, long Ts);

    // ── alerts ──────────────────────────────────────────
    private int _cpuHighTicks, _ramHighTicks;
    private bool _cpuHotRaised, _gpuHotRaised, _cpuBusyRaised, _ramRaised;
    private readonly Dictionary<string, DateTime> _diskRaised = new();

    /// <summary>With hysteresis: one alert when a value crosses the line, another only after it came back.</summary>
    private void CheckAlerts()
    {
        if (_activity is null) return;
        try
        {
            var s = (Snapshot)SampleStats();

            if (s.CpuTempC is { } ct)
            {
                if (!_cpuHotRaised && ct >= 85) { _cpuHotRaised = true; _activity.Add("alert", $"Temperatura de CPU alta: {ct:0} °C", null, "warning"); }
                else if (_cpuHotRaised && ct < 75) _cpuHotRaised = false;
            }
            if (s.Gpu?.TempC is { } gt)
            {
                if (!_gpuHotRaised && gt >= 85) { _gpuHotRaised = true; _activity.Add("alert", $"Temperatura de GPU alta: {gt:0} °C", s.Gpu.Name, "warning"); }
                else if (_gpuHotRaised && gt < 75) _gpuHotRaised = false;
            }

            _cpuHighTicks = s.Cpu >= 95 ? _cpuHighTicks + 1 : 0;
            if (!_cpuBusyRaised && _cpuHighTicks >= 4) { _cpuBusyRaised = true; _activity.Add("alert", "CPU al máximo durante más de un minuto", $"{s.Cpu:0} %", "warning"); }
            else if (_cpuBusyRaised && s.Cpu < 70) _cpuBusyRaised = false;

            _ramHighTicks = s.RamPct >= 92 ? _ramHighTicks + 1 : 0;
            if (!_ramRaised && _ramHighTicks >= 2) { _ramRaised = true; _activity.Add("alert", $"Memoria casi llena: {s.RamPct:0} %", $"{s.RamUsedMB / 1024.0:0.0} de {s.RamTotalMB / 1024.0:0.0} GB", "warning"); }
            else if (_ramRaised && s.RamPct < 85) _ramRaised = false;

            foreach (var d in s.Disks)
            {
                if (d.FreeGB / Math.Max(0.1, d.TotalGB) >= 0.05) continue;
                // At most once a day per drive: a full disk stays full for a while.
                if (_diskRaised.TryGetValue(d.Name, out var at) && DateTime.UtcNow - at < TimeSpan.FromDays(1)) continue;
                _diskRaised[d.Name] = DateTime.UtcNow;
                _activity.Add("alert", $"Queda poco espacio en {d.Name}", $"{d.FreeGB:0.0} GB libres de {d.TotalGB:0} GB", "warning");
            }
        }
        catch
        {
            // Never let a counter hiccup kill the timer.
        }
    }

    // ── Helpers ─────────────────────────────────────────
    private static string GetOsName()
    {
        try
        {
            using var searcher = new ManagementObjectSearcher("SELECT Caption FROM Win32_OperatingSystem");
            foreach (var o in searcher.Get())
                return o["Caption"]?.ToString()?.Trim() ?? "Windows";
        }
        catch { /* fall through */ }
        return $"Windows {Environment.OSVersion.Version}";
    }

    private static string GetCpuModel()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            return key?.GetValue("ProcessorNameString")?.ToString()?.Trim() ?? "unknown";
        }
        catch { return "unknown"; }
    }

    private static (double usedPct, long usedMB, long totalMB)? GetMemoryStatus()
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

    public void Dispose()
    {
        _alerts?.Dispose();
        _cpuCounter.Dispose();
        _hw.Dispose();
    }
}
