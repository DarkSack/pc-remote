using Microsoft.Extensions.Logging.Abstractions;
using PcRemote.Core.Auth;
using PcRemote.Core.Config;

namespace PcRemote.Tests;

public class PairingServiceTests
{
    private static PairingService Create(int maxAttempts = 3) => new(
        new AgentSettings { Pairing = new PairingSettings { CodeTtlSeconds = 120, MaxAttempts = maxAttempts, LockoutSeconds = 300 } },
        NullLogger<PairingService>.Instance);

    private static string CodeFor(PairingService s, string ip) =>
        Assert.IsType<PairingRequestResult.Issued>(s.RequestCode(ip)).Record.Code;

    [Fact]
    public void Network_code_only_works_from_the_ip_that_asked_for_it()
    {
        var s = Create();
        var code = CodeFor(s, "192.168.1.20");

        Assert.IsType<PairingValidationResult.InvalidCode>(s.Validate(code, "192.168.1.99"));
        Assert.IsType<PairingValidationResult.Ok>(s.Validate(code, "192.168.1.20"));
    }

    [Fact]
    public void A_code_is_single_use()
    {
        var s = Create();
        var code = CodeFor(s, "10.0.0.5");

        Assert.IsType<PairingValidationResult.Ok>(s.Validate(code, "10.0.0.5"));
        Assert.IsType<PairingValidationResult.InvalidCode>(s.Validate(code, "10.0.0.5"));
    }

    [Fact]
    public void Asking_again_from_the_same_ip_keeps_the_code_on_screen()
    {
        var s = Create();
        Assert.Equal(CodeFor(s, "10.0.0.5"), CodeFor(s, "10.0.0.5"));
    }

    [Fact]
    public void Panel_code_works_from_any_ip_and_replaces_the_previous_one()
    {
        var s = Create();
        var first = s.IssuePanelCode().Code;
        var second = s.IssuePanelCode().Code;

        Assert.IsType<PairingValidationResult.InvalidCode>(s.Validate(first == second ? "x" : first, "192.168.1.30"));
        Assert.IsType<PairingValidationResult.Ok>(s.Validate(second, "192.168.1.30"));
    }

    [Fact]
    public void Too_many_wrong_codes_lock_the_ip_out_and_drop_its_code()
    {
        var s = Create(maxAttempts: 3);
        var code = CodeFor(s, "10.0.0.7");
        var wrong = code == "000000" ? "111111" : "000000";

        Assert.IsType<PairingValidationResult.InvalidCode>(s.Validate(wrong, "10.0.0.7"));
        Assert.IsType<PairingValidationResult.InvalidCode>(s.Validate(wrong, "10.0.0.7"));
        Assert.IsType<PairingValidationResult.InvalidCode>(s.Validate(wrong, "10.0.0.7")); // third failure locks

        // Even the right code is refused now, and asking for a new one is too.
        Assert.IsType<PairingValidationResult.Locked>(s.Validate(code, "10.0.0.7"));
        Assert.IsType<PairingRequestResult.Locked>(s.RequestCode("10.0.0.7"));
        // Other devices are unaffected.
        Assert.IsType<PairingRequestResult.Issued>(s.RequestCode("10.0.0.8"));
    }

    [Fact]
    public void Network_codes_are_capped_but_the_panel_is_never_blocked()
    {
        var s = Create();
        for (var i = 1; i <= 5; i++) CodeFor(s, $"10.0.1.{i}");

        Assert.IsType<PairingRequestResult.Busy>(s.RequestCode("10.0.1.6"));
        var panel = s.IssuePanelCode();
        Assert.IsType<PairingValidationResult.Ok>(s.Validate(panel.Code, "10.0.1.6"));
    }
}
