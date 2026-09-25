using Microsoft.Extensions.Logging.Abstractions;
using PcRemote.Core.Plugins;
using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Tests;

public class PluginManagerTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "pcremote-tests-" + Guid.NewGuid().ToString("N"));

    private sealed class Module(string domain, bool? enabledByDefault = null, bool canDisable = true) : ICommandModule, IPluginMetadata
    {
        public string Domain => domain;
        public IReadOnlyList<CommandDescriptor> Commands { get; } = new[] { new CommandDescriptor("x", "x") };
        public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct) =>
            Task.FromResult(CommandResponse.Ok(req.Id));
        public string DisplayName => domain;
        public string Description => "";
        public bool EnabledByDefault => enabledByDefault ?? true;
        public bool CanDisable => canDisable;
    }

    private (PluginManager manager, CommandRouter router) Build(params ICommandModule[] modules)
    {
        var state = new PluginState(Path.Combine(_dir, "plugins.json"));
        var manager = new PluginManager(state, new PluginRegistry(Path.Combine(_dir, "plugins")), NullLogger<PluginManager>.Instance);
        var router = new CommandRouter(modules, NullLogger<CommandRouter>.Instance, audit: null, plugins: manager);
        return (manager, router);
    }

    private static ClientSession Session() => new()
    {
        SessionId = "0123456789abcdef", DeviceId = "d", DeviceName = "phone", StartedAt = DateTimeOffset.UtcNow,
    };

    private static CommandRequest Req(string domain) => new("request", "r1", domain, "x", null, 0);

    [Fact]
    public void Defaults_come_from_the_module()
    {
        var (m, _) = Build(new Module("media"), new Module("terminal", enabledByDefault: false));
        Assert.True(m.IsEnabled("media"));
        Assert.False(m.IsEnabled("terminal"));
    }

    [Fact]
    public async Task A_disabled_plugin_answers_PLUGIN_DISABLED()
    {
        var (m, router) = Build(new Module("media"));
        Assert.True(m.SetEnabled("media", false));

        var res = await router.DispatchAsync(Req("media"), Session(), CancellationToken.None);

        Assert.False(res.Success);
        Assert.Equal(ErrorCodes.PluginDisabled, res.Error!.Code);
    }

    [Fact]
    public void Choices_survive_a_restart()
    {
        var (m, _) = Build(new Module("terminal", enabledByDefault: false));
        m.SetEnabled("terminal", true);

        var (again, _) = Build(new Module("terminal", enabledByDefault: false));
        Assert.True(again.IsEnabled("terminal"));
    }

    [Fact]
    public void Protocol_plugins_cannot_be_disabled()
    {
        var (m, _) = Build(new Module("ping"), new Module("core", canDisable: false));
        Assert.False(m.SetEnabled("ping", false));
        Assert.False(m.SetEnabled("core", false));
        Assert.True(m.IsEnabled("ping"));
        Assert.True(m.IsEnabled("core"));
    }

    [Fact]
    public void Unknown_ids_are_rejected()
    {
        var (m, _) = Build(new Module("media"));
        Assert.False(m.SetEnabled("nope", true));
        Assert.False(m.SetEnabled("ext:Nope", true));
        Assert.False(m.IsEnabled("nope"));
    }

    [Fact]
    public void Two_modules_with_one_domain_do_not_crash_the_router()
    {
        var (_, router) = Build(new Module("media"), new Module("MEDIA"));
        Assert.Single(router.Modules);
    }

    [Fact]
    public void A_corrupt_state_file_falls_back_to_defaults()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(Path.Combine(_dir, "plugins.json"), "{ not json");
        var (m, _) = Build(new Module("terminal", enabledByDefault: false));
        Assert.False(m.IsEnabled("terminal"));
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { }
    }
}
