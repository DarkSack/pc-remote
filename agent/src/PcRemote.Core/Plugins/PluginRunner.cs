using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PcRemote.Core.Plugins;

/// <summary>Result of running a plugin action.</summary>
public sealed record PluginRunResult(bool Started, int? ExitCode, string? Stdout, string? Stderr, bool TimedOut, bool Truncated);

/// <summary>A value typed on the phone did not pass the action's rules.</summary>
public sealed class PluginParamException(string message) : Exception(message);

/// <summary>
/// Runs one plugin action. No shell: the program and every argument go to
/// CreateProcess as separate items (ProcessStartInfo.ArgumentList), and each
/// {param} fills part of one argument only.
/// </summary>
public static class PluginRunner
{
    /// <summary>Per stream. Enough for ipconfig /all; a runaway script does not flood the phone.</summary>
    public const int MaxOutputChars = 64 * 1024;

    static PluginRunner()
    {
        // OEM code pages (850, 437…) are not built into .NET.
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    /// <summary>Checks every declared parameter against the values sent by the phone.</summary>
    public static Dictionary<string, string> BindParams(PluginAction action, JsonElement? values)
    {
        var bound = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var p in action.Params)
        {
            JsonElement? raw = values is { ValueKind: JsonValueKind.Object } v && v.TryGetProperty(p.Id, out var el) ? el : p.Default;
            var name = p.Label ?? p.Id;

            if (raw is null || raw.Value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined ||
                (raw.Value.ValueKind == JsonValueKind.String && raw.Value.GetString()!.Length == 0))
            {
                if (p.Required) throw new PluginParamException($"Falta «{name}».");
                bound[p.Id] = "";
                continue;
            }

            var value = raw.Value;
            bound[p.Id] = p.Type switch
            {
                "number" => BindNumber(p, name, value),
                "bool" => value.ValueKind switch
                {
                    JsonValueKind.True => "true",
                    JsonValueKind.False => "false",
                    JsonValueKind.String when bool.TryParse(value.GetString(), out var b) => b ? "true" : "false",
                    _ => throw new PluginParamException($"«{name}» debe ser sí o no."),
                },
                "choice" => value.ValueKind == JsonValueKind.String && p.Options!.Contains(value.GetString()!)
                    ? value.GetString()!
                    : throw new PluginParamException($"«{name}» debe ser una de las opciones."),
                _ => BindString(p, name, value),
            };
        }
        return bound;
    }

    private static string BindNumber(PluginParam p, string name, JsonElement value)
    {
        double n;
        if (value.ValueKind == JsonValueKind.Number) n = value.GetDouble();
        else if (value.ValueKind == JsonValueKind.String &&
                 double.TryParse(value.GetString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed)) n = parsed;
        else throw new PluginParamException($"«{name}» debe ser un número.");

        if (double.IsNaN(n) || double.IsInfinity(n)) throw new PluginParamException($"«{name}» debe ser un número.");
        if (p.Min is { } min && n < min) throw new PluginParamException($"«{name}» debe ser ≥ {min}.");
        if (p.Max is { } max && n > max) throw new PluginParamException($"«{name}» debe ser ≤ {max}.");
        return n.ToString(CultureInfo.InvariantCulture);
    }

    private static string BindString(PluginParam p, string name, JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.String) throw new PluginParamException($"«{name}» debe ser texto.");
        var s = value.GetString()!;
        if (s.Length > Math.Clamp(p.MaxLength, 1, 4096)) throw new PluginParamException($"«{name}» es demasiado largo.");
        // Control characters (newlines included) have no business in one argument.
        if (s.Any(char.IsControl)) throw new PluginParamException($"«{name}» tiene caracteres no permitidos.");
        if (p.Pattern is not null &&
            !Regex.IsMatch(s, $"^(?:{p.Pattern})$", RegexOptions.None, TimeSpan.FromMilliseconds(200)))
            throw new PluginParamException($"«{name}» no tiene el formato esperado.");
        return s;
    }

    public static string Fill(string template, IReadOnlyDictionary<string, string> values) =>
        PluginIds.Placeholder().Replace(template, m => values.TryGetValue(m.Groups[1].Value, out var v) ? v : m.Value);

    public static async Task<PluginRunResult> RunAsync(
        LoadedPlugin plugin, PluginAction action, IReadOnlyDictionary<string, string> values, CancellationToken ct)
    {
        if (!string.IsNullOrWhiteSpace(action.Open))
        {
            Process.Start(new ProcessStartInfo(Environment.ExpandEnvironmentVariables(action.Open))
            {
                UseShellExecute = true,
                WorkingDirectory = plugin.Directory,
            })?.Dispose();
            return new PluginRunResult(true, null, null, null, false, false);
        }

        var psi = new ProcessStartInfo(ResolveProgram(plugin.Directory, Environment.ExpandEnvironmentVariables(action.Run!)))
        {
            UseShellExecute = false,
            WorkingDirectory = plugin.Directory,
            CreateNoWindow = action.Output,
        };
        // Environment variables come from the manifest (the owner wrote them); only then
        // are the phone's values put in, so a value like "%USERPROFILE%" stays literal.
        foreach (var arg in action.Args) psi.ArgumentList.Add(Fill(Environment.ExpandEnvironmentVariables(arg), values));

        if (!action.Output)
        {
            Process.Start(psi)?.Dispose();
            return new PluginRunResult(true, null, null, null, false, false);
        }

        var encoding = string.Equals(action.Encoding, "utf8", StringComparison.OrdinalIgnoreCase)
            ? new UTF8Encoding(false)
            : Encoding.GetEncoding(GetOEMCP());
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.RedirectStandardInput = true;
        psi.StandardOutputEncoding = encoding;
        psi.StandardErrorEncoding = encoding;

        using var proc = Process.Start(psi) ?? throw new InvalidOperationException("No se pudo iniciar el proceso.");
        proc.StandardInput.Close(); // nothing will ever answer a prompt

        var stdout = new BoundedText(MaxOutputChars);
        var stderr = new BoundedText(MaxOutputChars);
        var readOut = PumpAsync(proc.StandardOutput, stdout);
        var readErr = PumpAsync(proc.StandardError, stderr);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(Math.Clamp(action.TimeoutSec, 1, 600)));
        var timedOut = false;
        try
        {
            await proc.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            timedOut = !ct.IsCancellationRequested;
            try { proc.Kill(entireProcessTree: true); } catch { /* already gone */ }
        }
        await Task.WhenAll(readOut, readErr).WaitAsync(TimeSpan.FromSeconds(2)).ContinueWith(_ => { }).ConfigureAwait(false);

        return new PluginRunResult(true, proc.HasExited ? proc.ExitCode : null,
            stdout.ToString(), stderr.ToString(), timedOut, stdout.Truncated || stderr.Truncated);
    }

    /// <summary>"script.ps1" or "tools\x.exe" next to plugin.json win over PATH.</summary>
    private static string ResolveProgram(string dir, string run)
    {
        if (Path.IsPathRooted(run)) return run;
        var local = Path.GetFullPath(Path.Combine(dir, run));
        return local.StartsWith(Path.GetFullPath(dir), StringComparison.OrdinalIgnoreCase) && File.Exists(local) ? local : run;
    }

    private static async Task PumpAsync(StreamReader reader, BoundedText sink)
    {
        var buffer = new char[4096];
        int n;
        while ((n = await reader.ReadAsync(buffer).ConfigureAwait(false)) > 0) sink.Append(buffer, n);
    }

    private sealed class BoundedText(int max)
    {
        private readonly StringBuilder _sb = new();
        public bool Truncated { get; private set; }

        public void Append(char[] chars, int count)
        {
            lock (_sb)
            {
                var room = max - _sb.Length;
                if (room <= 0) { Truncated = true; return; }
                if (count > room) { count = room; Truncated = true; }
                _sb.Append(chars, 0, count);
            }
        }

        public override string ToString() { lock (_sb) return _sb.ToString(); }
    }

    [DllImport("kernel32.dll")]
    private static extern int GetOEMCP();
}
