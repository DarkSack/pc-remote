using Makaretu.Dns;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Discovery;

/// <summary>
/// Publishes the agent as an mDNS service so mobile clients can auto-discover it.
/// Service type: _pcremote._tcp
/// TXT records: hostname, os, version, fp (SHA-256 of the TLS certificate).
/// </summary>
public sealed class MdnsPublisher : IHostedService, IDisposable
{
    private readonly AgentSettings _settings;
    private readonly ILogger<MdnsPublisher> _logger;
    private MulticastService? _mdns;
    private ServiceDiscovery? _discovery;

    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2 _certificate;

    public MdnsPublisher(
        AgentSettings settings,
        System.Security.Cryptography.X509Certificates.X509Certificate2 certificate,
        ILogger<MdnsPublisher> logger)
    {
        _settings    = settings;
        _certificate = certificate;
        _logger      = logger;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        try
        {
            _mdns      = new MulticastService();
            _discovery = new ServiceDiscovery(_mdns);

            var profile = new ServiceProfile(
                instanceName: Environment.MachineName,
                serviceName:  $"{_settings.Discovery.MdnsServiceType}",
                port:         (ushort)_settings.WebSocket.Port);

            profile.AddProperty("hostname", Environment.MachineName);
            profile.AddProperty("os", "Windows");
            profile.AddProperty("version", GetAgentVersion());
            // The certificate fingerprint identifies THIS agent across IP changes: a
            // paired phone matches it to update the address it saved when DHCP hands
            // the PC a new one. It is not a secret (any client sees it in the TLS
            // handshake) and says nothing that lets anyone authenticate.
            profile.AddProperty("fp", PcRemote.Core.Security.CertificateProvider.GetFingerprint(_certificate));

            _discovery.Advertise(profile);
            _mdns.Start();

            _logger.LogInformation(
                "mDNS advertising as {Service}.{Type} on port {Port}",
                profile.InstanceName,
                _settings.Discovery.MdnsServiceType,
                _settings.WebSocket.Port);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to start mDNS publisher");
        }

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _mdns?.Stop();
        _logger.LogInformation("mDNS publisher stopped");
        return Task.CompletedTask;
    }

    public void Dispose()
    {
        _discovery?.Dispose();
        _mdns?.Dispose();
    }

    private static string GetAgentVersion() =>
        typeof(MdnsPublisher).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";
}
