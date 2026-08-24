using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.SystemInfo;

/// <summary>
/// Skeleton — Phase 3 wires PerformanceCounters + WMI + GlobalMemoryStatusEx.
/// Exposes actions:
///   - info      → returns static host info (os, cpu model, ram total, ...)
///   - stats     → SUBSCRIBE: streams { cpu, ram, gpu, netIn, netOut } every N ms
/// </summary>
public sealed class SystemInfoModule : ICommandModule
{
    public string Domain => "systeminfo";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info",  "Static system information"),
        new CommandDescriptor("stats", "Realtime CPU/RAM/GPU stats (stream)"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        // TODO Phase 3.
        return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InternalError, $"'{req.Action}' not implemented yet."));
    }
}
