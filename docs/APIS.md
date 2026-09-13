# APIs de Windows por módulo

## Cheat sheet

Lo que usa cada módulo **hoy**. Las filas marcadas ⏳ son APIs previstas para
funciones que aún no existen.

| Módulo | API principal | Origen |
|---|---|---|
| **System** — Shutdown / Restart / Logoff | `ExitWindowsEx` + `AdjustTokenPrivileges(SE_SHUTDOWN_NAME)` | Win32 `user32.dll` + `advapi32.dll` |
| **System** — Sleep / Hibernate | `SetSuspendState(hibernate, force, wakeup)` | `powrprof.dll` |
| **System** — Lock | `LockWorkStation` | `user32.dll` |
| **Input** — Mouse / Keyboard | `SendInput(INPUT_MOUSE|INPUT_KEYBOARD)`, `SetCursorPos`, `GetCursorPos` | `user32.dll` (P/Invoke) |
| **Clipboard** — Read/Write | `System.Windows.Forms.Clipboard` en un hilo STA | .NET |
| **Clipboard** — Watch | Sondeo de `GetClipboardSequenceNumber` cada 500 ms; solo lee el texto si cambió | `user32.dll` |
| **Media** — Play/Pause/Next/Previous + metadata | `GlobalSystemMediaTransportControlsSessionManager` (SMTC) | WinRT |
| **Media** — Volume | `IAudioEndpointVolume` vía `NAudio.CoreAudioApi` (se libera en cada llamada) | NAudio 3 |
| **Windows** — Enum + operations | `EnumWindows`, `GetWindowText`, `GetWindowThreadProcessId`, `SetForegroundWindow`, `ShowWindow`, `PostMessage(WM_CLOSE)` | `user32.dll` |
| **Processes** — List | `Process.GetProcesses()` (ordenado por working set) | .NET |
| ⏳ **Processes** — CPU% | `\Process(*)\% Processor Time` delta + `Environment.ProcessorCount` | Perf Counter |
| **Processes** — Kill | `Process.Kill(entireProcessTree: true)` | .NET |
| **Applications** — Enum Win32 | Registro `HKLM/HKCU\...\Uninstall`, solo si `DisplayIcon` apunta a un `.exe` que existe | .NET registry |
| **Applications** — Enum Start Menu | `.lnk` en las carpetas `Programs` del menú Inicio (usuario y común) | `System.IO` |
| **Applications** — Enum UWP | `Get-StartApps` en PowerShell, salida forzada a UTF-8 | PowerShell |
| **Applications** — Launch Win32 | `Process.Start(ProcessStartInfo{ UseShellExecute=true })` | .NET |
| **Applications** — Launch UWP | `explorer.exe shell:AppsFolder\<AppID>` | Shell |
| **SystemInfo** — CPU% | `\Processor(_Total)\% Processor Time` | Perf Counter |
| **SystemInfo** — RAM | `GlobalMemoryStatusEx` | `kernel32.dll` |
| ⏳ **SystemInfo** — GPU% | `\GPU Engine(*)\Utilization Percentage` (excluir engine 3D idle) | Perf Counter |
| ⏳ **SystemInfo** — Disk | `\LogicalDisk(*)\% Free Space` + `\LogicalDisk(*)\Disk Bytes/sec` | Perf Counter |
| ⏳ **SystemInfo** — Network | `\Network Interface(*)\Bytes Total/sec` | Perf Counter |
| **SystemInfo** — Static info | WMI `Win32_OperatingSystem`, registro (`ProcessorNameString`), `Environment` | .NET + WMI |

## Qué requiere qué

**Puro C#/.NET 10**:
- Kestrel WebSocket server, Serilog, SQLite (`Microsoft.Data.Sqlite`),
  procesos, WMI (`System.Management`), Ed25519 (`NSec.Cryptography`).

**Requiere `net10.0-windows10.0.19041.0` + WinRT**:
- SMTC (control multimedia).

**Requiere P/Invoke a Win32**:
- `SendInput`, `EnumWindows`, `GetClipboardSequenceNumber`, `ExitWindowsEx`
  con `AdjustTokenPrivileges`, `LockWorkStation`, `SetForegroundWindow`, `PostMessage`.

**Requiere Perf Counters** (`System.Diagnostics.PerformanceCounter`):
- CPU hoy; GPU, disco, red y CPU por proceso cuando se implementen.

## Elevación

Nada de lo implementado requiere elevación.

Requerirían admin (no implementado):
- Kill de procesos de otros usuarios / sistema.
- Enum de ventanas de otros usuarios.
- Manipular servicios de Windows.
- Cambiar volumen de sesiones de audio de otros usuarios.

Estas se resuelven con un helper separado `PcRemote.Elevated.exe` lanzado
con `runas` (UAC visible al usuario).
