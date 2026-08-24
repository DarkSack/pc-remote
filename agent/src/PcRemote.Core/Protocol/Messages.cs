using System.Text.Json.Serialization;

namespace PcRemote.Core.Protocol;

// ══════════════════════════════════════════════════════════════════
// WebSocket message envelope. See docs/PROTOCOL.md for full spec.
// ══════════════════════════════════════════════════════════════════

public enum MessageKind
{
    Request,
    Response,
    Subscribe,
    Stream,
    Unsubscribe,
    Event,
    Ping,
    Pong,
    // Auth-bootstrap
    PairInit,
    PairConfirm,
    Auth,
}

public abstract record Message(
    [property: JsonPropertyName("kind")] MessageKind Kind,
    [property: JsonPropertyName("ts")]   long Ts);

public sealed record CommandRequest(
    [property: JsonPropertyName("id")]      string Id,
    [property: JsonPropertyName("domain")]  string Domain,
    [property: JsonPropertyName("action")]  string Action,
    [property: JsonPropertyName("params")]  System.Text.Json.JsonElement? Params,
    long Ts) : Message(MessageKind.Request, Ts);

public sealed record CommandResponse(
    [property: JsonPropertyName("id")]        string Id,
    [property: JsonPropertyName("success")]   bool Success,
    [property: JsonPropertyName("data")]      object? Data,
    [property: JsonPropertyName("error")]     ErrorInfo? Error,
    long Ts) : Message(MessageKind.Response, Ts);

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
}
