using System.Runtime.Versioning;
using System.Text.Json;
using NAudio.CoreAudioApi;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;
using Windows.Media.Control;

namespace PcRemote.Modules.Media;

// ══════════════════════════════════════════════════════════════
// Media — control de reproducción (SMTC) + volumen (NAudio).
//
// SMTC (System Media Transport Controls) es el bus de Windows por
// el que Spotify/YouTube/etc. exponen play/pause/next + metadata.
//
// El volumen del dispositivo por defecto se maneja con NAudio
// (IAudioEndpointVolume vía COM).
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class MediaModule : ICommandModule
{
    public string Domain => "media";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("play",       "Reanudar reproducción"),
        new CommandDescriptor("pause",      "Pausar reproducción"),
        new CommandDescriptor("playPause",  "Toggle play/pause"),
        new CommandDescriptor("next",       "Siguiente pista"),
        new CommandDescriptor("previous",   "Pista anterior"),
        new CommandDescriptor("nowPlaying", "Metadata (título, artista, sesión)"),
        new CommandDescriptor("volumeGet",  "Obtener volumen 0..100 + mute"),
        new CommandDescriptor("volumeSet",  "Ajustar volumen 0..100"),
        new CommandDescriptor("volumeMute", "Alternar mute"),
    };

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return req.Action switch
            {
                "play"       => await SessionAction(req, s => s.TryPlayAsync().AsTask()),
                "pause"      => await SessionAction(req, s => s.TryPauseAsync().AsTask()),
                "playPause"  => await SessionAction(req, s => s.TryTogglePlayPauseAsync().AsTask()),
                "next"       => await SessionAction(req, s => s.TrySkipNextAsync().AsTask()),
                "previous"   => await SessionAction(req, s => s.TrySkipPreviousAsync().AsTask()),
                "nowPlaying" => await NowPlaying(req),
                "volumeGet"  => VolumeGet(req),
                "volumeSet"  => VolumeSet(req),
                "volumeMute" => VolumeMute(req),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (Exception ex)
        {
            return CommandResponse.Fail(req.Id, ErrorCodes.InternalError, ex.Message);
        }
    }

    // ── SMTC helpers ─────────────────────────────────────────

    private static async Task<GlobalSystemMediaTransportControlsSession?> GetCurrentSessionAsync()
    {
        var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        return mgr.GetCurrentSession();
    }

    private static async Task<CommandResponse> SessionAction(CommandRequest req, Func<GlobalSystemMediaTransportControlsSession, Task<bool>> action)
    {
        var s = await GetCurrentSessionAsync();
        if (s == null) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, "No active media session");
        var ok = await action(s);
        return ok
            ? CommandResponse.Ok(req.Id, new { ok = true, source = s.SourceAppUserModelId })
            : CommandResponse.Fail(req.Id, ErrorCodes.InternalError, "Session refused the command");
    }

    private static async Task<CommandResponse> NowPlaying(CommandRequest req)
    {
        var s = await GetCurrentSessionAsync();
        if (s == null) return CommandResponse.Ok(req.Id, new { active = false });
        var props = await s.TryGetMediaPropertiesAsync();
        var pb = s.GetPlaybackInfo();
        return CommandResponse.Ok(req.Id, new
        {
            active   = true,
            source   = s.SourceAppUserModelId,
            title    = props?.Title,
            artist   = props?.Artist,
            album    = props?.AlbumTitle,
            status   = pb?.PlaybackStatus.ToString(),
        });
    }

    // ── Volume helpers ───────────────────────────────────────

    private static AudioEndpointVolume GetDefaultDeviceVolume()
    {
        var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        return device.AudioEndpointVolume;
    }

    private static CommandResponse VolumeGet(CommandRequest req)
    {
        var vol = GetDefaultDeviceVolume();
        return CommandResponse.Ok(req.Id, new
        {
            volume = (int)Math.Round(vol.MasterVolumeLevelScalar * 100),
            mute   = vol.Mute,
        });
    }

    private static CommandResponse VolumeSet(CommandRequest req)
    {
        var p = req.Params ?? default;
        var pct = Math.Clamp(p.GetProperty("volume").GetInt32(), 0, 100);
        var vol = GetDefaultDeviceVolume();
        vol.MasterVolumeLevelScalar = pct / 100f;
        return CommandResponse.Ok(req.Id, new { volume = pct });
    }

    private static CommandResponse VolumeMute(CommandRequest req)
    {
        var p = req.Params ?? default;
        var vol = GetDefaultDeviceVolume();
        // Si viene `mute` explícito, usarlo; sino toggle.
        bool desired = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("mute", out var m)
            ? m.GetBoolean() : !vol.Mute;
        vol.Mute = desired;
        return CommandResponse.Ok(req.Id, new { mute = desired });
    }
}
