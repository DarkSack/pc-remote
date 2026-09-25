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
public sealed class MediaModule : ICommandModule, IPluginMetadata, IStreamModule
{
    public string Domain => "media";

    public string DisplayName => "Multimedia";
    public string Description => "Lo que suena en el PC, controles de reproducción y volumen.";
    public string Category => PluginCategories.Control;

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "nowPlaying" };

    /// <summary>Artwork larger than this is not sent (JPEG/PNG thumbnails are usually 10–100 KB).</summary>
    private const int MaxArtworkBytes = 512 * 1024;

    /// <summary>Ticks (one a second) spent waiting for a new track's artwork to appear.</summary>
    private const int ArtworkTries = 5;

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
            return CommandResponse.FromException(req.Id, ex);
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

    // ── Stream: nowPlaying ───────────────────────────────────

    /// <summary>
    /// Pushes track / playback / volume changes. Polls once a second and only
    /// sends when something changed. The artwork travels only when the track
    /// changes (base64), never on every tick; `artworkBase64` is null otherwise and
    /// `trackChanged` tells the client whether to replace or keep its image.
    /// </summary>
    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "nowPlaying") yield break;

        string? lastTrack = null;
        string? lastState = null;
        // Players publish the title first and the thumbnail a moment later (Spotify,
        // browsers). Reading the artwork only on the tick the track changed left
        // those tracks without a cover; it is now retried for a few ticks.
        var artworkTriesLeft = 0;

        while (!ct.IsCancellationRequested)
        {
            var snap = await SnapshotAsync();
            var track = snap.Active ? $"{snap.Source}|{snap.Title}|{snap.Artist}|{snap.Album}" : "";
            var state = $"{track}|{snap.Status}|{snap.Volume}|{snap.Mute}";

            var trackChanged = track != lastTrack;
            if (trackChanged) artworkTriesLeft = snap.Active ? ArtworkTries : 0;

            string? artwork = null;
            if (artworkTriesLeft > 0 && snap.Session is not null)
            {
                artwork = await TryReadArtworkAsync(snap.Session);
                artworkTriesLeft = artwork is null ? artworkTriesLeft - 1 : 0;
            }

            // A late cover goes out on its own, with trackChanged: true so the client
            // replaces its image (the flag means "take artworkBase64 as the new image").
            if (state != lastState || artwork is not null)
            {
                trackChanged = trackChanged || artwork is not null;
                lastTrack = track;
                lastState = state;
                yield return new
                {
                    active = snap.Active,
                    source = snap.Source,
                    title  = snap.Title,
                    artist = snap.Artist,
                    album  = snap.Album,
                    status = snap.Status,
                    volume = snap.Volume,
                    mute   = snap.Mute,
                    trackChanged,
                    artworkBase64 = artwork,
                };
            }

            try { await Task.Delay(1000, ct); } catch (TaskCanceledException) { yield break; }
        }
    }

    private sealed record Snapshot(
        GlobalSystemMediaTransportControlsSession? Session, bool Active, string? Source,
        string? Title, string? Artist, string? Album, string? Status, int? Volume, bool? Mute);

    private static async Task<Snapshot> SnapshotAsync()
    {
        int? volume = null;
        bool? mute = null;
        try
        {
            (volume, mute) = WithDefaultDevice(v => ((int?)(int)Math.Round(v.MasterVolumeLevelScalar * 100), (bool?)v.Mute));
        }
        catch { /* no audio device: leave volume unknown */ }

        try
        {
            var s = await GetCurrentSessionAsync();
            if (s is null) return new Snapshot(null, false, null, null, null, null, null, volume, mute);
            var props = await s.TryGetMediaPropertiesAsync();
            var pb = s.GetPlaybackInfo();
            return new Snapshot(s, true, s.SourceAppUserModelId, props?.Title, props?.Artist,
                props?.AlbumTitle, pb?.PlaybackStatus.ToString(), volume, mute);
        }
        catch
        {
            // SMTC throws while an app is closing its session; report "nothing playing".
            return new Snapshot(null, false, null, null, null, null, null, volume, mute);
        }
    }

    private static async Task<string?> TryReadArtworkAsync(GlobalSystemMediaTransportControlsSession session)
    {
        try
        {
            var props = await session.TryGetMediaPropertiesAsync();
            if (props?.Thumbnail is null) return null;
            using var stream = await props.Thumbnail.OpenReadAsync();
            if (stream.Size == 0 || stream.Size > MaxArtworkBytes) return null;
            using var net = stream.AsStreamForRead();
            using var ms = new MemoryStream((int)stream.Size);
            await net.CopyToAsync(ms);
            return Convert.ToBase64String(ms.GetBuffer(), 0, (int)ms.Length);
        }
        catch
        {
            return null;
        }
    }

    // ── Volume helpers ───────────────────────────────────────

    /// <summary>
    /// Runs <paramref name="fn"/> against the default output device and releases the
    /// COM objects afterwards. They used to be created on every volume call and never
    /// disposed, so each slider move leaked an enumerator and a device.
    /// </summary>
    private static T WithDefaultDevice<T>(Func<AudioEndpointVolume, T> fn)
    {
        using var enumerator = new MMDeviceEnumerator();
        using var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        return fn(device.AudioEndpointVolume);
    }

    private static CommandResponse VolumeGet(CommandRequest req) =>
        WithDefaultDevice(vol => CommandResponse.Ok(req.Id, new
        {
            volume = (int)Math.Round(vol.MasterVolumeLevelScalar * 100),
            mute   = vol.Mute,
        }));

    private static CommandResponse VolumeSet(CommandRequest req)
    {
        var p = req.Params ?? default;
        var pct = Math.Clamp(p.GetProperty("volume").GetInt32(), 0, 100);
        return WithDefaultDevice(vol =>
        {
            vol.MasterVolumeLevelScalar = pct / 100f;
            return CommandResponse.Ok(req.Id, new { volume = pct });
        });
    }

    private static CommandResponse VolumeMute(CommandRequest req)
    {
        var p = req.Params ?? default;
        return WithDefaultDevice(vol =>
        {
            // Si viene `mute` explícito, usarlo; sino toggle.
            bool desired = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("mute", out var m)
                ? m.GetBoolean() : !vol.Mute;
            vol.Mute = desired;
            return CommandResponse.Ok(req.Id, new { mute = desired });
        });
    }
}
