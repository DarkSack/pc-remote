using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Core.Storage;

namespace PcRemote.Core.Activity;

/// <summary>
/// <c>activity.recent</c>: one timeline with the agent's own events (sessions,
/// pairings, alerts) and the audited commands, newest first.
/// </summary>
public sealed class ActivityModule(ActivityLog log, CommandAuditLog audit) : ICommandModule, IPluginMetadata
{
    public string Domain => "activity";

    public string DisplayName => "Actividad";
    public string Description => "Historial de conexiones, comandos y alertas del PC.";
    public string Category => PluginCategories.System;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("recent", "Eventos y comandos recientes"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        if (req.Action != "recent")
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"));

        var limit = 100;
        if (req.Params is { ValueKind: JsonValueKind.Object } p && p.TryGetProperty("limit", out var l) && l.TryGetInt32(out var n))
            limit = Math.Clamp(n, 1, 500);

        var commands = SafeAudit(limit).Select(r => new ActivityEvent(
            r.Ts.ToUnixTimeMilliseconds(),
            ActivityKinds.Command,
            ActivityLabels.For(r.Domain, r.Action),
            r.Success ? null : $"Falló ({r.ErrorCode})",
            r.Success ? ActivityLevels.Info : ActivityLevels.Error,
            r.DeviceName));

        var events = log.Snapshot(limit)
            .Concat(commands)
            .OrderByDescending(e => e.Ts)
            .Take(limit)
            .ToList();
        return Task.FromResult(CommandResponse.Ok(req.Id, new { events }));
    }

    private IReadOnlyList<AuditRow> SafeAudit(int limit)
    {
        try { return audit.Recent(limit); }
        catch { return Array.Empty<AuditRow>(); } // no database: the in-memory events still show
    }
}

/// <summary>Readable names for audited commands (params are never stored, so no app names).</summary>
public static class ActivityLabels
{
    private static readonly Dictionary<string, string> Labels = new(StringComparer.OrdinalIgnoreCase)
    {
        ["system.shutdown"] = "Apagado solicitado",
        ["system.restart"] = "Reinicio solicitado",
        ["system.sleep"] = "PC suspendido",
        ["system.hibernate"] = "PC hibernado",
        ["system.lock"] = "PC bloqueado",
        ["system.logoff"] = "Sesión de Windows cerrada",
        ["applications.launch"] = "App abierta",
        ["applications.list"] = "Lista de apps leída",
        ["processes.kill"] = "Proceso finalizado",
        ["processes.list"] = "Procesos consultados",
        ["windows.focus"] = "Ventana enfocada",
        ["windows.close"] = "Ventana cerrada",
        ["windows.minimize"] = "Ventana minimizada",
        ["windows.maximize"] = "Ventana maximizada",
        ["clipboard.set"] = "Texto enviado al portapapeles",
        ["clipboard.setImage"] = "Imagen enviada al portapapeles",
        ["clipboard.get"] = "Portapapeles leído",
        ["clipboard.historyRestore"] = "Elemento del historial restaurado",
        ["clipboard.historyClear"] = "Historial del portapapeles borrado",
        ["input.keyType"] = "Texto escrito en el PC",
        ["input.keyPress"] = "Atajo de teclado",
        ["media.playPause"] = "Reproducir / pausar",
        ["media.next"] = "Siguiente pista",
        ["media.previous"] = "Pista anterior",
        ["media.volumeMute"] = "Silencio cambiado",
        ["terminal.run"] = "Comando ejecutado en la terminal",
        ["files.open"] = "Archivo abierto en el PC",
        ["files.read"] = "Archivo descargado al móvil",
        ["files.reveal"] = "Archivo mostrado en el Explorador",
    };

    public static string For(string domain, string action) =>
        Labels.TryGetValue($"{domain}.{action}", out var label) ? label : $"{domain}.{action}";
}
