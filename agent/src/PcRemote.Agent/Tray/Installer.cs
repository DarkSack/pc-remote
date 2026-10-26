using System.Diagnostics;
using System.Runtime.Versioning;
using System.Windows.Forms;
using Microsoft.Win32;

namespace PcRemote.Agent.Tray;

/// <summary>
/// The downloaded SackDesk.exe installs itself on first run, per user and without
/// admin (like VS Code's user setup): a copy in %LOCALAPPDATA%\Programs\SackDesk,
/// a Start menu entry, start with Windows, and an entry in Settings › Apps that
/// runs <c>--uninstall</c>. Running a newer download over it is the update.
/// The firewall is left to Windows, which asks the first time the agent listens;
/// if it stays blocked, the phone goes through the relay.
/// </summary>
[SupportedOSPlatform("windows")]
internal static class Installer
{
    /// <summary>The running agent quits when this is set: an install over it or an uninstall.</summary>
    public const string ExitSignalName = @"Local\SackDesk.Agent.Exit";

    private const string DisplayName = "SackDesk";
    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\SackDesk";
    private static readonly string LocalAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);

    public static string Dir { get; } = Path.Combine(LocalAppData, "Programs", "SackDesk");
    public static string Exe { get; } = Path.Combine(Dir, "SackDesk.exe");
    private static string StartMenu => Environment.GetFolderPath(Environment.SpecialFolder.Programs);
    private static string Shortcut => Path.Combine(StartMenu, DisplayName + ".lnk");

    /// <summary>
    /// Installs when this is the published exe running from anywhere but the install
    /// folder. True means the installed copy is starting and this process should exit.
    /// </summary>
    public static bool InstallIfNeeded()
    {
        var self = Environment.ProcessPath!;
        // `dotnet run` and the tests run the DLL build: only the single-file exe installs.
        if (typeof(Installer).Assembly.Location.Length != 0 || SamePath(self, Exe)) return false;
        try
        {
            StopRunningAgent();
            Directory.CreateDirectory(Dir);
            CopyOver(self, Exe);
            CreateShortcut(Shortcut, Exe);
            Register();
            Autostart.Set(true, Exe);
            Process.Start(new ProcessStartInfo(Exe) { UseShellExecute = false, WorkingDirectory = Dir })?.Dispose();
            return true;
        }
        catch (Exception ex)
        {
            MessageBox.Show($"No se pudo instalar: {ex.Message}\n\nSackDesk se abre desde donde está.",
                DisplayName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return false;
        }
    }

    /// <summary><c>--uninstall</c> from Settings › Apps; with <c>--quiet</c> (winget) it asks nothing and keeps the data.</summary>
    public static int Uninstall(bool quiet)
    {
        var purge = false;
        if (!quiet)
        {
            if (MessageBox.Show("¿Desinstalar SackDesk de este PC?", DisplayName,
                    MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return 1;
            purge = MessageBox.Show(
                "¿Borrar también los móviles emparejados y la configuración?\n\n" +
                "Si los conservas, al reinstalar no hará falta volver a emparejar.",
                DisplayName, MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) == DialogResult.Yes;
        }

        StopRunningAgent();
        Autostart.Set(false);
        File.Delete(Shortcut);
        Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, throwOnMissingSubKey: false);
        if (purge)
        {
            try { Directory.Delete(Path.Combine(LocalAppData, "SackDesk"), recursive: true); }
            catch (DirectoryNotFoundException) { }
        }
        // A running exe cannot delete itself: cmd waits until this process is gone, then removes the folder.
        Process.Start(new ProcessStartInfo("cmd.exe", $"/c ping 127.0.0.1 -n 3 >nul & rmdir /s /q \"{Dir}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WorkingDirectory = Path.GetTempPath(),
        })?.Dispose();
        return 0;
    }

    /// <summary>
    /// Asks this session's agent to quit (it holds the exe, the ports and the database)
    /// and waits until its single-instance mutex is free. One that ignores the
    /// request (a version without the exit signal) is killed.
    /// </summary>
    private static void StopRunningAgent()
    {
        if (EventWaitHandle.TryOpenExisting(ExitSignalName, out var exit))
            using (exit) exit.Set();

        using var running = new Mutex(false, Program.SingleInstanceMutex);
        try
        {
            if (running.WaitOne(10_000)) { running.ReleaseMutex(); return; }
        }
        catch (AbandonedMutexException) { running.ReleaseMutex(); return; } // it died holding it: free now

        var session = Process.GetCurrentProcess().SessionId;
        foreach (var p in Process.GetProcessesByName("SackDesk"))
            using (p)
                if (p.Id != Environment.ProcessId && p.SessionId == session)
                    try { p.Kill(); p.WaitForExit(5_000); }
                    catch (InvalidOperationException) { /* already gone */ }
    }

    /// <summary>Right after the old agent exits, Windows can hold its exe for a moment.</summary>
    private static void CopyOver(string from, string to)
    {
        for (var attempt = 0; ; attempt++)
            try { File.Copy(from, to, overwrite: true); return; }
            catch (IOException) when (attempt < 20) { Thread.Sleep(250); }
    }

    private static void CreateShortcut(string path, string target)
    {
        dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell")!)!;
        var link = shell.CreateShortcut(path);
        link.TargetPath = target;
        link.WorkingDirectory = Path.GetDirectoryName(target);
        link.Description = "Controla este PC desde el móvil";
        link.Save();
    }

    private static void Register()
    {
        using var key = Registry.CurrentUser.CreateSubKey(UninstallKey);
        key.SetValue("DisplayName", DisplayName);
        key.SetValue("DisplayVersion", typeof(Installer).Assembly.GetName().Version!.ToString(3));
        key.SetValue("Publisher", "Sackito");
        key.SetValue("DisplayIcon", Exe);
        key.SetValue("InstallLocation", Dir);
        key.SetValue("UninstallString", $"\"{Exe}\" --uninstall");
        key.SetValue("QuietUninstallString", $"\"{Exe}\" --uninstall --quiet");
        key.SetValue("EstimatedSize", (int)(new FileInfo(Exe).Length / 1024), RegistryValueKind.DWord);
        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
    }

    private static bool SamePath(string a, string b) =>
        string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.OrdinalIgnoreCase);
}
