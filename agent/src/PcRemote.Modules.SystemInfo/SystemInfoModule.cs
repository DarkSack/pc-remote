using System.Diagnostics;
using System.Management;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Microsoft.Win32;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.SystemInfo;

/// <summary>
/// System info + realtime stats.
///   - "info"  → static host info (os, cpu model, ram total, uptime, hostname).
///   - "stats" → snapshot of cpu%, ram%, ram used/total.
/// Streaming (subscribe) lands in Phase 3b.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class SystemInfoModule : ICommandModule, IDisposable
{
    public string Domain => "systeminfo";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info",  "Static system information"),
        new CommandDescriptor("stats", "Snapshot of CPU / RAM usage"),
    };

    private readonly PerformanceCounter _cpuCounter = new("Processor", "% Processor Time", "_Total");
    private bool _cpuCounterPrimed;

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "info"  => GetInfo(req.Id),
                "stats" => GetStats(req.Id),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand,
                        $"Unknown action '{req.Action}' in systeminfo domain."),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.Fail(
                req.Id, ErrorCodes.InternalError, ex.Message));
        }
    }

    // ── info ─────────────────────────────────────────────────
    private static CommandResponse GetInfo(string id)
    {
        var mem = GetMemoryStatus();
        var payload = new
        {
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
        };
        return CommandResponse.Ok(id, payload);
    }

    // ── stats ────────────────────────────────────────────────
    private CommandResponse GetStats(string id)
    {
        if (!_cpuCounterPrimed)
        {
            _cpuCounter.NextValue();
            Thread.Sleep(150);
            _cpuCounterPrimed = true;
        }
        var cpu = Math.Round(_cpuCounter.NextValue(), 1);

        var mem = GetMemoryStatus();
        var payload = new
        {
            cpu,
            ramPct     = mem is null ? 0 : Math.Round(mem.Value.usedPct, 1),
            ramUsedMB  = mem?.usedMB  ?? 0,
            ramTotalMB = mem?.totalMB ?? 0,
            ts         = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
        return CommandResponse.Ok(id, payload);
    }

    // ── Helpers ─────────────────────────────────────────────
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
        catch
        {
            return "unknown";
        }
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
