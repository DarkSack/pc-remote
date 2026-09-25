using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Files;

// ══════════════════════════════════════════════════════════════
// Files — browse the PC's disks from the phone.
//
//   roots               → known folders (Escritorio, Documentos, Descargas…) and drives
//   list   { path, hidden? } → folder contents, folders first
//   open   { path }     → open with its default app on the PC
//   reveal { path }     → show it selected in Explorer
//   read   { path }     → the file itself (≤ 10 MB) to save or share on the phone
//
// Local paths only: UNC paths (\\server\share) are refused, so a phone
// cannot make the PC authenticate against another machine. "open" refuses
// programs and scripts: apps are launched from the app catalog (by id),
// never from a path sent by the phone — the terminal plugin exists for that.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class FilesModule : ICommandModule, IPluginMetadata
{
    public string Domain => "files";

    public string DisplayName => "Archivos";
    public string Description => "Explorar las carpetas y discos del PC, abrir archivos en el PC y descargarlos al móvil.";
    public string Category => PluginCategories.Tools;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("roots",  "Carpetas conocidas y unidades"),
        new CommandDescriptor("list",   "Contenido de una carpeta"),
        new CommandDescriptor("open",   "Abrir un archivo con su app predeterminada"),
        new CommandDescriptor("reveal", "Mostrar en el Explorador"),
        new CommandDescriptor("read",   "Descargar un archivo (máx. 10 MB)"),
    };

    public const long MaxReadBytes = 10 * 1024 * 1024;
    private const int MaxEntries = 2000;

    /// <summary>Extensions that run code when opened. Checked on top of PATHEXT.</summary>
    internal static readonly HashSet<string> Executable = new(StringComparer.OrdinalIgnoreCase)
    {
        ".exe", ".com", ".bat", ".cmd", ".ps1", ".psm1", ".vbs", ".vbe", ".js", ".jse", ".wsf", ".wsh",
        ".msi", ".msp", ".msc", ".scr", ".pif", ".cpl", ".hta", ".jar", ".lnk", ".url", ".reg",
        ".appref-ms", ".application", ".gadget", ".inf", ".scf", ".settingcontent-ms", ".diagcab", ".appx", ".msix",
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            var p = req.Params is { ValueKind: JsonValueKind.Object } o ? o : default;
            return Task.FromResult(req.Action switch
            {
                "roots"  => Roots(req.Id),
                "list"   => List(req.Id, p),
                "open"   => Open(req.Id, p),
                "reveal" => Reveal(req.Id, p),
                "read"   => Read(req.Id, p),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            });
        }
        catch (ArgumentException ex) when (ex is not ArgumentNullException)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, ex.Message));
        }
        catch (UnauthorizedAccessException ex)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.PermissionDenied, ex.Message));
        }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException)
        {
            return Task.FromResult(CommandResponse.Fail(req.Id, ErrorCodes.NotFound, ex.Message));
        }
        catch (Exception ex)
        {
            return Task.FromResult(CommandResponse.FromException(req.Id, ex));
        }
    }

    private static CommandResponse Roots(string id)
    {
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var known = new (string name, string key, string? path)[]
        {
            ("Escritorio", "desktop", Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory)),
            ("Documentos", "documents", Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)),
            ("Descargas", "downloads", KnownFolder(new Guid("374DE290-123F-4565-9164-39C4925E467B")) ?? Path.Combine(profile, "Downloads")),
            ("Imágenes", "pictures", Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)),
            ("Música", "music", Environment.GetFolderPath(Environment.SpecialFolder.MyMusic)),
            ("Vídeos", "videos", Environment.GetFolderPath(Environment.SpecialFolder.MyVideos)),
            ("Carpeta personal", "home", profile),
        };
        var folders = known
            .Where(k => !string.IsNullOrEmpty(k.path) && Directory.Exists(k.path))
            .Select(k => new { name = k.name, path = k.path, kind = k.key })
            .ToList();

        var drives = new List<object>();
        foreach (var d in DriveInfo.GetDrives())
        {
            try
            {
                if (!d.IsReady || d.DriveType is DriveType.Network or DriveType.NoRootDirectory) continue;
                drives.Add(new
                {
                    name = string.IsNullOrWhiteSpace(d.VolumeLabel) ? d.Name.TrimEnd('\\') : $"{d.VolumeLabel} ({d.Name.TrimEnd('\\')})",
                    path = d.Name,
                    kind = d.DriveType == DriveType.Removable ? "removable" : "drive",
                    totalBytes = d.TotalSize,
                    freeBytes = d.TotalFreeSpace,
                });
            }
            catch (IOException) { }
        }
        return CommandResponse.Ok(id, new { folders, drives });
    }

    private static CommandResponse List(string id, JsonElement p)
    {
        var path = LocalPath(p.GetProperty("path").GetString());
        var hidden = p.TryGetProperty("hidden", out var h) && h.ValueKind == JsonValueKind.True;
        var dir = new DirectoryInfo(path);
        if (!dir.Exists) return CommandResponse.Fail(id, ErrorCodes.NotFound, $"No existe la carpeta {path}");

        var skip = hidden ? FileAttributes.None : FileAttributes.Hidden | FileAttributes.System;
        var options = new EnumerationOptions { IgnoreInaccessible = true, AttributesToSkip = skip, RecurseSubdirectories = false };
        var entries = new List<object>();
        var truncated = false;
        foreach (var info in dir.EnumerateFileSystemInfos("*", options)
                     .OrderByDescending(i => i is DirectoryInfo)
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
                ext = isDir ? null : info.Extension.ToLowerInvariant(),
            });
        }
        return CommandResponse.Ok(id, new
        {
            path = dir.FullName,
            parent = dir.Parent?.FullName,
            entries,
            truncated,
        });
    }

    private static CommandResponse Open(string id, JsonElement p)
    {
        var path = LocalPath(p.GetProperty("path").GetString());
        if (Directory.Exists(path))
        {
            Process.Start(new ProcessStartInfo { FileName = "explorer.exe", ArgumentList = { path }, UseShellExecute = false })?.Dispose();
            return CommandResponse.Ok(id, new { opened = path });
        }
        if (!File.Exists(path)) return CommandResponse.Fail(id, ErrorCodes.NotFound, $"No existe {path}");
        if (IsExecutable(path))
            return CommandResponse.Fail(id, ErrorCodes.PermissionDenied,
                "Por seguridad no se abren programas ni scripts desde Archivos. Usa Apps o la Terminal.");
        Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true })?.Dispose();
        return CommandResponse.Ok(id, new { opened = path });
    }

    private static CommandResponse Reveal(string id, JsonElement p)
    {
        var path = LocalPath(p.GetProperty("path").GetString());
        if (!File.Exists(path) && !Directory.Exists(path)) return CommandResponse.Fail(id, ErrorCodes.NotFound, $"No existe {path}");
        Process.Start(new ProcessStartInfo { FileName = "explorer.exe", Arguments = $"/select,\"{path}\"", UseShellExecute = false })?.Dispose();
        return CommandResponse.Ok(id, new { revealed = path });
    }

    private static CommandResponse Read(string id, JsonElement p)
    {
        var path = LocalPath(p.GetProperty("path").GetString());
        var fi = new FileInfo(path);
        if (!fi.Exists) return CommandResponse.Fail(id, ErrorCodes.NotFound, $"No existe {path}");
        if (fi.Length > MaxReadBytes)
            return CommandResponse.Fail(id, ErrorCodes.InvalidParams, $"El archivo pesa {fi.Length / 1048576.0:0.0} MB; el máximo es 10 MB.");
        var bytes = File.ReadAllBytes(path);
        return CommandResponse.Ok(id, new { name = fi.Name, size = fi.Length, base64 = Convert.ToBase64String(bytes) });
    }

    internal static bool IsExecutable(string path)
    {
        var ext = Path.GetExtension(path);
        if (ext.Length == 0) return false;
        if (Executable.Contains(ext)) return true;
        var pathExt = (Environment.GetEnvironmentVariable("PATHEXT") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries);
        return pathExt.Contains(ext, StringComparer.OrdinalIgnoreCase);
    }

    /// <summary>Absolute, normalised, on a local drive. Throws ArgumentException otherwise (→ INVALID_PARAMS).</summary>
    internal static string LocalPath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("path is required");
        if (path.StartsWith(@"\\", StringComparison.Ordinal) || path.StartsWith("//", StringComparison.Ordinal))
            throw new ArgumentException("Network (UNC) paths are not allowed");
        if (!Path.IsPathFullyQualified(path)) throw new ArgumentException("path must be absolute (C:\\…)");
        var full = Path.GetFullPath(path);
        if (full.StartsWith(@"\\", StringComparison.Ordinal)) throw new ArgumentException("Network (UNC) paths are not allowed");
        return full;
    }

    private static string? KnownFolder(Guid id)
    {
        try
        {
            if (SHGetKnownFolderPath(id, 0, IntPtr.Zero, out var ptr) != 0) return null;
            try { return Marshal.PtrToStringUni(ptr); }
            finally { Marshal.FreeCoTaskMem(ptr); }
        }
        catch { return null; }
    }

    [DllImport("shell32.dll")]
    private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr ppszPath);
}
