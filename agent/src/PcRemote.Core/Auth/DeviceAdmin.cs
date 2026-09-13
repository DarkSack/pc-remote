using System.Net.WebSockets;
using Microsoft.Extensions.Logging;
using PcRemote.Core.Server;

namespace PcRemote.Core.Auth;

/// <summary>
/// Revoke / delete a paired device AND cut it off right now.
///
/// Marking the row as revoked is not enough on its own: the device's socket is
/// already authenticated and would keep sending commands until it disconnected.
/// Both the tray and the web panel go through here so neither can forget a step.
/// </summary>
public sealed class DeviceAdmin
{
    /// <summary>Close code sent to a revoked device (see docs/PAIRING.md).</summary>
    public const WebSocketCloseStatus RevokedCloseStatus = (WebSocketCloseStatus)4001;

    private readonly DeviceRepository  _devices;
    private readonly SessionManager    _sessions;
    private readonly ConnectionManager _connections;
    private readonly ILogger<DeviceAdmin> _logger;

    public DeviceAdmin(
        DeviceRepository devices,
        SessionManager sessions,
        ConnectionManager connections,
        ILogger<DeviceAdmin> logger)
    {
        _devices     = devices;
        _sessions    = sessions;
        _connections = connections;
        _logger      = logger;
    }

    /// <returns>How many live connections were closed.</returns>
    public Task<int> RevokeAsync(string deviceId)
    {
        _devices.Revoke(deviceId);
        return CutOffAsync(deviceId, "revoked");
    }

    /// <returns>How many live connections were closed.</returns>
    public Task<int> DeleteAsync(string deviceId)
    {
        _devices.Delete(deviceId);
        return CutOffAsync(deviceId, "deleted");
    }

    private async Task<int> CutOffAsync(string deviceId, string what)
    {
        // Sessions first: any command already queued re-checks its session before
        // running, so nothing slips through while the close frame is on its way.
        _sessions.EndAllForDevice(deviceId);
        var closed = await _connections
            .CloseDeviceAsync(deviceId, RevokedCloseStatus, "Device revoked")
            .ConfigureAwait(false);
        _logger.LogInformation("Device {Id} {What}; closed {Count} live connection(s)", deviceId, what, closed);
        return closed;
    }
}
