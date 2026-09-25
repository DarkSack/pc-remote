using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.Versioning;
using System.Text.Json;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Modules.Network;

// ══════════════════════════════════════════════════════════════
// Network — the PC's side of the network, for the phone's Network screen.
//
//   info        → interfaces (type, speed, IPs, gateway, DNS), TCP counts
//   connections { limit? } → active TCP connections (local, remote, state)
//   ping        { host?, count? } → ping from the PC (default: its gateway)
//
// Throughput lives in systeminfo.stats (net.rxBps / txBps), sampled with
// everything else.
// ══════════════════════════════════════════════════════════════
[SupportedOSPlatform("windows")]
public sealed class NetworkModule : ICommandModule, IPluginMetadata
{
    public string Domain => "network";

    public string DisplayName => "Red";
    public string Description => "Interfaces, direcciones IP, conexiones activas y ping desde el PC.";
    public string Category => PluginCategories.System;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("info",        "Interfaces de red y resumen de conexiones"),
        new CommandDescriptor("connections", "Conexiones TCP activas"),
        new CommandDescriptor("ping",        "Ping desde el PC"),
    };

    public async Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct)
    {
        try
        {
            var p = req.Params is { ValueKind: JsonValueKind.Object } o ? o : default;
            return req.Action switch
            {
                "info" => Info(req.Id),
                "connections" => Connections(req.Id, p),
                "ping" => await PingAsync(req.Id, p, ct),
                _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
            };
        }
        catch (ArgumentException ex)
        {
            return CommandResponse.Fail(req.Id, ErrorCodes.InvalidParams, ex.Message);
        }
        catch (Exception ex)
        {
            return CommandResponse.FromException(req.Id, ex);
        }
    }

    private static CommandResponse Info(string id)
    {
        var lan = PcRemote.Core.Discovery.LanAddress.GuessInterface();
        var interfaces = new List<(bool primary, bool up, object data)>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback) continue;
            IPInterfaceProperties props;
            try { props = ni.GetIPProperties(); } catch (NetworkInformationException) { continue; }
            var v4 = props.UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork).Select(a => a.Address.ToString()).ToArray();
            var v6 = props.UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetworkV6 && !a.Address.IsIPv6LinkLocal).Select(a => a.Address.ToString()).ToArray();
            var up = ni.OperationalStatus == OperationalStatus.Up;
            var primary = v4.Contains(lan.Address);
            interfaces.Add((primary, up, new
            {
                name = ni.Name,
                description = ni.Description,
                type = TypeName(ni.NetworkInterfaceType),
                up,
                speedMbps = ni.Speed > 0 ? ni.Speed / 1_000_000 : (long?)null,
                mac = FormatMac(ni.GetPhysicalAddress()),
                ipv4 = v4,
                ipv6 = v6,
                gateways = props.GatewayAddresses.Select(g => g.Address.ToString()).Where(a => a != "0.0.0.0").ToArray(),
                dns = props.DnsAddresses.Select(d => d.ToString()).ToArray(),
                primary,
            }));
        }

        var ip = IPGlobalProperties.GetIPGlobalProperties();
        int established = 0, total = 0, listeners = 0;
        try
        {
            var conns = ip.GetActiveTcpConnections();
            total = conns.Length;
            established = conns.Count(c => c.State == TcpState.Established);
            listeners = ip.GetActiveTcpListeners().Length;
        }
        catch (NetworkInformationException) { }

        return CommandResponse.Ok(id, new
        {
            hostname = Environment.MachineName,
            lanIp = lan.Address,
            interfaces = interfaces.OrderByDescending(i => i.primary).ThenByDescending(i => i.up).Select(i => i.data).ToList(),
            tcp = new { total, established, listeners },
        });
    }

    private static CommandResponse Connections(string id, JsonElement p)
    {
        var limit = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("limit", out var l) ? Math.Clamp(l.GetInt32(), 1, 500) : 100;
        var conns = IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpConnections()
            .Where(c => !IPAddress.IsLoopback(c.RemoteEndPoint.Address))
            .OrderBy(c => c.State != TcpState.Established)
            .ThenBy(c => c.RemoteEndPoint.Address.ToString())
            .Take(limit)
            .Select(c => new
            {
                local = c.LocalEndPoint.ToString(),
                remote = c.RemoteEndPoint.ToString(),
                state = c.State.ToString(),
            })
            .ToList();
        return CommandResponse.Ok(id, new { count = conns.Count, connections = conns });
    }

    private static async Task<CommandResponse> PingAsync(string id, JsonElement p, CancellationToken ct)
    {
        string? host = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("host", out var h) ? h.GetString()?.Trim() : null;
        var count = p.ValueKind == JsonValueKind.Object && p.TryGetProperty("count", out var c) ? Math.Clamp(c.GetInt32(), 1, 10) : 4;
        if (string.IsNullOrEmpty(host)) host = DefaultGateway() ?? "1.1.1.1";
        if (host.Length > 253 || host.Any(ch => !(char.IsLetterOrDigit(ch) || ch is '.' or '-' or ':')))
            throw new ArgumentException("host must be a hostname or an IP address");

        using var ping = new Ping();
        var results = new List<long?>();
        for (var i = 0; i < count; i++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                var reply = await ping.SendPingAsync(host, TimeSpan.FromSeconds(2), cancellationToken: ct);
                results.Add(reply.Status == IPStatus.Success ? reply.RoundtripTime : null);
            }
            catch (PingException) { results.Add(null); }
            if (i < count - 1) await Task.Delay(250, ct);
        }
        var ok = results.Where(r => r.HasValue).Select(r => r!.Value).ToList();
        return CommandResponse.Ok(id, new
        {
            host,
            results,
            avgMs = ok.Count > 0 ? Math.Round(ok.Average(), 1) : (double?)null,
            lossPct = Math.Round((count - ok.Count) * 100.0 / count, 0),
        });
    }

    private static string? DefaultGateway() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up)
            .SelectMany(n => { try { return n.GetIPProperties().GatewayAddresses; } catch { return Enumerable.Empty<GatewayIPAddressInformation>(); } })
            .Select(g => g.Address)
            .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !a.Equals(IPAddress.Any))
            ?.ToString();

    private static string? FormatMac(PhysicalAddress mac)
    {
        var b = mac.GetAddressBytes();
        return b.Length == 6 ? string.Join(":", b.Select(x => x.ToString("X2"))) : null;
    }

    private static string TypeName(NetworkInterfaceType t) => t switch
    {
        NetworkInterfaceType.Wireless80211 => "wifi",
        NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetT
            or NetworkInterfaceType.FastEthernetFx or NetworkInterfaceType.Ethernet3Megabit => "ethernet",
        NetworkInterfaceType.Tunnel => "tunnel",
        NetworkInterfaceType.Ppp => "ppp",
        _ => "other",
    };
}
