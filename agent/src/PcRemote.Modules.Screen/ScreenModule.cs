using System.Runtime.Versioning;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Activity;
using PcRemote.Core.Plugins;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using PcRemote.Core.Server;
using PcRemote.Modules.Screen.Capture;

namespace PcRemote.Modules.Screen;

// ══════════════════════════════════════════════════════════════
// Remote screen — see and drive the PC from the phone, like RustDesk
// on the local network.
//
//   displays → monitors (index, name, position, size, primary)
//   open     → { ticket, path } for /socket/screen; the video, the
//              cursor and the input travel on that socket, so command
//              responses never wait behind a frame. See ScreenSession.
//
// Capture: DXGI Desktop Duplication. Encode: H.264 on the GPU through
// Media Foundation (software encoder as fallback). The phone decodes
// with MediaCodec.
//
// Windows does not let a normal process see or drive the secure desktop
// (UAC prompts, lock screen, Ctrl+Alt+Del): the phone gets a "blocked"
// status until the normal desktop is back.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class ScreenModule(SocketTickets tickets, ActivityLog activity, ILogger<ScreenModule> logger)
    : ICommandModule, IOptionalModule, ISocketModule
{
    public string Domain => "screen";
    public string SocketName => "screen";

    public string FeatureName => "Pantalla remota";
    public string FeatureDescription => "Ver la pantalla del PC en el móvil y controlarla con el dedo. Mientras alguien la mira, el icono de la bandeja lo indica.";
    public string FeatureIcon => "screen";
    public bool EnabledByDefault => true;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("displays", "Pantallas del PC"),
        new CommandDescriptor("open",     "Ticket para abrir la transmisión de la pantalla"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "displays" => CommandResponse.Ok(req.Id, new { displays = Displays.List() }),
                "open" => CommandResponse.Ok(req.Id, new
                {
                    ticket = tickets.Issue(session, SocketName),
                    path = "/socket/" + SocketName,
                    expiresInSec = (int)SocketTickets.Lifetime.TotalSeconds,
                }),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    public async Task RunSocketAsync(ModuleSocket socket, CancellationToken ct)
    {
        var session = new ScreenSession(socket, logger);
        activity.Add("screen", $"{socket.Session.DeviceName} empezó a ver la pantalla", socket.ClientIp, "warning");
        try
        {
            await session.RunAsync(ct);
        }
        finally
        {
            var minutes = Math.Max(1, (int)Math.Round(session.Duration.TotalMinutes));
            activity.Add("screen", $"{socket.Session.DeviceName} dejó de ver la pantalla", $"{minutes} min");
        }
    }
}
