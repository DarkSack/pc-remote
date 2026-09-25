using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Terminal;

// ══════════════════════════════════════════════════════════════
// Terminal — run a PowerShell or cmd command on the PC and get its output.
//
// This is the one plugin that runs arbitrary code, so it is OFF by default
// and can only be switched on from the agent's panel, on the PC itself.
// It runs as the logged-in user (the agent is never elevated).
//
//   info  → { cwd, shell, shells, user, host }
//   run   { command, shell?: "powershell"|"pwsh"|"cmd", timeoutSec?: 1–600 (60) }
//         → { stdout, stderr, exitCode, cwd, durationMs, timedOut, truncated }
//
// Each phone session keeps its own working directory: `cd` works as in a
// real prompt. PowerShell reports the location after every command; for
// cmd, `cd`/`cd /d`/`X:` are handled here. Output is capped at 512 KB per
// stream; a timeout or a dropped connection kills the whole process tree.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed partial class TerminalModule : ICommandModule, IPluginMetadata, ISessionAware
{
    public string Domain => "terminal";

    public string DisplayName => "Terminal";
    public string Description => "Ejecutar comandos de PowerShell o cmd en el PC como tu usuario. Actívalo solo si lo necesitas.";
    public string Category => PluginCategories.Advanced;
    public bool EnabledByDefault => false;
    public bool Sensitive => true;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info", "Directorio actual y shells disponibles"),
        new CommandDescriptor("run",  "Ejecutar un comando", IsDestructive: true),
    };

    private const int MaxOutputChars = 512 * 1024;
    private const int MaxCommandChars = 8 * 1024;
    private const string CwdMarker = "\u001e__PCREMOTE_CWD__";

    private readonly ConcurrentDictionary<string, string> _cwd = new();

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return req.Action switch
            {
                "info" => Info(req.Id, session),
                "run"  => await Run(req, session, ct),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }

    private CommandResponse Info(string id, ClientSession session) => CommandResponse.Ok(id, new
    {
        cwd = Cwd(session),
        shell = "powershell",
        shells = AvailableShells(),
        user = Environment.UserName,
        host = Environment.MachineName,
    });

    private string Cwd(ClientSession session) =>
        _cwd.GetOrAdd(session.SessionId, _ => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile));

    private static string[] AvailableShells()
    {
        var list = new List<string> { "powershell", "cmd" };
        if (FindOnPath("pwsh.exe") is not null) list.Insert(1, "pwsh");
        return list.ToArray();
    }

    private async Task<CommandResponse> Run(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        var p = req.Params ?? default;
        var command = p.GetProperty("command").GetString() ?? "";
        if (string.IsNullOrWhiteSpace(command))
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "command is empty");
        if (command.Length > MaxCommandChars)
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, $"command is over {MaxCommandChars} characters");

        var shell = p.TryGetProperty("shell", out var s) ? s.GetString() ?? "powershell" : "powershell";
        var timeout = p.TryGetProperty("timeoutSec", out var t) ? Math.Clamp(t.GetInt32(), 1, 600) : 60;
        var cwd = Cwd(session);
        if (!Directory.Exists(cwd)) cwd = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        if (shell == "cmd" && TryCmdChangeDirectory(command.Trim(), cwd, out var newDir, out var error))
        {
            if (error is null) _cwd[session.SessionId] = newDir!;
            return CommandResponse.Ok(req.Id, new
            {
                stdout = "", stderr = error ?? "", exitCode = error is null ? 0 : 1,
                cwd = error is null ? newDir : cwd, durationMs = 0, timedOut = false, truncated = false,
            });
        }

        ProcessStartInfo psi = shell switch
        {
            "powershell" => PowerShell("powershell.exe", command, cwd),
            "pwsh" => PowerShell(FindOnPath("pwsh.exe") ?? "pwsh.exe", command, cwd),
            "cmd" => Cmd(command, cwd),
            _ => throw new ArgumentException($"Unknown shell '{shell}'"),
        };

        var result = await ProcessRunner.RunAsync(psi, TimeSpan.FromSeconds(timeout), MaxOutputChars, ct);

        var stdout = result.Stdout;
        var finalCwd = cwd;
        var markerAt = stdout.LastIndexOf(CwdMarker, StringComparison.Ordinal);
        if (markerAt >= 0)
        {
            var path = stdout[(markerAt + CwdMarker.Length)..].Trim();
            if (Directory.Exists(path)) finalCwd = path;
            stdout = stdout[..markerAt].TrimEnd('\r', '\n');
        }
        _cwd[session.SessionId] = finalCwd;

        return CommandResponse.Ok(req.Id, new
        {
            stdout,
            stderr = result.Stderr.TrimEnd(),
            exitCode = result.ExitCode,
            cwd = finalCwd,
            durationMs = result.DurationMs,
            timedOut = result.TimedOut,
            truncated = result.Truncated,
        });
    }

    private static ProcessStartInfo PowerShell(string exe, string command, string cwd)
    {
        // The command runs inside a script block piped through Out-String so tables
        // are not cut at 80 columns, then the final location is printed after a
        // marker (Set-Location inside the block still moves the runspace).
        var script = $$"""
            $ProgressPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Set-Location -LiteralPath '{{cwd.Replace("'", "''")}}'
            $__errors = $Error.Count
            $global:LASTEXITCODE = 0
            & {
            {{command}}
            } 2>&1 | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) { [Console]::Error.WriteLine(($_ | Out-String).TrimEnd()) }
                else { $_ }
            } | Out-String -Width 160 -Stream
            $__code = if ($LASTEXITCODE) { $LASTEXITCODE } elseif ($Error.Count -gt $__errors) { 1 } else { 0 }
            [Console]::Out.WriteLine('{{CwdMarker}}' + (Get-Location).ProviderPath)
            exit $__code
            """;
        var psi = Base(exe, cwd);
        psi.ArgumentList.Add("-NoLogo");
        psi.ArgumentList.Add("-NoProfile");
        psi.ArgumentList.Add("-NonInteractive");
        psi.ArgumentList.Add("-ExecutionPolicy");
        psi.ArgumentList.Add("Bypass");
        psi.ArgumentList.Add("-EncodedCommand");
        psi.ArgumentList.Add(Convert.ToBase64String(Encoding.Unicode.GetBytes(script)));
        return psi;
    }

    private static ProcessStartInfo Cmd(string command, string cwd)
    {
        var psi = Base("cmd.exe", cwd);
        // /s /c "…": cmd strips the outer quotes and runs the rest verbatim.
        psi.Arguments = $"/d /s /c \"chcp 65001>nul & {command}\"";
        return psi;
    }

    private static ProcessStartInfo Base(string exe, string cwd) => new()
    {
        FileName = exe,
        WorkingDirectory = cwd,
        UseShellExecute = false,
        CreateNoWindow = true,
        RedirectStandardInput = true,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
        StandardOutputEncoding = Encoding.UTF8,
        StandardErrorEncoding = Encoding.UTF8,
    };

    /// <summary>cd, cd /d, chdir and "X:" for cmd, which cannot report its directory back.</summary>
    internal static bool TryCmdChangeDirectory(string command, string cwd, out string? newDir, out string? error)
    {
        newDir = null; error = null;
        string? target = null;
        var drive = DriveOnly().Match(command);
        if (drive.Success) target = command + "\\";
        else
        {
            var m = CdCommand().Match(command);
            if (!m.Success) return false;
            target = m.Groups["path"].Value.Trim().Trim('"');
            if (target.Length == 0) { newDir = cwd; return true; } // bare "cd" prints the directory in cmd
        }
        try
        {
            var full = Path.GetFullPath(Path.IsPathRooted(target) ? target : Path.Combine(cwd, target));
            if (Directory.Exists(full)) newDir = full;
            else error = "El sistema no puede encontrar la ruta especificada.";
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            error = "La sintaxis del nombre de archivo, directorio o volumen no es correcta.";
        }
        return true;
    }

    [GeneratedRegex(@"^[a-zA-Z]:$")]
    private static partial Regex DriveOnly();

    [GeneratedRegex(@"^(cd|chdir)(\s+/d)?(\s+(?<path>.*))?$", RegexOptions.IgnoreCase)]
    private static partial Regex CdCommand();

    private static string? FindOnPath(string exe)
    {
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var candidate = Path.Combine(dir.Trim(), exe);
                if (File.Exists(candidate)) return candidate;
            }
            catch (ArgumentException) { }
        }
        return null;
    }

    public void OnSessionEnded(ClientSession session) => _cwd.TryRemove(session.SessionId, out _);
}
