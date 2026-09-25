using System.Text.Json.Serialization;

namespace PcRemote.Core.Protocol;

// ══════════════════════════════════════════════════════════════════
// WebSocket message envelope. See docs/PROTOCOL.md for full spec.
// ══════════════════════════════════════════════════════════════════

public static class MessageKinds
{
    public const string Request        = "request";
    public const string Response       = "response";
    public const string Subscribe      = "subscribe";
    public const string Stream         = "stream";
    public const string Unsubscribe    = "unsubscribe";
    public const string Event          = "event";
    public const string Ping           = "ping";
    public const string Pong           = "pong";
    // Bootstrap
    public const string PairInit       = "pair_init";
    public const string PairConfirm    = "pair_confirm";
    public const string PairResult     = "pair_result";
    public const string AuthChallenge  = "auth_challenge";
    public const string Auth           = "auth";
    public const string AuthResult     = "auth_result";
}

/// <summary>Discriminator used to peek the "kind" field before full deserialization.</summary>
public sealed record MessageHeader(
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("id")]   string? Id,
    [property: JsonPropertyName("ts")]   long?   Ts);

public sealed record CommandRequest(
    [property: JsonPropertyName("kind")]   string Kind,
    [property: JsonPropertyName("id")]     string Id,
    [property: JsonPropertyName("domain")] string Domain,
    [property: JsonPropertyName("action")] string Action,
    [property: JsonPropertyName("params")] System.Text.Json.JsonElement? Params,
    [property: JsonPropertyName("ts")]     long Ts);

public sealed record CommandResponse(
    [property: JsonPropertyName("kind")]    string    Kind,
    [property: JsonPropertyName("id")]      string    Id,
    [property: JsonPropertyName("success")] bool      Success,
    [property: JsonPropertyName("data")]    object?   Data,
    [property: JsonPropertyName("error")]   ErrorInfo? Error,
    [property: JsonPropertyName("ts")]      long      Ts)
{
    public static CommandResponse Ok(string id, object? data = null) =>
        new(MessageKinds.Response, id, true, data, null, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    public static CommandResponse Fail(string id, string code, string message, bool recoverable = false) =>
        new(MessageKinds.Response, id, false, null, new ErrorInfo(code, message, recoverable),
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    /// <summary>
    /// Error response for an exception thrown by a handler. A missing or mistyped
    /// param surfaces as an exception from JsonElement (GetProperty → KeyNotFound,
    /// GetInt32 on a string → InvalidOperation, out of range → Format); those are
    /// the client's fault and must say INVALID_PARAMS, not INTERNAL_ERROR.
    /// </summary>
    public static CommandResponse FromException(string id, Exception ex) =>
        IsParamError(ex)
            ? Fail(id, ErrorCodes.InvalidParams, $"Missing or invalid params: {ex.Message}")
            : Fail(id, ErrorCodes.InternalError, ex.Message);

    /// <remarks>
    /// Decided by where the exception was thrown, not by <c>Exception.Source</c>: on
    /// .NET 10 a wrong type or an out-of-range number reports its Source as
    /// "System.Text.Json.Rethrowable", so comparing Source to "System.Text.Json" sent
    /// every mistyped param back as INTERNAL_ERROR (caught by the unit tests).
    /// </remarks>
    private static bool IsParamError(Exception ex) =>
        ex is KeyNotFoundException ||
        (ex is InvalidOperationException or FormatException &&
         new System.Diagnostics.StackTrace(ex).GetFrames()
             .Any(f => f.GetMethod()?.DeclaringType == typeof(System.Text.Json.JsonElement)));
}

public sealed record ErrorInfo(
    [property: JsonPropertyName("code")]        string Code,
    [property: JsonPropertyName("message")]     string Message,
    [property: JsonPropertyName("recoverable")] bool Recoverable = false);

public static class ErrorCodes
{
    public const string PermissionDenied  = "PERMISSION_DENIED";
    public const string NotAuthenticated  = "NOT_AUTHENTICATED";
    public const string InvalidCommand    = "INVALID_COMMAND";
    public const string InvalidParams     = "INVALID_PARAMS";
    public const string NotFound          = "NOT_FOUND";
    public const string Timeout           = "TIMEOUT";
    public const string InternalError     = "INTERNAL_ERROR";
    public const string RateLimited       = "RATE_LIMITED";
    public const string PairingFailed     = "PAIRING_FAILED";
    /// <summary>Optional feature or plugin switched off in the panel.</summary>
    public const string FeatureDisabled   = "FEATURE_DISABLED";
}

// ── Pairing / Auth messages ─────────────────────────────────────

public sealed record PairInitMessage(
    [property: JsonPropertyName("kind")] string Kind);

public sealed record PairConfirmMessage(
    [property: JsonPropertyName("kind")]       string Kind,
    [property: JsonPropertyName("code")]       string Code,
    [property: JsonPropertyName("deviceName")] string DeviceName,
    [property: JsonPropertyName("publicKey")]  string PublicKey); // base64 Ed25519 (32 bytes)

public sealed record PairResultMessage(
    [property: JsonPropertyName("kind")]             string Kind,
    [property: JsonPropertyName("success")]          bool Success,
    [property: JsonPropertyName("deviceId")]         string? DeviceId,
    [property: JsonPropertyName("agentPublicKey")]   string? AgentPublicKey,
    [property: JsonPropertyName("certFingerprint")]  string? CertFingerprint,
    [property: JsonPropertyName("error")]            ErrorInfo? Error);

public sealed record AuthChallengeMessage(
    [property: JsonPropertyName("kind")]  string Kind,
    [property: JsonPropertyName("nonce")] string Nonce); // hex 32 bytes

public sealed record AuthMessage(
    [property: JsonPropertyName("kind")]       string Kind,
    [property: JsonPropertyName("deviceId")]   string DeviceId,
    [property: JsonPropertyName("signature")]  string Signature); // base64 Ed25519(nonce)

public sealed record AuthResultMessage(
    [property: JsonPropertyName("kind")]      string Kind,
    [property: JsonPropertyName("success")]   bool Success,
    [property: JsonPropertyName("sessionId")] string? SessionId,
    [property: JsonPropertyName("error")]     ErrorInfo? Error);
