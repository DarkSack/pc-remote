using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PcRemote.Core.Discovery;

/// <summary>The adapter facing the LAN: its IPv4, MAC (for Wake-on-LAN) and subnet broadcast.</summary>
public sealed record LanInterface(string Address, string? MacAddress, string? Broadcast);

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

    public static string Guess() => GuessInterface().Address;

    public static LanInterface GuessInterface()
    {
        try
        {
            var best = NetworkInterface.GetAllNetworkInterfaces()
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
                        .Select(a => (ni, a, hasGateway, looksVirtual, isPrivate: IsPrivate(a.Address)));
                })
                .OrderByDescending(c => c.hasGateway)
                .ThenBy(c => c.looksVirtual)
                .ThenByDescending(c => c.isPrivate)
                .FirstOrDefault();

            if (best.ni is not null)
                return new LanInterface(best.a.Address.ToString(), FormatMac(best.ni), Broadcast(best.a));
        }
        catch (NetworkInformationException)
        {
            // Fall through to loopback.
        }
        return new LanInterface("127.0.0.1", null, null);
    }

    private static string? FormatMac(NetworkInterface ni)
    {
        var bytes = ni.GetPhysicalAddress().GetAddressBytes();
        return bytes.Length == 6 ? string.Join(":", bytes.Select(b => b.ToString("X2"))) : null;
    }

    private static string? Broadcast(UnicastIPAddressInformation a)
    {
        if (a.IPv4Mask is null) return null;
        var ip = a.Address.GetAddressBytes();
        var mask = a.IPv4Mask.GetAddressBytes();
        if (mask.Length != 4) return null;
        var broadcast = new byte[4];
        for (int i = 0; i < 4; i++) broadcast[i] = (byte)(ip[i] | ~mask[i]);
        return new IPAddress(broadcast).ToString();
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
