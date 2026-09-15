using PcRemote.Modules.Applications;
using PcRemote.Modules.Applications.Sources;
using PcRemote.Modules.Clipboard;
using PcRemote.Modules.Input.Win32;

namespace PcRemote.Tests;

public class AppCatalogMergeTests
{
    private static AppEntry App(string id, string name, string source = "startmenu") => new(id, name, source, "x");

    [Theory]
    [InlineData("Uninstall")]
    [InlineData("Uninstall IncrediBuild")]
    [InlineData("Desinstalar Genshin Impact")]
    [InlineData("Natron Uninstall")]
    public void Uninstaller_shortcuts_are_dropped(string name)
    {
        Assert.Empty(AppCatalog.Merge(new[] { App("a", name) }));
    }

    [Theory]
    [InlineData("Revo Uninstaller")]
    [InlineData("Uninstaller Pro Tools")]
    [InlineData("Visual Studio Code")]
    public void Apps_that_merely_mention_uninstalling_are_kept(string name)
    {
        Assert.Single(AppCatalog.Merge(new[] { App("a", name) }));
    }

    [Fact]
    public void First_source_wins_on_the_same_name_ignoring_case_and_spaces()
    {
        var merged = AppCatalog.Merge(new[]
        {
            App("startmenu:x", "Spotify"),
            App("registry:y", " spotify ", "registry"),
        });
        Assert.Equal("startmenu:x", Assert.Single(merged).Id);
    }

    [Fact]
    public void Duplicate_ids_are_dropped_even_with_different_names()
    {
        // The phone keys its list by id; a repeated key crashes the LazyColumn.
        var merged = AppCatalog.Merge(new[] { App("registry:{GUID}", "Tool"), App("registry:{GUID}", "Tool (x86)") });
        Assert.Single(merged);
    }

    [Fact]
    public void Result_is_sorted_by_name_and_skips_blank_names()
    {
        var merged = AppCatalog.Merge(new[] { App("1", "zeta"), App("2", "  "), App("3", "Alpha"), App("4", "beta") });
        Assert.Equal(new[] { "Alpha", "beta", "zeta" }, merged.Select(a => a.Name));
    }
}

public class AppsFolderSourceTests
{
    // Integration test against this Windows install: the agent only runs on Windows,
    // and the COM interop is exactly the part that can break.
    [Fact]
    public void Lists_start_menu_apps_with_launchable_ids()
    {
        var apps = AppsFolderSource.Enumerate().ToList();

        Assert.NotEmpty(apps);
        Assert.All(apps, a =>
        {
            Assert.False(string.IsNullOrWhiteSpace(a.Name));
            Assert.StartsWith(AppsFolderSource.LaunchPrefix, a.Launch);
            Assert.StartsWith(a.Source + ":", a.Id);
        });
        // Every Windows 10/11 install has Store apps (Settings, Calculator…).
        Assert.Contains(apps, a => a.Source == "uwp");
    }
}

public class VirtualKeysTests
{
    [Fact]
    public void Resolves_a_combo_in_order()
    {
        Assert.True(VirtualKeys.TryResolve("ctrl+shift+esc", out var vks));
        Assert.Equal(new ushort[] { 0x11, 0x10, 0x1B }, vks);
    }

    [Theory]
    [InlineData("a", 0x41)]
    [InlineData("Z", 0x5A)]
    [InlineData("7", 0x37)]
    [InlineData("F5", 0x74)]
    [InlineData(" win ", 0x5B)]
    public void Resolves_single_keys(string expr, int vk)
    {
        Assert.True(VirtualKeys.TryResolve(expr, out var vks));
        Assert.Equal((ushort)vk, Assert.Single(vks));
    }

    [Theory]
    [InlineData("ctrl+nope")]
    [InlineData("ñ")]
    [InlineData("f13")]
    public void Unknown_keys_fail_the_whole_expression(string expr)
    {
        Assert.False(VirtualKeys.TryResolve(expr, out var vks));
        Assert.Empty(vks);
    }
}

public class ClipboardTruncateTests
{
    [Fact]
    public void Never_splits_a_surrogate_pair()
    {
        var text = "abc😀"; // the emoji is two UTF-16 chars at indexes 3 and 4
        Assert.Equal("abc", ClipboardModule.SafeTruncate(text, 4));
    }

    [Fact]
    public void Leaves_short_text_alone_and_cuts_plain_text_exactly()
    {
        Assert.Equal("hola", ClipboardModule.SafeTruncate("hola", 10));
        Assert.Equal("hol", ClipboardModule.SafeTruncate("hola", 3));
    }
}
