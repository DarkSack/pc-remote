using System.Collections.Concurrent;
using System.Security.Cryptography;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Auth;

/// <summary>Issues 6-digit pairing codes with TTL + rate limiting + lockout.</summary>
public sealed class PairingService
{
    private readonly AgentSettings _settings;
    private readonly ILogger<PairingService> _logger;
    private readonly ConcurrentDictionary<string, PairingCode> _codes = new();
    private readonly ConcurrentDictionary<string, int> _failedAttempts = new();
    private readonly ConcurrentDictionary<string, DateTimeOffset> _lockoutUntil = new();

    public PairingService(AgentSettings settings, ILogger<PairingService> logger)
    {
        _settings = settings;
        _logger   = logger;
    }

    public event EventHandler<PairingCode>? CodeIssued;

    /// <summary>Generates and returns a fresh code, notifying subscribers (tray).</summary>
    public PairingCode IssueCode(string clientIp)
    {
        var code = GenerateSixDigitCode();
        var record = new PairingCode(
            Code:      code,
            IssuedAt:  DateTimeOffset.UtcNow,
            ExpiresAt: DateTimeOffset.UtcNow.AddSeconds(_settings.Pairing.CodeTtlSeconds),
            ClientIp:  clientIp);
        _codes[code] = record;
        CleanupExpired();
        _logger.LogInformation("Issued pairing code {Code} for {Ip} (TTL {Ttl}s)",
            code, clientIp, _settings.Pairing.CodeTtlSeconds);
        CodeIssued?.Invoke(this, record);
        return record;
    }

    public PairingValidationResult Validate(string code, string clientIp)
    {
        // Check lockout first
        if (_lockoutUntil.TryGetValue(clientIp, out var until) && until > DateTimeOffset.UtcNow)
        {
            var remaining = (int)(until - DateTimeOffset.UtcNow).TotalSeconds;
            return PairingValidationResult.LockedOut(remaining);
        }

        CleanupExpired();

        if (!_codes.TryGetValue(code, out var record) || record.ExpiresAt < DateTimeOffset.UtcNow)
        {
            RegisterFailure(clientIp);
            return PairingValidationResult.Invalid();
        }

        // One-time use: consume it
        _codes.TryRemove(code, out _);
        _failedAttempts.TryRemove(clientIp, out _);
        return PairingValidationResult.Valid(record);
    }

    private void RegisterFailure(string clientIp)
    {
        var attempts = _failedAttempts.AddOrUpdate(clientIp, 1, (_, prev) => prev + 1);
        if (attempts >= _settings.Pairing.MaxAttempts)
        {
            _lockoutUntil[clientIp] = DateTimeOffset.UtcNow.AddSeconds(_settings.Pairing.LockoutSeconds);
            _failedAttempts.TryRemove(clientIp, out _);
            _logger.LogWarning("Pairing lockout for {Ip} for {Sec}s (max attempts reached)",
                clientIp, _settings.Pairing.LockoutSeconds);
        }
    }

    private void CleanupExpired()
    {
        foreach (var kvp in _codes)
        {
            if (kvp.Value.ExpiresAt < DateTimeOffset.UtcNow)
                _codes.TryRemove(kvp.Key, out _);
        }
        foreach (var kvp in _lockoutUntil)
        {
            if (kvp.Value < DateTimeOffset.UtcNow)
                _lockoutUntil.TryRemove(kvp.Key, out _);
        }
    }

    private static string GenerateSixDigitCode()
    {
        // Cryptographically secure — avoids RandomNumberGenerator predictability.
        var buf = new byte[4];
        RandomNumberGenerator.Fill(buf);
        var n = BitConverter.ToUInt32(buf, 0) % 1_000_000u;
        return n.ToString("D6");
    }
}

public sealed record PairingCode(
    string Code,
    DateTimeOffset IssuedAt,
    DateTimeOffset ExpiresAt,
    string ClientIp);

public abstract record PairingValidationResult
{
    public sealed record Ok(PairingCode Record) : PairingValidationResult;
    public sealed record InvalidCode : PairingValidationResult;
    public sealed record Locked(int RemainingSeconds) : PairingValidationResult;

    public static PairingValidationResult Valid(PairingCode r) => new Ok(r);
    public static PairingValidationResult Invalid() => new InvalidCode();
    public static PairingValidationResult LockedOut(int s) => new Locked(s);
}
