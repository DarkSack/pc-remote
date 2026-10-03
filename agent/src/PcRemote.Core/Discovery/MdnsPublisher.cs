using System.Net;
using System.Net.NetworkInformation;
using Makaretu.Dns;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Config;

namespace PcRemote.Core.Discovery;

/// <summary>
/// Publishes the agent as an mDNS service so mobile clients can auto-discover it.
/// Service type: _pcremote._tcp
/// TXT records: hostname, os, version, fp (SHA-256 of the TLS certificate).
///
/// Two things this gets right that "new ServiceProfile(...)" alone does not:
///  - It announces only the LAN-facing IPv4 (<see cref="LanAddress"/>, the same one
///    the QR shows). Left to itself, the library lists every address of the PC —
///    WSL, Hyper-V, Docker, VPN — and the phone may well pick one it cannot reach.
///  - The address is not frozen at start-up. The library reads the addresses once,
///    when the profile is built; after a DHCP change the PC kept announcing the old
///    IP, so a paired phone "found" it exactly where it no longer was. When the LAN
///    address changes, the old announcement says goodbye and a fresh mDNS service
///    starts with the new one. (Swapping profiles on the same ServiceDiscovery
///    leaves the old A record in its catalog: it would answer with both.)
/// </summary>
public sealed class MdnsPublisher : IHostedService, IDisposable
{
    /// <summary>Windows raises several address-change events per switch; act once it settles.</summary>
    private static readonly TimeSpan Settle = TimeSpan.FromSeconds(3);

    private readonly AgentSettings _settings;
    private readonly ILogger<MdnsPublisher> _logger;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2 _certificate;
    private readonly object _gate = new();

    private MulticastService? _mdns;
    private ServiceDiscovery? _discovery;
    private ServiceProfile? _profile;
    private string? _advertisedAddress;
    private Timer? _settleTimer;
    private bool _stopped;

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
            lock (_gate) Publish();
            _settleTimer = new Timer(_ => OnNetworkSettled(), null, Timeout.Infinite, Timeout.Infinite);
            NetworkChange.NetworkAddressChanged += OnNetworkChanged;
            NetworkChange.NetworkAvailabilityChanged += OnNetworkChanged;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to start mDNS publisher");
        }

        return Task.CompletedTask;
    }

    private void OnNetworkChanged(object? sender, EventArgs e) =>
        _settleTimer?.Change(Settle, Timeout.InfiniteTimeSpan);

    private void OnNetworkSettled()
    {
        try
        {
            lock (_gate)
            {
                if (_stopped || LanAddress.Guess() == _advertisedAddress) return;
                var old = _advertisedAddress;
                Retire();
                Publish();
                _logger.LogInformation("Network changed: mDNS now advertises {Address} (was {Old})", _advertisedAddress, old);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not refresh the mDNS announcement");
        }
    }

    /// <summary>Starts a fresh mDNS service announcing the current LAN address. Caller holds <see cref="_gate"/>.</summary>
    private void Publish()
    {
        var address = LanAddress.Guess();
        var profile = new ServiceProfile(
            instanceName: Environment.MachineName,
            serviceName:  _settings.Discovery.MdnsServiceType,
            port:         (ushort)_settings.WebSocket.Port,
            // No usable LAN address yet (cable out, Wi-Fi off): let the library list what there is.
            addresses:    address == "127.0.0.1" ? null : new[] { IPAddress.Parse(address) });

        profile.AddProperty("hostname", Environment.MachineName);
        profile.AddProperty("os", "Windows");
        profile.AddProperty("version", GetAgentVersion());
        // The certificate fingerprint identifies THIS agent across IP changes: a
        // paired phone matches it to update the address it saved when DHCP hands
        // the PC a new one. It is not a secret (any client sees it in the TLS
        // handshake) and says nothing that lets anyone authenticate.
        profile.AddProperty("fp", PcRemote.Core.Security.CertificateProvider.GetFingerprint(_certificate));

        var mdns = new MulticastService();
        var discovery = new ServiceDiscovery(mdns);
        discovery.Advertise(profile);
        mdns.Start();
        // Unsolicited answer, so phones that are already looking learn the address now.
        try { discovery.Announce(profile); } catch (Exception ex) { _logger.LogDebug(ex, "mDNS announce failed"); }

        _mdns = mdns;
        _discovery = discovery;
        _profile = profile;
        if (_advertisedAddress is null)
            _logger.LogInformation("mDNS advertising {Service}.{Type} at {Address}:{Port}",
                profile.InstanceName, _settings.Discovery.MdnsServiceType, address, _settings.WebSocket.Port);
        _advertisedAddress = address;
    }

    /// <summary>Goodbye (TTL 0, so phones drop the address from their caches) and shut down. Caller holds <see cref="_gate"/>.</summary>
    private void Retire()
    {
        if (_profile is not null && _discovery is not null)
        {
            try { _discovery.Unadvertise(_profile); } catch (Exception ex) { _logger.LogDebug(ex, "mDNS goodbye failed"); }
        }
        try { _discovery?.Dispose(); } catch { /* best effort */ }
        try { _mdns?.Stop(); _mdns?.Dispose(); } catch { /* best effort */ }
        _discovery = null;
        _mdns = null;
        _profile = null;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        NetworkChange.NetworkAvailabilityChanged -= OnNetworkChanged;
        _settleTimer?.Dispose();
        lock (_gate)
        {
            _stopped = true;
            Retire();
        }
        _logger.LogInformation("mDNS publisher stopped");
        return Task.CompletedTask;
    }

    public void Dispose()
    {
        _settleTimer?.Dispose();
        lock (_gate) Retire();
    }

    private static string GetAgentVersion() =>
        typeof(MdnsPublisher).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";
}
