using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.Versioning;
using System.Text.RegularExpressions;
using PcRemote.Core.Discovery;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Network;

// ══════════════════════════════════════════════════════════════
// Network — what the "Red" screen of the app shows.
//
//   info → interfaces (IP, MAC, speed, gateway, DNS), the one the phone
//          talks to, and TCP connections (established / listening).
//   ping → ping from the PC to a host (the gateway by default): tells
//          "the PC has no internet" apart from "the phone cannot reach the PC".
//
// Throughput (download / upload) goes in systeminfo.stats, which already streams.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed partial class NetworkModule : ICommandModule
{
    public string Domain => "network";

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info", "Interfaces, puerta de enlace, DNS y conexiones"),
        new CommandDescriptor("ping", "Ping desde el PC (por defecto a la puerta de enlace)"),
    };

    private const int MaxConnectionsListed = 60;

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            return req.Action switch
            {
                "info" => CommandResponse.Ok(req.Id, Info()),
                "ping" => await PingAsync(req, ct),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }

    private static object Info()
    {
        var primary = LanAddress.GuessInterface();
        var interfaces = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
            .Select(n =>
            {
                IPInterfaceProperties? props = null;
                try { props = n.GetIPProperties(); } catch { /* adapter going away */ }
                var ipv4 = props?.UnicastAddresses.FirstOrDefault(a => a.Address.AddressFamily == AddressFamily.InterNetwork);
                var ipv6 = props?.UnicastAddresses
                    .Where(a => a.Address.AddressFamily == AddressFamily.InterNetworkV6 && !a.Address.IsIPv6LinkLocal)
                    .Select(a => a.Address.ToString()).FirstOrDefault();
                var mac = n.GetPhysicalAddress().GetAddressBytes();
                return new
                {
                    name = n.Name,
                    description = n.Description,
                    type = TypeLabel(n.NetworkInterfaceType),
                    up = n.OperationalStatus == OperationalStatus.Up,
                    ipv4 = ipv4?.Address.ToString(),
                    prefixLength = ipv4?.PrefixLength,
                    ipv6,
                    mac = mac.Length == 6 ? string.Join(":", mac.Select(b => b.ToString("X2"))) : null,
                    speedMbps = n.OperationalStatus == OperationalStatus.Up && n.Speed > 0 ? n.Speed / 1_000_000 : (long?)null,
                    gateway = props?.GatewayAddresses
                        .Select(g => g.Address).FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork)?.ToString(),
                    dns = props?.DnsAddresses.Where(a => a.AddressFamily == AddressFamily.InterNetwork)
                        .Select(a => a.ToString()).ToArray() ?? Array.Empty<string>(),
                    primary = ipv4 is not null && ipv4.Address.ToString() == primary.Address,
                };
            })
            // Up first, the phone's interface on top; virtual adapters that are down at the end.
            .OrderByDescending(n => n.primary).ThenByDescending(n => n.up).ThenBy(n => n.name)
            .ToArray();

        var global = IPGlobalProperties.GetIPGlobalProperties();
        TcpConnectionInformation[] tcp;
        try { tcp = global.GetActiveTcpConnections(); } catch { tcp = Array.Empty<TcpConnectionInformation>(); }
        int listening;
        try { listening = global.GetActiveTcpListeners().Length; } catch { listening = 0; }

        var established = tcp.Where(c => c.State == TcpState.Established).ToArray();
        var remote = established
            .Where(c => !IPAddress.IsLoopback(c.RemoteEndPoint.Address))
            .GroupBy(c => c.RemoteEndPoint.Address.ToString())
            .Select(g => new
            {
                address = g.Key,
                ports = g.Select(c => c.RemoteEndPoint.Port).Distinct().OrderBy(p => p).Take(6).ToArray(),
                count = g.Count(),
                local = IsPrivate(g.First().RemoteEndPoint.Address),
            })
            .OrderByDescending(r => r.count)
            .Take(MaxConnectionsListed)
            .ToArray();

        return new
        {
            hostname = Environment.MachineName,
            domain = global.DomainName,
            lanIp = primary.Address,
            mac = primary.MacAddress,
            interfaces,
            tcp = new
            {
                established = established.Length,
                listening,
                timeWait = tcp.Count(c => c.State == TcpState.TimeWait),
                total = tcp.Length,
            },
            remote,
        };
    }

    private static async Task<CommandResponse> PingAsync(CommandRequest req, CancellationToken ct)
    {
        string? host = null;
        if (req.Params is { ValueKind: System.Text.Json.JsonValueKind.Object } p && p.TryGetProperty("host", out var h))
            host = h.GetString()?.Trim();

        host ??= DefaultGateway() ?? "1.1.1.1";
        if (!HostPattern().IsMatch(host))
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, "host must be an IP address or a host name.");

        using var ping = new Ping();
        var times = new List<long>();
        var lost = 0;
        for (var i = 0; i < 3; i++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                var reply = await ping.SendPingAsync(host, TimeSpan.FromSeconds(2), cancellationToken: ct);
                if (reply.Status == IPStatus.Success) times.Add(reply.RoundtripTime);
                else lost++;
            }
            catch (PingException)
            {
                lost++;
            }
        }

        return CommandResponse.Ok(req.Id, new
        {
            host,
            sent = 3,
            lost,
            avgMs = times.Count > 0 ? Math.Round(times.Average(), 1) : (double?)null,
            minMs = times.Count > 0 ? times.Min() : (long?)null,
            maxMs = times.Count > 0 ? times.Max() : (long?)null,
        });
    }

    private static string? DefaultGateway() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => { try { return n.GetIPProperties().GatewayAddresses; } catch { return Enumerable.Empty<GatewayIPAddressInformation>(); } })
            .Select(g => g.Address)
            .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !a.Equals(IPAddress.Any))
            ?.ToString();

    internal static bool IsPrivate(IPAddress a)
    {
        if (a.AddressFamily == AddressFamily.InterNetworkV6) return a.IsIPv6LinkLocal || a.IsIPv6SiteLocal || a.IsIPv6UniqueLocal;
        var b = a.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
    }

    private static string TypeLabel(NetworkInterfaceType t) => t switch
    {
        NetworkInterfaceType.Wireless80211 => "wifi",
        NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetT
            or NetworkInterfaceType.FastEthernetFx or NetworkInterfaceType.Ethernet3Megabit => "ethernet",
        NetworkInterfaceType.Ppp => "vpn",
        _ => "other",
    };

    [GeneratedRegex(@"^[A-Za-z0-9.:\-]{1,253}$")]
    private static partial Regex HostPattern();
}
