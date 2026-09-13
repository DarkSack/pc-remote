using PcRemote.Core.Protocol;

namespace PcRemote.Core.Router;

/// <summary>
/// Contract every module must implement. The core discovers modules by reflection
/// (all types implementing ICommandModule in loaded assemblies) and registers them
/// in the CommandRouter by their <see cref="Domain"/>.
/// </summary>
public interface ICommandModule
{
    /// <summary>Domain name used in the protocol (e.g. "system", "mouse").</summary>
    string Domain { get; }

    /// <summary>Actions exposed by this module (used for schema + docs).</summary>
    IReadOnlyList<CommandDescriptor> Commands { get; }

    /// <summary>Execute an action. Must not throw for expected failures; return an error response.</summary>
    Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct);
}

/// <summary>
/// Optional: modules that keep per-session state (e.g. a mouse button held for a
/// drag) implement this to clean it up when the connection goes away.
/// </summary>
public interface ISessionAware
{
    void OnSessionEnded(ClientSession session);
}

public sealed record CommandDescriptor(
    string Action,
    string Description,
    bool RequiresElevation = false,
    bool IsDestructive = false);

/// <summary>Placeholder for the session type populated after Auth. Filled in Phase 2.</summary>
public sealed class ClientSession
{
    public required string SessionId { get; init; }
    public required string DeviceId { get; init; }
    public required string DeviceName { get; init; }
    public required DateTimeOffset StartedAt { get; init; }
}
