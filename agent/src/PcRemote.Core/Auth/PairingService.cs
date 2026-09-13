using System.Security.Cryptography;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Auth;

/// <summary>
/// Issues 6-digit pairing codes with TTL + rate limiting + lockout.
///
/// Each code is bound to whoever asked for it:
///   - a code requested over the WebSocket (<c>pair_init</c>) is only valid from
///     that same client IP;
///   - a code generated from the web panel is valid from any IP (the phone scans
///     it from the screen), but there is only ever one of them.
///
/// Why it matters: codes used to be a global pool and <c>pair_init</c> had no
/// limit. Asking for thousands of codes made every blind guess thousands of times
/// likelier to hit, which turned the 3-attempt lockout into a formality. Now an IP
/// holds at most one code, so each guess is still one in a million.
/// </summary>
public sealed class PairingService
{
    /// <summary>Codes alive at once, across all IPs (panel code included).</summary>
    private const int MaxActiveCodes = 5;

    /// <summary>A repeated pair_init reuses the live code; it only re-notifies after this.</summary>
    private static readonly TimeSpan NotifyCooldown = TimeSpan.FromSeconds(15);

    private const string PanelOrigin = "panel";

    private readonly AgentSettings _settings;
    private readonly ILogger<PairingService> _logger;
    private readonly object _gate = new();

    // All state below is guarded by _gate. Pairing is rare; a lock is simpler and
    // easier to get right than juggling several concurrent dictionaries.
    private readonly Dictionary<string, PairingCode> _byCode = new();
    private readonly Dictionary<string, PairingCode> _byOrigin = new();
    private readonly Dictionary<string, DateTimeOffset> _lastNotified = new();
    // Failures count only inside a window: without it, two typos last week plus one
    // today locked a legitimate user out.
    private readonly Dictionary<string, (int Count, DateTimeOffset First)> _failedAttempts = new();
    private readonly Dictionary<string, DateTimeOffset> _lockoutUntil = new();

    public PairingService(AgentSettings settings, ILogger<PairingService> logger)
    {
        _settings = settings;
        _logger   = logger;
    }

    /// <summary>Raised when the user must be shown a code (tray balloon).</summary>
    public event EventHandler<PairingCode>? CodeIssued;

    /// <summary>Code for a device asking over the network. Bound to its IP.</summary>
    public PairingRequestResult RequestCode(string clientIp)
    {
        PairingCode record;
        bool notify;
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            CleanupExpired(now);

            if (_lockoutUntil.TryGetValue(clientIp, out var until))
                return new PairingRequestResult.Locked((int)Math.Ceiling((until - now).TotalSeconds));

            if (_byOrigin.TryGetValue(clientIp, out var existing))
            {
                // Same device retrying: keep the code already on screen instead of
                // minting a new one (and a new balloon) on every reconnect.
                record = existing;
                notify = !_lastNotified.TryGetValue(clientIp, out var last) || now - last > NotifyCooldown;
            }
            else
            {
                if (_byCode.Count >= MaxActiveCodes)
                    return new PairingRequestResult.Busy();

                record = Store(clientIp, fromPanel: false, now);
                notify = true;
            }

            if (notify) _lastNotified[clientIp] = now;
        }

        if (notify)
        {
            _logger.LogInformation("Pairing code issued for {Ip} (TTL {Ttl}s)", clientIp, _settings.Pairing.CodeTtlSeconds);
            CodeIssued?.Invoke(this, record);
        }
        return new PairingRequestResult.Issued(record);
    }

    /// <summary>Code shown in the web panel. Replaces any previous panel code.</summary>
    public PairingCode IssuePanelCode()
    {
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            CleanupExpired(now);
            Forget(PanelOrigin);
            _logger.LogInformation("Pairing code issued from the web panel (TTL {Ttl}s)", _settings.Pairing.CodeTtlSeconds);
            // The panel shows the code itself, so no tray balloon. It is also exempt
            // from MaxActiveCodes: a LAN client filling the pool must not be able to
            // lock the owner out of pairing from their own screen.
            return Store(PanelOrigin, fromPanel: true, now);
        }
    }

    public PairingValidationResult Validate(string code, string clientIp)
    {
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            CleanupExpired(now);

            if (_lockoutUntil.TryGetValue(clientIp, out var until))
                return PairingValidationResult.LockedOut((int)Math.Ceiling((until - now).TotalSeconds));

            if (!_byCode.TryGetValue(code, out var record) ||
                (!record.FromPanel && record.ClientIp != clientIp))
            {
                RegisterFailure(clientIp, now);
                return PairingValidationResult.Invalid();
            }

            // One-time use.
            Forget(record.FromPanel ? PanelOrigin : record.ClientIp);
            _failedAttempts.Remove(clientIp);
            return PairingValidationResult.Valid(record);
        }
    }

    // ── internals (caller holds _gate) ─────────────────────────────

    private PairingCode Store(string origin, bool fromPanel, DateTimeOffset now)
    {
        string code;
        do { code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6"); }
        while (_byCode.ContainsKey(code));

        var record = new PairingCode(
            Code:      code,
            IssuedAt:  now,
            ExpiresAt: now.AddSeconds(_settings.Pairing.CodeTtlSeconds),
            ClientIp:  origin,
            FromPanel: fromPanel);
        _byCode[code] = record;
        _byOrigin[origin] = record;
        return record;
    }

    private void Forget(string origin)
    {
        if (_byOrigin.Remove(origin, out var record))
            _byCode.Remove(record.Code);
        _lastNotified.Remove(origin);
    }

    private void RegisterFailure(string clientIp, DateTimeOffset now)
    {
        var window = TimeSpan.FromSeconds(_settings.Pairing.LockoutSeconds);
        var attempts = _failedAttempts.TryGetValue(clientIp, out var prev) && now - prev.First < window
            ? (Count: prev.Count + 1, prev.First)
            : (Count: 1, First: now);
        if (attempts.Count >= _settings.Pairing.MaxAttempts)
        {
            _failedAttempts.Remove(clientIp);
            _lockoutUntil[clientIp] = now.AddSeconds(_settings.Pairing.LockoutSeconds);
            // A locked-out IP loses its pending code too; it gets a fresh one later.
            Forget(clientIp);
            _logger.LogWarning("Pairing lockout for {Ip} for {Sec}s (max attempts reached)",
                clientIp, _settings.Pairing.LockoutSeconds);
        }
        else
        {
            _failedAttempts[clientIp] = attempts;
        }
    }

    private void CleanupExpired(DateTimeOffset now)
    {
        foreach (var (origin, record) in _byOrigin.ToArray())
            if (record.ExpiresAt <= now) Forget(origin);

        foreach (var (ip, until) in _lockoutUntil.ToArray())
            if (until <= now) _lockoutUntil.Remove(ip);

        var window = TimeSpan.FromSeconds(_settings.Pairing.LockoutSeconds);
        foreach (var (ip, failures) in _failedAttempts.ToArray())
            if (now - failures.First >= window) _failedAttempts.Remove(ip);
    }
}

public sealed record PairingCode(
    string Code,
    DateTimeOffset IssuedAt,
    DateTimeOffset ExpiresAt,
    string ClientIp,
    bool FromPanel = false);

public abstract record PairingRequestResult
{
    public sealed record Issued(PairingCode Record) : PairingRequestResult;
    public sealed record Locked(int RemainingSeconds) : PairingRequestResult;
    public sealed record Busy : PairingRequestResult;
}

public abstract record PairingValidationResult
{
    public sealed record Ok(PairingCode Record) : PairingValidationResult;
    public sealed record InvalidCode : PairingValidationResult;
    public sealed record Locked(int RemainingSeconds) : PairingValidationResult;

    public static PairingValidationResult Valid(PairingCode r) => new Ok(r);
    public static PairingValidationResult Invalid() => new InvalidCode();
    public static PairingValidationResult LockedOut(int s) => new Locked(s);
}
