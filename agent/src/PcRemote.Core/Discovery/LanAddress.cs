using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PcRemote.Core.Discovery;

/// <summary>
/// The IPv4 address a phone on the same Wi-Fi should use to reach this PC.
///
/// "First private address" is not good enough: WSL, Hyper-V, Docker, VirtualBox
/// and VPN adapters all carry private addresses (often 172.x) and are usually
/// listed first. A QR or tray label showing one of those pairs the phone with an
/// address it can never reach. The adapter that really faces the LAN is the one
/// with a default gateway, so that wins; virtual adapters are a last resort.
/// </summary>
public static class LanAddress
{
    private static readonly string[] VirtualHints =
    {
        "virtual", "vethernet", "hyper-v", "wsl", "docker", "vmware", "virtualbox",
        "vpn", "tap-", "tun", "tailscale", "zerotier", "wireguard", "loopback",
    };

    public static string Guess()
    {
        try
        {
            var candidates = NetworkInterface.GetAllNetworkInterfaces()
                .Where(ni => ni.OperationalStatus == OperationalStatus.Up &&
                             ni.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
                .SelectMany(ni =>
                {
                    var props = ni.GetIPProperties();
                    var hasGateway = props.GatewayAddresses.Any(g =>
                        g.Address.AddressFamily == AddressFamily.InterNetwork &&
                        !g.Address.Equals(IPAddress.Any));
                    var looksVirtual = VirtualHints.Any(h =>
                        ni.Name.Contains(h, StringComparison.OrdinalIgnoreCase) ||
                        ni.Description.Contains(h, StringComparison.OrdinalIgnoreCase));
                    return props.UnicastAddresses
                        .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork &&
                                    !IPAddress.IsLoopback(a.Address) &&
                                    !IsLinkLocal(a.Address))
                        .Select(a => (a.Address, hasGateway, looksVirtual, isPrivate: IsPrivate(a.Address)));
                })
                .OrderByDescending(c => c.hasGateway)
                .ThenBy(c => c.looksVirtual)
                .ThenByDescending(c => c.isPrivate)
                .ToList();

            if (candidates.Count > 0) return candidates[0].Address.ToString();
        }
        catch (NetworkInformationException)
        {
            // Fall through to loopback.
        }
        return "127.0.0.1";
    }

    private static bool IsPrivate(IPAddress ip)
    {
        var b = ip.GetAddressBytes();
        return b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] >= 16 && b[1] <= 31);
    }

    private static bool IsLinkLocal(IPAddress ip)
    {
        var b = ip.GetAddressBytes();
        return b[0] == 169 && b[1] == 254;
    }
}
