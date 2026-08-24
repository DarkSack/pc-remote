using NSec.Cryptography;

namespace PcRemote.Core.Auth;

/// <summary>
/// Thin wrapper around NSec.Cryptography for Ed25519 signature verification.
/// The private key lives on the mobile client — the agent only verifies.
/// </summary>
public static class Ed25519Signing
{
    private static readonly SignatureAlgorithm Alg = SignatureAlgorithm.Ed25519;

    public static bool Verify(byte[] publicKey, ReadOnlySpan<byte> data, ReadOnlySpan<byte> signature)
    {
        if (publicKey.Length != 32) return false;
        if (signature.Length != 64) return false;
        try
        {
            var pk = PublicKey.Import(Alg, publicKey, KeyBlobFormat.RawPublicKey);
            return Alg.Verify(pk, data, signature);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// Server-side keypair (optional — used when the agent needs to sign server pushes
    /// so the client can verify server identity beyond TLS pinning). For now unused.
    /// </summary>
    public static (byte[] publicKey, byte[] privateKey) GenerateKeypair()
    {
        var creationParams = new KeyCreationParameters
        {
            ExportPolicy = KeyExportPolicies.AllowPlaintextExport,
        };
        using var key = Key.Create(Alg, creationParams);
        return (
            key.Export(KeyBlobFormat.RawPublicKey),
            key.Export(KeyBlobFormat.RawPrivateKey));
    }
}
