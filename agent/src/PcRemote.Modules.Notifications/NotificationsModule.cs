using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Plugins;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Notifications;

// ══════════════════════════════════════════════════════════════
// Notifications — the toasts Windows shows, seen from the phone.
//
//   watch → stream: { op:"snapshot", items, error? } with the latest 50,
//           newest first (item = { id, app, appId, title, body, ts }), then
//           { op:"add", item } for each toast that arrives, and
//           { op:"disabled" } if the owner turns the feature off meanwhile
//
// Read-only: nothing here dismisses, answers or clicks a notification.
// Off by default (they carry private messages and sign-in codes). Turning
// it off in the panel also ends a running watch on its next poll (≤ 2 s).
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class NotificationsModule(FeatureStore features) : ICommandModule, IStreamModule, IOptionalModule
{
    public string Domain => "notifications";

    public string FeatureName => "Notificaciones";
    public string FeatureDescription => "Ver en el móvil las notificaciones que muestra Windows. Desactivado por defecto: pueden traer mensajes privados o códigos de verificación.";
    public string FeatureIcon => "notifications";
    public bool EnabledByDefault => false;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("watch", "Stream: notificaciones nuevas según llegan"),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "watch" };

    public const int ListLimit = 50;
    private static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(2);

    internal string DatabasePath { get; init; } = NotificationDatabase.DefaultPath;

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct) =>
        Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"'{req.Action}' is a stream: subscribe to it."));

    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "watch") yield break;

        long lastId = 0;
        string? error = null;
        List<PcNotification> items = [];
        try { items = NotificationDatabase.Latest(DatabasePath, ListLimit, out lastId); }
        catch (Exception ex) { error = ErrorText(ex); }
        yield return new { op = "snapshot", items, error };
        if (error is not null) yield break;

        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(PollEvery, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { yield break; }
            if (!features.IsEnabled(Domain, EnabledByDefault))
            {
                yield return new { op = "disabled" }; // the phone drops what it had and says why
                yield break;
            }

            List<PcNotification> fresh;
            try { fresh = NotificationDatabase.After(DatabasePath, lastId, out lastId); }
            catch { continue; } // Windows holds the file for a moment while it writes: try on the next poll.
            foreach (var item in fresh) yield return new { op = "add", item };
        }
    }

    private static string ErrorText(Exception ex) => ex is FileNotFoundException
        ? ex.Message
        : "No se pudieron leer las notificaciones de Windows: " + ex.Message;
}
