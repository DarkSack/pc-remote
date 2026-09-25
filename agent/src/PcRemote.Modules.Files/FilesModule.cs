using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Activity;
using PcRemote.Core.Plugins;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Files;

// ══════════════════════════════════════════════════════════════
// Files — browse the PC from the phone.
//
//   roots  → user folders (Escritorio, Documentos, Descargas…) and drives
//   list   → one folder: folders first, then files
//   open   → open a file / folder on the PC with its default app
//   reveal → show it selected in Explorer
//   read   → a chunk of a file (base64), to download it to the phone
//   upload → a chunk of a file coming from the phone (to Descargas by default)
//
// Local paths only (no \\server\share, no \\?\ device paths). Nothing here
// deletes or overwrites: an upload whose name exists gets " (1)", " (2)"…
// Optional feature, on by default; the PC owner can switch it off in the panel.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class FilesModule(ActivityLog activity) : ICommandModule, IOptionalModule, ISessionAware
{
    public string Domain => "files";

    public string FeatureName => "Archivos";
    public string FeatureDescription => "Explorar las carpetas del PC, abrir archivos en él y pasar archivos entre el PC y el móvil.";
    public string FeatureIcon => "folder";
    public bool EnabledByDefault => true;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("roots",  "Carpetas del usuario y unidades"),
        new CommandDescriptor("list",   "Contenido de una carpeta"),
        new CommandDescriptor("open",   "Abrir en el PC con la app predeterminada"),
        new CommandDescriptor("reveal", "Mostrar en el Explorador"),
        new CommandDescriptor("read",   "Leer un trozo de un archivo (descarga)"),
        new CommandDescriptor("upload", "Recibir un trozo de un archivo (subida)"),
    };

    public const int MaxEntries = 3000;
    public const int MaxChunkBytes = 1024 * 1024;
    public const long MaxUploadBytes = 2L * 1024 * 1024 * 1024;

    /// <summary>Uploads in progress: session → (upload id → state).</summary>
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, Upload>> _uploads = new();

    private sealed class Upload
    {
        public required string PartPath { get; init; }
        public required string FinalDir { get; init; }
        public required string FileName { get; init; }
        public long Written;
    }

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return Task.FromResult(req.Action switch
            {
                "roots"  => CommandResponse.Ok(req.Id, Roots()),
                "list"   => List(req),
                "open"   => Open(req, reveal: false),
                "reveal" => Open(req, reveal: true),
                "read"   => Read(req),
                "upload" => Receive(req, session),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (UnauthorizedAccessException ex)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.PermissionDenied, $"Sin permiso: {ex.Message}"));
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.NotFound, ex.Message));
        }
        catch (IOException ex)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InternalError, ex.Message));
        }
        catch (Exception ex) when (ex is ArgumentException or FormatException && ex is not ArgumentNullException)
        {
            // Bad path, bad base64.
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, ex.Message));
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    // ── roots ─────────────────────────────────────────────
    private static object Roots()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var places = new (string Name, string Icon, string? Path)[]
        {
            ("Escritorio", "desktop", Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory)),
            ("Descargas", "download", DownloadsFolder()),
            ("Documentos", "description", Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)),
            ("Imágenes", "image", Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)),
            ("Música", "music", Environment.GetFolderPath(Environment.SpecialFolder.MyMusic)),
            ("Vídeos", "movie", Environment.GetFolderPath(Environment.SpecialFolder.MyVideos)),
            ("Carpeta personal", "home", home),
        }
        .Where(p => !string.IsNullOrEmpty(p.Path) && Directory.Exists(p.Path))
        .Select(p => new { name = p.Name, icon = p.Icon, path = p.Path })
        .ToArray();

        var drives = DriveInfo.GetDrives()
            .Where(d => d.DriveType is DriveType.Fixed or DriveType.Removable or DriveType.Network or DriveType.CDRom)
            .Select(d =>
            {
                bool ready = d.IsReady;
                return new
                {
                    name = d.Name.TrimEnd('\\'),
                    path = d.RootDirectory.FullName,
                    label = ready ? SafeLabel(d) : null,
                    type = d.DriveType.ToString().ToLowerInvariant(),
                    ready,
                    totalBytes = ready ? d.TotalSize : (long?)null,
                    freeBytes = ready ? d.AvailableFreeSpace : (long?)null,
                };
            })
            .ToArray();

        return new { places, drives };
    }

    private static string? SafeLabel(DriveInfo d) { try { return d.VolumeLabel; } catch { return null; } }

    // ── list ──────────────────────────────────────────────
    private static CommandResponse List(CommandRequest req)
    {
        var p = req.Params ?? default;
        var path = CheckPath(p.GetProperty("path").GetString());
        var showHidden = p.TryGetProperty("showHidden", out var h) && h.ValueKind == JsonValueKind.True;

        var dir = new DirectoryInfo(path);
        if (!dir.Exists) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"No existe la carpeta {path}");

        var options = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            AttributesToSkip = showHidden ? 0 : FileAttributes.Hidden | FileAttributes.System,
            RecurseSubdirectories = false,
        };

        var entries = new List<object>();
        var truncated = false;
        foreach (var info in dir.EnumerateFileSystemInfos("*", options)
                     .OrderBy(i => i is FileInfo)
                     .ThenBy(i => i.Name, StringComparer.CurrentCultureIgnoreCase))
        {
            if (entries.Count >= MaxEntries) { truncated = true; break; }
            var isDir = info is DirectoryInfo;
            entries.Add(new
            {
                name = info.Name,
                path = info.FullName,
                dir = isDir,
                size = isDir ? (long?)null : ((FileInfo)info).Length,
                modified = new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeMilliseconds(),
                ext = isDir ? null : info.Extension.TrimStart('.').ToLowerInvariant(),
                hidden = info.Attributes.HasFlag(FileAttributes.Hidden),
            });
        }

        return CommandResponse.Ok(req.Id, new
        {
            path = dir.FullName,
            name = dir.Name,
            parent = dir.Parent?.FullName,
            entries,
            truncated,
        });
    }

    // ── open / reveal ─────────────────────────────────────
    private CommandResponse Open(CommandRequest req, bool reveal)
    {
        var path = CheckPath((req.Params ?? default).GetProperty("path").GetString());
        var isDir = Directory.Exists(path);
        if (!isDir && !File.Exists(path)) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"No existe {path}");

        var psi = reveal
            ? new ProcessStartInfo("explorer.exe") { UseShellExecute = false, ArgumentList = { "/select,", path } }
            : new ProcessStartInfo(path) { UseShellExecute = true, WorkingDirectory = isDir ? path : Path.GetDirectoryName(path)! };
        Process.Start(psi)?.Dispose();
        activity.Add("files", reveal ? $"Mostrado en el Explorador: {Path.GetFileName(path)}" : $"Abierto en el PC: {Path.GetFileName(path)}", path);
        return CommandResponse.Ok(req.Id, new { opened = path });
    }

    // ── read (download) ───────────────────────────────────
    private CommandResponse Read(CommandRequest req)
    {
        var p = req.Params ?? default;
        var path = CheckPath(p.GetProperty("path").GetString());
        var offset = p.TryGetProperty("offset", out var o) ? o.GetInt64() : 0;
        var length = p.TryGetProperty("length", out var l) ? Math.Clamp(l.GetInt32(), 1, MaxChunkBytes) : MaxChunkBytes;

        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        if (offset < 0 || offset > fs.Length) return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "offset fuera del archivo");
        fs.Position = offset;
        var buffer = new byte[(int)Math.Min(length, fs.Length - offset)];
        var read = fs.ReadAtLeast(buffer, buffer.Length, throwOnEndOfStream: false);
        if (offset == 0) activity.Add("files", $"Enviado al móvil: {Path.GetFileName(path)}", FormatSize(fs.Length));

        return CommandResponse.Ok(req.Id, new
        {
            name = Path.GetFileName(path),
            offset,
            length = read,
            total = fs.Length,
            eof = offset + read >= fs.Length,
            data = Convert.ToBase64String(buffer, 0, read),
        });
    }

    // ── upload ────────────────────────────────────────────
    /// <remarks>
    /// Params: <c>uploadId</c> (client-chosen), <c>name</c>, <c>offset</c>, <c>data</c>
    /// (base64), <c>done</c>, optional <c>dir</c> (default: Descargas). Chunks must arrive in
    /// order; the file is written as a hidden .part and renamed when <c>done</c>.
    /// </remarks>
    private CommandResponse Receive(CommandRequest req, ClientSession session)
    {
        var p = req.Params ?? default;
        var uploadId = p.GetProperty("uploadId").GetString() ?? "";
        if (uploadId.Length is 0 or > 64) return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "uploadId no válido");
        var offset = p.GetProperty("offset").GetInt64();
        var data = Convert.FromBase64String(p.GetProperty("data").GetString() ?? "");
        var done = p.TryGetProperty("done", out var d) && d.ValueKind == JsonValueKind.True;
        if (data.Length > MaxChunkBytes) return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Trozo demasiado grande");

        var mine = _uploads.GetOrAdd(session.SessionId, _ => new());
        if (offset == 0)
        {
            var name = SanitizeFileName(p.GetProperty("name").GetString());
            if (name is null) return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Nombre de archivo no válido");
            var dir = p.TryGetProperty("dir", out var dd) && dd.GetString() is { Length: > 0 } custom ? CheckPath(custom) : DownloadsFolder();
            if (!Directory.Exists(dir)) return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, $"No existe la carpeta {dir}");

            if (mine.TryRemove(uploadId, out var stale)) TryDelete(stale.PartPath);
            var part = Path.Combine(dir, $".{name}.{uploadId[..Math.Min(8, uploadId.Length)]}.part");
            File.WriteAllBytes(part, Array.Empty<byte>());
            File.SetAttributes(part, FileAttributes.Hidden);
            mine[uploadId] = new Upload { PartPath = part, FinalDir = dir, FileName = name };
        }

        if (!mine.TryGetValue(uploadId, out var up))
            return CommandResponse.Fail(req.Id, ErrorCodes.NotFound, "Subida desconocida; empieza desde el principio.");
        if (offset != up.Written)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, $"Se esperaba el byte {up.Written}, no {offset}.");
        if (up.Written + data.Length > MaxUploadBytes)
        {
            mine.TryRemove(uploadId, out _);
            TryDelete(up.PartPath);
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "Archivo demasiado grande (máx. 2 GB).");
        }

        using (var fs = new FileStream(up.PartPath, FileMode.Append, FileAccess.Write, FileShare.None))
            fs.Write(data);
        up.Written += data.Length;

        if (!done) return CommandResponse.Ok(req.Id, new { received = up.Written });

        mine.TryRemove(uploadId, out _);
        var final = UniquePath(up.FinalDir, up.FileName);
        File.SetAttributes(up.PartPath, FileAttributes.Normal);
        File.Move(up.PartPath, final);
        activity.Add("files", $"Archivo recibido del móvil: {Path.GetFileName(final)}", FormatSize(up.Written), "success");
        return CommandResponse.Ok(req.Id, new { received = up.Written, path = final });
    }

    public void OnSessionEnded(ClientSession session)
    {
        // Half-finished uploads die with the connection; do not leave .part files behind.
        if (_uploads.TryRemove(session.SessionId, out var mine))
            foreach (var up in mine.Values) TryDelete(up.PartPath);
    }

    // ── helpers ───────────────────────────────────────────
    /// <summary>Absolute, local, normalised. Throws INVALID_PARAMS-worthy errors otherwise.</summary>
    internal static string CheckPath(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) throw new ArgumentException("path es obligatorio");
        if (raw.StartsWith(@"\\", StringComparison.Ordinal) || raw.StartsWith("//", StringComparison.Ordinal))
            throw new ArgumentException("Solo rutas locales (nada de \\\\servidor ni \\\\?\\).");
        if (!Path.IsPathFullyQualified(raw)) throw new ArgumentException("La ruta debe ser absoluta (C:\\…).");
        return Path.GetFullPath(raw);
    }

    internal static string? SanitizeFileName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;
        name = Path.GetFileName(name.Replace('/', '\\'));
        var invalid = Path.GetInvalidFileNameChars();
        var clean = new string(name.Select(c => invalid.Contains(c) ? '_' : c).ToArray()).Trim().TrimEnd('.');
        if (clean.Length == 0 || clean is "." or "..") return null;
        return clean.Length > 180 ? clean[..180] : clean;
    }

    internal static string UniquePath(string dir, string name)
    {
        var candidate = Path.Combine(dir, name);
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (var i = 1; File.Exists(candidate) || Directory.Exists(candidate); i++)
            candidate = Path.Combine(dir, $"{stem} ({i}){ext}");
        return candidate;
    }

    private static string DownloadsFolder()
    {
        // No SpecialFolder for Downloads; the known-folder GUID via SHGetKnownFolderPath would be
        // exact, but %USERPROFILE%\Downloads is right on virtually every machine.
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        return Directory.Exists(path) ? path : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
    }

    private static void TryDelete(string path) { try { File.Delete(path); } catch { /* best effort */ } }

    internal static string FormatSize(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:0.#} KB",
        < 1024L * 1024 * 1024 => $"{bytes / 1024.0 / 1024:0.#} MB",
        _ => $"{bytes / 1024.0 / 1024 / 1024:0.##} GB",
    };
}
