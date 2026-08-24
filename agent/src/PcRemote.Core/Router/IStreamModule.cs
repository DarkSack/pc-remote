using System.Text.Json;

namespace PcRemote.Core.Router;

/// <summary>
/// Optional capability a module can implement to expose subscribable streams.
/// The server will call <see cref="StartStreamAsync"/> when it receives a
/// <c>subscribe</c> message for one of the module's stream actions and forward
/// each emitted item to the client as <c>{ kind:"stream", id, data }</c>.
/// </summary>
public interface IStreamModule
{
    /// <summary>Actions in this module that are streamable (rest are request/response).</summary>
    IReadOnlySet<string> StreamActions { get; }

    /// <summary>
    /// Yields items to push to the client until <paramref name="ct"/> is cancelled
    /// (unsubscribe from client or connection closed).
    /// </summary>
    IAsyncEnumerable<object> StartStreamAsync(
        string action,
        JsonElement? parameters,
        ClientSession session,
        CancellationToken ct);
}
