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
| **Windows** — Enum + operations | `EnumWindows`, `GetWindowText`, `GetWindowThreadProcessId`, `SetForegroundWindow` (precedido de un `SendInput` vacío y, si Windows se niega, `AttachThreadInput`), `ShowWindow`, `PostMessage(WM_CLOSE)` | `user32.dll` |
| **Processes** — List | `Process.GetProcesses()` (ordenado por working set) | .NET |
| ⏳ **Processes** — CPU% | `\Process(*)\% Processor Time` delta + `Environment.ProcessorCount` | Perf Counter |
| **Processes** — Kill | `Process.Kill(entireProcessTree: true)` | .NET |
| **Applications** — Enum Win32 | Registro `HKLM/HKCU\...\Uninstall`, solo si `DisplayIcon` apunta a un `.exe` que existe | .NET registry |
| **Applications** — Enum menú Inicio + Store + accesos URL | `SHGetKnownFolderItem(FOLDERID_AppsFolder)` → `IEnumShellItems`, nombre y AppID con `IShellItem.GetDisplayName` (en un hilo STA). Si nunca ha funcionado, se leen los `.lnk` de las carpetas `Programs` | `shell32.dll` (COM) |
| **Applications** — Vigilancia | `FileSystemWatcher` sobre las carpetas `Programs` + reescaneo cada 2 min | .NET |
| **Applications** — Launch registro | `Process.Start(ProcessStartInfo{ UseShellExecute=true, WorkingDirectory=carpeta del .exe })` | .NET |
| **Applications** — Launch resto | `explorer.exe shell:AppsFolder\<AppID>` | Shell |
| **Applications** — Iconos | `SHCreateItemFromParsingName` + `IShellItemImageFactory.GetImage` → PNG | `shell32.dll` (COM) |
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
