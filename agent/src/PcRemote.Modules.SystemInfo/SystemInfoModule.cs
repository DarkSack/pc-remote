using System.Diagnostics;
using System.Management;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text.Json;
using Microsoft.Win32;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.SystemInfo;

/// <summary>
/// System info + realtime stats.
///   - "info"  → request/response: static host info.
///   - "stats" → request/response: single snapshot of cpu%/ram%.
///   - "stats" → subscribe:       stream of snapshots every params.intervalMs (default 1000).
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
    private static CommandResponse GetInfo(string id)
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
            ramTotalMB = mem?.totalMB ?? 0,
            uptimeSec  = Environment.TickCount64 / 1000,
            timezone   = TimeZoneInfo.Local.Id,
        });
    }

    // ── stats snapshot (shared by request/response and stream) ──
    private object SampleStats()
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
        return new
        {
            cpu,
            ramPct     = mem is null ? 0 : Math.Round(mem.Value.usedPct, 1),
            ramUsedMB  = mem?.usedMB  ?? 0,
            ramTotalMB = mem?.totalMB ?? 0,
            ts         = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
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

    public void Dispose() => _cpuCounter.Dispose();
}
