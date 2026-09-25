using System.Diagnostics;
using System.Text;

namespace PcRemote.Modules.Terminal;

/// <summary>Runs a process to completion with a timeout and bounded output.</summary>
internal static class ProcessRunner
{
    public sealed record Result(string Stdout, string Stderr, int ExitCode, long DurationMs, bool TimedOut, bool Truncated);

    public static async Task<Result> RunAsync(ProcessStartInfo psi, TimeSpan timeout, int maxChars, CancellationToken ct)
    {
        var sw = Stopwatch.StartNew();
        using var proc = new Process { StartInfo = psi, EnableRaisingEvents = true };
        var stdout = new BoundedBuffer(maxChars);
        var stderr = new BoundedBuffer(maxChars);
        proc.OutputDataReceived += (_, e) => { if (e.Data is not null) stdout.AppendLine(e.Data); };
        proc.ErrorDataReceived += (_, e) => { if (e.Data is not null) stderr.AppendLine(e.Data); };

        proc.Start();
        // Nothing reads stdin: close it so a command waiting for input ends instead of hanging.
        proc.StandardInput.Close();
        proc.BeginOutputReadLine();
        proc.BeginErrorReadLine();

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeout);
        var timedOut = false;
        try
        {
            await proc.WaitForExitAsync(timeoutCts.Token);
        }
        catch (OperationCanceledException)
        {
            timedOut = !ct.IsCancellationRequested;
            try { proc.Kill(entireProcessTree: true); } catch { /* already gone */ }
            try { await proc.WaitForExitAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5)); } catch { }
            ct.ThrowIfCancellationRequested();
        }

        return new Result(
            stdout.ToString(), stderr.ToString(),
            timedOut ? -1 : proc.ExitCode,
            sw.ElapsedMilliseconds, timedOut, stdout.Truncated || stderr.Truncated);
    }

    private sealed class BoundedBuffer(int max)
    {
        private readonly StringBuilder _sb = new();
        public bool Truncated { get; private set; }

        public void AppendLine(string line)
        {
            lock (_sb)
            {
                if (_sb.Length >= max) { Truncated = true; return; }
                var room = max - _sb.Length;
                if (line.Length + 1 > room) { _sb.Append(line.AsSpan(0, Math.Max(0, room))); Truncated = true; return; }
                _sb.Append(line).Append('\n');
            }
        }

        public override string ToString() { lock (_sb) return _sb.ToString(); }
    }
}
