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
///   - "stats" → request/response: single snapshot (see HardwareSampler).
///   - "stats" → subscribe:       stream of snapshots every params.intervalMs (default 1000).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class SystemInfoModule : ICommandModule, IStreamModule, IPluginMetadata, IDisposable
{
    public string Domain => "systeminfo";

    public string DisplayName => "Monitor del sistema";
    public string Description => "CPU, RAM, GPU, discos y red en tiempo real, e información del equipo.";
    public string Category => PluginCategories.System;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info",  "Static system information"),
        new CommandDescriptor("stats", "Snapshot or stream of CPU / RAM / GPU / disk / network usage"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "stats" };

    private readonly HardwareSampler _sampler = new();
    private readonly ActivityLog? _activity;

    public SystemInfoModule(ActivityLog? activity = null) => _activity = activity;

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

        while (!ct.IsCancellationRequested)
        {
            // Sampling sleeps briefly the very first time (counters need two
            // readings); keep that off the stream's thread pool thread.
            yield return await Task.Run(SampleStats, ct);
            try { await Task.Delay(intervalMs, ct); } catch (TaskCanceledException) { yield break; }
        }
    }

    // ── info ────────────────────────────────────────────
    private CommandResponse GetInfo(string id)
    {
        var mem = Memory.Status();
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
            gpuName    = _sampler.Adapter?.Name,
            vramTotalMB = _sampler.Adapter?.MemoryMB,
            uptimeSec  = Environment.TickCount64 / 1000,
            timezone   = TimeZoneInfo.Local.Id,
            agentVersion = typeof(SystemInfoModule).Assembly.GetName().Version?.ToString(3),
        });
    }

    // ── stats snapshot (shared by request/response and stream) ──
    private object SampleStats()
    {
        var s = _sampler.Sample();
        RaiseAlerts(s);
        return new
        {
            cpu        = s.Cpu,
            cpuFreqMHz = s.CpuFreqMHz,
            cpuTempC   = s.CpuTempC,
            ramPct     = s.RamPct,
            ramUsedMB  = s.RamUsedMB,
            ramTotalMB = s.RamTotalMB,
            gpu        = s.Gpu,
            disks      = s.Disks,
            net        = s.Net,
            uptimeSec  = s.UptimeSec,
            ts         = s.Ts,
        };
    }

    /// <summary>Into the Activity timeline, at most every 15 minutes per kind. Only while someone watches stats.</summary>
    private void RaiseAlerts(StatsSample s)
    {
        if (_activity is null) return;
        var cooldown = TimeSpan.FromMinutes(15);
        if (s.RamPct >= 92)
            _activity.Alert("ram", $"Memoria RAM al {s.RamPct:0}%", $"{s.RamUsedMB / 1024.0:0.0} de {s.RamTotalMB / 1024.0:0.0} GB", cooldown);
        if (s.CpuTempC is >= 85)
            _activity.Alert("cputemp", $"Temperatura de la CPU: {s.CpuTempC:0} °C", null, cooldown);
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

    public void Dispose() => _sampler.Dispose();
}
