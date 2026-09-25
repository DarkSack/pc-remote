using PcRemote.Core.Activity;
using PcRemote.Modules.Clipboard;
using PcRemote.Modules.Files;
using PcRemote.Modules.Terminal;

namespace PcRemote.Tests;

public class ClipboardHistoryTests
{
    [Fact]
    public void Newest_first_and_copying_again_moves_to_the_top()
    {
        var h = new ClipboardHistory();
        h.Add(ClipEntry.ForText("uno"));
        h.Add(ClipEntry.ForText("dos"));
        var firstId = h.List(0, 10, null).Last().Id;

        h.Add(ClipEntry.ForText("uno"));

        var list = h.List(0, 10, null);
        Assert.Equal(2, list.Count);
        Assert.Equal("uno", list[0].Text);
        Assert.Equal(firstId, list[0].Id); // same entry, same id
    }

    [Fact]
    public void Oldest_entries_are_evicted_beyond_the_cap()
    {
        var h = new ClipboardHistory();
        for (var i = 0; i < ClipboardHistory.MaxItems + 5; i++) h.Add(ClipEntry.ForText("t" + i));
        Assert.Equal(ClipboardHistory.MaxItems, h.Count);
        Assert.Equal("t" + (ClipboardHistory.MaxItems + 4), h.List(0, 1, null)[0].Text);
    }

    [Fact]
    public void Delete_clear_and_version()
    {
        var h = new ClipboardHistory();
        h.Add(ClipEntry.ForText("a"));
        h.Add(ClipEntry.ForFiles(new[] { @"C:\x.txt" }));
        var v = h.Version;
        var id = h.List(0, 10, "text")[0].Id;

        Assert.True(h.Delete(id));
        Assert.False(h.Delete(id));
        Assert.True(h.Version > v);
        Assert.Single(h.List(0, 10, null));

        h.Clear();
        Assert.Equal(0, h.Count);
    }
}

public class TerminalCdTests
{
    private static readonly string Home = Path.GetTempPath();

    [Fact]
    public void Cd_to_an_existing_folder_moves()
    {
        var sub = Directory.CreateDirectory(Path.Combine(Home, "pcr-cd-" + Guid.NewGuid().ToString("N")));
        try
        {
            Assert.True(TerminalModule.TryCmdChangeDirectory($"cd {sub.Name}", Home, out var dir, out var err));
            Assert.Null(err);
            Assert.Equal(sub.FullName.TrimEnd('\\'), dir!.TrimEnd('\\'));
        }
        finally { sub.Delete(); }
    }

    [Fact]
    public void Cd_to_a_missing_folder_is_an_error_not_a_move()
    {
        Assert.True(TerminalModule.TryCmdChangeDirectory("cd /d Z:\\does\\not\\exist", Home, out var dir, out var err));
        Assert.Null(dir);
        Assert.NotNull(err);
    }

    [Theory]
    [InlineData("dir")]
    [InlineData("cdx")]
    [InlineData("echo cd foo")]
    public void Other_commands_are_left_to_cmd(string command)
    {
        Assert.False(TerminalModule.TryCmdChangeDirectory(command, Home, out _, out _));
    }
}

public class FilesPathTests
{
    [Theory]
    [InlineData(@"\\server\share\file.txt")]
    [InlineData("//server/share")]
    [InlineData(@"relative\path")]
    [InlineData("")]
    public void Network_and_relative_paths_are_refused(string path)
    {
        Assert.Throws<ArgumentException>(() => FilesModule.LocalPath(path));
    }

    [Fact]
    public void Local_paths_are_normalised()
    {
        Assert.Equal(@"C:\Users\x", FilesModule.LocalPath(@"C:\Users\y\..\x"));
    }

    [Theory]
    [InlineData(@"C:\a\setup.exe", true)]
    [InlineData(@"C:\a\run.PS1", true)]
    [InlineData(@"C:\a\link.lnk", true)]
    [InlineData(@"C:\a\photo.jpg", false)]
    [InlineData(@"C:\a\notes.txt", false)]
    [InlineData(@"C:\a\README", false)]
    public void Programs_and_scripts_count_as_executable(string path, bool expected)
    {
        Assert.Equal(expected, FilesModule.IsExecutable(path));
    }
}

public class ActivityTests
{
    [Fact]
    public void Alerts_respect_the_cooldown()
    {
        var log = new ActivityLog();
        log.Alert("ram", "RAM alta", null, TimeSpan.FromMinutes(5));
        log.Alert("ram", "RAM alta", null, TimeSpan.FromMinutes(5));
        Assert.Single(log.Snapshot(100), e => e.Kind == ActivityKinds.Alert);
    }

    [Fact]
    public void Known_commands_get_a_readable_label()
    {
        Assert.Equal("PC bloqueado", ActivityLabels.For("system", "lock"));
        Assert.Equal("foo.bar", ActivityLabels.For("foo", "bar"));
    }
}
