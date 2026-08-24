# APIs de Windows por módulo

## Cheat sheet

| Módulo | API principal | Origen |
|---|---|---|
| **System** — Shutdown / Restart / Logoff | `ExitWindowsEx` + `AdjustTokenPrivileges(SE_SHUTDOWN_NAME)` | Win32 `user32.dll` + `advapi32.dll` |
| **System** — Sleep / Hibernate | `SetSuspendState(hibernate, force, wakeup)` | `powrprof.dll` |
| **System** — Lock | `LockWorkStation` | `user32.dll` |
| **Input** — Mouse / Keyboard | `SendInput(INPUT_MOUSE|INPUT_KEYBOARD)` | `user32.dll` (P/Invoke) |
| **Clipboard** — Read/Write | `System.Windows.Forms.Clipboard` o WinRT `DataPackage` | .NET / WinRT |
| **Clipboard** — Watch | `AddClipboardFormatListener` + `WM_CLIPBOARDUPDATE` (message-only window) | Win32 |
| **Media** — Play/Pause/Next/Previous + metadata | `Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager` (SMTC) | WinRT (`Microsoft.Windows.SDK.NET`) |
| **Media** — Volume | `IAudioEndpointVolume` (COM Core Audio) via `NAudio.CoreAudioApi` | NAudio (nuget) |
| **Windows** — Enum + operations | `EnumWindows`, `GetWindowText`, `GetWindowThreadProcessId`, `SetForegroundWindow`, `ShowWindow`, `SetWindowPos` | `user32.dll` |
| **Processes** — List | `Process.GetProcesses()` | .NET |
| **Processes** — CPU% | `\Process(*)\% Processor Time` delta + `Environment.ProcessorCount` | Perf Counter |
| **Processes** — Kill | `Process.Kill(entireProcessTree: true)` | .NET |
| **Applications** — Enum Win32 | Registry `HKLM/HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall` | .NET registry |
| **Applications** — Enum Start Menu | `.lnk` en `%ProgramData%\Microsoft\Windows\Start Menu\Programs` + `%AppData%\...` | `System.IO` + `IWshShortcut` |
| **Applications** — Enum UWP | `PackageManager.FindPackagesForUser(currentUserSid)` | `Windows.Management.Deployment` (WinRT) |
| **Applications** — Launch Win32 | `Process.Start(ProcessStartInfo{ UseShellExecute=true })` | .NET |
| **Applications** — Launch UWP | `IApplicationActivationManager::ActivateApplication(aumid)` | COM `shell32.dll` |
| **SystemInfo** — CPU% | `\Processor(_Total)\% Processor Time` | Perf Counter |
| **SystemInfo** — RAM | `GlobalMemoryStatusEx` | `kernel32.dll` |
| **SystemInfo** — GPU% | `\GPU Engine(*)\Utilization Percentage` (excluir engine 3D idle) | Perf Counter |
| **SystemInfo** — Disk | `\LogicalDisk(*)\% Free Space` + `\LogicalDisk(*)\Disk Bytes/sec` | Perf Counter |
| **SystemInfo** — Network | `\Network Interface(*)\Bytes Total/sec` | Perf Counter |
| **SystemInfo** — Static info | `Environment.OSVersion` + WMI `Win32_ComputerSystem`, `Win32_Processor` | .NET + WMI |

## Qué requiere qué

**Puro C#/.NET 8**:
- Kestrel WebSocket server, Serilog, SQLite (`Microsoft.Data.Sqlite`),
  procesos, WMI (`System.Management`), pairing crypto (`NSec.Cryptography` para Ed25519).

**Requiere `net8.0-windows10.0.19041.0` + WinRT** (`Microsoft.Windows.SDK.NET.Ref`):
- SMTC (media control), `PackageManager` (UWP enum), `Windows.Storage`.

**Requiere P/Invoke a Win32**:
- `SendInput`, `EnumWindows`, `AddClipboardFormatListener`, `ExitWindowsEx`
  con `AdjustTokenPrivileges`, `LockWorkStation`, `SetForegroundWindow`.

**Requiere Perf Counters** (`System.Diagnostics.PerformanceCounter`):
- CPU, RAM, GPU, Disk, Network, Process CPU%.

## Elevación

Ninguna operación del MVP (shutdown, restart, sleep, hibernate, lock,
logoff, systeminfo) requiere elevación.

Requieren admin (Fase 4+):
- Kill de procesos de otros usuarios / sistema.
- Enum de ventanas de otros usuarios.
- Manipular servicios de Windows.
- Cambiar volumen de sesiones de audio de otros usuarios.

Estas se resuelven con un helper separado `PcRemote.Elevated.exe` lanzado
con `runas` (UAC visible al usuario).
