using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Security;

/// <summary>
/// Ensures a self-signed TLS certificate exists on disk. Generated on first run,
/// reused thereafter. Fingerprint SHA-256 is stable across restarts, so mobile
/// clients can pin it after pairing.
/// </summary>
public sealed class CertificateProvider
{
    private readonly AgentSettings _settings;
    private readonly ILogger<CertificateProvider> _logger;

    public CertificateProvider(AgentSettings settings, ILogger<CertificateProvider> logger)
    {
        _settings = settings;
        _logger   = logger;
    }

    [SupportedOSPlatform("windows")]
    public X509Certificate2 LoadOrCreate()
    {
        var path = _settings.Storage.ResolvedCertificatePath;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);

        if (File.Exists(path))
        {
            _logger.LogInformation("Loading existing certificate from {Path}", path);
            var existing = new X509Certificate2(path, GetOrCreatePassphrase(), X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable);
            _logger.LogInformation("Cert fingerprint SHA-256: {Fingerprint}", GetFingerprint(existing));
            return existing;
        }

        _logger.LogInformation("Generating new self-signed certificate at {Path}", path);
        var pass = GetOrCreatePassphrase();
        var freshCert = CreateSelfSigned();
        var bytes = freshCert.Export(X509ContentType.Pfx, pass);
        File.WriteAllBytes(path, bytes);
        // Reload from disk so the key material is properly persisted and Kestrel sees it.
        var reloaded = new X509Certificate2(path, pass, X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable);
        _logger.LogInformation("Cert fingerprint SHA-256: {Fingerprint}", GetFingerprint(reloaded));
        return reloaded;
    }

    private static X509Certificate2 CreateSelfSigned()
    {
        var hostname = Environment.MachineName;
        // No `using` — the cert holds a reference to the RSA key; disposing here breaks it.
        var rsa = RSA.Create(2048);

        var request = new CertificateRequest(
            $"CN=PcRemote-{hostname}",
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
                true));
        request.CertificateExtensions.Add(
            new X509EnhancedKeyUsageExtension(
                new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") /* server auth */ },
                true));

        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName(hostname);
        san.AddDnsName("localhost");
        san.AddIpAddress(System.Net.IPAddress.Loopback);
        request.CertificateExtensions.Add(san.Build());

        var notBefore = DateTimeOffset.UtcNow.AddMinutes(-5);
        var notAfter  = notBefore.AddYears(10);
        return request.CreateSelfSigned(notBefore, notAfter);
    }

    /// <summary>Passphrase for the .pfx. Stored via DPAPI (per-user, per-machine).</summary>
    [SupportedOSPlatform("windows")]
    private string GetOrCreatePassphrase()
    {
        var passphrasePath = Path.Combine(
            Path.GetDirectoryName(_settings.Storage.ResolvedCertificatePath)!,
            "cert.pass");

        if (File.Exists(passphrasePath))
        {
            var encrypted = File.ReadAllBytes(passphrasePath);
            var decrypted = System.Security.Cryptography.ProtectedData.Unprotect(
                encrypted, null, System.Security.Cryptography.DataProtectionScope.CurrentUser);
            return System.Text.Encoding.UTF8.GetString(decrypted);
        }

        var random = new byte[32];
        RandomNumberGenerator.Fill(random);
        var pass = Convert.ToBase64String(random);
        var enc  = System.Security.Cryptography.ProtectedData.Protect(
            System.Text.Encoding.UTF8.GetBytes(pass),
            null,
            System.Security.Cryptography.DataProtectionScope.CurrentUser);
        File.WriteAllBytes(passphrasePath, enc);
        return pass;
    }

    public static string GetFingerprint(X509Certificate2 cert)
    {
        var hash = SHA256.HashData(cert.RawData);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }
}
