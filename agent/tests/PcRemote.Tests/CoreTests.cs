using System.Text.Json;
using NSec.Cryptography;
using PcRemote.Core.Auth;
using PcRemote.Core.Protocol;

namespace PcRemote.Tests;

public class CommandResponseTests
{
    [Fact]
    public void Missing_param_is_invalid_params()
    {
        var p = JsonDocument.Parse("{}").RootElement;
        var ex = Record.Exception(() => p.GetProperty("id"));
        Assert.Equal(ErrorCodes.InvalidParams, CommandResponse.FromException("r1", ex!).Error!.Code);
    }

    [Fact]
    public void Wrong_param_type_is_invalid_params()
    {
        var p = JsonDocument.Parse("""{"pid":"abc"}""").RootElement;
        var ex = Record.Exception(() => p.GetProperty("pid").GetInt32());
        Assert.Equal(ErrorCodes.InvalidParams, CommandResponse.FromException("r1", ex!).Error!.Code);
    }

    [Fact]
    public void Out_of_range_number_is_invalid_params()
    {
        var p = JsonDocument.Parse("""{"volume":99999999999}""").RootElement;
        var ex = Record.Exception(() => p.GetProperty("volume").GetInt32());
        Assert.Equal(ErrorCodes.InvalidParams, CommandResponse.FromException("r1", ex!).Error!.Code);
    }

    [Fact]
    public void Missing_params_object_is_invalid_params()
    {
        JsonElement p = default; // what `req.Params ?? default` gives when no params were sent
        var ex = Record.Exception(() => p.GetProperty("id"));
        Assert.Equal(ErrorCodes.InvalidParams, CommandResponse.FromException("r1", ex!).Error!.Code);
    }

    [Fact]
    public void Agent_failures_stay_internal_errors()
    {
        var res = CommandResponse.FromException("r1", new InvalidOperationException("boom"));
        Assert.Equal(ErrorCodes.InternalError, res.Error!.Code);
        Assert.False(res.Success);
        Assert.Equal("r1", res.Id);
    }
}

public class Ed25519SigningTests
{
    [Fact]
    public void Verifies_a_real_signature_and_rejects_a_tampered_one()
    {
        var alg = SignatureAlgorithm.Ed25519;
        using var key = Key.Create(alg);
        var nonce = new byte[32];
        Random.Shared.NextBytes(nonce);
        var signature = alg.Sign(key, nonce);
        var publicKey = key.Export(KeyBlobFormat.RawPublicKey);

        Assert.True(Ed25519Signing.Verify(publicKey, nonce, signature));

        nonce[0] ^= 1;
        Assert.False(Ed25519Signing.Verify(publicKey, nonce, signature));
    }

    [Fact]
    public void Malformed_keys_and_signatures_are_rejected_without_throwing()
    {
        Assert.False(Ed25519Signing.Verify(new byte[31], new byte[32], new byte[64]));
        Assert.False(Ed25519Signing.Verify(new byte[32], new byte[32], new byte[63]));
    }
}

public class SessionManagerTests
{
    [Fact]
    public void Revoking_a_device_ends_only_its_sessions()
    {
        var sessions = new SessionManager();
        var a1 = sessions.Create("device-a", "A");
        var a2 = sessions.Create("device-a", "A");
        var b = sessions.Create("device-b", "B");

        var ended = sessions.EndAllForDevice("device-a");

        Assert.Equal(2, ended.Count);
        Assert.Null(sessions.Get(a1.SessionId));
        Assert.Null(sessions.Get(a2.SessionId));
        Assert.NotNull(sessions.Get(b.SessionId));
    }
}
