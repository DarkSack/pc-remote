namespace PcRemote.Core.Config;

/// <summary>Root config bound from <c>appsettings.json</c> section "Agent".</summary>
public sealed class AgentSettings
{
    public WebSocketSettings WebSocket { get; init; } = new();
    public DiscoverySettings Discovery { get; init; } = new();
    public StorageSettings   Storage   { get; init; } = new();
    public PairingSettings   Pairing   { get; init; } = new();
    public SessionSettings   Session   { get; init; } = new();
    public PanelSettings     Panel     { get; init; } = new();
}

public sealed class PanelSettings
{
    public bool Enabled { get; init; } = true;
    // Same as appsettings.json. It used to default to 47821, which is also what
    // the (never implemented) UDP fallback claimed.
    public int  Port    { get; init; } = 47810;
}

public sealed class WebSocketSettings
{
    public int    Port        { get; init; } = 47820;
    public string BindAddress { get; init; } = "0.0.0.0";
}

public sealed class DiscoverySettings
{
    public string MdnsServiceType { get; init; } = "_pcremote._tcp";
}

public sealed class StorageSettings
{
    public string DatabasePath    { get; init; } = @"%LOCALAPPDATA%\PcRemote\agent.db";
    public string CertificatePath { get; init; } = @"%LOCALAPPDATA%\PcRemote\cert.pfx";

    public string ResolvedDatabasePath    => Environment.ExpandEnvironmentVariables(DatabasePath);
    public string ResolvedCertificatePath => Environment.ExpandEnvironmentVariables(CertificatePath);
}

public sealed class PairingSettings
{
    public int CodeTtlSeconds  { get; init; } = 120;
    public int MaxAttempts     { get; init; } = 3;
    public int LockoutSeconds  { get; init; } = 300;
}

public sealed class SessionSettings
{
    public int PingIntervalSeconds { get; init; } = 15;
}
