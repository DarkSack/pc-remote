using PcRemote.Core.Protocol;
using PcRemote.Core.Router;

namespace PcRemote.Core.Plugins;

/// <summary>
/// <c>plugins.list</c>: what this agent can do, so the phone shows only the
/// sections that will work. Read-only on purpose — plugins are switched on and
/// off in the PC's panel, by someone sitting at the PC.
/// </summary>
public sealed class PluginsModule(PluginManager manager) : ICommandModule, IPluginMetadata
{
    public string Domain => "plugins";

    public string DisplayName => "Plugins";
    public string Description => "Lista de funciones disponibles en este PC.";
    public string Category => PluginCategories.System;
    public bool CanDisable => false;

    public IReadOnlyList<CommandDescriptor> Commands { get; } = new[]
    {
        new CommandDescriptor("list", "Plugins instalados y si están activos"),
    };

    public Task<CommandResponse> HandleAsync(CommandRequest req, ClientSession session, CancellationToken ct) =>
        Task.FromResult(req.Action switch
        {
            "list" => CommandResponse.Ok(req.Id, new { plugins = manager.Describe() }),
            _ => CommandResponse.Fail(req.Id, ErrorCodes.InvalidCommand, $"Unknown action '{req.Action}'"),
        });
}
