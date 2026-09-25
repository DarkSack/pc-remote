using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading.Channels;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Core.Activity;

/// <summary>
/// <c>activity.list</c> → recent events, newest first.
/// <c>activity.watch</c> (stream) → <c>{ op:"snapshot", events }</c> first, then <c>{ op:"add", event }</c>.
/// </summary>
public sealed class ActivityModule(ActivityLog log) : ICommandModule, IStreamModule
{
    public string Domain => "activity";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list",  "Eventos recientes del PC"),
        new CommandDescriptor("watch", "Stream: eventos nuevos"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "watch" };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct) =>
        Task.FromResult(req.Action == "list"
            ? CommandResponse.Ok(req.Id, new { events = log.Recent(ReadLimit(req.Params)) })
            : CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"));

    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "watch") yield break;

        var queue = Channel.CreateBounded<ActivityEvent>(new BoundedChannelOptions(256)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
        });
        void OnAdded(ActivityEvent e) => queue.Writer.TryWrite(e);

        // Subscribe before taking the snapshot so nothing slips between the two.
        log.Added += OnAdded;
        try
        {
            var snapshot = log.Recent(ReadLimit(parameters));
            var lastId = snapshot.Count > 0 ? snapshot[0].Id : 0;
            yield return new { op = "snapshot", events = snapshot };

            while (await queue.Reader.WaitToReadAsync(ct).ConfigureAwait(false))
            {
                while (queue.Reader.TryRead(out var ev))
                {
                    if (ev.Id <= lastId) continue; // already in the snapshot
                    yield return new { op = "add", @event = ev };
                }
            }
        }
        finally
        {
            log.Added -= OnAdded;
        }
    }

    private static int ReadLimit(JsonElement? p) =>
        p is { ValueKind: JsonValueKind.Object } o && o.TryGetProperty("limit", out var l) && l.TryGetInt32(out var n)
            ? Math.Clamp(n, 1, ActivityLog.Capacity)
            : 100;
}
