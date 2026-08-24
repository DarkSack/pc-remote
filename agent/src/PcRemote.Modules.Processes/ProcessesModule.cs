using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Processes;

// ══════════════════════════════════════════════════════════════
// Processes — listar y matar procesos.
//
// list ordena por WorkingSet64 desc y devuelve top N (default 50,
// max 500). Se puede filtrar por sustring en name (case-insensitive).
//
// kill usa Process.Kill(entireProcessTree:true). NO se puede matar
// procesos del sistema (System, Idle) ni el propio agente (por
// seguridad se comprueba pid contra Environment.ProcessId).
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ProcessesModule : ICommandModule
{
    public string Domain => "processes";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list", "Listar procesos (top por RAM)"),
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
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InternalError, ex.Message));
        }
    }

    private static CommandResponse List(CommandRequest req)
    {
        var p = req.Params ?? default;
        int limit = 50;
        string? filter = null;
        if (p.ValueKind == JsonValueKind.Object)
        {
            if (p.TryGetProperty("limit",  out var l)) limit = Math.Clamp(l.GetInt32(), 1, 500);
            if (p.TryGetProperty("filter", out var f)) filter = f.GetString();
        }

        var procs = Process.GetProcesses();
        var rows = new List<object>();
        foreach (var pr in procs)
        {
            try
            {
                if (filter != null && !pr.ProcessName.Contains(filter, StringComparison.OrdinalIgnoreCase)) continue;
                rows.Add(new
                {
                    pid       = pr.Id,
                    name      = pr.ProcessName,
                    workingMB = pr.WorkingSet64 / (1024 * 1024),
                    threads   = pr.Threads.Count,
                    startTime = TryGet(() => pr.StartTime.ToString("O")),
                });
            }
            catch { /* algunos procesos bloquean acceso */ }
            finally { pr.Dispose(); }
        }
        var top = rows
            .OrderByDescending(r => (long)r.GetType().GetProperty("workingMB")!.GetValue(r)!)
            .Take(limit)
            .ToList();
        return CommandResponse.Ok(req.Id, new { count = top.Count, processes = top });
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
        finally { proc.Dispose(); }
    }

    private static string? TryGet(Func<string> fn) { try { return fn(); } catch { return null; } }
}
