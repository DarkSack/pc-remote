using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Processes;

// ══════════════════════════════════════════════════════════════
// Processes — listar y matar procesos.
//
// list devuelve top N (default 50, max 500) ordenados por memoria, CPU
// o nombre, con el % de CPU de cada uno. Se puede filtrar por sustring
// en name (case-insensitive).
//
// kill usa Process.Kill(entireProcessTree:true). NO se puede matar
// procesos del sistema (System, Idle) ni el propio agente (por
// seguridad se comprueba pid contra Environment.ProcessId).
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ProcessesModule : ICommandModule, IPluginMetadata
{
    public string Domain => "processes";

    public string DisplayName => "Procesos";
    public string Description => "Procesos activos con su consumo, y finalizarlos.";
    public string Category => PluginCategories.System;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list", "Listar procesos (por RAM, CPU o nombre)"),
        new CommandDescriptor("kill", "Matar proceso por PID", IsDestructive: true),
    };

    private static readonly HashSet<string> ProtectedNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "System", "Idle", "Registry", "csrss", "smss", "wininit", "services", "lsass", "winlogon",
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "list" => List(req),
                "kill" => Kill(req),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    // CPU % needs two readings of each process' CPU time. The last reading is kept,
    // so a phone refreshing every few seconds gets the average since its previous
    // call; a first call (or one after a long pause) samples twice, 500 ms apart.
    private static readonly object CpuLock = new();
    private static Dictionary<int, TimeSpan> _lastCpu = new();
    private static long _lastCpuTicks;

    private static CommandResponse List(CommandRequest req)
    {
        var p = req.Params ?? default;
        int limit = 50;
        string? filter = null;
        var sort = "memory";
        if (p.ValueKind == JsonValueKind.Object)
        {
            if (p.TryGetProperty("limit",  out var l)) limit = Math.Clamp(l.GetInt32(), 1, 500);
            if (p.TryGetProperty("filter", out var f)) filter = f.GetString();
            if (p.TryGetProperty("sort",   out var s)) sort = s.GetString() ?? "memory";
        }

        var cpu = SampleCpu();
        var rows = new List<ProcessRow>();
        foreach (var pr in Process.GetProcesses())
        {
            try
            {
                if (pr.Id == 0) continue; // Idle
                if (filter != null && !pr.ProcessName.Contains(filter, StringComparison.OrdinalIgnoreCase)) continue;
                rows.Add(new ProcessRow(
                    pr.Id,
                    pr.ProcessName,
                    pr.WorkingSet64 / (1024 * 1024),
                    cpu.TryGetValue(pr.Id, out var c) ? c : null,
                    pr.Threads.Count,
                    TryGet(() => pr.StartTime.ToString("O")),
                    TryGet(() => pr.MainWindowTitle is { Length: > 0 } t ? t : null)));
            }
            catch { /* algunos procesos bloquean acceso */ }
            finally { pr.Dispose(); }
        }

        IEnumerable<ProcessRow> ordered = sort switch
        {
            "cpu"  => rows.OrderByDescending(r => r.Cpu ?? 0).ThenByDescending(r => r.WorkingMB),
            "name" => rows.OrderBy(r => r.Name, StringComparer.OrdinalIgnoreCase),
            _      => rows.OrderByDescending(r => r.WorkingMB),
        };
        var top = ordered.Take(limit).Select(r => new
        {
            pid = r.Pid, name = r.Name, workingMB = r.WorkingMB, cpu = r.Cpu,
            threads = r.Threads, startTime = r.StartTime, windowTitle = r.WindowTitle,
        }).ToList();
        return CommandResponse.Ok(req.Id, new { count = top.Count, total = rows.Count, processes = top });
    }

    private sealed record ProcessRow(int Pid, string Name, long WorkingMB, double? Cpu, int Threads, string? StartTime, string? WindowTitle);

    /// <summary>pid → CPU % of the whole machine (0–100) since the previous reading.</summary>
    private static Dictionary<int, double> SampleCpu()
    {
        lock (CpuLock)
        {
            var now = Stopwatch.GetTimestamp();
            if (_lastCpuTicks == 0 || Stopwatch.GetElapsedTime(_lastCpuTicks, now) > TimeSpan.FromSeconds(30))
            {
                _lastCpu = ReadCpuTimes();
                _lastCpuTicks = Stopwatch.GetTimestamp();
                Thread.Sleep(500);
                now = Stopwatch.GetTimestamp();
            }
            var current = ReadCpuTimes();
            var wall = Stopwatch.GetElapsedTime(_lastCpuTicks, now).TotalMilliseconds * Environment.ProcessorCount;
            var result = new Dictionary<int, double>();
            if (wall > 0)
            {
                foreach (var (pid, time) in current)
                {
                    if (!_lastCpu.TryGetValue(pid, out var before)) continue;
                    var pct = (time - before).TotalMilliseconds / wall * 100;
                    result[pid] = Math.Round(Math.Clamp(pct, 0, 100), 1);
                }
            }
            _lastCpu = current;
            _lastCpuTicks = now;
            return result;
        }
    }

    private static Dictionary<int, TimeSpan> ReadCpuTimes()
    {
        var map = new Dictionary<int, TimeSpan>();
        foreach (var pr in Process.GetProcesses())
        {
            try { map[pr.Id] = pr.TotalProcessorTime; }
            catch { /* access denied: no CPU figure for it */ }
            finally { pr.Dispose(); }
        }
        return map;
    }

    private static CommandResponse Kill(CommandRequest req)
    {
        var p = req.Params ?? default;
        var pid = p.GetProperty("pid").GetInt32();

        if (pid == Environment.ProcessId)
            return CommandResponse.Fail(req.Id, ErrorCodes.PermissionDenied, "Cannot kill the agent itself");

        Process proc;
        try { proc = Process.GetProcessById(pid); }
        catch (ArgumentException) { return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"No process with PID {pid}"); }

        try
        {
            if (ProtectedNames.Contains(proc.ProcessName))
                return CommandResponse.Fail(req.Id, ErrorCodes.PermissionDenied, $"Protected process '{proc.ProcessName}'");

            var name = proc.ProcessName;
            proc.Kill(entireProcessTree: true);
            return CommandResponse.Ok(req.Id, new { killed = pid, name });
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            // Access denied: an elevated process or another user's. Not an agent fault.
            return CommandResponse.Fail(req.Id, ErrorCodes.PermissionDenied, $"Cannot kill PID {pid}: {ex.Message}");
        }
        catch (InvalidOperationException)
        {
            return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"Process {pid} already exited");
        }
        finally { proc.Dispose(); }
    }

    private static string? TryGet(Func<string> fn) { try { return fn(); } catch { return null; } }
}
