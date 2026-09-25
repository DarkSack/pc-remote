using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using PcRemote.Core.Activity;
using PcRemote.Core.Plugins;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Terminal;

// ══════════════════════════════════════════════════════════════
// Terminal — PowerShell commands from the phone.
//
// The one place where the agent runs a string. That is why it is an
// optional feature that is OFF until the PC owner turns it on in the
// panel (the phone cannot turn it on), and why every command lands in the
// activity timeline and the audit log.
//
//   info → shell, starting folder, user and host (for the prompt)
//   exec → stream: { type:"out"|"err", lines:[…] } while it runs, then
//          { type:"exit", code, cwd, durationMs, truncated }. Unsubscribing
//          kills the command (and everything it started).
//
// Each command runs in a fresh powershell.exe at the folder the app sends
// (cwd); the folder it ends in comes back, so "cd" works across commands.
// No stdin: a command that waits for input gets end-of-file and ends.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class TerminalModule(ActivityLog activity) : ICommandModule, IStreamModule, IOptionalModule
{
    public string Domain => "terminal";

    public string FeatureName => "Terminal";
    public string FeatureDescription => "Ejecutar comandos de PowerShell en el PC desde el móvil. Desactivado por defecto: quien tenga el móvil podrá ejecutar cualquier cosa como tu usuario.";
    public string FeatureIcon => "terminal";
    public bool EnabledByDefault => false;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info", "Shell, carpeta inicial, usuario y equipo"),
        new CommandDescriptor("exec", "Stream: ejecutar un comando y recibir su salida", IsDestructive: true),
    };

    public IReadOnlySet<string> StreamActions { get; } = new HashSet<string> { "exec" };

    public const int MaxCommandChars = 8 * 1024;
    public const int MaxOutputChars = 1024 * 1024;
    public static readonly TimeSpan MaxDuration = TimeSpan.FromMinutes(10);

    /// <summary>Last line of every run: where the command left us. Unlikely to collide with real output.</summary>
    private const string CwdMarker = "\u0001PCREMOTE_CWD\u0001";

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct) =>
        Task.FromResult(req.Action == "info"
            ? CommandResponse.Ok(req.Id, new
            {
                shell = "PowerShell",
                cwd = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                user = Environment.UserName,
                host = Environment.MachineName,
            })
            : CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"'{req.Action}' is a stream: subscribe to it."));

    public async IAsyncEnumerable<object> StartStreamAsync(
        string action, JsonElement? parameters, ClientSession session,
        [EnumeratorCancellation] CancellationToken ct)
    {
        if (action != "exec") yield break;

        var p = parameters ?? default;
        var command = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("command", out var c) ? c.GetString() ?? "" : "";
        var cwd = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("cwd", out var w) ? w.GetString() : null;

        if (command.Trim().Length == 0 || command.Length > MaxCommandChars)
        {
            yield return new { type = "exit", code = -1, cwd, error = "Comando vacío o demasiado largo.", durationMs = 0 };
            yield break;
        }
        if (string.IsNullOrWhiteSpace(cwd) || !Path.IsPathFullyQualified(cwd) || !Directory.Exists(cwd))
            cwd = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        activity.Add("terminal", Shorten(command, 80), $"{session.DeviceName} · {cwd}", "warning");

        var events = Channel.CreateUnbounded<(string Type, string Line)>();
        var psi = new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
            StandardOutputEncoding = new UTF8Encoding(false),
            StandardErrorEncoding = new UTF8Encoding(false),
            WorkingDirectory = cwd,
        };
        foreach (var a in new[] { "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand" })
            psi.ArgumentList.Add(a);
        psi.ArgumentList.Add(Convert.ToBase64String(Encoding.Unicode.GetBytes(BuildScript(command, cwd))));

        var started = Stopwatch.StartNew();
        using var proc = new Process { StartInfo = psi, EnableRaisingEvents = true };
        var outputChars = 0;
        var truncated = false;
        string? finalCwd = null;

        void OnLine(string type, string? line)
        {
            if (line is null) return;
            if (type == "out" && line.StartsWith(CwdMarker, StringComparison.Ordinal))
            {
                finalCwd = line[CwdMarker.Length..];
                return;
            }
            if (Interlocked.Add(ref outputChars, line.Length + 1) > MaxOutputChars) { truncated = true; return; }
            events.Writer.TryWrite((type, line));
        }
        proc.OutputDataReceived += (_, e) => OnLine("out", e.Data);
        proc.ErrorDataReceived += (_, e) => OnLine("err", e.Data);

        string? startError = null;
        try
        {
            proc.Start();
            proc.StandardInput.Close();
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
        }
        catch (Exception ex)
        {
            startError = ex.Message;
        }
        if (startError is not null)
        {
            yield return new { type = "exit", code = -1, cwd, error = $"No se pudo iniciar PowerShell: {startError}", durationMs = 0 };
            yield break;
        }

        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(MaxDuration);
        _ = proc.WaitForExitAsync(limit.Token).ContinueWith(_ =>
        {
            // WaitForExitAsync also waits for the redirected streams to reach EOF.
            events.Writer.TryComplete();
        }, TaskScheduler.Default);

        var timedOut = false;
        try
        {
            // Batch lines: a noisy command (dir /s) would otherwise send a frame per line.
            var batch = new List<string>();
            string? batchType = null;
            while (true)
            {
                bool more;
                try { more = await events.Reader.WaitToReadAsync(limit.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) { timedOut = !ct.IsCancellationRequested; break; }
                if (!more) break;

                await Task.Delay(40, CancellationToken.None).ConfigureAwait(false);
                while (events.Reader.TryRead(out var ev))
                {
                    if (batchType is not null && (ev.Type != batchType || batch.Count >= 200))
                    {
                        yield return new { type = batchType, lines = batch.ToArray() };
                        batch.Clear();
                    }
                    batchType = ev.Type;
                    batch.Add(ev.Line);
                }
                if (batch.Count > 0)
                {
                    yield return new { type = batchType!, lines = batch.ToArray() };
                    batch.Clear();
                    batchType = null;
                }
            }
        }
        finally
        {
            if (!proc.HasExited)
            {
                try { proc.Kill(entireProcessTree: true); } catch { /* already gone */ }
            }
        }

        if (ct.IsCancellationRequested) yield break;
        if (!proc.HasExited)
        {
            try { await proc.WaitForExitAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false); }
            catch (TimeoutException) { }
        }

        yield return new
        {
            type = "exit",
            code = proc.HasExited ? proc.ExitCode : -1,
            cwd = finalCwd is { Length: > 0 } && Directory.Exists(finalCwd) ? finalCwd : cwd,
            durationMs = (long)started.Elapsed.TotalMilliseconds,
            truncated,
            error = timedOut ? $"Cancelado: superó {MaxDuration.TotalMinutes:0} minutos." : null,
        };
    }

    /// <summary>
    /// Runs the command dot-sourced (so "cd" sticks), formats objects as text at a
    /// phone-friendly width, and ends with the marker line carrying the final folder.
    /// </summary>
    internal static string BuildScript(string command, string cwd) => $$"""
        $ErrorActionPreference = 'Continue'
        $ProgressPreference = 'SilentlyContinue'
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        $OutputEncoding = [System.Text.Encoding]::UTF8
        Set-Location -LiteralPath '{{cwd.Replace("'", "''")}}'
        $global:LASTEXITCODE = 0
        $__ok = $true
        $__errors = $Error.Count
        try {
            . {
        {{command}}
            } | Out-String -Stream -Width 120
            $__ok = $? -and $Error.Count -eq $__errors
        } catch {
            $__ok = $false
            [Console]::Error.WriteLine($_.ToString())
        }
        $__code = if ($LASTEXITCODE) { $LASTEXITCODE } elseif ($__ok) { 0 } else { 1 }
        [Console]::Out.WriteLine('{{CwdMarker}}' + (Get-Location).ProviderPath)
        exit $__code
        """;

    private static string Shorten(string s, int max)
    {
        s = s.ReplaceLineEndings(" ").Trim();
        return s.Length <= max ? s : s[..(max - 1)] + "…";
    }
}
