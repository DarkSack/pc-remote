using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Activity;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Processes;

// ══════════════════════════════════════════════════════════════
// Processes — listar, vigilar y matar procesos.
//
// list  → top N (default 50, max 500) por RAM, o por CPU con sort:"cpu".
//         group:true agrupa por nombre como el Administrador de tareas
//         ("Chrome · 23 procesos · 1,8 GB").
// watch → stream de lo mismo cada intervalMs (default 2000).
// kill  → pid, o pids:[…] para cerrar un grupo entero.
//
// El % de CPU sale de comparar TotalProcessorTime con la muestra anterior
// (compartida entre peticiones); en la primera se toman dos muestras
// separadas 300 ms. 100 % = todos los núcleos, igual que el Administrador.
//
// NO se puede matar procesos del sistema (System, Idle…) ni el propio agente.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ProcessesModule : ICommandModule, IStreamModule
{
    public string Domain => "processes";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list",  "Listar procesos (por RAM o CPU, opcionalmente agrupados)"),
        new CommandDescriptor("watch", "Stream: la lista cada pocos segundos"),
        new CommandDescriptor("kill",  "Matar proceso(s) por PID", IsDestructive: true),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "watch" };

    private static readonly HashSet<string> ProtectedNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "System", "Idle", "Registry", "csrss", "smss", "wininit", "services", "lsass", "winlogon",
        "Memory Compression", "dwm", "fontdrvhost", "svchost",
    };

    private readonly ActivityLog? _activity;

    public ProcessesModule(ActivityLog activity) => _activity = activity;

    internal ProcessesModule() { }

    // Previous CPU times, shared by every caller: pid → (start time, cpu time).
    private readonly object _cpuLock = new();
    private Dictionary<int, (DateTime Start, TimeSpan Cpu)> _prevCpu = new();
    private long _prevAt;

    private sealed record Row(int Pid, string Name, long WorkingBytes, double Cpu, int Threads, string? Title);

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return req.Action switch
            {
                "list" => CommandResponse.Ok(req.Id, await ListAsync(req.Params, ct)),
                "kill" => Kill(req, session),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }

    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "watch") yield break;
        var interval = parameters is { ValueKind: JsonValueKind.Object } p && p.TryGetProperty("intervalMs", out var i) && i.TryGetInt32(out var ms)
            ? Math.Clamp(ms, 1000, 30_000)
            : 2000;
        while (!ct.IsCancellationRequested)
        {
            yield return await ListAsync(parameters, ct);
            try { await Task.Delay(interval, ct); } catch (OperationCanceledException) { yield break; }
        }
    }

    private async Task<object> ListAsync(JsonElement? parameters, CancellationToken ct)
    {
        int limit = 50;
        string? filter = null;
        var sort = "ram";
        var group = false;
        if (parameters is { ValueKind: JsonValueKind.Object } p)
        {
            if (p.TryGetProperty("limit",  out var l)) limit = Math.Clamp(l.GetInt32(), 1, 500);
            if (p.TryGetProperty("filter", out var f)) filter = f.GetString();
            if (p.TryGetProperty("sort",   out var s) && s.GetString() is "cpu" or "name") sort = s.GetString()!;
            if (p.TryGetProperty("group",  out var g)) group = g.ValueKind == JsonValueKind.True;
        }

        var rows = await SampleAsync(ct);
        if (!string.IsNullOrWhiteSpace(filter))
            rows = rows.Where(r => r.Name.Contains(filter, StringComparison.OrdinalIgnoreCase) ||
                                   (r.Title?.Contains(filter, StringComparison.OrdinalIgnoreCase) ?? false)).ToList();

        var total = rows.Count;
        if (group)
        {
            var groups = rows.GroupBy(r => r.Name, StringComparer.OrdinalIgnoreCase)
                .Select(g => new
                {
                    name = g.First().Name,
                    count = g.Count(),
                    pids = g.Select(r => r.Pid).ToArray(),
                    cpu = Math.Round(g.Sum(r => r.Cpu), 1),
                    workingMB = g.Sum(r => r.WorkingBytes) / (1024 * 1024),
                    title = g.Select(r => r.Title).FirstOrDefault(t => !string.IsNullOrEmpty(t)),
                    isProtected = ProtectedNames.Contains(g.Key),
                });
            var ordered = sort switch
            {
                "cpu" => groups.OrderByDescending(x => x.cpu).ThenByDescending(x => x.workingMB),
                "name" => groups.OrderBy(x => x.name, StringComparer.OrdinalIgnoreCase),
                _ => groups.OrderByDescending(x => x.workingMB),
            };
            var top = ordered.Take(limit).ToList();
            return new { count = top.Count, total, grouped = true, processes = top };
        }

        var sorted = sort switch
        {
            "cpu" => rows.OrderByDescending(r => r.Cpu).ThenByDescending(r => r.WorkingBytes),
            "name" => rows.OrderBy(r => r.Name, StringComparer.OrdinalIgnoreCase),
            _ => rows.OrderByDescending(r => r.WorkingBytes),
        };
        var list = sorted.Take(limit).Select(r => new
        {
            pid = r.Pid,
            name = r.Name,
            workingMB = r.WorkingBytes / (1024 * 1024),
            cpu = Math.Round(r.Cpu, 1),
            threads = r.Threads,
            title = r.Title,
            isProtected = ProtectedNames.Contains(r.Name),
        }).ToList();
        return new { count = list.Count, total, grouped = false, processes = list };
    }

    /// <summary>One pass over all processes with CPU % against the previous pass.</summary>
    private async Task<List<Row>> SampleAsync(CancellationToken ct)
    {
        bool needBaseline;
        lock (_cpuLock) needBaseline = _prevAt == 0 || Stopwatch.GetElapsedTime(_prevAt) > TimeSpan.FromSeconds(30);
        if (needBaseline)
        {
            Snapshot(out _);
            await Task.Delay(300, ct);
        }
        return Snapshot(out var rows);
    }

    private List<Row> Snapshot(out List<Row> rows)
    {
        rows = new List<Row>();
        var now = Stopwatch.GetTimestamp();
        var cores = Environment.ProcessorCount;
        var current = new Dictionary<int, (DateTime, TimeSpan)>();
        Dictionary<int, (DateTime Start, TimeSpan Cpu)> prev;
        double elapsedMs;
        lock (_cpuLock)
        {
            prev = _prevCpu;
            elapsedMs = _prevAt == 0 ? 0 : Stopwatch.GetElapsedTime(_prevAt, now).TotalMilliseconds;
        }

        foreach (var pr in Process.GetProcesses())
        {
            try
            {
                if (pr.Id == 0) continue; // Idle
                TimeSpan cpuTime = default;
                DateTime start = default;
                double cpu = 0;
                try
                {
                    cpuTime = pr.TotalProcessorTime;
                    start = pr.StartTime;
                    current[pr.Id] = (start, cpuTime);
                    // Same pid with the same start time = same process (pids are reused).
                    if (elapsedMs > 0 && prev.TryGetValue(pr.Id, out var before) && before.Start == start)
                        cpu = Math.Clamp((cpuTime - before.Cpu).TotalMilliseconds / elapsedMs / cores * 100, 0, 100);
                }
                catch { /* elevated or protected: no CPU time for us, still listed */ }

                string? title = null;
                try { title = pr.MainWindowTitle is { Length: > 0 } t ? t : null; } catch { }
                int threads = 0;
                try { threads = pr.Threads.Count; } catch { }

                rows.Add(new Row(pr.Id, pr.ProcessName, pr.WorkingSet64, cpu, threads, title));
            }
            catch { /* exited while we looked */ }
            finally { pr.Dispose(); }
        }

        lock (_cpuLock)
        {
            _prevCpu = current;
            _prevAt = now;
        }
        return rows;
    }

    private CommandResponse Kill(CommandRequest req, ClientSession session)
    {
        var p = req.Params ?? default;
        var pids = new List<int>();
        if (p.TryGetProperty("pids", out var many) && many.ValueKind == JsonValueKind.Array)
            pids.AddRange(many.EnumerateArray().Select(e => e.GetInt32()));
        else
            pids.Add(p.GetProperty("pid").GetInt32());
        if (pids.Count is 0 or > 200) return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Between 1 and 200 pids.");

        if (pids.Contains(Environment.ProcessId))
            return CommandResponse.Fail(req.Id, ErrorCodes.PermissionDenied, "Cannot kill the agent itself");

        var killed = new List<int>();
        var errors = new List<string>();
        string? name = null;
        foreach (var pid in pids.Distinct())
        {
            Process proc;
            try { proc = Process.GetProcessById(pid); }
            catch (ArgumentException) { errors.Add($"PID {pid} ya no existe"); continue; }

            try
            {
                name ??= proc.ProcessName;
                if (ProtectedNames.Contains(proc.ProcessName))
                {
                    errors.Add($"'{proc.ProcessName}' es un proceso protegido");
                    continue;
                }
                proc.Kill(entireProcessTree: true);
                killed.Add(pid);
            }
            catch (System.ComponentModel.Win32Exception ex)
            {
                // Access denied: an elevated process or another user's. Not an agent fault.
                errors.Add($"PID {pid}: {ex.Message}");
            }
            catch (InvalidOperationException)
            {
                killed.Add(pid); // exited on its own meanwhile: same outcome
            }
            finally { proc.Dispose(); }
        }

        if (killed.Count == 0)
        {
            var code = errors.Any(e => e.Contains("protegido") || e.Contains("denied", StringComparison.OrdinalIgnoreCase) || e.Contains("denegado"))
                ? ErrorCodes.PermissionDenied : ErrorCodes.NotFound;
            return CommandResponse.Fail(req.Id, code, string.Join("; ", errors));
        }

        _activity?.Add("process", $"Proceso cerrado: {name}",
            killed.Count > 1 ? $"{killed.Count} procesos · {session.DeviceName}" : $"PID {killed[0]} · {session.DeviceName}", "warning");
        return CommandResponse.Ok(req.Id, new { killed = killed.Count == 1 ? killed[0] : (object)killed, pids = killed, name, errors });
    }
}
