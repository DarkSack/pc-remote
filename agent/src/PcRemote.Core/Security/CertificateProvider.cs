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
            var existing = Load(path, GetOrCreatePassphrase());
            _logger.LogInformation("Cert fingerprint SHA-256: {Fingerprint}", GetFingerprint(existing));
            return existing;
        }

        _logger.LogInformation("Generating new self-signed certificate at {Path}", path);
        var pass = GetOrCreatePassphrase();
        using var freshCert = CreateSelfSigned();
        File.WriteAllBytes(path, freshCert.Export(X509ContentType.Pfx, pass));
        // Reload from disk so what Kestrel uses is exactly what later runs will load.
        var reloaded = Load(path, pass);
        _logger.LogInformation("Cert fingerprint SHA-256: {Fingerprint}", GetFingerprint(reloaded));
        return reloaded;
    }

    /// <summary>
    /// UserKeySet, without MachineKeySet or PersistKeySet. The old flags wrote the
    /// private key into the machine key store (C:\ProgramData\...\MachineKeys),
    /// which a normal user may not be allowed to do, and every start imported the
    /// PFX again, leaving one more persisted key file behind each time. The PFX on
    /// disk is the source of truth; the key only needs to live while the agent runs.
    /// The certificate itself — and its fingerprint — does not change.
    /// </summary>
    private static X509Certificate2 Load(string path, string pass) =>
        X509CertificateLoader.LoadPkcs12FromFile(path, pass, X509KeyStorageFlags.UserKeySet);

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
